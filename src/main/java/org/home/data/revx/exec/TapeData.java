package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * ЛЕНТА И ОПОРА ИЗ УЖЕ СОБРАННЫХ БАЗ — общий вход для приборов, которые считают
 * ПО ЛЕНТЕ, а не по своим сделкам.
 *
 * <h2>Почему лента, а не свои исполнения</h2>
 *
 * Наших сделок на BTC около 88 в сутки, принтов на ленте — 964. Но главное не в
 * объёме: свои исполнения зависят от того самого отступа, который мы и выбираем,
 * поэтому кривая «прибыль против отступа», построенная на них, подтверждает сама
 * себя. Лента к нашим решениям равнодушна.
 *
 * <h2>Опора берётся из журнала бота</h2>
 *
 * ⚠️ Это осознанный выбор, и у него есть цена. Опору можно было бы пересчитать
 * из книг стенда, но тогда в неё войдёт вся разница между {@code StandFair} и
 * живым котировщиком (CLAUDE.md: недобор сидит именно там). Журнал даёт ровно ту
 * цену, по которой жил бот, — с той оговоркой, что тики в нём идут раз в ~1.2 с,
 * и принт между тиками получает опору с этой точностью.
 */
public final class TapeData {

    private static final Logger log = LoggerFactory.getLogger(TapeData.class);

    /** Принт ленты. {@code aggressor} +1 — покупатель забрал, −1 — продавец. */
    public record Print(long tsMs, double price, double qty, int aggressor) {
    }

    /** Ряд опоры с поиском по времени. */
    public record Fair(long[] ts, double[] px) {

        /** Последняя опора НЕ ПОЗЖЕ {@code t}; ноль, если такой нет. */
        public double at(long t) {
            int i = upperBound(t) - 1;
            return i < 0 ? 0 : px[i];
        }

        /** Первая опора НЕ РАНЬШЕ {@code t}; ноль, если такой нет. */
        public double after(long t) {
            int i = upperBound(t - 1);
            return i >= ts.length ? 0 : px[i];
        }

        public boolean isEmpty() {
            return ts.length == 0;
        }

        private int upperBound(long t) {
            int lo = 0;
            int hi = ts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ts[mid] <= t) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    private TapeData() {
    }

    /** Лента одной пары из базы стенда. */
    public static List<Print> prints(String standDb, String symbol, long from, long to) {
        List<Print> out = new ArrayList<>();
        String url = "jdbc:sqlite:file:" + Path.of(standDb).toAbsolutePath() + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = ?"
                             + " AND ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
            ps.setString(1, symbol);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String side = rs.getString(4);
                    out.add(new Print(rs.getLong(1), rs.getDouble(2), rs.getDouble(3),
                            side != null && side.toLowerCase().startsWith("b") ? 1 : -1));
                }
            }
        } catch (Exception e) {
            log.warn("лента {} из {}: {}", symbol, standDb, e.toString());
        }
        return out;
    }

    /** Опора из журнала (нескольких через {@code +}). */
    public static Fair fair(String journals, long from, long to) {
        List<long[]> ts = new ArrayList<>();
        List<Double> px = new ArrayList<>();
        for (String path : journals.split("\\+")) {
            String url = "jdbc:sqlite:file:" + Path.of(path.trim()).toAbsolutePath() + "?mode=ro";
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 AND ts_ms >= ?"
                                 + " AND ts_ms < ? ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ts.add(new long[]{rs.getLong(1)});
                        px.add(rs.getDouble(2));
                    }
                }
            } catch (Exception e) {
                log.warn("опора из {}: {}", path, e.toString());
            }
        }
        long[] t = new long[ts.size()];
        double[] p = new double[px.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ts.get(i)[0];
            p[i] = px.get(i);
        }
        return new Fair(t, p);
    }
}
