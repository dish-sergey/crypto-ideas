package org.home.data.revx.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/** Живая лента тейкеров (01.11.2026): та же формула окна, что у стенда, и дочитывание новых принтов. */
class TakerFlowLiveTest {

    @Test
    void liveMatchesPreloadedAndSeesNewPrints() throws Exception {
        // не @TempDir: на Windows живое соединение ленты держит файл, и уборка каталога падала бы
        Path db = Files.createTempDirectory("takerflow").resolve("t.db");
        db.toFile().deleteOnExit();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE revx_trade (symbol TEXT, ts_ms INTEGER, price REAL, qty REAL, side TEXT)");
            s.execute("INSERT INTO revx_trade VALUES ('BTC/USDC', 1000000, 100, 1, 'buy'),"
                    + " ('BTC/USDC', 1030000, 100, 2, 'sell'), ('BTC/USDC', 1050000, 100, 3, 'buy'),"
                    + " ('ETH/USDC', 1050000, 100, 9, 'buy')");
        }
        long now = 1_060_000;
        TakerFlow pre = TakerFlow.preloaded(db.toString(), "BTC/USDC", 0, 0, Long.MAX_VALUE);
        TakerFlow live = TakerFlow.live(db.toString(), "BTC/USDC", 3000);
        for (long w : new long[]{20_000, 60_000, 3_600_000}) {
            assertEquals(pre.usd(true, now, w), live.usd(true, now, w), 1e-9);
            assertEquals(pre.usd(false, now, w), live.usd(false, now, w), 1e-9);
        }
        assertEquals(400, live.usd(true, now, 3_600_000), 1e-9);
        assertEquals(300, live.usd(true, now, 20_000), 1e-9);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO revx_trade VALUES ('BTC/USDC', 1061000, 100, 5, 'buy')");
        }
        // раньше refreshMs не перечитывает, потом видит новый принт
        assertEquals(400, live.usd(true, now + 1000, 3_600_000), 1e-9);
        assertEquals(900, live.usd(true, now + 4000, 3_600_000), 1e-9);
        assertEquals(true, Files.exists(db));
    }
}
