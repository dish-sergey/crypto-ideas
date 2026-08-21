package org.home.data.theory.ou;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.Db;
import org.home.data.theory.Jlog;
import org.home.data.theory.TheoryDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Импорт минутных свечей перпа Binance для базиса спот–перп <b>одной площадки</b>.
 *
 * <p>Зачем отдельный импортёр (П1 док. 71): первый прогон ТЗ 67 мерил базис как
 * марк-цену перпа Kraken против спота Binance по пятиминутным снимкам тикера.
 * Это кросс-площадочная величина с другим механизмом привязки, и её разрешения
 * (5 минут) не хватает: отклонения базиса живут минуты — на пятиминутной сетке
 * они усредняются, и σ = 2 б.п. получится независимо от того, есть эффект или нет.
 *
 * <p>Здесь спот и перп берутся с одной площадки и на одной сетке: свеча спота из
 * {@code crypto.db} и свеча перпа из fapi имеют <b>одинаковый close_time</b>,
 * поэтому рассинхронизация ног равна нулю по построению, а не «уложилась в
 * допуск». Доля минут без пары всё равно считается и попадает в шапку отчёта.
 *
 * <p>CLI: {@code --theory=basis-import}.
 */
@Component
@Lazy
public class PerpMinuteImporter {

    private static final Logger log = LoggerFactory.getLogger(PerpMinuteImporter.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Спотовый эндпоинт Binance принимает не больше 1000 свечей за запрос (fapi — 1500);
    // единый лимит 1000 нужен, чтобы условие «пришло меньше запрошенного — данные
    // кончились» не срабатывало ложно на спотовой ноге и не обрывало окно на 1000-й минуте.
    private static final int LIMIT = 1000;

    private final Db db;
    private final TheoryDb theoryDb;

    public PerpMinuteImporter(Db db, TheoryDb theoryDb) {
        this.db = db;
        this.theoryDb = theoryDb;
    }

    /** Тянет минутные свечи перпа на том же окне, что покрыто минутным спотом в crypto.db. */
    public void run(List<String> symbols) {
        for (String symbol : symbols) {
            Long from = db.queryLong("SELECT min(open_time) FROM candles WHERE symbol=? AND interval='1m'",
                    symbol);
            Long to = db.queryLong("SELECT max(open_time) FROM candles WHERE symbol=? AND interval='1m'",
                    symbol);
            if (from == null || to == null) {
                Jlog.warn(log, "basis.import.skip",
                        Map.of("symbol", symbol, "reason", "нет минутного спота в crypto.db"));
                continue;
            }
            int rows = importRange("perp_1m", "https://fapi.binance.com/fapi/v1/klines", symbol, from, to);
            Jlog.info(log, "basis.import", Map.of("symbol", symbol, "rows", rows,
                    "from_ms", from, "to_ms", to));
        }
    }

    /**
     * Стресс-выборка: обе ноги (спот и перп) на минутной сетке в дни наибольшей
     * волатильности. Именно там док. 61 §2.3 помещает отклонения базиса выше
     * порога входа — «арбитражёры упираются в лимиты на всплесках». Спокойное
     * недельное окно этот вопрос не решает: там отклонений быть и не должно.
     *
     * @param days сколько самых волатильных дней взять (по |дневной доходности|
     *             и по внутридневному размаху, объединение)
     */
    public void runStress(List<String> symbols, int days, String from) {
        for (String symbol : symbols) {
            List<long[]> windows = stressDays(symbol, days, from);
            int spot = 0;
            int perp = 0;
            for (long[] w : windows) {
                spot += importRange("spot_1m", "https://api.binance.com/api/v3/klines", symbol, w[0], w[1]);
                perp += importRange("perp_1m", "https://fapi.binance.com/fapi/v1/klines", symbol, w[0], w[1]);
            }
            Jlog.info(log, "basis.import.stress", Map.of("symbol", symbol, "windows", windows.size(),
                    "spot_rows", spot, "perp_rows", perp));
        }
    }

    /**
     * Непрерывная минутная история обеих ног с заданной даты. Нужна, чтобы у
     * ряда базиса были <b>календарно честная</b> частота эпизодов и применимые
     * тесты стационарности: на сшитой стресс-выборке ни того, ни другого нет.
     */
    public void runContinuous(List<String> symbols, String from) {
        long start = java.time.LocalDate.parse(from).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        long end = System.currentTimeMillis();
        for (String symbol : symbols) {
            int spot = importRange("spot_1m", "https://api.binance.com/api/v3/klines", symbol, start, end);
            int perp = importRange("perp_1m", "https://fapi.binance.com/fapi/v1/klines", symbol, start, end);
            Jlog.info(log, "basis.import.continuous", Map.of("symbol", symbol, "from", from,
                    "spot_rows", spot, "perp_rows", perp));
        }
    }

    /** Дни с наибольшим |ходом| и наибольшим размахом — объединение множеств. */
    private List<long[]> stressDays(String symbol, int days, String from) {
        record Day(long openTime, double move, double range) {
        }
        List<Day> all = db.query("SELECT open_time, open, high, low, close FROM candles "
                + "WHERE symbol=? AND interval='1d' AND date(open_time/1000,'unixepoch') >= ? "
                + "ORDER BY open_time", rs -> {
            double open = rs.getDouble(2);
            double close = rs.getDouble(5);
            double range = open > 0 ? (rs.getDouble(3) - rs.getDouble(4)) / open : 0;
            double move = open > 0 ? Math.abs(close / open - 1) : 0;
            return new Day(rs.getLong(1), move, range);
        }, symbol, from);

        java.util.LinkedHashSet<Long> picked = new java.util.LinkedHashSet<>();
        all.stream().sorted((a, b) -> Double.compare(b.move(), a.move())).limit(days)
                .forEach(d -> picked.add(d.openTime()));
        all.stream().sorted((a, b) -> Double.compare(b.range(), a.range())).limit(days)
                .forEach(d -> picked.add(d.openTime()));
        List<long[]> windows = new ArrayList<>();
        for (long open : picked) {
            windows.add(new long[]{open, open + 86_400_000L - 60_000L});
        }
        return windows;
    }

    private int importRange(String table, String endpoint, String symbol, long from, long to) {
        long start = from;
        int total = 0;
        long now = System.currentTimeMillis();
        while (start <= to) {
            JsonNode klines = get(endpoint + "?symbol=" + symbol
                    + "&interval=1m&startTime=" + start + "&endTime=" + to + "&limit=" + LIMIT);
            if (!klines.isArray() || klines.isEmpty()) {
                break;
            }
            List<Object[]> rows = new ArrayList<>();
            long lastOpen = start;
            for (JsonNode c : klines) {
                lastOpen = c.get(0).asLong();
                rows.add(new Object[]{symbol, c.get(6).asLong(), c.get(4).asDouble(), now});
            }
            total += theoryDb.batch("INSERT OR REPLACE INTO " + table
                    + "(symbol, close_time, close, imported_ms) VALUES(?,?,?,?)", rows);
            if (klines.size() < LIMIT) {
                break;
            }
            start = lastOpen + 60_000L;
        }
        return total;
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
