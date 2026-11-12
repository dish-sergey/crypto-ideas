import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Док. 221: вероятность, что заявку снимут, — от отступа, объёма перед ней и потока сделок. Без бота: каждые STEP с
 * ставится мысленная заявка на δ от опоры (бид — fair·(1−δ), аск — fair·(1+δ)), стоит НЕПОДВИЖНО H секунд.
 * Снята, если за (t, t+H] тейкер встречной стороны напечатал цену не хуже нашей (продажа ≤ p для бида, покупка ≥ p для аска):
 * принт через нашу цену значит, что всё, что стояло перед нами, выбрано.
 *
 * Строка на (момент, сторона): t;sd;fair;спред б.п.;до лучшей б.п.(лучшая против опоры);поток стороны $ за 60 с;за 300 с;норма часа $ за 60 с;
 *   далее по каждому δ: впереди $;снята за 60 с;снята за 300 с;время до снятия с;маркаут 5 мин б.п. (за вычетом хода часа, + — в нашу пользу).
 * sd: 0 — наш бид (снимает тейкер-продавец), 1 — наш аск (тейкер-покупатель).
 * ⚠️ Заявки живых ботов в книге и их сделки в ленте НЕ вычтены.
 *
 * Аргументы: база рынка, символ (BTC/USDC), журнал прогона (опора из exec_quote.fair), t0, t1, выходной csv.
 */
public class FillProb60 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final long STEP = 30_000, H1 = 60_000, H5 = 300_000;
    static long[] ft; static double[] ff; static int fn;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String db = a[0], sym = a[1];
        long t0 = java.time.Instant.parse(a[3]).toEpochMilli(), t1 = java.time.Instant.parse(a[4]).toEpochMilli();
        ft = new long[1 << 22]; ff = new double[1 << 22];
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[2] + "?mode=ro");
             ResultSet rs = c.createStatement().executeQuery("SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 ORDER BY ts_ms")) {
            while (rs.next()) { if (fn == ft.length) { ft = Arrays.copyOf(ft, fn * 2); ff = Arrays.copyOf(ff, fn * 2); } ft[fn] = rs.getLong(1); ff[fn] = rs.getDouble(2); fn++; }
        }
        // лента
        List<long[]> pt = new ArrayList<>(); List<double[]> pv = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             PreparedStatement ps = c.prepareStatement("SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = ? AND ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
            ps.setString(1, sym); ps.setLong(2, t0 - 3_600_000L); ps.setLong(3, t1 + H5 + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { String s = rs.getString(4); if (s == null) continue;
                    pt.add(new long[]{rs.getLong(1), s.equals("buy") ? 1 : 0}); pv.add(new double[]{rs.getDouble(2), rs.getDouble(2) * rs.getDouble(3)}); }
            }
        }
        int n = pt.size(); long[] tt = new long[n]; int[] ts = new int[n]; double[] tp = new double[n], tu = new double[n];
        for (int i = 0; i < n; i++) { tt[i] = pt.get(i)[0]; ts[i] = (int) pt.get(i)[1]; tp[i] = pv.get(i)[0]; tu[i] = pv.get(i)[1]; }
        double[][] cum = new double[2][n + 1];   // накопленный $ тейкеров по стороне
        for (int i = 0; i < n; i++) for (int s = 0; s < 2; s++) cum[s][i + 1] = cum[s][i] + (ts[i] == s ? tu[i] : 0);
        System.err.println("принтов " + n + ", тиков опоры " + fn);

        // контроль хода часа: средний ход опоры за 5 мин по сетке 10 с
        Map<Long, double[]> hm = new HashMap<>();
        for (long t = t0; t < t1; t += 10_000) {
            double f = fairAt(t), f5 = fairAt(t + H5); if (Double.isNaN(f) || Double.isNaN(f5)) continue;
            double[] h = hm.computeIfAbsent(t / 3_600_000L, k -> new double[2]); h[0] += (f5 / f - 1) * 1e4; h[1]++;
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             PreparedStatement pb = c.prepareStatement("SELECT bp1,bq1,bp2,bq2,bp3,bq3,bp4,bq4,bp5,bq5, ap1,aq1,ap2,aq2,ap3,aq3,ap4,aq4,ap5,aq5, deep_bids, deep_asks FROM revx_book WHERE symbol = ? AND t_recv_ms <= ? AND t_recv_ms > ? AND leg = 'usdc' ORDER BY t_recv_ms DESC LIMIT 1");
             PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(a[5])))) {
            pb.setString(1, sym);
            int rows = 0;
            for (long t = t0; t < t1; t += STEP) {
                double f = fairAt(t); if (Double.isNaN(f)) continue;
                pb.setLong(2, t); pb.setLong(3, t - 10_000);
                TreeMap<Double, Double> bids = new TreeMap<>(Comparator.reverseOrder()), asks = new TreeMap<>();
                try (ResultSet r = pb.executeQuery()) {
                    if (!r.next()) continue;
                    for (int i = 0; i < 5; i++) { double p = r.getDouble(1 + 2 * i), q = r.getDouble(2 + 2 * i); if (p > 0 && q > 0) bids.put(p, q); }
                    for (int i = 0; i < 5; i++) { double p = r.getDouble(11 + 2 * i), q = r.getDouble(12 + 2 * i); if (p > 0 && q > 0) asks.put(p, q); }
                    deep(r.getString(21), bids); deep(r.getString(22), asks);
                }
                if (bids.isEmpty() || asks.isEmpty()) continue;
                double bb = bids.firstKey(), ba = asks.firstKey(); if (bb >= ba) continue;
                double hr = hm.containsKey(t / 3_600_000L) ? hm.get(t / 3_600_000L)[0] / hm.get(t / 3_600_000L)[1] : 0;
                int i0 = lb(tt, t + 1);   // первый принт строго после t
                for (int sd = 0; sd < 2; sd++) {
                    int taker = sd == 0 ? 0 : 1;   // наш бид снимает тейкер-продавец
                    double best = sd == 0 ? bb : ba;
                    double toBest = sd == 0 ? (1 - best / f) * 1e4 : (best / f - 1) * 1e4;   // где лучшая цена стороны относительно опоры
                    double fl60 = cum[taker][lb(tt, t)] - cum[taker][lb(tt, t - 60_000)], fl300 = cum[taker][lb(tt, t)] - cum[taker][lb(tt, t - 300_000)];
                    double norm = (cum[taker][lb(tt, t)] - cum[taker][lb(tt, t - 3_600_000L)]) / 60.0;
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d;%d;%.2f;%.2f;%.2f;%.0f;%.0f;%.0f", t, sd, f, (ba / bb - 1) * 1e4, toBest, fl60, fl300, norm));
                    for (int dl : DL) {
                        double p = sd == 0 ? f * (1 - dl / 1e4) : f * (1 + dl / 1e4);
                        boolean cross = sd == 0 ? p >= ba : p <= bb;
                        if (cross) { sb.append(";NaN;NaN;NaN;NaN;NaN"); continue; }
                        double ahead = 0;
                        if (sd == 0) { for (var e : bids.entrySet()) { if (e.getKey() < p) break; ahead += e.getKey() * e.getValue(); } }
                        else { for (var e : asks.entrySet()) { if (e.getKey() > p) break; ahead += e.getKey() * e.getValue(); } }
                        long tf = -1;
                        for (int i = i0; i < n && tt[i] <= t + H5; i++) {
                            if (ts[i] != taker) continue;
                            if (sd == 0 ? tp[i] <= p : tp[i] >= p) { tf = tt[i]; break; }
                        }
                        double mk = Double.NaN;
                        if (tf > 0) { double fa = fairAt(tf + H5), fb = fairAt(tf); if (!Double.isNaN(fa)) mk = sd == 0 ? (fa / p - 1) * 1e4 - hr : (1 - fa / p) * 1e4 + hr; }
                        sb.append(String.format(Locale.ROOT, ";%.0f;%d;%d;%s;%s", ahead, tf > 0 && tf - t <= H1 ? 1 : 0, tf > 0 ? 1 : 0,
                                tf > 0 ? String.valueOf((tf - t) / 1000.0) : "NaN", Double.isNaN(mk) ? "NaN" : String.format(Locale.ROOT, "%.2f", mk)));
                    }
                    out.println(sb); rows++;
                }
            }
            System.err.println("строк " + rows);
        }
    }

    static void deep(String d, Map<Double, Double> l) {
        if (d == null || d.isEmpty()) return;
        for (String x : d.split(",")) { int j = x.indexOf(':'); if (j > 0) l.put(Double.parseDouble(x.substring(0, j)), Double.parseDouble(x.substring(j + 1))); }
    }
    static int lb(long[] ts, long t) { int l = 0, h = ts.length; while (l < h) { int m = (l + h) >>> 1; if (ts[m] < t) l = m + 1; else h = m; } return l; }
    static double fairAt(long t) {
        int l = 0, h = fn; while (l < h) { int m = (l + h) >>> 1; if (ft[m] <= t) l = m + 1; else h = m; }
        int i = l - 1; if (i < 0 || t - ft[i] > 30_000) return Double.NaN; return ff[i];
    }
}
