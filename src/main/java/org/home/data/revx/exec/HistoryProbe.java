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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-history-probe}: ВИДНЫ ЛИ ПОТЕРЯННЫЕ ИСПОЛНЕНИЯ В ИСТОРИИ АККАУНТА.
 * Только GET, ни одного ордера.
 *
 * <h2>Зачем</h2>
 *
 * Зонд {@link FateProbe} 14.09.2026 перебирал УГАДАННЫЕ адреса ({@code /orders/history},
 * {@code /fills}, {@code /trades/my}) и записал «истории заявок у площадки нет».
 * Документация площадки (developer.revolut.com/docs/x-api/orders) называет три
 * других адреса, которых мы не пробовали:
 * <pre>
 * GET /api/1.0/orders/historical        все заявки аккаунта: symbols, order_states,
 *                                       order_types, start_date, end_date, cursor, limit
 * GET /api/1.0/trades/private/{symbol}  наши исполнения: start_date, end_date, cursor, limit
 * GET /api/1.0/orders/fills/{id}        исполнения одной заявки
 * </pre>
 *
 * Прогон 22–23.09.2026 потерял 4–8 исполнений на призраках замены (наследник
 * создан, идентификатор не возвращён, 34 случая за сутки). Если история их
 * показывает, класс потерь закрывается целиком: фоновый читатель истории
 * становится источником правды для всех ботов.
 *
 * <h2>Что делает</h2>
 *
 * Выкачивает обе истории за окно, печатает сырой образец (форма ответа не
 * описана нами нигде), раскладывает исполненные заявки по ботам по метке
 * {@code client_order_id} и сверяет с журналами: всё, что исполнено у
 * площадки, но не записано ботом, печатается поимённо и суммируется по монете.
 */
@Component
@Lazy
public class HistoryProbe {

    private static final Logger log = LoggerFactory.getLogger(HistoryProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE_LIMIT = 100;
    /**
     * ⚠️ История ЗАЯВОК огромна: каждая замена — отдельная заявка, и 200 страниц
     * по сто (60 000 записей на три пары) покрыли 22–23.09.2026 лишь последние
     * часы суток. Для неё хватает образца; сверка идёт по СДЕЛКАМ.
     */
    private static final int MAX_ORDER_PAGES = 3;
    private static final int MAX_PAGES = 200;

    private final RevxConfig cfg;
    private final List<String> botSpec;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private TradeAuth auth;
    private final StringBuilder report = new StringBuilder();

    public HistoryProbe(RevxConfig cfg, @Value("${revx.info.bots}") List<String> botSpec) {
        this.cfg = cfg;
        this.botSpec = botSpec;
    }

    public void run(String fromIso, String toIso, String symbolsCsv, String out) {
        auth = TradeAuth.fromEnvironment();
        long from = Instant.parse(fromIso).toEpochMilli();
        long to = Instant.parse(toIso).toEpochMilli();
        line("# Зонд истории аккаунта — ТОЛЬКО GET");
        line("окно %s → %s, ключ %s", fromIso, toIso, auth.keyFingerprint());

        // КОНТРОЛЬ: заведомо рабочий адрес. 200 = подпись верна, остальным ответам верим.
        Resp control = get("/api/1.0/orders/active", "");
        line("\n## Контроль подписи\n`/orders/active` → %d", control.status);

        List<JsonNode> orders = new ArrayList<>();
        List<JsonNode> trades = new ArrayList<>();
        for (String raw : symbolsCsv.split(",")) {
            String symbol = raw.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            orders.addAll(pull("/api/1.0/orders/historical",
                    "symbols=" + enc(symbol), from, to, "заявки " + symbol, MAX_ORDER_PAGES));
            trades.addAll(pull("/api/1.0/trades/private/" + symbol, "", from, to,
                    "свои сделки " + symbol, MAX_PAGES));
        }

        String sampleFilled = null;
        for (JsonNode o : orders) {
            if (num(o, "filled_quantity") > 0) {
                sampleFilled = text(o, "id", "venue_order_id");
                break;
            }
        }
        if (sampleFilled != null) {
            Resp fills = get("/api/1.0/orders/fills/" + sampleFilled, "");
            line("\n## `/orders/fills/%s` → %d\n```\n%s\n```", sampleFilled, fills.status,
                    cut(fills.body, 1500));
        }

        crossCheck(orders, trades, from, to);

        String text = report.toString();
        log.info("\n{}", text);
        if (out != null && !out.isBlank()) {
            try {
                Path p = Path.of(out);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, text);
                log.info("отчёт: {}", p.toAbsolutePath());
            } catch (Exception e) {
                log.error("не записать {}: {}", out, e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- выкачка

    /**
     * Все страницы одного адреса. Даты пробуются в миллисекундах; если площадка
     * отвечает 400, второй попыткой — ISO: формат в документации не уточнён.
     */
    private List<JsonNode> pull(String path, String baseQuery, long from, long to, String what,
                                int maxPages) {
        line("\n## %s — `%s`", what, path);
        List<JsonNode> all = new ArrayList<>();
        String[][] dateForms = {
                {String.valueOf(from), String.valueOf(to)},
                {enc(Instant.ofEpochMilli(from).toString()), enc(Instant.ofEpochMilli(to).toString())}
        };
        for (String[] dates : dateForms) {
            String cursor = null;
            int pages = 0;
            boolean failed = false;
            while (pages < maxPages) {
                StringBuilder q = new StringBuilder(baseQuery);
                append(q, "start_date=" + dates[0]);
                append(q, "end_date=" + dates[1]);
                append(q, "limit=" + PAGE_LIMIT);
                if (cursor != null) {
                    append(q, "cursor=" + enc(cursor));
                }
                Resp r = get(path, q.toString());
                if (pages == 0) {
                    line("запрос `?%s` → %d", q, r.status);
                    line("```\n%s\n```", cut(r.body, 2500));
                }
                if (r.status != 200) {
                    failed = true;
                    break;
                }
                JsonNode root = parse(r.body);
                List<JsonNode> items = items(root);
                all.addAll(items);
                pages++;
                String next = nextCursor(root);
                if (next == null || next.isBlank() || items.isEmpty() || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
            if (!failed) {
                line("страниц %d, записей %d", pages, all.size());
                if (!all.isEmpty()) {
                    line("поля первой записи: %s", fieldNames(all.get(0)));
                }
                return all;
            }
            all.clear();
        }
        line("⚠️ адрес не ответил 200 ни с одной формой дат");
        return all;
    }

    private static void append(StringBuilder q, String part) {
        if (!q.isEmpty()) {
            q.append('&');
        }
        q.append(part);
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

    /** Курсор ищется где угодно в ответе, кроме самих записей. */
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

    // ---------------------------------------------------------------- сверка

    private void crossCheck(List<JsonNode> orders, List<JsonNode> trades, long from, long to) {
        line("\n## Сверка с журналами ботов");
        Map<String, InfoBot.Watched> byPrefix = new LinkedHashMap<>();
        for (InfoBot.Watched w : InfoBot.parse(botSpec)) {
            byPrefix.put(new BotTag(w.botId()).prefix(), w);
        }
        // Что записано: исполнения по venue_id и сумма по заявке.
        Map<String, Double> booked = new HashMap<>();
        // Наши client_order_id из тел запросов — чтобы узнать призраков.
        Set<String> ourClientIds = new HashSet<>();
        Pattern cid = Pattern.compile("\"client_order_id\"\\s*:\\s*\"([^\"]+)\"");
        for (InfoBot.Watched w : byPrefix.values()) {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro")) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT venue_id, SUM(qty) FROM exec_fill WHERE venue_id IS NOT NULL "
                                + "GROUP BY venue_id");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        booked.merge(rs.getString(1), rs.getDouble(2), Double::sum);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT body FROM exec_request WHERE ts_ms BETWEEN ? AND ? "
                                + "AND method IN ('POST','PUT') AND body IS NOT NULL")) {
                    ps.setLong(1, from - 3_600_000L);
                    ps.setLong(2, to + 3_600_000L);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Matcher m = cid.matcher(rs.getString(1));
                            if (m.find()) {
                                ourClientIds.add(m.group(1).toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                line("⚠️ не прочитать журнал %s: %s", w.journalPath(), e);
            }
        }
        line("записанных заявок с исполнением в журналах: %d, наших client_order_id за окно: %d",
                booked.size(), ourClientIds.size());

        // Записи НЕ по ответу площадки (по догадке, передачи) — у них нет venue_id,
        // и сверка по сделкам их не видит. Печатаются отдельно, чтобы их можно было
        // сопоставить с незаписанным руками.
        line("\n### Записи без venue_id в окне (по догадке, передачи)");
        for (InfoBot.Watched w : byPrefix.values()) {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, side, qty, price, status FROM exec_fill "
                                 + "WHERE venue_id IS NULL AND ts_ms BETWEEN ? AND ?")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        line("- %s %s: %s %.8f по %s (%s)", w.botId(),
                                Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2),
                                rs.getDouble(3), rs.getDouble(4), rs.getString(5));
                    }
                }
            } catch (Exception e) {
                line("⚠️ %s: %s", w.journalPath(), e);
            }
        }

        // 🔑 СВЕРКА ПО СДЕЛКАМ. В сделке нет client_order_id, но есть `oid` —
        // идентификатор заявки. Всё, что записано ботами, узнаётся по нему сразу;
        // про остальное спрашиваем GET /orders/{oid} — там метка бота. Так
        // находится и наследник призрачной замены: его client_order_id мы сами
        // положили в тело PUT.
        Map<String, double[]> byOid = new LinkedHashMap<>();   // oid → [qty, price, ts, sell?]
        for (JsonNode t : trades) {
            String oid = text(t, "oid");
            if (oid == null) {
                continue;
            }
            double[] a = byOid.computeIfAbsent(oid, k -> new double[4]);
            a[0] += num(t, "q");
            a[1] = num(t, "p");
            a[2] = Math.max(a[2], num(t, "tdt"));
            a[3] = "sell".equalsIgnoreCase(text(t, "s")) ? 1 : 0;
        }
        int bookedOk = 0;
        int asked = 0;
        Map<String, double[]> netByBot = new TreeMap<>();
        List<String> unbooked = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        List<String> traces = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byOid.entrySet()) {
            String oid = e.getKey();
            double[] a = e.getValue();
            double gap = a[0] - booked.getOrDefault(oid, 0.0);
            if (gap <= 1e-12) {
                bookedOk++;
                continue;
            }
            Resp r = get("/api/1.0/orders/" + oid, "");
            asked++;
            JsonNode o = parse(r.body);
            if (o != null && o.has("data")) {
                o = o.get("data");
            }
            String client = o == null ? null : text(o, "client_order_id");
            String symbol = o == null ? "?" : text(o, "symbol");
            InfoBot.Watched w = client == null || client.length() < 8 ? null
                    : byPrefix.get(client.substring(0, 8).toLowerCase(Locale.ROOT));
            String side = a[3] > 0 ? "sell" : "buy";
            String when = Instant.ofEpochMilli((long) a[2]).toString();
            if (w == null) {
                foreign.add(String.format(Locale.ROOT, "- %s %s %s %.8f по %s, %s, client_order_id=%s (ответ %d)",
                        when, symbol, side, gap, a[1], oid, client, r.status));
                continue;
            }
            boolean ghost = !ourClientIds.contains(client.toLowerCase(Locale.ROOT));
            unbooked.add(String.format(Locale.ROOT,
                    "| %s | %s | %s | %.8f | %s | %s | %s | %s%s |",
                    w.botId(), symbol, side, gap, a[1], when, oid, client,
                    ghost ? " (нет в наших телах)" : ""));
            traces.add(trace(w, oid, client, (long) a[2]));
            netByBot.computeIfAbsent(w.botId() + "|" + w.base(), k -> new double[1])[0]
                    += (a[3] > 0 ? -1 : 1) * gap;
        }
        line("\n### Сверка по сделкам (`/trades/private`)");
        line("сделок %d по %d заявкам; записано ботами полностью %d, спрошено у площадки %d",
                trades.size(), byOid.size(), bookedOk, asked);
        line("\n#### Исполнено у площадки, НЕ записано ботом: %d", unbooked.size());
        if (!unbooked.isEmpty()) {
            line("| бот | пара | сторона | не записано | цена | время | oid | client_order_id |");
            line("|---|---|---|---|---|---|---|---|");
            unbooked.forEach(this::line);
            line("\n#### Что бот делал с этими заявками (журнал)");
            traces.forEach(this::line);
            line("\nИтог незаписанного по ботам (+ покупка, − продажа):");
            netByBot.forEach((k, v) -> line("- %s: %+.8f", k, v[0]));
        }
        // Обратная сторона: записано ботом БОЛЬШЕ, чем исполнила площадка (дубль
        // накопительного filled_quantity, запись по чужому ответу).
        line("\n#### Записано ботом, но у площадки такого исполнения нет (или меньше)");
        int over = 0;
        for (InfoBot.Watched w : byPrefix.values()) {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT venue_id, side, SUM(qty), MAX(ts_ms), COUNT(*) FROM exec_fill "
                                 + "WHERE venue_id IS NOT NULL AND ts_ms BETWEEN ? AND ? "
                                 + "GROUP BY venue_id, side")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double traded = byOid.containsKey(rs.getString(1))
                                ? byOid.get(rs.getString(1))[0] : 0;
                        double extra = rs.getDouble(3) - traded;
                        if (extra > 1e-12) {
                            over++;
                            line("- %s %s %s: записано %.8f (%d записей), у площадки %.8f, "
                                            + "лишнее %.8f, %s",
                                    w.botId(), Instant.ofEpochMilli(rs.getLong(4)), rs.getString(2),
                                    rs.getDouble(3), rs.getInt(5), traded, extra, rs.getString(1));
                        }
                    }
                }
            } catch (Exception e) {
                line("⚠️ %s: %s", w.journalPath(), e);
            }
        }
        line("итого %d", over);
        line("\n#### Сделки без метки наших ботов: %d", foreign.size());
        foreign.forEach(this::line);
        line("\n(для справки: заявок в образце истории %d)", orders.size());
    }

    /**
     * След заявки в журнале бота: все запросы, где встречается её id или наш
     * client_order_id, и события вокруг исполнения. Отвечает на вопрос «почему
     * бот не записал», а не только «что не записано».
     */
    private String trace(InfoBot.Watched w, String oid, String client, long tdt) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "\n**%s %s** (исполнена %s)\n", w.botId(), oid,
                Instant.ofEpochMilli(tdt)));
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, method, path, status, latency_ms, substr(response, 1, 160), error "
                            + "FROM exec_request WHERE ts_ms BETWEEN ? AND ? AND (path LIKE ? "
                            + "OR body LIKE ? OR response LIKE ?) ORDER BY ts_ms LIMIT 40")) {
                ps.setLong(1, tdt - 2 * 3_600_000L);
                ps.setLong(2, tdt + 1_800_000L);
                ps.setString(3, "%" + oid + "%");
                ps.setString(4, "%" + client + "%");
                ps.setString(5, "%" + oid + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append(String.format(Locale.ROOT, "- %s %s %s → %s за %d мс: %s%s\n",
                                Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2),
                                rs.getString(3).replace("/api/1.0", ""), rs.getObject(4),
                                rs.getLong(5), rs.getString(6) == null ? ""
                                        : rs.getString(6).replaceAll("\s+", " "),
                                rs.getString(7) == null ? "" : " ОШИБКА " + rs.getString(7)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, kind, substr(detail, 1, 220) FROM exec_event "
                            + "WHERE ts_ms BETWEEN ? AND ? AND kind NOT IN ('no_cross','no_funds','park') "
                            + "ORDER BY ts_ms LIMIT 25")) {
                ps.setLong(1, tdt - 120_000L);
                ps.setLong(2, tdt + 600_000L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append(String.format(Locale.ROOT, "  · событие %s %s: %s\n",
                                Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2),
                                rs.getString(3)));
                    }
                }
            }
        } catch (Exception e) {
            sb.append("⚠️ журнал: ").append(e).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTTP

    private record Resp(int status, String body) {
    }

    private Resp get(String path, String query) {
        URI uri = URI.create(cfg.baseUrl() + path + (query.isEmpty() ? "" : "?" + query));
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20)).GET();
                auth.headers("GET", uri, "").forEach(request::header);
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                Thread.sleep(250);   // не залпом: рядом работают сборщик и боты
                if (response.statusCode() == 429) {
                    long wait = response.headers().firstValue("Retry-After")
                            .map(Long::parseLong).orElse(1000L);
                    Thread.sleep(Math.min(Math.max(wait, 200), 30_000));
                    continue;
                }
                return new Resp(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Resp(-1, e.toString());
            } catch (Exception e) {
                if (attempt == 5) {
                    return new Resp(-1, e.toString());
                }
            }
        }
        return new Resp(429, "повторы исчерпаны");
    }

    // ---------------------------------------------------------------- мелочи

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) {
                return v.asText();
            }
        }
        return null;
    }

    private static double num(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return 0;
        }
        try {
            return v.isNumber() ? v.asDouble() : Double.parseDouble(v.asText());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String time(JsonNode o) {
        for (String k : List.of("updated_date", "created_date", "tdt", "timestamp")) {
            JsonNode v = o.get(k);
            if (v != null && v.isNumber()) {
                return Instant.ofEpochMilli(v.asLong()).toString();
            }
            if (v != null && v.isTextual()) {
                return v.asText();
            }
        }
        return "?";
    }

    private static String fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "(пусто)";
        }
        return s.length() <= n ? s : s.substring(0, n) + " …";
    }

    private void line(String fmt, Object... args) {
        report.append(args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args)).append('\n');
    }
}
