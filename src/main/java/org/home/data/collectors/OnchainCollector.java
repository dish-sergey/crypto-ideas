package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * On-chain метрики из Coin Metrics Community API (док. 09 §2.4, проверен живым запросом).
 * MVRV Z-score НЕ качается — считается на чтении из CapMrktCurUSD и CapRealUSD.
 * available_at = день + 30ч: моделируем лаг публикации on-chain данных
 * (док. 04 §2.1: «+24ч к on-chain по умолчанию» + запас на конец дня).
 */
@Component
public class OnchainCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(OnchainCollector.class);

    private static final String BASE = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics";
    private static final Duration PUBLICATION_LAG = Duration.ofHours(30);

    private static final String UPSERT = """
            INSERT OR REPLACE INTO onchain_daily(asset, day, metric, value, available_at)
            VALUES(?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> assets;
    private final List<String> metrics;

    public OnchainCollector(Db db, ApiClient api,
                            @Value("${collectors.onchain-assets}") List<String> assets,
                            @Value("${collectors.onchain-metrics}") List<String> metrics) {
        this.db = db;
        this.api = api;
        this.assets = assets;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return "onchain";
    }

    @Override
    public void collect() {
        for (String asset : assets) {
            collectAsset(asset, null);
        }
    }

    /** Полный бэкфилл с указанной даты (например "2017-01-01"). */
    public void backfill(String fromDay) {
        for (String asset : assets) {
            collectAsset(asset, fromDay);
        }
    }

    private void collectAsset(String asset, String fromDay) {
        String start = fromDay;
        if (start == null) {
            // инкрементально: с последнего сохранённого дня минус 3 дня
            // (Coin Metrics иногда ревизит свежие точки)
            Long lastAvail = db.queryLong(
                    "SELECT MAX(available_at) FROM onchain_daily WHERE asset=?", asset);
            start = lastAvail == null
                    ? "2015-01-01"
                    : Instant.ofEpochMilli(lastAvail).minus(Duration.ofDays(4)).toString().substring(0, 10);
        }
        String url = BASE + "?assets=" + asset
                + "&metrics=" + String.join(",", metrics)
                + "&frequency=1d&page_size=1000&start_time=" + start;
        long total = 0;
        while (url != null) {
            JsonNode root = readTree(url);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>();
            for (JsonNode point : data) {
                String day = point.get("time").asText().substring(0, 10);
                long availableAt = Instant.parse(day + "T00:00:00Z").plus(PUBLICATION_LAG).toEpochMilli();
                for (String metric : metrics) {
                    JsonNode v = point.get(metric);
                    if (v != null && !v.isNull()) {
                        rows.add(new Object[]{asset, day, metric, v.asDouble(), availableAt});
                    }
                }
            }
            total += db.batch(UPSERT, rows);
            JsonNode next = root.get("next_page_url");
            url = (next != null && !next.isNull() && data.size() >= 1000) ? next.asText() : null;
        }
        if (total > 0) {
            log.info("onchain {}: +{} значений", asset, total);
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
