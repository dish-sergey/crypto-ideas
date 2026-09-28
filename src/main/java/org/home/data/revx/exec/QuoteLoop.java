package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цикл котирования. Та же формула, что в симуляции ({@link Quoter}), те же
 * параметры из того же конфига — иначе сверять предсказание с фактом
 * бессмысленно.
 *
 * Цель этапа — НЕ прибыль, а измерение: размеры минимальные, весь риск ограничен
 * пределами из {@link ExecLimits}.
 *
 * ⚠️ Прежняя формулировка цели — «доля исполнений, которые предсказала модель:
 * 104 в сутки и 6.0% с отрицательным захватом (док. 91 §3)» — снята как
 * устаревшая дважды. Числа считались для настройки, которой больше нет, а главное
 * — установлено, что **обход не предсказывает число сделок живого бота на
 * конкретном окне и не может** (CLAUDE.md): совпадают все маргинальные
 * распределения, но не совместное с рынком. Сверка живого с моделью делается
 * {@code --revx-fill-check} и {@code --revx-replay}; что именно предсказано для
 * текущих шести ботов — записано в задаче A39 ДО запуска опыта.
 *
 * Пять правил, без которых цикл опасен:
 *
 * 1. **Стартует остановленным.** Котирование начинается только по явной команде.
 *    Перезапуск после падения не должен сам возобновлять торговлю — сначала
 *    человек смотрит, почему упало.
 * 2. **Гейт справедливой цены соблюдается буквально.** Опора сломана или курс
 *    ненадёжен — заявки снимаются целиком, а не «оставим пока висеть».
 * 3. **Замена создаёт НОВУЮ заявку.** Площадка возвращает новый
 *    {@code venue_order_id}, и состояние читается из ответа, а не помнится
 *    (проверено живой заявкой 27.08.2026).
 * 4. **Истина о заявках — у площадки, а не в нашей памяти.** Ошибка на замене
 *    не значит, что замены не было: 30.08.2026 ответ 422 пришёл на уже
 *    выполненную замену, и наследник шесть часов простоял в книге без хозяина.
 *    Поэтому список активных заявок сверяется раз в минуту и после каждого
 *    отказа — см. {@link #reconcile(String)}.
 * 5. **Что МОЖНО ПОСТАВИТЬ — по {@code available}; что У НАС ЕСТЬ — по
 *    {@code total}.** Средства под стоящей заявкой в остатке видны, а поставить
 *    на них нельзя ({@link #affordable}) — отсюда правило про {@code available}.
 *    ⚠️ Но 13.09.2026 это правило применили не туда: по нему считался и
 *    знаменатель реестра владения, а владение — вопрос не «на что поставить», а
 *    «сколько монеты есть». Монета в чужой заявке при этом исчезала из счёта и
 *    вычиталась второй раз как чужая претензия, и целые лоты становились
 *    невидимыми для {@code /claim} — см. {@link #accountBase()} и задачу A42.
 */
public final class QuoteLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(QuoteLoop.class);

    /**
     * Потолок паузы после отказа площадки.
     *
     * С 06.09.2026 — 15 с вместо 60. Шестьдесят секунд задумывались против
     * долбёжки неизвестной ошибкой, но на живых шести ботах превратились в
     * другое: заявка выпадала из книги на минуту с лишним, и всё это время бот
     * не торговал, хотя причина отказа (залп замен выше лимита площадки)
     * проходила за секунды.
     *
     * ⚠️ Короткая пауза законна ТОЛЬКО вместе с потолком замен на тик
     * ({@link #chooseReplaceSlot}). Без него бот возвращался бы в тот же залп,
     * снова ловил 429 и удлинял наказание — короткий отход по сути означал бы
     * «повторять чаще». Сначала перестали пробивать лимит, потом сократили паузу.
     */
    private static final long MAX_PLACE_BACKOFF_MS = 15_000L;
    /**
     * Сколько заявка считается «свежей». Список активных отстаёт от постановки,
     * и вывод «её там нет, значит исполнилась» на свежем id даёт дубль.
     */
    private static final long ADOPT_GRACE_MS = 5_000L;
    /**
     * Какую долю потолка обязана покрывать своя касса, чтобы бот пустили в работу.
     *
     * Полное покрытие требовать нельзя: 04.09.2026 счёта хватало на $46.21 при
     * сумме потолков трёх ботов $49.13, и строгий порог не пустил бы третьего
     * из-за 11% нехватки. Ноль тоже не годится: бот с четвертью денег — уже
     * другой бот, и сравнивать его с остальными нечестно.
     */
    private static final double MIN_FUNDING_SHARE = 0.80;

    /** Как часто сверяться с книгой площадки, даже когда всё выглядит хорошо. */
    private static final long RECONCILE_PERIOD_MS = 60_000L;
    /** Сколько расхождение позиции должно продержаться, чтобы стать тревогой. */
    private static final long MISMATCH_GRACE_MS = 30_000L;
    /** И как часто повторять тревогу, если оно не уходит. */
    private static final long MISMATCH_REPEAT_MS = 300_000L;

    /** Ключи долгоживущего состояния в журнале. */
    private static final String STATE_POSITION = "position";
    private static final String STATE_CASH = "cash";
    private static final String STATE_SEED = "seed_position";
    /**
     * База для КАССЫ. До 04.09.2026 её не было вовсе — в расчёт подставлялся
     * ноль, и любая передача денег выглядела торговым результатом: захват
     * прибылью, освобождение убытком. Бот B после /release отчитался об убытке
     * −6.77 USDC, которого не было.
     */
    private static final String STATE_SEED_CASH = "seed_cash";

    private static final Pattern VENUE_ID =
            Pattern.compile("\"venue_order_id\"\\s*:\\s*\"([^\"]+)\"");
    /**
     * Из остатков нужны ОБА числа, и путать их нельзя:
     * {@code available} — на что можно поставить новую заявку,
     * {@code total} — сколько мы на самом деле держим (включая зарезервированное
     * под уже стоящей заявкой).
     *
     * Скос считается от ПОЗИЦИИ, то есть от {@code total}: пока аск стоит, его
     * объём зарезервирован и из {@code available} исчезает, а позицией быть не
     * перестаёт. Если брать {@code available}, инвентарь занижается ровно на
     * размер стоящей заявки, скос выходит слабее задуманного, и живое перестаёт
     * совпадать с симуляцией — где инвентарь всегда полный.
     */
    private static final Pattern BALANCE = Pattern.compile(
            "\\{\"currency\":\"([A-Z0-9]+)\",\"available\":\"([0-9.]+)\",\"reserved\":\"([0-9.]+)\",\"total\":\"([0-9.]+)\"");

    /** Стоящая заявка: id площадки и цена, по которой она стоит. */
    private static final class Resting {
        String venueId;
        double price;
        double size;
        /** Когда id стал текущим. Пока свежий, отсутствие в списке активных — не факт. */
        long sinceMs;
        /** До какого момента не пробовать ставить снова (после отказа площадки). */
        long blockedUntilMs;
        int failures;
        long fundsWarnedMs;

        /**
         * Идентификатор заявки, о которой площадка сказала «частично исполнена».
         *
         * ⚠️ Хранится ИМЕННО ИДЕНТИФИКАТОР, а не булев флаг. Флаг пришлось бы
         * гасить руками во всех местах, где слот меняет заявку, и одно забытое
         * место заморозило бы слот навсегда. Сравнение с {@link #venueId} гасит
         * его само: другая заявка — другая жизнь.
         */
        String partialId;
        long partialSinceMs;
        double partialFilled;

        /** Стоит ли в слоте частично исполненная заявка. Её заменить нельзя. */
        boolean partial() {
            return partialId != null && partialId.equals(venueId);
        }

        /**
         * Этап 3, §3.3: снятая нами заявка, судьба которой ещё не окончательна.
         * Пока она здесь, слот занят: новую не ставим, ресурс под ней держим.
         */
        String questioned;
        Side qSide;
        double qSize;
        double qPrice;
        long qSinceMs;
        long qAskedMs;
        long qRecancelMs;
    }

    public record Stats(long placements, long replaces, long cancels, long fills,
                        double inventory, double lastFair, String state, String pausedReason,
                        long ticks, long ticksAtCap, long partials, int partialsNow,
                        long ticksEmpty, long[] lotHist, long volGated) {
    }

    private final Venue client;
    private final Clock clock;
    private final FairSource stand;
    private final ExecJournal journal;
    private final Quoter quoter;
    /**
     * Откуда берутся цены. У бота A это сам {@link Quoter}, у бота B — он же
     * под надстройками (пол по себестоимости, растущий шаг). Порог
     * перевыставления по-прежнему у quoter: он про геометрию, а не про цены.
     */
    private final org.home.data.revx.sim.QuotePolicy policy;
    private final Quoter.Params params;
    private final String symbol;
    private final String base;
    /** Валюта котировки: касса тоже общая на всех шестерых и тоже делится реестром. */
    private final String quote;
    private final long periodMs;
    /** Минимальный номинал заявки на площадке: ниже него постановка отвергается. */
    private final double minNotional;
    /** Метка бота: она же владелец заявки, см. {@link BotTag}. */
    private final BotTag tag;
    /** Вести ли позицию по своим сделкам вместо остатков аккаунта (два бота). */
    private final boolean ownPosition;
    private volatile double ownCash;
    /** Затравка позиции при первом запуске: отрицательная = взять из остатка аккаунта. */
    private final double positionSeed;
    /** Позиция, принятая за точку отсчёта P&L: с неё началась жизнь этого бота. */
    private volatile double seedPosition;
    /** Та же точка отсчёта для кассы: передачи двигают её вместе с остатком. */
    private volatile double seedCash;
    private volatile long mismatchSinceMs;
    private volatile long mismatchWarnedMs;

    private final AtomicBoolean quoting = new AtomicBoolean(false);
    private volatile boolean alive = true;

    /**
     * Уровни котировки на каждой стороне, от ближнего к рынку к дальнему.
     *
     * ⚠️ Один уровень — это ЧАСТНЫЙ СЛУЧАЙ, а не отдельный режим: при
     * {@code levels == 1} и {@code levelStep == 0} всё поведение прежнее, и это
     * проверяется повтором живого журнала (бот A обязан по-прежнему давать
     * 99.92% совпавших котировок). Живые боты работают именно так.
     *
     * Сетка измерена на стенде 05.09.2026 и даёт примерно вдвое больше
     * одиночной котировки на трёх разных окнах (ровном, падающем и полном
     * цикле), причём при РАВНОМ капитале и с меньшим остатком инвентаря.
     */
    private final java.util.List<Resting> bids = new java.util.ArrayList<>();
    private final java.util.List<Resting> asks = new java.util.ArrayList<>();

    /**
     * УРОВЕНЬ СЕТКИ ПО ИДЕНТИФИКАТОРУ ЗАЯВКИ.
     *
     * Нужен, чтобы разложить доход по уровням: до 11.09.2026 он был известен
     * только целиком по боту, и вопрос «зарабатывает ли дальний уровень или
     * числится» ответа не имел.
     *
     * Почему отдельная карта, а не поиск по слотам в момент проводки: к этому
     * моменту слот уже может держать НАСЛЕДНИКА (замена создаёт новый
     * идентификатор) или быть пустым, и связь с исполнившейся заявкой теряется.
     * Карта помнит её с постановки.
     *
     * Размер ограничен: заявок за сутки десятки тысяч, а нужны только живые и
     * недавно умершие.
     */
    private final java.util.Map<String, Integer> levelByOrder =
            new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Integer> eldest) {
                    return size() > 4096;
                }
            };

    private void rememberLevel(Side side, Resting resting) {
        if (resting.venueId != null) {
            levelByOrder.put(resting.venueId, levelOf(side, resting));
            myOrders.put(resting.venueId, side);
        }
    }

    /**
     * Свои заявки последнего времени — по ним лента опознаёт свои сделки, не дожидаясь,
     * пока читатель спросит заявку и узнает её хозяина (этап 3, 28.09.2026).
     */
    private final java.util.Map<String, Side> myOrders =
            new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Side> eldest) {
                    return size() > 4096;
                }
            };

    private int levelOf(String venueId) {
        Integer v = venueId == null ? null : levelByOrder.get(venueId);
        return v == null ? -1 : v;
    }
    private final int levels;
    /** Расстояние между уровнями в долях цены. 0 при одном уровне. */
    private final double levelStep;
    /**
     * Кому из уровней достаётся инвентарь под продажу первым.
     *
     * ⚠️ Выбор НЕ безобидный, и первая реализация выбрала неверно. Раздача
     * «внутренним вперёд» означает: держим один лот — аск стоит на ближнем
     * уровне, то есть по САМОЙ ХУДШЕЙ из доступных цен. Покупки при этом идут на
     * всех уровнях, включая дальние и выгодные. Перекос систематический:
     * покупаем в среднем лучше базового отступа, а продаём почти всегда по нему.
     * На измерении 05.09.2026 это дало −0.3090 на падении против −0.1724 у
     * одиночной котировки — хуже всех.
     *
     * При {@code false} инвентарь достаётся ДАЛЬНИМ уровням первыми: продаём
     * настолько далеко от рынка, насколько хватает лотов.
     */
    private final boolean innerFirst;
    /**
     * Потолок постановок для ПРОГНОЗА. Ноль — брать боевой из {@link ExecLimits}.
     *
     * ⚠️ Нужен потому, что при исчерпании лимита бот не пропускает постановку, а
     * ВЫКЛЮЧАЕТСЯ совсем. Трёхуровневый режим жжёт постановки втрое быстрее
     * одноуровневого, упирается в 300 посреди прогона и глохнет — и тогда стенд
     * меряет скорость выгорания лимита, а не экономику конструкции. На живом
     * боте переопределять НЕЛЬЗЯ: там лимит защищает общий суточный потолок
     * аккаунта в 1000 постановок.
     */
    private int placementCapOverride;
    /** Сколько тиков каждый уровень реально стоял в книге. Диагностика сетки. */
    private long[] bidTicks;
    private long[] askTicks;
    private long levelTicks;


    /**
     * ФАКТИЧЕСКОЕ расстояние котировки от справедливой цены, в базисных пунктах.
     *
     * <h2>Зачем мерить то, что задано настройкой</h2>
     *
     * Настройка задаёт отступ, но до книги доезжает не он. По дороге его
     * двигают скос по инвентарю, динамический отступ по ширине опоры, два
     * раздвижения (по пошлине и по бюджету постановок) и зажим по книге и тику.
     * Сумма этих поправок и есть то, что решает поток: κ = 0.385 на базисный
     * пункт, то есть ошибка в один пункт стоит трети исполнений.
     *
     * Замер 10.09.2026 показал, что сравнивать стенд с живым по ЧИСЛУ СДЕЛОК
     * бесполезно — так было потрачено восемь гипотез подряд. У живого бота
     * заявка в моменты исполнений стояла в 8.67 б.п. от собственной
     * справедливой цены при настройке 12, и именно это давало ему 50 отдельных
     * возможностей против 34 у ровных 12 б.п. Значит сравнивать надо здесь, до
     * всякой модели исполнения.
     */
    private final java.util.List<Double> bidOffsetsBp = new java.util.ArrayList<>();
    private final java.util.List<Double> askOffsetsBp = new java.util.ArrayList<>();
    /** То же, но по цене ФАКТИЧЕСКИ СТОЯЩЕЙ заявки, а не по цели. */
    private final java.util.List<Double> bidRestingBp = new java.util.ArrayList<>();
    private final java.util.List<Double> askRestingBp = new java.util.ArrayList<>();

    public String effectiveOffset() {
        if (bidOffsetsBp.isEmpty() && askOffsetsBp.isEmpty()) {
            return "эффективный отступ: нет котировок";
        }
        return "ЦЕЛЬ, б.п.: бид " + pct(bidOffsetsBp) + "; аск " + pct(askOffsetsBp)
                + System.lineSeparator() + "    В КНИГЕ, б.п.: бид " + pct(bidRestingBp)
                + "; аск " + pct(askRestingBp);
    }

    private static String pct(java.util.List<Double> v) {
        if (v.isEmpty()) {
            return "нет";
        }
        java.util.List<Double> s = new java.util.ArrayList<>(v);
        java.util.Collections.sort(s);
        return String.format(java.util.Locale.ROOT,
                "медиана %.2f (10%% %.2f, 25%% %.2f, 75%% %.2f, 90%% %.2f), тиков %d",
                s.get(s.size() / 2), s.get(s.size() / 10), s.get(s.size() / 4),
                s.get(s.size() * 3 / 4), s.get(s.size() * 9 / 10), s.size());
    }
    public String levelPresence() {
        if (levelTicks == 0) {
            return "нет данных";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "  уровень %d: бид %.0f%%, аск %.0f%%%n",
                    i, 100.0 * bidTicks[i] / levelTicks, 100.0 * askTicks[i] / levelTicks));
        }
        return sb.toString();
    }

    public void placementCap(int cap) {
        this.placementCapOverride = cap;
    }

    /**
     * СТЕНД: поднять денежные пределы пропорционально размеру лота.
     *
     * Боевые пределы записаны в абсолютных долларах под лот $1. Прогон с лотом
     * большего размера обязан получить их в том же масштабе, иначе он меряет не
     * рынок, а наш предохранитель — и делает это молча, ровными нулями.
     *
     * ⚠️ Живой исполнитель не вызывает этот метод НИКОГДА. Пределы для реальных
     * денег остаются в {@link ExecLimits}, то есть за пересборкой и выкаткой.
     */
    /**
     * СТЕНД: включить динамический отступ вместо бинарного гейта по опоре.
     *
     * @param k       какую долю отступа отдаём неопределённости цены (1/3 —
     *                разумная отправная точка; 0 выключает, и гейт остаётся
     *                бинарным, как на живых ботах)
     * @param maxPct  выше этой ширины опоры не котируем ни на каком отступе:
     *                при сломанной опоре (1.18% у ETH 19.08.2026) заявка ушла бы
     *                на абсурдное расстояние и всё равно не исполнялась бы
     */
    public void dynamicOffset(double k, double maxPct) {
        this.spreadToOffset = k;
        this.spreadToOffsetMaxPct = maxPct;
    }

    /**
     * ПОВТОР: брать раздвижение отступа из записи, а не из живого ведра.
     *
     * Ведро постановок — общее живое состояние всех ботов аккаунта, и истории оно не хранит:
     * восстановить, каким было давление в прошлую среду, нельзя ниоткуда.
     * Поэтому живой пишет применённую долю в каждый тик, а повтор подставляет
     * её обратно. Без этого повтор котирует по нераздвинутому отступу — разница
     * в треть процента, но её хватает, чтобы цена легла на соседний тик и
     * сверка показала провал при исправном боте (10.09.2026, бот A: 0.62%).
     */
    public void replayPressure(java.util.function.LongToDoubleFunction source) {
        this.pressureFromRecord = source;
    }

    private java.util.function.LongToDoubleFunction pressureFromRecord;

    /** Причина паузы — именно ширина опоры, а не что-то другое из гейтов. */
    private static boolean isReferenceSpreadReason(String reason) {
        return reason != null && reason.startsWith("опорная книга широка");
    }

    /**
     * С какого расстояния заявка считается припаркованной, а не торгующей.
     *
     * Три процента против пяти, на которые уводит {@link Park}: запас нужен,
     * потому что между парковкой и стартом цена успевает сдвинуться, и заявка,
     * оставленная на пяти процентах, к моменту сверки может оказаться на
     * четырёх. Слишком низкий порог опаснее слишком высокого — он оставил бы в
     * книге настоящую торгующую заявку у неработающего бота.
     */
    static final double PARKED_MIN_PCT = 0.03;

    /** Припаркована ли заявка по этой цене относительно последней надёжной. */
    static boolean isParked(double price, double trustedFair, double minPct) {
        if (!(trustedFair > 0) || !(price > 0)) {
            return false;                 // цены не знаем — считаем торгующей
        }
        return Math.abs(price - trustedFair) / trustedFair >= minPct;
    }

    /**
     * ⚠️ На СТАРТЕ своей цены ещё нет: сверка идёт раньше первого тика, и
     * {@code lastTrustedFair} с {@code lastFair} оба нули. Первая же выкатка
     * 07.09.2026 на этом и споткнулась — бот аккуратно припарковал три заявки
     * при остановке и тут же снял их при запуске, потому что «цены не знаем —
     * считаем торгующей». Поэтому здесь третьим шагом спрашиваем стенд: он
     * отвечает сразу, ещё до того, как цикл сделает первый оборот.
     */
    private boolean isParked(double price) {
        double reference = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(reference > 0)) {
            try {
                StandReader.Fair fresh = stand.latest(base, 300_000);
                if (fresh != null && fresh.price() > 0) {
                    reference = fresh.price();
                }
            } catch (Exception e) {
                log.warn("стенд не отдал цену для разбора припаркованных: {}", e.getMessage());
            }
        }
        return isParked(price, reference, PARKED_MIN_PCT);
    }

    /**
     * Раздвигать ли отступ вместо остановки котирования.
     *
     * ⚠️ Раздвигается ТОЛЬКО ширина опорной книги. Остальные гейты остаются
     * бинарными, и это не осторожность, а разная природа: ширина книги — мера
     * неопределённости цены, её можно оплатить расстоянием. А «опора разошлась с
     * рынком» или «разброс implied выше порога» означают, что справедливая цена
     * посчитана НЕВЕРНО, и отодвигать заявку от неверной цены бессмысленно —
     * дальше от чего? У живой ENA за сутки таких тиков 1076 из 17081, то есть
     * различать эти случаи приходится на самом деле, а не теоретически.
     *
     * @param k       доля отступа, отдаваемая неопределённости; 0 выключает
     * @param maxPct  выше этой ширины опоры не котируем ни на каком отступе
     */
    static boolean gateWidens(boolean quotable, String reason, double price,
                              double referenceSpreadPct, double k, double maxPct) {
        return k > 0 && !quotable && price > 0
                && isReferenceSpreadReason(reason)
                && referenceSpreadPct <= maxPct;
    }

    /**
     * Отодвинуть обе стороны на неопределённость цены.
     *
     * Середина книги шириной s известна с точностью ±s/2, поэтому заявка уходит
     * на s/2, делённое на долю k, которую мы готовы отдать неопределённости: при
     * k = 1/3 половина спреда опоры стоит трёх таких же долей отступа.
     */
    static Quoter.Quotes widenForSpread(Quoter.Quotes target, double price,
                                        double referenceSpreadPct, double k) {
        if (!(k > 0) || !(price > 0) || !(referenceSpreadPct > 0)) {
            return target;
        }
        double extra = price * (referenceSpreadPct / 100.0) / 2 / k;
        return new Quoter.Quotes(
                target.bid() == null ? null : target.bid() - extra,
                target.ask() == null ? null : target.ask() + extra);
    }

    /**
     * Отодвинуть обе стороны пропорционально дефициту постановок.
     *
     * Раздвигается ИМЕННО ОТСТУП, а не абсолютная величина: заявка отходит на
     * долю своего же расстояния до справедливой цены. Иначе тонкая пара с
     * отступом в 30 б.п. и биткойн с десятью получили бы одинаковую прибавку в
     * долларах, то есть совершенно разное наказание.
     */
    /**
     * ПЕРВЫЙ ЛОТ ПОКУПАЕМ АГРЕССИВНЕЕ ОСТАЛЬНЫХ.
     *
     * <h2>Зачем</h2>
     *
     * Первый лот стоит дороже своего спреда: пока его нет, НЕТ И АСКА, то есть
     * простаивает половина конструкции. Замер по живым журналам 11.09.2026 за
     * пять суток: с нулевым инвентарём бот A живёт 15.7% времени, B — 28.8%,
     * C — 44.0%; с одним лотом и меньше — 54%, 70% и 72%. Цель скоса при этом
     * 2.1 лота, то есть до неё боты почти не доходят.
     *
     * Скос и так подтягивает бид (у A с 12 до 7.7 б.п. при пустом инвентаре),
     * но линейно и потому слабо. Этот ключ делает подтягивание НЕЛИНЕЙНЫМ:
     * пока своих лотов меньше одного, бид ставится на заданный тесный отступ.
     * При κ = 0.385 на базисный пункт сужение с 7.7 до 4 б.п. примерно удваивает
     * темп набора.
     *
     * ⚠️ ОПЫТ, по умолчанию ВЫКЛЮЧЕН (0). Живых ботов не трогает, пока ключ не
     * задан: конструкция меняет риск на сделку, и включать её можно только
     * замера на обоих типах окон.
     */
    /**
     * ПРИДВИЖЕНИЕ АСКА ПО ВОЗРАСТУ ПОЗИЦИИ.
     *
     * <h2>Зачем</h2>
     *
     * Хвост убытка создаётся ВОЗРАСТОМ позиции, а не её размером: скос обусловлен
     * размером и по построению возраста не видит (A28). Здесь отступ на продаже
     * линейно стягивается к справедливой цене по мере старения лота.
     *
     * Замер по ленте 12.09.2026 объясняет, почему это дешевле тейкера: аск НА
     * справедливой цене снимают за 5 минут у BTC (317 съёмов в сутки) и за 12–14
     * у ETH и SOL, а стоит это ≈5 б.п. отбора против 16.4 б.п. тейкерской пошлины
     * (полуспред 7.4 + комиссия 9). Разгрузиться придвижением втрое дешевле, чем
     * ударить по рынку (A31).
     *
     * <h2>Правило</h2>
     *
     * <pre>
     *   доля = max(floor, 1 − возраст / tau)
     *   аск  = fair + доля · (аск_исходный − fair)
     * </pre>
     *
     * То есть за {@code tau} минут аск проходит весь путь от своего отступа до
     * {@code floor} долей от него. Бид не трогается: набирать быстрее оттого, что
     * позиция стара, незачем.
     *
     * ⚠️ Ключи задают ОСЬ ОПЫТА, а не боевую настройку: по умолчанию tau = 0 и
     * поведение в точности прежнее. В бой — только после обхода на двух окнах.
     */
    private Quoter.Quotes decayAsk(Quoter.Quotes target, double price, double offset) {
        if (ASK_DECAY_MIN <= 0 || lotAges.isEmpty()
                || !target.hasAsk() || !(target.ask() > 0) || !(price > 0)) {
            return target;
        }
        double ageMin = (clock.now() - lotAges.peekFirst()[0]) / 60_000.0 - ASK_DECAY_AFTER;
        if (ageMin <= 0) {
            return target;
        }
        double share = Math.max(ASK_DECAY_FLOOR, 1 - ageMin / ASK_DECAY_MIN);
        if (share >= 1) {
            return target;
        }
        double ask = price + share * (target.ask() - price);
        return new Quoter.Quotes(target.bid(), ask);
    }

    /** За сколько минут аск доходит от своего отступа до пола; 0 — выключено. */
    private static final double ASK_DECAY_MIN =
            Double.parseDouble(System.getProperty("revx.sim.ask-decay-min", "0"));

    /**
     * ⚠️ СКОЛЬКО МИНУТ НЕ ТРОГАТЬ АСК ВООБЩЕ. Ключ добавлен после первого
     * прогона 12.09.2026, где затухание считалось от момента открытия позиции и
     * проиграло во всех клетках: BTC на свежем окне −259% годовых против −15%
     * без затухания, доля ленты выросла с 17% до 22%.
     *
     * Причина в том, ЧТО с чем сравнивается. По ленте придвижение сопоставлялось
     * с тейкерским выходом (−16.4 б.п.) и выходило втрое дешевле. Но в обходе
     * оно сравнивается с «просто подождать на своём отступе», а большинство
     * лотов разгружается само: продавая на справедливой цене вместо +δ, мы
     * теряем захват на КАЖДОЙ разгрузке, а не только на застрявших.
     *
     * Замысел был другой — «если не разгрузились за N минут». Поэтому затухание
     * должно включаться ПОСЛЕ порога возраста, а не с нуля.
     */
    private static final double ASK_DECAY_AFTER =
            Double.parseDouble(System.getProperty("revx.sim.ask-decay-after", "0"));

    /** Доля отступа, ниже которой аск не придвигается. 0 — вплоть до справедливой цены. */
    private static final double ASK_DECAY_FLOOR =
            Double.parseDouble(System.getProperty("revx.sim.ask-decay-floor", "0"));

    /**
     * Очередь партий FIFO: {@code {отметка покупки, остаток количества}}.
     *
     * Нужна ровно для одного — возраста САМОГО СТАРОГО удерживаемого лота.
     * Полноценный учёт партий живёт в {@link FifoLedger}, но он строится по
     * журналу и котировщику недоступен на горячем пути.
     */
    private final Deque<double[]> lotAges = new ArrayDeque<>();


    /**
     * ГЕЙТ ПО РЕЖИМУ РЫНКА: не котировать, пока волатильность выше обычной.
     *
     * <h2>Что за находка</h2>
     *
     * Разложение живых кругов по волатильности часа (задача A32, четверо суток
     * 08–12.09) дало у всех трёх ботов с полной историей одно и то же: в тихом
     * режиме захват на круг <b>+0.2…+20.7 б.п.</b>, в информативном
     * <b>−14.9…−65.4</b>. И вторая половина, из-за которой это не лечится само:
     * информативные часы занимают 8–14% времени, а кругов в них закрывается
     * 23–28%. Волатильность двигает справедливую цену, цена доходит до наших
     * заявок, исполнений становится больше — <b>бот сам стягивает торговлю в тот
     * режим, где теряет</b>.
     *
     * Это единственная найденная за два дня вещь, которая не является разменом
     * на кривой «меньше инвентаря — лучше сделка, меньше сделок»: тихий и
     * информативный режимы различаются ЗНАКОМ, а не ценой.
     *
     * <h2>Правило</h2>
     *
     * {@code σ} за короткое окно против {@code σ} за длинное, обе по минутным
     * приращениям справедливой цены. Гейт срабатывает, когда короткая выше
     * длинной в {@code VOL_GATE_MULT} раз.
     *
     * Режимы: {@code bid} — снимается только бид (перестаём НАБИРАТЬ, но
     * разгружаться можно), {@code both} — обе стороны. Первый ближе к замеру:
     * терялись мы на позициях, набранных в шуме, а не на продажах.
     *
     * ⚠️ Порог относительный, а не абсолютный: у пар разная σ, и константа
     * означала бы у BTC одно, а у SOL другое. По той же причине длинное окно
     * должно быть заметно длиннее короткого — иначе всплеск попадёт в обе
     * половины и отношение не вырастет.
     *
     * ⚠️ Ключ задаёт ось опыта, а не боевую настройку. По умолчанию выключено.
     */
    private Quoter.Quotes volGate(Quoter.Quotes target) {
        if (VOL_GATE_MULT <= 0 || volMinutes.size() < VOL_LONG_MIN / 2) {
            return target;
        }
        double shortSd = sdOfLast(VOL_SHORT_MIN);
        double longSd = sdOfLast(VOL_LONG_MIN);
        if (!(longSd > 0) || shortSd < VOL_GATE_MULT * longSd) {
            return target;
        }
        volGated++;
        return "both".equals(VOL_GATE_SIDE)
                ? new Quoter.Quotes(null, null)
                : new Quoter.Quotes(null, target.ask());
    }

    /** СКО минутных приращений по последним {@code minutes} отсчётам, б.п. */
    private double sdOfLast(int minutes) {
        int n = volMinutes.size();
        if (n < 3) {
            return 0;
        }
        double[] values = new double[n];
        int i = 0;
        for (double[] p : volMinutes) {
            values[i++] = p[1];
        }
        int from = Math.max(1, n - minutes);
        int count = 0;
        double sum = 0;
        double sum2 = 0;
        for (int k = from; k < n; k++) {
            if (!(values[k - 1] > 0)) {
                continue;
            }
            double r = (values[k] - values[k - 1]) / values[k - 1] * 10_000;
            sum += r;
            sum2 += r * r;
            count++;
        }
        if (count < 3) {
            return 0;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, sum2 / count - mean * mean));
    }

    /** Один отсчёт справедливой цены на минуту — вход для гейта по режиму. */
    private void rememberVol(double fair) {
        if (VOL_GATE_MULT <= 0 || !(fair > 0)) {
            return;
        }
        long minute = clock.now() / 60_000;
        if (!volMinutes.isEmpty() && (long) volMinutes.peekLast()[0] == minute) {
            return;
        }
        volMinutes.addLast(new double[]{minute, fair});
        while (volMinutes.size() > VOL_LONG_MIN + 2) {
            volMinutes.pollFirst();
        }
    }

    /** Во сколько раз короткая σ должна превысить длинную; 0 — гейт выключен. */
    private static final double VOL_GATE_MULT =
            Double.parseDouble(System.getProperty("revx.sim.vol-gate-mult", "0"));

    /** Что снимать при срабатывании: {@code bid} (только набор) или {@code both}. */
    private static final String VOL_GATE_SIDE =
            System.getProperty("revx.sim.vol-gate-side", "bid");

    /** Короткое окно волатильности, минут. */
    private static final int VOL_SHORT_MIN =
            Integer.getInteger("revx.sim.vol-short-min", 15);

    /** Длинное окно волатильности, минут. */
    private static final int VOL_LONG_MIN =
            Integer.getInteger("revx.sim.vol-long-min", 360);

    private final Deque<double[]> volMinutes = new ArrayDeque<>();

    /** Сколько тиков гейт держал сторону снятой — печатается прогоном. */
    private long volGated;
    private Quoter.Quotes pullFirstLot(Quoter.Quotes target, double price) {
        double firstLotBp = Double.parseDouble(
                System.getProperty("revx.sim.first-lot-offset", "0"));
        if (!(firstLotBp > 0) || !target.hasBid() || !(price > 0)) {
            return target;
        }
        if (inventory >= params.size() - 1e-15) {
            return target;            // свой лот уже есть, аск стоит — гнаться незачем
        }
        double pulled = price * (1 - firstLotBp / 10_000);
        // Только ПОДТЯГИВАЕМ: если скос уже увёл бид ближе, не отодвигаем назад.
        return new Quoter.Quotes(Math.max(target.bid(), pulled), target.ask());
    }

    static Quoter.Quotes widenForBudget(Quoter.Quotes target, double price, double pressure) {
        if (!(pressure > 0) || !(price > 0)) {
            return target;
        }
        double m = BUDGET_WIDEN * Math.min(1.0, pressure);
        return new Quoter.Quotes(
                target.bid() == null ? null : target.bid() - (price - target.bid()) * m,
                target.ask() == null ? null : target.ask() + (target.ask() - price) * m);
    }

    public void scaleLimitsForLot(double lotUsd) {
        double k = Math.max(1.0, lotUsd);
        this.maxOrderNotional = ExecLimits.MAX_ORDER_NOTIONAL_USDC * k;
        this.maxExposure = ExecLimits.MAX_TOTAL_EXPOSURE_USDC * k;
        // Порог остановки по убытку задан под лот $1, и забыть про него мало: он
        // не режет отдельную заявку, а ГЛУШИТ котирование до конца суток. При
        // лоте $30 доллар убытка — 3% лота, то есть шум одной сделки. Первая
        // лестница лотов 06.09.2026 словила 195 таких остановок, все на верхних
        // ступенях, и занизила их ровно там, где мерилась ёмкость.
        this.maxTradingLoss = ExecLimits.MAX_TRADING_LOSS_USDC * k;
    }

    private int placementCap() {
        return placementCapOverride > 0
                ? placementCapOverride : ExecLimits.maxPlacementsPerDay(tag.id());
    }

    /**
     * ТОЧЕЧНЫЙ СНИМОК окна тиков для сличения двух путей.
     *
     * След действий отвечает на вопрос «что сделали», а расхождение живёт в
     * «почему решили так»: какой размер посчитан, сколько осталось в пуле, что
     * лежало в слоте. Включается парой ключей
     * {@code -Drevx.dumpFrom} / {@code -Drevx.dumpTo} в миллисекундах.
     */
    private static final long DUMP_FROM = Long.getLong("revx.dumpFrom", 0);
    private static final long DUMP_TO = Long.getLong("revx.dumpTo", 0);

    private boolean dumping() {
        long now = clock.now();
        return DUMP_TO > 0 && now >= DUMP_FROM && now <= DUMP_TO;
    }

    private void dump(String line) {
        if (dumping()) {
            log.warn("СНИМОК {} {}", clock.now(), line);
        }
    }

    /**
     * СЛЕД ДЕЙСТВИЙ для построчного сличения двух путей расчёта.
     *
     * Включается ключом {@code -Drevx.trace=<файл>}. Точка одна на оба пути —
     * встроенный и модульный проходят через {@link #place}, {@link #replace} и
     * {@link #cancel}, — поэтому следы прямо сопоставимы.
     *
     * ⚠️ Номер уровня обязателен. Без него видно, что цены разошлись, но не
     * видно, КАКОЙ слот остался без заявки, а именно это и было причиной.
     *
     * ⚠️ Только для стенда: на живом боте это лишняя запись на горячем пути.
     */
    private static final String TRACE_PATH = System.getProperty("revx.trace", "");
    private static java.io.PrintWriter traceOut;

    private int levelOf(Side side, Resting r) {
        var list = side == Side.BUY ? bids : asks;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == r) {
                return i;
            }
        }
        return -1;
    }

    private void trace(String kind, Side side, Resting r, double price, double size) {
        if (TRACE_PATH.isEmpty()) {
            return;
        }
        synchronized (QuoteLoop.class) {
            try {
                if (traceOut == null) {
                    traceOut = new java.io.PrintWriter(
                            new java.io.FileWriter(TRACE_PATH, true), true);
                }
                traceOut.printf(java.util.Locale.ROOT, "%d %s %s ур%d %.10f %.10f%n",
                        clock.now(), kind, side, levelOf(side, r), price, size);
            } catch (Exception ignored) {
                // След вспомогательный: его отсутствие не повод ронять прогон.
            }
        }
    }

    /** Сменная расстановка; {@code null} — работает встроенный путь. */
    private org.home.data.revx.layout.OrderLayout layout;

    /** Сменный способ приведения книги; {@code null} — работает встроенный путь. */
    private org.home.data.revx.place.Placer placer;

    public void modules(org.home.data.revx.layout.OrderLayout layout,
                        org.home.data.revx.place.Placer placer) {
        this.layout = layout;
        this.placer = placer;
    }

    /**
     * Путь через сменные модули: расстановка решает «как должно быть»,
     * приведение — «что сделать сейчас», цикл выполняет и охраняет.
     *
     * ⚠️ Предохранители остаются ЗДЕСЬ и проверяются заново. Расстановка о
     * пределах знает, но соблюдение их не её забота: ТЗ §6 держит охрану в коде
     * исполнителя, и сменная версия не должна получить возможность её обойти.
     *
     * ⚠️ Раздача капитала тоже здесь. Учесть возврат резерва при замене можно,
     * только зная резерв КАЖДОГО слота, а это состояние размещения, не рынка.
     */
    private void applyLayout(StandReader.Fair fair) {
        var state = new org.home.data.revx.layout.MarketState(
                fair.price(), fair.referenceSpreadPct(), fair.bookBid(), fair.bookAsk(),
                inventory,
                alloc != null ? Math.max(0, alloc.own(tag.id(), quote)) : quoteBalance,
                efficiency.open(params.driftGateEr()) ? drift() : 0,
                budgetPressure, params.quoteStep(), dust > 0 ? dust * 2 : 0,
                minNotional, maxOrderNotional, maxExposure);

        var desired = layout.layout(state);
        // ⚠️ Порядок обхода — часть логики: действия выполняются подряд, и
        // каждое видит остаток средств после предыдущего.
        var current = new java.util.ArrayList<org.home.data.revx.place.RestingOrder>();
        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            current.add(restingOf(Side.BUY, i));
            current.add(restingOf(Side.SELL, i));
        }
        var plan = placer.plan(desired, current, clock.now());
        if (dumping()) {
            dump("ХОЧУ: " + desired);
            dump("СЛОТЫ: " + current);
            dump("ПЛАН: " + plan);
        }

        double buyCash = alloc != null
                ? Math.max(0, alloc.own(tag.id(), quote) - lockedQuoteEff()) : Double.MAX_VALUE;
        double sellPool = Math.max(0, inventory - lockedBaseEff());
        double buyRoom = buyRoom();
        for (var r : current) {
            Resting slot = r.side() == Side.BUY ? bids.get(r.level()) : asks.get(r.level());
            var want = findWanted(desired, r.side(), r.level());
            var action = findAction(plan, r.side(), r.level());
            double pool = r.side() == Side.BUY
                    ? (want != null && want.price() > 0
                            ? Math.min(buyCash / want.price(), buyRoom) : 0)
                    : sellPool;
            dump(String.format(java.util.Locale.ROOT,
                    "МОД %s ур%d: слот=%s цена=%.8f размер=%.6f | хочу=%s | план=%s | пул=%.6f",
                    r.side(), r.level(), slot.venueId == null ? "пуст" : "есть",
                    slot.price, slot.size,
                    want == null ? "нет" : String.format(java.util.Locale.ROOT, "%.8f", want.price()),
                    action == null ? "ничего" : action.kind(), pool));
            double took = settle(r.side(), slot, want, action, pool);
            if (r.side() == Side.BUY) {
                buyCash = Math.max(0, buyCash - took * (want == null ? 0 : want.price()));
                buyRoom = Math.max(0, buyRoom - took);
            } else {
                sellPool = Math.max(0, sellPool - took);
            }
        }
    }

    /**
     * Слот глазами расстановщика.
     *
     * ⚠️ Частично исполненная заявка отдаётся как заблокированная НАВСЕГДА
     * ({@code Long.MAX_VALUE}), и это не хитрость, а точный смысл поля: «до
     * этого момента заявку трогать нельзя». Заменить её нельзя вовсе — площадка
     * требует состояния {@code NEW}. Расстановщик от этого перестаёт тратить на
     * неё единственный жетон замены и отдаёт его слоту, который двигать МОЖНО.
     * Постановку и отмену это не задевает: их {@link #settle} решает сам, по
     * состоянию слота, а не по плану.
     */
    /**
     * Остаток до потолка — ОБЩИЙ на все биды, как касса и как запас у продаж.
     *
     * ⚠️ До 27.09.2026 каждый бид резал себя остатком сам ({@link #sizeFor}), и
     * при 6 лотах из 7 все три уровня получали по лоту: пробой всех трёх на
     * резком падении дал бы 9 лотов при потолке 7. Живьём e и f выходили на
     * 7.09–7.14 лота 23.09; с уровнями 12/15/20 пробой всех трёх стал реальнее.
     */
    /**
     * Порядок раздачи по бидам: {@code true} — дальние первыми, независимо от
     * {@code innerFirst} продаж (вопрос владельца 27.09.2026). Системное свойство,
     * как {@code revx.sim.level-growth}; пишется в машинную часть {@code boot}.
     */
    static final boolean BUY_FAR_FIRST = Boolean.getBoolean("revx.exec.buy-far-first");

    private double buyRoom() {
        return frozenUnwind ? 0 : Math.max(0, params.inventoryCap() - inventory);
    }

    private org.home.data.revx.place.RestingOrder restingOf(Side side, int level) {
        Resting r = side == Side.BUY ? bids.get(level) : asks.get(level);
        long blocked = r.partial() ? Long.MAX_VALUE : r.blockedUntilMs;
        return new org.home.data.revx.place.RestingOrder(side, level, r.venueId, r.price, r.size,
                blocked);
    }

    /**
     * Один слот: посчитать размер, занять пул и — если план велит — действовать.
     *
     * ⚠️ ПУЛ ЗАНИМАЕТСЯ ДАЖЕ БЕЗ ДЕЙСТВИЯ. Слот со своей заявкой продолжает
     * держать ресурс: отдать его дальнему уровню значило бы продать один лот
     * дважды. Держит он ВНОВЬ ВЫЧИСЛЕННЫЙ размер, а не старую заявку.
     */
    private double settle(Side side, Resting slot, org.home.data.revx.layout.DesiredOrder want,
                          org.home.data.revx.place.Action action, double pool) {
        if (want == null) {
            if (slot.venueId != null) {
                cancel(side, slot, "сторона не котируется");
            }
            return 0;
        }
        double price = want.price();
        double size = Math.min(Math.min(want.size(), sizeFor(side, price, slot)),
                Math.max(0, pool));
        double notional = size * price;
        if (size <= 0 || notional < minNotional) {
            if (slot.venueId != null) {
                cancel(side, slot, "нечем котировать эту сторону");
            }
            warnNoFunds(side, slot);
            return 0;
        }
        if (!(notional > 0 && notional <= maxOrderNotional)) {
            log.error("заявка {} на {} USDC превышает предел {} — не ставлю",
                    side, notional, maxOrderNotional);
            journal.event("limit_blocked", side + " нотионал " + notional);
            return 0;
        }
        if (exposure() + notional > maxExposure) {
            log.error("экспозиция превысила бы предел {} — не ставлю", maxExposure);
            journal.event("limit_blocked", "экспозиция");
            return 0;
        }
        if (slot.questioned != null) {
            return slot.qSize;                // заявка под вопросом: слот занят
        }
        if (clock.now() < slot.blockedUntilMs) {
            return slot.venueId == null ? 0 : slot.size;
        }
        // ⚠️ ПУСТОТА СЛОТА ПРОВЕРЯЕТСЯ СЕЙЧАС, а не по плану.
        //
        // План строится один раз в начале тика, а слот может опустеть ПОСРЕДИ
        // него: заявка исполняется, и цикл узнаёт об этом при обходе. Встроенный
        // путь перечитывал состояние на каждом уровне и потому ставил новую
        // заявку сразу; модульный работал по устаревшему снимку и пропускал
        // постановку до следующего тика.
        //
        // Найдено снимком 08.09.2026: в списке слотов у нулевого уровня заявка
        // ЕСТЬ, а к моменту исполнения её уже нет — при полностью совпадающих
        // ценах, размерах и пулах. Пять предыдущих заходов искали расхождение в
        // арифметике, а оно было во времени наблюдения.
        //
        // Пропускать такую постановку особенно вредно потому, что потолком тика
        // постановки НЕ ограничены: пустой слот дороже устаревшей цены, и ждать
        // следующего тика незачем.
        if (slot.venueId == null) {
            place(side, slot, price, size);
        } else if (action != null
                && action.kind() == org.home.data.revx.place.Action.Kind.REPLACE) {
            replace(side, slot, price, size);
        }
        return size;
    }

    private static org.home.data.revx.layout.DesiredOrder findWanted(
            java.util.List<org.home.data.revx.layout.DesiredOrder> desired, Side side, int level) {
        for (var d : desired) {
            if (d.side() == side && d.level() == level) {
                return d;
            }
        }
        return null;
    }

    private static org.home.data.revx.place.Action findAction(
            java.util.List<org.home.data.revx.place.Action> plan, Side side, int level) {
        for (var a : plan) {
            if (a.side() == side && a.level() == level) {
                return a;
            }
        }
        return null;
    }

    /**
     * Общее ведро постановок на весь аккаунт. Без него бот работает по прежнему
     * неподвижному потолку из {@link ExecLimits} — так ходит стенд, которому
     * делить ни с кем нечего.
     */
    public void placementBudget(PlacementBudget budget) {
        this.budget = budget;
        if (budget != null) {
            // Считать сразу, а не ждать первой минуты: перезапуск при пустом
            // ведре иначе успел бы отторговать минуту в тесном отступе — ровно
            // тогда, когда бюджета нет.
            budgetPressure = budget.state(tag.id(), clock.now()).pressure();
        }
    }

    /**
     * Во сколько раз раздвинуть отступ при полностью выбранном бюджете.
     *
     * Смысл в том, что дефицит постановок лечится не остановкой, а РЕДКОСТЬЮ
     * исполнений: чем дальше заявка от цены, тем реже она исполняется, а
     * постановка тратится только после исполнения. Вдвое — потому что закон
     * прихода λ(δ) = A·e^{−κδ} при κ ≈ 0.3…0.45 на б.п. даёт на удвоении
     * десятибазисного отступа примерно двадцатикратное падение частоты; этого
     * с запасом хватает, чтобы расход упал ниже пополнения.
     *
     * Заявки при этом продолжают жить: перестановка идёт через PUT, у которого
     * суточного лимита нет вовсе.
     */
    private static final double BUDGET_WIDEN = 1.0;

    private volatile double inventory;
    private volatile double baseAvailable;
    /**
     * Остаток базовой валюты на СЧЁТЕ целиком — {@code available + reserved} из
     * ответа площадки. Это и есть знаменатель реестра владения: монета, лежащая
     * в выставленной заявке, никуда не делась, она просто заперта.
     *
     * ⚠️ Не путать с {@link #baseAvailable}: на неё можно ПОСТАВИТЬ, а эта —
     * сколько монеты есть. Подмена одного другим стоила нам ничейных лотов —
     * см. {@link #accountBase()}.
     */
    private volatile double baseTotalAccount = Double.NaN;
    private volatile double baseReserved;
    /** Резерв USDC у площадки — для разрыва «резерв минус видимые покупки» (этап 3, §3.6). */
    private volatile double quoteReserved;
    private volatile double quoteBalance;
    private volatile double quoteTotal;
    private volatile double lastFair;
    /**
     * Последняя справедливая цена, которой гейт ДОВЕРЯЛ. От неё считается отвод
     * заявок: текущей цене в момент закрытия гейта доверия нет по определению.
     */
    private volatile double lastTrustedFair;
    /** Относительное расстояние отвода; ≤ 0 — отвод выключен, работает отмена. */
    private final double parkDistance;
    /** Реестр владения инвентарём: счёт у площадки один на всех ботов. */
    private final AllocRegistry alloc;
    /**
     * Сторож свипов; { null} — реакция выключена.
     *
     * ⚠️ Выключен по умолчанию и включается только явной настройкой: это
     * изменение поведения на живых деньгах, а измерен эффект на записи.
     */
    private SweepWatch sweeps;

    /**
     * Какую сторону двигает реакция на свип.
     *
     * {@code BOTH} — сдвигается ОПОРА, то есть обе стороны разом (как было).
     * {@code BID} и {@code ASK} двигают одну, оставляя вторую там, где её
     * поставила бы логика без правки, — это ось сравнения, а не настройка среды.
     */
    public enum SweepSide { BOTH, BID, ASK }

    private SweepSide sweepSide = SweepSide.BOTH;



    /** Сдвиг опоры по перевесу тейкеров Бинанса (док. 179). */


    private FlowWatch flow;
    private final java.util.ArrayDeque<long[]> fairHistory = new java.util.ArrayDeque<>();
    private final org.home.data.revx.sim.EfficiencyRatio efficiency;
    private volatile String pausedReason = "не запущен";
    private long placements;
    private long replaces;
    private long cancels;
    private long fills;
    /**
     * Сколько заявок за жизнь процесса застали частично исполненными.
     *
     * До перехода на лот $3 это было тождественно нулём: все 390 живых сделок за
     * сутки 08.09.2026 шли ровно в один лот, потому что принты крупнее нашей
     * заявки. С лотом $3 частичные исполнения стали обычным делом, и число
     * важно само по себе — оно решает, стоит ли усложнять поведение.
     */
    private long partials;
    private long minuteStartMs;
    private long lastReconcileMs;
    private int replacesThisMinute;
    /** Кому досталась единственная замена этого тика (см. chooseReplaceSlot). */
    private Side replaceSlotSide;
    private int replaceSlotLevel = -1;

    /**
     * Действующие денежные пределы. По умолчанию — боевые из {@link ExecLimits}.
     *
     * ⚠️ Переопределяются ТОЛЬКО стендом, через {@link #scaleLimitsForLot}, и
     * только вверх пропорционально лоту. Причина в том, что боевые пределы
     * заданы в АБСОЛЮТНЫХ долларах под лот $1: заявка не больше $10, экспозиция
     * не больше $40. Прогон с лотом $10 упирается в них раньше, чем в рынок, и
     * измеряет не ёмкость пары, а наш собственный предохранитель. Так и вышло
     * 06.09.2026: лестница лотов дала ровные нули на $10 и $30 при живом рынке,
     * а в логе лежало 15.5 млн строк «экспозиция превысила бы предел 30.0».
     *
     * Живой исполнитель этот метод НЕ ВЫЗЫВАЕТ, и пределы для него остаются там,
     * где им положено быть по ТЗ §6 — в коде, за пересборкой.
     */
    /**
     * Доля отступа, которую готовы отдать неопределённости цены (0 — выключено).
     * Ставится только стендом: на живых ботах гейт пока бинарный.
     */
    private double spreadToOffset;
    private double spreadToOffsetMaxPct = 1.0;

    private double maxOrderNotional = ExecLimits.MAX_ORDER_NOTIONAL_USDC;
    private double maxExposure = ExecLimits.MAX_TOTAL_EXPOSURE_USDC;
    private double maxTradingLoss = ExecLimits.MAX_TRADING_LOSS_USDC;

    /**
     * ПРЕДЕЛ УБЫТКА — АБСОЛЮТНЫЙ, и поэтому у мелкого бота он жёстче.
     *
     * Один доллар при потолке  — это 5% капитала, при потолке  уже 14%.
     * Опытные боты на малом потолке останавливались бы втрое чаще больших, и
     * меряли бы мы не настройку, а срабатывание предохранителя.
     *
     * Задаётся юнитом (`--revx.exec.max-loss=`), по умолчанию прежняя единица:
     * у боевых ботов ничего не меняется, пока значение не передано явно.
     */
    public void maxTradingLoss(double usdc) {
        if (usdc > 0) {
            this.maxTradingLoss = usdc;
            log.warn("предел убытка задан юнитом: {} USDC", fmt(usdc));
        }
    }
    private PlacementBudget budget;
    /**
     * Счётчики тиков — В ПАМЯТИ, а не через журнал.
     *
     * Стенду нужна одна величина: доля времени с полным инвентарём. Раньше ради
     * неё на КАЖДЫЙ тик писалась строка в SQLite, а потом весь журнал читался
     * обратно. Замер 07.09.2026 показал, во что это обходится: 39 МБ/с записи и
     * 666 операций в секунду при чтении 0.4 МБ/с — то есть обход упирался в
     * запись собственного журнала, который нужен ради одного числа. Плюс 4861
     * забытый каталог во временной папке, около 19 ГБ.
     */
    private long ticks;
    private long ticksAtCap;
    /**
     * Тики, когда продать НЕЧЕГО: инвентаря меньше лота, аска в книге нет.
     *
     * Симметричная беда к «полному инвентарю» и на живых ботах куда более
     * частая (замер 10.09.2026: без аска 15% времени у BTC, 24% у SOL, 44% у
     * ETH). Обе доли нужны вместе: порознь они не говорят, в какую сторону
     * двигать потолок, — у пустого и полного бота простаивает РАЗНАЯ половина
     * конструкции.
     */
    private long ticksEmpty;
    /**
     * Сколько тиков инвентарь стоял на каждом целом числе лотов.
     *
     * Средний инвентарь и две доли на краях не отвечают на вопрос «сколько
     * лотов вообще нужно»: одно и то же среднее даёт и ровный бот, и бот,
     * скачущий между пустым и полным. Корзина по индексу
     * floor(инвентарь / лот), последняя собирает всё сверх.
     */
    private long[] lotHist = new long[0];
    private double statsCap;

    /** Потолок инвентаря для счётчика «доля времени в потолке». */
    public void statsInventoryCap(double cap) {
        this.statsCap = cap;
    }

    private void countTick() {
        ticks++;
        if (statsCap > 0 && inventory >= 0.9 * statsCap) {
            ticksAtCap++;
        }
        double lot = params.size();
        if (lot > 0) {
            if (inventory < lot) {
                ticksEmpty++;
            }
            int bucket = Math.max(0, (int) Math.floor(inventory / lot));
            int capLots = statsCap > 0 ? (int) Math.ceil(statsCap / lot) : bucket;
            int want = Math.min(Math.max(capLots, bucket) + 1, 64);
            if (lotHist.length < want) {
                lotHist = java.util.Arrays.copyOf(lotHist, want);
            }
            lotHist[Math.min(bucket, lotHist.length - 1)]++;
        }
    }
    /**
     * Насколько туго с общим бюджетом, 0…1. Перечитывается раз в минуту, а не
     * каждый тик: ведро наполняется одним токеном за сто секунд, так что чаще
     * незачем, а шесть процессов, дёргающих общую базу по десять раз в секунду,
     * стоили бы дороже самой экономии.
     */
    private volatile double budgetPressure;
    private double totalFees;
    private double totalFilledNotional;
    private double startInventory;
    private double startQuote;
    private boolean startCaptured;
    private java.util.function.Consumer<String> alert = message -> { };

    /** Включить реакцию на свипы; {@code null} выключает. */
    public void sweepWatch(SweepWatch watch) {
        this.sweeps = watch;
    }

    /** Сторож перевеса тейкеров Бинанса; null — сдвига по потоку нет. */


    public void flowWatch(FlowWatch watch) {


        this.flow = watch;


    }



    public void sweepWatch(SweepWatch watch, SweepSide side) {
        this.sweeps = watch;
        this.sweepSide = side == null ? SweepSide.BOTH : side;
    }

    /**
     * Склейка односторонней реакции: одна сторона от СДВИНУТОЙ опоры, вторая от
     * нетронутой.
     *
     * ⚠️ Складывать надо именно готовые котировки, а не «сдвигать полцены»:
     * между опорой и ценой стоят скос по инвентарю, снос и форма сетки, и
     * повторять их в обход {@code policy} значило бы завести вторую реализацию
     * котировщика — ровно то, от чего стенд и уберегает.
     *
     * @param shifted котировка по сдвинутой опоре
     * @param plain   котировка по опоре БЕЗ сдвига (тот же инвентарь и снос)
     */
    static Quoter.Quotes spliceSide(Quoter.Quotes shifted, Quoter.Quotes plain, SweepSide side) {
        return switch (side) {
            case BOTH -> shifted;
            case BID -> new Quoter.Quotes(shifted.bid(), plain.ask());
            case ASK -> new Quoter.Quotes(plain.bid(), shifted.ask());
        };
    }

    public QuoteLoop(Venue client, Clock clock, FairSource stand, ExecJournal journal,
                     Quoter.Params params, String symbol, long periodMs, double minNotional,
                     BotTag tag, org.home.data.revx.sim.QuotePolicy policy,
                     boolean ownPosition, double positionSeed, double baseStep,
                     double parkDistance, AllocRegistry alloc) {
        this(client, clock, stand, journal, params, symbol, periodMs, minNotional, tag, policy,
                ownPosition, positionSeed, baseStep, parkDistance, alloc, 1, 0, true);
    }

    /**
     * @param levels    сколько заявок держать на каждой стороне
     * @param levelStep расстояние между уровнями в долях цены
     */
    public QuoteLoop(Venue client, Clock clock, FairSource stand, ExecJournal journal,
                     Quoter.Params params, String symbol, long periodMs, double minNotional,
                     BotTag tag, org.home.data.revx.sim.QuotePolicy policy,
                     boolean ownPosition, double positionSeed, double baseStep,
                     double parkDistance, AllocRegistry alloc, int levels, double levelStep,
                     boolean innerFirst) {
        this.innerFirst = innerFirst;
        this.bidTicks = new long[Math.max(1, levels)];
        this.askTicks = new long[Math.max(1, levels)];
        this.levels = Math.max(1, levels);
        this.levelStep = levelStep;
        for (int i = 0; i < this.levels; i++) {
            bids.add(new Resting());
            asks.add(new Resting());
        }
        this.alloc = alloc;
        this.client = client;
        this.clock = clock != null ? clock : Clock.system();
        this.minuteStartMs = this.clock.now();
        this.lastReconcileMs = this.minuteStartMs;
        this.stand = stand;
        this.journal = journal;
        this.params = params;
        this.quoter = new Quoter(params);
        this.efficiency = new org.home.data.revx.sim.EfficiencyRatio(
                params.erWindowMs() > 0 ? params.erWindowMs() : 86_400_000L,
                params.erSampleMs() > 0 ? params.erSampleMs() : 3_600_000L);
        this.symbol = symbol;
        this.base = symbol.substring(0, symbol.indexOf('/'));
        this.quote = symbol.substring(symbol.indexOf('/') + 1);
        this.periodMs = periodMs;
        this.minNotional = minNotional;
        this.tag = tag;
        this.policy = policy != null ? policy : this.quoter;
        this.ownPosition = ownPosition;
        this.positionSeed = positionSeed;
        this.baseStep = baseStep;
        this.dust = baseStep > 0 ? baseStep / 2 : 0;
        this.parkDistance = parkDistance;
    }

    /**
     * Ниже этого остатка позиция считается нулевой.
     *
     * Площадка квантует количество шагом {@code base_step}, поэтому остаток
     * мельче половины шага не может соответствовать никакому реальному
     * количеству — это накопленная ошибка сложения. У бота B так висело
     * 3.4e−21 BTC (док. 126 §9 п.5): продать его нельзя (номинал на двадцать
     * порядков ниже минимума заявки), а ненулевым он числился, и каждый тик
     * котировал аск, который тут же отбраковывался как «нечем котировать».
     *
     * Обнуление безопасно в обе стороны: расхождение с остатком аккаунта
     * проверяется условием «своя позиция не больше общей», и занижение его не
     * ломает.
     */
    private final double baseStep;
    private final double dust;

    /**
     * @return null, если включить можно; иначе причина отказа
     *
     * Отказ при непокрытых деньгах — не придирка. Бот без своей доли кассы
     * встаёт на отказах «Insufficient balance» и тратит на них суточный лимит
     * постановок: у бота B за сутки таких отказов было 197. Лучше не стартовать
     * вовсе, чем стартовать и жечь общий ресурс впустую.
     */
    /**
     * Взять недостающие деньги под свой потолок. Зовётся из {@code /start}.
     *
     * Отдельной командой это быть не должно: в захвате ДЕНЕГ нет решения, оно
     * механическое — «сколько стоит недостающая до потолка часть инвентаря,
     * столько и беру, если свободно». Решение есть только в захвате ЛОТОВ, и оно
     * остаётся за человеком ({@code /claim N}). Предпросмотр даёт {@code /free}:
     * там стоит строка «до потолка не хватает X лота = Y USDC».
     *
     * @return что взято, либо null, если брать было нечего
     */
    public String topUpCash() {
        if (alloc == null) {
            return null;
        }
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(price > 0)) {
            return null;
        }
        long now = clock.now();
        refreshBalances();
        double need = Math.max(0, (params.inventoryCap() - inventory) * price);
        double have = alloc.own(tag.id(), quote);
        double want = Math.max(0, need - have);
        // 🔑 КАССА СВОДИТСЯ В ОБЕ СТОРОНЫ, а не только добирается.
        //
        // Инвариант прост: за ботом стоит денег ровно на ту часть потолка, которую
        // он ещё не держит монетой, то есть «монеты + касса = потолок». Покупка и
        // продажа его держат сами (купил — потратил), а вот ПЕРЕДАЧА двигает
        // монеты, не трогая денег: захватил лот — и остался с монетой плюс
        // деньгами под неё же.
        //
        // ⚠️ Найдено владельцем 13.09.2026 на живом боте: e захватил лот BTC в
        // 12:47, а касса осталась 6.75 USDC — деньги на все семь лотов потолка при
        // одном лоте уже в монете. Доля бота на общем счёте выросла на стоимость
        // лота, и эти деньги стали недоступны остальным, хотя потратить их он всё
        // равно не может: покупку ограничивает потолок инвентаря.
        if (want <= 0) {
            return giveBackCash(need, have, price, now);
        }
        double free = alloc.free(quote, quoteTotal, now).free();
        double take = Math.min(want, free);
        if (take <= 0) {
            return String.format(java.util.Locale.ROOT,
                    "Свободных денег нет: за ботом %.2f %s, до потолка нужно %.2f.",
                    have, quote, need);
        }
        if (!alloc.claim(tag.id(), quote, take, quoteTotal, price, now)) {
            return "Деньги забрать не удалось.";
        }
        ownCash += take;
        seedCash += take;
        journal.putState(STATE_CASH, ownCash);
        journal.putState(STATE_SEED_CASH, seedCash);
        journal.event("claim", String.format(java.util.Locale.ROOT,
                "автозахват при старте: %.2f %s", take, quote));
        return String.format(java.util.Locale.ROOT,
                "Взято %.2f %s (нужно было %.2f, свободно было %.2f).%s",
                take, quote, want, free,
                take + 1e-9 < want ? " Потолок инвентаря фактически ниже заданного." : "");
    }

    /**
     * Вернуть в котёл деньги сверх потолка — вторая половина сведе́ния кассы.
     *
     * Отдаётся только ИЗЛИШЕК: то, что бот всё равно не может потратить, потому
     * что покупку ограничивает потолок инвентаря. Мелочь ниже минимума заявки не
     * трогаем — гонять по реестру центы дороже, чем они стоят.
     */
    private String giveBackCash(double need, double have, double price, long now) {
        double give = have - need;
        if (give <= Math.max(minNotional, 1e-9)) {
            return null;
        }
        double gave = alloc.releasePart(tag.id(), quote, give, price, now);
        if (!(gave > 0)) {
            return null;
        }
        // Передача двигает и кассу, и ЕЁ точку отсчёта: иначе возврат лишнего
        // выглядел бы убытком бота (на этом бот B однажды отчитался о −6.77 USDC,
        // которых не было).
        ownCash -= gave;
        seedCash -= gave;
        journal.putState(STATE_CASH, ownCash);
        journal.putState(STATE_SEED_CASH, seedCash);
        journal.event("release", String.format(java.util.Locale.ROOT,
                "сведение кассы: возвращено %.2f %s (держал %.2f, под потолок нужно %.2f)",
                gave, quote, have, need));
        return String.format(java.util.Locale.ROOT,
                "Возвращено в общий котёл %.2f %s: держал %.2f, а под потолок нужно %.2f "
                        + "(остальное уже в монете).", gave, quote, have, need);
    }

    public String cannotStart() {
        if (alloc == null) {
            return null;
        }
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(price > 0)) {
            return null;                  // цены нет — судить не о чем, гейт разберётся
        }
        double have = alloc.own(tag.id(), quote);
        double oneLot = params.size() * price;
        // ⚠️ СНАЧАЛА «СКОЛЬКО ВООБЩЕ НУЖНО», И ТОЛЬКО ПОТОМ «ХВАТАЕТ ЛИ».
        //
        // Порядок был обратный, и 16.09.2026 это не пустило бота `c`: потолок у
        // него 2.33 лота, держал он 2, докупать оставалось на 0.96 USDC — а
        // числилось 0.97, то есть хватало. Гейт же требовал ПОЛНЫЙ лот, 2.90,
        // и отказывал.
        //
        // Цена ошибки больше, чем кажется: не пустив бота, мы лишаем его
        // возможности ПРОДАВАТЬ, хотя у потолка это ровно то, что ему нужно.
        // Касса нужна под покупки, а покупок у него почти не осталось.
        double need = Math.max(0, (params.inventoryCap() - inventory) * price);
        if (need <= 0) {
            return null;                  // инвентарь уже у потолка, покупать не на что
        }
        double required = Math.min(Math.max(oneLot, minNotional), need);
        if (have + 1e-9 < required) {
            return String.format(java.util.Locale.ROOT,
                    "Не хватает своей кассы: за ботом числится %.2f %s, а до потолка "
                            + "нужно ещё %.2f (на одну заявку %.2f).",
                    have, quote, required, Math.max(oneLot, minNotional));
        }
        // Порог покрытия. Требовать ПОЛНОГО покрытия нельзя: 04.09.2026 на счёте
        // было $46.21 при сумме потолков трёх ботов $49.13, и строгий гейт не
        // пустил бы третьего из-за 11% нехватки. Но и стартовать с любой
        // недостачей неправильно: бот с четвертью денег — это уже другой бот, и
        // сравнивать его с остальными нечестно.
        //
        // Недостача при этом БЕЗОПАСНА: покупка ограничена своей долей кассы
        // (см. sizeFor), поэтому в чужие деньги бот не залезет и отказов
        // «Insufficient balance» не наберёт. Порог здесь про сопоставимость
        // измерения, а не про риск.
        double share = have / need;
        if (share + 1e-9 < MIN_FUNDING_SHARE) {
            return String.format(java.util.Locale.ROOT,
                    "Касса покрывает только %.0f%% потолка (%.2f из %.2f %s), нужно не "
                            + "меньше %.0f%%. Долейте на счёт или освободите кассу у "
                            + "другого бота через /release.",
                    share * 100, have, need, quote, MIN_FUNDING_SHARE * 100);
        }
        if (share < 1) {
            log.warn("касса покрывает {}% потолка: фактический потолок инвентаря ниже",
                    Math.round(share * 100));
        }
        return null;
    }

    public void startQuoting() {
        if (quoting.compareAndSet(false, true)) {
            // Иначе /status отвечает «котирует (не запущен)»: причина паузы
            // держится до следующего тика и противоречит состоянию.
            pausedReason = null;
            journal.event("start", "котирование включено");
            log.warn("КОТИРОВАНИЕ ВКЛЮЧЕНО: {} по {} USDC", symbol, params.size());
        }
    }

    public void stopQuoting() {
        if (quoting.compareAndSet(true, false)) {
            journal.event("stop", "котирование выключено, снимаю заявки");
            cancelAll("остановка по команде");
            // Снять по своей памяти мало: она и бывает неверна. Проверяем факт.
            reconcile("остановка");
            log.warn("котирование выключено");
        }
    }

    /** Куда сообщать о срабатывании предохранителей. Ставится исполнителем. */
    public void alertTo(java.util.function.Consumer<String> sink) {
        this.alert = sink == null ? message -> { } : sink;
    }

    public boolean isQuoting() {
        return quoting.get();
    }

    /**
     * Завершение ПРОЦЕССА отводит заявки, а не снимает их.
     *
     * Отмена бесплатна, а восстановление после неё стоит постановок — 3-6 на
     * бота, замер 07.09.2026, при ровном расходе SOL в 0.2 постановки за пять
     * минут. Оставленная в книге заявка возвращается в работу заменой, у которой
     * суточного лимита нет вовсе.
     *
     * ⚠️ Парковать надо ЗДЕСЬ, а не только в {@link Park}. Первая выкатка
     * 07.09.2026 это показала: хук systemd отработал правильно, но бот к тому
     * моменту уже снял свои заявки сам, и парковать было нечего. Внешний Park
     * остаётся вторым рубежом — он подберёт то, чего не знал цикл.
     *
     * ⚠️ {@code /stop} по-прежнему СНИМАЕТ: это осознанный выход человека, а не
     * перезапуск, и оставлять заявки в книге при нём нельзя.
     */
    public void shutdown() {
        alive = false;
        // Заявок здесь НЕ ТРОГАЕМ — ни снять, ни отвести. Этим занимается
        // {@link Park} из ExecStopPost, и он единственный хозяин.
        //
        // Отводить отсюда пробовали 07.09.2026, и на живых ботах вышло плохо в
        // обе стороны. Хуки JVM выполняются ОДНОВРЕМЕННО, поэтому журнал
        // закрывался раньше, чем отвод успевал записаться: заявки уезжали на
        // ±5%, а в журнале не оставалось ни строчки — ни события, ни самих PUT.
        // Для системы, где журнал и есть первоисточник, это хуже потерянной
        // экономии. Вторым следствием шёл двойной отвод: внешний Park заставал
        // уже отведённые заявки и отводил их ещё на 5%, до десяти процентов от
        // цены.
        //
        // Плата за простоту — секунд восемь, пока заявки стоят на рабочих ценах
        // без хозяина. Это меньше, чем они там стояли всё время работы бота.
        // ExecStopPost выполняется и после SIGKILL, так что покрытие не теряется,
        // а если Park не справится, он снимет заявки сам.
        quoting.set(false);
    }

    /** Метка бота: суточный лимит постановок у каждого свой (см. {@link ExecLimits}). */
    public String botId() {
        return tag.id();
    }

    /**
     * Кто этот бот и чем торгует — шапка любого ответа в Telegram.
     *
     * Ботов на счёте несколько, у каждого свой чат, и отличаются они парой и
     * настройками. Без этой строки в переписке нельзя понять, на что смотришь:
     * 05.09.2026 бот B переезжает с BTC на SOL, а чат у него остаётся прежним.
     */
    public String profile() {
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        double lot = params.size();
        double cap = params.inventoryCap();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "Бот %s · %s%n",
                tag.id().toUpperCase(java.util.Locale.ROOT), symbol));
        sb.append(String.format(java.util.Locale.ROOT,
                "Отступ %.1f б.п., скос %.2f%%, цель %.0f%% потолка%n",
                params.offset() * 10_000, params.skewK() * 100, params.skewTarget() * 100));
        sb.append(String.format(java.util.Locale.ROOT, "Лот %s %s%s, потолок %.1f лота%s%n",
                fmt(lot), base,
                price > 0 ? String.format(java.util.Locale.ROOT, " (%.2f %s)",
                        lot * price, quote) : "",
                lot > 0 ? cap / lot : 0,
                price > 0 ? String.format(java.util.Locale.ROOT, " (%.2f %s)",
                        cap * price, quote) : ""));
        // ⚠️ ФОРМА СЕТКИ — ПЕРВОКЛАССНАЯ НАСТРОЙКА С 12.09.2026, а в профиле её не
        // было: три бота из шести работают на трёх уровнях, и по /status их было
        // не отличить от одноуровневых. Порядок заполнения показывается только у
        // многоуровневых — при одном уровне он не определён (задача A38).
        sb.append(levels > 1
                ? String.format(java.util.Locale.ROOT,
                        "Уровней %d, шаг %.1f б.п., первым берёт %s%n",
                        levels, levelStep * 10_000, innerFirst ? "БЛИЖНИЙ" : "дальний")
                : "Уровень один\n");
        sb.append(String.format(java.util.Locale.ROOT,
                "Период опроса %d мс, отвод заявок %s%n", periodMs,
                parkDistance > 0 ? String.format(java.util.Locale.ROOT, "%.0f%%",
                        parkDistance * 100) : "выключен"));
        return sb.toString();
    }

    /** Цель скоса: доля потолка, к которой котировщик тянет инвентарь. */
    public double skewTarget() {
        return params.skewTarget();
    }

    /** Потолок инвентаря — знаменатель для доли в процентах. */
    public double inventoryCap() {
        return params.inventoryCap();
    }

    /** Размер лота — знаменатель, чтобы показывать инвентарь в лотах, а не в BTC. */
    public double lotSize() {
        return params.size();
    }

    /** Базовая валюта пары: для подписей в отчётах. */
    public String base() {
        return base;
    }

    public Stats stats() {
        int partialsNow = (int) java.util.stream.Stream.concat(bids.stream(), asks.stream())
                .filter(Resting::partial).count();
        return new Stats(placements, replaces, cancels, fills, inventory, lastFair,
                quoting.get() ? "котирует" : "остановлен", pausedReason, ticks, ticksAtCap,
                partials, partialsNow, ticksEmpty, lotHist.clone(), volGated);
    }

    @Override
    public void run() {
        restorePosition();
        recoverMissedFills();
        refreshBalances();
        // Стартуем остановленными, значит и книга должна быть пуста: заявки
        // переживают наш процесс, и оставшиеся после падения — уже не наши.
        reconcile("старт");
        while (alive) {
            long started = clock.now();
            try {
                tick();
            } catch (Exception e) {
                // Цикл не должен умирать от единичной ошибки: заявки останутся
                // висеть, а следующий тик их подхватит. Но молчать нельзя.
                log.error("тик упал: {}", e.toString());
                journal.event("tick_error", e.toString());
            }
            long sleep = periodMs - (clock.now() - started);
            if (sleep > 0) {
                try {
                    clock.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Проверка размеров сделана; повторять на каждом тике незачем. */
    private boolean sizingChecked;

    /**
     * РАЗОВАЯ ПРОВЕРКА РАЗМЕРОВ на первой известной цене.
     *
     * Лот и потолок заданы в МОНЕТАХ, а деньги и пределы — в долларах, и связь
     * между ними держится на цене. Цена меняется, связь тихо уезжает, и
     * замечаешь это по последствиям, а не по причине. Два последствия, оба
     * наблюдались:
     *
     * <ul>
     *   <li><b>число лотов в потолке</b> перестаёт быть целым, и потолок можно
     *       перелететь на неполный лот. При двадцати лотах по $1 такого не было,
     *       при 6.67 лотах по $3 — до 105% потолка;</li>
     *   <li><b>лот дорастает до предела заявки.</b> {@code MAX_ORDER_NOTIONAL}
     *       живьём не масштабируется: лот $3 упрётся в $10 при росте монеты в
     *       3.3 раза, и бот просто перестанет ставить заявки с записью «заявка
     *       превышает предел». Молча, потому что это штатная ветка отказа.</li>
     * </ul>
     *
     * Поэтому связь проверяется один раз при первой цене и пишется в журнал:
     * дрейф становится видимым в момент, когда он ещё ничего не сломал.
     */
    private void checkSizing(double price) {
        if (sizingChecked || !(price > 0)) {
            return;
        }
        sizingChecked = true;
        double lotUsd = params.size() * price;
        double capUsd = params.inventoryCap() * price;
        double lots = params.size() > 0 ? params.inventoryCap() / params.size() : 0;
        String detail = String.format(java.util.Locale.ROOT,
                "лот %.2f USDC, потолок %.2f USDC, лотов в потолке %.2f, предел заявки %.2f",
                lotUsd, capUsd, lots, maxOrderNotional);
        log.warn("размеры: {}", detail);
        journal.event("sizing", detail);

        if (Math.abs(lots - Math.round(lots)) > 0.01) {
            log.warn("⚠️ лотов в потолке НЕ ЦЕЛОЕ ({}): потолок можно перелететь "
                    + "на неполный лот, а скос грубеет", String.format("%.2f", lots));
        }
        if (lots < 7) {
            log.warn("⚠️ лотов в потолке меньше семи ({}): скос двигается "
                    + "скачками по {} б.п. при коэффициенте {}",
                    String.format("%.2f", lots),
                    String.format("%.1f", lots > 0 ? params.skewK() * 10_000 / lots : 0),
                    params.skewK());
        }
        if (lotUsd > 0.6 * maxOrderNotional) {
            log.error("⚠️ лот {} USDC занимает больше 60% предела заявки {} — "
                    + "при дальнейшем росте монеты бот перестанет ставить заявки",
                    String.format("%.2f", lotUsd), String.format("%.2f", maxOrderNotional));
            journal.event("sizing_warn", detail);
        }
    }

    private void tick() {
        rollCounters();
        StandReader.Fair fair = stand.latest(base, 30_000);
        lastFair = fair.price();
        rememberFair(fair.price());
        if (fair.price() > 0) {
            efficiency.accept(clock.now(), fair.price());
            checkSizing(fair.price());
        }

        if (!quoting.get()) {
            pausedReason = "не запущен";
            return;
        }
        // Две паузы, обе про площадку, а не про рынок: пока она тормозит на
        // заменах, мы плодим неснимаемый резерв; пока он не отпущен, продать
        // монету всё равно нельзя. В обоих случаях лучшее действие — никакого.
        String venuePause = venuePauseReason();
        if (venuePause != null) {
            pausedReason = venuePause;
            countTick();
            journal.quote(fair.price(), null, null, inventory, false, pausedReason);
            // ⚠️ НА ЗАТЫКЕ — СНИМАТЬ, А НЕ ОТВОДИТЬ, и это не мелочь.
            //
            // {@link #standAside} уводит заявку далеко от рынка ЗАМЕНОЙ, то есть
            // шлёт ровно ту операцию, которая призраков и рождает: площадка
            // отменяет исходную, создаёт наследника и теряет его идентификатор.
            // Отводить во время затыка значит тушить пожар бензином.
            //
            // Отмена наследника не создаёт по построению, поэтому призрака
            // родить не может. Платим постановками за возврат: при суточной
            // тысяче и нашем расходе в 124 это по карману.
            if (clock.now() < stallUntilMs) {
                cancelAll(pausedReason);
            } else {
                standAside(pausedReason);
            }
            return;
        }
        // ⚠️ ДИНАМИЧЕСКИЙ ОТСТУП вместо бинарного гейта — опыт, а не умолчание.
        //
        // Гейт по ширине опорной книги останавливает котирование целиком, и у
        // ENA это половина времени (62% проходимости за 16 суток, 45% на
        // выходных). Но ширина опоры — это не «можно/нельзя», а мера
        // неопределённости: середина книги шириной s известна с точностью ±s/2.
        // Значит вместо остановки можно ОТОДВИНУТЬ заявку на ту же величину и
        // продолжать торговать, только дальше от цены.
        //
        // Порог гейта при этом всё равно нужен: при совсем сломанной опоре
        // (замер 19.08.2026 — 1.18% у ETH на движении 18%) отступ вырос бы до
        // абсурда, и честнее не котировать вовсе. Поэтому отступ раздвигается
        // до потолка, а дальше работает прежний гейт.
        boolean widened = gateWidens(fair.quotable(), fair.pausedReason(), fair.price(),
                fair.referenceSpreadPct(), spreadToOffset, spreadToOffsetMaxPct);
        if ((!fair.quotable() && !widened) || !(fair.price() > 0)) {
            // Гейт ТЗ §4.1: опора сломана — уводим котировки из зоны исполнения.
            pausedReason = fair.pausedReason() == null ? "курс ненадёжен" : fair.pausedReason();
            countTick();
            journal.quote(fair.price(), null, null, inventory, false, pausedReason);
            standAside(pausedReason);
            return;
        }
        long staleMs = clock.now() - fair.asOfMs();
        if (staleMs > 15_000) {
            pausedReason = "данные стенда устарели на " + staleMs / 1000 + " с";
            standAside(pausedReason);
            return;
        }
        pausedReason = null;
        lastTrustedFair = fair.price();

        // Страховка от тренда включается только в трендовом режиме (док. 105 §5).
        // При выключенном гейте (порог 0) поведение прежнее.
        double drift = efficiency.open(params.driftGateEr()) ? drift() : 0;
        // В распродаже цена считается котировщиком с ЦЕЛЬЮ СКОСА В НОЛЬ: иначе
        // скос тянул бы к половине потолка, то есть подтягивал бид и отодвигал
        // аск — ровно наоборот тому, что нужно, когда надо сводить позицию.
        // 🔑 РЕАКЦИЯ НА СВИП: котируем вокруг ОЖИДАЕМОЙ середины, а не текущей.
        //
        // После крупного свипа опора идёт в его сторону, и остаток пути измерен
        // (A81–A84): 3.97 б.п. всего, из них к минуте проходит 3.40. Сдвигаем
        // опору на ожидаемый ОСТАТОК — тогда бид не стоит под падение, а аск не
        // отдаёт монету перед ростом.
        //
        // ⚠️ Форма именно сдвиг, а не гейт: гейт убирает вместе с плохими
        // сделками и хорошие, и на этом провалились гейты A46.
        // Два источника сдвига опоры, и они СКЛАДЫВАЮТСЯ: свип — событие на нашей
        // ленте, перевес тейкеров Бинанса — состояние чужого рынка. Корреляция
        // между ними 0.012–0.053 (задача A81), то есть это разные события.
        double sweepBp = sweeps == null ? 0 : sweeps.shiftBp(clock.now());
        double flowBp = flow == null ? 0 : flow.shiftBp(clock.now());
        double quoteFair = fair.price() * (1 + (sweepBp + flowBp) / 1e4);
        if (sweepBp != 0) {
            SweepWatch.Sweep sw = sweeps.lastSweep(clock.now());
            if (sweeps.isNew(sw)) {
                journal.event("sweep_shift", String.format(java.util.Locale.ROOT,
                        "%s: свип %s на $%.0f, опора %+.2f б.п.", symbol,
                        sw.side() > 0 ? "вверх" : "вниз", sw.notional(), sweepBp));
            }
        }
        Quoter.Quotes target = frozenUnwind
                ? unwindQuoter().quotes(quoteFair, inventory, drift)
                : policy.quotes(quoteFair, inventory, drift);
        // ОДНОСТОРОННИЙ СДВИГ: считаем котировку ДВАЖДЫ и берём одну сторону от
        // сдвинутой опоры, другую от нетронутой.
        //
        // 🔑 Зачем разделять. Сдвиг опоры двигает обе стороны сразу, а
        // действуют они по-разному: на свипе ВНИЗ бид уходит от рынка (не лови
        // падающий нож), а аск приближается (успей продать). Это две разные
        // ставки, и на замере они смешаны. Прогон 21.09 показал, что весь плюс
        // пришёл оттуда, где бот РАСКЛИНИЛСЯ у потолка, — то есть от аска, а не
        // от бида, но раздельно это не мерилось.
        //
        // ⚠️ Второй вызов обязан идти по ТОМУ ЖЕ инвентарю и сносу: иначе
        // сравнивались бы не стороны, а два разных состояния.
        if (sweepBp + flowBp != 0 && sweepSide != SweepSide.BOTH) {
            Quoter.Quotes plain = frozenUnwind
                    ? unwindQuoter().quotes(fair.price(), inventory, drift)
                    : policy.quotes(fair.price(), inventory, drift);
            target = spliceSide(target, plain, sweepSide);
        }
        if (widened) {
            target = widenForSpread(target, fair.price(), fair.referenceSpreadPct(),
                    spreadToOffset);
            pausedReason = null;
        }
        // ДЕФИЦИТ ПОСТАНОВОК раздвигает отступ тем же приёмом, что и широкая
        // опора. Прежде исчерпание бюджета просто выключало бота: 07.09.2026
        // ADA простояла так 11 часов, а PEPE 7, и вернуть их мог только человек.
        // Теперь бот вместо остановки отходит от цены, реже исполняется и
        // тратит меньше — то есть подстраивается под остаток сам.
        double pressure = pressureFromRecord != null
                ? pressureFromRecord.applyAsDouble(clock.now()) : budgetPressure;
        target = widenForBudget(target, fair.price(), pressure);
        target = pullFirstLot(target, fair.price());
        target = decayAsk(target, fair.price(), params.offset());
        rememberVol(fair.price());
        target = volGate(target);
        // Пишется КАЖДЫЙ тик: без справедливой цены в момент исполнения захват
        // потом не восстановить, а именно он и сравнивается с моделью.
        //
        // ⚠️ Вместе с ней пишется и ПРИМЕНЁННОЕ РАЗДВИЖЕНИЕ. Оно приходит из
        // общего ведра постановок, у которого нет истории, и без записи повтор
        // воспроизвести котировку не может в принципе — см. replayPressure.
        countTick();
        if (fair.price() > 0 && bidOffsetsBp.size() < 500_000) {
            // ⚠️ обе стороны НУЛЛЯБЕЛЬНЫ: подавленную сторону котировщик отдаёт null
            if (target.hasBid() && target.bid() > 0) {
                bidOffsetsBp.add((fair.price() - target.bid()) / fair.price() * 1e4);
            }
            if (target.hasAsk() && target.ask() > 0) {
                askOffsetsBp.add((target.ask() - fair.price()) / fair.price() * 1e4);
            }
            // ⚠️ И ОТДЕЛЬНО — цена, которая РЕАЛЬНО стоит в книге. Цель
            // пересчитывается каждый тик, а заявку двигает только замена, и
            // порог перевыставления держит её на месте, пока цель не уедет.
            // Исполняется книга, а не цель, поэтому сравнивать с живым надо
            // именно этот ряд.
            for (Resting r : bids) {
                if (r.venueId != null && r.price > 0) {
                    bidRestingBp.add((fair.price() - r.price) / fair.price() * 1e4);
                    break;
                }
            }
            for (Resting r : asks) {
                if (r.venueId != null && r.price > 0) {
                    askRestingBp.add((r.price - fair.price()) / fair.price() * 1e4);
                    break;
                }
            }
        }
        journal.quote(fair.price(), target.bid(), target.ask(), inventory, true, null,
                pressure);

        // ⚠️ Пул РАЗДЕЛЯЕТСЯ между уровнями, и порядок задаётся innerFirst.
        //
        // ⚠️ ПО УМОЛЧАНИЮ ПЕРВЫМ БЕРЁТ САМЫЙ ДАЛЬНИЙ (innerFirst=false), и
        // описанное ниже «сжатие» при этом НЕ работает. Замер 12.09.2026
        // (задача A38): на BTC порядок «от ближнего» лучше в ДВЕНАДЦАТИ клетках
        // из двенадцати — оба окна, три и пять уровней. Живых ботов это не
        // трогает: у всех шести levels=1, где порядок не определён.
        //
        // Дальше описан режим innerFirst=true:
        //
        // Отсюда и «сжатие» само собой: продавать можно только то, что есть, и
        // инвентарь достаётся ближним к рынку уровням. Продали с ближнего —
        // инвентаря стало на лот меньше, и на следующем тике дальний уровень
        // получает цену ближнего. Никакой отдельной логики подтягивания не
        // нужно, она выпадает из правила распределения.
        //
        // При одном уровне пул целиком уходит ему, то есть поведение прежнее.
        // ⚠️ Касса тоже РАЗДАЁТСЯ по уровням, а не достаётся каждому целиком.
        // Иначе порядок обхода на покупках не значит ничего, и вопрос «с
        // ближнего начинать или с дальнего» на половине конструкции просто не
        // задан.
        double buyCash = alloc != null
                ? Math.max(0, alloc.own(tag.id(), quote) - lockedQuoteEff()) : Double.MAX_VALUE;
        double sellPool = Math.max(0, inventory - lockedBaseEff());
        levelTicks++;
        for (int i = 0; i < levels; i++) {
            if (bids.get(i).venueId != null) {
                bidTicks[i]++;
            }
            if (asks.get(i).venueId != null) {
                askTicks[i]++;
            }
        }
        // СМЕННАЯ РАССТАНОВКА идёт вместо встроенной, когда её задали.
        //
        // Встроенный путь ниже сохранён и остаётся умолчанием: пока сменная
        // версия не доказала совпадения с ним на прогоне, отключать его нельзя —
        // сравнивать будет не с чем.
        if (layout != null && placer != null) {
            applyLayout(fair);
            return;
        }
        // ⚠️ ПОРЯДОК РАЗДАЧИ решает исход, и проверяются оба.
        //
        // Внутренними вперёд: если ресурса хватает на одну заявку, она встаёт
        // ближе всех к рынку — исполнится скорее, но по худшей цене.
        // Дальними вперёд: та же единственная заявка встаёт дальше всех —
        // исполнится реже, но выгоднее.
        //
        // Первая реализация умела только первый вариант, и на продажах он давал
        // систематический перекос: покупки шли на всех уровнях, включая дальние
        // и выгодные, а продажи почти всегда уходили по ближней цене.
        chooseReplaceSlot(target, fair);
        double buyRoom = buyRoom();
        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            // Биды могут раздаваться в своём порядке: дальние первыми — у потолка
            // бот докупает только дёшево, а продажи по-прежнему с ближнего.
            int ib = BUY_FAR_FIRST ? levels - 1 - k : i;
            Double bidPrice = onTick(Side.BUY, noCross(Side.BUY,
                    levelPrice(Side.BUY, target.bid(), fair.price(), ib), fair));
            Double askPrice = onTick(Side.SELL, noCross(Side.SELL,
                    levelPrice(Side.SELL, target.ask(), fair.price(), i), fair));

            double cashCap = bidPrice != null && bidPrice > 0
                    ? buyCash / bidPrice : Double.MAX_VALUE;
            double bought = syncSide(Side.BUY, ib, bids.get(ib), bidPrice, fair.price(),
                    Math.min(cashCap, buyRoom));
            buyCash = Math.max(0, buyCash - bought * (bidPrice == null ? 0 : bidPrice));
            buyRoom = Math.max(0, buyRoom - bought);

            sellPool = Math.max(0,
                    sellPool - syncSide(Side.SELL, i, asks.get(i), askPrice, fair.price(), sellPool));
        }
    }

    /**
     * Какая ОДНА заявка имеет право на замену в этот тик.
     *
     * <h2>Почему потолок нужен</h2>
     *
     * Замена стоит запроса, а площадка даёт их 10 в секунду НА ВЕСЬ СЧЁТ. У бота
     * до шести заявок (три уровня × две стороны), ботов шесть, и на общее
     * движение цены они реагируют в одну и ту же секунду. Замерено 06.09.2026:
     * в среднем 4.3 замены в секунду — вроде бы вдвое ниже лимита, — но 68
     * секунд из 415 пробили десятку, пик 24. За пробой площадка наказывает
     * ответом 429 с {@code retry-after} до полутора минут, и всё это время бот
     * стоит со старой ценой. То есть залпы стоили нам ровно того, ради чего
     * замены и делаются.
     *
     * <h2>Почему одна, а не «первая освободившаяся»</h2>
     *
     * Одна замена на тик у шести ботов даёт 6 запросов в секунду при лимите 10 —
     * граница доказуемая, а не средняя. Платы за это почти нет: измеренный спрос
     * 0.72 замены на бота в секунду, то есть потолок срезает не поток, а пики.
     *
     * ⚠️ Право отдаётся заявке с НАИБОЛЬШИМ относительным расхождением, а не
     * первой по порядку обхода. Порядок обхода задан раздачей капитала (от
     * дальнего уровня к ближнему), и отдать замену по нему значило бы обновлять
     * дальние заявки, которые почти не двигаются, пока ближняя — та, что и
     * приносит исполнения, — висит по устаревшей цене.
     */
    private void chooseReplaceSlot(Quoter.Quotes target, StandReader.Fair fair) {
        replaceSlotSide = null;
        replaceSlotLevel = -1;
        double best = 0;
        for (int i = 0; i < levels; i++) {
            best = considerSlot(Side.BUY, i, bids.get(i),
                    onTick(Side.BUY, noCross(Side.BUY,
                            levelPrice(Side.BUY, target.bid(), fair.price(), i), fair)),
                    best);
            best = considerSlot(Side.SELL, i, asks.get(i),
                    onTick(Side.SELL, noCross(Side.SELL,
                            levelPrice(Side.SELL, target.ask(), fair.price(), i), fair)),
                    best);
        }
    }

    /** Претендент на замену: годится ли и насколько он разошёлся с целью. */
    private double considerSlot(Side side, int level, Resting resting, Double targetPrice,
                                double best) {
        if (resting.venueId == null || targetPrice == null || !(resting.price > 0)) {
            return best;                 // нечего заменять: заявки нет
        }
        if (clock.now() < resting.blockedUntilMs || !quoter.shouldRequote(resting.price, targetPrice)) {
            return best;                 // под наказанием или цена и так годная
        }
        if (resting.partial()) {
            // ⚠️ Право на замену НЕЛЬЗЯ отдавать частично исполненной заявке:
            // площадка её заменить не даст (422, состояние не NEW), а право у
            // бота одно на тик — и достанется оно самому отставшему слоту, то
            // есть как раз тому, кого только что задело исполнение. Остальные
            // слоты при этом стояли бы с устаревшей ценой не по разу, а подряд.
            return best;
        }
        double divergence = Math.abs(targetPrice - resting.price) / resting.price;
        if (divergence <= best) {
            return best;
        }
        replaceSlotSide = side;
        replaceSlotLevel = level;
        return divergence;
    }

    /** Досталось ли этой заявке право на замену в текущем тике. */
    private boolean mayReplace(Side side, int level) {
        return replaceSlotSide == side && replaceSlotLevel == level;
    }

    /**
     * Цена уровня {@code i}: базовая котировка, отодвинутая ОТ РЫНКА на i шагов.
     *
     * Скос, гейты и политика считаются один раз для базовой цены — лесенка
     * кладётся поверх. Так уровни не спорят с политикой, а продолжают её.
     */
    private Double levelPrice(Side side, Double base, double fair, int level) {
        if (base == null || level == 0 || levelStep <= 0) {
            return base;
        }
        // ⚠️ ГЕОМЕТРИЧЕСКИЙ ШАГ, если задан множитель.
        //
        // Равномерный шаг кладёт уровни через равные расстояния, а поток спадает
        // с расстоянием ЭКСПОНЕНЦИАЛЬНО: ближний уровень работает за всех, а
        // дальний почти мёртв. Геометрический шаг сгущает уровни там, где λ ещё
        // велика.
        //
        // Замер по ленте 12.09.2026: множитель 1.25 даёт у BTC 912 лотов в сутки
        // против 657 у равномерного шага и 5154 б.п. захвата против 3888. А
        // множитель 1.4 ХУЖЕ равномерного — он растягивает лестницу до 15 б.п.,
        // где потока уже нет. То есть выигрыш не от геометрии как таковой, а от
        // сосредоточения около рынка.
        //
        // По умолчанию множитель 1.0, то есть шаг равномерный и поведение в
        // точности прежнее.
        double growth = LEVEL_GROWTH;
        double shift;
        if (growth > 1.0) {
            // Сумма геометрической прогрессии: уровень i стоит на
            // step·(1 + g + … + g^(i−1)) от базовой цены.
            shift = levelStep * fair * (Math.pow(growth, level) - 1) / (growth - 1);
        } else {
            shift = level * levelStep * fair;
        }
        return side == Side.BUY ? base - shift : base + shift;
    }

    /**
     * Множитель геометрического шага уровней; 1.0 — равномерный шаг.
     *
     * Читается из свойства, а не из конфигурации, намеренно: это ось сравнения
     * в опытах, а не боевая настройка. Пока не доказано на обходе с потолком и
     * ведром, в бой не идёт.
     */
    private static final double LEVEL_GROWTH =
            Double.parseDouble(System.getProperty("revx.sim.level-growth", "1.0"));

    /**
     * Приводит цену к допустимому тику: покупку ВНИЗ, продажу ВВЕРХ.
     *
     * <h2>Зачем это здесь, а не «площадка сама округлит»</h2>
     *
     * Она и округляла — молча, и из-за этого мы почти сутки считали, что у ENA
     * работает сетка из трёх уровней. Шаг цены ENA — 0.0001, то есть 6.01 б.п.
     * при её цене, а шаг сетки задан в 2 б.п.: три уровня арифметически
     * различаются, но ложатся на один тик. 07.09.2026 в книге стояли три аска по
     * одной цене (65.4 б.п. от справедливой) и два бида по одной.
     *
     * Хуже, что этого не знал СТЕНД: {@code SimVenue} цену не округляет вовсе,
     * поэтому измерение ENA — и +546% динамического отступа, и +321% гейта —
     * считалось на сетке из трёх различимых цен, которой живьём не бывает.
     * Округление здесь чинит обе стороны разом: живое и стенд считает один и тот
     * же {@code QuoteLoop}, значит и вырождение сетки теперь видно в прогоне.
     *
     * <h2>Почему от рынка, а не к ближайшему</h2>
     *
     * Округление к ближайшему может подтянуть заявку на полтика ВНУТРЬ, к цене,
     * и превратить её в пересекающую — ровно то, от чего стоит {@code noCross}.
     * Округление наружу этого не может по построению. Плата — меньше полутика
     * отступа, у самой грубой пары это 3 б.п., у остальных сотые доли.
     *
     * ⚠️ Снапить надо ДО сравнения с уже стоящей заявкой. Иначе цель
     * неокруглённая, стоящая заявка округлённая, {@code shouldRequote} видит
     * разницу всегда, и бот перевыставляется каждый тик — 10 замен в секунду на
     * пустом месте.
     */
    private Double onTick(Side side, Double price) {
        double step = params.quoteStep();
        if (price == null || !(step > 0) || !(price > 0)) {
            return price;
        }
        double units = price / step;
        double snapped = (side == Side.BUY ? Math.floor(units) : Math.ceil(units)) * step;
        return snapped > 0 ? snapped : price;
    }

    /** Приводит одну сторону к целевой цене: поставить, переставить или снять. */
    /**
     * Предохранитель от пересечения книги (найден 03.09.2026).
     *
     * <b>Что случилось.</b> Справедливая цена считается по корзине из 23 пар и
     * на быстром движении обгоняет книгу самой площадки. Бид, посчитанный как
     * {@code fair·(1−d)}, оказывается на уровне текущего аска или выше, и
     * площадка отвергает заявку с {@code post_only_immediate_match}. Заявка при
     * этом ПОГИБАЕТ: следующая замена бьёт по мёртвому id, получает 422, бот
     * сверяется с книгой, не находит её и ставит новую через `POST` — то есть
     * тратит единственный жёсткий ресурс площадки.
     *
     * <b>Сколько это стоило.</b> Из 217 заявок с отказом замены за 8 часов
     * **139 (64%) были именно `post_only_immediate_match`**, ещё 72 (33%) успели
     * исполниться — эти постановки законны. Расход постановок на исполнение
     * вырос с 1.04 до 3.9.
     *
     * <b>Почему зажим, а не отказ от котировки.</b> Пересечение означает, что
     * наша цена лучше рынка, — то есть мы готовы стоять на месте, где нас сразу
     * заберут. Отойти на тик внутрь книги дешевле, чем не стоять вовсе: заявка
     * остаётся мейкерской, край при этом только растёт.
     *
     * ⚠️ Верх стакана берётся из снимка стенда, а он сам отстаёт на период
     * опроса. Зажим поэтому не гарантия, а сокращение частоты: он убирает случаи,
     * где пересечение видно уже по имеющимся данным, и не видит тех, где книга
     * ушла после снимка.
     */
    private Double noCross(Side side, Double targetPrice, StandReader.Fair fair) {
        if (targetPrice == null) {
            return null;
        }
        double step = Math.max(params.quoteStep(), 1e-9);
        if (side == Side.BUY && fair.bookAsk() > 0 && targetPrice >= fair.bookAsk()) {
            double clamped = fair.bookAsk() - step;
            journal.event("no_cross", String.format(java.util.Locale.ROOT,
                    "BUY %.2f пересекал аск %.2f — зажат до %.2f",
                    targetPrice, fair.bookAsk(), clamped));
            return clamped > 0 ? clamped : null;
        }
        if (side == Side.SELL && fair.bookBid() > 0 && targetPrice <= fair.bookBid()) {
            double clamped = fair.bookBid() + step;
            journal.event("no_cross", String.format(java.util.Locale.ROOT,
                    "SELL %.2f пересекал бид %.2f — зажат до %.2f",
                    targetPrice, fair.bookBid(), clamped));
            return clamped;
        }
        return targetPrice;
    }

    /**
     * Приводит ОДИН уровень к целевой цене.
     *
     * @param pool сколько ресурса осталось после внутренних уровней
     * @return сколько ресурса этот уровень занял
     */
    private double syncSide(Side side, int level, Resting resting, Double targetPrice, double fair,
                            double pool) {
        if (targetPrice == null) {
            if (resting.venueId != null) {
                cancel(side, resting, "сторона не котируется");
            }
            return 0;
        }
        // Пул уже урезан внутренними уровнями: дальний получает только остаток.
        double size = Math.min(sizeFor(side, targetPrice, resting), Math.max(0, pool));
        dump(String.format(java.util.Locale.ROOT,
                "ВСТР %s ур%d: слот=%s цена=%.8f размер=%.6f | цель=%.8f | пул=%.6f | размер=%.6f",
                side, level, resting.venueId == null ? "пуст" : "есть",
                resting.price, resting.size, targetPrice, pool, size));
        double notional = size * targetPrice;
        // Ниже минимума площадки заявка не встанет, а попытка потратит суточный
        // лимит постановок. Остаток от частичного исполнения бывает мельче
        // минимума (5.5e-7 BTC = 0.04 USDC при пороге 0.1) — это не повод стучаться.
        if (size <= 0 || notional < minNotional) {
            if (resting.venueId != null) {
                cancel(side, resting, "нечем котировать эту сторону");
            }
            warnNoFunds(side, resting);
            return 0;
        }
        if (!(notional > 0 && notional <= maxOrderNotional)) {
            log.error("заявка {} на {} USDC превышает предел {} — не ставлю", side, notional,
                    maxOrderNotional);
            journal.event("limit_blocked", side + " нотионал " + notional);
            return 0;
        }
        if (exposure() + notional > maxExposure) {
            log.error("экспозиция превысила бы предел {} — не ставлю",
                    maxExposure);
            journal.event("limit_blocked", "экспозиция");
            return 0;
        }

        // Пауза после отказа площадки распространяется на ОБА действия. Отказ на
        // замене стоит четырёх запросов, и повторять его каждую секунду так же
        // вредно, как долбиться постановкой.
        if (resting.questioned != null && resting.venueId == null) {
            // §3.3: снятая заявка под вопросом — она ещё может исполниться, и
            // ставить на её место вторую значит рискнуть двойной экспозицией.
            return resting.qSize;
        }
        if (clock.now() < resting.blockedUntilMs) {
            // Заявка не тронута, но ресурс под ней всё ещё занят: отдать его
            // дальнему уровню значило бы продать один лот дважды.
            return resting.venueId == null ? 0 : resting.size;
        }
        if (resting.venueId == null) {
            // ⚠️ ПОСТАНОВКА потолком тика НЕ ограничена, и намеренно: заявки в
            // книге нет вовсе, а это состояние дороже устаревшей цены. Постановок
            // и так мало — их сдерживает суточный лимит в тысячу на счёт.
            place(side, resting, targetPrice, size);
        } else if (quoter.shouldRequote(resting.price, targetPrice) && mayReplace(side, level)) {
            replace(side, resting, targetPrice, size);
        }
        return size;
    }

    /**
     * Спот: продать можно только то, что есть, купить — только на что есть USDC.
     * Это физика площадки, а не настройка, и проверять её надо ДО отправки.
     */
    private double sizeFor(Side side, double price, Resting resting) {
        // params.sizeFor учитывает асимметрию набора: покупаем медленнее, чем
        // разгружаемся (док. 98 §6). При симметричной настройке это прежний size().
        double want = params.sizeFor(side, inventory);
        double ownSize = resting.venueId == null ? 0 : resting.size;
        double affordable = affordable(side, price, baseAvailable, quoteBalance,
                ownSize, resting.price);
        // ⚠️ Продать больше СВОЕЙ позиции нельзя, даже если на счёте есть чужое.
        //
        // `affordable` для продажи смотрит на `baseAvailable` — общий остаток
        // аккаунта, а ботов на нём три. Без этого потолка заявка одного
        // исполняется против инвентаря, набранного другим, и бот уходит в шорт,
        // которого на споте быть не может.
        //
        // Так и случилось 03.09.2026: бот A весь день стоял в шорте на 7–13 лотов
        // и прошёл в нём ралли 77 000 → 81 400. Убыток за сутки −0.4354 USDC при
        // прибыли во все остальные дни; арифметика сходится точно
        // (10 лотов × 0.0000125 × 4 400 ≈ 0.55). Продаж 201 против 192 покупок
        // при нулевой затравке — на споте это невозможно.
        // ⚠️ ПОКУПКА РЕЖЕТСЯ ОСТАТКОМ ПОТОЛКА, а не только деньгами.
        //
        // Раньше здесь стоял Double.MAX_VALUE, и потолок соблюдался лишь тем,
        // что котировщик перестаёт выставлять бид при `инвентарь >= потолок`.
        // Пока в потолок укладывалось ЦЕЛОЕ число лотов (двадцать), этого
        // хватало: двадцатый лот упирался ровно в потолок. При лоте $3 и
        // потолке $20 лотов 6.67 — на шести это 90% потолка, бид ещё
        // выставляется, и седьмой доводит инвентарь до 105%.
        //
        // Клип остатком заодно отвечает на вопрос «что делать с нецелым лотом»:
        // последняя покупка становится частичной, и остаток потолка не пропадает.
        // Если остаток мельче минимальной заявки площадки, размер обнулится
        // ниже по общей проверке, и бид просто не выставится.
        // В распродаже покупок нет вовсе: смысл ступени в том, чтобы свести
        // позицию к нулю, а не в том, чтобы менять её состав.
        // Запертое призраком (§3.6) в позиции есть, а продать его нельзя.
        double ownPositionCap = side == Side.SELL
                ? Math.max(0, inventory - lockedBaseEff())
                : (frozenUnwind ? 0 : Math.max(0, params.inventoryCap() - inventory));
        // Симметрично для покупки: тратить можно только СВОЮ долю кассы, иначе
        // бот покупает на деньги соседа. У бота B это стоило 197 отказов
        // «Insufficient balance» за сутки — каждый из них тратит постановку из
        // общей суточной тысячи.
        //
        // Меньшая касса означает меньший фактический потолок инвентаря, и это
        // нормальное рабочее состояние: 04.09.2026 счёта хватало на $46.21 при
        // сумме потолков $49.13, и требовать полного покрытия было бы нельзя.
        double ownCashCap = Double.MAX_VALUE;
        if (side == Side.BUY && alloc != null && price > 0) {
            ownCashCap = Math.max(0, alloc.own(tag.id(), quote) - lockedQuoteEff()) / price;
        }
        // ⚠️ ПРОДАЖУ ОГРАНИЧИВАЕТ И ЗАХВАТ, А НЕ ТОЛЬКО СВОЙ СЧЁТЧИК.
        //
        // До 11.09.2026 потолок продажи стоял только по { inventory} —
        // внутреннему счётчику бота. Пока счётчик верен, этого хватает; но он
        // ПРОИЗВОДНАЯ величина и расходится с реальностью как минимум тремя
        // путями, найденными в тот же день: потерянное исполнение на третьей
        // 422, невыясненная судьба заявки при неудачном GET, и перезапуск.
        //
        // Реестр же говорит, что МОЁ, и обновляется на каждом исполнении
        // ({ AllocRegistry#applyFill}). Покупка по нему уже ограничена
        // строкой выше; продажа не была — асимметрия, которая и выстрелила.
        //
        // 11.09.2026 бот C сам написал в журнал «РАСХОЖДЕНИЕ: своя позиция
        // 0.00120795 больше остатка аккаунта 0.0008053», а через 38 секунд
        // продал 0.0008053 — вдвое больше своего захвата (0.00040265), то есть
        // отдал лот остановленного соседа по той же паре.
        //
        // Теперь расхождение счётчика приводит к НЕДОпродаже, а не к продаже
        // чужого. Само расхождение по-прежнему только логируется.
        double ownClaimCap = Double.MAX_VALUE;
        if (side == Side.SELL && alloc != null) {
            ownClaimCap = Math.max(0, alloc.own(tag.id(), base) - lockedBaseEff());
        }
        return Math.min(Math.min(want, ownClaimCap),
                Math.min(Math.min(affordable, ownPositionCap), ownCashCap));
    }

    /**
     * Сколько РЕАЛЬНО можно поставить на сторону.
     *
     * Считается по {@code available}, а не по {@code total}, и это не придирка:
     * средства под уже стоящей заявкой площадка держит в резерве, в {@code total}
     * они видны, а поставить на них нельзя. Ночь 29.08.2026 стоила 766 отказов
     * «Insufficient balance of ₿0» подряд и всего суточного лимита постановок:
     * инвентарь был весь в резерве под чужой (потерянной) заявкой, счётчик
     * позиции показывал его целиком, и цикл раз в секунду просил продать то,
     * чего у него не было.
     *
     * Своя же стоящая заявка резерв РАСШИРЯЕТ: замена его возвращает, поэтому её
     * объём прибавляется к доступному. Иначе перевыставить полностью
     * зарезервированную заявку стало бы невозможно.
     */
    static double affordable(Side side, double price, double baseAvailable,
                             double quoteAvailable, double ownSize, double ownPrice) {
        if (side == Side.SELL) {
            return Math.max(0, baseAvailable + ownSize);
        }
        if (!(price > 0)) {
            return 0;
        }
        return Math.max(0, quoteAvailable + ownSize * ownPrice) / price;
    }

    /**
     * Отсутствие средств — не ошибка, но и не норма: на споте это означает, что
     * стратегия стала односторонней. Молчать об этом нельзя (именно тишина
     * скрывала ночную аварию), а писать каждую секунду — бесполезно.
     */
    private void warnNoFunds(Side side, Resting resting) {
        // Дальний уровень пуст по построению, когда запаса меньше числа уровней:
        // пул выбран внутренними. Это не односторонняя стратегия, а норма, и у f
        // (три уровня, один лот) давало ~70 записей в час (27.09.2026). Говорим
        // только о БЛИЖНЕМ уровне — если нечем и ему, сторона действительно пуста.
        if (resting != (side == Side.BUY ? bids : asks).get(0)) {
            return;
        }
        long now = clock.now();
        if (now - resting.fundsWarnedMs < 60_000) {
            return;
        }
        resting.fundsWarnedMs = now;
        String detail = side + ": доступно " + fmt(side == Side.SELL ? baseAvailable : quoteBalance)
                + ", позиция " + fmt(inventory);
        log.warn("сторона {} не котируется — нечем ({})", side, detail);
        journal.event("no_funds", detail);
    }

    /**
     * Дрейф опоры за окно из конфига — по СВОЕЙ истории справедливых цен.
     *
     * Считается здесь, а не берётся из стенда, потому что источник цены у
     * исполнителя один и тот же ряд, который он уже видит раз в секунду. Кольцо
     * ограничено окном: память не растёт.
     *
     * Живое и модель обязаны считать дрейф ОДИНАКОВО — расхождение шага окна
     * (док. 94 §1) стоило дня разбирательств, повторять не надо.
     */
    private void rememberFair(double fair) {
        long window = params.driftWindowMs();
        if (!(fair > 0) || params.driftBeta() == 0 || window <= 0) {
            return;
        }
        long now = clock.now();
        fairHistory.addLast(new long[]{now, Double.doubleToRawLongBits(fair)});
        while (!fairHistory.isEmpty() && fairHistory.peekFirst()[0] < now - 2 * window) {
            fairHistory.pollFirst();
        }
    }

    private double drift() {
        long window = params.driftWindowMs();
        if (params.driftBeta() == 0 || window <= 0 || fairHistory.isEmpty()) {
            return 0;
        }
        long since = clock.now() - window;
        double anchor = 0;
        for (long[] point : fairHistory) {
            if (point[0] <= since) {
                anchor = Double.longBitsToDouble(point[1]);
            } else {
                break;
            }
        }
        // Якоря нет — истории ещё не накопилось; дрейф считаем нулевым, а не
        // выдумываем его по огрызку окна.
        return anchor > 0 && lastFair > 0 ? (lastFair - anchor) / anchor : 0;
    }

    private double exposure() {
        // Считаются ВСЕ уровни: предел экспозиции задан на бота, а не на заявку,
        // и сетка из трёх уровней занимает втрое больше книги.
        double resting = 0;
        for (Resting r : bids) {
            resting += r.venueId != null ? r.price * r.size : 0;
        }
        for (Resting r : asks) {
            resting += r.venueId != null ? r.price * r.size : 0;
        }
        return inventory * lastFair + resting;
    }

    /**
     * Постановок за последние 24 часа — ПО ЖУРНАЛУ, скользящим окном.
     *
     * Счётчик в памяти для этого не годится: он обнулялся на каждом запуске, а у
     * бота A их было 23, то есть предел не действовал ни разу. Журнал переживает
     * рестарт и деплой.
     *
     * Окно скользящее, потому что момент обнуления тысячи у площадки нам
     * неизвестен: за всю историю ни одного отказа по лимиту не приходило.
     * «Не более N за любые 24 часа» безопасно и при обнулении в полночь, и при
     * скользящем окне у них; обратное неверно.
     *
     * ⚠️ Это АВАРИЙНЫЙ потолок, а не рабочий. Делит бюджет между ботами теперь
     * {@link PlacementBudget}; здесь остался предохранитель на случай, когда
     * ведро недоступно или ошибочно щедро.
     */
    public long placementsLastDay() {
        return journal.placementsSince(clock.now() - 86_400_000L);
    }

    private void place(Side side, Resting resting, double price, double size) {
        trace("PLACE", side, resting, price, size);
        // Сначала общее ведро: оно и есть настоящий предел аккаунта.
        //
        // ⚠️ Отказ НЕ выключает бота. Прежде выключал, и 07.09.2026 это стоило
        // 11 часов простоя ADA и 7 часов PEPE — при том, что у SOL и ETH в те же
        // сутки пустовало 240 постановок. Пропущенная постановка обратима сама
        // собой: ведро пополнится, отступ сузится обратно. Выключенный бот сам
        // не возвращается.
        if (budget != null && !budget.tryAcquire(tag.id(), clock.now())) {
            journal.event("budget_denied", side + " по " + fmt(price)
                    + ": общий бюджет постановок исчерпан, отхожу от цены");
            return;
        }
        long used = placementsLastDay();
        if (used >= placementCap()) {
            log.error("исчерпан АВАРИЙНЫЙ потолок постановок ({} из {} за 24 ч) — "
                            + "останавливаю котирование",
                    used, placementCap());
            journal.event("limit_blocked",
                    "постановки за сутки: " + used + " из "
                            + placementCap());
            stopQuoting();
            return;
        }
        String body = """
                {"client_order_id":"%s","symbol":"%s","side":"%s",
                 "order_configuration":{"limit":{"base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}}}"""
                .formatted(tag.newClientOrderId(), symbol.replace('/', '-'),
                        side == Side.BUY ? "buy" : "sell", fmtSize(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        Venue.Response response = client.place(body);
        placements++;
        if (response.ok()) {
            resting.venueId = extract(response.body());
            rememberLevel(side, resting);
            // 🔑 ИДЕНТИФИКАТОР НА ДИСК СРАЗУ. Пока он жил только в поле объекта,
            // конец процесса стирал его вместе с заявкой: исполнение в момент
            // перезапуска терялось навсегда (13.09.2026, продажа dc4d7e77).
            journal.openOrder(resting.venueId, side.name(), levelOf(side, resting),
                    price, size, clock.now());
            resting.price = price;
            resting.size = size;
            resting.sinceMs = clock.now();
            resting.failures = 0;
            resting.blockedUntilMs = 0;
        } else {
            // Отказ на постановке тратит суточный лимит и ничего не даёт. Пауза
            // растёт с каждым отказом подряд: даже неизвестная причина не должна
            // успевать съесть тысячу постановок, как в ночь на 29.08.2026.
            resting.failures++;
            // ⚠️ Токен ведра возвращаем, если заявки ТОЧНО не возникло.
            // Определённый отказ площадки (4xx без идентификатора) заявки не
            // создаёт, и тратить на него общий суточный бюджет не за что. На
            // 5xx и на отсутствие ответа токен остаётся потраченным: там
            // неизвестно, создалась заявка или нет.
            boolean definitelyNotPlaced = budget != null
                    && response.status() >= 400 && response.status() < 500
                    && extract(response.body()) == null;
            if (definitelyNotPlaced) {
                budget.refund(tag.id(), clock.now());
            }
            long pause = Math.min(MAX_PLACE_BACKOFF_MS, 5_000L << Math.min(4, resting.failures - 1));
            resting.blockedUntilMs = clock.now() + pause;
            log.warn("постановка {} не прошла: {} {} — пауза {} с", side, response.status(),
                    response.body(), pause / 1000);
            journal.event("place_failed", side + " " + response.status() + ", пауза "
                    + pause / 1000 + " с");
            // Самая частая причина отказа — средства заняты заявкой, о которой мы
            // забыли. Сверка её найдёт и либо усыновит, либо снимет.
            refreshBalances();
            reconcile("отказ постановки");
        }
    }

    private void replace(Side side, Resting resting, double price, double size) {
        trace("REPLACE", side, resting, price, size);
        if (replacesThisMinute >= ExecLimits.MAX_REPLACES_PER_MINUTE) {
            return;                       // защита от зацикливания; молча, но с паузой
        }
        if (resting.partial()) {
            // Последний рубеж: замена частично исполненной заявки ОБРЕЧЕНА, и
            // отправлять её значит платить запросом за заведомый 422. Оба пути —
            // встроенный и модульный — сюда сходятся, поэтому проверка здесь.
            return;
        }
        String heirClientId = tag.newClientOrderId();
        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(heirClientId, fmtSize(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        Venue.Response response = client.replace(resting.venueId, body);
        replaces++;
        replacesThisMinute++;
        noteVenueStall(response.latencyMs());
        if (response.ok()) {
            // ⚠️ ПРЕДШЕСТВЕННИКА НАДО ДОПРОСИТЬ, ПОКА ЕГО ЕЩЁ ЕСТЬ О ЧЁМ
            // СПРОСИТЬ. Замена создаёт другую заявку, старый идентификатор
            // умирает, и если по нему что-то исполнилось частично, узнать об
            // этом больше неоткуда: единственный путь бота к сделке — заявка,
            // которой не стало, а частично исполненная из книги не исчезает.
            //
            // При лоте $1 это не срабатывает никогда: все 390 живых сделок за
            // сутки ровно в один лот, принты крупнее нашей заявки. При лоте $3
            // на стенде так терялось 30% объёма (08.09.2026) — и боевой бот на
            // крупном лоте терял бы столько же молча.
            //
            // Цена — один GET на замену. При измеренных 2.77 замены в секунду на
            // весь счёт это ~166 запросов в минуту при лимите 1000/мин, то есть
            // укладывается, но это не бесплатно.
            String oldId = resting.venueId;
            String newId = extract(response.body());
            resting.venueId = newId != null ? newId : resting.venueId;
            rememberLevel(side, resting);
            // Наследник — на диск, предшественник — закрыт: замена УБИВАЕТ
            // старую заявку, и держать её в списке незакрытых значит спрашивать
            // о ней площадку при каждом старте.
            journal.openOrder(resting.venueId, side.name(), levelOf(side, resting),
                    price, size, clock.now());
            if (oldId != null && !oldId.equals(resting.venueId)) {
                journal.closeOrder(oldId, "replaced", clock.now());
                // 🔑 ЭТАП 3: при живом читателе предка не допрашиваем — его частичное
                // исполнение придёт лентой по его oid (myOrders его помнит). Это
                // ~один GET на замену, ~200 GET/мин на шесть ботов (28.09.2026).
                if (!venueCovers()) {
                    inspectGoneOrder(side, oldId);
                }
            }
            resting.price = price;
            resting.size = size;
            resting.sinceMs = clock.now();
            resting.failures = 0;
            resting.blockedUntilMs = 0;
        } else {
            // ⚠️ 422 на замене НЕ ЗНАЧИТ, что замены не было. 30.08.2026 площадка
            // ответила «Cannot replace an order that is not in the NEW state», а
            // сама заявка уже числилась cancelled/replaced — наследник был создан
            // и остался в книге без хозяина. Судьбу заявки нельзя выводить из её
            // собственного статуса: спрашиваем СПИСОК АКТИВНЫХ, и он же решает,
            // усыновить наследника, снять дубль или признать заявку исполненной.
            //
            // Забывать заявку здесь нельзя. Первая версия обнуляла id сразу, и
            // если сверка слот не восстанавливала, следующий тик ставил ВТОРУЮ
            // заявку поверх живой (01.09.2026, 06:26 — резерв удвоился).
            log.info("замена {} не прошла ({}), сверяюсь с книгой", side, response.status());
            resting.failures++;
            // ⚠️ Отказ «не в состоянии NEW» — это НЕ «повтори позже», а «эта
            // заявка больше никогда не заменится». Слепая пауза с удвоением
            // превращала его в серию: 09.09.2026 бот A получил по одной заявке
            // восемь таких отказов за 84 секунды. Спрашиваем судьбу сразу — один
            // запрос вместо семи лишних.
            String fate = resolveNotNew(side, resting, response.body());
            // ⚠️ ПОДОЗРЕВАЕМЫЙ НАСЛЕДНИК. Замена могла ПРОЙТИ, а ответ не дойти
            // (док. 111): наследник создан, его venue_order_id не вернулся, и
            // спросить о нём площадку НЕЧЕМ — зонд 14.09.2026 показал, что заявка
            // адресуется только по venue_order_id, а истории заявок у площадки
            // нет. Обычно наследника находит сверка в книге; если он успел
            // исполниться раньше — исполнение теряется (бот A, 06:31:54).
            //
            // Запоминаем, ЧТО именно мы пытались поставить: сторону, размер,
            // цену и свой клиентский идентификатор. Дальше этим занимается
            // {@link #claimHeirIfEvidenceMatches}.
            // ⚠️ ПОДОЗРЕНИЕ ЗАВОДИТСЯ, ТОЛЬКО ЕСЛИ НАСЛЕДНИК ВОЗМОЖЕН.
            //
            // «filled» и «partially_filled» означают, что исполнилась САМА заявка:
            // никакой замены не произошло, наследника нет, и исполнение уже
            // проведено в resolveNotNew. Первая версия заводила подозрение на
            // ЛЮБОЙ 422, и живой бот C 14.09.2026 в 10:03:50 сразу после продажи
            // выдал ложную тревогу про наследника.
            boolean heirPossible = fate == null
                    || (!"filled".equalsIgnoreCase(fate) && !"partially_filled".equalsIgnoreCase(fate));
            if (response.status() == 422 && heirPossible) {
                // Запоминаем и СВОБОДНУЮ КАССУ в котле на этот момент: уликой
                // служит её ИЗМЕНЕНИЕ, а не размер — см. claimHeirIfEvidenceMatches.
                heir = new Heir(side, size, price, heirClientId, clock.now(), freePot(clock.now()));
                heirEvidence = 0;
            }
            // 🔑 ЭТАП 3, §3.6: МЕДЛЕННЫЙ 422 НА ЗАМЕНЕ — ПОДОЗРЕНИЕ НА ПРИЗРАКА.
            // Предок снят внутри этого же запроса, наследника нет, а резерв
            // предка площадка держит часами. Запоминаем, СКОЛЬКО он держал: это
            // наше, но недоступное, пока сверка не скажет иначе.
            if (VENUE_LOCKS && response.status() == 422 && heirPossible
                    && response.latencyMs() >= SLOW_REPLACE_MS && resting.size > 0) {
                addGhostSuspect(side, resting.size, resting.price);
            }
            // Пауза на сторону: без неё каждый отказ тянет за собой четыре запроса
            // (замена, статус, остатки, активные), и на устойчивом отказе это
            // 8 запросов в секунду по кругу — наблюдалось 01.09.2026.
            resting.blockedUntilMs = clock.now()
                    + Math.min(MAX_PLACE_BACKOFF_MS, 2_000L << Math.min(5, resting.failures - 1));
            reconcile("отказ замены");
            refreshBalances();
        }
    }

    /**
     * Отказ «Cannot replace an order that is not in the 'NEW' state»: выяснить
     * судьбу заявки НЕМЕДЛЕННО, а не ждать, пока догадается сверка.
     *
     * <h2>Почему это отдельный случай, а не общий отказ</h2>
     *
     * Общий отказ («заняты средства», «слишком часто») означает «попробуй
     * позже». Этот — не означает: состояние заявки уже изменилось необратимо, и
     * следующая попытка получит тот же ответ. Ждать нечего, а вопрос ровно один:
     * ЧТО с ней стало. Ответ даёт сама площадка, тремя разными исходами:
     *
     * <ul>
     *   <li>{@code filled} — заявку исполнили целиком. Слот освобождается СРАЗУ,
     *       и следующий тик ставит новую. Раньше это выяснялось только через
     *       {@link #ADOPT_GRACE_MS} и паузу отказа: 09.09.2026 в 14:26 бот A
     *       узнал об исполнении через 9 секунд и три отказа, а всё это время
     *       сторона стояла пустой;</li>
     *   <li>{@code partially_filled} — исполнена часть. Заменить её нельзя до
     *       конца жизни, поэтому слот ПОМЕЧАЕТСЯ и замены на него больше не
     *       тратятся. Исполненная часть проводится здесь же;</li>
     *   <li>{@code cancelled/replaced} — наследник создан, а ответ до нас не
     *       дошёл (док. 111). Ничего не решаем: наследника найдёт и усыновит
     *       сверка, которая идёт следующей строкой.</li>
     * </ul>
     *
     * Цена — один GET на отказ, при лимите 100/с и 1000/мин. Взамен исчезает
     * серия из PUT, GET активных и GET остатков на каждой попытке.
     */
    /**
     * @return статус, который назвала площадка, либо {@code null}, если не ответила.
     *         По нему решается, заводить ли подозрение о наследнике: «filled» и
     *         «partially_filled» означают, что исполнилась САМА заявка и никакого
     *         наследника нет.
     */
    private String resolveNotNew(Side side, Resting resting, String body) {
        // ⚠️ СУДЬБУ СПРАШИВАЕМ НА ЛЮБОЙ 422, А НЕ ТОЛЬКО НА «не в состоянии NEW».
        //
        // Раньше здесь стояла проверка текста, а «Could not replace order with
        // id: X» считался временным отказом площадки при живой заявке — так было
        // записано 09.09.2026 после проверки списком активных через 40 мс.
        // **Это опровергнуто 10.09.2026, и опровергнуто дорого.** Бот A получил
        // по заявке на покупку ВОСЕМЬ таких отказов за 13 секунд, ответил на них
        // паузой и отменой, — а заявка в это время ИСПОЛНИЛАСЬ: остаток счёта
        // вырос ровно на лот в 12:47:23, и ни одной записи об этом в журнале нет.
        // Лот остался на счёте ничейным на восемь часов, пока владелец не забрал
        // его вручную через /claim.
        //
        // Отличить «временный отказ» от «уже исполнена» ПО ТЕКСТУ нельзя: текст
        // один и тот же. Отличить можно только вопросом к площадке. Цена — один
        // GET на отказ (17 таких за сутки при лимите 100/с), и она несопоставима
        // с ценой потерянного исполнения: бот узнаёт о сделке ТОЛЬКО так, и
        // незамеченная сделка не восстанавливается уже никогда.
        if (resting.venueId == null) {
            return null;
        }
        String id = resting.venueId;
        Venue.Response order = client.order(id);
        if (!order.ok() || order.body() == null) {
            return null;                  // не знаем — оставляем всё как было
        }
        String status = field(order.body(), "status");
        // ⚠️ Ответ передаётся дальше, а не запрашивается заново. Первая версия
        // звала inspectGoneOrder без него, и в живом журнале 09.09.2026 виден
        // ОДИН И ТОТ ЖЕ GET дважды с разницей в 60 мс. Лимит это переживает
        // (100/с), но повторный вопрос о том же — приглашение однажды получить
        // два разных ответа и провести исполнение по второму.
        if ("partially_filled".equalsIgnoreCase(status)) {
            markPartial(side, resting, number(order.body(), "filled_quantity"),
                    number(order.body(), "quantity"));
            book(side, id, order.body()); // провести исполненную часть, пока она видна
        } else if ("filled".equalsIgnoreCase(status)) {
            closePartial(resting, "добрана");
            book(side, id, order.body());
            resting.venueId = null;       // слот свободен: пустота дороже паузы
            resting.blockedUntilMs = 0;
            resting.failures = 0;
        }
        // Прочие состояния (cancelled/replaced) разбирает сверка: судьбу заявки
        // нельзя выводить из её собственного статуса — только из списка активных.
        return status;
    }

    /**
     * Пометить слот как «стоит частично исполненная заявка».
     *
     * ⚠️ Событие пишется ОДИН РАЗ на заявку, а не на каждое обнаружение: пометка
     * ставится каждой сверкой заново, а мерить надо частичные исполнения, а не
     * число сверок.
     */
    private void markPartial(Side side, Resting resting, double filled, double quantity) {
        if (resting.partial()) {
            resting.partialFilled = filled;
            return;                       // уже помечена, счётчик не портим
        }
        resting.partialId = resting.venueId;
        resting.partialSinceMs = clock.now();
        resting.partialFilled = filled;
        partials++;
        double share = quantity > 0 ? filled / quantity * 100 : 0;
        String text = side + " " + resting.venueId + " исполнено " + fmt(filled)
                + " из " + fmt(quantity) + " (" + Math.round(share) + "%), замена невозможна";
        log.warn("частичное исполнение: {}", text);
        journal.event("partial", text);
    }

    /**
     * Частичная заявка дожила до развязки — записать, СКОЛЬКО она ждала.
     *
     * Это и есть то измерение, ради которого всё затевалось: решение «ждать или
     * снимать и ставить заново» упирается в вопрос, как часто остаток добирается
     * сам и за какое время. Один наблюдённый случай (84 секунды) статистикой не
     * является. Ответ читается из журнала: {@code SELECT detail FROM exec_event
     * WHERE kind = 'partial_done'}.
     */
    private void closePartial(Resting resting, String outcome) {
        if (!resting.partial()) {
            resting.partialId = null;
            return;
        }
        long waited = Math.max(0, clock.now() - resting.partialSinceMs);
        String text = resting.partialId + " " + outcome + " через " + waited / 1000
                + " с, частичное было " + fmt(resting.partialFilled);
        log.warn("частичная заявка: {}", text);
        journal.event("partial_done", text);
        resting.partialId = null;
    }

    /**
     * Сверка с площадкой: в книге должны стоять РОВНО те заявки, которые мы
     * помним, — по одной на сторону, и ни одной, пока котирование выключено.
     *
     * Инвариант проверяется у площадки, а не у себя, потому что расхождение
     * ровно в том и состоит, что наша память неверна. Три исхода:
     *
     * <ul>
     *   <li>заявка на стороне есть, id другой — УСЫНОВЛЯЕМ. Так выглядит замена,
     *       выполненная площадкой и отвергнутая в ответе;</li>
     *   <li>заявок на стороне больше одной — лишние СНИМАЕМ. На сторону может
     *       стоять только одна: вторая удваивает риск и морозит средства;</li>
     *   <li>заявки нет, а мы её помним — выясняем судьбу (исполнилась) и
     *       забываем. Но только если id уже не свежий: список активных может
     *       отставать от постановки, и поспешный вывод «исчезла» приведёт к
     *       дублю.</li>
     * </ul>
     */
    private void reconcile(String why) {
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            return;                       // не знаем состояние — ничего не трогаем
        }
        java.util.List<ActiveOrder> all = ActiveOrder.parse(active.body()).stream()
                .filter(o -> ActiveOrder.normalize(symbol).equals(o.symbol()))
                .toList();
        // ⚠️ Фильтр по МЕТКЕ обязателен: на одном аккаунте и одной паре список
        // активных отдаёт и заявки соседнего бота. Без него сверка снимала бы их
        // как «бесхозные» каждую минуту — первое, что ломается при параллельном
        // запуске. Заявка без клиентского идентификатора считается ЧУЖОЙ: молчаливо
        // присвоить чужое хуже, чем оставить в книге хвост.
        java.util.List<ActiveOrder> orders = all.stream()
                .filter(o -> tag.owns(o.clientId()))
                .toList();
        int foreign = all.size() - orders.size();
        if (foreign > 0) {
            log.debug("в книге {} чужих заявок по {} — не трогаю", foreign, symbol);
        }
        forgetHeirIfVisible(orders);
        if (!quoting.get()) {
            // Правило 1: пока котирование выключено, наших ТОРГУЮЩИХ заявок в
            // книге быть не должно.
            //
            // ⚠️ Исключение — припаркованные, и без него вся парковка бессмысленна:
            // бот стартует с выключенным котированием, сверка идёт сразу, и она
            // снесла бы ровно то, что {@link Park} оставил специально, чтобы не
            // платить за восстановление постановками. Найдено при выкатке
            // 07.09.2026, до неё парковка не экономила бы ничего.
            //
            // Припаркованной считается заявка дальше {@link #PARKED_MIN_PCT} от
            // последней надёжной цены. Такая заявка — не позиция, а держатель
            // места: исполниться она может только на движении в несколько
            // процентов, а вернуть её в работу можно заменой, то есть даром.
            // Цены не знаем — снимаем всё, как раньше: неизвестность обязана
            // работать в сторону осторожности.
            java.util.List<ActiveOrder> parked = new java.util.ArrayList<>();
            for (ActiveOrder order : orders) {
                if (isParked(order.price())) {
                    parked.add(order);
                } else {
                    cancelStray(order, why);
                }
            }
            // ⚠️ Припаркованные РАСКЛАДЫВАЕМ ПО СЛОТАМ, а не забываем.
            //
            // Прежде слоты обнулялись целиком, и это сводило парковку на нет:
            // включённый бот видел пустые слоты, ставил шесть новых заявок, а
            // припаркованные оказывались лишними и уходили под нож плановой
            // сверки. Замерено на живом включении 07.09.2026 — 31 постановка
            // вместо нуля, ровно столько же, сколько стоил бы честный рестарт
            // с отменой.
            //
            // Помня о них, бот вернёт их на место ЗАМЕНОЙ, у которой суточного
            // лимита нет.
            adoptSide(Side.BUY, bids, parked, why);
            adoptSide(Side.SELL, asks, parked, why);
            return;
        }
        adoptSide(Side.BUY, bids, orders, why);
        adoptSide(Side.SELL, asks, orders, why);
    }

    /**
     * Сверка стороны с книгой при нескольких уровнях.
     *
     * Наши заявки раскладываются по уровням ПО ЦЕНЕ: лучшая достаётся ближнему
     * к рынку уровню, следующая — второму и так далее. Привязать их к уровням
     * иначе нечем — площадка о наших уровнях не знает, а порядок цен совпадает с
     * порядком уровней по построению лесенки.
     *
     * При одном уровне это ровно прежнее поведение: единственная заявка идёт в
     * единственный слот, всё лишнее снимается как бесхозное.
     */
    private void adoptSide(Side side, java.util.List<Resting> slots,
                           java.util.List<ActiveOrder> orders, String why) {
        java.util.List<ActiveOrder> mine = new java.util.ArrayList<>(orders.stream()
                .filter(o -> o.side() == side)
                .toList());
        mine.sort(side == Side.BUY
                ? java.util.Comparator.comparingDouble(ActiveOrder::price).reversed()
                : java.util.Comparator.comparingDouble(ActiveOrder::price));

        // ⚠️ СНАЧАЛА каждый слот ищет СВОЮ заявку по идентификатору, и только
        // потом незанятые слоты разбирают остаток по цене.
        //
        // Раскладка чисто по рангу цены теряет исполнения. Исполнилась заявка
        // ближнего уровня и исчезла — следующая по цене становится лучшей и
        // занимает его слот. Бот видит «в слоте заявка есть» и об исполнении не
        // узнаёт никогда: inspectGoneOrder не вызывается, инвентарь не растёт.
        // Измерено 05.09.2026: три бида стояли в книге по 90% времени каждый, а
        // покупок вышло 216 против 340 у ОДНОГО бида при 99% — то есть больше
        // трети исполнений просто терялось.
        java.util.Set<String> taken = new java.util.HashSet<>();
        ActiveOrder[] byId = new ActiveOrder[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            String own = slots.get(i).venueId;
            if (own == null) {
                continue;
            }
            for (ActiveOrder o : mine) {
                if (o.id().equals(own)) {
                    byId[i] = o;
                    taken.add(o.id());
                    break;
                }
            }
        }
        java.util.List<ActiveOrder> rest = new java.util.ArrayList<>();
        for (ActiveOrder o : mine) {
            if (!taken.contains(o.id())) {
                rest.add(o);
            }
        }
        int next = 0;
        for (int i = 0; i < slots.size(); i++) {
            ActiveOrder found = byId[i];
            if (found == null && next < rest.size()) {
                found = rest.get(next++);
            }
            adopt(side, slots.get(i), found, why);
        }
        for (int i = next; i < rest.size(); i++) {
            cancelStray(rest.get(i), why);
        }
    }

    /**
     * Сколько по каждой заявке уже записано. Ключ живёт до вытеснения.
     *
     * <h2>Зачем помнить</h2>
     *
     * {@code filled_quantity} площадки НАКОПИТЕЛЬНЫЙ, а спрашиваем мы его не
     * один раз: заявка может исполниться частично, потом ещё, и только потом
     * исчезнуть. Записывать надо РАЗНИЦУ, иначе первый объём попадёт в журнал
     * дважды.
     *
     * Это не теория: 08.09.2026 в живом журнале бота D нашлось исполнение ADA,
     * записанное дважды (тот же {@code venue_id}, 10 с спустя, 4.5981 оба раза).
     * Поймано сверкой суммы записей с фактическим изменением остатка на счёте —
     * BTC и ETH сошлись до знака, ADA разошлась ровно на лот. Инвентарь бота от
     * такой записи смещается навсегда.
     */
    private final java.util.Map<String, Double> bookedByOrder =
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Double> eldest) {
                    return size() > 512;
                }
            };

    private void adopt(Side side, Resting resting, ActiveOrder keep, String why) {
        if (keep == null) {
            boolean fresh = clock.now() - resting.sinceMs < ADOPT_GRACE_MS;
            if (resting.venueId != null && !fresh) {
                String status = inspectGoneOrder(side, resting.venueId);
                // 🔑 «НЕТ В СПИСКЕ АКТИВНЫХ» НЕ ЗНАЧИТ «ЗАЯВКИ НЕТ».
                //
                // Прежде слот очищался при ЛЮБОМ ответе, и если площадка
                // говорила `new` — то есть заявка жива, просто не попала в
                // список, — бот забывал её навсегда. Такая заявка остаётся в
                // книге, её никто не заменит и не снимет, а её резерв делает
                // монету неотчуждаемой: 15.09.2026 на счёте так зависли ВСЕ
                // ETH, BTC и SOL (`available` ноль при непустом остатке), и
                // боты перестали продавать вовсе.
                //
                // Теперь живую заявку слот удерживает: следующий тик заменит её
                // как обычно. Неизвестную судьбу (ответа нет) тоже удерживаем —
                // её добьёт очередь `unknownFate`.
                if (status != null && !terminal(status)) {
                    if (clock.now() - resting.sinceMs > 60_000) {
                        journal.event("kept_alive", side + " " + resting.venueId
                                + ": нет в списке активных, но площадка говорит «" + status
                                + "» — заявку держу, не забываю");
                    }
                    return;
                }
                closePartial(resting, "filled".equalsIgnoreCase(status)
                        ? "добрана" : "ушла из книги (" + status + ")");
                resting.venueId = null;
                refreshBalances();
            }
            return;
        }
        if (keep.id().equals(resting.venueId)) {
            // ⚠️ ПОМЕТКА БЕРЁТСЯ ИЗ ОТВЕТА ПЛОЩАДКИ на каждой сверке, а не
            // помнится. Список активных сам называет состояние заявки
            // (`partially_filled`), и потому пометка не может ни устареть, ни
            // застрять: пропала заявка — пропала и она.
            if (keep.partiallyFilled()) {
                markPartial(side, resting, keep.filled(), keep.filled() + keep.size());
            }
            // ⚠️ ЗАЯВКА НА МЕСТЕ, НО ПОХУДЕЛА — значит её частично исполнили.
            //
            // Единственный путь, которым бот узнаёт о сделке, — исчезнувшая
            // заявка. Частично исполненная не исчезает: она остаётся в книге с
            // меньшим leaves_quantity, бот её ЗАМЕНЯЕТ, наследник получает новый
            // идентификатор, и запись об исполнении не появляется нигде.
            //
            // При лоте $1 этого не случается вовсе — все 390 живых сделок за
            // сутки ровно в один лот, принты крупнее нашей заявки. Но на стенде
            // с лотом $3 так терялось 33% объёма (08.09.2026), и при переходе
            // на крупный лот боевой бот начал бы терять сделки молча.
            if (keep.size() < resting.size - 1e-12) {
                inspectGoneOrder(side, resting.venueId);
                resting.size = keep.size();
            }
            return;
        }
        // ⚠️ Прежний хозяин слота уходит вместе со своим идентификатором, и
        // спросить о нём после усыновления будет уже некому. Спрашиваем сейчас:
        // если он исполнился, запись должна появиться. Повторного счёта нет —
        // {@link #bookedByOrder} помнит, сколько по нему уже записано.
        if (resting.venueId != null) {
            String status = inspectGoneOrder(side, resting.venueId);
            closePartial(resting, "сменилась наследником (" + status + ")");
        }
        log.warn("усыновляю заявку {} {} по {} ({})", side, keep.id(), keep.price(), why);
        journal.event("adopt", side + " " + keep.id() + " по " + fmt(keep.price())
                + " (" + why + ")");
        resting.venueId = keep.id();
        if (keep.id().equals(resting.questioned)) {
            resting.questioned = null;        // «снятая» оказалась живой — ведём её дальше
        }
        rememberLevel(side, resting);
        resting.price = keep.price();
        resting.size = keep.size();
        resting.sinceMs = clock.now();
        if (keep.partiallyFilled()) {
            // Усыновлённая заявка тоже бывает частично исполненной: наследник
            // мог успеть поймать часть, пока мы о нём не знали.
            markPartial(side, resting, keep.filled(), keep.filled() + keep.size());
        }
        // Наследник найден — значит предыдущий отказ был мнимым, и держать
        // за него паузу не за что.
        resting.failures = 0;
        resting.blockedUntilMs = 0;
    }

    private void cancelStray(ActiveOrder order, String why) {
        Venue.Response response = client.cancel(order.id());
        cancels++;
        log.warn("снимаю бесхозную заявку {} {} по {} ({}) → {}", order.side(), order.id(),
                order.price(), why, response.status());
        journal.event("stray_cancel", order.side() + " " + order.id() + " по "
                + fmt(order.price()) + " (" + why + ") → " + response.status());
        if (response.ok()) {
            journal.closeOrder(order.id(), "cancelled", clock.now());
        }
    }

    /**
     * Что случилось с исчезнувшей заявкой. Ответ площадки содержит фактическую
     * цену исполнения, объём и комиссию — всё то, что иначе пришлось бы выводить
     * из разницы остатков, теряя точность и путаясь при нескольких исполнениях
     * подряд.
     *
     * Здесь же срабатывает предохранитель по комиссии: конструкция измерялась
     * при maker 0% (тариф сверен, задача A31 — это штатная схема без ступеней, а
     * не промо), и появление любой ненулевой комиссии означает смену экономики, а
     * не параметра. Решение принимает человек.
     */
    private String inspectGoneOrder(Side side, String venueId) {
        Venue.Response order = client.order(venueId);
        if (!order.ok() || order.body() == null) {
            // ⚠️ СУДЬБА НЕИЗВЕСТНА — И ЗАБЫВАТЬ ЭТОТ ВОПРОС НЕЛЬЗЯ.
            //
            // Прежде здесь стояло `fills++` и возврат: счётчик прибавлялся, а
            // позиция, касса и реестр оставались прежними НАВСЕГДА. Если заявка
            // на самом деле исполнилась, бот про это не узнавал никогда.
            //
            // Так и случилось 13.09.2026 в 22:06 UTC: с края пришёл залп 429
            // (тело HTML, не JSON площадки), три повтора `GET /orders/{id}`
            // получили отказ подряд, и продажа лота BTC у бота e потерялась.
            // Монета ушла со счёта, а в журнале осталась — через минуту сторож
            // расхождения позиции закричал, и правильно сделал.
            //
            // Теперь вопрос попадает в очередь и повторяется раз в минуту, пока
            // площадка не ответит. Повторный учёт не страшен: {@link #book}
            // записывает РАЗНИЦУ по {@code bookedByOrder}, поэтому один и тот же
            // ответ дважды инвентарь не сдвинет.
            if (unknownFate.putIfAbsent(venueId, new UnknownFate(side, clock.now())) == null) {
                journal.event("fate_unknown", String.format(java.util.Locale.ROOT,
                        "%s %s: площадка не ответила о судьбе (%d), спрошу ещё",
                        side, venueId, order.status()));
                log.warn("судьба заявки {} неизвестна (ответ {}) — вопрос поставлен в очередь",
                        venueId, order.status());
            }
            return null;
        }
        unknownFate.remove(venueId);
        return book(side, venueId, order.body());
    }

    /**
     * Заявка, которую площадка, возможно, создала, не вернув идентификатора.
     *
     * @param clientId наш {@code client_order_id} — единственное, чем мы её знаем
     */
    private record Heir(Side side, double size, double price, String clientId, long sinceMs,
                        double freePot) {
    }

    /**
     * Замена дольше этого — признак затыка площадки, на котором рождаются
     * призраки.
     *
     * Порог взят по измерению 14–15.09.2026, а не на глаз: медиана нормальной
     * замены — 170 мс на 291 729 запросах шести ботов, а у сорока замен,
     * оставивших неснимаемый резерв, медиана 3004 мс, и 27 из 40 лежат в полосе
     * 2.5–3.3 с. Между 170 мс и 2.5 с пустота, так что секунда разделяет два
     * режима с запасом в обе стороны.
     */
    static final long SLOW_REPLACE_MS = 1_000;

    /**
     * Сколько стоим в стороне после затыка.
     *
     * Призраки приходят ПАЧКАМИ: 40 штук за двое суток уместились примерно в
     * дюжину всплесков, и внутри всплеска все шесть ботов получали отказ в одну
     * и ту же секунду (чаще всего HH:38:32). Значит смысл паузы — переждать
     * всплеск целиком, а не отдельный отказ; минуты на это хватает, а стоит она
     * при отступе 12 б.п. немногого.
     */
    static final long STALL_STAND_ASIDE_MS = 60_000;

    /** До этого момента не котируем: площадка отвечает на замены слишком долго. */
    private volatile long stallUntilMs;

    /** Сколько призраков насчитали за жизнь процесса. */
    private volatile int ghosts;

    /**
     * 🔑 ЗАТЫК ПЛОЩАДКИ: отойти в сторону, а не продолжать замены.
     *
     * Призрак — неснимаемый резерв — рождается только на медленной замене:
     * площадка успевает отменить исходную заявку и создать наследника, а потом
     * упирается в свой трёхсекундный таймаут и возвращает 422 БЕЗ
     * идентификатора наследника (задача A48). Наследник держит монету, а снять
     * его нечем: по {@code client_order_id} площадка спрашивать не даёт.
     *
     * Отсюда единственная профилактика, доступная нам: заметив первую медленную
     * замену, перестать их слать. Убрать замены вовсе нельзя — их 291 729 за
     * двое суток при лимите постановок 1000 в сутки, то есть схема «отменить и
     * поставить заново» невозможна в двести раз.
     *
     * ⚠️ Отходим В СТОРОНУ, а не просто замолкаем: оставить заявку в книге на
     * минуту затыка значит держать несвежую котировку ровно тогда, когда
     * площадка ведёт себя странно. {@link #standAside} уводит её из зоны
     * исполнения.
     */
    /**
     * Почему не котируем по вине площадки, либо {@code null}, если котируем.
     *
     * Вынесено отдельно, чтобы причину можно было проверить тестом, не поднимая
     * весь цикл: обе паузы — про состояние, а не про поток событий.
     */
    String venuePauseReason() {
        return venuePause(frozenPaused, clock.now(), stallUntilMs);
    }

    /**
     * ⚠️ Порядок причин не случаен: заморозка важнее затыка.
     *
     * Затык проходит за минуту, заморозка держится часами, и если вернуть
     * человеку «площадка тормозит» там, где на самом деле заперта монета, он
     * будет ждать минуту вместо того, чтобы смотреть {@code --revx-audit}.
     */
    static String venuePause(boolean frozen, long now, long stallUntilMs) {
        if (frozen) {
            return "распродано, монета заперта — жду, пока площадка отпустит";
        }
        if (now < stallUntilMs) {
            return "площадка тормозит на заменах — стою в стороне";
        }
        return null;
    }

    private void noteVenueStall(long latencyMs) {
        if (latencyMs < SLOW_REPLACE_MS) {
            return;
        }
        long now = clock.now();
        boolean fresh = now >= stallUntilMs;
        stallUntilMs = now + STALL_STAND_ASIDE_MS;
        if (!fresh) {
            return;                       // всплеск продолжается — окно продлили, шуметь незачем
        }
        String message = ("площадка отвечает на замену %d мс при обычных ~170 — "
                + "отхожу в сторону на %d с, чтобы не плодить неснимаемый резерв")
                .formatted(latencyMs, STALL_STAND_ASIDE_MS / 1000);
        log.warn(message);
        journal.event("venue_stall", message);
    }

    /**
     * Призрак опознан: наследник не нашёлся ни в книге, ни по остаткам.
     *
     * Вызывается по истечении {@link #HEIR_WINDOW_MS}. К этому моменту проверены
     * оба способа: заявка с клиентским идентификатором наследника не появилась в
     * списке активных (тогда {@code heir} был бы снят в сверке), и остатки не
     * показали исполнения (тогда его записал бы {@link
     * #claimHeirIfEvidenceMatches}). Остаётся третий случай — наследник есть, но
     * невидим, и его резерв запирает монету.
     *
     * Запись нужна затем, что сумму запертого она даёт СРАЗУ и с разбивкой по
     * стороне, тогда как сторож {@link #checkFrozen} выводит её из остатков и
     * только через пять минут.
     */
    /**
     * Наследник НАШЁЛСЯ в книге — значит призраком он не был.
     *
     * ⚠️ Без этого снятия подозрения любая удавшаяся замена через полчаса
     * записывалась бы призраком: {@code heir} заводится на КАЖДОМ 422, а
     * снимался он до 16.09.2026 только по уликам исполнения. Между тем чаще
     * всего наследник вполне видим — площадка создала его и показывает в
     * списке активных, просто идентификатор пришёл не в ответе, а в сверке.
     * Узнаём его по клиентскому идентификатору: он НАШ, мы сами его выдали.
     */
    private void forgetHeirIfVisible(java.util.List<ActiveOrder> orders) {
        Heir h = heir;
        if (h == null) {
            return;
        }
        for (ActiveOrder o : orders) {
            if (h.clientId().equals(o.clientId())) {
                heir = null;
                heirEvidence = 0;
                log.debug("наследник {} нашёлся в книге — подозрение снято", h.clientId());
                return;
            }
        }
    }

    private void noteGhost(Heir h, long now) {
        ghosts++;
        String message = ("ПРИЗРАК ЗАМЕНЫ: наследник %s %s по %s (%s) не нашёлся за %d мин — "
                + "площадка его создала, а идентификатор не вернула. %s заперто до её "
                + "собственной уборки (наблюдалось от 2 до 32 ч). Призраков за запуск: %d")
                .formatted(h.side(), fmt(h.size()), fmt(h.price()), h.clientId(),
                        HEIR_WINDOW_MS / 60_000,
                        h.side() == Side.SELL ? base : quote, ghosts);
        // ⚠️ ТОЖЕ НЕ ТРЕВОГА, и по той же причине: подозрение о наследнике
        // заводится на КАЖДОМ 422 и бывает ложным. Будить надо фактом, а факт
        // приходит следующим: если наследник действительно держит монету, через
        // пять минут об этом скажет checkFrozen, который смотрит на остаток, а
        // не на догадку.
        log.error(message);
        journal.event("ghost_replace", message);
    }

    private volatile Heir heir;
    /** Сколько минутных проверок подряд улики сходятся. */
    private volatile int heirEvidence;

    /** Сколько ждём наследника, прежде чем забыть о нём. */
    private static final long HEIR_WINDOW_MS = 30 * 60_000L;
    /** Сколько минут подряд улики должны сходиться, прежде чем записывать сделку. */
    private static final int HEIR_EVIDENCE_TICKS = 2;

    /**
     * Допуски совпадения улик. Точного равенства не бывает: на счёте лежит пыль,
     * цена наследника могла отличаться на тик, касса шевелится сделками соседа по
     * паре. Монету допускаем на 5% меньше ожидаемой, кассу — на 20%; сверху
     * кассовая нехватка ограничена втрое, и всё, что больше, идёт в тревогу, а не
     * в запись.
     */
    private static final double COIN_TOLERANCE = 0.95;
    private static final double CASH_TOLERANCE = 0.8;
    private static final double CASH_CEILING = 3.0;

    private volatile long heirAlarmMs;

    /**
     * Свободная касса в общем котле: остаток счёта минус живые претензии всех
     * ботов. Покупка её забирает, продажа приносит — поэтому уликой служит её
     * ИЗМЕНЕНИЕ за время подозрения, а не величина.
     */
    private double freePot(long now) {
        return alloc == null ? 0 : quoteTotal - alloc.free(quote, quoteTotal, now).claimedLive();
    }

    /** Чем кончилось сличение улик по подозреваемому наследнику. */
    enum HeirVerdict {
        /** Монета появилась и денег не хватает — ровно столько, сколько ожидалось. */
        СОВПАЛО,
        /** Улик нет или они малы: ждём дальше, ничего не записываем. */
        НЕ_СОВПАЛО,
        /** Нехватка кассы кратно больше нашей сделки: это не наш наследник. */
        СЛИШКОМ_МНОГО
    }

    /**
     * Сличение улик — чистая функция, потому что цена ошибки здесь высока, а
     * проверять её на живом боте нечем.
     *
     * @param size     сколько монеты бот пытался купить или продать
     * @param notional сколько это стоит в кассе
     * @param coinGap  сколько монеты на счёте не записано ни за кем (для продажи —
     *                 насколько наши претензии превышают остаток)
     * @param cash     насколько не хватает кассы (для продажи — насколько её больше)
     */
    static HeirVerdict heirVerdict(double size, double notional, double coinGap, double cash) {
        if (cash > notional * CASH_CEILING) {
            return HeirVerdict.СЛИШКОМ_МНОГО;
        }
        boolean coinOk = coinGap >= size * COIN_TOLERANCE;
        boolean cashOk = cash >= notional * CASH_TOLERANCE;
        return coinOk && cashOk ? HeirVerdict.СОВПАЛО : HeirVerdict.НЕ_СОВПАЛО;
    }

    /**
     * 🔑 ЗАПИСЬ ИСПОЛНЕНИЯ ПО ДВУМ УЛИКАМ, А НЕ ПО ОТВЕТУ ПЛОЩАДКИ.
     *
     * <h2>Почему это исключение из главного правила</h2>
     *
     * Всё остальное в боте проводится ТОЛЬКО по ответу площадки. Здесь ответа
     * нет и быть не может: наследник создан, идентификатора мы не получили, а
     * спросить по своему клиентскому идентификатору нельзя — проверено зондом
     * 14.09.2026 (все формы 401/404, истории заявок нет). Либо догадка, либо
     * исполнение теряется навсегда.
     *
     * <h2>Почему догадка безопасна ровно здесь</h2>
     *
     * Требуются ДВЕ независимые улики, и обе должны сойтись по величине с тем,
     * что бот пытался поставить:
     *
     * <ol>
     *   <li><b>монета</b>: на счёте появилось не меньше нашего размера монеты,
     *       которая не числится НИ ЗА ОДНИМ ботом (для продажи — наоборот,
     *       монеты не хватает против наших претензий);</li>
     *   <li><b>деньги</b>: ровно на стоимость этой монеты не хватает денег —
     *       сумма живых претензий на кассу превышает остаток счёта. Это и значит
     *       «площадка списала за покупку, а записи о ней нет».</li>
     * </ol>
     *
     * Плюс три ограничителя: улики держатся {@link #HEIR_EVIDENCE_TICKS} минуты
     * подряд, подозрение живёт не дольше {@link #HEIR_WINDOW_MS}, и размеры
     * ботов на одной паре различаются втрое, так что перепутать соседа нельзя.
     *
     * ⚠️ Если наследник на самом деле жив и стоит в книге, обе улики не сойдутся:
     * монета появится, только когда он исполнится, а пока он стоит — не появится.
     * Если сверка усыновит его и проведёт как обычно, монета станет нашей, и
     * первая улика исчезнет сама. Догадка самоотменяется.
     *
     * Запись помечается статусом {@code inferred} — чтобы всякий будущий разбор
     * мог отделить её от сделок, подтверждённых площадкой.
     */
    private void claimHeirIfEvidenceMatches(long now) {
        Heir h = heir;
        if (h == null || alloc == null) {
            return;
        }
        if (now - h.sinceMs() > HEIR_WINDOW_MS) {
            // Раньше наследника здесь просто забывали. Забыть его можно, а
            // промолчать — нет: именно эти невидимые наследники за полтора суток
            // заперли ВСЕ ETH, BTC и SOL на счёте (A48).
            noteGhost(h, now);
            heir = null;
            heirEvidence = 0;
            return;
        }
        if (Double.isNaN(baseTotalAccount) || !(h.price() > 0)) {
            return;
        }
        AllocRegistry.Free fb = alloc.free(base, baseTotalAccount, now);
        double notional = h.size() * h.price();
        // ⚠️ УЛИКА ПО ДЕНЬГАМ — ЭТО ИЗМЕНЕНИЕ СВОБОДНОЙ КАССЫ, А НЕ ЕЁ РАЗМЕР.
        //
        // Первая версия смотрела на абсолютную величину: для покупки «разобрано
        // больше, чем есть на счёте», для продажи — наоборот. Для покупки это
        // верно, а для продажи бессмысленно: свободная касса в котле велика
        // всегда (у нас там 63 USDC ничьих), и условие выполнялось само собой.
        // На живом боте C 14.09.2026 это дало тревогу «нехватка кассы 66.23 при
        // ожидаемой 3.05 — кратно больше»: сравнивались разные величины.
        //
        // Правильная улика одна на обе стороны: покупка ЗАБИРАЕТ деньги из котла
        // (свободная касса падает на стоимость сделки), продажа их ПРИНОСИТ.
        // Считаем от снимка, сделанного в момент подозрения.
        double potDelta = freePot(now) - h.freePot();
        double cashGap = h.side() == Side.BUY ? -potDelta : potDelta;
        // Монеты, не записанной ни за кем (для продажи — нехватка монеты).
        double coinGap = h.side() == Side.BUY
                ? fb.free()
                : alloc.claims(base, now).stream().filter(AllocRegistry.Claim::live)
                        .mapToDouble(AllocRegistry.Claim::qty).sum() - fb.venueTotal();
        // ⚠️ СРАВНИВАЕМ ПОЛОСОЙ, А НЕ РАВЕНСТВОМ. Точных совпадений тут не бывает:
        // на счёте лежит пыль от прошлых остатков, цена наследника могла отличаться
        // от последней нашей на тик, а касса шевелится сделками соседей по паре.
        // Нижняя граница защищает от «похоже, но мало», верхняя — от «слишком
        // много»: если нехватка кассы кратно больше нашей сделки, это уже не наш
        // случай, а что-то покрупнее, и записывать по догадке нельзя.
        double cash = cashGap;            // уже приведена к знаку «в нашу пользу»
        HeirVerdict verdict = heirVerdict(h.size(), notional, coinGap, cash);
        if (verdict != HeirVerdict.СОВПАЛО) {
            if (heirEvidence > 0) {
                log.info("наследник {}: улики разошлись (монета {} из {}, касса {} из {})",
                        h.clientId(), fmt(coinGap), fmt(h.size()), fmt(cash), fmt(notional));
            }
            heirEvidence = 0;
            // Нехватка кассы КРАТНО больше нашей сделки — это не наш наследник, а
            // расхождение покрупнее: о нём кричит инвариант реестра, и подменять
            // его догадкой нельзя.
            if (verdict == HeirVerdict.СЛИШКОМ_МНОГО) {
                String alarm = ("⚠️ Свободная касса котла изменилась на %s с тех пор, как "
                        + "площадка не вернула идентификатор заявки (%s %s по %s, это %s). "
                        + "Изменение кратно больше сделки — значит дело не в ней: так двигают "
                        + "котёл /claim и /release соседей. По догадке НЕ записываю, "
                        + "смотрите /alloc в сводке и приложение площадки.")
                        .formatted(fmt(cash), h.side(), fmt(h.size()), fmt(h.price()),
                                fmt(notional));
                if (now - heirAlarmMs > MISMATCH_REPEAT_MS) {
                    heirAlarmMs = now;
                    log.error(alarm);
                    journal.event("heir_gap_too_big", alarm);
                    alert.accept(alarm);
                }
            }
            return;
        }
        if (++heirEvidence < HEIR_EVIDENCE_TICKS) {
            return;                       // подождём ещё минуту: улики должны устояться
        }
        heir = null;
        heirEvidence = 0;
        // ⚠️ ПРИ ЖИВОЙ ЛЕНТЕ СДЕЛОК ДОГАДКА ЗАПРЕЩЕНА (27.09.2026, этап 2 читателя).
        // Запись по догадке идёт БЕЗ id заявки; если наследник действительно
        // исполнился, лента сделок принесёт ту же сделку с настоящим id — и лот
        // записался бы дважды. Лента видит наследника по нашей метке сама.
        // ⚠️ И при учёте запертого (§3.6) — тоже нет: призрак наследника НЕ создаёт,
        // а изменение кассы там от запертого резерва. На стенде (ленты нет) догадка
        // 28.09.2026 записала несуществующую покупку лота, и бот ушёл в распродажу.
        if (ledgerActive() || VENUE_LOCKS) {
            String note = ("наследник %s (%s %s по %s): улики совпали, но записывать по "
                    + "догадке не буду — исполнение, если оно было, придёт по ленте сделок")
                    .formatted(h.clientId(), h.side(), fmt(h.size()), fmt(h.price()));
            log.warn(note);
            journal.event("heir_left_to_ledger", note);
            return;
        }
        journal.fill(null, h.side().name(), h.size(), h.price(), lastFair, 0, null, "inferred");
        applyFill(h.side(), h.size(), h.price());
        String message = ("ЗАПИСАНО ПО ДОГАДКЕ: %s %s по %s. Площадка не вернула "
                + "идентификатор наследника заявки (наш %s), спросить её нечем. Улики: "
                + "монеты без хозяина %s (сделка %s), касса котла сдвинулась на %s "
                + "(стоимость сделки %s). "
                + "Позиция стала %s. ⚠️ Это единственная запись в боте, сделанная НЕ по "
                + "ответу площадки — сверьте с приложением.")
                .formatted(h.side(), fmt(h.size()), fmt(h.price()), h.clientId(),
                        fmt(coinGap), fmt(h.size()), fmt(Math.abs(cashGap)), fmt(notional),
                        fmt(inventory));
        log.error(message);
        journal.event("inferred_fill", message);
        alert.accept(message);
    }

    /** Как далеко назад смотреть при восстановлении и сколько заявок спрашивать. */
    private static final long RECOVER_WINDOW_MS = 30 * 60_000L;
    private static final int RECOVER_MAX_ORDERS = 24;

    /**
     * ВОССТАНОВЛЕНИЕ ПРИ СТАРТЕ: спросить площадку про свои заявки, которых нет
     * ни в книге, ни в журнале исполнений.
     *
     * <h2>Дыра, которую это закрывает</h2>
     *
     * Бот узнаёт о сделке единственным способом: заметив, что его заявки не
     * стало, и спросив о ней площадку. Пока процесса нет, замечать некому.
     *
     * 13.09.2026 в 22:28 бот C остановился на выкатку, за тринадцать секунд
     * паузы его аск исполнился, и новый процесс об этом не узнал: в книге заявки
     * уже нет, в памяти ещё нет. Через минуту закричал сторож расхождения —
     * единственное, что сработало. То же самое бывает при падении процесса и при
     * {@code /stop} с последующим стартом.
     *
     * <h2>Как</h2>
     *
     * Идентификаторы берутся из СВОЕГО ЖЕ журнала: каждая постановка и замена
     * записаны вместе с ответом площадки, а цепочки замен связаны полем
     * {@code previous_order_id}, поэтому живых хвостов — единицы. Те, что стоят
     * в книге, пропускаем: ими займётся обычная сверка. Про остальные
     * спрашиваем, и что исполнилось — записываем.
     *
     * ⚠️ Сначала восстанавливается память об уже учтённом
     * ({@link ExecJournal#bookedByOrder}), иначе повторный вопрос о старой
     * заявке провёл бы её исполнение ВТОРОЙ раз: {@link #book} пишет разницу, а
     * карта живёт в памяти процесса и перезапуск её обнуляет.
     *
     * ⚠️ Сторона берётся из ответа площадки, а не выводится из наших тел
     * запросов: в теле {@code PUT} стороны нет вовсе, и попытка вывести её из
     * цепочки замен уже стоила нам суток неверных чисел (сверка 09.09.2026).
     */
    private void recoverMissedFills() {
        bookedByOrder.putAll(journal.bookedByOrder());
        java.util.List<String> tails = journal.recentOrderTails(
                clock.now() - RECOVER_WINDOW_MS, RECOVER_MAX_ORDERS);
        if (tails.isEmpty()) {
            return;
        }
        java.util.Set<String> alive = new java.util.HashSet<>();
        Venue.Response active = client.activeOrders();
        if (active.ok() && active.body() != null) {
            for (ActiveOrder o : ActiveOrder.parse(active.body())) {
                alive.add(o.id());
            }
        } else {
            // Списка нет — спрашиваем про все хвосты. Лишний вопрос стоит один
            // GET, а пропущенное исполнение не восстанавливается уже никогда.
            log.warn("восстановление: список активных заявок недоступен ({})", active.status());
        }
        int asked = 0;
        int booked = 0;
        for (String id : tails) {
            if (alive.contains(id)) {
                continue;                 // стоит в книге — это забота сверки
            }
            Venue.Response order = client.order(id);
            asked++;
            if (!order.ok() || order.body() == null) {
                unknownFate.putIfAbsent(id, new UnknownFate(Side.BUY, clock.now()));
                continue;                 // переспросим через минуту
            }
            String status = field(order.body(), "status");
            if (!"filled".equalsIgnoreCase(status)
                    && !"partially_filled".equalsIgnoreCase(status)) {
                continue;                 // отменена или заменена — записывать нечего
            }
            Side side = "sell".equalsIgnoreCase(field(order.body(), "side"))
                    ? Side.SELL : Side.BUY;
            double before = inventory;
            book(side, id, order.body());
            if (Math.abs(inventory - before) > 1e-15) {
                booked++;
                String message = String.format(java.util.Locale.ROOT,
                        "восстановлено при старте: %s %s, позиция %s → %s",
                        side, id, fmt(before), fmt(inventory));
                log.warn(message);
                journal.event("recovered_fill", message);
                alert.accept(message);
            }
        }
        if (asked > 0) {
            log.info("восстановление при старте: спрошено {} заявок, записано {} исполнений",
                    asked, booked);
        }
        recoverOpenOrders(alive);
    }

    /**
     * 🔑 ЗАЯВКИ ИЗ ПРОШЛОЙ ЖИЗНИ — ПО ЗАПИСИ НА ДИСКЕ, А НЕ ПО СПИСКУ АКТИВНЫХ.
     *
     * <h2>Зачем этого не хватало</h2>
     *
     * Восстановление выше берёт идентификаторы из ТЕЛ ЗАПРОСОВ за последние
     * полчаса и только чтобы записать пропущенные исполнения. Две дыры оно не
     * закрывает:
     * <ul>
     *   <li>заявка старше окна — например, поставленная перед долгим простоем;</li>
     *   <li>заявка ЖИВА, но её нет в списке активных. Прежде такую просто
     *       пропускали («отменена или заменена — записывать нечего»), и она
     *       оставалась в книге навсегда, держа резерв: 15.09.2026 из-за этого на
     *       счёте зависли все ETH, BTC и SOL.</li>
     * </ul>
     *
     * Теперь источник — {@code exec_open_order}: туда пишется КАЖДАЯ постановка
     * и замена, и запись закрывается только выясненной судьбой. При старте бот
     * спрашивает площадку про каждую незакрытую.
     *
     * ⚠️ Живую заявку из прошлой жизни СНИМАЕМ, а не усыновляем. Бот стартует с
     * выключенным котированием, и правило «наших заявок в книге быть не должно»
     * действует и здесь; усыновлять её в слот вслепую нельзя — мы не знаем, чем
     * она была, а вторая заявка поверх живой уже удваивала резерв (01.09.2026).
     */
    private void recoverOpenOrders(java.util.Set<String> alive) {
        java.util.List<ExecJournal.OpenOrder> open =
                journal.openOrders(clock.now() - OPEN_ORDER_WINDOW_MS);
        if (open.isEmpty()) {
            return;
        }
        int asked = 0;
        int cancelled = 0;
        int booked = 0;
        for (ExecJournal.OpenOrder o : open) {
            if (alive.contains(o.venueId())) {
                continue;                 // стоит в книге — этим займётся сверка
            }
            Venue.Response order = client.order(o.venueId());
            asked++;
            if (!order.ok() || order.body() == null) {
                // Судьба неизвестна: запись НЕ закрываем, спросим при следующем
                // старте или через очередь unknownFate.
                continue;
            }
            Side side = "SELL".equalsIgnoreCase(o.side()) ? Side.SELL : Side.BUY;
            String status = field(order.body(), "status");
            // ⚠️ СТАРОЕ ИСПОЛНЕНИЕ АВТОМАТИЧЕСКИ НЕ ЗАПИСЫВАЕМ (27.09.2026). Запись о
            // заявке живёт неделю, а позицию за это время могли свести вручную
            // (/release, /claim). Так и вышло: после перезапуска бот a записал
            // продажу от 23.09, которую владелец уже поглотил, и ушёл в −1 лот.
            // Граница — та же, что у ленты сделок: старше неё решает человек.
            // ⚠️ updated_date площадка отдаёт ЧИСЛОМ без кавычек — field() его не видит.
            Matcher um = Pattern.compile("\"updated_date\"\\s*:\\s*\"?(\\d+)").matcher(order.body());
            long updated = um.find() ? Long.parseLong(um.group(1)) : 0;
            if (updated > 0 && clock.now() - updated > LEDGER_LOOKBACK_MS
                    && number(order.body(), "filled_quantity") > 0) {
                String message = String.format(java.util.Locale.ROOT,
                        "СТАРОЕ ИСПОЛНЕНИЕ НЕ ЗАПИСАНО: %s %s (%s %s по %s) исполнена %s — старше "
                                + "%d ч. Если позиция с тех пор не сводилась вручную, её надо "
                                + "поправить: /claim или разбор. Запись о заявке закрыта.",
                        side, o.venueId(), status, fmt(number(order.body(), "filled_quantity")),
                        fmt(o.price()), java.time.Instant.ofEpochMilli(updated),
                        LEDGER_LOOKBACK_MS / 3_600_000L);
                log.error(message);
                journal.event("stale_fill_skipped", message);
                alert.accept(message);
                if (terminal(status)) {
                    journal.closeOrder(o.venueId(), status, clock.now());
                }
                continue;
            }
            double before = inventory;
            book(side, o.venueId(), order.body());
            if (Math.abs(inventory - before) > 1e-15) {
                booked++;
                String message = String.format(java.util.Locale.ROOT,
                        "восстановлено по записи заявки: %s %s, позиция %s → %s",
                        side, o.venueId(), fmt(before), fmt(inventory));
                log.warn(message);
                journal.event("recovered_fill", message);
                alert.accept(message);
            }
            if (!terminal(status)) {
                // Живая, но невидимая в списке активных — снимаем поимённо.
                Venue.Response cancel = client.cancel(o.venueId());
                cancels++;
                String message = String.format(java.util.Locale.ROOT,
                        "ЗАЯВКА ИЗ ПРОШЛОЙ ЖИЗНИ: %s %s (%s %s по %s) жива, но в списке "
                                + "активных её нет — снял, ответ %d",
                        side, o.venueId(), status, fmt(o.size()), fmt(o.price()),
                        cancel.status());
                log.warn(message);
                journal.event("orphan_cancel", message);
                alert.accept(message);
                if (cancel.ok()) {
                    journal.closeOrder(o.venueId(), "cancelled", clock.now());
                    cancelled++;
                }
                refreshBalances();
            }
        }
        if (asked > 0) {
            log.warn("незакрытых заявок в журнале: {}, спрошено {}, записано исполнений {}, "
                    + "снято живых {}", open.size(), asked, booked, cancelled);
        }
    }

    /**
     * Насколько назад смотреть незакрытые заявки при старте.
     *
     * Неделя: за это время любая наша заявка либо исполнилась, либо снята, а
     * спрашивать про каждую из тысяч — это часы GET-ов при общем лимите в
     * тысячу в минуту. Незакрытые записи старше просто остаются в журнале как
     * след: их видно прибором сверки (`--revx-audit`).
     */
    private static final long OPEN_ORDER_WINDOW_MS = 7 * 24 * 3600_000L;

    /** Заявка, исчезнувшая из книги, о судьбе которой площадка не ответила. */
    private static final class UnknownFate {
        final Side side;
        final long sinceMs;
        volatile boolean warned;

        UnknownFate(Side side, long sinceMs) {
            this.side = side;
            this.sinceMs = sinceMs;
        }
    }

    private final java.util.Map<String, UnknownFate> unknownFate =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Сколько ждать ответа, прежде чем кричать. */
    private static final long FATE_ALERT_MS = 5 * 60_000L;

    /**
     * ПЕРЕСПРОСИТЬ ПРО ЗАЯВКИ С НЕИЗВЕСТНОЙ СУДЬБОЙ. Раз в минуту.
     *
     * Минута, а не каждый тик, намеренно: вопрос возникает ровно тогда, когда
     * площадка режет запросы, и долбиться в неё в этот момент — сделать хуже.
     * Один GET на заявку в минуту стоит пренебрежимо мало при лимите 100/с.
     */
    private void retryUnknownFates(long now) {
        for (java.util.Map.Entry<String, UnknownFate> e : unknownFate.entrySet()) {
            String venueId = e.getKey();
            UnknownFate fate = e.getValue();
            Venue.Response order = client.order(venueId);
            if (order.ok() && order.body() != null) {
                unknownFate.remove(venueId);
                String status = book(fate.side, venueId, order.body());
                String message = String.format(java.util.Locale.ROOT,
                        "%s %s: судьба выяснена через %d с — %s",
                        fate.side, venueId, (now - fate.sinceMs) / 1000, status);
                log.warn(message);
                journal.event("fate_resolved", message);
                continue;
            }
            if (!fate.warned && now - fate.sinceMs > FATE_ALERT_MS) {
                fate.warned = true;
                String message = ("НЕ ЗНАЮ СУДЬБУ ЗАЯВКИ %s (%s) уже %d мин: площадка не "
                        + "отвечает (%d). Пока не ответит, позиция может быть неверна — "
                        + "продолжаю спрашивать раз в минуту.")
                        .formatted(venueId, fate.side, (now - fate.sinceMs) / 60_000,
                                order.status());
                log.error(message);
                journal.event("fate_stuck", message);
                alert.accept(message);
            }
        }
    }

    /**
     * Провести то, что площадка УЖЕ рассказала о заявке. Возвращает её статус.
     *
     * Отделено от запроса, чтобы ответ, полученный по другому поводу (разбор
     * отказа замены), не приходилось спрашивать второй раз.
     */
    private String book(Side side, String venueId, String responseBody) {
        String status = field(responseBody, "status");
        double total = number(responseBody, "filled_quantity");
        // ⚠️ ЦЕНА — ИЗ ПОЛЯ `price`, А НЕ ИЗ `average_fill_price`.
        //
        // `average_fill_price` это НЕ цена, а частное `filled_amount /
        // filled_quantity` ПОСЛЕ ОКРУГЛЕНИЯ. Наши заявки только `post_only` и
        // только лимитные, поэтому исполняются они ровно по своей цене, и на
        // ленте принт совпадает с полем `price` ТОЧНО.
        //
        // Разница не косметическая: 10.09.2026 сверка живых исполнений с лентой
        // по частному дала 27% совпадений, по `price` — 100%. По этому же полю
        // считаются захват и markout, то есть округление садилось прямо в
        // экономику. Пример из журнала: наш SELL стоял на 79357.60, принт прошёл
        // по 79357.60, а частное показывало 79357.50.
        //
        // ⚠️ Записи в `exec_fill` СТАРШЕ 10.09.2026 содержат частное.
        double price = number(responseBody, "price");
        if (!(price > 0)) {
            price = number(responseBody, "average_fill_price");   // запасной путь
        }
        double fee = number(responseBody, "total_fee");
        String feeCurrency = field(responseBody, "fee_currency");

        // ⚠️ filled_quantity НАКОПИТЕЛЬНЫЙ, а спрашиваем мы не по разу: заявку
        // проверяют и при частичном исполнении, и при усыновлении наследника, и
        // когда она исчезла. Записывать надо разницу — см. bookedByOrder.
        // 🔑 ПРОМАХ ПО ПАМЯТИ ПЕРЕСПРАШИВАЕТСЯ У ЖУРНАЛА, и это не подстраховка,
        // а единственное, что тут работает.
        //
        // Карта в памяти — LRU на 512 записей, а в журнале живого бота их 1250.
        // Восстановление при старте заливает в неё ВЕСЬ журнал через putAll, то
        // есть две трети записей вытесняются сразу, и какие именно — решает
        // произвольный порядок обхода HashMap. 16.09.2026 так вытеснило свежую:
        // продажа `f2430d44` записалась в 18:02:19 живым ботом и ВТОРОЙ РАЗ в
        // 18:03:00 восстановлением после перезапуска, и позиция бота `a` ушла
        // в минус на лот (−0.0000251 BTC) — у спотового бота, который шортить
        // не умеет.
        //
        // Защита из A48 была верна по замыслу и перестала работать ровно тогда,
        // когда журнал перерос ёмкость карты. Поэтому источник истины — журнал,
        // а карта остаётся тем, чем и была: кэшем.
        double already = bookedByOrder.containsKey(venueId)
                ? bookedByOrder.get(venueId)
                : journal.bookedFor(venueId);
        double filled = total - already;
        if (filled > 1e-12) {
            bookedByOrder.put(venueId, total);
            fills++;
            totalFilledNotional += filled * price;
            journal.fill(venueId, side.name(), filled, price, lastFair, fee, feeCurrency, status,
                    levelOf(venueId));
            // Своя позиция и касса меняются ЗДЕСЬ, а не по остаткам аккаунта:
            // при двух ботах остатки содержат чужие сделки.
            applyFill(side, filled, price);
            // Политике, чьи цены зависят от собственных сделок (пол по
            // себестоимости), факт исполнения нужен раньше следующего тика.
            policy.onFill(new org.home.data.revx.sim.Fill(
                    clock.now(), side, price, filled, lastFair));
        }
        if (fee > ExecLimits.MAX_FEE_USDC) {
            totalFees += fee;
            String message = ("ОСТАНОВКА: площадка списала комиссию %s %s по заявке %s. "
                    + "Вся конструкция считалась при maker 0%% (тариф сверен 12.09.2026 "
                    + "нашими же 2106 исполнениями); ненулевая комиссия — это смена "
                    + "экономики, а не параметра. Котирование выключено, заявки сняты.")
                    .formatted(fmt(fee), feeCurrency == null ? "" : feeCurrency, venueId);
            log.error(message);
            journal.event("fee_detected", message);
            alert.accept(message);
            stopQuoting();
        }
        // Судьба выяснена площадкой — закрываем запись, если она окончательная.
        // Живую (`new`, `partially_filled`) НЕ закрываем: она ещё стоит в книге,
        // и при следующем старте её надо снова найти.
        if (terminal(status)) {
            journal.closeOrder(venueId, status, clock.now());
        }
        return status;
    }

    /**
     * Окончательна ли судьба заявки.
     *
     * ⚠️ Всё, что не в этом списке, считается ЖИВЫМ. Ошибиться в эту сторону
     * дёшево — лишний вопрос площадке при старте; ошибиться в другую значит
     * забыть стоящую заявку, а её резерв делает монету неотчуждаемой.
     */
    private static boolean terminal(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "filled", "cancelled", "canceled", "rejected", "expired", "replaced" -> true;
            default -> false;
        };
    }

    /**
     * Торговый P&L против buy & hold: рыночное движение стартовой позиции не в счёт.
     *
     * ⚠️ Считается по {@code total}, и это не мелочь. Первая версия брала
     * {@code quoteBalance}, то есть {@code available}, — а он не содержит средств,
     * зарезервированных под стоящей заявкой. Предохранитель занижал P&L ровно на
     * размер резерва: при заявке в 1 USDC и пороге в 1 USDC любой висящий бид уже
     * означал «убыток». 30.08–01.09.2026 стоп сработал трижды, и каждый раз
     * фактический счёт был на своём стартовом значении с точностью до цента
     * (док. 113 §2). Позиция — это {@code total}, независимо от того, лежит она
     * свободно или в резерве.
     */
    private void checkTradingPnl() {
        // Только пока котируем. Иначе предупреждение повторяется каждую минуту у
        // уже остановленного бота: 04.09.2026 бот B после /release прислал его
        // трижды подряд, хотя котирование выключилось первым же разом.
        if (!quoting.get() || !startCaptured || !(lastFair > 0)) {
            return;
        }
        // При своей позиции касса тоже своя: разница остатков аккаунта содержит
        // сделки соседнего бота и торговым результатом этого бота не является.
        double pnl = ownPosition
                ? tradingPnl(ownCash, seedCash, inventory, seedPosition, lastFair)
                : tradingPnl(quoteTotal, startQuote, inventory, startInventory, lastFair);
        if (pnl < -maxTradingLoss) {
            String message = ("ОСТАНОВКА: торговый убыток %s USDC против buy & hold превысил "
                    + "предел %s. Котирование выключено, заявки сняты.")
                    .formatted(fmt(pnl), fmt(-maxTradingLoss));
            log.error(message);
            journal.event("loss_stop", message);
            alert.accept(message);
            stopQuoting();
        }
    }

    /**
     * Изменение стоимости счёта против удержания стартовой позиции.
     *
     * Обе валюты берутся по {@code total}: резерв под стоящей заявкой — это наши
     * деньги, просто занятые. Подстановка сюда {@code available} превращает
     * висящий бид в убыток на его номинал (док. 113 §2).
     */
    static double tradingPnl(double quoteTotal, double startQuote,
                             double inventory, double startInventory, double fair) {
        return (quoteTotal - startQuote) + (inventory - startInventory) * fair;
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static double number(String json, String name) {
        String value = field(json, name);
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Снять заявку. Отказ отмены НЕ означает, что заявка отменена — она могла
     * ИСПОЛНИТЬСЯ за мгновение до нашего запроса.
     *
     * ⚠️ 01.09.2026 это стоило потерянного исполнения. Аск сработал в 13:08:14,
     * следующий тик решил, что сторона больше не котируется, и послал отмену;
     * площадка ответила {@code 409 "Can't cancel order in inactive state"}, цикл
     * счёл заявку снятой и пошёл дальше. Сделка не попала ни в журнал, ни в
     * счётчик, ни под предохранитель по комиссии — а деньги по ней прошли.
     *
     * Третий случай подряд, когда ответ площадки об ошибке означает не то, чем
     * выглядит (см. правило 4 в шапке класса). Вывод общий: **после любого отказа
     * по заявке надо спрашивать её судьбу, а не додумывать.**
     */
    private void cancel(Side side, Resting resting, String why) {
        if (resting.venueId == null) {
            return;
        }
        trace("CANCEL", side, resting, resting.price, resting.size);
        closePartial(resting, "снята нами");
        String dead = resting.venueId;
        // Обнуляем ДО выяснения: предохранитель по комиссии внутри может позвать
        // остановку, а та — снова сюда, и рекурсия должна упереться в этот null.
        resting.venueId = null;
        Venue.Response response = client.cancel(dead);
        cancels++;
        journal.event("cancel", side + " " + dead + " (" + why + ") → " + response.status());
        if (VENUE_SLOTS) {
            // 🔑 §3.3: ЛЮБОЙ ответ на отмену — не окончательный. В затык 204 бывает
            // на заявку, которая исполнится через секунды, 404 — на живую. Слот
            // держим, пока площадка не назовёт окончательную судьбу и после
            // отмены не пройдёт QUIET_MS тишины.
            resting.questioned = dead;
            resting.qSide = side;
            resting.qSize = resting.size;
            resting.qPrice = resting.price;
            resting.qSinceMs = clock.now();
            resting.qAskedMs = 0;
            resting.qRecancelMs = clock.now();
            return;
        }
        if (response.ok()) {
            journal.closeOrder(dead, "cancelled", clock.now());
        } else {
            log.info("отмена {} не прошла ({}), выясняю судьбу заявки", side, response.status());
            inspectGoneOrder(side, dead);
            refreshBalances();
        }
    }

    private void cancelAll(String why) {
        bids.forEach(r -> cancel(Side.BUY, r, why));
        asks.forEach(r -> cancel(Side.SELL, r, why));
    }

    /**
     * Уйти из зоны исполнения на время закрытого гейта — **не отменяя заявку**.
     *
     * Зачем. Единственный жёсткий ресурс площадки — `POST /orders`: 1000 в сутки
     * на ВЕСЬ аккаунт. Отмена стоит дёшево сама по себе, но каждая отмена обязана
     * когда-нибудь оплатиться новой постановкой. Замер 02.09.2026: у бота A из
     * 169 суточных постановок 57 (**34%**) — возвраты после закрытия гейта, у
     * бота B 92 из 153 (**60%**). То есть больше половины бюджета B уходит не на
     * торговлю, а на повторный вход.
     *
     * Замена (`PUT`) суточного потолка не имеет. Поэтому вместо «снять и потом
     * поставить заново» заявка уводится далеко от рынка и возвращается обычным
     * перевыставлением: две замены вместо отмены и постановки, ноль расхода
     * дефицитного ресурса.
     *
     * ⚠️ **Чем это НЕ бесплатно.** Отведённая заявка остаётся в книге и может
     * исполниться, если рынок дойдёт до неё. Дойдёт он ровно в тех эпизодах,
     * ради которых гейт и закрывается: 22.08.2026 марка перпа двадцать минут
     * стояла на 2.35% от спота, а спред опоры доходил до 8% (док. 138 §5).
     * То есть отведённая заявка — это опцион, который мы бесплатно выписали
     * рынку, и исполняется он только в худшие минуты. Поэтому:
     * <ul>
     *   <li>расстояние отвода настраивается и по умолчанию ВЫКЛЮЧЕНО
     *       ({@code revx.exec.park-distance} ≤ 0 — прежнее поведение, отмена);</li>
     *   <li>отвод считается от ПОСЛЕДНЕЙ ДОВЕРЕННОЙ цены, а не от текущей:
     *       текущей мы как раз и не доверяем, на том гейт и сработал;</li>
     *   <li>если доверенной цены ещё не было, заявка снимается по-старому.</li>
     * </ul>
     *
     * Отвод применяется ТОЛЬКО к закрытому гейту. Отмена «нечем котировать эту
     * сторону» остаётся отменой: там проблема в деньгах, а отведённая заявка
     * держит их в резерве и отнимает у соседнего бота — у B за сутки 197 отказов
     * по средствам, добавлять к ним нечего.
     */
    private void standAside(String why) {
        if (parkDistance <= 0 || !(lastTrustedFair > 0)) {
            cancelAll(why);
            return;
        }
        // Отводятся ВСЕ уровни, и каждый на свою глубину: иначе они съедутся в
        // одну цену и на возврате рынка перепутаются между слотами.
        for (int i = 0; i < levels; i++) {
            double away = parkDistance + i * levelStep;
            parkSide(Side.BUY, bids.get(i), lastTrustedFair * (1 - away), why);
            parkSide(Side.SELL, asks.get(i), lastTrustedFair * (1 + away), why);
        }
    }

    private void parkSide(Side side, Resting resting, double price, String why) {
        if (resting.venueId == null) {
            return;                       // отводить нечего
        }
        if (alreadyParked(side, resting, price)) {
            return;                       // уже стоит в стороне — не трогаем
        }
        int failuresBefore = resting.failures;
        replace(side, resting, price, resting.size);
        if (resting.failures > failuresBefore) {
            // Площадка отказала — возможно, цена в 10% от рынка ей не нравится.
            // Оставить заявку там, где она есть, нельзя: гейт закрыт именно
            // потому, что цена подозрительная, и заявка стоит в зоне исполнения.
            // Отвод — оптимизация расхода постановок, а не повод ослабить гейт,
            // поэтому при неудаче возвращаемся к прежнему поведению.
            log.warn("отвод {} не прошёл — снимаю заявку по-старому", side);
            cancel(side, resting, why + " (отвод отклонён)");
            return;
        }
        journal.event("park", side + " отведена на " + fmt(price) + " (" + why + ")");
    }

    /**
     * Стоит ли заявка уже достаточно далеко, чтобы её не трогать.
     *
     * ⚠️ Раньше здесь стоял обычный {@link Quoter#shouldRequote}, и отведённая
     * заявка ГНАЛАСЬ ЗА ЦЕНОЙ. Цена отвода считается от {@code lastTrustedFair},
     * а та продолжает шевелиться, пока гейт открывается и закрывается; порог
     * перевыставления — доли базисного пункта, отвод — целые проценты, так что
     * условие срабатывало почти на каждом тике. Измерено на живом боте E
     * 06.09.2026: 438 отводов за час, 51 отмена и 60 постановок из суточных 80 —
     * бот сжёг три четверти бюджета, ни разу не поторговав.
     *
     * Смысл отведённой заявки — стоять в стороне, а не в точной точке. Поэтому
     * достаточно проверить, что она ещё хотя бы вполовину так далеко, как
     * задумано. Половина — не произвол: если рынок съел половину отвода, это уже
     * настоящее движение, и переставить заявку стоит.
     */
    private boolean alreadyParked(Side side, Resting resting, double parkPrice) {
        if (!(lastTrustedFair > 0) || !(resting.price > 0)) {
            return false;
        }
        double want = Math.abs(parkPrice - lastTrustedFair) / lastTrustedFair;
        double have = side == Side.BUY
                ? (lastTrustedFair - resting.price) / lastTrustedFair
                : (resting.price - lastTrustedFair) / lastTrustedFair;
        return have >= want * 0.5;
    }

    /**
     * Остатки площадки. Раньше отсюда бралась и ПОЗИЦИЯ — «истина о позиции у
     * биржи, а не в нашем счётчике».
     *
     * ⚠️ С двумя ботами на одном аккаунте это перестало быть верным: в остатках
     * лежит СУММА обоих, и каждый принял бы чужой биткойн за свой. Скос, пол по
     * себестоимости и стоп по убытку поехали бы у обоих сразу.
     *
     * Поэтому при {@code ownPosition} позиция ведётся по своим исполнениям
     * ({@link #applyFill}) и хранится в журнале, а остатки остаются **контролем**:
     * наша позиция не может быть больше общей. Расхождение — не повод
     * подстраиваться под остатки (там чужое), а повод кричать.
     */
    private void refreshBalances() {
        Venue.Response response = client.balances();
        if (!response.ok() || response.body() == null) {
            return;
        }
        double baseTotal = Double.NaN;
        Matcher matcher = BALANCE.matcher(response.body());
        while (matcher.find()) {
            double available = Double.parseDouble(matcher.group(2));
            double total = Double.parseDouble(matcher.group(4));
            if (base.equals(matcher.group(1))) {
                baseTotal = total;
                baseTotalAccount = total;     // сколько монеты есть вообще
                baseReserved = Double.parseDouble(matcher.group(3));
                baseAvailable = available;    // поставить можно только на это
                if (!ownPosition) {
                    inventory = total;        // одинокий бот: вся позиция наша
                }
            } else if ("USDC".equals(matcher.group(1))) {
                quoteBalance = available;     // на что можно поставить новую заявку
                quoteReserved = Double.parseDouble(matcher.group(3));
                quoteTotal = total;           // а это — сколько денег у нас есть
            }
        }
        checkPositionAgainstAccount(baseTotal);
        if (!startCaptured && inventory + quoteTotal > 0) {
            startInventory = inventory;
            startQuote = quoteTotal;
            startCaptured = true;
        }
        checkTradingPnl();
    }

    /**
     * Своя позиция против остатка аккаунта — с выдержкой.
     *
     * ⚠️ Мгновенная проверка даёт ложные тревоги, и это гонка, а не поломка:
     * аск исполняется → остаток на бирже падает СРАЗУ → мы сверяемся до того, как
     * узнали о собственной сделке. 01.09.2026 бот B выдал четыре таких тревоги
     * подряд, и каждая закрывалась через 4–7 секунд, когда исполнение находилось.
     * Итог сошёлся до последнего знака: 8 сделок, нетто +0.000025, столько же на
     * счету.
     *
     * Поэтому: заметили расхождение — сначала СВЕРЯЕМСЯ с книгой (сверка находит
     * исчезнувшую заявку и записывает исполнение), и только если расхождение
     * пережило выдержку, кричим. Тревога, которая срабатывает на штатной работе,
     * маскирует настоящую.
     */
    private void checkPositionAgainstAccount(double baseTotal) {
        if (!ownPosition || Double.isNaN(baseTotal)) {
            return;
        }
        long now = clock.now();
        if (inventory <= baseTotal + 1e-12) {
            mismatchSinceMs = 0;          // сошлось — счётчик выдержки сбрасывается
            return;
        }
        if (mismatchSinceMs == 0) {
            mismatchSinceMs = now;
            reconcile("расхождение позиции");   // может найти неучтённое исполнение
            return;
        }
        if (now - mismatchSinceMs < MISMATCH_GRACE_MS
                || now - mismatchWarnedMs < MISMATCH_REPEAT_MS) {
            return;
        }
        mismatchWarnedMs = now;
        // ⚠️ Если есть заявки с невыясненной судьбой, причина расхождения почти
        // наверняка в них, и человеку надо сказать именно это: бот продолжает
        // спрашивать площадку и может починиться сам.
        String pending = unknownFate.isEmpty() ? ""
                : String.format(java.util.Locale.ROOT,
                        " Жду ответа площадки по %d заявке(ам) — вероятно, дело в них.",
                        unknownFate.size());
        String message = ("РАСХОЖДЕНИЕ: своя позиция %s больше остатка аккаунта %s уже "
                + "%d с. Либо потеряно исполнение, либо позицию тронули извне.%s")
                .formatted(fmt(inventory), fmt(baseTotal), (now - mismatchSinceMs) / 1000,
                        pending);
        log.error(message);
        journal.event("position_mismatch", message);
        alert.accept(message);
    }

    /**
     * Восстановить свою позицию после перезапуска.
     *
     * Журнал — единственное, что её переживает: вывести позицию из остатков при
     * двух ботах нельзя, там сумма обоих. Если состояния ещё нет (первый запуск
     * этого бота), берётся затравка из конфига: у одинокого бота это остаток
     * аккаунта, у второго — ноль, иначе он присвоил бы себе чужой биткойн.
     */
    private void restorePosition() {
        if (!ownPosition) {
            return;
        }
        Double saved = journal.getState(STATE_POSITION);
        Double savedCash = journal.getState(STATE_CASH);
        if (saved != null) {
            // Пыль, накопленная предыдущей версией бота, переживает перезапуск в
            // журнале — снимаем её здесь же, иначе правка не подействует.
            inventory = Math.abs(saved) < dust ? 0 : saved;
            ownCash = savedCash == null ? 0 : savedCash;
            Double savedSeed = journal.getState(STATE_SEED);
            seedPosition = savedSeed == null ? 0 : savedSeed;
            Double savedSeedCash = journal.getState(STATE_SEED_CASH);
            seedCash = savedSeedCash == null ? 0 : savedSeedCash;
            log.warn("позиция восстановлена из журнала: {} {}, касса {}",
                    fmt(inventory), base, fmt(ownCash));
            return;
        }
        if (positionSeed >= 0) {
            inventory = positionSeed;
        } else {
            // Затравка «из аккаунта»: осмысленна только пока бот один.
            Venue.Response response = client.balances();
            Matcher matcher = BALANCE.matcher(response.body() == null ? "" : response.body());
            while (matcher.find()) {
                if (base.equals(matcher.group(1))) {
                    inventory = Double.parseDouble(matcher.group(4));
                }
            }
        }
        ownCash = 0;
        seedPosition = inventory;
        journal.putState(STATE_POSITION, inventory);
        journal.putState(STATE_CASH, ownCash);
        journal.putState(STATE_SEED, seedPosition);
        log.warn("первый запуск: позиция принята за {} {}", fmt(inventory), base);
        journal.event("position_seed", fmt(inventory) + " " + base);
    }

    /**
     * Своя позиция и своя касса после исполнения. Обе сохраняются сразу: журнал —
     * единственное, что переживёт перезапуск, а вывести позицию из остатков при
     * двух ботах больше нельзя.
     */
    private void applyFill(Side side, double qty, double price) {
        if (!ownPosition) {
            return;
        }
        inventory += side.sign() * qty;
        if (Math.abs(inventory) < dust) {
            // Пыль ниже половины шага количества — не позиция, а ошибка сложения.
            inventory = 0;
        }
        // ⚠️ ВОЗРАСТ САМОГО СТАРОГО ЛОТА, А НЕ ПОЗИЦИИ. Разница решающая, и первая
        // версия 12.09.2026 была написана неверно: она считала время с момента,
        // когда счёт перестал быть пустым. При потолке в семь лотов бот непустой
        // 71% времени, лоты внутри оборачиваются, а «возраст позиции» растёт
        // часами — и придвижение аска, включённое по нему, прижимало аск к
        // справедливой цене навсегда. Обход показал −238% годовых против −15%.
        //
        // Замысел был про ЛОТ: «купили что-то и не разгрузились за N минут».
        // Поэтому здесь очередь FIFO из отметок покупок: продажа съедает её с
        // головы, и головная отметка и есть возраст того, что мы держим дольше
        // всего.
        if (side == Side.BUY) {
            lotAges.addLast(new double[]{clock.now(), qty});
        } else {
            double left = qty;
            while (left > 1e-15 && !lotAges.isEmpty()) {
                double[] head = lotAges.peekFirst();
                double take = Math.min(left, head[1]);
                head[1] -= take;
                left -= take;
                if (head[1] <= 1e-15) {
                    lotAges.pollFirst();
                }
            }
        }
        if (inventory == 0) {
            lotAges.clear();
        }
        ownCash -= side.sign() * qty * price;
        journal.putState(STATE_POSITION, inventory);
        journal.putState(STATE_CASH, ownCash);
        // Реестр двигается ТОЛЬКО здесь — своими сделками. Захват возможен лишь
        // при инициализации, иначе нельзя отличить «продал купленное» от «продал
        // найденное», и проверка логики бота теряет смысл.
        if (alloc != null) {
            long now = clock.now();
            alloc.applyFill(tag.id(), base, side.sign() * qty, now);
            // Деньги двигаются зеркально монетам, и прибыль оседает ЗДЕСЬ:
            // продали дороже, чем купили — в претензии бота стало больше USDC.
            // Без этой строки заработанное становилось бы ничьим.
            alloc.applyFill(tag.id(), quote, -side.sign() * qty * price, now);
        }
        // ⚠️ ОСТАТКИ ПЕРЕЧИТЫВАЮТСЯ ЗДЕСЬ — в единственной точке, где позиция
        // меняется исполнением.
        //
        // Иначе позиция уже учла сделку, а остаток счёта ещё нет, и
        // предохранитель сравнивает свежее со старым. Первое же живое частичное
        // исполнение (09.09.2026 13:35, бот A) дало ложную тревогу «позиция
        // 0.00008553 больше остатка аккаунта 0.00007594», которая сама
        // рассосалась через минуту. Тревога, срабатывающая на штатной работе,
        // маскирует настоящую — а настоящая здесь означает потерянную сделку.
        //
        // Один лишний GET на ЗАПИСАННОЕ исполнение, а не на каждый опрос: опрос
        // идёт на каждой замене (тысячи в сутки), исполнений же сотни.
        refreshBalances();
    }

    /**
     * Что бот увидит перед захватом: сколько монет свободно и сколько это лотов
     * против его собственной цели инвентаря.
     */
    public String describeFree() {
        if (alloc == null) {
            return "реестр владения не подключён";
        }
        refreshBalances();
        long now = clock.now();
        double lot = params.size();
        double cap = params.inventoryCap();
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        Locked locked = lockedNow(now);
        double orphan = locked == null ? 0 : locked.orphans();
        AllocRegistry.Free fb = alloc.free(base, accountBase() - orphan, now);
        AllocRegistry.Free fq = alloc.free(quote, quoteTotal, now);
        double myBase = alloc.own(tag.id(), base);
        double myQuote = alloc.own(tag.id(), quote);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT,
                "СЧЁТ%n  %s: %.8f = %.1f лота%n  %s: %.2f%n",
                base, accountBase(), lot > 0 ? accountBase() / lot : 0,
                quote, fq.venueTotal()));
        // Где монета лежит физически. Без этой строки «на счёте вижу, а бот не
        // видит» выглядит как поломка, хотя это заявки.
        if (locked == null) {
            sb.append("  ⚠️ список активных заявок не получен — что заперто, неизвестно\n");
        } else if (locked.total() > 0) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "  в заявках: %.8f (моих %.8f, соседей %.8f%s)%n",
                    locked.total(), locked.mine(), locked.liveNeighbours(),
                    locked.orphans() > 0
                            ? String.format(java.util.Locale.ROOT,
                                    ", ⚠️ БЕЗ ХОЗЯИНА %.8f в %d заявк(ах)",
                                    locked.orphans(), locked.orphanOrders())
                            : ""));
        }
        sb.append("\n");

        sb.append("ДЕРЖАТ БОТЫ\n");
        for (AllocRegistry.Claim c : alloc.claims(base, now)) {
            sb.append(String.format(java.util.Locale.ROOT, "  %s: %.1f лота + %.2f %s%s%n",
                    c.botId(), lot > 0 ? c.qty() / lot : 0,
                    alloc.own(c.botId(), quote), quote,
                    c.live() ? "" : "  (аренда истекла — можно забрать)"));
        }

        // ⚠️ Свободное округляется ВНИЗ, а не «как получится».
        //
        // 04.09.2026 здесь стоял %.1f: свободно было 9.859 лота, напечаталось
        // «9.9», человек скопировал подсказку в /claim 9.9 и получил отказ.
        // Число, которое предлагают ввести, обязано быть заведомо принимаемым —
        // округление вверх превращает подсказку в ловушку.
        double freeLots = lot > 0 ? Math.floor(fb.free() / lot * 10) / 10 : 0;
        double freeCash = Math.floor(fq.free() * 100) / 100;
        sb.append(String.format(java.util.Locale.ROOT,
                "%nСВОБОДНО%n  %s: %.1f лота (%.8f)%n  %s: %.2f%n",
                base, freeLots, fb.free(), quote, freeCash));
        if (freeLots > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "  взять всё: /claim %.1f%n", freeLots));
        }
        sb.append("\n");

        // Своё состояние — отдельным блоком и без двусмысленных слов: «держу
        // сейчас» это факт, а не заявка на будущее, и /claim к нему ПРИБАВЛЯЕТ.
        sb.append(String.format(java.util.Locale.ROOT, "Я (бот %s)%n", tag.id()));
        sb.append(String.format(java.util.Locale.ROOT,
                "  держу сейчас: %.1f лота + %.2f %s%n",
                lot > 0 ? myBase / lot : 0, myQuote, quote));
        sb.append(String.format(java.util.Locale.ROOT,
                "  потолок инвентаря: %.1f лота%s%n",
                lot > 0 ? cap / lot : 0,
                price > 0 ? String.format(java.util.Locale.ROOT, " (≈ %.2f %s)",
                        cap * price, quote) : ""));
        sb.append(String.format(java.util.Locale.ROOT,
                "  цель скоса: %.0f%% потолка = %.1f лота%n",
                params.skewTarget() * 100,
                lot > 0 ? params.skewTarget() * cap / lot : 0));
        if (price > 0) {
            double needQuote = Math.max(0, (cap - myBase) * price);
            sb.append(String.format(java.util.Locale.ROOT,
                    "  до потолка не хватает: %.1f лота = %.2f %s%s%n",
                    lot > 0 ? (cap - myBase) / lot : 0, needQuote, quote,
                    myQuote >= needQuote * 0.995 ? " — покрыто" : " ← НЕ ПОКРЫТО"));
        }
        return sb.toString();
    }

    /**
     * Захват свободных лотов при инициализации.
     *
     * Разрешён ТОЛЬКО пока бот не котирует: во время работы инвентарь обязан
     * быть функцией собственных сделок и ничего больше.
     *
     * @return текст для человека; захват либо состоялся, либо объяснён отказ
     */
    public String claimLots(double lots) {
        if (alloc == null) {
            return "реестр владения не подключён";
        }
        if (quoting.get()) {
            return "Захват запрещён при включённом котировании: инвентарь во время "
                    + "работы обязан меняться только своими сделками. Сначала /stop.";
        }
        if (lots < 0) {
            return "Сколько лотов брать? Ноль означает «только деньги».";
        }
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(price > 0)) {
            return "Нет доверенной справедливой цены — передачу оценить нечем. "
                    + "Подождите, пока опора заработает.";
        }
        return claimQty(lots * params.size());
    }

    /**
     * Взять ВСЁ свободное до последнего знака, а не круглое число лотов.
     *
     * Нужно потому, что ничейное редко оказывается целым числом лотов: после
     * {@code /release} и частичных продаж на счёте остаётся хвост в доли лота, и
     * захват «сколько-то лотов» оставляет его ничейным навсегда. Подсказка
     * {@code /free} округляет ВНИЗ, чтобы её можно было скопировать, — а эта
     * команда берёт остаток целиком.
     */
    public String claimAll() {
        if (alloc == null) {
            return "реестр владения не подключён";
        }
        if (quoting.get()) {
            return "Захват запрещён при включённом котировании. Сначала /stop.";
        }
        refreshBalances();
        long now = clock.now();
        Locked locked = lockedNow(now);
        if (locked == null) {
            return "Отказ: список активных заявок не получен, "
                    + "а без него нельзя отличить свободную монету от запертой в чужой заявке.";
        }
        double free = alloc.free(base, accountBase() - locked.orphans(), now).free();
        if (!(free > 0)) {
            return "Свободного нет.\n\n" + describeFree();
        }
        return claimQty(free);
    }

    private String claimQty(double qty) {
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(price > 0)) {
            return "Нет доверенной справедливой цены — передачу оценить нечем. "
                    + "Подождите, пока опора заработает.";
        }
        refreshBalances();
        long now = clock.now();

        // ⚠️ Без списка активных заявок захват запрещён. Монета, запертая в
        // заявке БЕЗ живого хозяина, выглядит в остатке счёта как свободная, но
        // забравший её получит фантомный инвентарь: заявка исполнится сама, а
        // реестр будет считать монету за новым владельцем. Один GET на команду,
        // которая и так делается руками.
        Locked locked = lockedNow(now);
        if (locked == null) {
            return "Отказ: список активных заявок не получен, "
                    + "а без него нельзя отличить свободную монету от запертой в чужой заявке.";
        }
        double denominator = accountBase() - locked.orphans();

        // Деньги забираются ВМЕСТЕ с монетами, одной командой и по принципу
        // «либо всё, либо ничего». Смысл: бот должен уметь дойти до потолка, а
        // не встать на полпути с отказами по средствам — у бота B их было 197
        // за сутки. Нужно ровно столько, сколько стоит недостающая до потолка
        // часть инвентаря: остальное он уже держит монетами.
        double needQuote = Math.max(0, (params.inventoryCap() - (inventory + qty)) * price);
        double freeQuote = alloc.free(quote, quoteTotal, now).free();
        double haveQuote = alloc.own(tag.id(), quote);
        double takeQuote = Math.max(0, needQuote - haveQuote);
        if (takeQuote > freeQuote + 1e-9) {
            return String.format(java.util.Locale.ROOT,
                    "Отказ: до потолка нужно %.2f %s, свободно только %.2f. "
                            + "Стартовать с нехваткой денег нельзя — бот встанет на "
                            + "отказах по средствам.%n%n%s",
                    takeQuote, quote, freeQuote, describeFree());
        }
        if (!alloc.claim(tag.id(), base, qty, denominator, price, now)) {
            return "Отказ: свободных лотов меньше запрошенного.\n\n" + describeFree();
        }
        if (takeQuote > 0 && !alloc.claim(tag.id(), quote, takeQuote, quoteTotal, price, now)) {
            // Монеты уже взяты — возвращаем, чтобы не остаться в половинном состоянии.
            if (qty > 0) {
                alloc.applyFill(tag.id(), base, -qty, now);
            }
            return "Отказ: деньги забрать не удалось, монеты возвращены.\n\n" + describeFree();
        }
        // Передача двигает и позицию, и ЕЁ ТОЧКУ ОТСЧЁТА — иначе она попадёт в
        // торговый результат. Захват денег без сдвига базы выглядел бы прибылью,
        // освобождение — убытком; на этом бот B отчитался о −6.77 USDC, которых
        // не было (04.09.2026).
        inventory += qty;
        seedPosition += qty;
        ownCash += takeQuote;
        seedCash += takeQuote;
        journal.putState(STATE_POSITION, inventory);
        journal.putState(STATE_SEED, seedPosition);
        // 🔑 И СРАЗУ СВОДИМ КАССУ. Монеты пришли передачей, значит денег под них
        // больше не нужно: «монеты + касса = потолок». Без этого захват молча
        // увеличивал долю бота на общем счёте на стоимость захваченного, и лишнее
        // оседало за ним мёртвым грузом (найдено владельцем 13.09.2026).
        String gaveBack = giveBackCash(
                Math.max(0, (params.inventoryCap() - inventory) * price),
                alloc.own(tag.id(), quote), price, now);
        journal.putState(STATE_CASH, ownCash);
        journal.putState(STATE_SEED_CASH, seedCash);
        if (qty > 0) {
            journal.fill(null, "BUY", qty, price, price, 0, null, "handover");
        }
        // Доли лота печатаются ДВУМЯ знаками: `/claim всё` берёт остаток целиком,
        // и он почти никогда не круглый — с одним знаком «0.3 лота» скрывало бы,
        // что взято 0.34, а именно эта разница и оставалась ничейной.
        double lots = params.size() > 0 ? qty / params.size() : 0;
        journal.event("claim", String.format(java.util.Locale.ROOT,
                "%.2f лота = %.8f %s по %.2f, плюс %.2f %s",
                lots, qty, base, price, takeQuote, quote));
        return String.format(java.util.Locale.ROOT,
                "Взято %.2f лота = %.8f %s по справедливой %.2f и %.2f %s.%n"
                        + "Записано передачей (status=handover), в статистику сделок не идёт.%n"
                        + "%s%n%s",
                lots, qty, base, price, takeQuote, quote,
                gaveBack == null ? "" : gaveBack + "\n", describeFree());
    }

    /** Отдать инвентарь в общий котёл. Заявки снимаются ДО освобождения. */
    public String release() {
        if (alloc == null) {
            return "реестр владения не подключён";
        }
        if (quoting.get()) {
            return "Сначала /stop: освобождать инвентарь под работающими заявками нельзя.";
        }
        double price = lastTrustedFair > 0 ? lastTrustedFair : lastFair;
        if (!(price > 0)) {
            return "Нет доверенной справедливой цены — передачу оценить нечем.";
        }
        cancelAll("освобождение инвентаря");
        reconcile("освобождение");
        double qty = alloc.own(tag.id(), base);
        double cash = alloc.own(tag.id(), quote);
        if (qty <= 0 && cash <= 0) {
            return "Держать нечего.";
        }
        long now = clock.now();
        // Отдаём ОБЕ валюты: иначе касса бота останется за ним навсегда и станет
        // недоступной остальным, а «ничьих денег» мы и добивались не допустить.
        if (qty > 0) {
            alloc.release(tag.id(), base, price, now);
            journal.fill(null, "SELL", qty, price, price, 0, null, "handover");
            inventory -= qty;
            seedPosition -= qty;
            journal.putState(STATE_POSITION, inventory);
            journal.putState(STATE_SEED, seedPosition);
        }
        if (cash > 0) {
            alloc.release(tag.id(), quote, price, now);
            ownCash -= cash;
            seedCash -= cash;
            journal.putState(STATE_CASH, ownCash);
            journal.putState(STATE_SEED_CASH, seedCash);
        }
        journal.event("release", String.format(java.util.Locale.ROOT,
                "%.8f %s и %.2f %s по %.2f", qty, base, cash, quote, price));
        // ⚠️ Напоминание не вежливость, а защита от самого частого сценария:
        // 13.09.2026 владелец освободил всех шестерых и захватил обратно меньше,
        // чем отдал. Остаток стал ничейным — им никто не торгует, и виден он
        // только на сайте площадки.
        return String.format(java.util.Locale.ROOT,
                "Освобождено %.8f %s и %.2f %s по %.2f, закрыто по переоценке.%n"
                        + "⚠️ Теперь это НИЧЕЙНОЕ: пока кто-нибудь не сделает /claim, "
                        + "монетой не торгует никто.%n%n%s",
                qty, base, cash, quote, price, describeFree());
    }

    /**
     * Остаток счёта по базовой валюте — знаменатель для реестра владения.
     *
     * ⚠️ ЗДЕСЬ БЫЛА ОШИБКА, ИЗ-ЗА КОТОРОЙ МОНЕТЫ СТАНОВИЛИСЬ НЕВИДИМЫМИ
     * (13.09.2026). Считалось {@code baseAvailable + СВОИ стоящие аски}, то есть
     * остаток счёта минус монеты, запертые в заявках ДРУГИХ ботов. А свободное
     * реестр считает как {@code знаменатель − живые претензии}, и претензии
     * соседа вычитались ВТОРОЙ раз: его монеты и в знаменатель не входили, и из
     * него же вычитались.
     *
     * Пока на паре работал один бот, разницы не было. С 12.09.2026 ботов на паре
     * ДВА (опыт «один уровень против трёх»), и ошибка стала постоянной: у BTC
     * при остатке счёта 0.00002529 и соседском аске на 0.00001255 бот видел
     * «свободно 0.00000018» вместо лота, а владелец видел монеты на счёте и не
     * мог их забрать никаким {@code /claim}.
     *
     * Правильный знаменатель — остаток СЧЁТА: монета в выставленной заявке никуда
     * не делась. Кто чем владеет, решают живые претензии реестра, а не остаток.
     */
    private double accountBase() {
        if (!Double.isNaN(baseTotalAccount)) {
            return baseTotalAccount;
        }
        // Остатки ещё не пришли — считаем по своим заявкам, как раньше.
        double reserved = 0;
        for (Resting r : asks) {
            reserved += r.venueId != null ? r.size : 0;
        }
        return baseAvailable + reserved;
    }

    /** Что заперто в заявках по этой паре и за кем числится. Всё в монетах. */
    record Locked(double mine, double liveNeighbours, double orphans, int orphanOrders) {
        double total() {
            return mine + liveNeighbours + orphans;
        }
    }

    /**
     * РАЗБОР ЗАПЕРТОГО: чьи заявки держат монету.
     *
     * Монета в заявке соседа с ЖИВОЙ претензией — его, и она уже вычтена из
     * свободного как его претензия. А вот монета в заявке, у которой живого
     * хозяина нет (бот убит, а заявка осталась; или заявка досталась от старой
     * версии без метки), — это ловушка: реестру она видна как свободная, но
     * забравший её не сможет ни продать, ни удержать — заявка исполнится сама,
     * и у нового хозяина останется фантомный инвентарь. Такие монеты из
     * свободного вычитаются отдельно.
     *
     * Хозяин определяется по метке в {@code client_order_id} — первые восемь
     * знаков ({@link BotTag#prefix()}). Заявка без метки считается чужой.
     */
    static Locked lockedInOrders(String symbol, BotTag tag, java.util.List<ActiveOrder> all,
                                 java.util.List<AllocRegistry.Claim> claims) {
        double mine = 0;
        double live = 0;
        double orphan = 0;
        int orphanOrders = 0;
        String mySymbol = ActiveOrder.normalize(symbol);
        for (ActiveOrder o : all) {
            if (o.side() != Side.SELL || !mySymbol.equals(o.symbol())) {
                continue;                 // монету запирает только продажа
            }
            // ⚠️ Вычитать filled НЕ НАДО: {@link ActiveOrder} кладёт в size уже
            // `leaves_quantity`, то есть то, что ещё стоит в книге и держит
            // резерв. Первая версия вычитала исполненное второй раз и занижала
            // запертое на частично исполненной заявке — поймано тестом.
            double left = Math.max(0, o.size());
            if (tag.owns(o.clientId())) {
                mine += left;
            } else if (hasLiveOwner(o.clientId(), claims)) {
                live += left;
            } else {
                orphan += left;
                orphanOrders++;
            }
        }
        return new Locked(mine, live, orphan, orphanOrders);
    }

    /** Есть ли у метки заявки живой хозяин среди претензий реестра. */
    private static boolean hasLiveOwner(String clientId, java.util.List<AllocRegistry.Claim> claims) {
        if (clientId == null) {
            return false;
        }
        for (AllocRegistry.Claim c : claims) {
            if (c.live() && new BotTag(c.botId()).owns(clientId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Разбор запертого прямо сейчас, одним запросом к площадке.
     *
     * @return {@code null}, если список активных заявок получить не удалось. Не
     *         зная списка, мы не знаем и о заявках-ловушках, поэтому захват в
     *         этом случае запрещается целиком — см. {@link #claimLots}.
     */
    private Locked lockedNow(long now) {
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            return null;
        }
        return lockedInOrders(symbol, tag, ActiveOrder.parse(active.body()),
                alloc == null ? java.util.List.of() : alloc.claims(base, now));
    }

    /** Когда в последний раз кричали про расхождение реестра и про ничейное. */
    private volatile long registryWarnedMs;
    private volatile long unownedWarnedMs;
    /** Какую величину ничейного уже называли; 0 — не называли. */
    private volatile double unownedReported;
    private volatile long frozenWarnedMs;
    /** Когда впервые заметили запертую монету без заявки; 0 — не замечали. */
    private volatile long frozenSinceMs;
    /**
     * Пока true — не котируем: монета заперта призраком, продать её нельзя.
     *
     * ⚠️ Пауза, а не поправка к инвентарю, и это решение владельца от 16.09.2026.
     * Соблазн был вычесть замороженное из инвентаря и торговать свободной
     * частью — тогда бот не простаивает. Но продавать он всё равно не может, а
     * покупать продолжит, то есть накопит инвентарь СВЕРХ потолка на паре, где
     * выход закрыт. Это ровно тот крен в покупку, который отвергнут в A49.
     */
    private volatile boolean frozenPaused;

    /**
     * Распродажа: заморозка выела ёмкость, но продавать ещё есть что.
     *
     * 🔑 Решение владельца 16.09.2026, и оно точнее прежнего «встать при первом
     * же запертом лоте». Ступеней три:
     *
     * <ol>
     *   <li>заперто меньше половины потолка — <b>торгуем как обычно</b>. Остаток
     *       свободной монеты работает буфером: продавать есть чем, и
     *       останавливаться значит терять деньги на ровном месте;</li>
     *   <li>заперто больше половины — <b>распродажа</b>: цель скоса в ноль,
     *       покупки запрещены, бот сводит инвентарь к нулю;</li>
     *   <li>распродано — <b>стоим</b> и ждём, пока площадка отпустит.</li>
     * </ol>
     *
     * ⚠️ Прежний порог «лот и пять минут» ловил не то. 16.09.2026 бот d встал,
     * имея ТРИ продаваемых лота, только потому, что один заперт; а сосед c на
     * том же счёте и той же заморозке не встал — у него крупнее лот. Абсолютная
     * величина здесь ни при чём, важна доля выеденной ёмкости.
     *
     * ⚠️ Буфер из лишних монет РАССМАТРИВАЛСЯ И ОТВЕРГНУТ: счёт один, отложенная
     * монета падает в цене вместе с остальными, и страховка стоит ровно один лот
     * рыночного риска за каждый застрахованный. На падающем окне это дороже той
     * торговли, которую спасает.
     */
    private volatile boolean frozenUnwind;

    /** Тот же котировщик с целью скоса в НОЛЬ — для распродажи. */
    private volatile Quoter unwindQuoter;

    /**
     * Строится лениво и один раз: распродажа — редкое состояние, а заводить в
     * конструкторе второй котировщик ради него значит платить за него всегда.
     */
    private Quoter unwindQuoter() {
        Quoter q = unwindQuoter;
        if (q == null) {
            q = new Quoter(params.withSkewTarget(0));
            unwindQuoter = q;
        }
        return q;
    }

    /**
     * 🔑 ЗАПЕРТО, А ЗАЯВКИ НЕТ: четвёртый сторож, которого не хватало.
     *
     * <h2>Зачем</h2>
     *
     * Три прежних сторожа проверяют НАШИ книги друг против друга: позиция
     * против остатка, реестр против журнала, ничейное против претензий. Ни один
     * не смотрел на то, чем монета ЗАПЕРТА, — и эта беда пряталась дольше всех.
     *
     * 15.09.2026 на счёте оказались заперты ВСЕ ETH, BTC и SOL: площадка держала
     * их в {@code reserved}, не показывая по ним ни одной заявки. Проверено при
     * остановленных ботах и при снятых через приложение заявках — резерв не
     * сдвинулся. Пока он есть, {@code available} ноль, и бот физически не может
     * продать: ETH-боты неделю только покупали, и это выглядело как «нет
     * потока», а не как поломка.
     *
     * <h2>Почему это отдельная тревога</h2>
     *
     * Её причина вне нас, и лечение тоже вне нас: снять заявку, которой не
     * видно, нельзя — идентификатора у неё нет. Поэтому сторож не чинит, а
     * НАЗЫВАЕТ: сколько заперто, сколько из этого объясняется видимыми
     * заявками, и с какого момента это длится.
     *
     * ⚠️ Порог — лот и пять минут. Мгновенное расхождение штатно: остатки и
     * список активных читаются РАЗНЫМИ запросами, и между ними успевает пройти
     * замена. Беда — когда расхождение держится.
     */
    /**
     * 🔑 ПОКРЫТИЕ: хватает ли ДОСТУПНОГО на то, что за ботами ЧИСЛИТСЯ.
     *
     * Решение владельца 16.09.2026, и оно точнее двух прежних попыток. Мерить
     * «сколько заперто» относительно потолка неправильно: потолок — это
     * разрешение торговать, а не обязательство иметь. Важно другое — может ли
     * бот забрать из доступного то, что записано за ним в реестре.
     *
     * Считается по ВСЕМ ботам сразу и по обеим валютам: счёт общий, и заморозка
     * у соседа отнимает доступное у всех.
     *
     * @return доля покрытия; 1.0 и больше — хватает с запасом, 0.5 — доступного
     *         вдвое меньше, чем числится. Если не числится ничего, покрытие
     *         полное: нечего покрывать.
     */
    static double coverage(double available, double claimed) {
        if (!(claimed > 0)) {
            return 1.0;
        }
        return Math.max(0, available) / claimed;
    }

    /** Ниже этого покрытия уходим в распродажу. */
    static final double COVERAGE_UNWIND = 0.7;
    /** Возврат в работу — только при полном покрытии, без всяких «почти». */
    static final double COVERAGE_RESUME = 1.0;

    /** Сумма претензий ВСЕХ ботов по валюте. */
    private double claimedAll(String currency, long now) {
        if (alloc == null) {
            return 0;
        }
        double sum = 0;
        for (AllocRegistry.Claim c : alloc.claims(currency, now)) {
            sum += c.qty();
        }
        return sum;
    }

    private void checkFrozen(long now) {
        if (!(params.size() > 0) || baseReserved <= 0) {
            // ⚠️ СНЯТЬ ПАУЗУ НАДО И ЗДЕСЬ, а не только ниже по расчёту разницы.
            // Полное освобождение резерва обнуляет baseReserved, то есть выходит
            // ровно этой веткой — и первая версия правки оставляла бота в паузе
            // навсегда в тот единственный момент, ради которого пауза и заведена.
            frozenSinceMs = 0;
            maybeResume(now);
            return;
        }
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            return;                       // не знаем состояние — молчим
        }
        double visible = 0;
        // 🔑 ЗАПЕРТОЕ НАШИМИ ЖЕ ЗАЯВКАМИ — ДОСЯГАЕМО, и это не тонкость.
        //
        // `available` не включает монету под нашей стоящей продажей, но эта
        // монета никуда не делась: заявка исполнится или снимется, и монета
        // вернётся. Считать покрытие по одному `available` значит наказывать
        // бота за то, что он работает.
        //
        // 16.09.2026 это сразу дало ложную распродажу: бот доложил «доступного
        // хватает лишь на 67%», имея при этом полное покрытие — недостающее
        // лежало в его собственных заявках. Поэтому в числителе не `available`,
        // а `available + наши видимые заявки`, что по тождеству площадки равно
        // «всего минус по-настоящему недосягаемое».
        //
        // USDC считается по ВСЕМ парам: котируемая валюта общая, и покупка
        // соседа запирает её так же, как наша.
        double visibleBuysQuote = 0;
        for (ActiveOrder o : ActiveOrder.parse(active.body())) {
            if (o.side() == Side.SELL && ActiveOrder.normalize(symbol).equals(o.symbol())) {
                visible += o.size();
            }
            if (o.side() == Side.BUY && o.symbol() != null && o.symbol().endsWith("/" + quote)) {
                visibleBuysQuote += o.size() * o.price();
            }
        }
        double frozen = baseReserved - visible;

        // 🔑 ЛЕСТНИЦА СЧИТАЕТСЯ ПО ПОКРЫТИЮ, А НЕ ПО РАЗМЕРУ ЛОТА.
        //
        // Здесь стояло условие «заперто ≥ лота ЭТОГО бота», и оно тихо вернуло
        // ровно ту зависимость, которую мерка покрытия и убирала: 16.09.2026
        // заперло 0.00040265 ETH, у бота `d` это ровно лот — он лестницу прошёл,
        // а у соседа `c` лот втрое крупнее, и до проверки покрытия он не дошёл
        // вовсе. Одна заморозка, один счёт, разное поведение — признак того, что
        // мерка смотрит не туда.
        // Этап 3, §3.6: запертое призраком — своё и вернётся, в покрытие оно входит.
        // Распродажа остаётся на настоящую нехватку (претензий больше, чем монет),
        // а не на то, что площадка держит резерв без заявки.
        double gapBase = VENUE_LOCKS ? Math.max(0, baseReserved - visible) : 0;
        double gapQuote = VENUE_LOCKS ? Math.max(0, quoteReserved - visibleBuysQuote) : 0;
        double cover = Math.min(
                coverage(baseAvailable + visible + gapBase, claimedAll(base, now)),
                coverage(quoteBalance + visibleBuysQuote + gapQuote, claimedAll(quote, now)));
        if (cover >= COVERAGE_UNWIND) {
            frozenSinceMs = 0;
            maybeResume(now);
            return;
        }
        // Пять минут выдержки: остатки и список заявок читаются РАЗНЫМИ
        // запросами, и мгновенное расхождение между ними штатно.
        if (frozenSinceMs == 0) {
            frozenSinceMs = now;
            return;
        }
        if (now - frozenSinceMs < 5 * 60_000L) {
            return;
        }
        // ⚠️ СТУПЕНИ СТАВЯТСЯ РАНЬШЕ ТРЕВОГИ И НЕ ЗАВИСЯТ ОТ ЕЁ ПОВТОРА.
        //
        // Тревога повторяется не чаще раза в час, чтобы не будить человека
        // каждую минуту. Если привязать к ней и остановку, то бот, у которого
        // заморозка длится третий час, окажется НЕ остановлен: условие про час
        // не выполнено, и до самой остановки дело не дойдёт.
        //
        // Первая ступень: пока ДОСТУПНОГО хватает на то, что за ботами числится,
        // торгуем. Буфером работает свободный остаток, а не отдельная куча.
        if (cover < COVERAGE_UNWIND && !frozenUnwind) {
            frozenUnwind = true;
            String msg = ("РАСПРОДАЖА: доступного хватает лишь на %.0f%% того, что "
                    + "числится за ботами (заперто %s %s). Цель скоса в ноль, "
                    + "покупки прекращаю, свожу инвентарь к нулю.")
                    .formatted(cover * 100, fmt(frozen), base);
            log.error(msg);
            journal.event("frozen_unwind", msg);
            alert.accept(msg);
        }
        // Вторая ступень: распродано — стоим. Порог в лот, а не в ноль: остаток
        // мельче лота продать нельзя, он ниже минимума площадки.
        if (frozenUnwind && inventory < params.size() && !frozenPaused) {
            frozenPaused = true;
            standAside("распродано, монета заперта — жду площадку");
        }
        if (now - frozenWarnedMs < 3_600_000L) {
            return;
        }
        frozenWarnedMs = now;
        String message = ("ЗАПЕРТО БЕЗ ЗАЯВКИ: площадка держит %s %s в резерве, а видимые "
                + "заявки объясняют только %s. Разница %s (%.1f лота) не отпускается уже "
                + "%d мин: продать эту монету нельзя, снять нечего — идентификатора у неё "
                + "нет. Это сторона площадки; прибор для разбора — --revx-audit.")
                .formatted(fmt(baseReserved), base, fmt(visible), fmt(frozen),
                        frozen / params.size(), (now - frozenSinceMs) / 60_000);
        // ⚠️ БУДИМ, ТОЛЬКО ЕСЛИ ЗАМОРОЗКА КУСАЕТ. Правило владельца: тревога — про
        // то, что внутри не сходится либо монет не хватает. Запертый лот из семи
        // не мешает торговать (ступень «торгуем» на то и заведена), а 16.09.2026
        // заморозки на ETH шли раз в двадцать минут и снимались сами за
        // четверть часа — это был бы поток тревог ни о чём.
        //
        // В журнале запись остаётся всегда: по ней считается частота, а частота
        // и есть ответ на вопрос, живёт ли такой бот на этой площадке.
        log.error(message);
        journal.event("frozen_reserve", message);
        if (frozenUnwind || frozenPaused) {
            alert.accept(message);
        }
    }

    /**
     * Возврат в работу, когда площадка отпустила резерв.
     *
     * Ждать человека здесь нечего: замер 14–16.09.2026 показал, что площадка
     * убирает призраков сама, но с непредсказуемой задержкой — от двух часов
     * (призрак 15.09 01:38 ушёл до 06:00) до тридцати двух (призрак 14.09 15:38
     * дожил до 15.09 23:50). Все выжившие отпустились ОДНОЙ минутой, то есть
     * это сверка по счёту, а не таймаут на заявку, и предсказать её нельзя.
     * Значит единственная разумная политика — ждать и вернуться самому.
     */
    /**
     * Возврат в работу — ТОЛЬКО при полном покрытии.
     *
     * ⚠️ Порог возврата выше порога ухода (1.0 против 0.7) намеренно. Совпади
     * они — бот дёргался бы туда-сюда на границе, а каждый заход в распродажу
     * стоит сведённой позиции и потерянного оборота. Разные пороги дают
     * гистерезис: уходим при заметной нехватке, возвращаемся, когда хватает
     * всего и всем.
     */
    private void maybeResume(long now) {
        if (!frozenPaused && !frozenUnwind) {
            return;
        }
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            return;                       // не знаем состояние — не возвращаемся вслепую
        }
        double visible = 0;
        double visibleBuysQuote = 0;
        for (ActiveOrder o : ActiveOrder.parse(active.body())) {
            if (o.side() == Side.SELL && ActiveOrder.normalize(symbol).equals(o.symbol())) {
                visible += o.size();
            }
            if (o.side() == Side.BUY && o.symbol() != null && o.symbol().endsWith("/" + quote)) {
                visibleBuysQuote += o.size() * o.price();
            }
        }
        // Этап 3, §3.6: запертое призраком — своё и вернётся, в покрытие оно входит.
        // Распродажа остаётся на настоящую нехватку (претензий больше, чем монет),
        // а не на то, что площадка держит резерв без заявки.
        double gapBase = VENUE_LOCKS ? Math.max(0, baseReserved - visible) : 0;
        double gapQuote = VENUE_LOCKS ? Math.max(0, quoteReserved - visibleBuysQuote) : 0;
        double cover = Math.min(
                coverage(baseAvailable + visible + gapBase, claimedAll(base, now)),
                coverage(quoteBalance + visibleBuysQuote + gapQuote, claimedAll(quote, now)));
        if (cover < COVERAGE_RESUME) {
            return;
        }
        resumeAfterFrozen(now);
    }

    private void resumeAfterFrozen(long now) {
        if (!frozenPaused && !frozenUnwind) {
            return;
        }
        frozenPaused = false;
        frozenUnwind = false;
        frozenWarnedMs = 0;
        String message = "резерв отпущен — возвращаюсь в работу по " + symbol;
        log.warn(message);
        journal.event("frozen_released", message);
        alert.accept(message);
    }

    /**
     * РЕЕСТР ПРОТИВ ЖУРНАЛА: они обязаны совпадать до пыли.
     *
     * Обе величины двигает один и тот же код: {@code applyFill} прибавляет к
     * позиции в журнале и к претензии в реестре. Значит расхождение означает
     * ровно одно — одна из двух записей не прошла. Реестр при ошибке записи
     * только пишет в лог ({@code log.error}) и работает дальше, то есть тихо
     * разъезжается с журналом, а замечается это через сутки по «на счёте монеты
     * есть, а бот их не видит».
     *
     * ⚠️ Журнал — первоисточник, реестр — кэш. Поэтому здесь только ТРЕВОГА, и
     * никакого самолечения: подгонять кэш под журнал молча значит стереть след
     * настоящей поломки. Лечится перезахватом руками.
     */
    private void checkRegistryAgainstJournal(long now) {
        double claimed = alloc.own(tag.id(), base);
        double drift = Math.abs(claimed - inventory);
        // Пыль: позиция и претензия считаются в double, и на длинной серии
        // сделок последние знаки расходятся законно.
        double dust = Math.max(1e-12, params.size() * 1e-6);
        if (drift <= dust || now - registryWarnedMs < MISMATCH_REPEAT_MS) {
            return;
        }
        registryWarnedMs = now;
        String message = ("РЕЕСТР РАЗОШЁЛСЯ С ЖУРНАЛОМ: за мной числится %s %s, "
                + "а по своим сделкам у меня %s. Реестр — кэш, журнал — истина; "
                + "чинить перезахватом (/stop, /release, /claim всё), но сначала посмотреть, "
                + "почему не прошла запись.")
                .formatted(fmt(claimed), base, fmt(inventory));
        log.error(message);
        journal.event("registry_drift", message);
        alert.accept(message);
    }

    /**
     * НИЧЕЙНОЕ НА СЧЁТЕ: монета есть, а хозяина у неё нет.
     *
     * ⚠️ Это самая тихая из поломок. Ничейной монетой никто не торгует, ни один
     * счётчик её не показывает, и узнаёт о ней владелец, случайно заглянув в
     * приложение площадки. Так было 10.09.2026 (лот пролежал ничейным восемь
     * часов) и 13.09.2026 (по лоту на каждой из трёх пар после {@code /release}
     * без обратного захвата).
     *
     * Порог — ЛОТ: меньше лота ничем не торгуют, а запаздывание снимка остатков
     * (раз в минуту) на лот и отличается. Повтор не чаще раза в час: тревога,
     * которую видно каждую минуту, перестаёт быть тревогой.
     */
    /**
     * Новость ли это, или уже названное.
     *
     * @param free     сколько ничейного сейчас
     * @param reported сколько называли в прошлый раз; 0 — не называли
     * @param lot      размер лота: шаг, которым приходит новая беда
     */
    static boolean unownedIsNews(double free, double reported, double lot) {
        // ⚠️ Допуск обязателен, и это не педантизм: РОВНО лот сверху — канонический
        // случай новой беды (пришло одно исполнение и не нашло хозяина), а на
        // двоичной арифметике 6×0.00944 оказывается чуть меньше 5×0.00944 + 0.00944.
        // Без допуска сторож промолчал бы именно там, где обязан кричать.
        double dust = Math.max(1e-12, lot * 1e-6);
        return reported <= 0 || free >= reported + lot - dust;
    }

    private void checkUnowned(long now) {
        if (!(params.size() > 0) || Double.isNaN(baseTotalAccount)
                || now - unownedWarnedMs < 3_600_000L) {
            return;
        }
        // ⚠️ ОСТАНОВЛЕННОМУ БОТУ ЭТО ГОВОРИТЬ НЕЗАЧЕМ, и совет в самой тревоге
        // это выдаёт: «/stop, затем /claim» обращён к работающему. Молчание тут
        // не потеря сторожа, а его точность: пока котирование выключено, человек
        // и так стоит руками у счёта, а ничейное чаще всего сам же и сделал
        // через /release. 16.09.2026 это дало шесть тревог в час на нарочное
        // состояние.
        if (!quoting.get()) {
            return;
        }
        double free = alloc.free(base, baseTotalAccount, now).free();
        if (free < params.size()) {
            unownedReported = 0;          // ничейное разобрали — следующее будет новостью
            return;
        }
        // 🔑 СООБЩАЕМ НОВОСТЬ, А НЕ СОСТОЯНИЕ.
        //
        // Ничейный остаток бывает ДОЛГИМ и осознанным: владелец освободил
        // претензии, чтобы начать с нуля, и остаток будет лежать, пока его не
        // продадут. Повторять про него каждый час значит приучить не читать
        // тревоги — а следующая будет про потерянное исполнение.
        //
        // Поэтому молчим, пока ничейное не ВЫРАСТЕТ ещё на лот: именно так
        // выглядит новая беда (лот пришёл и хозяина не нашёл), тогда как
        // неизменная величина — это уже известное.
        if (!unownedIsNews(free, unownedReported, params.size())) {
            return;
        }
        // Заперто в заявке без живого хозяина — тоже ничейное, но забирать его
        // нельзя, и в тревоге это надо различать: иначе совет «заберите» приведёт
        // к фантомному инвентарю.
        Locked locked = lockedNow(now);
        double orphan = locked == null ? 0 : locked.orphans();
        unownedWarnedMs = now;
        unownedReported = free;
        String message = ("НИЧЕЙНОЕ: на счёте %s %s не числится ни за одним ботом (%.1f лота). %s")
                .formatted(fmt(free), base, free / params.size(),
                        orphan > params.size() * 0.01
                                ? "Из них " + fmt(orphan) + " заперто в заявках без живого "
                                        + "хозяина — их сначала надо снять."
                                : "Забрать: /stop, затем /claim всё.");
        // ⚠️ ЭТО НЕ ТРЕВОГА, и с 16.09.2026 намеренно.
        //
        // Правило владельца: будить только тем, что внутри бота НЕ СХОДИТСЯ,
        // либо монеты и USDC не хватает. Ничейное — не то и не другое: счёт
        // цел, бот исправен, монета просто лежит, и владелец её видит в сводном
        // боте вместе с ценой. Тревога же приходила от каждого бота по монете
        // раз в час, в том числе на состояние, сделанное самим владельцем через
        // /release, — то есть приучала не читать тревоги.
        //
        // В журнале запись ОСТАЁТСЯ: разбор задним числом стоит дёшево, а
        // молчащий журнал стоил бы дорого.
        log.warn(message);
        journal.event("unowned", message);
    }

    /**
     * 🔑 ЭТАП 2 ЧИТАТЕЛЯ ПЛОЩАДКИ: ИСПОЛНЕНИЯ ПО ЛЕНТЕ СДЕЛОК (27.09.2026).
     *
     * Схема и доказательства — {@code docs/pairs/ЧИТАТЕЛЬ-ПЛОЩАДКИ.md}. Бот узнаёт об
     * исполнении по исчезновению заявки и её статусу, и в затык площадки этот путь
     * терял сделки (шесть за сутки прогона 22–23.09: 204 на отмену уже исполненной,
     * 404 на живую). Правдой в разобранных случаях была только лента своих сделок
     * {@code /trades/private}, которую читатель складывает в {@code venue.db}.
     *
     * Раз в минуту бот берёт из ленты свои сделки (метка бота, своя пара) старше
     * {@link #LEDGER_GRACE_MS} и моложе {@link #LEDGER_LOOKBACK_MS} и дописывает
     * разницу «исполнено площадкой − записано в журнале» обычным {@link #book}: он
     * пишет разницу по заявке, поэтому повторная встреча той же сделки ничего не
     * сдвигает. Льгота пять минут — обычный путь бывает выясняет судьбу заявки за
     * 1–4 минуты (fate_resolved), и с ним нельзя бежать наперегонки:
     * ленте остаётся только то, что он пропустил, и об этом — тревога.
     *
     * Всё в потоке котирования, как и остальной учёт: гонок с {@code book} нет.
     * Работает только у живого бота и только если база читателя на месте; у
     * стенда и без читателя — ничего не делает.
     */
    private void bookFromLedger(long now) {
        if (!ledgerActive()) {
            return;
        }
        String sql = "SELECT t.oid, MAX(t.side), SUM(t.qty), SUM(t.qty * t.price), MAX(t.tdt), "
                + "MAX(o.status) FROM trade t JOIN order_info o ON o.oid = t.oid "
                + "WHERE o.bot = ? AND o.symbol = ? AND t.tdt >= ? GROUP BY t.oid "
                // Отсрочка по ПОСЛЕДНЕЙ сделке заявки: пока добивка частичной свежая,
                // её ведёт обычный путь, и половину заявки лента не трогает.
                + "HAVING MAX(t.tdt) <= ?";
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + VENUE_DB + "?mode=ro");
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tag.id());
            ps.setString(2, symbol);
            ps.setLong(3, now - LEDGER_LOOKBACK_MS);
            ps.setLong(4, now - LEDGER_GRACE_MS);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getLong(5), rs.getString(6)});
                }
            }
        } catch (Exception e) {
            log.warn("лента сделок не прочитана: {}", e.toString());
            return;
        }
        for (Object[] r : rows) {
            String oid = (String) r[0];
            double total = (double) r[2];
            double already = bookedByOrder.containsKey(oid)
                    ? bookedByOrder.get(oid) : journal.bookedFor(oid);
            if (total - already <= 1e-12 || total <= 0) {
                continue;
            }
            Side side = "sell".equalsIgnoreCase((String) r[1]) ? Side.SELL : Side.BUY;
            double price = (double) r[3] / total;
            String status = r[5] == null ? "partially_filled" : (String) r[5];
            String body = String.format(java.util.Locale.ROOT,
                    "{\"status\":\"%s\",\"filled_quantity\":\"%s\",\"price\":\"%s\",\"total_fee\":\"0\"}",
                    status, java.math.BigDecimal.valueOf(total).toPlainString(),
                    java.math.BigDecimal.valueOf(price).toPlainString());
            double before = inventory;
            book(side, oid, body);
            String message = String.format(java.util.Locale.ROOT,
                    "ИСПОЛНЕНИЕ ПО ЛЕНТЕ: %s %s по %s (заявка %s, сделка %s) — обычный путь его "
                            + "пропустил. Позиция %s → %s.",
                    side, fmt(total - already), fmt(price), oid,
                    java.time.Instant.ofEpochMilli((long) r[4]), fmt(before), fmt(inventory));
            log.error(message);
            journal.event("ledger_fill", message);
            alert.accept(message);
        }
    }

    /** Живой бот и база читателя на месте. */
    private boolean ledgerActive() {
        return (client instanceof TradeClient
                || client instanceof VenueReads vr && vr.inner() instanceof TradeClient)
                && java.nio.file.Files.exists(java.nio.file.Path.of(VENUE_DB));
    }

    /** База читателя площадки. */
    static final String VENUE_DB =
            System.getProperty("revx.exec.venue-db", "/home/ubuntu/revx-shared/venue.db");
    /** Сделку моложе этого ведёт обычный путь — лента подбирает только пропущенное. */
    static final long LEDGER_GRACE_MS = 5 * 60_000L;
    /**
     * Глубже не смотрим: старые пропуски записаны по догадке или вручную без id
     * заявки, и лента записала бы их второй раз. Шесть часов покрывают любой затык
     * и перезапуск.
     */
    static final long LEDGER_LOOKBACK_MS = 6 * 3_600_000L;

    // ================================================================ этап 3

    /**
     * 🔑 ЭТАП 3 ЧИТАТЕЛЯ: исполнения из ленты сразу, а не страховкой через 5 минут.
     * Включается свойством {@code revx.exec.venue-fills=true} — по одному боту.
     */
    static final boolean VENUE_FILLS =
            Boolean.parseBoolean(System.getProperty("revx.exec.venue-fills", "false"));
    /** Читатель старше этого — считаем, что его нет, и возвращаемся к своим GET. */
    static final long VENUE_FRESH_MS = 10_000L;
    /** Как часто бот смотрит в ленту. */
    static final long VENUE_FILLS_EVERY_MS = 2_000L;
    /** Глубина быстрого пути; старше — страховочный {@link #bookFromLedger}. */
    static final long VENUE_FILLS_LOOKBACK_MS = 30 * 60_000L;

    private long lastVenueFillsMs;
    private boolean venueFresh;

    /**
     * Этап 3б: {@code /orders/active} и {@code /balances} — из снимков читателя
     * ({@link VenueReads}); сам бот спрашивает, только если снимок несвеж.
     */
    static final boolean VENUE_READS =
            Boolean.parseBoolean(System.getProperty("revx.exec.venue-reads", "false"));
    private long lastReadsReportMs;

    // ------------------------------------------------ этап 3, §3.5–3.6: запертое

    /**
     * 🔑 ЗАПЕРТОЕ ПРИЗРАКОМ — СВОЁ, НО НЕДОСТУПНОЕ. Флаг {@code revx.exec.venue-locks}.
     *
     * Призрак замены (медленный 422 в затык) снимает предка, наследника не
     * создаёт, а резерв предка держит 2–32 ч. Монеты и касса бота никуда не
     * делись — в позиции и скосе они остаются, — но продать или потратить их
     * нельзя. Без учёта бот видит в {@code available} чужое (ничейное, соседское)
     * и продаёт его вместо своего запертого.
     */
    static final boolean VENUE_LOCKS =
            Boolean.parseBoolean(System.getProperty("revx.exec.venue-locks", "false"));
    /** Не подтвердилось разрывом резерва за столько — подозрение ложное. */
    static final long GHOST_CONFIRM_MS = 5 * 60_000L;

    private static final class GhostSuspect {
        final Side side;
        /** Монета (продажа) или касса (покупка), которую держал предок. */
        final double amount;
        final long sinceMs;
        boolean confirmed;

        GhostSuspect(Side side, double amount, long sinceMs) {
            this.side = side;
            this.amount = amount;
            this.sinceMs = sinceMs;
        }
    }

    private final java.util.List<GhostSuspect> ghostSuspects = new java.util.ArrayList<>();

    private void addGhostSuspect(Side side, double size, double price) {
        double amount = side == Side.SELL ? size : size * price;
        ghostSuspects.add(new GhostSuspect(side, amount, clock.now()));
        String text = String.format(java.util.Locale.ROOT,
                "подозрение на призрака: %s, предок держал %s %s — не продаю и не трачу это, "
                        + "пока разрыв резерва не скажет иначе",
                side, fmt(amount), side == Side.SELL ? base : quote);
        log.warn(text);
        journal.event("ghost_suspect", text);
    }

    /** Своя монета, запертая призраком: в позиции есть, продать нельзя. */
    double lockedBase() {
        double sum = 0;
        for (GhostSuspect g : ghostSuspects) {
            if (g.side == Side.SELL) {
                sum += g.amount;
            }
        }
        return sum;
    }

    /**
     * 🔑 БУФЕР: запертое своё покрывается НИЧЕЙНЫМ свободным на счёте (решение
     * владельца 28.09.2026). Продать ничейную монету вместо своей запертой — верно
     * по учёту: претензия бота −лот, всего на счёте −лот, ничейное то же; призрак
     * отпустит — своя монета займёт место проданной. Не берём только ЧУЖОЕ
     * (числящееся за другими ботами): ничейное = всего − Σ претензий всех ботов.
     * Не продаём лишь ту часть запертого, что буфером не покрыта.
     */
    private double ownerlessFree(String currency, double total, double available) {
        if (alloc == null || !Double.isFinite(total)) {
            return 0;
        }
        return Math.max(0, Math.min(total - claimedAll(currency, clock.now()), available));
    }

    /** Запертая своя монета, НЕ покрытая буфером: её одну и нельзя продавать. */
    double lockedBaseEff() {
        double locked = lockedBase();
        return locked <= 0 ? 0
                : Math.max(0, locked - ownerlessFree(base, baseTotalAccount, baseAvailable));
    }

    /** Запертая своя касса, не покрытая ничейными USDC. */
    double lockedQuoteEff() {
        double locked = lockedQuote();
        return locked <= 0 ? 0
                : Math.max(0, locked - ownerlessFree(quote, quoteTotal, quoteBalance));
    }

    /** Своя касса, запертая призраком покупки. */
    double lockedQuote() {
        double sum = 0;
        for (GhostSuspect g : ghostSuspects) {
            if (g.side == Side.BUY) {
                sum += g.amount;
            }
        }
        return sum;
    }

    /**
     * Сверка подозрений с разрывом «резерв площадки минус видимые заявки».
     * Разрыв общий на счёт (в нём и соседские призраки), поэтому правила
     * осторожные: подтверждаем, если разрыва хватает на подозрение; снимаем
     * подтверждённые, начиная со старых, когда разрыв стал меньше их суммы.
     */
    private void trackGhosts(long now) {
        if (!VENUE_LOCKS || ghostSuspects.isEmpty()) {
            return;
        }
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            return;
        }
        double visibleSell = 0;
        double visibleBuyQuote = 0;
        for (ActiveOrder o : ActiveOrder.parse(active.body())) {
            if (o.side() == Side.SELL && ActiveOrder.normalize(symbol).equals(o.symbol())) {
                visibleSell += o.size();
            }
            if (o.side() == Side.BUY && o.symbol() != null && o.symbol().endsWith("/" + quote)) {
                visibleBuyQuote += o.size() * o.price();
            }
        }
        settleGhosts(Side.SELL, Math.max(0, baseReserved - visibleSell), now);
        settleGhosts(Side.BUY, Math.max(0, quoteReserved - visibleBuyQuote), now);
    }

    private void settleGhosts(Side side, double gap, long now) {
        java.util.List<GhostSuspect> mine = new java.util.ArrayList<>();
        for (GhostSuspect g : ghostSuspects) {
            if (g.side == side) {
                mine.add(g);
            }
        }
        String unit = side == Side.SELL ? base : quote;
        for (GhostSuspect g : mine) {
            if (g.confirmed) {
                continue;
            }
            if (gap >= g.amount * 0.9) {
                g.confirmed = true;
                double buffer = side == Side.SELL
                        ? ownerlessFree(base, baseTotalAccount, baseAvailable)
                        : ownerlessFree(quote, quoteTotal, quoteBalance);
                journal.event("ghost_confirmed", String.format(java.util.Locale.ROOT,
                        "%s %s %s заперто: разрыв резерва %s; ничейный буфер %s — %s",
                        side, fmt(g.amount), unit, fmt(gap), fmt(buffer),
                        buffer >= g.amount ? "покрывает, торгую как обычно"
                                : "покрывает не всё, непокрытое не трогаю"));
            } else if (now - g.sinceMs >= GHOST_CONFIRM_MS) {
                ghostSuspects.remove(g);
                journal.event("ghost_false", String.format(java.util.Locale.ROOT,
                        "%s %s %s: разрыва резерва нет (%s) — отказ был без призрака",
                        side, fmt(g.amount), unit, fmt(gap)));
            }
        }
        double held = 0;
        for (GhostSuspect g : ghostSuspects) {
            if (g.side == side && g.confirmed) {
                held += g.amount;
            }
        }
        // Отпустили — разрыв меньше того, что мы за собой держим. Снимаем старые.
        for (GhostSuspect g : mine) {
            if (!g.confirmed || held <= gap * 1.1 + 1e-12) {
                continue;
            }
            ghostSuspects.remove(g);
            held -= g.amount;
            String text = String.format(java.util.Locale.ROOT,
                    "площадка отпустила %s %s (%s, заперто было %d мин); разрыв резерва теперь %s",
                    fmt(g.amount), unit, side, (now - g.sinceMs) / 60_000, fmt(gap));
            log.info(text);
            journal.event("ghost_released", text);
        }
    }

    /** Лента читателя свежа и бот в неё смотрит — свои GET по предкам не нужны. */
    private boolean venueCovers() {
        return VENUE_FILLS && venueFresh
                && clock.now() - lastVenueFillsMs < VENUE_FRESH_MS;
    }

    /** Последний УСПЕШНЫЙ цикл читателя не старше {@link #VENUE_FRESH_MS}. */
    private boolean readerFresh(long now) {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + VENUE_DB + "?mode=ro");
             java.sql.ResultSet rs = c.createStatement().executeQuery(
                     "SELECT MAX(last_ok_ms) FROM heartbeat")) {
            return rs.next() && now - rs.getLong(1) <= VENUE_FRESH_MS;
        } catch (Exception e) {
            log.warn("читатель площадки не прочитан: {}", e.toString());
            return false;
        }
    }

    /**
     * Свои сделки из ленты — по метке бота ИЛИ по памяти своих заявок — пишутся
     * СРАЗУ тем же {@link #book} (он пишет разницу по заявке, повтор ничего не
     * сдвигает, и обычный путь с ним не конфликтует). Исполненная целиком заявка
     * текущего слота освобождает слот: следующий тик поставит новую, не дожидаясь
     * отказа 422 и вопроса о судьбе.
     */
    private void venueFills(long now) {
        java.util.List<VenueTrade> rows = new java.util.ArrayList<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + VENUE_DB + "?mode=ro");
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT t.oid, MAX(t.side), SUM(t.qty), SUM(t.qty * t.price), MAX(t.tdt), "
                             + "MAX(o.status), MAX(o.bot) FROM trade t "
                             + "LEFT JOIN order_info o ON o.oid = t.oid "
                             + "WHERE t.symbol = ? AND t.tdt >= ? GROUP BY t.oid")) {
            ps.setString(1, symbol.replace('/', '-'));
            ps.setLong(2, now - VENUE_FILLS_LOOKBACK_MS);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new VenueTrade(rs.getString(1), rs.getString(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
                }
            }
        } catch (Exception e) {
            log.warn("лента сделок не прочитана: {}", e.toString());
            venueFresh = false;
            return;
        }
        for (VenueTrade t : rows) {
            if (!mine(t.bot(), myOrders.containsKey(t.oid()), tag.id())) {
                continue;
            }
            Side side = "sell".equalsIgnoreCase(t.side()) ? Side.SELL : Side.BUY;
            double already = bookedByOrder.containsKey(t.oid())
                    ? bookedByOrder.get(t.oid()) : journal.bookedFor(t.oid());
            if (t.qty() - already > 1e-12) {
                double price = t.notional() / t.qty();
                String status = t.status() == null ? "partially_filled" : t.status();
                String body = String.format(java.util.Locale.ROOT,
                        "{\"status\":\"%s\",\"filled_quantity\":\"%s\",\"price\":\"%s\",\"total_fee\":\"0\"}",
                        status, java.math.BigDecimal.valueOf(t.qty()).toPlainString(),
                        java.math.BigDecimal.valueOf(price).toPlainString());
                book(side, t.oid(), body);
                journal.event("venue_fill", String.format(java.util.Locale.ROOT,
                        "%s %s по %s (заявка %s, сделка %s, статус %s) — из ленты читателя",
                        side, fmt(t.qty() - already), fmt(price), t.oid(),
                        java.time.Instant.ofEpochMilli(t.lastTdt()), status));
            }
            if ("filled".equalsIgnoreCase(t.status())) {
                for (Resting r : side == Side.BUY ? bids : asks) {
                    if (t.oid().equals(r.venueId)) {
                        closePartial(r, "добрана");
                        log.info("заявка {} {} исполнена целиком (лента) — слот свободен",
                                side, t.oid());
                        r.venueId = null;
                    }
                }
            }
        }
    }

    /** Сделка площадки наша: по метке бота у читателя или по памяти своих заявок. */
    static boolean mine(String readerBot, boolean remembered, String myId) {
        return remembered || myId.equals(readerBot);
    }

    private record VenueTrade(String oid, String side, double qty, double notional, long lastTdt,
                              String status, String bot) {
    }

    // ------------------------------------------------ этап 3, §3.3: слот под вопросом

    /** Флаг {@code revx.exec.venue-slots}: снятая заявка держит слот до окончательной судьбы. */
    static final boolean VENUE_SLOTS =
            Boolean.parseBoolean(System.getProperty("revx.exec.venue-slots", "false"));
    /** Тишина после отмены: сделка на живой приходила через 4 с после 204 (b, 22.09). */
    static final long QUIET_MS = 10_000L;
    /** Как часто спрашивать судьбу заявки под вопросом. */
    static final long QUESTION_ASK_MS = 2_000L;
    /** Жива после отмены — отменяем снова не чаще. */
    static final long QUESTION_RECANCEL_MS = 5_000L;
    /** Площадка так и не ответила — слот отпускаем, исполнение подберёт лента. */
    static final long QUESTION_GIVEUP_MS = 30 * 60_000L;

    private void settleQuestioned(long now) {
        if (!VENUE_SLOTS) {
            return;
        }
        for (Resting r : bids) {
            settleQuestioned(r, now);
        }
        for (Resting r : asks) {
            settleQuestioned(r, now);
        }
    }

    private void settleQuestioned(Resting r, long now) {
        if (r.questioned == null || now - r.qAskedMs < QUESTION_ASK_MS) {
            return;
        }
        r.qAskedMs = now;
        String id = r.questioned;
        Venue.Response resp = client.order(id);
        if (resp.ok() && resp.body() != null) {
            unknownFate.remove(id);
            String status = book(r.qSide, id, resp.body());      // исполнение — разницей
            if (status != null && !terminal(status)) {
                // Жива после нашей отмены (404 на живую, 204 «на словах»): снимаем снова.
                if (now - r.qRecancelMs >= QUESTION_RECANCEL_MS) {
                    r.qRecancelMs = now;
                    Venue.Response again = client.cancel(id);
                    cancels++;
                    journal.event("question_recancel", r.qSide + " " + id + ": после отмены «"
                            + status + "» — снимаю снова → " + again.status());
                }
                return;
            }
            if (status != null && now - r.qSinceMs >= QUIET_MS) {
                journal.closeOrder(id, status, now);
                if (now - r.qSinceMs > QUIET_MS + QUESTION_ASK_MS) {
                    journal.event("question_settled", String.format(java.util.Locale.ROOT,
                            "%s %s: судьба «%s» через %d с — слот свободен",
                            r.qSide, id, status, (now - r.qSinceMs) / 1000));
                }
                r.questioned = null;
            }
            return;
        }
        if (now - r.qSinceMs >= QUESTION_GIVEUP_MS) {
            String text = String.format(java.util.Locale.ROOT,
                    "%s %s: судьба не выяснена за %d мин (последний ответ %d) — слот отпускаю, "
                            + "исполнение, если было, придёт лентой",
                    r.qSide, id, (now - r.qSinceMs) / 60_000, resp.status());
            log.warn(text);
            journal.event("question_expired", text);
            unknownFate.putIfAbsent(id, new UnknownFate(r.qSide, now));
            r.questioned = null;
        }
    }

    private void rollCounters() {
        long now = clock.now();
        settleQuestioned(now);
        if (VENUE_FILLS && now - lastVenueFillsMs >= VENUE_FILLS_EVERY_MS && ledgerActive()) {
            lastVenueFillsMs = now;
            venueFresh = readerFresh(now);
            if (venueFresh) {
                venueFills(now);
            }
        }
        if (now - minuteStartMs >= 60_000) {
            minuteStartMs = now;
            replacesThisMinute = 0;
            // Аренда продлевается, пока ЖИВ ПРОЦЕСС, а не пока идёт котирование:
            // /stop на час претензию терять не должен, а убитый процесс — должен.
            // Сначала переспрашиваем про повисшие заявки: расхождение позиции
            // чаще всего именно этим и объясняется, и кричать о нём, не задав
            // вопрос ещё раз, значит будить человека зря.
            retryUnknownFates(now);
            bookFromLedger(now);
            if (client instanceof VenueReads vr && now - lastReadsReportMs >= 3_600_000L) {
                lastReadsReportMs = now;
                journal.event("venue_reads", "списочные GET за жизнь процесса: " + vr.stats());
            }
            if (alloc != null) {
                alloc.heartbeat(tag.id(), now);
                checkRegistryAgainstJournal(now);
                claimHeirIfEvidenceMatches(now);
                checkUnowned(now);
            }
            // Сторож запертого без заявки работает и без реестра: он сверяет
            // остаток площадки с её же списком заявок.
            checkFrozen(now);
            // Остатки перечитываются раз в минуту: исполнение могло случиться молча.
            refreshBalances();
            trackGhosts(now);
            if (budget != null) {
                PlacementBudget.State state = budget.state(tag.id(), now);
                double was = budgetPressure;
                budgetPressure = state.pressure();
                if (Math.abs(budgetPressure - was) > 0.05) {
                    log.warn("бюджет постановок: {} токенов, свой расход {} за сутки, "
                                    + "по аккаунту {} — отступ раздвинут на {}%",
                            Math.round(state.tokens()), state.ownSpendDay(),
                            state.totalSpendDay(),
                            Math.round(BUDGET_WIDEN * budgetPressure * 100));
                }
                budget.prune(now);
            }
        }
        // Суточный счётчик постановок БОЛЬШЕ НЕ ОБНУЛЯЕТСЯ здесь: он считается
        // по журналу скользящим окном (placementsLastDay). Прежнее обнуление было
        // опрокидывающимся окном от старта процесса и допускало до 2N подряд на
        // стыке, а рестарт сбрасывал его целиком.
        if (now - lastReconcileMs >= RECONCILE_PERIOD_MS) {
            lastReconcileMs = now;
            reconcile("плановая сверка");
        }
    }


    private static String extract(String body) {
        Matcher matcher = VENUE_ID.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Без экспоненты и без лишних нулей: площадка принимает десятичную строку. */
    /**
     * РАЗМЕР ЗАЯВКИ — С ОКРУГЛЕНИЕМ К ШАГУ ПАРЫ.
     *
     * ⚠️ Без этого в запрос уходит мусор двоичной арифметики. Три лота ETH по
     * 0.00040265 в сумме дают 0.0012079499999999999, `BigDecimal.valueOf`
     * печатает все девятнадцать знаков, и площадка отвечает
     * `400 base_size precision must not exceed 8 decimal places`.
     *
     * Поймано 11.09.2026 на опытном боте d: за пятнадцать минут ТРИДЦАТЬ таких
     * отказов подряд при двух удачных постановках. Бот не мог продать вообще,
     * молотил вхолостую и жёг общий лимит запросов — со стороны выглядело как
     * «не хватает лимитов».
     *
     * Округляем ВНИЗ: продать больше, чем есть, нельзя, а остаток мельче шага
     * всё равно не примут.
     */
    private String fmtSize(double size) {
        if (!(baseStep > 0)) {
            return fmt(size);
        }
        java.math.BigDecimal step = java.math.BigDecimal.valueOf(baseStep);
        java.math.BigDecimal q = java.math.BigDecimal.valueOf(size)
                .divide(step, 0, java.math.RoundingMode.DOWN)
                .multiply(step);
        return q.stripTrailingZeros().toPlainString();
    }

    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
