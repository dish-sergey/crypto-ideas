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

    /** Потолок паузы после отказа постановки. */
    private static final long MAX_PLACE_BACKOFF_MS = 60_000L;
    /**
     * Сколько заявка считается «свежей». Список активных отстаёт от постановки,
     * и вывод «её там нет, значит исполнилась» на свежем id даёт дубль.
     */
    private static final long ADOPT_GRACE_MS = 5_000L;
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
    }

    public record Stats(long placements, long replaces, long cancels, long fills,
                        double inventory, double lastFair, String state, String pausedReason) {
    }

    private final TradeClient client;
    private final StandReader stand;
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
    private volatile long mismatchSinceMs;
    private volatile long mismatchWarnedMs;

    private final AtomicBoolean quoting = new AtomicBoolean(false);
    private volatile boolean alive = true;

    private final Resting bid = new Resting();
    private final Resting ask = new Resting();

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
    private final java.util.ArrayDeque<long[]> fairHistory = new java.util.ArrayDeque<>();
    private final org.home.data.revx.sim.EfficiencyRatio efficiency;
    private volatile String pausedReason = "не запущен";
    private long placements;
    private long replaces;
    private long cancels;
    private long fills;
    private long minuteStartMs = System.currentTimeMillis();
    private long lastReconcileMs = System.currentTimeMillis();
    private int replacesThisMinute;
    private double totalFees;
    private double totalFilledNotional;
    private double startInventory;
    private double startQuote;
    private boolean startCaptured;
    private java.util.function.Consumer<String> alert = message -> { };

    public QuoteLoop(TradeClient client, StandReader stand, ExecJournal journal,
                     Quoter.Params params, String symbol, long periodMs, double minNotional,
                     BotTag tag, org.home.data.revx.sim.QuotePolicy policy,
                     boolean ownPosition, double positionSeed, double baseStep,
                     double parkDistance) {
        this.client = client;
        this.stand = stand;
        this.journal = journal;
        this.params = params;
        this.quoter = new Quoter(params);
        this.efficiency = new org.home.data.revx.sim.EfficiencyRatio(
                params.erWindowMs() > 0 ? params.erWindowMs() : 86_400_000L,
                params.erSampleMs() > 0 ? params.erSampleMs() : 3_600_000L);
        this.symbol = symbol;
        this.base = symbol.substring(0, symbol.indexOf('/'));
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

    public void startQuoting() {
        if (quoting.compareAndSet(false, true)) {
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

    public void shutdown() {
        alive = false;
        // Сначала снимаем флаг: сверка при включённом котировании усыновила бы
        // заявки обратно вместо того, чтобы их снять.
        quoting.set(false);
        cancelAll("выключение процесса");
        reconcile("выключение процесса");
    }

    /** Метка бота: суточный лимит постановок у каждого свой (см. {@link ExecLimits}). */
    public String botId() {
        return tag.id();
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
        return new Stats(placements, replaces, cancels, fills, inventory, lastFair,
                quoting.get() ? "котирует" : "остановлен", pausedReason);
    }

    @Override
    public void run() {
        restorePosition();
        refreshBalances();
        // Стартуем остановленными, значит и книга должна быть пуста: заявки
        // переживают наш процесс, и оставшиеся после падения — уже не наши.
        reconcile("старт");
        while (alive) {
            long started = System.currentTimeMillis();
            try {
                tick();
            } catch (Exception e) {
                // Цикл не должен умирать от единичной ошибки: заявки останутся
                // висеть, а следующий тик их подхватит. Но молчать нельзя.
                log.error("тик упал: {}", e.toString());
                journal.event("tick_error", e.toString());
            }
            long sleep = periodMs - (System.currentTimeMillis() - started);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void tick() {
        rollCounters();
        StandReader.Fair fair = stand.latest(base, 30_000);
        lastFair = fair.price();
        rememberFair(fair.price());
        if (fair.price() > 0) {
            efficiency.accept(System.currentTimeMillis(), fair.price());
        }

        if (!quoting.get()) {
            pausedReason = "не запущен";
            return;
        }
        if (!fair.quotable() || !(fair.price() > 0)) {
            // Гейт ТЗ §4.1: опора сломана — уводим котировки из зоны исполнения.
            pausedReason = fair.pausedReason() == null ? "курс ненадёжен" : fair.pausedReason();
            journal.quote(fair.price(), null, null, inventory, false, pausedReason);
            standAside(pausedReason);
            return;
        }
        long staleMs = System.currentTimeMillis() - fair.asOfMs();
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
        // Пишется КАЖДЫЙ тик: без справедливой цены в момент исполнения захват
        // потом не восстановить, а именно он и сравнивается с моделью.
        journal.quote(fair.price(), target.bid(), target.ask(), inventory, true, null);
        syncSide(Side.BUY, bid, noCross(Side.BUY, target.bid(), fair), fair.price());
        syncSide(Side.SELL, ask, noCross(Side.SELL, target.ask(), fair), fair.price());
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

    private void syncSide(Side side, Resting resting, Double targetPrice, double fair) {
        if (targetPrice == null) {
            if (resting.venueId != null) {
                cancel(side, resting, "сторона не котируется");
            }
            return;
        }
        double size = sizeFor(side, targetPrice, resting);
        double notional = size * targetPrice;
        // Ниже минимума площадки заявка не встанет, а попытка потратит суточный
        // лимит постановок. Остаток от частичного исполнения бывает мельче
        // минимума (5.5e-7 BTC = 0.04 USDC при пороге 0.1) — это не повод стучаться.
        if (size <= 0 || notional < minNotional) {
            if (resting.venueId != null) {
                cancel(side, resting, "нечем котировать эту сторону");
            }
            warnNoFunds(side, resting);
            return;
        }
        if (!ExecLimits.orderAllowed(notional)) {
            log.error("заявка {} на {} USDC превышает предел {} — не ставлю", side, notional,
                    ExecLimits.MAX_ORDER_NOTIONAL_USDC);
            journal.event("limit_blocked", side + " нотионал " + notional);
            return;
        }
        if (!ExecLimits.exposureAllowed(exposure() + notional)) {
            log.error("экспозиция превысила бы предел {} — не ставлю",
                    ExecLimits.MAX_TOTAL_EXPOSURE_USDC);
            journal.event("limit_blocked", "экспозиция");
            return;
        }

        // Пауза после отказа площадки распространяется на ОБА действия. Отказ на
        // замене стоит четырёх запросов, и повторять его каждую секунду так же
        // вредно, как долбиться постановкой.
        if (System.currentTimeMillis() < resting.blockedUntilMs) {
            return;
        }
        if (resting.venueId == null) {
            place(side, resting, targetPrice, size);
        } else if (quoter.shouldRequote(resting.price, targetPrice)) {
            replace(side, resting, targetPrice, size);
        }
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
        double ownPositionCap = side == Side.SELL ? Math.max(0, inventory) : Double.MAX_VALUE;
        return Math.min(want, Math.min(affordable, ownPositionCap));
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
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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
        long since = System.currentTimeMillis() - window;
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
        return inventory * lastFair
                + (bid.venueId != null ? bid.price * bid.size : 0)
                + (ask.venueId != null ? ask.price * ask.size : 0);
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
     */
    private long placementsLastDay() {
        return journal.placementsSince(System.currentTimeMillis() - 86_400_000L);
    }

    private void place(Side side, Resting resting, double price, double size) {
        long used = placementsLastDay();
        if (used >= ExecLimits.maxPlacementsPerDay(tag.id())) {
            log.error("исчерпан суточный лимит постановок ({} из {} за 24 ч) — "
                            + "останавливаю котирование",
                    used, ExecLimits.maxPlacementsPerDay(tag.id()));
            journal.event("limit_blocked",
                    "постановки за сутки: " + used + " из "
                            + ExecLimits.maxPlacementsPerDay(tag.id()));
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
        TradeClient.Response response = client.place(body);
        placements++;
        if (response.ok()) {
            resting.venueId = extract(response.body());
            resting.price = price;
            resting.size = size;
            resting.sinceMs = System.currentTimeMillis();
            resting.failures = 0;
            resting.blockedUntilMs = 0;
        } else {
            // Отказ на постановке тратит суточный лимит и ничего не даёт. Пауза
            // растёт с каждым отказом подряд: даже неизвестная причина не должна
            // успевать съесть тысячу постановок, как в ночь на 29.08.2026.
            resting.failures++;
            long pause = Math.min(MAX_PLACE_BACKOFF_MS, 5_000L << Math.min(4, resting.failures - 1));
            resting.blockedUntilMs = System.currentTimeMillis() + pause;
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
        if (replacesThisMinute >= ExecLimits.MAX_REPLACES_PER_MINUTE) {
            return;                       // защита от зацикливания; молча, но с паузой
        }
        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(tag.newClientOrderId(), fmt(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        TradeClient.Response response = client.replace(resting.venueId, body);
        replaces++;
        replacesThisMinute++;
        if (response.ok()) {
            // Замена создаёт ДРУГУЮ заявку: новый id обязателен к перечитыванию.
            String newId = extract(response.body());
            resting.venueId = newId != null ? newId : resting.venueId;
            resting.price = price;
            resting.size = size;
            resting.sinceMs = System.currentTimeMillis();
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
            // Пауза на сторону: без неё каждый отказ тянет за собой четыре запроса
            // (замена, статус, остатки, активные), и на устойчивом отказе это
            // 8 запросов в секунду по кругу — наблюдалось 01.09.2026.
            resting.blockedUntilMs = System.currentTimeMillis()
                    + Math.min(MAX_PLACE_BACKOFF_MS, 2_000L << Math.min(5, resting.failures - 1));
            reconcile("отказ замены");
            refreshBalances();
        }
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
        TradeClient.Response active = client.activeOrders();
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
            // Правило 1: пока котирование выключено, наших заявок в книге быть
            // не должно. Ни одной, независимо от того, что мы о них помним.
            for (ActiveOrder order : orders) {
                cancelStray(order, why);
            }
            bid.venueId = null;
            ask.venueId = null;
            return;
        }
        adopt(Side.BUY, bid, orders, why);
        adopt(Side.SELL, ask, orders, why);
    }

    private void adopt(Side side, Resting resting, java.util.List<ActiveOrder> orders, String why) {
        java.util.List<ActiveOrder> mine = orders.stream()
                .filter(o -> o.side() == side)
                .sorted(java.util.Comparator.comparingLong(ActiveOrder::createdMs).reversed())
                .toList();
        if (mine.isEmpty()) {
            boolean fresh = System.currentTimeMillis() - resting.sinceMs < ADOPT_GRACE_MS;
            if (resting.venueId != null && !fresh) {
                inspectGoneOrder(side, resting.venueId);
                resting.venueId = null;
                refreshBalances();
            }
            return;
        }
        ActiveOrder keep = mine.stream()
                .filter(o -> o.id().equals(resting.venueId))
                .findFirst()
                .orElse(mine.get(0));
        if (!keep.id().equals(resting.venueId)) {
            log.warn("усыновляю заявку {} {} по {} ({})", side, keep.id(), keep.price(), why);
            journal.event("adopt", side + " " + keep.id() + " по " + fmt(keep.price())
                    + " (" + why + ")");
            resting.venueId = keep.id();
            resting.price = keep.price();
            resting.size = keep.size();
            resting.sinceMs = System.currentTimeMillis();
            // Наследник найден — значит предыдущий отказ был мнимым, и держать
            // за него паузу не за что.
            resting.failures = 0;
            resting.blockedUntilMs = 0;
        }
        for (ActiveOrder extra : mine) {
            if (!extra.id().equals(keep.id())) {
                cancelStray(extra, why);
            }
        }
    }

    private void cancelStray(ActiveOrder order, String why) {
        TradeClient.Response response = client.cancel(order.id());
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
    private void inspectGoneOrder(Side side, String venueId) {
        TradeClient.Response order = client.order(venueId);
        if (!order.ok() || order.body() == null) {
            fills++;                      // судьбу не выяснили, но заявки нет
            return;
        }
        String status = field(order.body(), "status");
        double filled = number(order.body(), "filled_quantity");
        double price = number(order.body(), "average_fill_price");
        double fee = number(order.body(), "total_fee");
        String feeCurrency = field(order.body(), "fee_currency");

        if (filled > 0) {
            fills++;
            totalFilledNotional += filled * price;
            journal.fill(venueId, side.name(), filled, price, lastFair, fee, feeCurrency, status);
            // Своя позиция и касса меняются ЗДЕСЬ, а не по остаткам аккаунта:
            // при двух ботах остатки содержат чужие сделки.
            applyFill(side, filled, price);
            // Политике, чьи цены зависят от собственных сделок (пол по
            // себестоимости), факт исполнения нужен раньше следующего тика.
            policy.onFill(new org.home.data.revx.sim.Fill(
                    System.currentTimeMillis(), side, price, filled, lastFair));
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
        if (!startCaptured || !(lastFair > 0)) {
            return;
        }
        // При своей позиции касса тоже своя: разница остатков аккаунта содержит
        // сделки соседнего бота и торговым результатом этого бота не является.
        double pnl = ownPosition
                ? tradingPnl(ownCash, 0, inventory, seedPosition, lastFair)
                : tradingPnl(quoteTotal, startQuote, inventory, startInventory, lastFair);
        if (pnl < -ExecLimits.MAX_TRADING_LOSS_USDC) {
            String message = ("ОСТАНОВКА: торговый убыток %s USDC против buy & hold превысил "
                    + "предел %s. Котирование выключено, заявки сняты.")
                    .formatted(fmt(pnl), fmt(-ExecLimits.MAX_TRADING_LOSS_USDC));
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
        String dead = resting.venueId;
        // Обнуляем ДО выяснения: предохранитель по комиссии внутри может позвать
        // остановку, а та — снова сюда, и рекурсия должна упереться в этот null.
        resting.venueId = null;
        TradeClient.Response response = client.cancel(dead);
        cancels++;
        journal.event("cancel", side + " " + dead + " (" + why + ") → " + response.status());
        if (!response.ok()) {
            log.info("отмена {} не прошла ({}), выясняю судьбу заявки", side, response.status());
            inspectGoneOrder(side, dead);
            refreshBalances();
        }
    }

    private void cancelAll(String why) {
        cancel(Side.BUY, bid, why);
        cancel(Side.SELL, ask, why);
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
        parkSide(Side.BUY, bid, lastTrustedFair * (1 - parkDistance), why);
        parkSide(Side.SELL, ask, lastTrustedFair * (1 + parkDistance), why);
    }

    private void parkSide(Side side, Resting resting, double price, String why) {
        if (resting.venueId == null) {
            return;                       // отводить нечего
        }
        if (!quoter.shouldRequote(resting.price, price)) {
            return;                       // уже отведена
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
        TradeClient.Response response = client.balances();
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
        long now = System.currentTimeMillis();
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
            log.warn("позиция восстановлена из журнала: {} {}, касса {}",
                    fmt(inventory), base, fmt(ownCash));
            return;
        }
        if (positionSeed >= 0) {
            inventory = positionSeed;
        } else {
            // Затравка «из аккаунта»: осмысленна только пока бот один.
            TradeClient.Response response = client.balances();
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
    }

    private void rollCounters() {
        long now = System.currentTimeMillis();
        if (now - minuteStartMs >= 60_000) {
            minuteStartMs = now;
            replacesThisMinute = 0;
            // Остатки перечитываются раз в минуту: исполнение могло случиться молча.
            refreshBalances();
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
