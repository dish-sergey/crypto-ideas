import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 244: где выигрывает/теряет состояние «ликвидации» по ботам. Кривая денег как в StandE (захват + перенос, поминутно).
// Минуты делятся на: «в состоянии» (интервал файла), «час после» (до 60 мин после конца интервала), «прочее».
// По каждому боту и варианту: Δденег в каждой части, покупки/продажи $ в состоянии, маркаут покупок в состоянии через 5 и 30 мин
// по опоре (б.п., взвешенно по $), и поток тейкеров Revolut в состоянии: продавцы / покупатели, $.
// Аргументы: <каталог журналов> <окно> <файл интервалов> <база рынка> <метка=тег>…   (тег с {g} и {b} как в StandE)
public class LiqSplit244 {
    static final String[][] BOTS = {{"BTC", "a"}, {"BTC", "e"}, {"ETH", "c"}, {"ETH", "d"}, {"SOL", "b"}, {"SOL", "f"}};

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        TreeMap<Long, Long> iv = new TreeMap<>();
        for (String l : Files.readAllLines(Path.of(a[2]))) { String[] p = l.split(";"); if (p.length == 3 && p[0].equals("BTC")) iv.put(Long.parseLong(p[1]) / 60000, Long.parseLong(p[2]) / 60000); }
        // поток тейкеров по монетам в состоянии
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[3] + "?mode=ro")) {
            for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
                double[] in = new double[2], all = new double[2]; long n0 = Long.MAX_VALUE, n1 = 0;
                try (ResultSet r = c.createStatement().executeQuery("select ts_ms, price*qty, side from revx_trade where symbol = '" + coin + "/USDC'")) {
                    while (r.next()) { long m = r.getLong(1) / 60000; int s = "buy".equalsIgnoreCase(r.getString(3)) ? 1 : 0; all[s] += r.getDouble(2); if (part(iv, m) == 0) in[s] += r.getDouble(2); n0 = Math.min(n0, m); n1 = Math.max(n1, m); }
                }
                long inMin = 0; for (long m = n0; m <= n1; m++) if (part(iv, m) == 0) inMin++;
                System.out.printf(Locale.ROOT, "%s: поток тейкеров в состоянии (%d мин): продавцы $%.0f, покупатели $%.0f (доля покупателей %.0f%%); за всё окно продавцы $%.0f, покупатели $%.0f (%.0f%%)%n",
                        coin, inMin, in[0], in[1], 100 * in[1] / Math.max(1, in[0] + in[1]), all[0], all[1], 100 * all[1] / Math.max(1, all[0] + all[1]));
            }
        }
        System.out.println("\nбот вариант | Δ$ в состоянии | час после | прочее | всего | в состоянии: покупки $ / продажи $ | маркаут покупок 5 / 30 мин, б.п. | запас на входе в состояние, $ (среднее)");
        for (String[] b : BOTS) for (int k = 4; k < a.length; k++) {
            String[] lt = a[k].split("=", 2);
            String db = a[0] + "/j-" + a[1] + "-d" + lt[1].replace("{g}", "acb".contains(b[1]) ? "acb" : "edf").replace("{b}", b[1]) + "-" + b[0] + "/bot-" + b[1] + ".db";
            if (!new java.io.File(db).exists()) continue;
            TreeMap<Long, double[]> m = new TreeMap<>(); List<double[]> fills = new ArrayList<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) { while (r.next()) m.put(r.getLong(1) / 60000, new double[]{r.getDouble(2), r.getDouble(3), 0}); }
                try (ResultSet r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill order by ts_ms")) {
                    while (r.next()) {
                        long tm = r.getLong(1) / 60000; var e = m.floorEntry(tm); if (e == null) continue;
                        boolean buy = r.getString(2).equals("BUY"); double v = r.getDouble(3) * r.getDouble(4), px = r.getDouble(4), f = e.getValue()[0];
                        e.getValue()[2] += (buy ? f / px - 1 : 1 - f / px) * v;
                        fills.add(new double[]{tm, buy ? 1 : 0, v, px});
                    }
                }
            }
            double[] dP = new double[3]; double[] last = null; long lastK = 0;
            for (var x : m.entrySet()) { double[] v = x.getValue(); double d = v[2]; if (last != null) d += last[1] * (v[0] - last[0]); dP[part(iv, x.getKey())] += d; last = v; }
            double bIn = 0, sIn = 0, mk5 = 0, mk30 = 0, w5 = 0, w30 = 0;
            for (double[] f : fills) {
                if (part(iv, (long) f[0]) != 0) continue;
                if (f[1] == 1) { bIn += f[2]; var e5 = m.ceilingEntry((long) f[0] + 5); var e30 = m.ceilingEntry((long) f[0] + 30);
                    if (e5 != null) { mk5 += (e5.getValue()[0] / f[3] - 1) * 1e4 * f[2]; w5 += f[2]; } if (e30 != null) { mk30 += (e30.getValue()[0] / f[3] - 1) * 1e4 * f[2]; w30 += f[2]; } }
                else sIn += f[2];
            }
            double invIn = 0; int nIn = 0; for (long s : iv.keySet()) { var e = m.floorEntry(s); if (e != null && s >= m.firstKey() && s <= m.lastKey()) { invIn += e.getValue()[1] * e.getValue()[0]; nIn++; } }
            System.out.printf(Locale.ROOT, "%s %s %-8s | %+6.2f | %+6.2f | %+6.2f | %+6.2f | %5.0f / %5.0f | %+6.1f / %+6.1f | %.1f%n", b[1], b[0], lt[0], dP[0], dP[1], dP[2], dP[0] + dP[1] + dP[2], bIn, sIn,
                    w5 > 0 ? mk5 / w5 : Double.NaN, w30 > 0 ? mk30 / w30 : Double.NaN, nIn > 0 ? invIn / nIn : 0);
        }
    }

    // 0 — в состоянии, 1 — час после конца интервала, 2 — прочее
    static int part(TreeMap<Long, Long> iv, long m) {
        var e = iv.floorEntry(m); if (e == null) return 2;
        if (m < e.getValue()) return 0; if (m < e.getValue() + 60) return 1; return 2;
    }
}
