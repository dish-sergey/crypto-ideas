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
 * Funding history (док. 09 §2.3, §3). Основной источник — OKX (проверен),
 * дополнительный — Binance fapi (может быть недоступен из некоторых сетей —
 * сбой одного источника не валит другой, см. док. 01 §3 «отказ данных = нейтраль»).
 * available_at = funding_time. Символ нормализуется: BTC-USDT-SWAP -> BTCUSDT.
 */
@Component
public class FundingCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(FundingCollector.class);

    private static final String OKX = "https://www.okx.com/api/v5/public/funding-rate-history";
    private static final String BINANCE = "https://fapi.binance.com/fapi/v1/fundingRate";

    private static final String UPSERT = """
            INSERT OR REPLACE INTO funding(exchange, symbol, funding_time, rate, available_at)
            VALUES(?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> okxInstruments;
    private final List<String> symbols;

    public FundingCollector(Db db, ApiClient api,
                            @Value("${collectors.okx-instruments}") List<String> okxInstruments,
                            @Value("${collectors.symbols}") List<String> symbols) {
        this.db = db;
        this.api = api;
        this.okxInstruments = okxInstruments;
        this.symbols = symbols;
    }

    @Override
    public String name() {
        return "funding";
    }

    @Override
    public void collect() {
        for (String instId : okxInstruments) {
            try {
                collectOkx(instId, 3); // инкрементально хватает трёх страниц (24 ставки)
            } catch (RuntimeException e) {
                log.error("funding okx {}: {}", instId, e.getMessage());
            }
        }
        for (String symbol : symbols) {
            try {
                collectBinance(symbol);
            } catch (RuntimeException e) {
                log.warn("funding binance {} недоступен: {}", symbol, e.getMessage());
            }
        }
    }

    /** Полный бэкфилл OKX: страницы назад, пока приходят данные. */
    public void backfillOkx(String instId) {
        collectOkx(instId, Integer.MAX_VALUE);
    }

    private void collectOkx(String instId, int maxPages) {
        String symbol = normalize(instId);
        Long newest = db.queryLong(
                "SELECT MAX(funding_time) FROM funding WHERE exchange='okx' AND symbol=?", symbol);
        String after = null; // OKX: after=ts отдаёт записи СТАРШЕ ts (пагинация назад)
        int pages = 0;
        long total = 0;
        while (pages++ < maxPages) {
            String url = OKX + "?instId=" + instId + "&limit=100" + (after != null ? "&after=" + after : "");
            JsonNode data = readTree(url).path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>();
            long oldest = Long.MAX_VALUE;
            for (JsonNode n : data) {
                long ts = n.get("fundingTime").asLong();
                oldest = Math.min(oldest, ts);
                rows.add(new Object[]{"okx", symbol, ts, n.get("fundingRate").asDouble(), ts});
            }
            total += db.batch(UPSERT, rows);
            if (newest != null && oldest <= newest) {
                break; // дошли до уже сохранённого
            }
            after = String.valueOf(oldest);
        }
        if (total > 0) {
            log.info("funding okx {}: +{} ставок", symbol, total);
        }
    }

    /** Binance fapi: пагинация вперёд от последней сохранённой ставки. */
    private void collectBinance(String symbol) {
        Long last = db.queryLong(
                "SELECT MAX(funding_time) FROM funding WHERE exchange='binance' AND symbol=?", symbol);
        long cursor = last != null ? last + 1 : OhlcvCollector.DEFAULT_FROM_MS;
        long total = 0;
        while (true) {
            String url = BINANCE + "?symbol=" + symbol + "&startTime=" + cursor + "&limit=1000";
            JsonNode arr = readTree(url);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>();
            long maxTs = cursor;
            for (JsonNode n : arr) {
                long ts = n.get("fundingTime").asLong();
                maxTs = Math.max(maxTs, ts);
                rows.add(new Object[]{"binance", symbol, ts, n.get("fundingRate").asDouble(), ts});
            }
            total += db.batch(UPSERT, rows);
            if (arr.size() < 1000) {
                break;
            }
            cursor = maxTs + 1;
        }
        if (total > 0) {
            log.info("funding binance {}: +{} ставок", symbol, total);
        }
    }

    private static String normalize(String instId) {
        return instId.replace("-SWAP", "").replace("-", "");
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
