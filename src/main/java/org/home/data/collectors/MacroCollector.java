package org.home.data.collectors;

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
 * Макро-серии для C4 из FRED CSV без ключа (док. 09 §2.5, проверен живым запросом):
 * DFF (ставка), WALCL (баланс ФРС), DTWEXBGS (broad dollar = прокси DXY), CPIAUCSL.
 * Качается вся серия целиком (объём копеечный), upsert перезаписывает ревизии.
 * available_at = день + 1 сутки (пессимистичная модель лага публикации).
 */
@Component
public class MacroCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(MacroCollector.class);

    private static final String BASE = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=";

    private static final String UPSERT = """
            INSERT OR REPLACE INTO macro_series(series_id, day, value, available_at)
            VALUES(?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final List<String> series;

    public MacroCollector(Db db, ApiClient api,
                          @Value("${collectors.fred-series}") List<String> series) {
        this.db = db;
        this.api = api;
        this.series = series;
    }

    @Override
    public String name() {
        return "macro";
    }

    @Override
    public void collect() {
        for (String id : series) {
            String csv = api.get(BASE + id);
            List<Object[]> rows = parseCsv(id, csv);
            int written = db.batch(UPSERT, rows);
            log.info("macro {}: {} точек", id, written);
        }
    }

    /**
     * FRED CSV: заголовок {@code observation_date,SERIES}, далее строки {@code YYYY-MM-DD,value};
     * пропущенные значения обозначены точкой.
     */
    static List<Object[]> parseCsv(String seriesId, String csv) {
        List<Object[]> rows = new ArrayList<>();
        for (String line : csv.split("\r?\n")) {
            int comma = line.indexOf(',');
            if (comma != 10) {
                continue; // заголовок или мусор: дата всегда 10 символов
            }
            String day = line.substring(0, comma);
            String value = line.substring(comma + 1).trim();
            if (value.isEmpty() || ".".equals(value)) {
                continue;
            }
            try {
                double v = Double.parseDouble(value);
                long availableAt = Instant.parse(day + "T00:00:00Z").plus(Duration.ofDays(1)).toEpochMilli();
                rows.add(new Object[]{seriesId, day, v, availableAt});
            } catch (NumberFormatException | java.time.format.DateTimeParseException ignore) {
                // строка не является наблюдением
            }
        }
        return rows;
    }
}
