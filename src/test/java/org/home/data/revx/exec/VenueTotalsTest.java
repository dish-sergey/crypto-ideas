package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * НАДКУШЕННАЯ ЗАЯВКА СЧИТАЕТСЯ ЦЕЛИКОМ, ДАЖЕ ЕСЛИ ПЕРВЫЕ УКУСЫ ВЫПАЛИ ИЗ ОКНА.
 *
 * 10.10.2026 заявку d (продажа 0.00074992 ETH) надкусили шесть раз по $0.10 за
 * полчаса, потом пришёл остаток. Быстрый путь ленты складывал сделки только за
 * последние 30 минут и вычитал из этой суммы НАКОПИТЕЛЬНО записанное — первый укус
 * уже выпал из окна, и остаток записался на укус меньше (0.0004702 вместо
 * 0.00051016). Хвост добрала сверка через пять минут тревогой «обычный путь его
 * пропустил». Окно обязано отбирать заявки, а не сделки.
 */
class VenueTotalsTest {

    @Test
    void старыеУкусыВходятВИтогЗаявки() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE trade (tid TEXT PRIMARY KEY, oid TEXT, symbol TEXT, side TEXT, "
                    + "qty REAL, price REAL, tdt INTEGER, seen_ms INTEGER, maker INTEGER)");
            s.execute("CREATE TABLE order_info (oid TEXT PRIMARY KEY, bot TEXT, status TEXT)");
            s.execute("INSERT INTO order_info VALUES ('o1', 'd', 'filled'), ('old', 'd', 'filled')");
            long now = 10_000_000_000L;
            long min = 60_000L;
            // шесть укусов: первый 31 мин назад — за окном в 30 мин
            long[] bites = {31, 22, 18, 13, 9, 4};
            int i = 0;
            for (long ago : bites) {
                s.execute("INSERT INTO trade VALUES ('b" + (i++) + "', 'o1', 'ETH-USDC', 'sell', "
                        + "0.00003996, 2502.56, " + (now - ago * min) + ", 0, 1)");
            }
            s.execute("INSERT INTO trade VALUES ('rest', 'o1', 'ETH-USDC', 'sell', 0.00051016, 2502.56, "
                    + now + ", 0, 1)");
            // заявка, у которой ВСЕ сделки за окном, в выборку не попадает
            s.execute("INSERT INTO trade VALUES ('x', 'old', 'ETH-USDC', 'buy', 0.0008, 2490, "
                    + (now - 90 * min) + ", 0, 1)");

            List<QuoteLoop.VenueTrade> rows = QuoteLoop.venueTotals(c, "ETH-USDC", now - 30 * min);

            assertEquals(1, rows.size());
            assertEquals("o1", rows.get(0).oid());
            assertEquals(0.00074992, rows.get(0).qty(), 1e-12);
            assertEquals(0.00074992 * 2502.56, rows.get(0).notional(), 1e-9);
        }
    }
}
