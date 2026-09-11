package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цикл котирования. Та же формула, что в симуляции ({@link Quoter}), те же
 * параметры из того же конфига — иначе сверять предсказание с фактом
 * бессмысленно.
 *
 * Цель этапа — НЕ прибыль, а одно число: доля исполнений, которые модель
 * предсказала (док. 91 §3). Предсказание для нашего размера и периода опроса
 * посчитано заранее: **104 исполнения в сутки** и **6.0% исполнений с
 * отрицательным захватом**. Поэтому размеры минимальные, а весь риск ограничен
 * пределами из {@link ExecLimits}.
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
 * 5. **Проверять надо {@code available}, а не {@code total}.** Средства под
 *    стоящей заявкой в позиции видны, а поставить на них нельзя — см.
 *    {@link #affordable}.
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
    }

    public record Stats(long placements, long replaces, long cancels, long fills,
                        double inventory, double lastFair, String state, String pausedReason,
                        long ticks, long ticksAtCap, long partials, int partialsNow) {
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
    /** Валюта котировки: касса тоже общая на трёх ботов и тоже делится реестром. */
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
        }
    }

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
     * Ведро постановок — общее состояние трёх ботов, и истории оно не хранит:
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
                ? Math.max(0, alloc.own(tag.id(), quote)) : Double.MAX_VALUE;
        double sellPool = Math.max(0, inventory);
        for (var r : current) {
            Resting slot = r.side() == Side.BUY ? bids.get(r.level()) : asks.get(r.level());
            var want = findWanted(desired, r.side(), r.level());
            var action = findAction(plan, r.side(), r.level());
            double pool = r.side() == Side.BUY
                    ? (want != null && want.price() > 0 ? buyCash / want.price() : 0)
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
        if (want <= 0) {
            return null;
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
        if (have + 1e-9 < Math.max(oneLot, minNotional)) {
            return String.format(java.util.Locale.ROOT,
                    "Не хватает своей кассы: за ботом числится %.2f %s, на одну заявку "
                            + "нужно %.2f.",
                    have, quote, Math.max(oneLot, minNotional));
        }
        double need = Math.max(0, (params.inventoryCap() - inventory) * price);
        if (need <= 0) {
            return null;                  // инвентарь уже у потолка, покупать не на что
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
                partials, partialsNow);
    }

    @Override
    public void run() {
        restorePosition();
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
        Quoter.Quotes target = policy.quotes(fair.price(), inventory, drift);
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

        // ⚠️ Пул РАЗДЕЛЯЕТСЯ между уровнями, и внутренние забирают первыми.
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
                ? Math.max(0, alloc.own(tag.id(), quote)) : Double.MAX_VALUE;
        double sellPool = Math.max(0, inventory);
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
        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            Double bidPrice = onTick(Side.BUY, noCross(Side.BUY,
                    levelPrice(Side.BUY, target.bid(), fair.price(), i), fair));
            Double askPrice = onTick(Side.SELL, noCross(Side.SELL,
                    levelPrice(Side.SELL, target.ask(), fair.price(), i), fair));

            double cashCap = bidPrice != null && bidPrice > 0
                    ? buyCash / bidPrice : Double.MAX_VALUE;
            double bought = syncSide(Side.BUY, i, bids.get(i), bidPrice, fair.price(), cashCap);
            buyCash = Math.max(0, buyCash - bought * (bidPrice == null ? 0 : bidPrice));

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
        double shift = level * levelStep * fair;
        return side == Side.BUY ? base - shift : base + shift;
    }

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
        double ownPositionCap = side == Side.SELL
                ? Math.max(0, inventory)
                : Math.max(0, params.inventoryCap() - inventory);
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
            ownCashCap = Math.max(0, alloc.own(tag.id(), quote)) / price;
        }
        return Math.min(want, Math.min(Math.min(affordable, ownPositionCap), ownCashCap));
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
                        side == Side.BUY ? "buy" : "sell", fmt(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        Venue.Response response = client.place(body);
        placements++;
        if (response.ok()) {
            resting.venueId = extract(response.body());
            rememberLevel(side, resting);
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
        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(tag.newClientOrderId(), fmt(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        Venue.Response response = client.replace(resting.venueId, body);
        replaces++;
        replacesThisMinute++;
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
            if (oldId != null && !oldId.equals(resting.venueId)) {
                inspectGoneOrder(side, oldId);
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
            resolveNotNew(side, resting, response.body());
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
    private void resolveNotNew(Side side, Resting resting, String body) {
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
            return;
        }
        String id = resting.venueId;
        Venue.Response order = client.order(id);
        if (!order.ok() || order.body() == null) {
            return;                       // не знаем — оставляем всё как было
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
    }

    /**
     * Что случилось с исчезнувшей заявкой. Ответ площадки содержит фактическую
     * цену исполнения, объём и комиссию — всё то, что иначе пришлось бы выводить
     * из разницы остатков, теряя точность и путаясь при нескольких исполнениях
     * подряд.
     *
     * Здесь же срабатывает предохранитель по комиссии: конструкция измерялась
     * при maker 0%, и появление любой ненулевой комиссии означает конец промо —
     * то есть смену экономики, а не параметра. Решение принимает человек.
     */
    private String inspectGoneOrder(Side side, String venueId) {
        Venue.Response order = client.order(venueId);
        if (!order.ok() || order.body() == null) {
            fills++;                      // судьбу не выяснили, но заявки нет
            return null;
        }
        return book(side, venueId, order.body());
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
        double filled = total - bookedByOrder.getOrDefault(venueId, 0.0);
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
                    + "Вся конструкция считалась при maker 0%%; отмена промо — это смена "
                    + "экономики, а не параметра. Котирование выключено, заявки сняты.")
                    .formatted(fmt(fee), feeCurrency == null ? "" : feeCurrency, venueId);
            log.error(message);
            journal.event("fee_detected", message);
            alert.accept(message);
            stopQuoting();
        }
        return status;
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
        if (!response.ok()) {
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
                baseAvailable = available;    // поставить можно только на это
                if (!ownPosition) {
                    inventory = total;        // одинокий бот: вся позиция наша
                }
            } else if ("USDC".equals(matcher.group(1))) {
                quoteBalance = available;     // на что можно поставить новую заявку
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
        String message = ("РАСХОЖДЕНИЕ: своя позиция %s больше остатка аккаунта %s уже "
                + "%d с. Либо потеряно исполнение, либо позицию тронули извне.")
                .formatted(fmt(inventory), fmt(baseTotal), (now - mismatchSinceMs) / 1000);
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
        AllocRegistry.Free fb = alloc.free(base, baseTotal(), now);
        AllocRegistry.Free fq = alloc.free(quote, quoteTotal, now);
        double myBase = alloc.own(tag.id(), base);
        double myQuote = alloc.own(tag.id(), quote);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT,
                "СЧЁТ%n  %s: %.8f = %.1f лота%n  %s: %.2f%n%n",
                base, fb.venueTotal(), lot > 0 ? fb.venueTotal() / lot : 0,
                quote, fq.venueTotal()));

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
        refreshBalances();
        double qty = lots * params.size();
        long now = clock.now();

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
        if (!alloc.claim(tag.id(), base, qty, baseTotal(), price, now)) {
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
        journal.putState(STATE_CASH, ownCash);
        journal.putState(STATE_SEED_CASH, seedCash);
        if (qty > 0) {
            journal.fill(null, "BUY", qty, price, price, 0, null, "handover");
        }
        journal.event("claim", String.format(java.util.Locale.ROOT,
                "%.1f лота = %.8f %s по %.2f, плюс %.2f %s",
                lots, qty, base, price, takeQuote, quote));
        return String.format(java.util.Locale.ROOT,
                "Взято %.1f лота = %.8f %s по справедливой %.2f и %.2f %s.%n"
                        + "Записано передачей (status=handover), в статистику сделок не идёт.%n%n%s",
                lots, qty, base, price, takeQuote, quote, describeFree());
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
        return String.format(java.util.Locale.ROOT,
                "Освобождено %.8f %s и %.2f %s по %.2f, закрыто по переоценке.%n%n%s",
                qty, base, cash, quote, price, describeFree());
    }

    /** Остаток счёта по базовой валюте — знаменатель для реестра. */
    private double baseTotal() {
        double reserved = 0;
        for (Resting r : asks) {
            reserved += r.venueId != null ? r.size : 0;
        }
        return baseAvailable + reserved;
    }

    private void rollCounters() {
        long now = clock.now();
        if (now - minuteStartMs >= 60_000) {
            minuteStartMs = now;
            replacesThisMinute = 0;
            // Аренда продлевается, пока ЖИВ ПРОЦЕСС, а не пока идёт котирование:
            // /stop на час претензию терять не должен, а убитый процесс — должен.
            if (alloc != null) {
                alloc.heartbeat(tag.id(), now);
            }
            // Остатки перечитываются раз в минуту: исполнение могло случиться молча.
            refreshBalances();
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
    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
