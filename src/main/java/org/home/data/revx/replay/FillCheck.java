package org.home.data.revx.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ПОВЕРКА МОДЕЛИ ИСПОЛНЕНИЯ по каждой реальной заявке, без обратной связи.
 *
 * <h2>Зачем ещё один прибор</h2>
 *
 * Все прежние сверки сравнивали ЧИСЛО сделок: живьём 390, у стенда 235. Такое
 * сравнение смешивает две разные ошибки. Стенд ставит свои заявки, они
 * исполняются иначе, от этого расходится инвентарь, за ним скос, за ним цены —
 * и к концу окна стенд котирует уже не там, где котировал живой бот. Значит
 * «модель занижает на 40%» может означать и плохое правило исполнения, и просто
 * разъехавшуюся траекторию.
 *
 * Здесь обратной связи нет вовсе. Берутся ВСЕ заявки, которые живой бот
 * действительно поставил — с их ценами, размерами, временем жизни, — и по
 * каждой задаётся один вопрос: сказала бы модель, что она исполнится? Ответ
 * сверяется с тем, что случилось на самом деле.
 *
 * Получается матрица ошибок, а не одно число: сколько исполнений модель
 * пропустила, сколько выдумала, и насколько вовремя.
 *
 * <h2>Откуда берутся заявки</h2>
 *
 * Журнал пишет каждый запрос и ответ (ТЗ §6), и этого хватает, чтобы
 * восстановить книгу собственных заявок поминутно:
 *
 * <ul>
 *   <li>{@code POST} — в теле сторона, цена и размер, в ответе новый
 *       идентификатор. Заявка родилась;</li>
 *   <li>{@code PUT} — в пути СТАРЫЙ идентификатор (заявка умерла), в теле новая
 *       цена, в ответе НОВЫЙ идентификатор (родилась другая). Сторона
 *       наследуется по цепочке;</li>
 *   <li>{@code DELETE} — в пути идентификатор. Заявка снята;</li>
 *   <li>{@code GET /orders/{id}} с {@code filled_quantity > 0} — заявка
 *       исполнилась, и площадка сама сообщает КОГДА: {@code updated_date}.</li>
 * </ul>
 *
 * ⚠️ Время исполнения берётся ТОЛЬКО из {@code updated_date}. Отметка в
 * {@code exec_fill} — это момент, когда бот УЗНАЛ, и он на 2.4–5.0 с позже
 * (максимум 37 с); за это время цена уходит, и любой замер по ней смещён.
 *
 * ⚠️ Отказ {@code PUT} (422) заявку НЕ убивает: она к тому моменту уже была
 * мертва по другой причине. Убивают только успешные запросы.
 */
public final class FillCheck {

    private static final Logger log = LoggerFactory.getLogger(FillCheck.class);

    private static final Pattern VENUE_ID = Pattern.compile("\"venue_order_id\":\"([^\"]+)\"");
    private static final Pattern PATH_ID = Pattern.compile("/orders/([0-9a-fA-F-]{8,})");
    private static final Pattern SIDE = Pattern.compile("\"side\":\"(buy|sell)\"");
    private static final Pattern PRICE = Pattern.compile("\"price\":\"?([0-9.eE+-]+)\"?");
    private static final Pattern SIZE = Pattern.compile("\"base_size\":\"?([0-9.eE+-]+)\"?");
    private static final Pattern SYMBOL = Pattern.compile("\"symbol\":\"([A-Z0-9]+)[-/]([A-Z0-9]+)\"");

    /** Одна заявка: как жила и чем кончилась. */
    private static final class Order {
        String id;
        boolean buy;
        double price;
        double size;
        long bornMs;
        long diedMs = Long.MAX_VALUE;
        /** Момент исполнения по данным площадки; 0 — не исполнилась. */
        long filledMs;
    }

    private FillCheck() {
    }

    public static void run(String journalPath, String standDbPath, String fromIso, String toIso) {
        Map<String, Order> orders = new LinkedHashMap<>();
        String symbol = null;
        try (Connection c = open(journalPath); Statement st = c.createStatement()) {
            symbol = readOrders(st, orders);
        } catch (Exception e) {
            log.error("журнал не прочитался: {}", e.toString(), e);
            return;
        }
        if (symbol == null || orders.isEmpty()) {
            log.error("в журнале нет ни одной постановки — поверять нечего");
            return;
        }

        long from = fromIso == null || fromIso.isBlank() ? Long.MIN_VALUE
                : java.time.Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE
                : java.time.Instant.parse(toIso).toEpochMilli();

        List<Order> live = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.bornMs >= from && o.bornMs <= to && o.price > 0 && o.size > 0) {
                live.add(o);
            }
        }
        live.sort((x, y) -> Long.compare(x.bornMs, y.bornMs));
        if (live.isEmpty()) {
            log.error("в окне нет заявок");
            return;
        }
        long start = live.get(0).bornMs;
        long end = 0;
        for (Order o : live) {
            end = Math.max(end, o.diedMs == Long.MAX_VALUE ? o.bornMs : o.diedMs);
        }

        log.warn("поверка модели по {}: заявок {}, окно {} — {}", symbol, live.size(),
                java.time.Instant.ofEpochMilli(start), java.time.Instant.ofEpochMilli(end));

        MarketData market = MarketData.load(standDbPath, symbol, start - 60_000, end + 60_000);
        // ⚠️ Курсор ленты односторонний: перечислять сделки надо по ОТДЕЛЬНОЙ
        // копии, иначе модель получит пустой рынок.
        List<Long> tradeTimes = new ArrayList<>();
        for (org.home.data.revx.sim.MarketTrade t
                : market.fresh().tradesBetween(start - 60_001, end + 60_000)) {
            tradeTimes.add(t.tsMs());
        }
        log.warn("рынок: сделок в окне {}", tradeTimes.size());
        MarketFillModel model = new MarketFillModel(market);

        Verdict v = walk(live, tradeTimes, model);
        log.info("\n{}", render(symbol, live, v));
        log.warn("механизм: очередью {}, перехватом {}, пропущено из-за невидимости {}",
                model.queueFills(), model.interceptFills(), model.invisibleSkips());
        log.warn("{}", model.queueBlockStats());
    }

    /** Что модель сказала по каждой заявке. */
    private record Verdict(Map<String, Long> modelFilledAt) {
    }

    /**
     * Пройти по времени, держа СОБСТВЕННУЮ книгу заявок такой, какой она была.
     *
     * Точки обхода — рождения, смерти и сделки на ленте. Объём сделки модель
     * делит между нашими заявками ровно так же, как в прогоне: иначе поверка
     * мерила бы не то правило, которым считают.
     */
    private static Verdict walk(List<Order> live, List<Long> tradeTimes, MarketFillModel model) {
        java.util.TreeSet<Long> times = new java.util.TreeSet<>();
        for (Order o : live) {
            times.add(o.bornMs);
            if (o.diedMs != Long.MAX_VALUE) {
                times.add(o.diedMs);
            }
        }
        // Сделки — тоже точки обхода: между ними модели делать нечего.
        times.addAll(tradeTimes);

        Map<String, Order> resting = new LinkedHashMap<>();
        Map<String, Long> filledAt = new HashMap<>();
        int bornCursor = 0;
        List<Order> byBorn = new ArrayList<>(live);
        List<Order> byDeath = new ArrayList<>(live);
        byDeath.sort((x, y) -> Long.compare(x.diedMs, y.diedMs));
        int deathCursor = 0;

        for (long now : times) {
            while (deathCursor < byDeath.size() && byDeath.get(deathCursor).diedMs <= now) {
                Order o = byDeath.get(deathCursor++);
                if (resting.remove(o.id) != null) {
                    model.cancelled(o.id);
                }
            }
            while (bornCursor < byBorn.size() && byBorn.get(bornCursor).bornMs <= now) {
                Order o = byBorn.get(bornCursor++);
                if (o.diedMs > now) {
                    resting.put(o.id, o);
                    model.placed(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.bornMs));
                }
            }
            if (resting.isEmpty()) {
                model.advance(now, List.of());
                continue;
            }
            List<FillModel.Resting> rs = new ArrayList<>(resting.size());
            for (Order o : resting.values()) {
                rs.add(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.bornMs));
            }
            for (FillModel.Filled f : model.advance(now, rs)) {
                filledAt.putIfAbsent(f.orderId(), now);
                // Исполненная заявка из книги уходит — как и на площадке.
                if (resting.remove(f.orderId()) != null) {
                    model.cancelled(f.orderId());
                }
            }
        }
        return new Verdict(filledAt);
    }

    private static String render(String symbol, List<Order> live, Verdict v) {
        int both = 0;
        int onlyLive = 0;
        int onlyModel = 0;
        int neither = 0;
        List<Double> lagSec = new ArrayList<>();
        for (Order o : live) {
            boolean l = o.filledMs > 0;
            Long m = v.modelFilledAt().get(o.id);
            if (l && m != null) {
                both++;
                lagSec.add((m - o.filledMs) / 1000.0);
            } else if (l) {
                onlyLive++;
            } else if (m != null) {
                onlyModel++;
            } else {
                neither++;
            }
        }
        int liveFills = both + onlyLive;
        int modelFills = both + onlyModel;
        Collections.sort(lagSec);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ПОВЕРКА МОДЕЛИ ИСПОЛНЕНИЯ: ").append(symbol).append(" ===\n\n");
        sb.append(String.format(Locale.ROOT, "заявок всего: %d%n", live.size()));
        sb.append(String.format(Locale.ROOT, "исполнилось живьём: %d (%.1f%%)%n",
                liveFills, 100.0 * liveFills / live.size()));
        sb.append(String.format(Locale.ROOT, "исполнила модель:   %d (%.1f%%)%n%n",
                modelFills, 100.0 * modelFills / live.size()));
        sb.append("             | живьём ДА | живьём НЕТ\n");
        sb.append("-------------+-----------+-----------\n");
        sb.append(String.format(Locale.ROOT, "модель ДА    | %9d | %9d%n", both, onlyModel));
        sb.append(String.format(Locale.ROOT, "модель НЕТ   | %9d | %9d%n%n", onlyLive, neither));
        sb.append(String.format(Locale.ROOT,
                "ПОЛНОТА  (нашла из живых):    %.1f%%  — %d из %d%n",
                liveFills == 0 ? 0 : 100.0 * both / liveFills, both, liveFills));
        sb.append(String.format(Locale.ROOT,
                "ТОЧНОСТЬ (её сделки реальны): %.1f%%  — %d из %d%n",
                modelFills == 0 ? 0 : 100.0 * both / modelFills, both, modelFills));
        if (!lagSec.isEmpty()) {
            sb.append(String.format(Locale.ROOT,
                    "%nзапаздывание модели, с: медиана %.1f, p10 %.1f, p90 %.1f%n",
                    lagSec.get(lagSec.size() / 2),
                    lagSec.get((int) (lagSec.size() * 0.10)),
                    lagSec.get((int) (lagSec.size() * 0.90))));
        }
        sb.append("\n⚠️ Полнота и точность — РАЗНЫЕ болезни. Низкая полнота значит, что\n");
        sb.append("модель занижает поток и всё, что от него зависит. Низкая точность —\n");
        sb.append("что она рисует сделки, которых не было, и тогда прибыль в прогоне\n");
        sb.append("завышена вдвойне: и числом, и тем, что эти сделки удобные.\n");
        return sb.toString();
    }

    /** @return символ пары, восстановленный из первой постановки */
    private static String readOrders(Statement st, Map<String, Order> orders) throws Exception {
        String symbol = null;
        // Сначала постановки и замены — в порядке времени, чтобы цепочка
        // «предок → наследник» собиралась за один проход.
        try (ResultSet rs = st.executeQuery(
                "SELECT ts_ms, method, path, body, response, status FROM exec_request"
                        + " WHERE method IN ('POST','PUT','DELETE') ORDER BY ts_ms, id")) {
            while (rs.next()) {
                long ts = rs.getLong(1);
                String method = rs.getString(2);
                String path = rs.getString(3);
                String body = rs.getString(4);
                String response = rs.getString(5);
                int status = rs.getInt(6);
                boolean ok = status >= 200 && status < 300;

                if ("DELETE".equals(method)) {
                    String id = match(PATH_ID, path);
                    if (ok && id != null) {
                        die(orders, id, ts);
                    }
                    continue;
                }
                if ("PUT".equals(method)) {
                    String oldId = match(PATH_ID, path);
                    // ⚠️ 422 НЕ убивает: к этому моменту заявка уже была мертва
                    // по другой причине, и приписывать ей смерть здесь значило бы
                    // сдвинуть время жизни.
                    if (!ok) {
                        continue;
                    }
                    Order prev = oldId == null ? null : orders.get(oldId);
                    die(orders, oldId, ts);
                    String newId = match(VENUE_ID, response);
                    if (newId == null) {
                        continue;
                    }
                    Order o = new Order();
                    o.id = newId;
                    o.buy = prev != null ? prev.buy : parseBuy(body);
                    o.price = num(match(PRICE, body), prev == null ? 0 : prev.price);
                    o.size = num(match(SIZE, body), prev == null ? 0 : prev.size);
                    o.bornMs = ts;
                    orders.put(newId, o);
                    continue;
                }
                // POST
                if (!ok) {
                    continue;
                }
                String newId = match(VENUE_ID, response);
                if (newId == null) {
                    continue;
                }
                if (symbol == null) {
                    Matcher m = SYMBOL.matcher(body == null ? "" : body);
                    if (m.find()) {
                        symbol = m.group(1) + "/" + m.group(2);
                    }
                }
                Order o = new Order();
                o.id = newId;
                o.buy = parseBuy(body);
                o.price = num(match(PRICE, body), 0);
                o.size = num(match(SIZE, body), 0);
                o.bornMs = ts;
                orders.put(newId, o);
            }
        }
        // Исполнения: время берём у площадки, не у бота.
        try (ResultSet rs = st.executeQuery(
                "SELECT response FROM exec_request WHERE method='GET' AND path LIKE '%/orders/%'"
                        + " AND status=200 AND response LIKE '%filled_quantity%'")) {
            while (rs.next()) {
                String body = rs.getString(1);
                if (body == null || body.contains("\"filled_quantity\":\"0\"")) {
                    continue;
                }
                String id = match(Pattern.compile("\"id\":\"([^\"]+)\""), body);
                String upd = match(Pattern.compile("\"updated_date\":([0-9]+)"), body);
                if (id == null || upd == null) {
                    continue;
                }
                Order o = orders.get(id);
                if (o != null) {
                    o.filledMs = Long.parseLong(upd);
                    // Исполнение — тоже смерть, и оно точнее любого нашего запроса.
                    o.diedMs = Math.min(o.diedMs, o.filledMs);
                }
            }
        }
        return symbol;
    }

    private static void die(Map<String, Order> orders, String id, long ts) {
        Order o = id == null ? null : orders.get(id);
        if (o != null && ts < o.diedMs) {
            o.diedMs = ts;
        }
    }

    private static boolean parseBuy(String body) {
        String s = match(SIDE, body);
        return !"sell".equalsIgnoreCase(s);
    }

    private static double num(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String match(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Connection open(String path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
    }
}
