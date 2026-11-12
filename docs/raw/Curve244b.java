import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 5 (вторая сборка): сетка δ до 60 б.п. и маркаут по размеру снятия.
// Мысленные заявки d222/f60/<окно>/fpv-*.csv (FillProb60 → MovingMentalV60 → MergeV60): по каждому δ снята ли за 5 мин,
// значение δ + маркаут 5 мин у снятой (за вычетом хода часа), и последняя колонка — размер свипа, снявшего заявку
// ($ встречных принтов через нашу цену за ±2 с).
// Единица $ на глубине s стопки на δ_k (s считается вместе со своими ближними ступенями — агрессор съедает их первыми)
// снимается с вероятностью Ps_k(s) = доля постановок, снятых свипом ≥ s, и приносит Vs_k(s) = среднее значение у таких снятий.
// Цена риска R = κ·(σ̂/σ̂_мед)^1.55·запас, κ ∝ 1/капитал; калибровка как в первой сборке: средний класс, пустой запас, ×1 → бид $93.60.
// Аргументы: <каталоги fpv через запятую> <bnb1s>
public class Curve244b {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final int NK = DL.length, NG = 2000;
    static final String[] COINS = {"BTC", "ETH", "SOL"}, CLS = {"тихо (≤p40)", "средне (p40–80)", "бурно (p80–95)", "буря (>p95)"};
    static final double K1 = 2 * 74.17, ANCHOR = 31.79 + 61.81, U = 2;
    static final double[] MULT = {1, 2, 5}, INVF = {0, 0.25, 0.5};
    static final long SQZ = Instant.parse("2026-11-08T21:45:00Z").toEpochMilli() / 60000;

    public static void main(String[] a) throws Exception {
        for (int ci = 0; ci < 3; ci++) {
            String coin = COINS[ci];
            Map<Long, Double> sig = sigma(a[1], coin);
            List<String[]> rows = new ArrayList<>(); List<Double> rs = new ArrayList<>();
            for (String dir : a[0].split(",")) for (String l : Files.readAllLines(Path.of(dir, "fpv-" + coin + ".csv"))) {
                String[] p = l.split(";"); Double s = sig.get(Long.parseLong(p[0]) / 60000 - 1);
                if (s == null || s <= 0.3) continue; rows.add(p); rs.add(s);
            }
            double[] sv = rs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double med = sv[sv.length / 2]; double[] cut = {sv[(int) (0.4 * sv.length)], sv[(int) (0.8 * sv.length)], sv[(int) (0.95 * sv.length)]};
            // счётчики: постановок; снятых со свипом в корзине j (≥ j·U); сумма значений
            long[][][] nP = new long[2][4][NK]; double[][] mS = new double[2][4]; long[][] mN = new long[2][4];
            long[][][][] cF = new long[2][4][NK][NG + 2]; double[][][][] sVv = new double[2][4][NK][NG + 2];
            for (int r = 0; r < rows.size(); r++) {
                String[] p = rows.get(r); double s = rs.get(r);
                int sd = Integer.parseInt(p[1]), cl = cls(s, cut);
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
            double[][][][] Ps = new double[2][4][NK][NG + 1], Vs = new double[2][4][NK][NG + 1]; double[][] M = new double[2][4];
            for (int sd = 0; sd < 2; sd++) for (int cl = 0; cl < 4; cl++) {
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
            System.out.printf(Locale.ROOT, "%n######## %s: постановок %d, σ̂ медиана %.2f, p40 %.2f, p80 %.2f, p95 %.2f б.п./мин%n", coin, rows.size(), med, cut[0], cut[1], cut[2]);
            System.out.println("   доход единицы по δ: P5 / V (б.п.) у любой снятой | V у снятых свипом ≥ $250 | ≥ $1000 | ≥ $5000 (в скобках число снятий)");
            int[] JS = {0, 125, 500, 2000};
            for (int sd = 0; sd < 2; sd++) for (int cl = 0; cl < 4; cl++) {
                System.out.printf("   %s %s, m = %.2f%n", sd == 0 ? "БИД" : "АСК", CLS[cl], M[sd][cl]);
                StringBuilder sb = new StringBuilder("     ");
                for (int k = 0; k < NK; k++) {
                    sb.append(String.format(Locale.ROOT, "%d: %.3f/%+.1f", DL[k], Ps[sd][cl][k][0], Vs[sd][cl][k][0]));
                    for (int q = 1; q < 4; q++) { long n = (long) (Ps[sd][cl][k][Math.min(NG, JS[q])] * nP[sd][cl][k]); sb.append(String.format(Locale.ROOT, " | %s(%d)", n >= 20 ? String.format(Locale.ROOT, "%+.1f", Vs[sd][cl][k][Math.min(NG, JS[q])]) : "—", n)); }
                    sb.append(k % 3 == 2 ? "\n     " : ";  ");
                }
                System.out.println(sb);
            }
            double lo = 1e-6, hi = 10;
            for (int it = 0; it < 80; it++) { double md = Math.sqrt(lo * hi); double q = bid(Ps[0][1], Vs[0][1], md * M[0][1], 0, null, K1); if (q > ANCHOR) lo = md; else hi = md; }
            double kappa = Math.sqrt(lo * hi);
            Double sq = sig.get(SQZ - 1);
            System.out.printf(Locale.ROOT, "   κ (×1) = %.5f; 08.11 21:45: σ̂ = %.2f → класс «%s», m = %.2f%n", kappa, sq, CLS[cls(sq, cut)], Math.pow(sq / med, 1.55));
            System.out.println("   ПРЕДСКАЗАНИЯ: ступени δ:$ — бид | аск; снятых $/ч и доход ступеней $/ч (Ps·Vs за 5 мин ×12)");
            for (double mu : MULT) for (int cl = 0; cl <= 4; cl++) {
                int c = cl == 4 ? cls(sq, cut) : cl; double mb = cl == 4 ? Math.pow(sq / med, 1.55) : M[0][c], ma = cl == 4 ? mb : M[1][c];
                for (double fi : INVF) {
                    double I = fi * K1 * mu, kap = kappa / mu;
                    double[] qb = new double[NK], qa = new double[NK];
                    double tb = bid(Ps[0][c], Vs[0][c], kap * mb, I, qb, K1 * mu - I);
                    double ta = I > 0 ? ask(Ps[1][c], Vs[1][c], kap * ma, I, qa) : 0;
                    double[] eb = expect(Ps[0][c], Vs[0][c], qb), ea = expect(Ps[1][c], Vs[1][c], qa);
                    System.out.printf(Locale.ROOT, "   ×%.0f %-16s запас %3.0f%% (%4.0f$) | бид %5.0f$ (на ≥30 б.п. %3.0f%%): %-44s | аск %5.0f$: %-40s | бид снятых %4.0f $/ч доход %.3f; аск %4.0f / %.3f%n",
                            mu, cl == 4 ? "08.11 21:45" : CLS[cl], 100 * fi, I, tb, tb > 0 ? 100 * deep(qb, 30) / tb : 0, steps(qb), ta, steps(qa), eb[0], eb[1], ea[0], ea[1]);
                }
            }
        }
    }

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
