import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 244, идея владельца 12.11: «зачем много покупать, если продать некому». Мысленные заявки (fpvr — маркаут без вычета часа)
// окон wall, 28–30.10, 06–10.11. Признак в момент постановки бида t — встречный поток: доля тейкеров-ПОКУПАТЕЛЕЙ в $ на ленте
// Revolut за [t − W, t] (W = 15 мин) и их $ в час. Исходы для бида на δ: снят ли за 5 мин; значение δ + маркаут 5 мин;
// маркаут 30 и 60 мин от цены снятия (опора из журнала окна); «продать есть кому» — снялся ли мысленный аск на 12 б.п.,
// поставленный в первый 30-секундный шаг после нашей покупки (за 5 мин). Разрез по квинтилям признака, ошибка — по суткам.
// Аргументы: <окно:каталог fpvr:журнал опоры:база ленты>… (по одному на окно; журнал — wb-<монета>.db в каталоге)
public class Flow244 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final int[] USE = {8, 12, 16, 20, 30};
    static final long W = 900_000L;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            List<double[]> rows = new ArrayList<>(); // x, $покупателей/ч, day, k, fill, v5, mk30, mk60, sell
            for (String spec : a) {
                String[] s = spec.split(":", 2); String rest = s[1];
                // каталог;журналы;база — разделитель «;»
                String[] p = rest.split(";");
                String dir = p[0], wb = p[1] + "/wb-" + coin + ".db", db = p[2];
                long[] ft; double[] ff;
                { List<long[]> t = new ArrayList<>(); List<Double> f = new ArrayList<>();
                  try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + wb + "?mode=ro"); ResultSet r = c.createStatement().executeQuery("select ts_ms, fair from exec_quote where fair > 0 order by ts_ms")) { while (r.next()) { t.add(new long[]{r.getLong(1)}); f.add(r.getDouble(2)); } }
                  ft = new long[t.size()]; ff = new double[t.size()]; for (int i = 0; i < ft.length; i++) { ft[i] = t.get(i)[0]; ff[i] = f.get(i); } }
                List<String> lines = Files.readAllLines(Path.of(dir, "fpvr-" + coin + ".csv"));
                long t0 = Long.parseLong(lines.get(0).split(";")[0]), t1 = Long.parseLong(lines.get(lines.size() - 1).split(";")[0]);
                // лента: накопленные $ покупателей и продавцов
                List<long[]> tt = new ArrayList<>(); List<double[]> uu = new ArrayList<>();
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
                     PreparedStatement ps = c.prepareStatement("select ts_ms, price*qty, side from revx_trade where symbol = ? and ts_ms >= ? and ts_ms <= ? order by ts_ms")) {
                    ps.setString(1, coin + "/USDC"); ps.setLong(2, t0 - W); ps.setLong(3, t1);
                    Set<Long> dummy = new HashSet<>();
                    try (ResultSet r = ps.executeQuery()) { while (r.next()) { tt.add(new long[]{r.getLong(1)}); boolean b = "buy".equalsIgnoreCase(r.getString(3)); uu.add(new double[]{b ? r.getDouble(2) : 0, b ? 0 : r.getDouble(2)}); } }
                }
                long[] ts = new long[tt.size()]; double[] cb = new double[ts.length + 1], cs = new double[ts.length + 1];
                for (int i = 0; i < ts.length; i++) { ts[i] = tt.get(i)[0]; cb[i + 1] = cb[i] + uu.get(i)[0]; cs[i + 1] = cs[i] + uu.get(i)[1]; }
                // аски по моменту
                Map<Long, String[]> asks = new HashMap<>();
                for (String l : lines) { String[] q = l.split(";"); if (q[1].equals("1")) asks.put(Long.parseLong(q[0]), q); }
                int k12 = Arrays.binarySearch(DL, 12);
                for (String l : lines) {
                    String[] q = l.split(";"); if (!q[1].equals("0")) continue;
                    long t = Long.parseLong(q[0]);
                    int hi = lb(ts, t + 1), lo = lb(ts, t - W);
                    double b = cb[hi] - cb[lo], sl = cs[hi] - cs[lo];
                    double x = b + sl > 0 ? b / (b + sl) : -1;      // −1 — потока не было вовсе
                    for (int k = 0; k < DL.length; k++) {
                        if (Arrays.binarySearch(USE, DL[k]) < 0) continue;
                        int o = 8 + 5 * k; if (q[o].equals("NaN")) continue;
                        boolean fill = q[o + 2].equals("1"); double v5 = d(q[o + 4]);
                        if (fill && Double.isNaN(v5)) continue;
                        double mk30 = Double.NaN, mk60 = Double.NaN, sell = Double.NaN;
                        if (fill) {
                            long tf = t + (long) (Double.parseDouble(q[o + 3]) * 1000);
                            double f0 = fair(ft, ff, tf), px = f0 * (1 - DL[k] / 1e4);
                            double f30 = fair(ft, ff, tf + 1_800_000L), f60 = fair(ft, ff, tf + 3_600_000L);
                            if (f30 > 0) mk30 = (f30 / px - 1) * 1e4; if (f60 > 0) mk60 = (f60 / px - 1) * 1e4;
                            String[] ar = asks.get((tf / 30_000 + 1) * 30_000);
                            if (ar != null && !ar[8 + 5 * k12].equals("NaN")) sell = ar[8 + 5 * k12 + 2].equals("1") ? 1 : 0;
                        }
                        rows.add(new double[]{x, b * 4, (t - 1789430400000L) / 86_400_000L, DL[k], fill ? 1 : 0, fill ? v5 : Double.NaN, mk30, mk60, sell});
                    }
                }
                System.err.println(coin + " " + s[0] + ": строк " + rows.size());
            }
            System.out.printf("%n######## %s: доля тейкеров-покупателей за 15 мин до постановки бида — квинтили (без потока — отдельно)%n", coin);
            double[] xs = rows.stream().filter(r -> r[0] >= 0).mapToDouble(r -> r[0]).sorted().toArray();
            double[] cut = new double[6]; cut[0] = -0.5; for (int q = 1; q < 5; q++) cut[q] = xs[q * xs.length / 5]; cut[5] = 1.01;
            for (int dl : USE) {
                System.out.printf("  δ = %d б.п. | класс (доля покупателей) | постановок | снято за 5 мин | значение 5 мин | маркаут 30 мин | маркаут 60 мин | аск 12 снят за 5 мин после покупки%n", dl);
                for (int q = -1; q < 5; q++) {
                    final int qq = q, dd = dl;
                    List<double[]> g = rows.stream().filter(r -> r[3] == dd && (qq < 0 ? r[0] < 0 : r[0] >= cut[qq] && r[0] < cut[qq + 1] && r[0] >= 0)).toList();
                    if (g.size() < 50) continue;
                    double fr = g.stream().mapToDouble(r -> r[4]).average().orElse(0);
                    System.out.printf(Locale.ROOT, "    %-22s | %6d | %5.3f | %s | %s | %s | %s%n",
                            q < 0 ? "потока нет" : String.format(Locale.ROOT, "%.0f–%.0f%%", 100 * Math.max(0, cut[q]), 100 * Math.min(1, cut[q + 1])),
                            g.size(), fr, ms(g, 5), ms(g, 6), ms(g, 7), ms(g, 8));
                }
            }
        }
    }

    // среднее по снятым с ошибкой по суткам
    static String ms(List<double[]> g, int col) {
        Map<Double, double[]> byDay = new HashMap<>(); double s = 0; int n = 0;
        for (double[] r : g) { if (Double.isNaN(r[col])) continue; s += r[col]; n++; double[] d = byDay.computeIfAbsent(r[2], z -> new double[2]); d[0] += r[col]; d[1]++; }
        if (n < 20) return "    —        ";
        double m = s / n; List<Double> dm = new ArrayList<>(); for (double[] d : byDay.values()) if (d[1] >= 3) dm.add(d[0] / d[1]);
        double v = 0; for (double x : dm) v += (x - m) * (x - m); double se = dm.size() > 1 ? Math.sqrt(v / (dm.size() - 1) / dm.size()) : Double.NaN;
        return String.format(Locale.ROOT, "%+6.2f ± %4.2f (%d)", m, se, n);
    }

    static int lb(long[] t, long q) { int l = 0, h = t.length; while (l < h) { int m = (l + h) >>> 1; if (t[m] < q) l = m + 1; else h = m; } return l; }
    static double fair(long[] t, double[] f, long q) { int i = lb(t, q + 1) - 1; if (i < 0 || q - t[i] > 60_000) return Double.NaN; return f[i]; }
    static double d(String s) { return s.equals("NaN") || s.isEmpty() ? Double.NaN : Double.parseDouble(s); }
}
