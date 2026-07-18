package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Open Interest, шаг 5m. КРИТИЧНО (док. 09 §4.1): Binance отдаёт только 30 дней
 * назад, поэтому коллектор должен работать с первого дня проекта и копить
 * собственную историю. Источники: OKX rubik (глубже) + Binance futures/data.
 * available_at = ts.
 */
@Component
public class OpenInterestCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(OpenInterestCollector.class);

    private static final String OKX =
            "https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history";
    private static final String BINANCE =
            "https://fapi.binance.com/futures/data/openInterestHist";

    private static final String UPSERT = """
            INSERT OR REPLACE INTO open_interest(exchange, symbol, ts, oi_contracts, oi_usd, available_at)
            VALUES(?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> okxInstruments;
    private final List<String> symbols;

    public OpenInterestCollector(Db db, ApiClient api,
                                 @Value("${collectors.okx-instruments}") List<String> okxInstruments,
                                 @Value("${collectors.symbols}") List<String> symbols) {
        this.db = db;
        this.api = api;
        this.okxInstruments = okxInstruments;
        this.symbols = symbols;
    }

    @Override
    public String name() {
        return "oi";
    }

    @Override
    public void collect() {
        for (String instId : okxInstruments) {
            try {
                collectOkx(instId);
            } catch (RuntimeException e) {
                log.error("oi okx {}: {}", instId, e.getMessage());
            }
        }
        for (String symbol : symbols) {
            try {
                collectBinance(symbol);
            } catch (RuntimeException e) {
                log.warn("oi binance {} недоступен: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * OKX rubik: свежая страница (limit 100 = ~8ч при 5m). Ответ — массив массивов
     * [ts, oiContracts, oiBase, oiUsd] (проверено живым запросом, док. 09 §2.3).
     */
    private void collectOkx(String instId) {
        String symbol = instId.replace("-SWAP", "").replace("-", "");
        String url = OKX + "?instId=" + instId + "&period=5m&limit=100";
        JsonNode data = readTree(url).path("data");
        if (!data.isArray()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode n : data) {
            long ts = n.get(0).asLong();
            rows.add(new Object[]{"okx", symbol, ts, n.get(2).asDouble(), n.get(3).asDouble(), ts});
        }
        int written = db.batch(UPSERT, rows);
        log.debug("oi okx {}: {} точек", symbol, written);
    }

    /** Binance: глубина максимум 30 дней, инкрементально от последней точки. */
    private void collectBinance(String symbol) {
        Long last = db.queryLong(
                "SELECT MAX(ts) FROM open_interest WHERE exchange='binance' AND symbol=?", symbol);
        long monthAgo = System.currentTimeMillis() - 29L * 24 * 3600 * 1000;
        long cursor = last != null ? Math.max(last + 1, monthAgo) : monthAgo;
        long total = 0;
        while (true) {
            String url = BINANCE + "?symbol=" + symbol + "&period=5m&limit=500&startTime=" + cursor;
            JsonNode arr = readTree(url);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>();
            long maxTs = cursor;
            for (JsonNode n : arr) {
                long ts = n.get("timestamp").asLong();
                maxTs = Math.max(maxTs, ts);
                rows.add(new Object[]{"binance", symbol, ts,
                        n.get("sumOpenInterest").asDouble(),
                        n.get("sumOpenInterestValue").asDouble(), ts});
            }
            total += db.batch(UPSERT, rows);
            if (arr.size() < 500) {
                break;
            }
            cursor = maxTs + 1;
        }
        if (total > 0) {
            log.debug("oi binance {}: +{} точек", symbol, total);
        }
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
