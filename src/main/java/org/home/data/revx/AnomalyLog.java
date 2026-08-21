package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Журнал аномалий. ТЗ §8: аномалию нельзя чинить на лету — она обязана попасть
 * в журнал и в отчёт (нарушенный инвариант книги, порядок уровней, skew выше
 * порога, 429, появление/исчезновение пары).
 */
@Component
@Lazy
public class AnomalyLog {

    private static final Logger log = LoggerFactory.getLogger(AnomalyLog.class);

    private static final String INSERT = """
            INSERT OR REPLACE INTO revx_anomaly(ts_ms, symbol, kind, detail) VALUES(?,?,?,?)
            """;

    private final RevxDb db;

    public AnomalyLog(RevxDb db) {
        this.db = db;
    }

    public void record(String kind, String symbol, String detail) {
        db.upsert(INSERT, System.currentTimeMillis(), symbol == null ? "" : symbol, kind, detail);
        log.warn("аномалия [{}] {}: {}", kind, symbol == null ? "-" : symbol, detail);
    }
}
