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
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6, 0)) {
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
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6, 0)) {
            assertEquals(0.0, watch.shiftBp(t0 + 2_000), 1e-9,
                    "принты врозь — это не свип, сдвига быть не должно");
        }
    }

    @Test
    void потолокОграничиваетСдвиг(@TempDir Path dir) throws Exception {
        String path = db(dir, "tape3.db");
        long t0 = 1_000_000_000_000L;
        print(path, t0, 100_000, 1.0, "buy");   // $100 000 — заведомо крупный
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 10.0, 6, 0)) {
            double bp = watch.shiftBp(t0 + 1_000);
            assertEquals(6.0, bp, 1e-9, "потолок обязан срезать даже при большой доле");
        }
    }

    /**
     * 🔑 ГЛАВНЫЙ ТЕСТ ДЛЯ СТЕНДА: будущие принты не видны.
     *
     * Живьём этой беды нет — будущего в базе просто не лежит. В ПОВТОРЕ лежит
     * вся запись целиком, а часы симулированные, и запрос без верхней границы
     * вернул бы свип, которого в этот момент ещё не было. Стенд показал бы
     * блестящий результат, невозможный живьём, и мы бы поверили.
     */
    @Test
    void будущиеПринтыНеВидны(@TempDir Path dir) throws Exception {
        String path = db(dir, "future.db");
        long t0 = 1_000_000_000_000L;
        for (int i = 0; i < 4; i++) {
            print(path, t0 + i * 20L, 100_000, 0.005, "sell");
        }
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6, 0)) {
            assertEquals(0.0, watch.shiftBp(t0 - 1_000), 1e-9,
                    "за секунду ДО свипа сдвига быть не может");
            assertTrue(watch.shiftBp(t0 + 1_000) < 0, "а после свипа — должен");
        }
    }

    /** Задержка ленты: принт моложе неё бот ещё не видит. */
    @Test
    void задержкаЛентыОтодвигаетРеакцию(@TempDir Path dir) throws Exception {
        String path = db(dir, "delay.db");
        long t0 = 1_000_000_000_000L;
        for (int i = 0; i < 4; i++) {
            print(path, t0 + i * 20L, 100_000, 0.005, "sell");
        }
        try (SweepWatch watch = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6, 2_000)) {
            assertEquals(0.0, watch.shiftBp(t0 + 1_000), 1e-9,
                    "через секунду принт ещё не доехал при задержке в две");
            assertTrue(watch.shiftBp(t0 + 3_000) < 0, "через три секунды — уже виден");
        }
    }

    /**
     * 🔑 ЛЕНТА В ПАМЯТИ И ЗАПРОСОМ — ОДИН ПРИБОР, А НЕ ДВА.
     *
     * Предзагрузка заведена 21.09.2026 потому, что у {@code revx_trade} нет ни
     * одного индекса: запрос на каждом тике давал полный скан таблицы, прогон
     * переставал укладываться в предохранитель и печатал ОБРЕЗАННЫЙ результат
     * (36 сделок на свободной машине против 17 на занятой). Ускорение не имеет
     * права менять ответ, поэтому оба пути сверяются на одной ленте в каждой
     * точке.
     */
    @Test
    void лентаВПамятиДаётТоЖе(@TempDir Path dir) throws Exception {
        String path = db(dir, "equiv.db");
        long t0 = 1_000_000_000_000L;
        // Три разные цепочки: крупная продажа, мелочь врозь, крупная покупка.
        for (int i = 0; i < 4; i++) {
            print(path, t0 + i * 20L, 100_000, 0.005, "sell");
        }
        for (int i = 0; i < 3; i++) {
            print(path, t0 + 30_000 + i * 700L, 100_000, 0.004, "buy");
        }
        for (int i = 0; i < 5; i++) {
            print(path, t0 + 90_000 + i * 15L, 100_000, 0.006, "buy");
        }
        try (SweepWatch query = new SweepWatch(path, "BTC/USDC", 1633, 100, 1.0, 6, 0);
             SweepWatch memory = SweepWatch.preloaded(path, "BTC/USDC", 1633, 100, 1.0, 6, 0,
                     t0, t0 + 400_000)) {
            for (long dt = 0; dt <= 350_000; dt += 250) {
                long now = t0 + dt;
                assertEquals(query.shiftBp(now), memory.shiftBp(now), 1e-12,
                        "сдвиг разошёлся на " + dt + " мс от начала");
            }
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
