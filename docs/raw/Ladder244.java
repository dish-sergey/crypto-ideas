import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 7: ТАБЛИЦА СТУПЕНЕЙ для котировщика (revx.exec.ladder-file) из кривой этапа 5 (сборка Curve244c: сетка до 60 б.п.,
// маркаут 5 мин без вычета часа, значение по размеру снятия, класс «ликвидации»). На каждого бота (якорь — его лот: бид в среднем
// классе при пустом запасе равен лоту, это калибровка κ под допустимую просадку 2%), сторону, класс (0 тихо ≤p40, 1 средне, 2 бурно
// p80–95, 3 буря >p95, 4 ликвидации) и запас q = 0, 0.1 … 1.0 капитала K: четыре ступени — ближняя 2–10 б.п., средняя 12–20,
// глубокая 25–35, дальняя 40–60; у ступени δ (среднее по $) и размер в $. Строка «#cls» — границы классов σ̂ монеты (СКО минутных
// ходов за прошлый час, б.п./мин, по Бинансу).
// Аргументы: <каталоги fpvr через запятую> <bnb1s> <liqms.csv> <выход> [K = 74.17]
public class Ladder244 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final int NK = DL.length, NG = 2000;
    static final String[] COINS = {"BTC", "ETH", "SOL"}, CLS = {"тихо (≤p40)", "средне (p40–80)", "бурно (p80–95)", "буря (>p95)", "ликвидации ≥$1M/3 мин"};
    static final double K1 = 2 * 74.17, ANCHOR = 31.79 + 61.81, U = 2;
    static final double[] MULT = {1, 2, 5}, INVF = {0, 0.25, 0.5};
    static final long SQZ = Instant.parse("2026-11-08T21:45:00Z").toEpochMilli() / 60000;

    static final double LIQ = Double.parseDouble(System.getProperty("curve.liq", "1e6"));
    static long[] lt; static double[] lc;
    static final long LW = Long.parseLong(System.getProperty("curve.liqwin", "180000"));
    static double liq(long t) { return cum(t) - cum(t - LW); }
    static double cum(long t) { int l = 0, h = lt.length; while (l < h) { int m = (l + h) >>> 1; if (lt[m] <= t) l = m + 1; else h = m; } return l == 0 ? 0 : lc[l - 1]; }

    public static void main(String[] a) throws Exception {
        List<double[]> lq = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(a[2]))) { String[] p = l.split(","); boolean lng = p[0].equals("bybit") ? p[3].equals("BUY") : p[0].equals("okx") && p[3].equals("SELL"); if (!lng) continue;
            double ct = p[0].equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1; lq.add(new double[]{Long.parseLong(p[6]), Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct}); }
        lq.sort(Comparator.comparingDouble(x -> x[0])); lt = new long[lq.size()]; lc = new double[lq.size()]; double z = 0;
        for (int i = 0; i < lq.size(); i++) { lt[i] = (long) lq.get(i)[0]; z += lq.get(i)[1]; lc[i] = z; }
        for (int ci = 0; ci < 3; ci++) {
            String coin = COINS[ci];
            Map<Long, Double> sig = sigma(a[1], coin);
            List<String[]> rows = new ArrayList<>(); List<Double> rs = new ArrayList<>();
            for (String dir : a[0].split(",")) for (String l : Files.readAllLines(Path.of(dir, System.getProperty("curve.prefix", "fpv-") + coin + ".csv"))) {
                String[] p = l.split(";"); Double s = sig.get(Long.parseLong(p[0]) / 60000 - 1);
                if (s == null || s <= 0.3) continue; rows.add(p); rs.add(s);
            }
            double[] sv = rs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double med = sv[sv.length / 2]; double[] cut = {sv[(int) (0.4 * sv.length)], sv[(int) (0.8 * sv.length)], sv[(int) (0.95 * sv.length)]};
            // счётчики: постановок; снятых со свипом в корзине j (≥ j·U); сумма значений
            long[][][] nP = new long[2][5][NK]; double[][] mS = new double[2][5]; long[][] mN = new long[2][5];
            long[][][][] cF = new long[2][5][NK][NG + 2]; double[][][][] sVv = new double[2][5][NK][NG + 2];
            for (int r = 0; r < rows.size(); r++) {
                String[] p = rows.get(r); double s = rs.get(r);
                int sd = Integer.parseInt(p[1]), cl = liq(Long.parseLong(p[0])) >= LIQ ? 4 : cls(s, cut);
                mS[sd][cl] += Math.pow(s / med, 1.55); mN[sd][cl]++;
                String[] sw = p[p.length - 1].split(",");
                for (int k = 0; k < NK; k++) {
                    int o = 8 + 5 * k; if (p[o].equals("NaN")) continue;
                    boolean f = p[o + 2].equals("1"); double mk = d(p[o + 4]);
                    if (f && Double.isNaN(mk)) continue;
                    nP[sd][cl][k]++;
                    if (f) { int j = (int) Math.min(NG + 1, Double.parseDouble(sw[k]) / U); cF[sd][cl][k][j]++; sVv[sd][cl][k][j] += mk; }
                }
            }
            // накопить «свип ≥ j·U» сверху вниз
            double[][][][] Ps = new double[2][5][NK][NG + 1], Vs = new double[2][5][NK][NG + 1]; double[][] M = new double[2][5];
            for (int sd = 0; sd < 2; sd++) for (int cl = 0; cl < 5; cl++) {
                M[sd][cl] = mS[sd][cl] / Math.max(1, mN[sd][cl]);
                for (int k = 0; k < NK; k++) {
                    long c = 0; double v = 0; double lastV = Double.NaN;
                    long[] cc = new long[NG + 2]; double[] vv = new double[NG + 2];
                    for (int j = NG + 1; j >= 0; j--) { c += cF[sd][cl][k][j]; v += sVv[sd][cl][k][j]; cc[j] = c; vv[j] = v; }
                    for (int j = 0; j <= NG; j++) {
                        Ps[sd][cl][k][j] = (double) cc[j] / Math.max(1, nP[sd][cl][k]);
                        if (cc[j] >= 20) lastV = vv[j] / cc[j];
                        Vs[sd][cl][k][j] = lastV; // где снятий меньше 20 — значение от последней корзины, где их хватало
                    }
                }
            }
            double K = a.length > 4 ? Double.parseDouble(a[4]) : 74.17;
            out.append(String.format(Locale.ROOT, "#cls;%s;%.4f;%.4f;%.4f;%.4f%n", coin, med, cut[0], cut[1], cut[2]));
            String[][] bots = {{"BTC", "a", "31.79"}, {"BTC", "e", "61.81"}, {"ETH", "c", "31.79"}, {"ETH", "d", "61.81"}, {"SOL", "b", "31.79"}, {"SOL", "f", "61.81"}};
            int[][] RG = {{2, 10}, {12, 20}, {25, 35}, {40, 60}};
            for (String[] bt : bots) {
                if (!bt[0].equals(coin)) continue;
                double A = System.getProperty("ladder.anchor") != null ? Double.parseDouble(System.getProperty("ladder.anchor")) * K : Double.parseDouble(bt[2]);
                double lo = 1e-6, hi = 50;
                for (int it = 0; it < 80; it++) { double md = Math.sqrt(lo * hi); double q = bid(Ps[0][1], Vs[0][1], md * M[0][1], 0, null, K); if (q > A) lo = md; else hi = md; }
                double kap = Math.sqrt(lo * hi);
                System.out.printf(Locale.ROOT, "%s бот %s: лот %.2f, κ %.5f; классы σ̂ %.2f / %.2f / %.2f (медиана %.2f)%n", coin, bt[1], A, kap, cut[0], cut[1], cut[2], med);
                for (int cl = 0; cl < 5; cl++) for (int qi = 0; qi <= 10; qi++) {
                    double I = qi / 10.0 * K;
                    double[] qb = new double[NK], qa = new double[NK];
                    bid(Ps[0][cl], Vs[0][cl], kap * M[0][cl], I, qb, Math.max(0, K - I));
                    if (I > 0) ask(Ps[1][cl], Vs[1][cl], kap * M[1][cl], I, qa);
                    for (int sd = 0; sd < 2; sd++) {
                        double[] q = sd == 0 ? qb : qa;
                        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s;%s;%d;%d", bt[1], sd == 0 ? "BUY" : "SELL", cl, qi));
                        for (int[] g : RG) {
                            double s = 0, w = 0;
                            for (int k = 0; k < NK; k++) if (DL[k] >= g[0] && DL[k] <= g[1]) { s += q[k]; w += q[k] * DL[k]; }
                            sb.append(String.format(Locale.ROOT, ";%.1f;%.2f", s > 0 ? w / s : (g[0] + g[1]) / 2.0, s));
                        }
                        out.append(sb).append('\n');
                        if (qi % 5 == 0) System.out.println("   " + sb);
                    }
                }
            }
        }
        Files.writeString(Path.of(a[3]), out.toString());
    }
    static final StringBuilder out = new StringBuilder("# бот;сторона;класс;запас (доля K ×10);δ0;$0;δ1;$1;δ2;$2;δ3;$3 — ступени 2–10 / 12–20 / 25–35 / 40–60 б.п.\n");

    static double deep(double[] q, int from) { double z = 0; for (int k = 0; k < NK; k++) if (DL[k] >= from) z += q[k]; return z; }
    static int cls(double s, double[] cut) { return s <= cut[0] ? 0 : s <= cut[1] ? 1 : s <= cut[2] ? 2 : 3; }
    static double closer(double[] st, int k) { double z = 0; for (int i = 0; i < k; i++) z += st[i]; return z; }
    static int jj(double[] st, int k, double s) { return (int) Math.min(NG, (closer(st, k) + s) / U); }

    static double bid(double[][] P, double[][] V, double km, double I, double[] q, double cash) {
        double[] st = new double[NK]; double tot = 0;
        while (tot + U <= cash + 1e-9) {
            double R = km * (I + tot + U / 2); int bk = -1; double be = 0;
            for (int k = 0; k < NK; k++) { int j = jj(st, k, st[k]); if (Double.isNaN(V[k][j])) continue; double e = P[k][j] * (V[k][j] - R); if (e > be) { be = e; bk = k; } }
            if (bk < 0) break; st[bk] += U; tot += U;
        }
        if (q != null) System.arraycopy(st, 0, q, 0, NK);
        return tot;
    }

    static double ask(double[][] P, double[][] V, double km, double I, double[] q) {
        double[] st = new double[NK]; double tot = 0;
        while (tot + U <= I + 1e-9) {
            double R = km * (I - tot - U / 2); int bk = -1; double be = 0;
            for (int k = 0; k < NK; k++) { int j = jj(st, k, st[k]); if (Double.isNaN(V[k][j])) continue; double e = P[k][j] * (V[k][j] + R); if (e > be) { be = e; bk = k; } }
            if (bk < 0) break; st[bk] += U; tot += U;
        }
        System.arraycopy(st, 0, q, 0, NK);
        return tot;
    }

    static double[] expect(double[][] P, double[][] V, double[] q) {
        double usd = 0, inc = 0;
        for (int k = 0; k < NK; k++) for (double s = 0; s < q[k] - 1e-9; s += U) { int j = jj(q, k, s); double f = P[k][j] * U * 12; usd += f; if (!Double.isNaN(V[k][j])) inc += f * V[k][j] / 1e4; }
        return new double[]{usd, inc};
    }

    static String steps(double[] q) {
        StringBuilder sb = new StringBuilder(); for (int k = 0; k < NK; k++) if (q[k] > 0) sb.append(DL[k]).append(':').append((long) q[k]).append(' ');
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
