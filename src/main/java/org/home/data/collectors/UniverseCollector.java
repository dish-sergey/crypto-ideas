package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Суточный снапшот топ-200 по капитализации (CoinPaprika, док. 09 §2.7).
 * Ретроспективы у бесплатного API нет — поэтому снапшоты копятся с первого дня
 * (survivorship-free вселенная для S2/C5 строится из НАШИХ снапшотов, док. 09 §4.3).
 * available_at = момент снапшота.
 */
@Component
public class UniverseCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(UniverseCollector.class);

    private static final String URL = "https://api.coinpaprika.com/v1/tickers";
    private static final int TOP_N = 200;

    private static final String UPSERT = """
            INSERT OR REPLACE INTO universe_snapshot(snap_day, coin_id, rank, symbol,
                market_cap_usd, price_usd, volume_24h_usd, available_at)
            VALUES(?,?,?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public UniverseCollector(Db db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    @Override
    public String name() {
        return "universe";
    }

    @Override
    public void collect() {
        JsonNode arr = readTree(URL);
        if (!arr.isArray()) {
            throw new ApiClient.ApiException("Неожиданный ответ coinpaprika");
        }
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode t : arr) {
            int rank = t.path("rank").asInt();
            if (rank < 1 || rank > TOP_N) {
                continue;
            }
            JsonNode usd = t.path("quotes").path("USD");
            rows.add(new Object[]{
                    today, t.get("id").asText(), rank, t.path("symbol").asText(),
                    usd.path("market_cap").asDouble(), usd.path("price").asDouble(),
                    usd.path("volume_24h").asDouble(), now
            });
        }
        int written = db.batch(UPSERT, rows);
        log.info("universe {}: снапшот {} монет", today, written);
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
