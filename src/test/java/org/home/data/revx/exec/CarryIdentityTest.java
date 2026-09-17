package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ТОЖДЕСТВО ПЕРЕНОСКИ: сходится с маркой на СЛЕДУЮЩЕМ тике и не сходится на предыдущем.
 *
 * <h2>Зачем</h2>
 *
 * Разложение {@code Δкапитал = переноска + захват} — тождество, а не модель, и
 * потому у него есть железная проверка: остаток обязан быть нулём. Единственное
 * место, где оно ломается, — выбор марки сделки. Инвентарь в {@code exec_quote}
 * меняется на том тике, где бот УЗНАЛ об исполнении, поэтому маркой обязан быть
 * следующий тик, а не предыдущий. Ошибка выглядит безобидно (сдвиг на один тик,
 * ~1.2 с) и при этом смещена: сделка случается ровно тогда, когда цена пришла к
 * нам. На живых журналах она давала остаток в треть всего результата.
 *
 * Здесь строится журнал из трёх тиков и одной покупки, у которого ответ известен
 * на бумаге.
 */
class CarryIdentityTest {

    /** Тик котировщика с заданной отметкой времени. */
    private static void quote(String path, long ts, double fair, double inv) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS exec_quote(ts_ms INT, fair REAL, bid REAL,"
                    + " ask REAL, inventory REAL, quotable INT, reason TEXT, pressure REAL)");
            st.execute("INSERT INTO exec_quote(ts_ms, fair, inventory, quotable)"
                    + " VALUES (" + ts + ", " + fair + ", " + inv + ", 1)");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fill(String path, long ts, String side, double qty, double price) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS exec_fill(ts_ms INT, venue_id TEXT, side TEXT,"
                    + " qty REAL, price REAL, fair REAL, fee REAL, fee_currency TEXT,"
                    + " status TEXT, level INT)");
            st.execute("INSERT INTO exec_fill(ts_ms, venue_id, side, qty, price, fair, fee, status)"
                    + " VALUES (" + ts + ", 'v1', '" + side + "', " + qty + ", " + price
                    + ", " + price + ", 0, 'filled')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void identityClosesWithTheNextTickAsMark(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        // Пусто на 100. Покупка одной единицы по 99 между тиками; на следующем
        // тике опора 100 и инвентарь 1. Потом цена падает до 98.
        quote(path, 1_000, 100.0, 0.0);
        fill(path, 1_500, "BUY", 1.0, 99.0);
        quote(path, 2_000, 100.0, 1.0);
        quote(path, 3_000, 98.0, 1.0);

        // На бумаге: захват = (100 − 99) × 1 = +1; переноска = 1 × (98 − 100) = −2;
        // капитал = касса −99 плюс запас 1 × 98 = −1, старт 0 → Δ = −1.
        Result r = decompose(path, true);
        assertEquals(+1.0, r.capture, 1e-12, "захват — расстояние от марки до цены сделки");
        assertEquals(-2.0, r.carry, 1e-12, "переноска — запас на ходе опоры");
        assertEquals(-1.0, r.actual, 1e-12, "капитал: касса плюс запас по последней опоре");
        assertEquals(0.0, r.actual - r.capture - r.carry, 1e-12, "остаток тождества — ноль");
    }

    @Test
    void previousTickAsMarkBreaksItAndTheErrorIsBiased(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        quote(path, 1_000, 100.0, 0.0);
        fill(path, 1_500, "BUY", 1.0, 99.0);
        quote(path, 2_000, 100.0, 1.0);
        quote(path, 3_000, 98.0, 1.0);

        // Предыдущий тик даёт ту же опору 100 только потому, что цена между
        // тиками не менялась. Сдвинем её: тик перед сделкой на 101.
        Result same = decompose(path, false);
        assertEquals(0.0, same.actual - same.capture - same.carry, 1e-12);

        String moved = dir.resolve("exec2.db").toString();
        quote(moved, 1_000, 101.0, 0.0);
        fill(moved, 1_500, "BUY", 1.0, 99.0);
        quote(moved, 2_000, 100.0, 1.0);
        quote(moved, 3_000, 98.0, 1.0);

        assertEquals(0.0, decompose(moved, true).residual(), 1e-12,
                "марка на следующем тике: тождество сходится при любой цене");
        assertTrue(Math.abs(decompose(moved, false).residual()) > 0.5,
                "марка на предыдущем тике: остаток равен ходу цены за тик, умноженному на лот");
    }

    private record Result(double capture, double carry, double actual) {
        double residual() {
            return actual - capture - carry;
        }
    }

    /** Та же арифметика, что в {@link CarryReport}, с выбором марки. */
    private static Result decompose(String path, boolean nextTick) {
        java.util.TreeMap<Long, double[]> ticks = new java.util.TreeMap<>();
        double cash = 0;
        double capture = 0;
        double carry = 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT ts_ms, fair, inventory FROM exec_quote ORDER BY ts_ms")) {
                while (rs.next()) {
                    ticks.put(rs.getLong(1), new double[]{rs.getDouble(2), rs.getDouble(3)});
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT ts_ms, side, qty, price FROM exec_fill ORDER BY ts_ms")) {
                while (rs.next()) {
                    double dq = "BUY".equals(rs.getString(2)) ? rs.getDouble(3) : -rs.getDouble(3);
                    double price = rs.getDouble(4);
                    var e = nextTick ? ticks.ceilingEntry(rs.getLong(1))
                            : ticks.floorEntry(rs.getLong(1));
                    double fair = e == null ? price : e.getValue()[0];
                    cash -= dq * price;
                    capture += (fair - price) * dq;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        double prevP = 0;
        double prevQ = 0;
        boolean first = true;
        for (var e : ticks.entrySet()) {
            if (!first) {
                carry += prevQ * (e.getValue()[0] - prevP);
            }
            first = false;
            prevP = e.getValue()[0];
            prevQ = e.getValue()[1];
        }
        double p0 = ticks.firstEntry().getValue()[0];
        double q0 = ticks.firstEntry().getValue()[1];
        return new Result(capture, carry, cash + prevQ * prevP - q0 * p0);
    }
}
