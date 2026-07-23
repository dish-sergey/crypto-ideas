package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Kraken Futures (док. 09 §2.9, §5 №10): единственная наша торговая биржа без
 * geo-блока публичных данных. Площадка шорт-ноги S1-lite, поэтому funding здесь
 * невзаимозаменяем с OKX/Binance (док. 09 §4.7).
 *
 * Два потока:
 *  - historicalfundingrates (API v4!) — ЧАСОВОЙ funding, ~12 мес в одном ответе;
 *    первый вызов бэкфиллит всю глубину, дальше добавляются новые часы. Раз в час.
 *  - tickers (v3) — снапшот mark/funding/предиктивный funding/OI, раз в 5 мин.
 *
 * Символы: PF_* — линейные perpetual (multi-collateral). available_at = событие/снапшот.
 */
@Component
public class KrakenCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(KrakenCollector.class);

    private static final String FUNDING = "https://futures.kraken.com/derivatives/api/v4/historicalfundingrates";
    private static final String TICKERS = "https://futures.kraken.com/derivatives/api/v3/tickers";
    private static final long FUNDING_INTERVAL_MS = 55 * 60_000; // не чаще раза в ~час

    private static final String UPSERT_FUNDING = """
            INSERT OR REPLACE INTO kraken_funding(symbol, funding_time, rate, rel_rate, available_at)
            VALUES(?,?,?,?,?)
            """;
    private static final String UPSERT_TICKER = """
            INSERT OR REPLACE INTO kraken_ticker(symbol, ts, mark_price, funding_rate, funding_pred,
                open_interest, vol24h, available_at)
            VALUES(?,?,?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> symbols;

    private volatile long lastFundingMs = 0;

    public KrakenCollector(Db db, ApiClient api,
                           @Value("${collectors.kraken-symbols}") List<String> symbols) {
        this.db = db;
        this.api = api;
        this.symbols = symbols;
    }

    @Override
    public String name() {
        return "kraken";
    }

    @Override
    public void collect() {
        collectTickers();
        long now = System.currentTimeMillis();
        if (now - lastFundingMs >= FUNDING_INTERVAL_MS) {
            for (String symbol : symbols) {
                try {
                    collectFunding(symbol);
                } catch (RuntimeException e) {
                    log.error("kraken funding {}: {}", symbol, e.getMessage());
                }
            }
            lastFundingMs = now;
        }
    }

    /** Часовой funding: v4 отдаёт ~12 мес разом, пишем только новые часы. */
    private void collectFunding(String symbol) {
        JsonNode rates = readTree(FUNDING + "?symbol=" + symbol).path("rates");
        if (!rates.isArray() || rates.isEmpty()) {
            return;
        }
        Long newest = db.queryLong(
                "SELECT MAX(funding_time) FROM kraken_funding WHERE symbol=?", symbol);
        long floor = newest != null ? newest : Long.MIN_VALUE;
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode n : rates) {
            long ts = parseTs(n.get("timestamp"));
            if (ts <= floor) {
                continue;
            }
            rows.add(new Object[]{symbol, ts, n.path("fundingRate").asDouble(),
                    n.path("relativeFundingRate").asDouble(), ts});
        }
        int written = db.batch(UPSERT_FUNDING, rows);
        if (written > 0) {
            log.info("kraken funding {}: +{} часовых ставок", symbol, written);
        }
    }

    /** Снапшот тикеров: один запрос на все перпы, фильтруем по нашим символам. */
    private void collectTickers() {
        JsonNode tickers;
        try {
            tickers = readTree(TICKERS).path("tickers");
        } catch (RuntimeException e) {
            log.error("kraken tickers: {}", e.getMessage());
            return;
        }
        if (!tickers.isArray()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode t : tickers) {
            String sym = matchSymbol(t.path("symbol").asText());
            if (sym == null) {
                continue;
            }
            rows.add(new Object[]{sym, now,
                    dbl(t, "markPrice"), dbl(t, "fundingRate"), dbl(t, "fundingRatePrediction"),
                    dbl(t, "openInterest"), dbl(t, "vol24h"), now});
        }
        int written = db.batch(UPSERT_TICKER, rows);
        if (written > 0) {
            log.debug("kraken tickers: {} снапшотов", written);
        }
    }

    /** tickers отдаёт symbol в нижнем регистре; возвращаем наш канонический (верхний). */
    private String matchSymbol(String tickerSymbol) {
        for (String s : symbols) {
            if (s.equalsIgnoreCase(tickerSymbol)) {
                return s;
            }
        }
        return null;
    }

    /** timestamp Kraken — ISO-8601 ('2026-07-01T13:00:00.000Z'); на всякий поддержим и epoch. */
    private static long parseTs(JsonNode node) {
        if (node.isTextual()) {
            return Instant.parse(node.asText()).toEpochMilli();
        }
        long v = node.asLong();
        return v < 1_000_000_000_000L ? v * 1000 : v; // секунды -> мс, если пришло число
    }

    private static Double dbl(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
