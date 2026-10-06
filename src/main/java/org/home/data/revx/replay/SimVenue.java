package org.home.data.revx.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.exec.Clock;
import org.home.data.revx.exec.Venue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Площадка стенда: вторая реализация {@link Venue}.
 *
 * <h2>Что здесь и чего здесь нет</h2>
 *
 * Здесь — весь учёт: заявки, остатки, резервы под стоящими заявками, семантика
 * замены (новый идентификатор), 422 на мёртвый идентификатор, {@code
 * filled_quantity} по исполнившейся заявке. Всё это проверено повтором живого
 * журнала: при верных исполнениях котировки сходятся с живым на 99.92%
 * (61 542 тика из 61 592, бот A, 17 часов).
 *
 * Здесь НЕТ решения, исполнилась ли заявка. Это единственная неизвестная, и она
 * вынесена в {@link FillModel} — чтобы любое расхождение стенда с реальностью
 * относилось к ней одной, а не размазывалось по десятку подозреваемых.
 *
 * <h2>Почему обмен строками JSON</h2>
 *
 * Чтобы стенд гонял ТОТ ЖЕ разбор, что и живой бот. Самые дорогие ошибки жили
 * именно в разборе: имя поля с идентификатором различается между ответами
 * ({@code venue_order_id} против {@code id}), замена возвращает новый
 * идентификатор, 422 не означает, что замены не было. Отдай мы типизированные
 * объекты — проверяли бы не бота, а свою модель бота.
 */
public final class SimVenue implements Venue, org.home.data.revx.exec.StallFeed {

    /**
     * Сдвиг минуты затыков посреди прогона: с момента {@code revx.sim.ghost-shift-at}
     * (ISO) окно встаёт на {@code revx.sim.ghost-at-sec-after}. Проверка того, что
     * боты замечают переезд.
     */
    private final long ghostShiftAtMs = System.getProperty("revx.sim.ghost-shift-at") == null ? Long.MAX_VALUE
            : java.time.Instant.parse(System.getProperty("revx.sim.ghost-shift-at")).toEpochMilli();
    private final int ghostAtSecAfter = Integer.getInteger("revx.sim.ghost-at-sec-after", -1);
    /** Стенд как зонд: {@code revx.sim.stall-feed=true} — боты видят расписание затыков. */
    private final boolean stallFeed = Boolean.getBoolean("revx.sim.stall-feed");
    private final long startedMs;

    private int ghostSecAt(long nowMs) {
        return nowMs >= ghostShiftAtMs ? ghostAtSecAfter : ghostAtSec;
    }

    @Override
    public synchronized List<Long> stallTimes(long sinceMs) {
        List<Long> out = new ArrayList<>();
        long now = clock.now();
        for (long h = Math.max(sinceMs, startedMs) / 3_600_000L * 3_600_000L; h <= now; h += 3_600_000L) {
            int sec = ghostSecAt(h);
            long t = h + sec * 1000L + 500;
            if (sec >= 0 && t >= sinceMs && t <= now && t >= startedMs) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public long observedMs(long nowMs) {
        return stallFeed ? Math.max(0, nowMs - startedMs + 72 * 3_600_000L) : 0;
    }

    // ⚠️ Все точки входа synchronized: котировщиков в прогнозе несколько, каждый
    // в своём потоке, а книга заявок и остатки здесь ОДНИ. Без этого гонка за
    // объёмом сделки делала прогон невоспроизводимым (см. SimClock: очередь хода).

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Order {
        String id;
        String clientId;
        String symbol;
        boolean buy;
        double price;
        double size;
        long createdMs;
    }

    private final Clock clock;
    private final FillModel model;
    private final String base;
    private final String quote;

    private final Map<String, Order> live = new LinkedHashMap<>();
    /** Что по какой заявке исполнилось: {объём, оборот}. Источник filled_quantity. */
    private final Map<String, double[]> done = new LinkedHashMap<>();
    private double baseTotal;
    private double quoteTotal;

    private long placements;
    private long replaces;
    private long cancels;
    private long applied;
    private long replaceRejects;
    /** Объём, реально исполненный площадкой, и объём, о котором бот не узнал. */
    private double filledBase;
    private long filledOrders;
    private double unreadBase;
    private long unreadOrders;
    /** По каким заявкам бот прочитал {@code filled_quantity}. */
    private final java.util.Set<String> inspected = new java.util.HashSet<>();
    /** Как заявка ушла из книги: исполнением, заменой или отменой. */
    private final Map<String, String> gone = new LinkedHashMap<>();
    private final double minNotional;
    /** Сколько раз площадку спросили и сколько из них заявка на стороне СТОЯЛА. */
    private long probes;
    private long bidPresent;
    private long askPresent;

    public String presence() {
        return probes == 0 ? "нет данных"
                : String.format(java.util.Locale.ROOT, "бид в книге %.1f%%, аск %.1f%%",
                        100.0 * bidPresent / probes, 100.0 * askPresent / probes);
    }

    public SimVenue(Clock clock, FillModel model, String symbol,
                    double baseStart, double quoteStart) {
        this(clock, model, symbol, baseStart, quoteStart, 0);
    }

    /**
     * @param minNotional минимальная заявка площадки в котируемой валюте
     *                    (Revolut X: 0.1 USDC). Остаток частичного исполнения
     *                    мельче этого в книге не живёт — см. {@link #apply}
     */
    public SimVenue(Clock clock, FillModel model, String symbol,
                    double baseStart, double quoteStart, double minNotional) {
        this.clock = clock;
        this.model = model;
        this.base = symbol.substring(0, symbol.indexOf('/'));
        this.quote = symbol.substring(symbol.indexOf('/') + 1);
        this.baseTotal = baseStart;
        this.quoteTotal = quoteStart;
        this.minNotional = minNotional;
        this.startedMs = clock.now();
    }

    // ------------------------------------------------ режим «призрак замены»

    /**
     * 🔑 ПРИЗРАК ЗАМЕНЫ (ЧИТАТЕЛЬ-ПЛОЩАДКИ.md §1): в затык площадка внутри одного
     * {@code PUT} снимает предка, наследника НЕ создаёт, отвечает 422 через ~3 с,
     * а резерв предка держит часами. До 28.09.2026 стенд этого не умел вовсе —
     * его 422 всегда был «чистым», и логику запертого проверить было негде.
     *
     * Окно задаётся секундой часа и шириной ({@code -Drevx.sim.ghost-at-sec=2309}
     * — это HH:38:29 — и {@code -Drevx.sim.ghost-width-sec=3}), сколько держится
     * резерв — {@code -Drevx.sim.ghost-hold-min}. По умолчанию выключено.
     */
    private final int ghostAtSec = Integer.getInteger("revx.sim.ghost-at-sec", -1);
    private final int ghostWidthSec = Integer.getInteger("revx.sim.ghost-width-sec", 3);
    private final long ghostHoldMs = Long.getLong("revx.sim.ghost-hold-min", 120L) * 60_000L;
    /** Ответ на замену в затык: столько думает площадка. */
    static final long GHOST_LATENCY_MS = 3_000L;

    private record GhostLock(boolean buy, double amount, long untilMs) {
    }

    private final List<GhostLock> ghostLocks = new ArrayList<>();
    private long ghostsMade;

    public long ghostsMade() {
        return ghostsMade;
    }

    /**
     * Прочие повадки площадки в затык (ЧИТАТЕЛЬ-ПЛОЩАДКИ.md §1, прогон 22–23.09):
     * {@code late204} — отмена отвечает 204, а заявка ещё живёт и исполняется
     * (b: сделка через 4 с после 204); {@code live404} — отмена отвечает 404 «нет
     * такой», а заявка жива (f: исполнилась через 3 с); {@code stale} — вопрос о
     * судьбе ушедшей заявки отвечает 404. Набор — {@code -Drevx.sim.stall-effects}
     * (по умолчанию только {@code ghost}), срок — {@code -Drevx.sim.stall-late-ms}.
     */
    private final java.util.Set<String> stallEffects = java.util.Set.of(
            System.getProperty("revx.sim.stall-effects", "ghost").split(","));
    private final long stallLateMs = Long.getLong("revx.sim.stall-late-ms", 4_000L);
    /** Отменённые «на словах»: в списке активных их нет, а исполниться могут до срока. */
    private final Map<String, Long> hiddenUntil = new LinkedHashMap<>();
    private long late204Made;
    private long live404Made;
    private long staleMade;
    /** Исполнения по заявкам, которые бот уже считал отменёнными. */
    private long fillsAfterCancel;

    /** Сводка несогласованностей за прогон — в отчёт. */
    public synchronized String stallDiag() {
        return String.format(Locale.ROOT, "призраков %d, 204-с-жизнью %d, 404-на-живой %d, "
                        + "404-о-судьбе %d, исполнено после «отмены» %d; затыков по ходу цены %d, "
                        + "гонок замены %d, невидимых свежих %d",
                ghostsMade, late204Made, live404Made, staleMade, fillsAfterCancel,
                volStalls, raceMade, fresh404Made);
    }

    private boolean effect(String name) {
        return stallEffects.contains(name) && inGhostWindow(clock.now());
    }

    /**
     * ЗАТЫК ПО ВОЛАТИЛЬНОСТИ (03.10.2026, разбор 02.10 18:39). Живьём самые дорогие
     * затыки совпадают с обвалом: XRP −2% за минуту, и площадка в ту же секунду
     * отвечает на замены по 1.8 с. Окно затыка открывается, когда середина книги
     * за {@code WIN} секунд прошла ≥ {@code BP} б.п., и держится {@code DUR} секунд:
     * {@code -Drevx.sim.vol-stall=BP,WIN,DUR}. Внутри окна действуют те же эффекты,
     * что в окне по расписанию, плюс два новых (включаются в {@code stall-effects}):
     * {@code race} — замена отвечает через 1.8 с, и старая заявка ещё живёт и может
     * исполниться (вместе с наследником — двойная покупка); {@code fresh404} — свежая
     * заявка 6 с невидима: запрос, замена и отмена отвечают 404, а исполниться она может.
     */
    private final double[] volStall = parseVolStall(System.getProperty("revx.sim.vol-stall", ""));
    private final java.util.ArrayDeque<double[]> volMids = new java.util.ArrayDeque<>();
    private long volStallUntil;
    private long volStalls;
    private long raceMade;
    private long stallReplaces;
    private long fresh404Made;
    static final long RACE_MS = Long.getLong("revx.sim.race-ms", 1_800L);
    /** В затыке каждая N-я замена — призрак, остальные — гонка (живьём 02.10: 3–4 призрака в сутки на бота). */
    static final int GHOST_EVERY = Integer.getInteger("revx.sim.ghost-every", 3);
    static final long FRESH_MS = Long.getLong("revx.sim.fresh404-ms", 6_000L);
    private final Map<String, Long> freshUntil = new LinkedHashMap<>();

    private static double[] parseVolStall(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] p = s.split(",");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }

    /** Следит за серединой книги и открывает окно затыка на резком ходе. */
    private void watchVolatility() {
        if (volStall == null || !(model instanceof MarketFillModel mfm)) {
            return;
        }
        long now = clock.now();
        org.home.data.revx.sim.BookView b = mfm.market().bookAt(now);
        if (b == null || b.empty()) {
            return;
        }
        double mid = (b.bestBid() + b.bestAsk()) / 2;
        if (volMids.isEmpty() || now - (long) volMids.peekLast()[0] >= 1_000) {
            volMids.addLast(new double[]{now, mid});
        }
        while (now - (long) volMids.peekFirst()[0] > volStall[1] * 1000) {
            volMids.pollFirst();
        }
        double mx = 0;
        double mn = Double.MAX_VALUE;
        for (double[] v : volMids) {
            mx = Math.max(mx, v[1]);
            mn = Math.min(mn, v[1]);
        }
        if (mn > 0 && (mx / mn - 1) * 1e4 >= volStall[0]) {
            if (now >= volStallUntil) {
                volStalls++;
            }
            volStallUntil = now + (long) (volStall[2] * 1000);
        }
    }

    /**
     * РЕАЛЬНОЕ РАСПИСАНИЕ ЗАТЫКОВ (03.10.2026, идея владельца): вместо придуманных
     * окон — моменты, когда площадка живьём отвечала на замены, постановки и отмены
     * дольше секунды (журналы всех шести ботов, слияние с запасом 5 с). Файл
     * {@code revx.sim.stall-schedule}: строки «начало_мс конец_мс». Внутри окна
     * действуют эффекты затыка, включая race и fresh404.
     */
    private final long[][] schedule = loadSchedule(System.getProperty("revx.sim.stall-schedule", ""));

    private static long[][] loadSchedule(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            List<long[]> w = new ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 2) {
                    w.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])});
                }
            }
            w.sort(java.util.Comparator.comparingLong(x -> x[0]));
            return w.toArray(new long[0][]);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("не прочитать расписание затыков " + path, e);
        }
    }

    private boolean inSchedule(long now) {
        if (schedule == null) {
            return false;
        }
        int lo = 0;
        int hi = schedule.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (schedule[mid][1] < now) {
                lo = mid + 1;
            } else if (schedule[mid][0] > now) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean inVolStall() {
        return clock.now() < volStallUntil || inSchedule(clock.now());
    }

    private boolean fresh(String id) {
        Long until = freshUntil.get(id);
        if (until == null) {
            return false;
        }
        if (until <= clock.now()) {
            freshUntil.remove(id);
            return false;
        }
        return true;
    }

    boolean inGhostWindow(long nowMs) {
        if (nowMs < volStallUntil || inSchedule(nowMs)) {
            return true;
        }
        int at = ghostSecAt(nowMs);
        if (at < 0) {
            return false;
        }
        long sec = (nowMs / 1000) % 3600;
        return sec >= at && sec < at + ghostWidthSec;
    }

    /** Резерв, который держат призраки: [монета, касса]. Истёкшие отпускаются. */
    private double[] ghostReserved() {
        long now = clock.now();
        ghostLocks.removeIf(g -> g.untilMs() <= now);
        double b = 0;
        double q = 0;
        for (GhostLock g : ghostLocks) {
            if (g.buy()) {
                q += g.amount();
            } else {
                b += g.amount();
            }
        }
        return new double[]{b, q};
    }

    public long placements() {
        return placements;
    }

    public long replaces() {
        return replaces;
    }

    public long cancels() {
        return cancels;
    }

    /** Замены, отклонённые как «заявки уже нет»: у живого их 1.5% (док. 151). */
    public long replaceRejects() {
        return replaceRejects;
    }

    public long appliedFills() {
        return applied;
    }

    /**
     * Сколько исполненного объёма бот НЕ УВИДЕЛ.
     *
     * Бот узнаёт об исполнении единственным способом — заметив, что заявка ушла
     * из {@code /orders/active}, и прочитав по ней {@code filled_quantity}
     * ({@code QuoteLoop.inspectGoneOrder}). Значит любое исполнение по заявке,
     * которая ушла из книги ИНАЧЕ — заменой или отменой, — до бота не доходит
     * вовсе, и в его P&L, инвентаре и статистике его нет.
     *
     * Число нужно рядом с {@link #appliedFills()}: без него «площадка исполнила
     * 137, бот заметил 52» невозможно разложить на «модель считает исполнения
     * событиями, а бот — заявками» и на настоящую потерю.
     */
    public synchronized String fillDiag() {
        // Считается НА КОНЕЦ прогона, а не в момент ухода заявки из книги: после
        // отказа замены (422) бот ещё сверяется с /orders/active и дочитывает
        // filled_quantity, так что «не увидел» выясняется только в конце.
        unreadBase = 0;
        unreadOrders = 0;
        Map<String, Long> why = new LinkedHashMap<>();
        for (var e : done.entrySet()) {
            if (!inspected.contains(e.getKey())) {
                unreadBase += e.getValue()[0];
                unreadOrders++;
                why.merge(gone.getOrDefault(e.getKey(), "осталась в книге"), 1L, Long::sum);
            }
        }
        return String.format(java.util.Locale.ROOT,
                "исполнено %.8g по %d заявкам; бот не увидел %.8g по %d (%.0f%% объёма); ушли %s",
                filledBase, filledOrders, unreadBase, unreadOrders,
                filledBase > 0 ? 100 * unreadBase / filledBase : 0, why);
    }

    public FillModel model() {
        return model;
    }

    /**
     * Догнать исполнения до текущего момента часов.
     *
     * Вызывается перед КАЖДЫМ ответом: бот узнаёт об исполнении из списка
     * активных и из {@code GET /orders/{id}}, и порядок «сначала событие, потом
     * ответ» обязан совпадать с живым.
     */
    private void advance() {
        watchVolatility();
        probes++;
        for (Order o : live.values()) {
            if (o.buy) {
                bidPresent++;
            } else {
                askPresent++;
            }
        }
        List<FillModel.Resting> resting = new ArrayList<>();
        for (Order o : live.values()) {
            resting.add(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        }
        for (FillModel.Filled f : model.advance(clock.now(), resting)) {
            if (hiddenUntil.containsKey(f.orderId()) && live.containsKey(f.orderId())) {
                fillsAfterCancel++;
            }
            apply(f);
        }
        // Срок «отменённых на словах» вышел — площадка их действительно сняла.
        long now = clock.now();
        var it = hiddenUntil.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!live.containsKey(e.getKey())) {
                it.remove();                                   // успела исполниться
            } else if (e.getValue() <= now) {
                live.remove(e.getKey());
                gone.put(e.getKey(), "отменой (поздно)");
                model.cancelled(e.getKey());
                it.remove();
            }
        }
    }

    /** Сколько уже исполнилось по заявке — то же число, что живая отдаёт в {@code filled_quantity}. */
    private double filledOf(String id) {
        double[] acc = done.get(id);
        return acc == null ? 0 : acc[0];
    }

    private void apply(FillModel.Filled f) {
        Order hit = live.get(f.orderId());
        if (hit == null) {
            return;
        }
        applied++;
        double qty = Math.min(f.qty(), hit.size);
        if (hit.buy) {
            baseTotal += qty;
            quoteTotal -= qty * f.price();
        } else {
            baseTotal -= qty;
            quoteTotal += qty * f.price();
        }
        // ⚠️ Исполнение надо ЗАПОМНИТЬ за заявкой. Бот узнаёт о нём не из
        // остатков, а из GET /orders/{id} по полю filled_quantity: при трёх
        // ботах на счёте остатки содержат чужие сделки, и других источников у
        // него нет. Площадка, отвечающая на исчезнувшую заявку нулём, оставляет
        // бота с нулевым инвентарём навсегда — первый прогон разошёлся с живым
        // ровно здесь, на 383-м тике.
        double[] acc = done.get(hit.id);
        if (acc == null) {
            acc = new double[2];
            done.put(hit.id, acc);
            filledOrders++;
        }
        filledBase += qty;
        acc[0] += qty;
        acc[1] += qty * f.price();
        hit.size -= qty;
        // ⚠️ ОСТАТОК МЕЛЬЧЕ МИНИМАЛЬНОЙ ЗАЯВКИ считается исполнением до конца.
        //
        // Без этого правила стенд терял 40% исполнений — и не в модели, а в
        // учёте. Разбор 08.09.2026 на BTC: заявка стенда 0.00001269, а принт,
        // который её берёт, — ровно 0.00001255, наш же ЖИВОЙ лот, вернувшийся
        // на ленту. Лоты отличаются на 1%, и после исполнения оставалась пыль
        // в 0.00000014 (около одного цента). Заявка с такой пылью оставалась в
        // книге, бот её заменял, а вместе с ней исчезала и запись об
        // исполнении: узнать о сделке он может ТОЛЬКО по исчезнувшей заявке
        // ({@code QuoteLoop.inspectGoneOrder}), и заменённая заявка этот путь
        // обходит. Живьём такого не бывает вовсе — все 346 сделок за сутки
        // ровно в один лот, — то есть пыль была целиком артефактом стенда.
        //
        // Порог — минимальная заявка площадки (0.1 USDC): остаток мельче неё
        // самостоятельной заявкой быть не может.
        if (hit.size <= 1e-12 || hit.size * f.price() < minNotional) {
            live.remove(hit.id);
            gone.put(hit.id, "исполнением");
            model.cancelled(hit.id);
        }
    }

    @Override
    public synchronized Response activeOrders() {
        advance();
        StringBuilder sb = new StringBuilder("{\"data\":[");
        boolean first = true;
        for (Order o : live.values()) {
            if (hiddenUntil.containsKey(o.id) || fresh(o.id)) {
                continue;                     // «отменена» на словах или свежая в затык — в списке её нет
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            // ⚠️ ЧАСТИЧНО ИСПОЛНЕННАЯ ЗАЯВКА НАЗЫВАЕТ СЕБЯ ТАК ЖЕ, КАК ЖИВАЯ.
            //
            // До 09.09.2026 стенд отдавал всем заявкам "status":"new" и не
            // отдавал filled_quantity вовсе. Живая площадка отдаёт
            // "partially_filled" — проверено на журнале бота A, заявка
            // c02363a9 висела в списке активных с filled_quantity 0.00001023
            // из 0.00003765. Разница не косметическая: замена такой заявки
            // живьём получает 422, а в стенде проходила, и стенд считал
            // репрайс возможным там, где его нет.
            double filled = filledOf(o.id);
            sb.append(String.format(Locale.ROOT,
                    "{\"id\":\"%s\",\"client_order_id\":\"%s\",\"symbol\":\"%s\","
                            + "\"side\":\"%s\",\"type\":\"limit\",\"quantity\":\"%s\","
                            + "\"filled_quantity\":\"%s\",\"leaves_quantity\":\"%s\","
                            + "\"price\":\"%s\",\"status\":\"%s\",\"created_date\":%d}",
                    o.id, o.clientId, o.symbol, o.buy ? "buy" : "sell",
                    plain(o.size + filled), plain(filled), plain(o.size), plain(o.price),
                    filled > 1e-12 ? "partially_filled" : "new", o.createdMs));
        }
        return new Response(200, sb.append("]}").toString(), 0);
    }

    @Override
    public synchronized Response balances() {
        advance();
        // ⚠️ Форма обязана совпадать с живой до символа: QuoteLoop разбирает её
        // жёсткой регуляркой без пробелов. available = total минус то, что
        // зарезервировано под нашими же стоящими заявками.
        double baseReserved = 0;
        double quoteReserved = 0;
        for (Order o : live.values()) {
            if (o.buy) {
                quoteReserved += o.size * o.price;
            } else {
                baseReserved += o.size;
            }
        }
        // Призраки держат резерв без видимой заявки — ровно так площадка и выглядит.
        double[] ghost = ghostReserved();
        baseReserved += ghost[0];
        quoteReserved += ghost[1];
        String body = "{\"data\":["
                + balance(base, baseTotal, baseReserved) + ","
                + balance(quote, quoteTotal, quoteReserved) + "]}";
        return new Response(200, body, 0);
    }

    private static String balance(String currency, double total, double reserved) {
        double avail = Math.max(0, total - reserved);
        return String.format(Locale.ROOT,
                "{\"currency\":\"%s\",\"available\":\"%s\",\"reserved\":\"%s\",\"total\":\"%s\"}",
                currency, plain(avail), plain(Math.max(0, reserved)), plain(Math.max(0, total)));
    }

    @Override
    public synchronized Response order(String id) {
        advance();
        if (fresh(id)) {
            fresh404Made++;
            return new Response(404, "{\"message\":\"Order not found\"}", 50);
        }
        if ((!live.containsKey(id) || hiddenUntil.containsKey(id)) && effect("stale")) {
            staleMade++;              // в затык судьба ушедшей заявки ещё не видна
            return new Response(404, "{\"message\":\"Order not found\"}", 50);
        }
        double[] acc = done.get(id);
        if (acc != null) {
            inspected.add(id);
        }
        double filled = acc == null ? 0 : acc[0];
        double avg = filled > 0 ? acc[1] / filled : 0;
        Order o = live.get(id);
        String status = o != null
                ? (filled > 1e-12 ? "partially_filled" : "new")
                : (filled > 0 ? "filled" : "cancelled");
        return new Response(200, String.format(Locale.ROOT,
                "{\"data\":{\"id\":\"%s\",\"status\":\"%s\",\"filled_quantity\":\"%s\","
                        + "\"average_fill_price\":\"%s\",\"total_fee\":\"0\","
                        + "\"fee_currency\":\"%s\",\"price\":\"%s\",\"quantity\":\"%s\"}}",
                id, status, plain(filled), plain(avg), quote,
                plain(o != null ? o.price : avg), plain(o != null ? o.size : filled)), 0);
    }

    @Override
    public synchronized Response place(String json) {
        advance();
        JsonNode n = read(json);
        if (n == null) {
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        JsonNode limit = n.path("order_configuration").path("limit");
        Order o = new Order();
        o.id = UUID.randomUUID().toString();
        o.clientId = n.path("client_order_id").asText(null);
        o.symbol = n.path("symbol").asText(null);
        o.buy = "buy".equalsIgnoreCase(n.path("side").asText(""));
        o.price = limit.path("price").asDouble();
        o.size = limit.path("base_size").asDouble();
        o.createdMs = clock.now();
        if (!json.contains("post_only") && model instanceof MarketFillModel mfm) {
            Response r = takeLiquidity(o, mfm);
            if (r != null) {
                return r;
            }
        }
        live.put(o.id, o);
        if (inVolStall() && effect("fresh404")) {
            freshUntil.put(o.id, clock.now() + FRESH_MS);
        }
        model.placed(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        placements++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    /** Тейкерская комиссия площадки (опубликованный тариф 0.09%). */
    static final double TAKER_FEE = Double.parseDouble(System.getProperty("revx.sim.taker-fee-bp", "9")) / 1e4;
    private long takerOrders;
    private double takerBase;
    private double takerFeeQuote;

    /**
     * ТЕЙКЕР (02.10.2026, экстренная продажа): заявка без post_only, пересекающая
     * книгу, исполняется сразу по видимым уровням не хуже своей цены; остаток
     * снимается (в книгу не встаёт). Комиссия заложена в среднюю цену исполнения —
     * бот учитывает её через {@code price} ответа, а не через {@code total_fee}
     * (ненулевой fee останавливает бота как смену тарифа).
     *
     * @return ответ, если заявка пересекла книгу; null — обычная постановка
     */
    private Response takeLiquidity(Order o, MarketFillModel mfm) {
        org.home.data.revx.sim.BookView book = mfm.market().bookAt(clock.now());
        if (book == null) {
            return null;
        }
        List<org.home.data.revx.sim.BookView.Level> side = o.buy ? book.asks() : book.bids();
        if (side.isEmpty() || (o.buy ? side.get(0).price() > o.price : side.get(0).price() < o.price)) {
            return null;
        }
        double left = o.size;
        double qty = 0;
        double net = 0;
        for (org.home.data.revx.sim.BookView.Level l : side) {
            if (left <= 1e-12 || (o.buy ? l.price() > o.price : l.price() < o.price)) {
                break;
            }
            double q = Math.min(left, l.qty());
            qty += q;
            net += q * l.price() * (o.buy ? 1 + TAKER_FEE : 1 - TAKER_FEE);
            takerFeeQuote += q * l.price() * TAKER_FEE;
            left -= q;
        }
        placements++;
        if (qty > 0) {
            if (o.buy) {
                baseTotal += qty;
                quoteTotal -= net;
            } else {
                baseTotal -= qty;
                quoteTotal += net;
            }
            done.put(o.id, new double[]{qty, net});
            filledOrders++;
            filledBase += qty;
            takerOrders++;
            takerBase += qty;
        }
        gone.put(o.id, qty > 0 ? "тейкером" : "тейкером (пусто)");
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"%s\"}}",
                o.id, o.clientId, qty > 0 ? "filled" : "cancelled"), 0);
    }

    public synchronized String takerDiag() {
        return String.format(Locale.ROOT, "тейкером %d заявок, %.8g монеты, комиссия %.4f",
                takerOrders, takerBase, takerFeeQuote);
    }

    @Override
    public synchronized Response replace(String id, String json) {
        advance();
        // ⚠️ ЧАСТИЧНО ИСПОЛНЕННУЮ ЗАЯВКУ ПЛОЩАДКА ЗАМЕНИТЬ НЕ ДАЁТ, и стенд
        // обязан отказывать так же. Замена требует состояния NEW, а частичное
        // исполнение из него выводит: 09.09.2026 бот A получил по одной такой
        // заявке восемь отказов подряд за 84 секунды. Стенд, пропускавший
        // замену, приписывал боту возможность переставить цену, которой у него
        // нет, — и тем занижал стоимость крупного лота.
        if (live.containsKey(id) && filledOf(id) > 1e-12) {
            replaceRejects++;
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}", 0);
        }
        if (hiddenUntil.containsKey(id)) {
            replaceRejects++;         // отменённую «на словах» заменить уже нельзя
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}", 50);
        }
        if (fresh(id)) {
            fresh404Made++;
            return new Response(404, "{\"message\":\"Order not found\"}", 400);
        }
        // Гонка: в затык по ходу цены две замены из трёх «медленные» (старая заявка
        // живёт ещё RACE_MS и может исполниться), третья — призрак, как живьём 02.10.
        boolean stallRace = live.containsKey(id) && inVolStall() && effect("race");
        boolean race = stallRace
                && !(stallEffects.contains("ghost") && stallReplaces % GHOST_EVERY == GHOST_EVERY - 1);
        if (stallRace) {
            stallReplaces++;          // счёт ВСЕХ замен в затыке, а не только гонок
        }
        if (!race && live.containsKey(id) && effect("ghost")) {
            // Призрак: предок снят, наследника нет, резерв предка остаётся заперт.
            Order dead = live.remove(id);
            gone.put(id, "призраком замены");
            model.cancelled(id);
            ghostLocks.add(new GhostLock(dead.buy, dead.buy ? dead.size * dead.price : dead.size,
                    clock.now() + ghostHoldMs));
            ghostsMade++;
            replaceRejects++;
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}",
                    GHOST_LATENCY_MS);
        }
        Order old;
        if (race) {
            // Старая остаётся в книге (исполнима) и уходит сама через RACE_MS.
            old = live.get(id);
            hiddenUntil.put(id, clock.now() + RACE_MS);
            gone.put(id, "заменой (гонка)");
            raceMade++;
        } else {
            old = live.remove(id);
            if (old != null) {
                gone.put(id, "заменой");
            }
        }
        if (old == null) {
            // Та самая 422 из док. 111. Проверено зондом 04.09.2026: наследника
            // площадка при этом НЕ создаёт, книга не растёт.
            replaceRejects++;
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}", 0);
        }
        if (!race) {
            model.cancelled(id);
        }
        JsonNode n = read(json);
        if (n == null) {
            if (!race) {
                live.put(id, old);
            }
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        Order o = new Order();
        // ⚠️ Замена создаёт ДРУГУЮ заявку с новым идентификатором — именно это
        // поведение площадки ломало учёт, и стенд обязан его повторять. Для
        // модели очереди это тоже принципиально: наследник встаёт в КОНЕЦ
        // очереди, приоритета предшественника он не наследует.
        o.id = UUID.randomUUID().toString();
        o.clientId = n.path("client_order_id").asText(old.clientId);
        o.symbol = old.symbol;
        o.buy = old.buy;
        o.price = n.path("price").asDouble(old.price);
        o.size = n.path("base_size").asDouble(old.size);
        o.createdMs = clock.now();
        live.put(o.id, o);
        model.placed(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        replaces++;
        if (inVolStall() && effect("fresh404")) {
            freshUntil.put(o.id, clock.now() + FRESH_MS);
        }
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), race ? RACE_MS : 0);
    }

    @Override
    public synchronized Response cancel(String id) {
        advance();
        cancels++;
        if (fresh(id)) {
            fresh404Made++;
            return new Response(404, "{\"message\":\"Order not found\"}", 60);   // свежая: снять нельзя
        }
        if (live.containsKey(id) && !hiddenUntil.containsKey(id)) {
            // В затык ответ на отмену не окончателен: заявка живёт ещё stallLateMs.
            // Два вида поочерёдно, если включены оба.
            boolean late = effect("late204");
            boolean ghost404 = effect("live404");
            if (late || ghost404) {
                boolean use404 = ghost404 && (!late || (late204Made + live404Made) % 2 == 1);
                hiddenUntil.put(id, clock.now() + stallLateMs);
                if (use404) {
                    live404Made++;
                    return new Response(404, "{\"message\":\"Order not found\"}", 50);
                }
                late204Made++;
                return new Response(204, "", 50);
            }
        }
        if (live.remove(id) == null) {
            gone.putIfAbsent(id, "отменой");
            return new Response(404, "{\"message\":\"not found\"}", 0);
        }
        model.cancelled(id);
        return new Response(204, "", 0);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Число без экспоненты и без минуса.
     *
     * Регулярка остатков в {@link org.home.data.revx.exec.QuoteLoop} принимает
     * только {@code [0-9.]+}: {@code 1.25E-5} или отрицательное значение она
     * молча не распознает, и бот решит, что остатка нет вовсе.
     */
    private static String plain(double v) {
        return BigDecimal.valueOf(Math.max(0, v))
                .setScale(12, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Заявки, оставшиеся в книге на конец прогона. */
    public List<String> openIds() {
        return new ArrayList<>(live.keySet());
    }
}
