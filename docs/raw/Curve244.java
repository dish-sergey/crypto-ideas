import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 5: сборка кривой «размер по расстоянию» из этапов 2–4 и запись предсказаний до проверки.
// Части:
//   доход единицы — мысленные заявки d222/fpv-*.csv: P5(δ) — доля снятых за 5 мин, V(δ) — δ + маркаут у снятой, б.п.; по классам σ̂;
//   ёмкость по размеру — лента Revolut (как Cap244): G_δ(s) — доля принтов, дошедших до δ, с объёмом ≥ s; снятие $-единицы на глубине s
//     в стопке на δ: P5(δ)·G_δ(s);
//   цена риска — этап 3: R = κ·m·запас, m = (σ̂/σ̂_мед)^1.55 (Var/T ∝ σ̂^1.55), κ ∝ 1/капитал (просадка 2% капитала).
// Раздача: единицы по $U по очереди; бид — единица j идёт на δ с наибольшим P·(V − R_j), R_j = κm(I + j·U), пока лучшее > 0;
// аск — единицы запаса I, единица j на δ с наибольшим P·(V + R_j), R_j = κm(I − j·U) (продажа снимает риск), пока > 0.
// Калибровка κ: средний класс σ̂, запас 0, капитал ×1 → суммарный бид = $93.60 на монету (лоты двух нынешних ботов 31.79 + 61.81).
// Аргументы: <каталог fpv> <bnb1s> <db>…
public class Curve244 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30};
    static final String[] COINS = {"BTC", "ETH", "SOL"}, CLS = {"тихо (≤p40)", "средне (p40–80)", "бурно (p80–95)", "буря (>p95)"};
    static final double K1 = 2 * 74.17, ANCHOR = 31.79 + 61.81, U = 2;
    static final double[] MULT = {1, 2, 5}, INVF = {0, 0.25, 0.5};
    static final long SQZ = Instant.parse("2026-11-08T21:45:00Z").toEpochMilli() / 60000;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (int ci = 0; ci < 3; ci++) {
            String coin = COINS[ci];
            Map<Long, Double> sig = sigma(a[1], coin);
            // классы σ̂ по минутам окна мысленных заявок
            List<String> lines = Files.readAllLines(Path.of(a[0], "fpv-" + coin + ".csv"));
            double[] sv = lines.stream().map(l -> sig.get(Long.parseLong(l.substring(0, l.indexOf(';'))) / 60000 - 1)).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
            double med = sv[sv.length / 2], c40 = sv[(int) (0.4 * sv.length)], c80 = sv[(int) (0.8 * sv.length)], c95 = sv[(int) (0.95 * sv.length)];
            double[] cut = {c40, c80, c95};
            // P5, V по стороне, классу, δ
            double[][][] nP = new double[2][4][11], nF = new double[2][4][11], sV = new double[2][4][11]; double[][] mS = new double[2][4];
            long[][] mN = new long[2][4];
            for (String l : lines) {
                String[] p = l.split(";"); long t = Long.parseLong(p[0]); Double s = sig.get(t / 60000 - 1);
                if (s == null || s <= 0.3) continue;
                int sd = Integer.parseInt(p[1]), cl = cls(s, cut);
                mS[sd][cl] += Math.pow(s / med, 1.55); mN[sd][cl]++;
                for (int k = 0; k < 11; k++) {
                    int o = 8 + 5 * k; if (p[o].equals("NaN")) continue;
                    boolean f = p[o + 2].equals("1"); double mk = d(p[o + 4]);
                    if (f && Double.isNaN(mk)) continue;
                    nP[sd][cl][k]++; if (f) { nF[sd][cl][k]++; sV[sd][cl][k] += mk; }
                }
            }
            // принты: расстояние и $ по стороне и классу
            List<List<List<double[]>>> pr = new ArrayList<>();
            for (int sd = 0; sd < 2; sd++) { List<List<double[]>> x = new ArrayList<>(); for (int c = 0; c < 4; c++) x.add(new ArrayList<>()); pr.add(x); }
            Set<String> seen = new HashSet<>(); String sym = coin + "/USDC";
            for (int k = 2; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement("select bp1, ap1 from revx_book where symbol = ? and t_recv_ms <= ? order by t_recv_ms desc limit 1");
                 ResultSet r = c.createStatement().executeQuery("select trade_id, ts_ms, price, qty, side from revx_trade where symbol = '" + sym + "' order by ts_ms")) {
                ps.setString(1, sym);
                while (r.next()) {
                    if (!seen.add(r.getString(1))) continue;
                    long t = r.getLong(2); Double sg = sig.get(t / 60000 - 1); if (sg == null) continue;
                    ps.setLong(2, t - 1); double mid;
                    try (ResultSet b = ps.executeQuery()) { if (!b.next() || !(b.getDouble(1) > 0) || !(b.getDouble(2) > 0)) continue; mid = (b.getDouble(1) + b.getDouble(2)) / 2; }
                    double px = r.getDouble(3); boolean sell = "sell".equalsIgnoreCase(r.getString(5));
                    pr.get(sell ? 0 : 1).get(cls(sg, cut)).add(new double[]{(sell ? mid - px : px - mid) / mid * 1e4, px * r.getDouble(4)});
                }
            }
            System.out.printf(Locale.ROOT, "%n######## %s: σ̂ медиана %.2f, p40 %.2f, p80 %.2f, p95 %.2f б.п./мин%n", coin, med, c40, c80, c95);
            double[][][] P = new double[2][4][11], V = new double[2][4][11]; double[][] M = new double[2][4];
            for (int sd = 0; sd < 2; sd++) for (int cl = 0; cl < 4; cl++) {
                M[sd][cl] = mS[sd][cl] / Math.max(1, mN[sd][cl]);
                for (int k = 0; k < 11; k++) { P[sd][cl][k] = nF[sd][cl][k] / Math.max(1, nP[sd][cl][k]); V[sd][cl][k] = nF[sd][cl][k] > 20 ? sV[sd][cl][k] / nF[sd][cl][k] : Double.NaN; }
            }
            System.out.println("   доход единицы: P5 (доля снятых за 5 мин) / V (δ + маркаут у снятой, б.п.) по классам σ̂");
            for (int sd = 0; sd < 2; sd++) {
                System.out.println("   " + (sd == 0 ? "БИД" : "АСК") + "  δ:   " + String.join("       ", Arrays.stream(DL).mapToObj(x -> String.format("%2d", x)).toList()));
                for (int cl = 0; cl < 4; cl++) {
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "   %-16s m=%5.2f |", CLS[cl], M[sd][cl]));
                    for (int k = 0; k < 11; k++) sb.append(String.format(Locale.ROOT, " %.3f/%+5.1f", P[sd][cl][k], V[sd][cl][k]));
                    System.out.println(sb);
                }
            }
            // калибровка κ (капитал ×1): средний класс, запас 0, бид = ANCHOR
            double lo = 1e-6, hi = 10;
            for (int it = 0; it < 80; it++) { double mid = Math.sqrt(lo * hi); double q = bid(P[0][1], V[0][1], pr.get(0).get(1), mid * M[0][1], 0, null, K1); if (q > ANCHOR) lo = mid; else hi = mid; }
            double kappa = Math.sqrt(lo * hi);
            System.out.printf(Locale.ROOT, "   κ (×1) = %.5f б.п. на $ запаса при m = 1; бид в среднем классе при пустом запасе = $%.0f%n", kappa, ANCHOR);
            // 08.11 21:45
            Double sq = sig.get(SQZ - 1);
            System.out.printf(Locale.ROOT, "   08.11 21:45 UTC: σ̂ = %s б.п./мин → m = %s%n", sq == null ? "нет данных" : String.format(Locale.ROOT, "%.2f", sq), sq == null ? "—" : String.format(Locale.ROOT, "%.2f", Math.pow(sq / med, 1.55)));
            System.out.println("   ПРЕДСКАЗАНИЯ: ступени δ:$ — бид | аск; ожидаемый оборот снятых $/ч и доход ступеней $/ч (P·V за 5 мин ×12), бид при пустом запасе");
            for (double mu : MULT) for (int cl = 0; cl <= 4; cl++) {
                if (cl == 4 && sq == null) continue;
                int c = cl == 4 ? cls(sq, cut) : cl; double m = cl == 4 ? Math.pow(sq / med, 1.55) : M[0][c];
                for (double fi : INVF) {
                    double I = fi * K1 * mu, kap = kappa / mu;
                    double[] qb = new double[11], qa = new double[11];
                    double tb = bid(P[0][c], V[0][c], pr.get(0).get(c), kap * m, I, qb, K1 * mu - I);
                    double ta = I > 0 ? ask(P[1][c], V[1][c], pr.get(1).get(c), kap * (cl == 4 ? m : M[1][c]), I, qa) : 0;
                    double[] eb = expect(P[0][c], V[0][c], pr.get(0).get(c), qb), ea = expect(P[1][c], V[1][c], pr.get(1).get(c), qa);
                    System.out.printf(Locale.ROOT, "   ×%.0f %-16s запас %3.0f%% (%4.0f$) | бид %5.0f$: %-38s | аск %5.0f$: %-38s | снятых бид %4.0f $/ч, доход %.3f $/ч; аск %4.0f / %.3f%n",
                            mu, cl == 4 ? "08.11 21:45" : CLS[cl], 100 * fi, I, tb, steps(qb), ta, steps(qa), eb[0], eb[1], ea[0], ea[1]);
                }
            }
        }
    }

    static final int NG = 3000;
    // своя очередь: агрессор, дошедший до δ_k, сначала съедает все ближние ступени
    static double closer(double[] st, int k) { double z = 0; for (int i = 0; i < k; i++) z += st[i]; return z; }

    static int cls(double s, double[] cut) { return s <= cut[0] ? 0 : s <= cut[1] ? 1 : s <= cut[2] ? 2 : 3; }

    // G_δ(s): доля принтов с расстоянием ≥ δ и объёмом ≥ s
    static double G(List<double[]> pr, double dl, double s) {
        long n = 0, g = 0; for (double[] x : pr) if (x[0] >= dl) { n++; if (x[1] >= s) g++; }
        return n == 0 ? 0 : (double) g / n;
    }

    static double[][] gcache(List<double[]> pr) { // [k][j] для s = j·U, j < 400
        double[][] g = new double[11][NG + 1];
        for (int k = 0; k < 11; k++) {
            List<Double> us = new ArrayList<>(); for (double[] x : pr) if (x[0] >= DL[k]) us.add(x[1]);
            Collections.sort(us); int n = us.size();
            for (int j = 0; j <= NG; j++) { double s = j * U; int lo = 0, hi = n; while (lo < hi) { int m = (lo + hi) / 2; if (us.get(m) < s) lo = m + 1; else hi = m; } g[k][j] = n == 0 ? 0 : (double) (n - lo) / n; }
        }
        return g;
    }

    static final Map<List<double[]>, double[][]> GC = new IdentityHashMap<>();
    static double[][] gc(List<double[]> pr) { return GC.computeIfAbsent(pr, Curve244::gcache); }

    // бид: возвращает суммарный размер; q[k] — размер на δ_k
    static double bid(double[] P, double[] V, List<double[]> pr, double km, double I, double[] q, double cash) {
        double[][] g = gc(pr); double[] st = new double[11]; double tot = 0;
        while (tot + U <= cash + 1e-9) {
            double R = km * (I + tot + U / 2); int bk = -1; double be = 0;
            for (int k = 0; k < 11; k++) {
                if (Double.isNaN(V[k])) continue; int jj = (int) Math.min(NG, (closer(st, k) + st[k]) / U);
                double e = P[k] * g[k][jj] * (V[k] - R); if (e > be) { be = e; bk = k; }
            }
            if (bk < 0) break; st[bk] += U; tot += U;
        }
        if (q != null) System.arraycopy(st, 0, q, 0, 11);
        return tot;
    }

    static double ask(double[] P, double[] V, List<double[]> pr, double km, double I, double[] q) {
        double[][] g = gc(pr); double[] st = new double[11]; double tot = 0;
        while (tot + U <= I + 1e-9) {
            double R = km * (I - tot - U / 2); int bk = -1; double be = 0;
            for (int k = 0; k < 11; k++) {
                if (Double.isNaN(V[k])) continue; int jj = (int) Math.min(NG, (closer(st, k) + st[k]) / U);
                double e = P[k] * g[k][jj] * (V[k] + R); if (e > be) { be = e; bk = k; }
            }
            if (bk < 0) break; st[bk] += U; tot += U;
        }
        System.arraycopy(st, 0, q, 0, 11);
        return tot;
    }

    static double[] expect(double[] P, double[] V, List<double[]> pr, double[] q) {
        double[][] g = gc(pr); double usd = 0, inc = 0;
        for (int k = 0; k < 11; k++) for (double s = 0; s < q[k] - 1e-9; s += U) { int jj = (int) Math.min(NG, (closer(q, k) + s) / U); double f = P[k] * g[k][jj] * U * 12; usd += f; if (!Double.isNaN(V[k])) inc += f * V[k] / 1e4; }
        return new double[]{usd, inc};
    }

    static String steps(double[] q) {
        StringBuilder sb = new StringBuilder(); for (int k = 0; k < 11; k++) if (q[k] > 0) sb.append(DL[k]).append(':').append((long) q[k]).append(' ');
        return sb.length() == 0 ? "—" : sb.toString().trim();
    }

    static double d(String s) { return s.equals("NaN") || s.isEmpty() ? Double.NaN : Double.parseDouble(s); }

    static Map<Long, Double> sigma(String dir, String coin) throws IOException {
        TreeMap<Long, Float> close = new TreeMap<>();
        try (var st = Files.list(Path.of(dir))) {
            for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDC-1s-")).sorted().toList())
                try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
                    if (zi.getNextEntry() == null) continue;
                    BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
                    for (String l; (l = r.readLine()) != null; ) { if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue; String[] p = l.split(",", 6); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000; close.put(t / 60000, Float.parseFloat(p[4])); }
                }
        }
        Map<Long, Double> sig = new HashMap<>(); ArrayDeque<Double> w = new ArrayDeque<>(); double s = 0, q = 0; Long pm = null; float pc = 0;
        for (var e : close.entrySet()) {
            if (pm != null && e.getKey() == pm + 1 && pc > 0) { double r = Math.log(e.getValue() / pc) * 1e4; w.addLast(r); s += r; q += r * r; if (w.size() > 60) { double o = w.pollFirst(); s -= o; q -= o * o; }
                if (w.size() == 60) sig.put(e.getKey(), Math.sqrt(Math.max(0, q / 60 - (s / 60) * (s / 60)))); }
            else { w.clear(); s = 0; q = 0; }
            pm = e.getKey(); pc = e.getValue();
        }
        return sig;
    }
}
