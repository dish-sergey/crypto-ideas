package org.home.data.revx.exec;

import org.home.data.revx.replay.ReplayFair;
import org.home.data.revx.replay.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ОБЕ ОПОРЫ В ЖУРНАЛЕ (27.10.2026, док. 216): «своя книга без поводка» и «Бинанс × базис» пишутся в
 * {@code exec_quote.ref_own / ref_bnb}. Без них «дальнюю из двух» и поводок нельзя разобрать задним числом —
 * в {@code fair} лежит их смесь.
 */
class QuoteRefsTest {

    @Test
    void старыйЖурналПолучаетКолонкиИКотировкаПишетОбеОпоры(@TempDir Path dir) throws Exception {
        String path = dir.resolve("j.db").toString();
        // журнал в схеме до 27.10.2026 — без колонок опор
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE exec_quote (ts_ms INTEGER NOT NULL, fair REAL, bid REAL, ask REAL,"
                    + " inventory REAL, quotable INTEGER NOT NULL, reason TEXT, pressure REAL)");
            st.execute("INSERT INTO exec_quote VALUES (1, 100, 99, 101, 0, 1, NULL, 0)");
        }
        try (ExecJournal j = new ExecJournal(path)) {
            j.quote(100.0, 99.0, 101.0, 0, true, null, 0, new double[]{100.2, 99.9});
            j.quote(100.0, 99.0, 101.0, 0, true, null, 0, null);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             ResultSet rs = c.createStatement().executeQuery("SELECT ref_own, ref_bnb FROM exec_quote ORDER BY rowid")) {
            rs.next();
            assertNull(rs.getObject(1), "старая строка — пусто");
            rs.next();
            assertEquals(100.2, rs.getDouble(1), 1e-12);
            assertEquals(99.9, rs.getDouble(2), 1e-12);
            rs.next();
            assertNull(rs.getObject(1), "источник опор не знает — пусто, а не ноль");
        }
    }

    @Test
    void повторОтдаётОпорыКакЕсть_аДальняяМеньшуюИБольшую() {
        SimClock clock = new SimClock(1000);
        ReplayFair f = new ReplayFair(List.of(new ReplayFair.Tick(1000, 100, null, null, 0, true, null, 0)), clock)
                .withSides(new double[][]{{100.2}, {99.9}});
        assertArrayEquals(new double[]{100.2, 99.9}, f.rawRefs(), 1e-12);
        assertArrayEquals(new double[]{99.9, 100.2}, f.sideRefs(), 1e-12);
    }
}
