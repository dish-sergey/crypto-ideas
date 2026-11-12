import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 245, идея владельца 12.11: «смотреть, до куда простреливают тейкеры: стоим на 15, а каждый второй прострел достаёт до 20 —
// ставить на 20». Свип продавцов на Revolut — принты тейкера-продавца, склеенные при разрыве ≤ 100 мс, ≥ $200; глубина — от
// середины книги перед первым принтом до худшей цены. Для каждой постановки мысленного бида (fpvr: снятие за 5 мин, значение
// δ + маркаут 5 мин без вычета часа) признак — медиана глубины свипов за прошлые 15 мин (и их число); классы по ней.
// Доход на постановку при δ = доля снятых × среднее значение снятых (б.п.). По классу: доход на каждом δ и лучший δ.
// Ошибка — по суткам. Аргументы: <каталоги fpvr через запятую> <db>…
public class Sweep245 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final int[] SHOW = {8, 10, 12, 14, 16, 20, 25, 30, 40};
    static final double[] CB = {0, 6, 10, 15, 20, 30, 1e9};
    static final long W = 900_000L;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            // свипы продавцов: время, глубина б.п.
            TreeMap<Long, Double> sw = new TreeMap<>(); Set<String> seen = new HashSet<>(); String sym = coin + "/USDC";
            for (int k = 1; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement pb = c.prepareStatement("select bp1, ap1 from revx_book where symbol = ? and t_recv_ms <= ? order by t_recv_ms desc limit 1");
                 ResultSet r = c.createStatement().executeQuery("select trade_id, ts_ms, price, qty, side from revx_trade where symbol = '" + sym + "' and ts_ms >= 1789444800000 order by ts_ms")) {
                pb.setString(1, sym);
                long st = -1, last = -1; double usd = 0, minPx = 1e18;
                while (true) {
                    boolean has = r.next();
                    if (has && !seen.add(r.getString(1))) continue;
                    long t = has ? r.getLong(2) : Long.MAX_VALUE; boolean sell = has && "sell".equalsIgnoreCase(r.getString(5));
                    if (st >= 0 && (!has || t - last > 100 || !sell)) {
                        if (usd >= 200) { pb.setLong(2, st - 1); try (ResultSet b = pb.executeQuery()) { if (b.next() && b.getDouble(1) > 0 && b.getDouble(2) > 0) { double mid = (b.getDouble(1) + b.getDouble(2)) / 2; sw.put(st, (mid - minPx) / mid * 1e4); } } }
                        st = -1;
                    }
                    if (!has) break;
                    if (!sell) continue;
                    if (st < 0) { st = t; usd = 0; minPx = 1e18; }
                    last = t; usd += r.getDouble(3) * r.getDouble(4); minPx = Math.min(minPx, r.getDouble(3));
                }
            }
            // по классам: [класс][δ] — постановок, сумма дохода; по суткам для ошибки
            int nc = CB.length - 1;
            double[][] cnt = new double[nc + 1][DL.length], val = new double[nc + 1][DL.length];
            Map<String, double[]> day = new HashMap<>();   // класс|δ|сутки → сумма, n
            long[] rowsBy = new long[nc + 1]; double[] swN = new double[nc + 1];
            for (String dir : a[0].split(",")) for (String l : Files.readAllLines(Path.of(dir, "fpvr-" + coin + ".csv"))) {
                String[] p = l.split(";"); if (!p[1].equals("0")) continue;
                long t = Long.parseLong(p[0]);
                var sub = sw.subMap(t - W, true, t, false);
                int cl;
                if (sub.isEmpty()) cl = nc;                           // свипов не было
                else { double[] d = sub.values().stream().mapToDouble(x -> x).sorted().toArray(); double med = d[d.length / 2];
                    cl = 0; while (cl + 1 < nc && med >= CB[cl + 1]) cl++; swN[cl] += d.length; }
                rowsBy[cl]++;
                for (int k = 0; k < DL.length; k++) {
                    int o = 8 + 5 * k; if (p[o].equals("NaN")) continue;
                    boolean f = p[o + 2].equals("1"); double mk = p[o + 4].equals("NaN") ? Double.NaN : Double.parseDouble(p[o + 4]);
                    if (f && Double.isNaN(mk)) continue;
                    double v = f ? mk : 0;
                    cnt[cl][k]++; val[cl][k] += v;
                    double[] dd = day.computeIfAbsent(cl + "|" + k + "|" + t / 86_400_000L, z -> new double[2]); dd[0] += v; dd[1]++;
                }
            }
            System.out.printf("%n######## %s: свипов продавцов ≥ $200 %d; доход мысленного бида на постановку, б.п. ×100 (снятые × значение), по медиане глубины свипов за 15 мин%n", coin, sw.size());
            StringBuilder h = new StringBuilder("   медиана глубины | постановок | свипов на постановку |"); for (int s : SHOW) h.append(String.format(" δ=%-6d", s)); h.append("| лучший δ (из всех)");
            System.out.println(h);
            for (int cl = 0; cl <= nc; cl++) {
                if (rowsBy[cl] < 500) continue;
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "   %-15s | %7d | %5.1f |", cl == nc ? "свипов нет" : String.format(Locale.ROOT, "%.0f–%s б.п.", CB[cl], CB[cl + 1] > 1e8 ? "∞" : String.format(Locale.ROOT, "%.0f", CB[cl + 1])), rowsBy[cl], cl == nc ? 0 : swN[cl] / rowsBy[cl]));
                int best = -1; double bv = -1e9;
                for (int k = 0; k < DL.length; k++) { double m = cnt[cl][k] > 0 ? val[cl][k] / cnt[cl][k] : Double.NaN; if (cnt[cl][k] >= 300 && m > bv) { bv = m; best = k; } }
                for (int s : SHOW) { int k = Arrays.binarySearch(DL, s); double m = cnt[cl][k] > 0 ? val[cl][k] / cnt[cl][k] : Double.NaN; sb.append(String.format(Locale.ROOT, " %+6.1f±%-4.1f", 100 * m, 100 * se(day, cl, k, m))); }
                sb.append(String.format(Locale.ROOT, " | %d б.п. (%+.1f)", best >= 0 ? DL[best] : -1, 100 * bv));
                System.out.println(sb);
            }
        }
    }

    static double se(Map<String, double[]> day, int cl, int k, double m) {
        List<Double> v = new ArrayList<>();
        for (var e : day.entrySet()) if (e.getKey().startsWith(cl + "|" + k + "|") && e.getValue()[1] >= 20) v.add(e.getValue()[0] / e.getValue()[1]);
        if (v.size() < 2) return Double.NaN; double s = 0; for (double x : v) s += (x - m) * (x - m); return Math.sqrt(s / (v.size() - 1) / v.size());
    }
}
