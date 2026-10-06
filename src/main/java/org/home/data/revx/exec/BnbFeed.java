package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;

/**
 * {@code --revx-bnb-feed}: ЦЕНА БИНАНСА ДЛЯ ЖИВОЙ ОПОРЫ (06.10.2026).
 *
 * Раз в секунду — один публичный запрос {@code /api/v3/ticker/bookTicker} по всем
 * нужным парам (вес 4, лимит Бинанса 6000 в минуту — запас в сотни раз), лучший бид и
 * аск пишутся в {@code revx-shared/bnb.db}, таблица {@code bnb_tick}. Хранится 3 часа:
 * опоре нужна только последняя цена, история — для разбора.
 *
 * Почему REST, а не WebSocket: стенд проверял опору на секундных свечах, то есть с
 * разрешением в секунду, и живой источник с тем же шагом даёт сравнимое. Ответ с
 * bot-arm приходит за ~0.3 с (проверено 06.10.2026).
 *
 * Отдельная служба, а не часть сборщика: сборщик близок к своему пределу запросов,
 * и его отказы невосполнимы (принцип 4). Сбой этой службы безопасен — боты
 * возвращаются к прежней опоре ({@link LiveHybridFair}).
 */
public final class BnbFeed {

    private static final Logger log = LoggerFactory.getLogger(BnbFeed.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String dbPath;
    private final String[] symbols;
    // ⚠️ Таймаут подключения 2 с, а не 5: 06.10.2026 в 16:32 один запрос висел на
    // подключении 5 с, дыра в записи вышла 6 с, и бот a ушёл в аварийный режим.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    /** Адреса публичного API Бинанса: при ошибке следующий запрос идёт на соседний. */
    private static final String[] HOSTS = {"api.binance.com", "api1.binance.com", "api2.binance.com",
            "api3.binance.com"};
    private int host;
    private volatile boolean alive = true;

    public BnbFeed(String dbPath, String symbols) {
        this.dbPath = dbPath;
        this.symbols = symbols.split(",");
    }

    public void stop() {
        alive = false;
    }

    public void run() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=2000");
            st.execute("CREATE TABLE IF NOT EXISTS bnb_tick (symbol TEXT NOT NULL, ts_ms INTEGER NOT NULL, "
                    + "bid REAL, ask REAL, PRIMARY KEY (symbol, ts_ms))");
            StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < symbols.length; i++) {
                list.append(i > 0 ? "," : "").append('"').append(symbols[i].trim()).append('"');
            }
            String query = "/api/v3/ticker/bookTicker?symbols="
                    + URLEncoder.encode(list.append("]").toString(), StandardCharsets.UTF_8);
            log.warn("цена Бинанса: {} → {} раз в секунду", String.join(",", symbols), dbPath);
            long lastPrune = 0;
            long errors = 0;
            try (PreparedStatement ins = db.prepareStatement(
                    "INSERT OR REPLACE INTO bnb_tick(symbol, ts_ms, bid, ask) VALUES (?,?,?,?)")) {
                while (alive) {
                    long t0 = System.currentTimeMillis();
                    try {
                        URI uri = URI.create("https://" + HOSTS[host] + query);
                        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (r.statusCode() != 200) {
                            throw new IllegalStateException("HTTP " + r.statusCode() + " " + r.body());
                        }
                        long ts = System.currentTimeMillis();
                        for (JsonNode n : MAPPER.readTree(r.body())) {
                            ins.setString(1, n.path("symbol").asText());
                            ins.setLong(2, ts);
                            ins.setDouble(3, n.path("bidPrice").asDouble());
                            ins.setDouble(4, n.path("askPrice").asDouble());
                            ins.addBatch();
                        }
                        ins.executeBatch();
                        errors = 0;
                    } catch (Exception e) {
                        host = (host + 1) % HOSTS.length;     // следующий запрос — на соседний адрес
                        if (++errors % 30 == 1) {
                            log.warn("цена Бинанса: {} (ошибок подряд {}), дальше {}", e.toString(), errors,
                                    HOSTS[host]);
                        }
                        Thread.sleep(200);                     // скорый повтор, но не в бешеном цикле
                        continue;
                    }
                    if (t0 - lastPrune > 60_000) {
                        lastPrune = t0;
                        st.execute("DELETE FROM bnb_tick WHERE ts_ms < " + (t0 - 3 * 3_600_000L));
                    }
                    long sleep = 1000 - (System.currentTimeMillis() - t0);
                    if (sleep > 0) {
                        Thread.sleep(sleep);
                    }
                }
            }
        }
    }
}
