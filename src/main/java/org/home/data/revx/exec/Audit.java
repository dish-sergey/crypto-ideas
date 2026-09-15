package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-audit}: СВЕСТИ ТРИ ИСТОЧНИКА УЧЁТА В ОДНОМ МЕСТЕ. Только GET.
 *
 * <h2>Зачем</h2>
 *
 * Учёт у нас живёт в трёх местах, и каждое видит своё:
 * <ul>
 *   <li><b>площадка</b> — {@code /balances} (сколько монеты есть и сколько
 *       заперто) и {@code /orders/active} (чем заперто);</li>
 *   <li><b>реестр</b> {@code alloc.db} — за кем что числится;</li>
 *   <li><b>журналы ботов</b> — что каждый бот думает о своей позиции.</li>
 * </ul>
 *
 * Пока они сходятся, всё в порядке. Разошлись — и узнаём мы об этом случайно:
 * владелец видит в приложении площадки монету, которой ни один бот не
 * показывает. Так было 10.09.2026 (лот лежал ничейным восемь часов), 13.09
 * (три лота после {@code /release} без обратного захвата) и 15.09 (резерв
 * площадки на 0.0016106 ETH, который не объясняется ни одной видимой заявкой).
 *
 * Прибор считает ВСЕ разности сразу и называет их по имени. Он ничего не чинит:
 * деньги в реестре двигают только {@code /claim} и {@code /release} самого
 * бота — это правило, а не ограничение реализации.
 *
 * <h2>Четыре тождества, которые обязаны выполняться</h2>
 *
 * <pre>
 *   1. всего = доступно + заперто                    (внутри площадки)
 *   2. заперто = Σ живых заявок по этой монете       (заперто ЧЕМ-ТО видимым)
 *   3. всего = Σ претензий в реестре + ничейное      (у монеты есть хозяин)
 *   4. претензия бота = его позиция в журнале        (реестр = кэш, журнал = истина)
 * </pre>
 *
 * Нарушение каждого означает свою беду, и путать их нельзя:
 * <ul>
 *   <li>2 — заявка, о которой мы не знаем: монета заперта, продать нельзя;</li>
 *   <li>3 — ничейная монета: ею никто не торгует;</li>
 *   <li>4 — потерянное исполнение: бот считает своим то, чего нет (или
 *       наоборот).</li>
 * </ul>
 */
@Component
@Lazy
public class Audit {

    private static final Logger log = LoggerFactory.getLogger(Audit.class);

    private static final Pattern FIELD = Pattern.compile(
            "\"currency\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"available\"\\s*:\\s*\"([^\"]+)\""
                    + "\\s*,\\s*\"reserved\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"total\"\\s*:\\s*\"([^\"]+)\"");

    private final RevxConfig cfg;
    private final List<String> botSpec;
    private final String allocPath;

    public Audit(RevxConfig cfg,
                 @Value("${revx.info.bots}") List<String> botSpec,
                 @Value("${revx.exec.alloc}") String allocPath) {
        this.cfg = cfg;
        this.botSpec = botSpec;
        this.allocPath = allocPath;
    }

    /** Остаток по одной валюте у площадки. */
    private record Balance(double available, double reserved, double total) {
    }

    /** Живая заявка площадки: что она запирает. */
    private record Order(String id, String clientId, String symbol, String side,
                         double leaves, double price) {

        /** Первый символ нашего идентификатора — метка бота (см. {@link BotTag}). */
        String owner() {
            return clientId == null || clientId.isEmpty() ? "?" : clientId.substring(0, 1);
        }

        String base() {
            int i = symbol.indexOf('/');
            return i < 0 ? symbol : symbol.substring(0, i);
        }

        String quote() {
            int i = symbol.indexOf('/');
            return i < 0 ? "" : symbol.substring(i + 1);
        }
    }

    public void run(String allocOverride, String outPath) {
        String alloc = allocOverride == null || allocOverride.isBlank() ? allocPath : allocOverride;
        TradeAuth auth = TradeAuth.fromEnvironment();
        log.info("торговый ключ загружен ({}), сверка учёта — ТОЛЬКО GET", auth.keyFingerprint());
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        Map<String, Balance> balances = balances(http, auth);
        List<Order> orders = orders(http, auth);
        Map<String, Map<String, Double>> claims = claims(alloc);
        Map<String, double[]> journals = journals();

        StringBuilder sb = new StringBuilder("\n=== СВЕРКА УЧЁТА: площадка ↔ реестр ↔ журналы ===\n");
        sb.append(String.format(Locale.ROOT, "заявок у площадки: %d%n%n", orders.size()));

        // 1–2: что запирает монету.
        sb.append("МОНЕТА: всего / заперто площадкой / заперто ВИДИМЫМИ заявками / разница\n");
        for (var e : balances.entrySet()) {
            String cur = e.getKey();
            Balance b = e.getValue();
            if (b.total() <= 0 && b.reserved() <= 0) {
                continue;
            }
            double byOrders = 0;
            for (Order o : orders) {
                if ("sell".equalsIgnoreCase(o.side()) && o.base().equals(cur)) {
                    byOrders += o.leaves();
                } else if ("buy".equalsIgnoreCase(o.side()) && o.quote().equals(cur)) {
                    byOrders += o.leaves() * o.price();
                }
            }
            double phantom = b.reserved() - byOrders;
            sb.append(String.format(Locale.ROOT, "  %-5s %14.8f | %14.8f | %14.8f | %+14.8f%s%n",
                    cur, b.total(), b.reserved(), byOrders, phantom,
                    Math.abs(phantom) > 1e-8 ? "  ⚠️ заперто НЕВИДИМЫМ" : ""));
        }
        sb.append("⚠️ Разница в этой графе значит, что площадка держит резерв под заявку,\n");
        sb.append("которой нет в /orders/active. Такую монету нельзя ни продать, ни забрать:\n");
        sb.append("`available` по ней ноль, а снять нечего — идентификатор неизвестен.\n");

        // 3: за кем монета числится.
        sb.append("\nВЛАДЕНИЕ: всего у площадки / Σ претензий в реестре / ничейное\n");
        java.util.Set<String> currencies = new java.util.TreeSet<>(balances.keySet());
        currencies.addAll(claims.keySet());
        for (String cur : currencies) {
            Balance b = balances.getOrDefault(cur, new Balance(0, 0, 0));
            Map<String, Double> byBot = claims.getOrDefault(cur, Map.of());
            double sum = byBot.values().stream().mapToDouble(Double::doubleValue).sum();
            if (b.total() <= 0 && Math.abs(sum) < 1e-12) {
                continue;
            }
            double free = b.total() - sum;
            sb.append(String.format(Locale.ROOT, "  %-5s %14.8f | %14.8f | %+14.8f%s%n",
                    cur, b.total(), sum, free,
                    Math.abs(free) > 1e-8 ? (free > 0 ? "  ⚠️ НИЧЕЙНОЕ" : "  ⚠️ ПРЕТЕНЗИЙ БОЛЬШЕ, ЧЕМ МОНЕТЫ") : ""));
            for (var c : byBot.entrySet()) {
                sb.append(String.format(Locale.ROOT, "        %s: %.8f%n", c.getKey(), c.getValue()));
            }
        }

        // 4: реестр против журнала каждого бота.
        sb.append("\nБОТЫ: позиция в журнале / претензия в реестре / расхождение\n");
        for (var e : journals.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String botId = parts[0];
            String base = parts[1];
            double position = e.getValue()[0];
            double claim = claims.getOrDefault(base, Map.of()).getOrDefault(botId, 0.0);
            double drift = claim - position;
            sb.append(String.format(Locale.ROOT, "  %s %-4s %14.8f | %14.8f | %+14.8f%s%n",
                    botId, base, position, claim, drift,
                    Math.abs(drift) > 1e-8 ? "  ⚠️ РЕЕСТР РАЗОШЁЛСЯ С ЖУРНАЛОМ" : ""));
        }

        // Кто чем владеет по заявкам — чтобы сироту было видно поимённо.
        sb.append("\nЗАЯВКИ ПО МЕТКАМ (первый символ client_order_id):\n");
        Map<String, Integer> byOwner = new TreeMap<>();
        for (Order o : orders) {
            byOwner.merge(o.owner() + " " + o.symbol() + " " + o.side(), 1, Integer::sum);
        }
        byOwner.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %-28s %d%n", k, v)));

        log.info(sb.toString());
        if (outPath != null && !outPath.isBlank()) {
            try {
                java.nio.file.Path p = java.nio.file.Path.of(outPath);
                if (p.getParent() != null) {
                    java.nio.file.Files.createDirectories(p.getParent());
                }
                java.nio.file.Files.writeString(p, sb.toString(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ex) {
                log.warn("не записалось в {}: {}", outPath, ex.toString());
            }
        }
    }

    private Map<String, Balance> balances(HttpClient http, TradeAuth auth) {
        Map<String, Balance> out = new LinkedHashMap<>();
        String body = get(http, auth, "/api/1.0/balances");
        if (body == null) {
            return out;
        }
        Matcher m = FIELD.matcher(body);
        while (m.find()) {
            out.put(m.group(1), new Balance(Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    private List<Order> orders(HttpClient http, TradeAuth auth) {
        List<Order> out = new ArrayList<>();
        String body = get(http, auth, "/api/1.0/orders/active");
        if (body == null) {
            return out;
        }
        // ⚠️ Разбор регулярками, а не Jackson: у площадки поля называются
        // по-разному в разных ответах (id против venue_order_id), и жёсткая
        // модель уже однажды не нашла собственную заявку (док. 111).
        for (String chunk : body.split("\\{\"id\"")) {
            String id = one(chunk, "\"id\"\\s*:\\s*\"([^\"]+)\"");
            if (id == null) {
                id = one("\"id\":" + chunk, "\"id\"\\s*:\\s*\"([^\"]+)\"");
            }
            String symbol = one(chunk, "\"symbol\"\\s*:\\s*\"([^\"]+)\"");
            if (symbol == null) {
                continue;
            }
            out.add(new Order(id, one(chunk, "\"client_order_id\"\\s*:\\s*\"([^\"]+)\""),
                    symbol, one(chunk, "\"side\"\\s*:\\s*\"([^\"]+)\""),
                    num(chunk, "\"leaves_quantity\"\\s*:\\s*\"([^\"]+)\""),
                    num(chunk, "\"price\"\\s*:\\s*\"([^\"]+)\"")));
        }
        return out;
    }

    private Map<String, Map<String, Double>> claims(String path) {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bot_id, currency, qty FROM claim ORDER BY currency, bot_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(2), k -> new LinkedHashMap<>())
                        .put(rs.getString(1), rs.getDouble(3));
            }
        } catch (Exception e) {
            log.error("не прочитать реестр {}: {}", path, e.toString());
        }
        return out;
    }

    /** По каждому боту: позиция из его журнала. Ключ — {@code метка|база}. */
    private Map<String, double[]> journals() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (InfoBot.Watched w : InfoBot.parse(botSpec)) {
            String base = w.symbol().substring(0, w.symbol().indexOf('/'));
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT value FROM exec_state WHERE key = 'position'");
                 ResultSet rs = ps.executeQuery()) {
                out.put(w.botId() + "|" + base,
                        new double[]{rs.next() ? rs.getDouble(1) : 0});
            } catch (Exception e) {
                log.warn("не прочитать журнал {}: {}", w.journalPath(), e.toString());
            }
        }
        return out;
    }

    private String get(HttpClient http, TradeAuth auth, String path) {
        URI uri = URI.create(cfg.baseUrl() + path);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20)).GET();
            auth.headers("GET", uri, "").forEach(request::header);
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("{} ответил {}: {}", path, response.statusCode(), response.body());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.error("{}: {}", path, e.toString());
            return null;
        }
    }

    private static String one(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static double num(String text, String regex) {
        String v = one(text, regex);
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
