package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code --revx-venue}: ЧИТАТЕЛЬ ПЛОЩАДКИ. Только GET, ни одного ордера.
 *
 * <h2>Зачем</h2>
 *
 * Схема и доказательства — {@code docs/pairs/ЧИТАТЕЛЬ-ПЛОЩАДКИ.md} (задача A92).
 * Коротко: в затык площадка отвечает не то, что сделала (204 на отмену уже
 * исполненной заявки, 404 на живую), и бот, приняв ответ за окончательный,
 * терял исполнения — шесть за сутки прогона 22–23.09.2026. Правду в разобранных
 * случаях давала лента своих сделок {@code /trades/private}, и только она.
 *
 * Читатель — единственный, кто спрашивает площадку: снимок активных заявок,
 * ленту своих сделок, судьбы заявок, балансы. Всё пишется в общую базу
 * {@code revx-shared/venue.db}, откуда будут читать боты и сводка.
 *
 * <h2>Этап 1: в тени</h2>
 *
 * Боты этой базы пока не читают. Читатель сверяет свою ленту с их журналами
 * ({@code shadow_diff}) и копит задержки, по которым будет выбрано время
 * подтверждения судьбы заявки.
 *
 * <h2>⚠️ Сердцебиение — только по УСПЕШНОМУ циклу</h2>
 *
 * Процесс, который жив, но получает от площадки одни отказы, для ботов так же
 * бесполезен, как мёртвый. Поэтому {@code last_ok_ms} двигается только тогда,
 * когда снимок активных заявок получен, а сводка будит по его возрасту, а не
 * по тому, что служба {@code active (running)} — на этом уже теряли 23 часа
 * (A64).
 */
@Component
@Lazy
public class VenueReader {

    private static final Logger log = LoggerFactory.getLogger(VenueReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Цикл: снимок активных каждый раз, одна пара ленты на цикл. */
    static final long CYCLE_MS = 1_000L;
    /** Балансы и резерв — раз в столько циклов. */
    static final int BALANCE_EVERY = 5;
    /** Сверка с журналами ботов. */
    static final long SHADOW_EVERY_MS = 60_000L;
    /**
     * Сделка моложе этого в сверку не идёт: бот узнаёт об исполнении через
     * секунды (затык — десятки секунд), и раньше «не записано» было бы ложным.
     */
    static final long SHADOW_GRACE_MS = 120_000L;
    /** Судьба незакрытой заявки перепроверяется не чаще. */
    static final long RECHECK_MS = 5_000L;
    /** Сколько судеб спрашивать за цикл — бюджет GET общий с ботами и сборщиком. */
    static final int ORDER_CHECKS_PER_CYCLE = 2;

    private final RevxConfig cfg;
    private final List<String> botSpec;
    private final String dbPath;
    private final List<String> symbols;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private TradeAuth auth;
    private Connection db;
    private final Map<String, InfoBot.Watched> byPrefix = new LinkedHashMap<>();
    private final Map<String, Long> lastTdt = new HashMap<>();
    private long backoffUntil;
    private long errors;
    private long throttled;
    private String lastError;
    private volatile boolean alive = true;

    public VenueReader(RevxConfig cfg,
                       @Value("${revx.info.bots}") List<String> botSpec,
                       @Value("${revx.venue.db:/home/ubuntu/revx-shared/venue.db}") String dbPath,
                       @Value("${revx.venue.symbols:BTC-USDC,ETH-USDC,SOL-USDC}") List<String> symbols) {
        this.cfg = cfg;
        this.botSpec = botSpec;
        this.dbPath = dbPath;
        this.symbols = symbols;
    }

    public void stop() {
        alive = false;
    }

    public void run() throws Exception {
        auth = TradeAuth.fromEnvironment();
        for (InfoBot.Watched w : InfoBot.parse(botSpec)) {
            byPrefix.put(new BotTag(w.botId()).prefix(), w);
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
        try (ResultSet rs = db.createStatement().executeQuery(
                "SELECT symbol, MAX(tdt) FROM trade GROUP BY symbol")) {
            while (rs.next()) {
                lastTdt.put(rs.getString(1), rs.getLong(2));
            }
        }
        long started = System.currentTimeMillis();
        exec("INSERT INTO heartbeat(name, started_ms, last_cycle_ms, last_ok_ms, cycles, errors, "
                + "throttled) VALUES('reader', ?, ?, 0, 0, 0, 0) ON CONFLICT(name) DO UPDATE SET "
                + "started_ms = excluded.started_ms", started, started);
        log.info("читатель площадки: база {}, пары {}, ботов {}, ключ {}", dbPath, symbols,
                byPrefix.size(), auth.keyFingerprint());

        long cycle = 0;
        long lastShadow = 0;
        while (alive) {
            long t0 = System.currentTimeMillis();
            cycle++;
            boolean ok = false;
            try {
                ok = snapshotActive();
                pullTrades(symbols.get((int) (cycle % symbols.size())));
                checkOrders();
                if (cycle % BALANCE_EVERY == 0) {
                    balances();
                }
                if (t0 - lastShadow > SHADOW_EVERY_MS) {
                    lastShadow = t0;
                    shadow();
                }
            } catch (Exception e) {
                errors++;
                lastError = e.toString();
                log.warn("читатель: цикл — {}", e.toString());
            }
            long now = System.currentTimeMillis();
            try {
                exec("UPDATE heartbeat SET last_cycle_ms = ?, cycles = cycles + 1, errors = ?, "
                                + "throttled = ?, last_error = ?" + (ok ? ", last_ok_ms = ?" : "")
                                + " WHERE name = 'reader'",
                        ok ? new Object[]{now, errors, throttled, lastError, now}
                                : new Object[]{now, errors, throttled, lastError});
            } catch (Exception e) {
                log.warn("читатель: сердцебиение не записано — {}", e.toString());
            }
            long sleep = CYCLE_MS - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                pause(sleep);
            }
        }
    }

    // ================================================================ снимок

    /**
     * Снимок {@code /orders/active} целиком, с временем НАЧАЛА запроса: вывод
     * «заявки нет в книге» законен только по снимку, начатому позже нашего
     * последнего запроса по ней (ЧИТАТЕЛЬ-ПЛОЩАДКИ.md §3.3).
     */
    private boolean snapshotActive() throws Exception {
        long started = System.currentTimeMillis();
        Resp r = get("/api/1.0/orders/active", "");
        if (r.status != 200) {
            lastError = "orders/active " + r.status;
            return false;
        }
        long finished = System.currentTimeMillis();
        List<JsonNode> items = items(parse(r.body));
        db.setAutoCommit(false);
        try {
            exec("DELETE FROM live_order");
            for (JsonNode o : items) {
                String client = text(o, "client_order_id");
                exec("INSERT OR REPLACE INTO live_order(oid, client_order_id, bot, symbol, side, "
                                + "price, qty, leaves, filled, status, created_ms, updated_ms) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        text(o, "id"), client, owner(client), text(o, "symbol"), text(o, "side"),
                        num(o, "price"), num(o, "quantity"), num(o, "leaves_quantity"),
                        num(o, "filled_quantity"), text(o, "status"),
                        (long) num(o, "created_date"), (long) num(o, "updated_date"));
            }
            exec("INSERT OR REPLACE INTO snapshot(name, started_ms, finished_ms, rows) "
                    + "VALUES('live_order', ?, ?, ?)", started, finished, items.size());
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        return true;
    }

    // ================================================================ сделки

    /**
     * Лента своих сделок по одной паре. Окно — от последней известной сделки с
     * запасом в минуту (сделка может появиться в ленте позже своего {@code tdt}),
     * но не глубже десяти минут: глубже — задача догонялки при старте.
     */
    private void pullTrades(String symbol) throws Exception {
        long now = System.currentTimeMillis();
        long from = Math.max(lastTdt.getOrDefault(symbol, 0L) - 60_000L, now - 600_000L);
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String q = "start_date=" + from + "&end_date=" + (now + 5_000) + "&limit=100"
                    + (cursor == null ? "" : "&cursor=" + enc(cursor));
            Resp r = get("/api/1.0/trades/private/" + symbol, q);
            if (r.status != 200) {
                lastError = "trades/private/" + symbol + " " + r.status;
                return;
            }
            long seen = System.currentTimeMillis();
            JsonNode root = parse(r.body);
            List<JsonNode> items = items(root);
            for (JsonNode t : items) {
                String tid = text(t, "tid");
                if (tid == null) {
                    continue;
                }
                long tdt = (long) num(t, "tdt");
                int inserted = exec("INSERT OR IGNORE INTO trade(tid, oid, symbol, side, qty, price, "
                                + "tdt, seen_ms, maker) VALUES(?,?,?,?,?,?,?,?,?)",
                        tid, text(t, "oid"), symbol, text(t, "s"), num(t, "q"), num(t, "p"), tdt,
                        seen, t.path("im").asBoolean(false) ? 1 : 0);
                if (inserted > 0) {
                    lastTdt.merge(symbol, tdt, Math::max);
                    // Заявку сделки — в очередь на опознание: метка бота живёт
                    // только в заявке, в сделке её нет.
                    exec("INSERT OR IGNORE INTO order_info(oid, first_trade_ms) VALUES(?, ?)",
                            text(t, "oid"), tdt);
                }
            }
            String next = nextCursor(root);
            if (next == null || next.equals(cursor) || items.size() < 100) {
                return;
            }
            cursor = next;
        }
    }

    // ================================================================ судьбы

    /**
     * Опознание заявок новых сделок и перепроверка незакрытых. Окончательный
     * статус запоминается с моментом, когда читатель его ВПЕРВЫЕ увидел, — это
     * и есть задержка, по которой будет выбрано время подтверждения.
     */
    private void checkOrders() throws Exception {
        long now = System.currentTimeMillis();
        List<String> due = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT oid FROM order_info WHERE terminal_seen_ms IS NULL "
                        + "AND (checked_ms IS NULL OR checked_ms < ?) AND first_trade_ms > ? "
                        + "ORDER BY checked_ms IS NOT NULL, checked_ms LIMIT ?")) {
            ps.setLong(1, now - RECHECK_MS);
            // Старше суток не перепроверяем: либо давно окончательная, либо
            // площадка про неё не отвечает — и то и другое не повод тратить GET.
            ps.setLong(2, now - 86_400_000L);
            ps.setInt(3, ORDER_CHECKS_PER_CYCLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(rs.getString(1));
                }
            }
        }
        for (String oid : due) {
            Resp r = get("/api/1.0/orders/" + oid, "");
            long seen = System.currentTimeMillis();
            if (r.status != 200) {
                exec("UPDATE order_info SET checked_ms = ?, last_http = ? WHERE oid = ?",
                        seen, r.status, oid);
                continue;
            }
            JsonNode o = parse(r.body);
            if (o != null && o.has("data")) {
                o = o.get("data");
            }
            String status = text(o, "status");
            String client = text(o, "client_order_id");
            boolean terminal = terminal(status);
            exec("UPDATE order_info SET client_order_id = ?, bot = ?, symbol = ?, side = ?, "
                            + "price = ?, qty = ?, filled = ?, status = ?, reject_reason = ?, "
                            + "previous_oid = ?, created_ms = ?, updated_ms = ?, checked_ms = ?, "
                            + "last_http = 200, terminal_seen_ms = CASE WHEN ? THEN "
                            + "COALESCE(terminal_seen_ms, ?) ELSE NULL END WHERE oid = ?",
                    client, owner(client), text(o, "symbol"), text(o, "side"), num(o, "price"),
                    num(o, "quantity"), num(o, "filled_quantity"), status,
                    text(o, "reject_reason"), text(o, "previous_order_id"),
                    (long) num(o, "created_date"), (long) num(o, "updated_date"), seen,
                    terminal ? 1 : 0, seen, oid);
        }
    }

    static boolean terminal(String status) {
        return status != null && (status.equalsIgnoreCase("filled")
                || status.equalsIgnoreCase("cancelled") || status.equalsIgnoreCase("rejected")
                || status.equalsIgnoreCase("replaced") || status.equalsIgnoreCase("expired"));
    }

    // ================================================================ балансы

    /**
     * Балансы и разница «заперто площадкой − заперто видимыми заявками». Разница
     * держится с момента появления: запертое без заявки дольше пяти минут —
     * признак призрака замены (A48/A51).
     */
    private void balances() throws Exception {
        long started = System.currentTimeMillis();
        Resp r = get("/api/1.0/balances", "");
        if (r.status != 200) {
            lastError = "balances " + r.status;
            return;
        }
        Map<String, Double> visible = new HashMap<>();
        try (ResultSet rs = db.createStatement().executeQuery(
                "SELECT symbol, side, leaves, price FROM live_order")) {
            while (rs.next()) {
                String[] p = rs.getString(1).split("[/-]");
                if ("sell".equalsIgnoreCase(rs.getString(2))) {
                    visible.merge(p[0], rs.getDouble(3), Double::sum);
                } else {
                    visible.merge(p[1], rs.getDouble(3) * rs.getDouble(4), Double::sum);
                }
            }
        }
        for (JsonNode b : items(parse(r.body))) {
            String cur = text(b, "currency");
            double reserved = num(b, "reserved");
            exec("INSERT OR REPLACE INTO balance(currency, total, reserved, available, started_ms) "
                            + "VALUES(?,?,?,?,?)",
                    cur, num(b, "total"), reserved, num(b, "available"), started);
            double gap = reserved - visible.getOrDefault(cur, 0.0);
            // Пыль от округлений не считается: одна миллионная доля от резерва.
            boolean has = gap > Math.max(1e-10, reserved * 1e-6);
            if (has) {
                exec("INSERT INTO reserve_gap(currency, reserved, visible, gap, since_ms, updated_ms) "
                                + "VALUES(?,?,?,?,?,?) ON CONFLICT(currency) DO UPDATE SET "
                                + "reserved = excluded.reserved, visible = excluded.visible, "
                                + "gap = excluded.gap, updated_ms = excluded.updated_ms",
                        cur, reserved, visible.getOrDefault(cur, 0.0), gap, started, started);
            } else {
                exec("DELETE FROM reserve_gap WHERE currency = ?", cur);
            }
        }
    }

    // ================================================================ тень

    /**
     * Сверка ленты с журналами ботов: по каждой заявке с меткой бота — сколько
     * исполнила площадка и сколько записал бот. Расхождение живёт в
     * {@code shadow_diff}, пока не сойдётся (тогда помечается {@code resolved_ms}).
     *
     * Журналы открываются только на чтение: у работающего бота это горячий путь,
     * и лишняя блокировка ему ни к чему (WAL читателей не блокирует).
     */
    private void shadow() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, List<String[]>> byBot = new HashMap<>();   // бот → [oid, symbol, side, qty, firstTdt]
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT t.oid, t.symbol, t.side, SUM(t.qty), MIN(t.tdt), o.bot "
                        + "FROM trade t JOIN order_info o ON o.oid = t.oid "
                        + "WHERE t.tdt BETWEEN ? AND ? AND o.bot IS NOT NULL "
                        + "GROUP BY t.oid")) {
            ps.setLong(1, now - 86_400_000L);
            ps.setLong(2, now - SHADOW_GRACE_MS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byBot.computeIfAbsent(rs.getString(6), k -> new ArrayList<>()).add(new String[]{
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            String.valueOf(rs.getDouble(4)), String.valueOf(rs.getLong(5))});
                }
            }
        }
        for (InfoBot.Watched w : byPrefix.values()) {
            List<String[]> rows = byBot.get(w.botId());
            if (rows == null) {
                continue;
            }
            Map<String, Double> booked = new HashMap<>();
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT venue_id, SUM(qty) FROM exec_fill WHERE ts_ms > ? "
                                 + "AND venue_id IS NOT NULL GROUP BY venue_id")) {
                ps.setLong(1, now - 2 * 86_400_000L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        booked.put(rs.getString(1), rs.getDouble(2));
                    }
                }
            } catch (Exception e) {
                log.warn("тень: журнал {} не прочитан — {}", w.journalPath(), e.toString());
                continue;
            }
            for (String[] t : rows) {
                double traded = Double.parseDouble(t[3]);
                double own = booked.getOrDefault(t[0], 0.0);
                boolean differs = Math.abs(traded - own) > 1e-12;
                if (differs) {
                    exec("INSERT INTO shadow_diff(oid, bot, symbol, side, traded, booked, tdt, "
                                    + "first_seen_ms, last_check_ms) VALUES(?,?,?,?,?,?,?,?,?) "
                                    + "ON CONFLICT(oid) DO UPDATE SET traded = excluded.traded, "
                                    + "booked = excluded.booked, last_check_ms = excluded.last_check_ms, "
                                    + "resolved_ms = NULL",
                            t[0], w.botId(), t[1], t[2], traded, own, Long.parseLong(t[4]), now, now);
                } else {
                    exec("UPDATE shadow_diff SET booked = ?, last_check_ms = ?, "
                            + "resolved_ms = COALESCE(resolved_ms, ?) WHERE oid = ?", own, now, now, t[0]);
                }
            }
        }
    }

    // ================================================================ разное

    /** Чей это client_order_id: бот по метке, ручная, либо null (чужая). */
    private String owner(String client) {
        if (client == null || client.length() < 8) {
            return null;
        }
        if (ManualTag.is(client)) {
            return "manual";
        }
        InfoBot.Watched w = byPrefix.get(client.substring(0, 8).toLowerCase(Locale.ROOT));
        return w == null ? null : w.botId();
    }

    private int exec(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private record Resp(int status, String body) {
    }

    /** Общий откат на 429: лимит GET делится со сборщиком и ботами. */
    private Resp get(String path, String query) {
        URI uri = URI.create(cfg.baseUrl() + path + (query.isEmpty() ? "" : "?" + query));
        long wait = backoffUntil - System.currentTimeMillis();
        if (wait > 0) {
            pause(wait);
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20)).GET();
            auth.headers("GET", uri, "").forEach(request::header);
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throttled++;
                long ra = response.headers().firstValue("Retry-After")
                        .map(v -> {
                            try {
                                return Long.parseLong(v.trim());
                            } catch (NumberFormatException e) {
                                return 1000L;
                            }
                        }).orElse(1000L);
                backoffUntil = System.currentTimeMillis() + Math.min(Math.max(ra, 200), 30_000);
            } else if (response.statusCode() != 200 && response.statusCode() != 404) {
                errors++;
            }
            return new Resp(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            alive = false;
            return new Resp(-1, null);
        } catch (Exception e) {
            errors++;
            lastError = path + ": " + e;
            return new Resp(-1, null);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonNode parse(String body) {
        try {
            return body == null ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<JsonNode> items(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode arr = root.isArray() ? root : root.path("data");
        if (arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private static String nextCursor(JsonNode root) {
        if (root == null || root.isArray()) {
            return null;
        }
        for (String key : List.of("next_cursor", "nextCursor", "cursor", "next")) {
            JsonNode v = root.findValue(key);
            if (v != null && v.isTextual()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static double num(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        if (v == null || v.isNull()) {
            return 0;
        }
        try {
            return v.isNumber() ? v.asDouble() : Double.parseDouble(v.asText());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Схема {@code venue.db}. Описание таблиц — ЧИТАТЕЛЬ-ПЛОЩАДКИ.md §3.1. */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS trade (
                tid     TEXT PRIMARY KEY,
                oid     TEXT,
                symbol  TEXT,
                side    TEXT,
                qty     REAL,
                price   REAL,
                tdt     INTEGER,
                seen_ms INTEGER,
                maker   INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_trade_oid ON trade(oid);
            CREATE INDEX IF NOT EXISTS idx_trade_tdt ON trade(tdt);
            CREATE TABLE IF NOT EXISTS order_info (
                oid              TEXT PRIMARY KEY,
                client_order_id  TEXT,
                bot              TEXT,
                symbol           TEXT,
                side             TEXT,
                price            REAL,
                qty              REAL,
                filled           REAL,
                status           TEXT,
                reject_reason    TEXT,
                previous_oid     TEXT,
                created_ms       INTEGER,
                updated_ms       INTEGER,
                first_trade_ms   INTEGER,
                checked_ms       INTEGER,
                last_http        INTEGER,
                terminal_seen_ms INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_order_info_open ON order_info(terminal_seen_ms, checked_ms);
            CREATE TABLE IF NOT EXISTS order_watch (
                oid       TEXT PRIMARY KEY,
                bot       TEXT,
                reason    TEXT,
                since_ms  INTEGER,
                closed_ms INTEGER
            );
            CREATE TABLE IF NOT EXISTS live_order (
                oid             TEXT PRIMARY KEY,
                client_order_id TEXT,
                bot             TEXT,
                symbol          TEXT,
                side            TEXT,
                price           REAL,
                qty             REAL,
                leaves          REAL,
                filled          REAL,
                status          TEXT,
                created_ms      INTEGER,
                updated_ms      INTEGER
            );
            CREATE TABLE IF NOT EXISTS snapshot (
                name        TEXT PRIMARY KEY,
                started_ms  INTEGER,
                finished_ms INTEGER,
                rows        INTEGER
            );
            CREATE TABLE IF NOT EXISTS balance (
                currency   TEXT PRIMARY KEY,
                total      REAL,
                reserved   REAL,
                available  REAL,
                started_ms INTEGER
            );
            CREATE TABLE IF NOT EXISTS reserve_gap (
                currency   TEXT PRIMARY KEY,
                reserved   REAL,
                visible    REAL,
                gap        REAL,
                since_ms   INTEGER,
                updated_ms INTEGER
            );
            CREATE TABLE IF NOT EXISTS heartbeat (
                name          TEXT PRIMARY KEY,
                started_ms    INTEGER,
                last_cycle_ms INTEGER,
                last_ok_ms    INTEGER,
                cycles        INTEGER,
                errors        INTEGER,
                throttled     INTEGER,
                last_error    TEXT
            );
            CREATE TABLE IF NOT EXISTS shadow_diff (
                oid           TEXT PRIMARY KEY,
                bot           TEXT,
                symbol        TEXT,
                side          TEXT,
                traded        REAL,
                booked        REAL,
                tdt           INTEGER,
                first_seen_ms INTEGER,
                last_check_ms INTEGER,
                resolved_ms   INTEGER,
                told_ms       INTEGER
            );
            """;
}
