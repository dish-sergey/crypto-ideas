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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Свечи со спот-зеркала Binance data-api.binance.vision (док. 09 §2.1):
 * без geo-ограничений и ключей, поле [9] = taker buy base volume (aggressor delta).
 * D1 — для всех символов, 1m — только для minute-symbols.
 * available_at = close_time: бар доступен в момент закрытия (док. 04 §2.1).
 */
@Component
public class OhlcvCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(OhlcvCollector.class);

    private static final String BASE = "https://data-api.binance.vision/api/v3/klines";
    private static final String EXCHANGE_INFO = "https://data-api.binance.vision/api/v3/exchangeInfo";
    private static final int PAGE_LIMIT = 1000;
    private static final long UNIVERSE_TTL_MS = 24 * 3600_000L;
    /** Стартовая точка бэкфилла по умолчанию: 2019-01-01 (док. 04 §1). */
    public static final long DEFAULT_FROM_MS = 1546300800000L;

    private static final String UPSERT = """
            INSERT OR REPLACE INTO candles(symbol, interval, open_time, open, high, low, close,
                volume, quote_volume, trades, taker_buy_volume, close_time, available_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> symbols;
    private final List<String> minuteSymbols;
    private final int universeTop;

    private volatile List<String> universeCache = List.of();
    private volatile long universeCachedMs = 0;

    public OhlcvCollector(Db db, ApiClient api,
                          @Value("${collectors.symbols}") List<String> symbols,
                          @Value("${collectors.minute-symbols}") List<String> minuteSymbols,
                          @Value("${collectors.universe-candles-top:0}") int universeTop) {
        this.db = db;
        this.api = api;
        this.symbols = symbols;
        this.minuteSymbols = minuteSymbols;
        this.universeTop = universeTop;
    }

    @Override
    public String name() {
        return "ohlcv";
    }

    @Override
    public void collect() {
        for (String symbol : symbols) {
            backfill(symbol, "1d", DEFAULT_FROM_MS);
        }
        for (String symbol : minuteSymbols) {
            // без явного backfill 1m качаем только последние 7 дней — полный
            // минутный бэкфилл запускается отдельно: --backfill=ohlcv --interval=1m
            long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            backfill(symbol, "1m", weekAgo);
        }
        // Топ-N вселенной — только дневные свечи (breadth C5 детектора, док. 01).
        int added = 0;
        for (String symbol : universeSymbols()) {
            if (!symbols.contains(symbol)) {
                backfill(symbol, "1d", DEFAULT_FROM_MS);
                added++;
            }
        }
        if (added > 0) {
            log.debug("ohlcv: дневные свечи по {} символам вселенной", added);
        }
    }

    /**
     * Символы для breadth: топ-{@code universeTop} монет из последнего снапшота
     * вселенной, пересечённые с торгуемыми USDT-парами Binance. Кэш на сутки.
     * universeTop=0 — фича выключена.
     */
    private List<String> universeSymbols() {
        if (universeTop <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (!universeCache.isEmpty() && now - universeCachedMs < UNIVERSE_TTL_MS) {
            return universeCache;
        }
        List<String> coins = db.queryStrings(
                "SELECT symbol FROM universe_snapshot WHERE snap_day="
                        + "(SELECT MAX(snap_day) FROM universe_snapshot) ORDER BY rank");
        Set<String> binance = binanceUsdtSymbols();
        if (coins.isEmpty() || binance.isEmpty()) {
            return universeCache; // не смогли обновить — отдаём прошлый кэш
        }
        List<String> result = new ArrayList<>();
        for (String coin : coins) {
            String sym = coin.toUpperCase() + "USDT";
            if (binance.contains(sym)) {
                result.add(sym);
                if (result.size() >= universeTop) {
                    break;
                }
            }
        }
        universeCache = result;
        universeCachedMs = now;
        log.info("ohlcv: вселенная для breadth — {} символов (топ-{})", result.size(), universeTop);
        return result;
    }

    /** Множество торгуемых спот-символов Binance с котировкой USDT. */
    private Set<String> binanceUsdtSymbols() {
        try {
            JsonNode arr = readTree(EXCHANGE_INFO).path("symbols");
            Set<String> set = new HashSet<>();
            for (JsonNode s : arr) {
                if ("TRADING".equals(s.path("status").asText())
                        && "USDT".equals(s.path("quoteAsset").asText())) {
                    set.add(s.path("symbol").asText());
                }
            }
            return set;
        } catch (RuntimeException e) {
            log.warn("ohlcv: exchangeInfo недоступен: {}", e.getMessage());
            return Set.of();
        }
    }

    /** Инкрементально: продолжает с последней сохранённой свечи, если она позже from. */
    public void backfill(String symbol, String interval, long fromMs) {
        Long last = db.queryLong(
                "SELECT MAX(open_time) FROM candles WHERE symbol=? AND interval=?", symbol, interval);
        long cursor = (last != null && last + 1 > fromMs) ? last + 1 : fromMs;
        long total = 0;
        while (true) {
            String url = BASE + "?symbol=" + symbol + "&interval=" + interval
                    + "&startTime=" + cursor + "&limit=" + PAGE_LIMIT;
            JsonNode arr = readTree(url);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>(arr.size());
            long lastCloseTime = 0;
            long now = System.currentTimeMillis();
            for (JsonNode k : arr) {
                long closeTime = k.get(6).asLong();
                if (closeTime > now) {
                    continue; // незакрытый бар не пишем — он ещё изменится
                }
                rows.add(new Object[]{
                        symbol, interval, k.get(0).asLong(),
                        k.get(1).asDouble(), k.get(2).asDouble(), k.get(3).asDouble(), k.get(4).asDouble(),
                        k.get(5).asDouble(), k.get(7).asDouble(), k.get(8).asLong(), k.get(9).asDouble(),
                        closeTime, closeTime
                });
                lastCloseTime = closeTime;
            }
            total += db.batch(UPSERT, rows);
            if (arr.size() < PAGE_LIMIT || rows.isEmpty()) {
                break;
            }
            cursor = lastCloseTime + 1;
        }
        if (total > 0) {
            log.info("ohlcv {} {}: +{} свечей", symbol, interval, total);
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
