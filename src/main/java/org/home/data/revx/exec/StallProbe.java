package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 🔑 ЗОНД ЗАТЫКОВ (идея владельца 28.09.2026, разрешено им явно): одна крошечная
 * заявка далеко от рынка, которую читатель площадки заменяет раз в
 * {@link #PERIOD_MS}. Замена, ответившая дольше {@link #SLOW_MS} или отказом, —
 * затык; момент пишется в {@code stall_probe}, и по этому расписанию все боты
 * выбирают минуту, в которую не торгуют ({@link StallFeed}).
 *
 * Зачем отдельно от ботов: в разведённую минуту бот ничего не шлёт и затыка там
 * не видит, то есть не узнает ни что затык переехал, ни что прекратился. Зонд
 * торгует во все минуты.
 *
 * Цена: замены суточного лимита не тратят; призрак зонда запирает ~0.15 USDC;
 * после отказа заявка ставится заново — не чаще раза в {@link #REPOST_MS}.
 * Метка {@code 99999999-}: чужая для всех ботов, их сверка её не трогает.
 */
final class StallProbe {

    private static final Logger log = LoggerFactory.getLogger(StallProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String PREFIX = "99999999";
    static final long PERIOD_MS = 2_000L;
    static final long SLOW_MS = 1_000L;
    static final long REPOST_MS = 5 * 60_000L;
    /** Цена обновляется от рынка не чаще — иначе зонд гонялся бы за ценой. */
    static final long REPRICE_MS = 5 * 60_000L;
    /** Насколько ниже рынка стоит покупка зонда. */
    static final double AWAY = 0.10;
    /** Номинал заявки: минимум площадки 0.1 USDC плюс запас на падение цены. */
    static final double NOTIONAL = 0.15;

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS stall_probe (
                ts_ms      INTEGER PRIMARY KEY,
                latency_ms INTEGER,
                status     INTEGER
            )""";

    private final String baseUrl;
    private final TradeAuth auth;
    private final HttpClient http;
    private final Connection db;
    private final String symbol;          // XRP-USDC
    private final String bookDb;          // база сборщика: середина книги
    private final double tick;
    private final double sizeStep;

    private String orderId;
    private long lastProbeMs;
    private long lastPostMs = Long.MIN_VALUE / 2;
    private long lastPriceMs = Long.MIN_VALUE / 2;
    private double basePrice;
    private boolean toggle;
    long probes;
    long stalls;

    StallProbe(String baseUrl, TradeAuth auth, HttpClient http, Connection db, String symbol,
               String bookDb, double tick, double sizeStep) {
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.http = http;
        this.db = db;
        this.symbol = symbol;
        this.bookDb = bookDb;
        this.tick = tick;
        this.sizeStep = sizeStep;
    }

    /** Один шаг, не чаще {@link #PERIOD_MS}. Сбой зонда не роняет читателя. */
    void tick(long now) {
        if (now - lastProbeMs < PERIOD_MS) {
            return;
        }
        lastProbeMs = now;
        try {
            if (orderId == null) {
                orderId = adopt();
            }
            if (now - lastPriceMs >= REPRICE_MS || basePrice <= 0) {
                double mid = mid();
                if (mid > 0) {
                    basePrice = Math.floor(mid * (1 - AWAY) / tick) * tick;
                    lastPriceMs = now;
                }
            }
            if (basePrice <= 0) {
                return;
            }
            double size = Math.ceil(NOTIONAL / basePrice / sizeStep) * sizeStep;
            if (orderId == null) {
                if (now - lastPostMs < REPOST_MS) {
                    return;
                }
                lastPostMs = now;
                String body = String.format(Locale.ROOT,
                        "{\"client_order_id\":\"%s\",\"symbol\":\"%s\",\"side\":\"buy\","
                                + "\"order_configuration\":{\"limit\":{\"base_size\":\"%s\","
                                + "\"price\":\"%s\",\"execution_instructions\":[\"post_only\"]}}}",
                        clientId(), symbol, plain(size), plain(basePrice));
                Resp r = send("POST", "/api/1.0/orders", body);
                orderId = r.status == 200 ? venueId(r.body) : null;
                log.info("зонд затыков: постановка {} по {} → {}", symbol, plain(basePrice), r.status);
                return;
            }
            toggle = !toggle;
            double price = toggle ? basePrice : basePrice - tick;
            String body = String.format(Locale.ROOT,
                    "{\"client_order_id\":\"%s\",\"base_size\":\"%s\",\"price\":\"%s\","
                            + "\"execution_instructions\":[\"post_only\"]}",
                    clientId(), plain(size), plain(price));
            long t0 = System.currentTimeMillis();
            Resp r = send("PUT", "/api/1.0/orders/" + orderId, body);
            long latency = System.currentTimeMillis() - t0;
            probes++;
            if (r.status == 429) {
                return;                                     // лимит — не затык
            }
            if (latency >= SLOW_MS || r.status != 200) {
                stalls++;
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT OR REPLACE INTO stall_probe(ts_ms, latency_ms, status) VALUES(?,?,?)")) {
                    ps.setLong(1, t0);
                    ps.setLong(2, latency);
                    ps.setInt(3, r.status);
                    ps.executeUpdate();
                }
            }
            if (r.status == 200) {
                String id = venueId(r.body);
                orderId = id != null ? id : orderId;
                heartbeat(now);
            } else if (r.status == 404 || r.status == 422) {
                orderId = null;                             // заявки больше нет — поставим снова
            }
        } catch (Exception e) {
            log.warn("зонд затыков: {}", e.toString());
        }
    }

    /** Своя заявка из последнего снимка активных — после перезапуска читателя. */
    private String adopt() throws Exception {
        try (ResultSet rs = db.createStatement().executeQuery(
                "SELECT oid FROM live_order WHERE client_order_id LIKE '" + PREFIX + "%' LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void heartbeat(long now) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO heartbeat(name, started_ms, last_cycle_ms, last_ok_ms, cycles, errors, "
                        + "throttled) VALUES('probe', ?, ?, ?, 1, 0, 0) ON CONFLICT(name) DO UPDATE "
                        + "SET last_cycle_ms = excluded.last_cycle_ms, last_ok_ms = excluded.last_ok_ms, "
                        + "cycles = cycles + 1")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private double mid() {
        String sym = symbol.replace('-', '/');
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + bookDb + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bp1, ap1 FROM revx_book WHERE symbol = ? ORDER BY t_recv_ms DESC LIMIT 1")) {
            ps.setString(1, sym);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getDouble(1) > 0 ? (rs.getDouble(1) + rs.getDouble(2)) / 2 : 0;
            }
        } catch (Exception e) {
            log.warn("зонд: цена {} не прочитана — {}", sym, e.toString());
            return 0;
        }
    }

    private static String clientId() {
        return PREFIX + UUID.randomUUID().toString().substring(8);
    }

    private static String venueId(String body) {
        try {
            JsonNode n = MAPPER.readTree(body);
            String id = n.path("data").path("venue_order_id").asText(null);
            return id != null ? id : n.path("venue_order_id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(8, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private record Resp(int status, String body) {
    }

    private Resp send(String method, String path, String body) {
        try {
            URI uri = URI.create(baseUrl + path);
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            auth.headers(method, uri, body).forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, null);
        } catch (Exception e) {
            return new Resp(-1, null);
        }
    }
}
