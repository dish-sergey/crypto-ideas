import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Док. 224 §5.7: как Merge (состояние книги и потока из fp-*.csv + исход ходящей заявки, маркаут за вычетом хода часа),
 * плюс последняя колонка T — доля МЕЛКИХ (< $1) среди встречных тейкерских принтов за прошлый час (бот на $0.10 и подобные).
 * Аргументы: fp-*.csv mm*.csv журнал.db база_рынка символ выход.csv
 */
public class MergeV60 {
    static long[] ft; static double[] ff; static int fn;
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        ft = new long[1 << 22]; ff = new double[1 << 22];
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[2] + "?mode=ro");
             ResultSet rs = c.createStatement().executeQuery("SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 ORDER BY ts_ms")) {
            while (rs.next()) { if (fn == ft.length) { ft = Arrays.copyOf(ft, fn * 2); ff = Arrays.copyOf(ff, fn * 2); } ft[fn] = rs.getLong(1); ff[fn] = rs.getDouble(2); fn++; }
        }
        // встречные принты по сторонам тейкера: время и признак «мелкий»
        List<List<long[]>> tape = List.of(new ArrayList<>(), new ArrayList<>());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[3] + "?mode=ro");
             PreparedStatement ps = c.prepareStatement("SELECT ts_ms, price * qty, side FROM revx_trade WHERE symbol = ? AND ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
            ps.setString(1, a[4]); ps.setLong(2, ft[0] - 3_700_000L); ps.setLong(3, ft[fn - 1]);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { String s = rs.getString(3); if (s == null) continue; tape.get(s.equals("buy") ? 1 : 0).add(new long[]{rs.getLong(1), rs.getDouble(2) < 1 ? 1 : 0}); } }
        }
        long[][] tt = new long[2][]; int[][] cs = new int[2][];
        for (int s = 0; s < 2; s++) { List<long[]> l = tape.get(s); tt[s] = new long[l.size()]; cs[s] = new int[l.size() + 1];
            for (int i = 0; i < l.size(); i++) { tt[s][i] = l.get(i)[0]; cs[s][i + 1] = cs[s][i] + (int) l.get(i)[1]; } }
        Map<Long, double[]> hm = new HashMap<>();
        for (long t = ft[0] / 10_000 * 10_000; t < ft[fn - 1]; t += 10_000) {
            double f = fairAt(t), f5 = fairAt(t + 300_000); if (Double.isNaN(f) || Double.isNaN(f5)) continue;
            double[] h = hm.computeIfAbsent(t / 3_600_000L, k -> new double[2]); h[0] += (f5 / f - 1) * 1e4; h[1]++;
        }
        Map<Long, String[]> mm = new HashMap<>();
        for (String l : Files.readAllLines(Path.of(a[1]))) { String[] p = l.split(";"); mm.put(Long.parseLong(p[0]) * 2 + Integer.parseInt(p[1]), p); }
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(a[5])))) {
            for (String l : Files.readAllLines(Path.of(a[0]))) {
                String[] p = l.split(";"); long t = Long.parseLong(p[0]); int sd = Integer.parseInt(p[1]);
                String[] m = mm.get(t * 2 + sd); if (m == null) continue;
                double[] h = hm.get(t / 3_600_000L); double hr = h == null || !Boolean.parseBoolean(System.getProperty("merge.hour-adj", "true")) ? 0 : h[0] / h[1];
                int nk = (p.length - 8) / 5; StringBuilder sws = new StringBuilder();
                for (int k = 0; k < nk; k++) {
                    int o = 8 + 5 * k; sws.append(k == 0 ? "" : ",").append(m[o]); if (p[o].equals("NaN")) continue;
                    p[o + 1] = m[o + 1]; p[o + 2] = m[o + 2]; p[o + 3] = m[o + 3];
                    String mk = "NaN";
                    if (m[o + 2].equals("1")) { long tf = t + (long) (Double.parseDouble(m[o + 3]) * 1000); double px = Double.parseDouble(m[o + 4]), fa = fairAt(tf + 300_000);
                        if (!Double.isNaN(fa)) mk = String.format(Locale.ROOT, "%.2f", sd == 0 ? (fa / px - 1) * 1e4 - hr : (1 - fa / px) * 1e4 + hr); }
                    p[o + 4] = mk;
                }
                int taker = sd == 0 ? 0 : 1; int hi = lb(tt[taker], t), lo = lb(tt[taker], t - 3_600_000L);
                double tiny = hi > lo ? (double) (cs[taker][hi] - cs[taker][lo]) / (hi - lo) : 0;
                out.println(String.join(";", p) + ";" + String.format(Locale.ROOT, "%.3f", tiny) + ";" + sws);
            }
        }
    }
    static int lb(long[] ts, long t) { int l = 0, h = ts.length; while (l < h) { int m = (l + h) >>> 1; if (ts[m] < t) l = m + 1; else h = m; } return l; }
    static double fairAt(long t) {
        int l = 0, h = fn; while (l < h) { int m = (l + h) >>> 1; if (ft[m] <= t) l = m + 1; else h = m; }
        int i = l - 1; if (i < 0 || t - ft[i] > 30_000) return Double.NaN; return ff[i];
    }
}
