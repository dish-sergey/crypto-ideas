package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СТОРОЖ СВИПОВ: склейка цепочки, знак сдвига и его ЗАТУХАНИЕ.
 *
 * <h2>Зачем тест</h2>
 *
 * У этой реакции три способа тихо сломаться, и все три стоят денег на живом
 * счёте:
 * <ul>
 *   <li><b>знак</b>. Свип вниз должен опускать опору; перепутанный знак
 *       подставит бид ровно под продолжение падения — то есть сделает хуже, чем
 *       было, и ровно на измеренную величину;</li>
 *   <li><b>затухание</b>. Информация свипа живёт минуту-две и к пятнадцатой
 *       минуте исчезает (`t` = 0.72, задача A84). Если сдвиг не затухает, бот
 *       держит перекос там, где эффекта уже нет;</li>
 *   <li><b>склейка</b>. Свип — это цепочка принтов в пределах 100 мс; если
 *       склейки нет, крупный заказ рассыпается на мелкие и порог не берётся
 *       никогда.</li>
 * </ul>
 */
class SweepWatchTest {

    private static String db(Path dir, String name) throws Exception {
        Path p = dir.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE revx_trade(trade_id TEXT, symbol TEXT, ts_ms INT,"
                    + " price REAL, qty REAL, side TEXT, ingest_ms INT)");
        }
        return p.toString();
    }

    private static void print(String path, long ts, double price, double qty, String side)
            throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO revx_trade(symbol, ts_ms, price, qty, side) VALUES ('BTC/USDC',"
                    + ts + ", " + price + ", " + qty + ", '" + side + "')");
        }
    }

    @Test
    void свипВнизОпускаетОпоруИСдвигЗатухает(@TempDir Path dir) throws Exception {
        String path = db(dir, "tape.db");
        // Четыре принта продавца в пределах 100 мс: по отдельности мелкие,
        // вместе — $2000, то есть крупный свип.
        long t0 = 1_000_000_000_000L;
        for (int i = 0; i < 4; i++) {
            print(path, t0 + i * 20L, 100_000, 0.005, "sell");
        }
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6)) {
            long end = t0 + 60L;
            double at1s = watch.shiftBp(end + 1_000);
            double at30s = watch.shiftBp(end + 30_000);
            double at5m = watch.shiftBp(end + 300_000);

            assertTrue(at1s < 0, "свип ВНИЗ обязан опускать опору, а вышло " + at1s);
            // Через секунду остаётся почти весь путь: 3.97 − 0.61.
            assertEquals(-3.36, at1s, 0.05, "сдвиг через секунду");
            // Через полминуты — заметно меньше: 3.97 − 1.82.
            assertEquals(-2.15, at30s, 0.05, "сдвиг через полминуты");
            assertTrue(Math.abs(at30s) < Math.abs(at1s), "сдвиг обязан затухать");
            assertEquals(0.0, at5m, 1e-9, "к пяти минутам эффекта нет — сдвига тоже");
        }
    }

    @Test
    void безСклейкиПорогНеБерётсяВовсе(@TempDir Path dir) throws Exception {
        String path = db(dir, "tape2.db");
        long t0 = 1_000_000_000_000L;
        // Те же четыре принта, но врозь: полсекунды между ними.
        for (int i = 0; i < 4; i++) {
            print(path, t0 + i * 500L, 100_000, 0.005, "sell");
        }
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6)) {
            assertEquals(0.0, watch.shiftBp(t0 + 2_000), 1e-9,
                    "принты врозь — это не свип, сдвига быть не должно");
        }
    }

    @Test
    void потолокОграничиваетСдвиг(@TempDir Path dir) throws Exception {
        String path = db(dir, "tape3.db");
        long t0 = 1_000_000_000_000L;
        print(path, t0, 100_000, 1.0, "buy");   // $100 000 — заведомо крупный
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 10.0, 6)) {
            double bp = watch.shiftBp(t0 + 1_000);
            assertEquals(6.0, bp, 1e-9, "потолок обязан срезать даже при большой доле");
        }
    }

    @Test
    void криваяПутиМонотоннаИНасыщается() {
        assertTrue(SweepWatch.path(0.5) < SweepWatch.path(5), "путь растёт");
        assertTrue(SweepWatch.path(5) < SweepWatch.path(60), "путь растёт");
        assertEquals(3.97, SweepWatch.path(300), 1e-9, "к пяти минутам путь пройден весь");
        assertEquals(3.97, SweepWatch.path(3600), 1e-9, "дальше не растёт");
    }
}
