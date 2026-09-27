package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code --revx-history-probe --watch-min=N}: НАБЛЮДЕНИЕ ЗА ИСТОРИЕЙ АККАУНТА ПЕРЕД ТЕМ, КАК
 * СТРОИТЬ НА НЕЙ ЧИТАТЕЛЯ. Только GET, ни одного ордера.
 *
 * <h2>Что должен решить</h2>
 *
 * Схема «один читатель площадки пишет в общую базу, боты только читают и не
 * ставят заявку на место исчезнувшей, пока не выяснена её судьба» держится на
 * четырёх свойствах площадки, которых мы не знаем:
 * <ol>
 *   <li>по какому времени фильтрует {@code /orders/historical} — по СОЗДАНИЮ или
 *       по последнему ИЗМЕНЕНИЮ. Если по созданию, окно «последние N минут» не
 *       увидит исполнения давно стоящей заявки, и исполнения придётся брать
 *       только из {@code /trades/private};</li>
 *   <li>работает ли история без {@code symbols} — один запрос на аккаунт;</li>
 *   <li>видны ли в истории ЖИВЫЕ заявки, которых нет в {@code /orders/active} —
 *       призраки A48, держащие резерв. Если видны, их можно снять по id;</li>
 *   <li>с какой задержкой сделка и изменение заявки появляются в ответах — это
 *       нижняя граница того, сколько слот бота будет ждать.</li>
 * </ol>
 * Первые три проверяются РАЗОВО по записанному (ботам работать не нужно),
 * четвёртое — только наблюдением за живыми сделками.
 *
 * <h2>⚠️ Нагрузка</h2>
 *
 * Лимит GET — общий на аккаунт, а сборщик ходит тем же ключом, и его отказ —
 * невосполнимая дыра в книге (принцип 4). Поэтому по умолчанию цикл в 2 с и
 * около 110 запросов в минуту, на 429 общий откат. Каждый 429 считается и
 * печатается.
 */
@Component
@Lazy
public class HistoryWatch {

    private static final Logger log = LoggerFactory.getLogger(HistoryWatch.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SYMBOLS = List.of("BTC-USDC", "ETH-USDC", "SOL-USDC");

    private final RevxConfig cfg;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private TradeAuth auth;
    private final StringBuilder report = new StringBuilder();
    private int requests;
    private int throttled;
    private long backoffUntil;

    private final List<String> botSpec;

    public HistoryWatch(RevxConfig cfg,
                        @org.springframework.beans.factory.annotation.Value("${revx.info.bots}")
                        List<String> botSpec) {
        this.botSpec = botSpec;
        this.cfg = cfg;
    }

    public void run(String fromIso, String toIso, int watchMin, long periodMs, String heirs,
                    String out) {
        auth = TradeAuth.fromEnvironment();
        line("# Наблюдение за историей аккаунта — ТОЛЬКО GET");
        line("ключ %s, запуск %s", auth.keyFingerprint(), Instant.now());
        Resp control = get("/api/1.0/orders/active", "");
        line("контроль `/orders/active` → %d", control.status);
        if (control.status != 200) {
            finish(out);
            return;
        }
        long from = Instant.parse(fromIso).toEpochMilli();
        long to = Instant.parse(toIso).toEpochMilli();
        testNoSymbols(from, to);
        testFilterSemantics(from, to);
        testGhosts();
        testHeirs(heirs);
        if (watchMin > 0) {
            watch(watchMin, periodMs);
        }
        line("\nзапросов %d, из них 429: %d", requests, throttled);
        finish(out);
    }

    // =============================================================== разовые

    /**
     * Вопрос 2: один запрос истории на весь аккаунт. Окно — первые десять минут
     * записанного прогона: в тихий час без ботов пустой ответ ничего не доказал
     * бы (так и вышло при первом запуске 27.09.2026).
     */
    private void testNoSymbols(long from, long to) {
        Resp r = get("/api/1.0/orders/historical",
                "start_date=" + from + "&end_date=" + Math.min(to, from + 600_000L) + "&limit=100");
        List<JsonNode> items = items(r.body);
        Set<String> symbols = new HashSet<>();
        items.forEach(o -> symbols.add(text(o, "symbol")));
        line("\n## 1. История без `symbols`\nответ %d, записей %d, пары %s%s", r.status,
                items.size(), symbols, r.status == 200 ? "" : "\n```\n" + cut(r.body, 500) + "\n```");
    }

    /**
     * Вопрос 1: берём настоящие исполненные заявки из окна, у которых создание и
     * исполнение разнесены во времени, и спрашиваем историю узким окном вокруг
     * КАЖДОГО из двух моментов. В каком окне заявка нашлась — по тому времени
     * площадка и фильтрует.
     */
    private void testFilterSemantics(long from, long to) {
        line("\n## 2. По какому времени фильтрует `/orders/historical`");
        line("| заявка | пара | создана | изменена | разнос, с | в окне создания | в окне изменения |");
        line("|---|---|---|---|---:|---|---|");
        int tested = 0;
        int byCreated = 0;
        int byUpdated = 0;
        Set<String> done = new HashSet<>();
        for (String symbol : SYMBOLS) {
            Resp tr = get("/api/1.0/trades/private/" + symbol,
                    "start_date=" + from + "&end_date=" + to + "&limit=100");
            int perSymbol = 0;
            for (JsonNode t : items(tr.body)) {
                if (perSymbol >= 4) {
                    break;
                }
                String oid = text(t, "oid");
                if (!done.add(oid)) {
                    continue;                 // заявка из нескольких сделок — один раз
                }
                JsonNode o = unwrap(parse(get("/api/1.0/orders/" + oid, "").body));
                if (o == null) {
                    continue;
                }
                long created = (long) num(o, "created_date");
                long updated = (long) num(o, "updated_date");
                if (updated - created < 20_000) {
                    continue;                 // моменты неразличимы окном в ±2 с
                }
                boolean inCreated = present(symbol, oid, created);
                boolean inUpdated = present(symbol, oid, updated);
                perSymbol++;
                tested++;
                if (inCreated) {
                    byCreated++;
                }
                if (inUpdated) {
                    byUpdated++;
                }
                line("| %s | %s | %s | %s | %.0f | %s | %s |", oid.substring(0, 8), symbol,
                        Instant.ofEpochMilli(created), Instant.ofEpochMilli(updated),
                        (updated - created) / 1000.0, inCreated ? "да" : "нет",
                        inUpdated ? "да" : "нет");
            }
        }
        line("\nпроверено %d: нашлись в окне создания %d, в окне изменения %d", tested,
                byCreated, byUpdated);
        if (tested > 0) {
            line(byCreated == tested && byUpdated == 0
                    ? "→ фильтр по СОЗДАНИЮ: исполнения брать из `/trades/private`, историю — для цепочек"
                    : byUpdated == tested && byCreated == 0
                    ? "→ фильтр по ИЗМЕНЕНИЮ: окно «последние N минут» видит и исполнения старых заявок"
                    : "→ картина смешанная, читать таблицу");
        }
    }

    /** Есть ли заявка в истории в окне ±2 с вокруг момента (все страницы). */
    private boolean present(String symbol, String oid, long at) {
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String q = "symbols=" + symbol + "&start_date=" + (at - 2000) + "&end_date=" + (at + 2000)
                    + "&limit=100" + (cursor == null ? "" : "&cursor=" + enc(cursor));
            Resp r = get("/api/1.0/orders/historical", q);
            JsonNode root = parse(r.body);
            for (JsonNode o : items(root)) {
                if (oid.equals(text(o, "id"))) {
                    return true;
                }
            }
            String next = nextCursor(root);
            if (next == null || next.equals(cursor) || items(root).isEmpty()) {
                return false;
            }
            cursor = next;
        }
        return false;
    }

    /**
     * Вопрос 3: живые по истории, но невидимые в списке активных. Спрашиваем
     * историю с фильтром по состоянию за неделю и сверяем со списком активных,
     * взятым ПОСЛЕ неё (иначе исполнившаяся в промежутке заявка сошла бы за
     * призрака). Плюс резерв счёта против суммы видимых заявок.
     */
    private void testGhosts() {
        line("\n## 3. Живые по истории, но невидимые в `/orders/active`");
        long now = System.currentTimeMillis();
        List<JsonNode> alive = new ArrayList<>();
        for (String state : List.of("new", "partially_filled")) {
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String q = "order_states=" + state + "&start_date=" + (now - 7 * 86_400_000L)
                        + "&end_date=" + now + "&limit=100"
                        + (cursor == null ? "" : "&cursor=" + enc(cursor));
                Resp r = get("/api/1.0/orders/historical", q);
                if (page == 0) {
                    line("`order_states=%s` → %d%s", state, r.status,
                            r.status == 200 ? "" : " " + cut(r.body, 300));
                }
                JsonNode root = parse(r.body);
                alive.addAll(items(root));
                String next = nextCursor(root);
                if (r.status != 200 || next == null || next.equals(cursor) || items(root).isEmpty()) {
                    break;
                }
                cursor = next;
            }
        }
        Set<String> active = new HashSet<>();
        Map<String, double[]> visible = new HashMap<>();
        for (JsonNode o : items(get("/api/1.0/orders/active", "").body)) {
            active.add(text(o, "id"));
            reserve(visible, o);
        }
        int ghosts = 0;
        Set<String> nonLive = new HashSet<>();
        for (JsonNode o : alive) {
            String status = text(o, "status");
            if (!"new".equalsIgnoreCase(status) && !"partially_filled".equalsIgnoreCase(status)) {
                nonLive.add(status);          // фильтр по состоянию не сработал
                continue;
            }
            if (!active.contains(text(o, "id"))) {
                ghosts++;
                line("- ПРИЗРАК? %s %s %s %s по %s, осталось %s, создана %s, client %s",
                        text(o, "id"), text(o, "symbol"), text(o, "side"), status,
                        text(o, "price"), text(o, "leaves_quantity"),
                        Instant.ofEpochMilli((long) num(o, "created_date")),
                        text(o, "client_order_id"));
            }
        }
        line("живых по истории %d, в списке активных %d, невидимых %d%s", alive.size(),
                active.size(), ghosts,
                nonLive.isEmpty() ? "" : " (⚠️ фильтр вернул и статусы " + nonLive + ")");
        line("\nрезерв счёта против видимых заявок:");
        for (JsonNode b : items(get("/api/1.0/balances", "").body)) {
            String cur = text(b, "currency");
            double reserved = num(b, "reserved");
            double[] v = visible.getOrDefault(cur, new double[1]);
            if (reserved > 0 || v[0] > 0) {
                line("- %s: заперто %.8f, видимыми %.8f, разница %+.8f", cur, reserved, v[0],
                        reserved - v[0]);
            }
        }
    }

    /**
     * Вопрос 3-бис: ВИДНЫ ЛИ В ИСТОРИИ НАСЛЕДНИКИ ПРИЗРАЧНЫХ ЗАМЕН — те самые
     * заявки, чей идентификатор площадка не вернула. Формат:
     * {@code client_order_id@ISO}, где ISO — время события {@code ghost_replace}.
     *
     * Точный момент замены и её ПРЕДОК берутся из журнала бота (тело PUT несёт
     * наш client_order_id, путь — id заменяемой заявки). В истории наследник
     * ищется двумя способами: по нашему client_order_id и по
     * {@code previous_order_id} = предок. Первая версия брала время «событие
     * минус 30 минут» и не нашла ни одного из семи — неточность окна не должна
     * сойти за ответ площадки.
     */
    private void testHeirs(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        line("\n## 3-бис. Наследники призрачных замен в истории");
        Map<String, InfoBot.Watched> byPrefix = new HashMap<>();
        for (InfoBot.Watched w : InfoBot.parse(botSpec)) {
            byPrefix.put(new BotTag(w.botId()).prefix(), w);
        }
        for (String item : spec.split(",")) {
            String[] p = item.trim().split("@");
            if (p.length != 2) {
                line("- не разобрал «%s»: нужно client_order_id@ISO", item);
                continue;
            }
            String client = p[0].toLowerCase(Locale.ROOT);
            InfoBot.Watched w = byPrefix.get(client.substring(0, 8));
            if (w == null) {
                line("- %s: бот по метке не найден", client);
                continue;
            }
            String symbol = w.symbol().replace('/', '-');
            long ghostAt = Instant.parse(p[1]).toEpochMilli();
            long putAt = 0;
            String parent = null;
            String putAnswer = null;
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 java.sql.PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, path, status, substr(response, 1, 200) FROM exec_request "
                                 + "WHERE ts_ms BETWEEN ? AND ? AND method = 'PUT' "
                                 + "AND body LIKE ? ORDER BY ts_ms LIMIT 1")) {
                ps.setLong(1, ghostAt - 3 * 3_600_000L);
                ps.setLong(2, ghostAt);
                ps.setString(3, "%" + client + "%");
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        putAt = rs.getLong(1);
                        String path = rs.getString(2);
                        parent = path.substring(path.lastIndexOf('/') + 1);
                        putAnswer = rs.getInt(3) + " " + rs.getString(4);
                    }
                }
            } catch (Exception e) {
                line("- %s: журнал не прочитан: %s", client, e);
                continue;
            }
            if (putAt == 0) {
                line("- %s: PUT с этим client_order_id в журнале %s не найден", client,
                        w.journalPath());
                continue;
            }
            JsonNode parentOrder = unwrap(parse(get("/api/1.0/orders/" + parent, "").body));
            long parentUpdated = (long) num(parentOrder, "updated_date");
            line("- %s (бот %s): PUT %s по предку %s → %s", client, w.botId(),
                    Instant.ofEpochMilli(putAt), parent, cut(putAnswer, 160));
            line("  предок сейчас: статус %s (%s), исполнено %s, изменён %s (%+d мс от PUT)",
                    text(parentOrder, "status"), text(parentOrder, "reject_reason"),
                    text(parentOrder, "filled_quantity"), Instant.ofEpochMilli(parentUpdated),
                    parentUpdated - putAt);
            // Все запросы бота на предка рядом с отказом: кто его заменил на самом
            // деле и не висел ли в это время другой запрос на ту же заявку.
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 java.sql.PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, method, path, status, latency_ms, substr(response, 1, 140), "
                                 + "substr(body, 1, 90) FROM exec_request WHERE ts_ms BETWEEN ? AND ? "
                                 + "AND (path LIKE ? OR response LIKE ?) ORDER BY ts_ms")) {
                ps.setLong(1, putAt - 60_000);
                ps.setLong(2, putAt + 60_000);
                ps.setString(3, "%" + parent + "%");
                ps.setString(4, "%" + parent + "%");
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sent = rs.getLong(1) - rs.getLong(5);
                        line("    · отправлен %s, ответ %s (%d мс): %s %s → %s | %s",
                                Instant.ofEpochMilli(sent), Instant.ofEpochMilli(rs.getLong(1)),
                                rs.getLong(5), rs.getString(2),
                                rs.getString(3).replace("/api/1.0", ""), rs.getObject(4),
                                rs.getString(6) == null ? "" : rs.getString(6).replaceAll("\\s+", " "),
                                rs.getString(7) == null ? "" : rs.getString(7));
                    }
                }
            } catch (Exception e) {
                line("    ⚠️ журнал: %s", e);
            }
            // Три окна: у нашего PUT, у момента, когда предок сменил статус (там
            // наследник и рождается, кем бы ни был заказан), и широкое — два часа.
            long[][] windows = {
                    {putAt - 10_000, putAt + 60_000},
                    {parentUpdated - 30_000, parentUpdated + 30_000},
                    {putAt - 600_000, putAt + 7_200_000}
            };
            String[] names = {"у PUT −10…+60 с", "у смены предка ±30 с", "широкое −10 мин…+2 ч"};
            for (int i = 0; i < windows.length; i++) {
                JsonNode[] hit = scan(symbol, windows[i][0], windows[i][1], client, parent,
                        i == 2 ? 150 : 30);
                line("  окно %s (просмотрено %.0f): по client_order_id %s; по предку %s",
                        names[i], hit[2].asDouble(), describe(hit[0]), describe(hit[1]));
                if (hit[0] != null || hit[1] != null) {
                    break;
                }
            }
        }
    }

    /** Ищет в истории заявку с нашим client_order_id и заявку с заданным предком. */
    private JsonNode[] scan(String symbol, long lo, long hi, String client, String parent,
                            int maxPages) {
        JsonNode byClient = null;
        JsonNode byParent = null;
        int scanned = 0;
        String cursor = null;
        for (int page = 0; page < maxPages && (byClient == null || byParent == null); page++) {
            String q = "symbols=" + symbol + "&start_date=" + lo + "&end_date=" + hi + "&limit=100"
                    + (cursor == null ? "" : "&cursor=" + enc(cursor));
            JsonNode root = parse(get("/api/1.0/orders/historical", q).body);
            for (JsonNode o : items(root)) {
                scanned++;
                if (client.equalsIgnoreCase(text(o, "client_order_id"))) {
                    byClient = o;
                }
                if (parent.equals(text(o, "previous_order_id"))) {
                    byParent = o;
                }
            }
            String next = nextCursor(root);
            if (next == null || next.equals(cursor) || items(root).isEmpty()) {
                break;
            }
            cursor = next;
        }
        return new JsonNode[]{byClient, byParent,
                com.fasterxml.jackson.databind.node.IntNode.valueOf(scanned)};
    }

    private static String describe(JsonNode o) {
        if (o == null) {
            return "НЕТ";
        }
        return String.format(Locale.ROOT, "id %s, %s %s, статус %s (%s), исполнено %s, client %s",
                text(o, "id"), text(o, "side"), text(o, "price"), text(o, "status"),
                text(o, "reject_reason"), text(o, "filled_quantity"), text(o, "client_order_id"));
    }

    /** Сколько монеты держит заявка: продажа — базу, покупка — USDC. */
    private static void reserve(Map<String, double[]> acc, JsonNode o) {
        String symbol = text(o, "symbol");
        if (symbol == null) {
            return;
        }
        String[] p = symbol.split("[/-]");
        double left = num(o, "leaves_quantity");
        if ("sell".equalsIgnoreCase(text(o, "side"))) {
            acc.computeIfAbsent(p[0], k -> new double[1])[0] += left;
        } else {
            acc.computeIfAbsent(p[1], k -> new double[1])[0] += left * num(o, "price");
        }
    }

    // =============================================================== наблюдение

    /** Что мы знаем о заявке по наблюдению. */
    private static final class Seen {
        long created;
        long firstActive;              // впервые увидели в /orders/active
        long goneActive;               // пропала из /orders/active
        long firstHist;                // впервые в истории
        long terminalHist;             // впервые в истории с окончательным статусом
        long updated;                  // updated_date у окончательного
        String status;
        boolean inActiveNow;
        boolean ghostReported;
    }

    private void watch(int minutes, long periodMs) {
        line("\n## 4. Наблюдение: %d мин, цикл %d мс", minutes, periodMs);
        long start = System.currentTimeMillis();
        long end = start + minutes * 60_000L;
        Map<String, Seen> orders = new LinkedHashMap<>();
        Map<String, Long> tradeSeen = new HashMap<>();
        List<Long> lagTrade = new ArrayList<>();
        List<Long> lagActiveIn = new ArrayList<>();
        List<Long> lagActiveOut = new ArrayList<>();
        List<Long> lagHistNew = new ArrayList<>();
        List<Long> lagHistTerminal = new ArrayList<>();
        List<String> events = new ArrayList<>();
        long lastHist = start - 60_000L;
        long lastTrades = start - 600_000L;
        int cycle = 0;
        long lastSummary = start;
        while (System.currentTimeMillis() < end) {
            long t0 = System.currentTimeMillis();
            cycle++;

            // --- активные
            Resp ar = get("/api/1.0/orders/active", "");
            if (ar.status == 200) {
                long seenAt = System.currentTimeMillis();
                Set<String> now = new HashSet<>();
                for (JsonNode o : items(ar.body)) {
                    String id = text(o, "id");
                    now.add(id);
                    Seen s = orders.computeIfAbsent(id, k -> new Seen());
                    s.inActiveNow = true;
                    if (s.firstActive == 0) {
                        s.firstActive = seenAt;
                        s.created = (long) num(o, "created_date");
                        if (s.created > start) {
                            lagActiveIn.add(seenAt - s.created);
                        }
                    }
                }
                for (Map.Entry<String, Seen> e : orders.entrySet()) {
                    Seen s = e.getValue();
                    if (s.inActiveNow && !now.contains(e.getKey())) {
                        s.inActiveNow = false;
                        s.goneActive = seenAt;
                        if (s.updated > 0) {
                            lagActiveOut.add(seenAt - s.updated);
                        }
                    }
                }
            }

            // --- история: скользящее окно по моменту прошлого опроса с перекрытием
            long histStart = System.currentTimeMillis();
            String cursor = null;
            for (int page = 0; page < 5; page++) {
                String q = "start_date=" + (lastHist - 5_000) + "&end_date=" + (histStart + 1_000)
                        + "&limit=100" + (cursor == null ? "" : "&cursor=" + enc(cursor));
                Resp hr = get("/api/1.0/orders/historical", q);
                if (hr.status != 200) {
                    break;
                }
                long seenAt = System.currentTimeMillis();
                JsonNode root = parse(hr.body);
                for (JsonNode o : items(root)) {
                    String id = text(o, "id");
                    Seen s = orders.computeIfAbsent(id, k -> new Seen());
                    s.created = (long) num(o, "created_date");
                    String status = text(o, "status");
                    if (s.firstHist == 0) {
                        s.firstHist = seenAt;
                        if (s.created > start) {
                            lagHistNew.add(seenAt - s.created);
                        }
                    }
                    if (status != null && !status.equals(s.status)) {
                        s.status = status;
                        if (terminal(status) && s.terminalHist == 0) {
                            s.terminalHist = seenAt;
                            s.updated = (long) num(o, "updated_date");
                            if (s.updated > start) {
                                lagHistTerminal.add(seenAt - s.updated);
                            }
                            if (num(o, "filled_quantity") > 0) {
                                events.add(String.format(Locale.ROOT,
                                        "%s исполнена %s %s %s: изменена %s, в истории через %d мс",
                                        id.substring(0, 8), text(o, "symbol"), text(o, "side"),
                                        text(o, "filled_quantity"), Instant.ofEpochMilli(s.updated),
                                        seenAt - s.updated));
                            }
                        }
                    }
                }
                String next = nextCursor(root);
                if (next == null || next.equals(cursor) || items(root).isEmpty()) {
                    break;
                }
                cursor = next;
            }
            lastHist = histStart;

            // --- призраки в живом: жива по истории, нет в списке, взятом ПОСЛЕ неё
            for (Map.Entry<String, Seen> e : orders.entrySet()) {
                Seen s = e.getValue();
                if (!s.ghostReported && s.firstHist > 0 && !terminal(s.status)
                        && s.status != null && !s.inActiveNow && s.firstActive == 0
                        && System.currentTimeMillis() - s.firstHist > 10_000) {
                    s.ghostReported = true;
                    events.add(String.format(Locale.ROOT,
                            "ПРИЗРАК? %s статус %s по истории, в /orders/active не появлялась 10 с",
                            e.getKey(), s.status));
                }
            }

            // --- свои сделки: раз в три цикла, по одной паре
            if (cycle % 3 == 0) {
                String symbol = SYMBOLS.get((cycle / 3) % SYMBOLS.size());
                long nowMs = System.currentTimeMillis();
                Resp tr = get("/api/1.0/trades/private/" + symbol,
                        "start_date=" + Math.max(lastTrades, nowMs - 600_000L) + "&end_date="
                                + (nowMs + 1_000) + "&limit=100");
                long seenAt = System.currentTimeMillis();
                for (JsonNode t : items(tr.body)) {
                    String tid = text(t, "tid");
                    if (tid == null || tradeSeen.containsKey(tid)) {
                        continue;
                    }
                    tradeSeen.put(tid, seenAt);
                    long tdt = (long) num(t, "tdt");
                    if (tdt > start) {
                        lagTrade.add(seenAt - tdt);
                        Seen s = orders.get(text(t, "oid"));
                        events.add(String.format(Locale.ROOT,
                                "сделка %s %s %s по %s: время %s, в /trades/private через %d мс; "
                                        + "заявка в истории окончательной %s, из активных ушла %s",
                                symbol, text(t, "s"), text(t, "q"), text(t, "p"),
                                Instant.ofEpochMilli(tdt), seenAt - tdt,
                                s == null || s.terminalHist == 0 ? "ещё нет"
                                        : "через " + (s.terminalHist - tdt) + " мс",
                                s == null || s.goneActive == 0 ? "ещё нет"
                                        : "через " + (s.goneActive - tdt) + " мс"));
                    }
                }
            }

            if (System.currentTimeMillis() - lastSummary > 60_000) {
                lastSummary = System.currentTimeMillis();
                log.info("наблюдение: {} мин, заявок {}, сделок {}, запросов {}, 429 {}",
                        (lastSummary - start) / 60_000, orders.size(), lagTrade.size(), requests,
                        throttled);
            }
            long sleep = periodMs - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                pause(sleep);
            }
        }

        line("\nзаявок замечено %d, новых сделок %d", orders.size(), lagTrade.size());
        line("\n| задержка появления | n | медиана, мс | 90%, мс | макс, мс |");
        line("|---|---:|---:|---:|---:|");
        quant("сделка в `/trades/private` (от `tdt`)", lagTrade);
        quant("новая заявка в `/orders/active` (от создания)", lagActiveIn);
        quant("новая заявка в истории (от создания)", lagHistNew);
        quant("окончательный статус в истории (от `updated_date`)", lagHistTerminal);
        quant("уход из `/orders/active` (от `updated_date`)", lagActiveOut);
        line("\n⚠️ Задержка включает цикл опроса (%d мс, сделки — втрое реже): это оценка сверху.",
                periodMs);
        line("\n### События");
        events.forEach(this::line);
    }

    private void quant(String name, List<Long> xs) {
        if (xs.isEmpty()) {
            line("| %s | 0 | — | — | — |", name);
            return;
        }
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        line("| %s | %d | %d | %d | %d |", name, s.size(), s.get(s.size() / 2),
                s.get((int) Math.min(s.size() - 1, Math.floor(s.size() * 0.9))), s.get(s.size() - 1));
    }

    private static boolean terminal(String status) {
        return status != null && (status.equalsIgnoreCase("filled")
                || status.equalsIgnoreCase("cancelled") || status.equalsIgnoreCase("rejected")
                || status.equalsIgnoreCase("replaced") || status.equalsIgnoreCase("expired"));
    }

    // =============================================================== HTTP

    private record Resp(int status, String body) {
    }

    /** Общий откат на 429: не долбиться, пока сборщик делит с нами лимит. */
    private Resp get(String path, String query) {
        URI uri = URI.create(cfg.baseUrl() + path + (query.isEmpty() ? "" : "?" + query));
        for (int attempt = 1; attempt <= 5; attempt++) {
            long wait = backoffUntil - System.currentTimeMillis();
            if (wait > 0) {
                pause(wait);
            }
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20)).GET();
                auth.headers("GET", uri, "").forEach(request::header);
                requests++;
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    throttled++;
                    long ra = response.headers().firstValue("Retry-After")
                            .map(Long::parseLong).orElse(1000L);
                    backoffUntil = System.currentTimeMillis() + Math.min(Math.max(ra, 500), 30_000);
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

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =============================================================== мелочи

    private static JsonNode parse(String body) {
        try {
            return body == null ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode unwrap(JsonNode n) {
        return n != null && n.has("data") && n.get("data").isObject() ? n.get("data") : n;
    }

    private static List<JsonNode> items(String body) {
        return items(parse(body));
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

    private static String cut(String s, int n) {
        return s == null ? "(пусто)" : s.length() <= n ? s : s.substring(0, n) + " …";
    }

    private void line(String fmt, Object... args) {
        report.append(args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args)).append('\n');
    }

    private void finish(String out) {
        String text = report.toString();
        log.info("\n{}", text);
        if (out == null || out.isBlank()) {
            return;
        }
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
