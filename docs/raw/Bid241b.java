import java.sql.*;
import java.util.*;
// Покупки ближе 6 б.п. от опоры: какой отступ был, когда заявку ПОСТАВИЛИ (последний тик с bid == цене сделки до неё, в пределах 10 мин),
// и сколько прошло от постановки цены до сделки; ход опоры за это время. Аргументы: <bot.db>
public class Bid241b {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        TreeMap<Long, double[]> q = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select ts_ms, fair, bid, inventory from exec_quote where fair > 0 order by ts_ms")) {
            while (r.next()) q.put(r.getLong(1), new double[]{r.getDouble(2), r.getDouble(3), r.getDouble(4)});
        }
        int[] h = new int[6]; int n = 0, found = 0; double sumSet = 0, sumAge = 0, sumMove = 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select ts_ms, price from exec_fill where upper(side) = 'BUY' order by ts_ms")) {
            while (r.next()) {
                long t = r.getLong(1); double px = r.getDouble(2); var e = q.floorEntry(t); if (e == null) continue;
                double f = e.getValue()[0], off = (f - px) / f * 1e4; if (off >= 6) continue; n++;
                Map.Entry<Long, double[]> set = null;
                for (var x : q.headMap(t, true).descendingMap().entrySet()) { if (t - x.getKey() > 600_000) break; if (Math.abs(x.getValue()[1] - px) < 1e-6) set = x; else if (set != null) break; }
                if (set == null) { System.out.printf(Locale.ROOT, "   %s цена %.2f отступ сейчас %.1f, постановки не найдено%n", java.time.Instant.ofEpochMilli(t), px, off); continue; }
                found++;
                double so = (set.getValue()[0] - px) / set.getValue()[0] * 1e4, age = (t - set.getKey()) / 1000.0, mv = (f / set.getValue()[0] - 1) * 1e4;
                sumSet += so; sumAge += age; sumMove += mv;
                h[so < 6 ? 0 : so < 8 ? 1 : so < 10 ? 2 : so < 12 ? 3 : so < 14 ? 4 : 5]++;
                System.out.printf(Locale.ROOT, "   %s отступ при сделке %5.1f | при постановке %5.1f | цена стоит %6.0f с | опора сдвинулась %+6.1f б.п. | запас до %.4f%n",
                        java.time.Instant.ofEpochMilli(t).toString().substring(5, 19), off, so, age, mv, e.getValue()[2]);
            }
        }
        System.out.printf(Locale.ROOT, "покупок ближе 6 б.п.: %d, постановка найдена %d; отступ при постановке в среднем %.1f, <6: %d | 6–8: %d | 8–10: %d | 10–12: %d | 12–14: %d | ≥14: %d; цена стояла %.0f с, опора сдвинулась %+.1f б.п.%n",
                n, found, sumSet / found, h[0], h[1], h[2], h[3], h[4], h[5], sumAge / found, sumMove / found);
    }
}
