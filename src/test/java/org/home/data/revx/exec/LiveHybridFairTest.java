package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живая опора «смесь + глубина + Бинанс»: аварийный режим при сбое источника и
 * возврат после нормы — это работает на живых деньгах, проверяется отдельно.
 */
class LiveHybridFairTest {

    @TempDir
    Path dir;

    private void book(String db, long ts) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS revx_book (symbol TEXT, t_recv_ms INTEGER,"
                    + " bp1 REAL,bq1 REAL,bp2 REAL,bq2 REAL,bp3 REAL,bq3 REAL,bp4 REAL,bq4 REAL,bp5 REAL,bq5 REAL,"
                    + " ap1 REAL,aq1 REAL,ap2 REAL,aq2 REAL,ap3 REAL,aq3 REAL,ap4 REAL,aq4 REAL,ap5 REAL,aq5 REAL,"
                    + " deep_bids TEXT, deep_asks TEXT)");
            // пыль на верхушке (по 1 монете), объём глубже
            st.execute("INSERT INTO revx_book VALUES ('XRP/USDC'," + ts
                    + ",1.4990,1, 1.4980,500, 1.4970,500, 0,0, 0,0,"
                    + " 1.5010,1, 1.5030,500, 1.5040,500, 0,0, 0,0, '', '')");
        }
    }

    private void bnb(String db, long ts, double mid) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS bnb_tick (symbol TEXT, ts_ms INTEGER, bid REAL, ask REAL)");
            st.execute("INSERT INTO bnb_tick VALUES ('XRPUSDC'," + ts + "," + (mid - 0.0001) + "," + (mid + 0.0001) + ")");
        }
    }

    @Test
    void degradesOnSilentBinanceAndRecoversAfterNorm() throws Exception {
        String standDb = dir.resolve("stand.db").toString();
        String bnbDb = dir.resolve("bnb.db").toString();
        System.setProperty("revx.bnb.db", bnbDb);
        System.setProperty("revx.fair.hybrid-recover-ms", "0");
        System.setProperty("revx.fair.hybrid-mix", "0.25");
        System.setProperty("revx.fair.hybrid-depth-k", "10");
        FairSource old = (base, lb) -> new StandReader.Fair(1.5000, true, null, System.currentTimeMillis(), 8);
        ExecJournal j = new ExecJournal(dir.resolve("j.db").toString());
        long now = System.currentTimeMillis();
        book(standDb, now);
        bnb(bnbDb, now, 1.5000);
        LiveHybridFair h = new LiveHybridFair(old, standDb, "XRP/USDC", 50, j);

        StandReader.Fair f1 = h.latest("XRP", 30_000);
        assertFalse(h.degraded());
        // глубина 10 лотов × 50 = 500 монет: пыль на верхушке пропущена, середина 1.4980/1.5030
        assertEquals(0.25 * 1.5 + 0.75 * (1.4980 + 1.5030) / 2, f1.price(), 1e-9);

        bnb(bnbDb, now - 60_000, 1.5);                 // Бинанс «замолчал»: свежей записи нет
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bnbDb); Statement st = c.createStatement()) {
            st.execute("DELETE FROM bnb_tick WHERE ts_ms >= " + now);
        }
        StandReader.Fair f2 = h.latest("XRP", 30_000);
        assertTrue(h.degraded(), "молчащий Бинанс обязан включить аварийный режим");
        assertTrue(h.degradedWhy().contains("Бинанс"));
        assertTrue(f2.price() > 0, "аварийная опора — уровень по своей книге");
        assertEquals("hybrid_degraded", j.lastEventOf("hybrid_degraded", "hybrid_recovered")[0]);

        bnb(bnbDb, System.currentTimeMillis(), 1.5010);
        h.latest("XRP", 30_000);                         // первый здоровый тик — отсчёт нормы
        h.latest("XRP", 30_000);                         // норма длится (порог 0 мс) — возврат
        assertFalse(h.degraded());
        assertEquals("hybrid_recovered", j.lastEventOf("hybrid_degraded", "hybrid_recovered")[0]);
        // После возврата среднее Бинанса начинается заново (опора не прыгает), а
        // следующий его ход снова двигает опору.
        double calm = h.latest("XRP", 30_000).price();
        bnb(bnbDb, System.currentTimeMillis() + 1, 1.5160);   // +1% на Бинансе
        double moved = h.latest("XRP", 30_000).price();
        assertTrue(moved > calm * 1.005, "ход Бинанса обязан двигать опору сразу: " + calm + " → " + moved);
        h.close();
        j.close();
    }
}
