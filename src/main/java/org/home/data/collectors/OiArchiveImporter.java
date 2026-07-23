package org.home.data.collectors;

import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * Ретро-OI из bulk-архива Binance Vision (док. 09 §2.2, §4.1). Суточные zip-дампы
 * {@code metrics} с 2021-01 отдают 5-минутные OI + long/short ratios; закрывают
 * дыру между началом истории и 30-дневным окном живого коллектора.
 *
 * CSV: create_time, symbol, sum_open_interest, sum_open_interest_value, ...ratios.
 * Пишем в open_interest (exchange='binance') — тот же PK, что у живого коллектора,
 * поэтому пересечение окон идемпотентно. available_at = ts (данные исторические).
 *
 * Разовый бэкфилл: --backfill=oi-archive --symbols=BTCUSDT,ETHUSDT --from=2021-01-01
 */
@Component
public class OiArchiveImporter {

    private static final Logger log = LoggerFactory.getLogger(OiArchiveImporter.class);

    private static final String BASE = "https://data.binance.vision/data/futures/um/daily/metrics";
    private static final DateTimeFormatter CREATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String UPSERT = """
            INSERT OR REPLACE INTO open_interest(exchange, symbol, ts, oi_contracts, oi_usd, available_at)
            VALUES(?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;

    public OiArchiveImporter(Db db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    public void backfill(List<String> symbols, String fromDay) {
        LocalDate from = LocalDate.parse(fromDay);
        LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(2); // архив с лагом ~1–2 суток
        for (String symbol : symbols) {
            long total = 0;
            int missing = 0;
            int skipped = 0;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (alreadyHave(symbol, day)) {
                    skipped++;
                    continue; // живой коллектор или прошлый прогон уже закрыли этот день
                }
                String url = BASE + "/" + symbol + "/" + symbol + "-metrics-" + day + ".zip";
                byte[] zip;
                try {
                    zip = api.getBytes(url);
                } catch (ApiClient.ApiException e) {
                    missing++; // день отсутствует в архиве (404) — пропускаем
                    continue;
                }
                total += db.batch(UPSERT, parse(zip, symbol));
            }
            log.info("oi-archive {}: +{} точек, дней пропущено (уже есть) {}, отсутствует в архиве {}",
                    symbol, total, skipped, missing);
        }
    }

    private boolean alreadyHave(String symbol, LocalDate day) {
        long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Long cnt = db.queryLong(
                "SELECT COUNT(*) FROM open_interest WHERE exchange='binance' AND symbol=? AND ts>=? AND ts<?",
                symbol, dayStart, dayStart + 86_400_000L);
        return cnt != null && cnt > 0;
    }

    /** Распаковка zip в памяти и парсинг единственной CSV-записи. */
    private List<Object[]> parse(byte[] zip, String symbol) {
        List<Object[]> rows = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            if (zis.getNextEntry() == null) {
                return rows;
            }
            String csv = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = csv.split("\n");
            for (int i = 1; i < lines.length; i++) { // строка 0 — заголовок
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] c = line.split(",");
                if (c.length < 4 || c[2].isEmpty() || c[3].isEmpty()) {
                    continue;
                }
                long ts = LocalDateTime.parse(c[0], CREATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
                rows.add(new Object[]{"binance", symbol, ts,
                        Double.parseDouble(c[2]), Double.parseDouble(c[3]), ts});
            }
        } catch (IOException | RuntimeException e) {
            log.warn("oi-archive {}: ошибка разбора zip: {}", symbol, e.getMessage());
        }
        return rows;
    }
}
