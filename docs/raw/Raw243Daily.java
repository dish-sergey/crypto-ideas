import java.sql.*;
import java.util.*;

// 12.11 (владелец, docs/raw): результат по суткам UTC каждого бота. Деньги как в StandE: перенос запаса (inv × Δопоры поминутно)
// + захват (отступ сделки от опоры × объём). Аргументы: <окно> <вариант> <бот> <монета> <bot.db> … (по пять).
// Выход CSV: window,variant,bot,coin,day,pnl,capture,carry,dd_in_day,buys,sells,turnover_usd,avg_inv_usd,end_inv_usd
public class Raw243Daily {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        System.out.println("window,variant,bot,coin,day,pnl,capture,carry,dd_in_day,buys,sells,turnover_usd,avg_inv_usd,end_inv_usd");
        for (int k = 0; k + 4 < a.length; k += 5) {
            String db = a[k + 4]; if (!new java.io.File(db).exists()) { System.err.println("нет " + db); continue; }
            TreeMap<Long, double[]> m = new TreeMap<>(); // минута → fair, inv, capture, buys, sells, turnover
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) {
                    while (r.next()) { long t = r.getLong(1) / 60000; double[] o = m.get(t); if (o == null) m.put(t, new double[]{r.getDouble(2), r.getDouble(3), 0, 0, 0, 0}); else { o[0] = r.getDouble(2); o[1] = r.getDouble(3); } }
                }
                try (ResultSet r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill order by ts_ms")) {
                    while (r.next()) {
                        var e = m.floorEntry(r.getLong(1) / 60000); if (e == null) continue;
                        boolean buy = r.getString(2).equals("BUY"); double px = r.getDouble(4), v = r.getDouble(3) * px, f = e.getValue()[0];
                        double[] o = e.getValue(); o[2] += (buy ? f / px - 1 : 1 - f / px) * v; o[buy ? 3 : 4] += 1; o[5] += v;
                    }
                }
            }
            TreeMap<Long, double[]> day = new TreeMap<>(); // день → pnl, capt, carry, buys, sells, turn, invSum, n, endInv, eqPeak, dd
            double eq = 0; double[] last = null;
            for (var x : m.entrySet()) {
                double[] v = x.getValue(); long d = x.getKey() / 1440;
                double[] D = day.computeIfAbsent(d, z -> new double[11]);
                if (D[7] == 0) { D[9] = eq; }
                double cr = last == null ? 0 : last[1] * (v[0] - last[0]);
                eq += cr + v[2]; D[0] += cr + v[2]; D[1] += v[2]; D[2] += cr; D[3] += v[3]; D[4] += v[4]; D[5] += v[5];
                D[6] += v[1] * v[0]; D[7]++; D[8] = v[1] * v[0];
                D[9] = Math.max(D[9], eq); D[10] = Math.max(D[10], D[9] - eq);
                last = v;
            }
            for (var x : day.entrySet()) {
                double[] D = x.getValue();
                String ds = java.time.LocalDate.ofEpochDay(x.getKey()).toString();
                System.out.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%d,%d,%.2f,%.2f,%.2f%n", a[k], a[k + 1], a[k + 2], a[k + 3], ds,
                        D[0], D[1], D[2], D[10], (long) D[3], (long) D[4], D[5], D[6] / Math.max(1, D[7]), D[8]);
            }
        }
    }
}
