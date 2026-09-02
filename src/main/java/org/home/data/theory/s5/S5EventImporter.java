package org.home.data.theory.s5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.theory.Jlog;
import org.home.data.theory.TheoryDb;
import org.home.data.trade.UnlockSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Импорт датасета событий S5 (токен-разлоки) в data/theory.db. Один раз ходит в
 * сеть, дальше стенды ТЗ 65/66 считают оффлайн и воспроизводимо.
 *
 * <p>Три источника:
 * <ul>
 *   <li>кэш DefiLlama emissions (снят до перевода API на платный тариф — CLAUDE.md
 *       «Ограничения источников»): cliff-разлоки и циркулирующее предложение;</li>
 *   <li>CoinGecko coins/list: gecko_id → тикер (кэшируется в s5_symbol_map);</li>
 *   <li>Binance fapi: дневные свечи и funding перпа по активам событий.</li>
 * </ul>
 *
 * <p>Событие берётся, если это {@code cliff} и доля разлока ≥ порога импорта
 * (по умолчанию 2%: фильтр стратегии — 3%, но датасет держим шире, чтобы ТЗ 66
 * мог показать чувствительность к порогу, не переснимая данные).
 *
 * <p>CLI: {@code --theory=s5-import}.
 */
@Component
@Lazy
public class S5EventImporter {

    private static final Logger log = LoggerFactory.getLogger(S5EventImporter.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY_MS = 86_400_000L;

    private final TheoryDb db;
    private final String cacheDir;
    private final double minPct;
    private final String from;

    public S5EventImporter(TheoryDb db,
                           @Value("${theory.s5.cache-dir}") String cacheDir,
                           @Value("${theory.s5.import-min-pct}") double minPct,
                           @Value("${theory.s5.price-from}") String from) {
        this.db = db;
        this.cacheDir = cacheDir;
        this.minPct = minPct;
        this.from = from;
    }

    private record Raw(String base, String day, double pct, double tokens) {
    }

    public void run() {
        String temp = System.getenv("TEMP") == null ? "/tmp" : System.getenv("TEMP");
        Path dir = Path.of(cacheDir.replace("%TEMP%", temp));
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("нет кэша DefiLlama emissions: " + dir
                    + " (API стал платным, ретроспективу заново не снять — см. CLAUDE.md)");
        }
        Map<String, String> geckoToBase = symbolMap();
        Set<String> perps = binancePerpBases();

        List<Raw> events = new ArrayList<>();
        Set<String> bases = new TreeSet<>();
        int files = 0;
        int skippedNoPerp = 0;
        try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                files++;
                try {
                    JsonNode e = MAPPER.readTree(f.toFile());
                    String base = geckoToBase.get(geckoId(e));
                    if (base == null || !perps.contains(base)) {
                        skippedNoPerp++;
                        continue;
                    }
                    collectEvents(e, base, events, bases);
                } catch (IOException ex) {
                    Jlog.warn(log, "s5.cache.skip",
                            Map.of("file", f.getFileName().toString(), "err", ex.toString()));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("не читается кэш " + dir, ex);
        }

        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (Raw r : events) {
            rows.add(new Object[]{r.base(), r.day(), r.pct(), r.tokens(), "defillama-cache", now});
        }
        db.batch("INSERT OR REPLACE INTO s5_event(base, unlock_day, pct_supply, tokens, source, imported_ms) "
                + "VALUES(?,?,?,?,?,?)", rows);
        Jlog.info(log, "s5.events", Map.of("files", files, "events", rows.size(),
                "bases", bases.size(), "skipped_no_perp", skippedNoPerp, "min_pct", minPct));

        int i = 0;
        for (String base : bases) {
            try {
                importPrices(base);
                importFunding(base);
            } catch (RuntimeException ex) {
                Jlog.warn(log, "s5.price.fail", Map.of("base", base, "err", ex.toString()));
            }
            if (++i % 25 == 0) {
                Jlog.info(log, "s5.price.progress", Map.of("done", i, "total", bases.size()));
            }
        }
        Jlog.info(log, "s5.import.done", Map.of("bases", bases.size(),
                "prices", db.queryLong("SELECT count(*) FROM s5_price"),
                "funding", db.queryLong("SELECT count(*) FROM s5_funding_daily")));
    }

    /**
     * События одного протокола через общий разбор {@link UnlockSchedule} (док. 130 §6): транши одного дня
     * сливаются, и только потом применяется {@code minPct}. Раньше здесь была своя копия разбора, которая
     * резала по порогу каждый транш, а дубликаты по {@code (base, unlock_day)} гасились уже в SQLite через
     * {@code INSERT OR REPLACE} — в базу попадала доля СЛУЧАЙНОГО транша, а не всего дня.
     */
    private void collectEvents(JsonNode e, String base, List<Raw> events, Set<String> bases) {
        for (UnlockSchedule.Unlock u : UnlockSchedule.parse(e, base, minPct)) {
            events.add(new Raw(base, day(u.day() * 86400_000L), u.pct(), u.tokens()));
            bases.add(base);
        }
    }

    private static String geckoId(JsonNode e) {
        String token = e.path("metadata").path("token").asText("");
        return token.startsWith("coingecko:") ? token.substring(10) : e.path("gecko_id").asText("");
    }

    /** gecko_id → тикер. Кэшируется: CoinGecko отдаёт 15+ тысяч строк и лимитирует темп. */
    private Map<String, String> symbolMap() {
        Map<String, String> cached = new HashMap<>();
        db.query("SELECT gecko_id, base FROM s5_symbol_map", rs -> cached.put(rs.getString(1), rs.getString(2)));
        if (!cached.isEmpty()) {
            Jlog.info(log, "s5.symbolmap.cached", Map.of("n", cached.size()));
            return cached;
        }
        JsonNode list = get("https://api.coingecko.com/api/v3/coins/list");
        List<Object[]> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JsonNode n : list) {
            String id = n.path("id").asText();
            String sym = n.path("symbol").asText().toUpperCase();
            cached.put(id, sym);
            rows.add(new Object[]{id, sym, now});
        }
        db.batch("INSERT OR REPLACE INTO s5_symbol_map(gecko_id, base, imported_ms) VALUES(?,?,?)", rows);
        Jlog.info(log, "s5.symbolmap.fetched", Map.of("n", cached.size()));
        return cached;
    }

    /** Базовые активы USDT-перпов Binance. */
    private Set<String> binancePerpBases() {
        Set<String> out = new HashSet<>();
        for (JsonNode n : get("https://fapi.binance.com/fapi/v1/exchangeInfo").path("symbols")) {
            if ("PERPETUAL".equals(n.path("contractType").asText())
                    && "USDT".equals(n.path("quoteAsset").asText())) {
                out.add(n.path("baseAsset").asText().toUpperCase());
            }
        }
        Jlog.info(log, "s5.perps", Map.of("n", out.size()));
        return out;
    }

    private void importPrices(String base) {
        long start = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> liq = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int page = 0; page < 6; page++) {
            JsonNode kl = get("https://fapi.binance.com/fapi/v1/klines?symbol=" + base
                    + "USDT&interval=1d&startTime=" + start + "&limit=1500");
            if (!kl.isArray() || kl.isEmpty()) {
                break;
            }
            long last = start;
            for (JsonNode c : kl) {
                long openTime = c.get(0).asLong();
                rows.add(new Object[]{base, day(openTime), c.get(1).asDouble(), c.get(2).asDouble(),
                        c.get(3).asDouble(), c.get(4).asDouble(), now});
                // klines: [7] — оборот в котируемой валюте, [8] — число сделок (док. 131 §7 п.4)
                liq.add(new Object[]{base, day(openTime), c.get(7).asDouble(0), c.get(8).asLong(0), now});
                last = openTime;
            }
            if (kl.size() < 1500) {
                break;
            }
            start = last + DAY_MS;
        }
        db.batch("INSERT OR REPLACE INTO s5_price(base, day, open, high, low, close, imported_ms) "
                + "VALUES(?,?,?,?,?,?,?)", rows);
        db.batch("INSERT OR REPLACE INTO s5_liquidity(base, day, quote_volume, trades, imported_ms) "
                + "VALUES(?,?,?,?,?)", liq);
    }

    private void importFunding(String base) {
        long start = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, Double> daily = new HashMap<>();
        for (int page = 0; page < 12; page++) {
            JsonNode fr = get("https://fapi.binance.com/fapi/v1/fundingRate?symbol=" + base
                    + "USDT&startTime=" + start + "&limit=1000");
            if (!fr.isArray() || fr.isEmpty()) {
                break;
            }
            long last = start;
            for (JsonNode row : fr) {
                long ts = row.path("fundingTime").asLong();
                daily.merge(day(ts), row.path("fundingRate").asDouble(), Double::sum);
                last = ts;
            }
            if (fr.size() < 1000) {
                break;
            }
            start = last + 1;
        }
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        daily.forEach((d, rate) -> rows.add(new Object[]{base, d, rate, now}));
        db.batch("INSERT OR REPLACE INTO s5_funding_daily(base, day, rate_sum, imported_ms) VALUES(?,?,?,?)", rows);
    }

    private static String day(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString();
    }

    private static JsonNode get(String url) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "curl/8.0")
                        .timeout(Duration.ofSeconds(60))
                        .GET().build();
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return MAPPER.readTree(r.body());
                }
                if (r.statusCode() == 429 || r.statusCode() == 418 || r.statusCode() >= 500) {
                    Thread.sleep(1500L * (attempt + 1));
                    continue;
                }
                throw new IllegalStateException("HTTP " + r.statusCode() + " " + url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("прервано", e);
            } catch (IOException e) {
                last = new IllegalStateException("IO " + url, e);
            }
        }
        throw last == null ? new IllegalStateException("исчерпаны ретраи: " + url) : last;
    }
}
