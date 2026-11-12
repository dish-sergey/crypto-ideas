import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Док. 222 §5: мысленная заявка, которая ХОДИТ ЗА ОПОРОЙ, как бот. Каждые 30 с ставится на δ от опоры; на каждом
 * встречном принте её цена — опора на (принт − LAG) × (1 ∓ δ): бот переставляет заявку по тику, и замена доходит не сразу.
 * Снята, когда через её текущую цену прошло встречных принтов не меньше minUsd (лот; 0 — любой принт, как было). Стоит до 5 мин.
 *
 * Выход в формате FillProb (для HourForecast): t;sd;f;—;—;—;—;—; по каждому δ: 0;снята за 1 мин;снята за 5 мин;время до
 * снятия, с;ЦЕНА СНЯТИЯ (а не маркаут — HourForecast считает маркаут сам).
 * Аргументы: база рынка, символ, журнал бота (опора), выход, [задержка мс = 1000].
 */
public class MovingMentalV60 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static long[] ft; static double[] ff; static int fn;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        long lag = a.length > 4 ? Long.parseLong(a[4]) : 1000;
        ft = new long[1 << 22]; ff = new double[1 << 22];
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[2] + "?mode=ro");
             ResultSet rs = c.createStatement().executeQuery("SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 ORDER BY ts_ms")) {
            while (rs.next()) { if (fn == ft.length) { ft = Arrays.copyOf(ft, fn * 2); ff = Arrays.copyOf(ff, fn * 2); } ft[fn] = rs.getLong(1); ff[fn] = rs.getDouble(2); fn++; }
        }
        long t0 = ft[0], t1 = ft[fn - 1];
        List<long[]> pt = new ArrayList<>(); List<Double> pp = new ArrayList<>(), pu = new ArrayList<>();
        double minUsd = a.length > 5 ? Double.parseDouble(a[5]) : 0;   // снята, когда через цену прошло не меньше лота, $
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro");
             PreparedStatement ps = c.prepareStatement("SELECT ts_ms, price, side, price * qty FROM revx_trade WHERE symbol = ? AND ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
            ps.setString(1, a[1]); ps.setLong(2, t0); ps.setLong(3, t1 + 400_000);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { String s = rs.getString(3); if (s == null) continue; pt.add(new long[]{rs.getLong(1), s.equals("buy") ? 1 : 0}); pp.add(rs.getDouble(2)); pu.add(rs.getDouble(4)); } }
        }
        int n = pt.size(); long[] tt = new long[n]; int[] ts = new int[n]; double[] tp = new double[n], tu = new double[n];
        for (int i = 0; i < n; i++) { tt[i] = pt.get(i)[0]; ts[i] = (int) pt.get(i)[1]; tp[i] = pp.get(i); tu[i] = pu.get(i); }
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(a[3])))) {
            for (long t = (t0 / 30_000 + 1) * 30_000; t < t1; t += 30_000) {
                double f = fairAt(t); if (Double.isNaN(f)) continue;
                int i0 = lb(tt, t + 1);
                for (int sd = 0; sd < 2; sd++) {
                    int taker = sd == 0 ? 0 : 1;
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d;%d;%.2f;0;0;0;0;0", t, sd, f));
                    for (int dl : DL) {
                        long tf = -1; double px = Double.NaN, cum = 0;
                        for (int i = i0; i < n && tt[i] <= t + 300_000; i++) {
                            if (ts[i] != taker) continue;
                            double fr = fairAt(Math.max(t, tt[i] - lag)); if (Double.isNaN(fr)) continue;
                            double p = sd == 0 ? fr * (1 - dl / 1e4) : fr * (1 + dl / 1e4);
                            if (sd == 0 ? tp[i] <= p : tp[i] >= p) { cum += tu[i]; if (cum >= minUsd) { tf = tt[i]; px = p; break; } }
                        }
                        // размер снятия: $ встречных принтов через нашу цену за [tf − 2 с, tf + 2 с] — величина свипа, который нас взял
                        double sw = 0;
                        if (tf > 0) for (int i = lb(tt, tf - 2000); i < n && tt[i] <= tf + 2000; i++) if (ts[i] == taker && (sd == 0 ? tp[i] <= px : tp[i] >= px)) sw += tu[i];
                        sb.append(String.format(Locale.ROOT, ";%.0f;%d;%d;%s;%s", sw, tf > 0 && tf - t <= 60_000 ? 1 : 0, tf > 0 ? 1 : 0,
                                tf > 0 ? String.valueOf((tf - t) / 1000.0) : "NaN", tf > 0 ? String.format(Locale.ROOT, "%.4f", px) : "NaN"));
                    }
                    out.println(sb);
                }
            }
        }
    }
    static int lb(long[] ts, long t) { int l = 0, h = ts.length; while (l < h) { int m = (l + h) >>> 1; if (ts[m] < t) l = m + 1; else h = m; } return l; }
    static double fairAt(long t) {
        int l = 0, h = fn; while (l < h) { int m = (l + h) >>> 1; if (ft[m] <= t) l = m + 1; else h = m; }
        int i = l - 1; if (i < 0 || t - ft[i] > 30_000) return Double.NaN; return ff[i];
    }
}
