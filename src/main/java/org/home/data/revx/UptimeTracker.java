package org.home.data.revx;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Поминутная телеметрия потоков. Из неё считается аптайм коллектора и доля 429
 * в разделе «Данные» отчёта (ТЗ §5.1): аптайм ниже 95% делает отчёт непригодным
 * для выводов, поэтому цифра должна быть измеренной, а не декларируемой.
 *
 * Пишем сразу, инкрементом в минутную корзину: копить в памяти нельзя — при
 * падении процесса потерялась бы именно та минута, ради которой всё считается.
 */
@Component
@Lazy
public class UptimeTracker {

    private static final String UPSERT = """
            INSERT INTO revx_uptime(minute_ms, stream, requests, ok, http_429, failures, records)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(minute_ms, stream) DO UPDATE SET
                requests = requests + excluded.requests,
                ok       = ok       + excluded.ok,
                http_429 = http_429 + excluded.http_429,
                failures = failures + excluded.failures,
                records  = records  + excluded.records
            """;

    private final RevxDb db;

    public UptimeTracker(RevxDb db) {
        this.db = db;
    }

    public void record(String stream, int requests, int ok, int http429, int failures, int records) {
        long minute = System.currentTimeMillis() / 60_000 * 60_000;
        db.upsert(UPSERT, minute, stream, requests, ok, http429, failures, records);
    }
}
