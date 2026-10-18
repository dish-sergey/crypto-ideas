package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Тишина в покупках: считаются только покупки, дошедшие до опоры НА МОМЕНТ ПРИНТА + X, в окне до задержки. */
class BuyQuietTest {

    @TempDir
    Path dir;

    private String db() throws Exception {
        Path p = dir.resolve("t.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE revx_trade(trade_id TEXT, symbol TEXT, ts_ms INT, price REAL, qty REAL, side TEXT, ingest_ms INT)");
            // опора 100 до 50 с, потом 90; X = 10 б.п. → порог 100.1, потом 90.09
            st.execute("INSERT INTO revx_trade VALUES"
                    + "('1','SOL/USDC',10000,100.2,1,'buy',0),"    // дошла
                    + "('2','SOL/USDC',20000,100.05,1,'buy',0),"   // не дошла
                    + "('3','SOL/USDC',30000,100.3,1,'sell',0),"   // продажа — не в счёт
                    + "('4','SOL/USDC',60000,95.0,1,'buy',0),"     // при опоре 90 — дошла, хотя ниже прежней опоры
                    + "('5','SOL/USDC',99000,95.0,1,'buy',0),"     // моложе задержки — ещё не видна
                    + "('6','ETH/USDC',10000,200,1,'buy',0)");     // чужая пара
        }
        return p.toString();
    }

    @Test
    void countsReachAgainstFairAtPrintTime() throws Exception {
        try (BuyQuiet q = BuyQuiet.preloaded(db(), "SOL/USDC", 2000, 0, 200_000)) {
            BuyQuiet.FairAt fair = t -> t < 50_000 ? 100 : 90;
            assertEquals(2, q.reached(100_000, 100_000, 10, fair));
            // окно 50 с до 98 с: только принт 4
            assertEquals(1, q.reached(100_000, 48_000, 10, fair));
            // через 2 с после принта 5 он виден
            assertEquals(3, q.reached(101_500, 100_000, 10, fair));
        }
    }
}
