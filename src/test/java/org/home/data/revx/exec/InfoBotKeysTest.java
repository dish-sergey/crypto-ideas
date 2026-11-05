package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сторож ключей API: будит при 401/403 дольше пяти минут без успехов, повтор раз в час (05.11.2026). */
class InfoBotKeysTest {

    private static final long MIN = 60_000L;

    private static String journal(Path dir, long now, long[][] rows) throws Exception {
        String path = dir.resolve("j.db").toString();
        try (ExecJournal j = new ExecJournal(path)) {
            j.event("boot", "SOL/USDC | {}");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = c.createStatement()) {
            for (long[] r : rows) {
                st.execute("INSERT INTO exec_request(ts_ms, method, path, status, latency_ms) VALUES("
                        + (now - r[0] * MIN) + ", 'GET', '/orders/active', " + r[1] + ", 1)");
            }
        }
        return path;
    }

    @Test
    void отказыДольшеПятиМинутБезУспеха(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        InfoBot.KeyState k = InfoBot.keyState(journal(dir, now, new long[][]{{9, 200}, {7, 403}, {1, 403}}), now);
        assertTrue(k.bad(now));
        assertEquals(403, k.badStatus());
        assertEquals(2, k.badCount());
    }

    @Test
    void короткиеОтказыНеТревога(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        assertFalse(InfoBot.keyState(journal(dir, now, new long[][]{{9, 200}, {2, 401}, {1, 401}}), now).bad(now));
    }

    @Test
    void успехПослеОтказаСнимает(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        assertFalse(InfoBot.keyState(journal(dir, now, new long[][]{{20, 403}, {10, 403}, {1, 200}}), now).bad(now));
    }

    @Test
    void чужиеОтказыНеКлючи(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        assertFalse(InfoBot.keyState(journal(dir, now, new long[][]{{20, 429}, {10, 500}, {1, 404}}), now).bad(now));
    }

}
