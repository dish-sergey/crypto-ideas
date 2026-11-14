import java.nio.file.*;
import java.util.*;

/**
 * Док. 225 §4–6: ЦЕНА ЗАПАСА по уравнению Беллмана и теневая цена постановки μ — перенос scripts/d225_dp_zapas_limit.py на Java
 * (локально нет Python). Мысленные заявки fpv-*.csv (новая мерка, 224 §9), только обучающие сутки 0–20 (15.09–05.10), коэффициенты
 * func-coef-v.csv. Скорость снятия λ = −ln(1−P)/5 в минуту, × k — уровень подгоняется так, чтобы близорукая политика (p = 0) на потолке Q
 * давала target исполнений лота в сутки (по стенду `fv`). Выбор δ в боте от k не зависит — k нужен только для таблицы.
 *
 * Аргументы: монета fpv.csv coef.csv target out.csv Q1,Q2 γ1,γ2 бюджет1,бюджет2 [every]
 * В out.csv дописываются строки «МОНЕТА;Q;γ;0;p_b(0)…p_b(Q−1)» (бид) и «МОНЕТА;Q;γ;1;p_a(1)…p_a(Q)» (аск), б.п.,
 * и «МОНЕТА;Q;γ;mu;бюджет;μ» — стартовая цена постановки.
 */
public class FuncInvSide {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30};
    static final long T0 = java.time.Instant.parse("2026-09-15T00:00:00Z").toEpochMilli();

    static double[] coef(String file, String coin, int side) throws Exception {
        for (String l : Files.readAllLines(Path.of(file))) {
            String[] p = l.trim().split(";");
            if (p.length == 20 && p[0].equals(coin) && Integer.parseInt(p[1]) == side) {
                double[] c = new double[18];
                for (int j = 0; j < 18; j++) c[j] = Double.parseDouble(p[2 + j]);
                return c;
            }
        }
        throw new IllegalStateException("нет коэффициентов " + coin + " " + side);
    }

    /** Состояния одной стороны: lam[i][j] (раз в минуту, без k), M[i][j]. */
    record Side(double[] d, double[][] lam, double[][] m) {
        /** H, R, I при сдвиге p: средние по состояниям max(0, max_δ λ(M−p)), снятий в минуту, доход б.п.·лот в минуту, средний δ. */
        double[] stats(double p) {
            double h = 0, r = 0, inc = 0, ds = 0; int nq = 0;
            for (int i = 0; i < lam.length; i++) {
                double best = 0; int bj = -1;
                for (int j = 0; j < d.length; j++) {
                    double v = lam[i][j] * (m[i][j] - p);
                    if (v > best) { best = v; bj = j; }
                }
                if (bj >= 0) { h += best; r += lam[i][bj]; inc += lam[i][bj] * m[i][bj]; ds += d[bj]; nq++; }
            }
            int n = lam.length;
            return new double[]{h / n, r / n, inc / n, nq > 0 ? ds / nq : Double.NaN, (double) nq / n};
        }
    }

    /** H(p), R(p), I(p) на сетке — для итераций. */
    static final class Ham {
        final double pmin = -80, step = 0.1; final double[] h, r, inc; double rmax;
        Ham(Side s) {
            int n = (int) Math.round(160 / step) + 1; h = new double[n]; r = new double[n]; inc = new double[n];
            for (int i = 0; i < n; i++) { double[] st = s.stats(pmin + i * step); h[i] = st[0]; r[i] = st[1]; inc[i] = st[2]; rmax = Math.max(rmax, st[1]); }
        }
        Ham(Ham o, double k) { h = o.h.clone(); r = o.r.clone(); inc = o.inc.clone(); for (int i = 0; i < h.length; i++) { h[i] *= k; r[i] *= k; inc[i] *= k; } rmax = o.rmax * k; }
        double at(double[] a, double x) {
            double t = (x - pmin) / step; if (t <= 0) return a[0]; if (t >= a.length - 1) return a[a.length - 1];
            int i = (int) t; double f = t - i; return a[i] * (1 - f) + a[i + 1] * f;
        }
        double H(double x) { return at(h, x); } double R(double x) { return at(r, x); } double I(double x) { return at(inc, x); }
    }

    /** Эргодическое уравнение Беллмана по q = 0..Q; возвращает {p_b (q=0..Q−1), p_a (q=1..Q)} без μ. */
    static double[][] solve(Ham hb, Ham ha, int Q, double gamma, double s2, double mu) {
        double[] h = new double[Q + 1]; double U = 1.1 * (hb.rmax + ha.rmax);
        for (int it = 0; it < 400_000; it++) {
            double[] T = new double[Q + 1];
            for (int q = 0; q <= Q; q++) {
                T[q] = -0.5 * gamma * s2 * q * q;
                if (q < Q) T[q] += hb.H(h[q] - h[q + 1] + mu);
                if (q > 0) T[q] += ha.H(h[q] - h[q - 1] + mu);
            }
            double mx = Arrays.stream(T).max().getAsDouble(), mn = Arrays.stream(T).min().getAsDouble();
            for (int q = 0; q <= Q; q++) h[q] += T[q] / U;
            double h0 = h[0]; for (int q = 0; q <= Q; q++) h[q] -= h0;
            if (mx - mn < 1e-10) break;
        }
        double[] pb = new double[Q], pa = new double[Q];
        for (int q = 0; q < Q; q++) { pb[q] = h[q] - h[q + 1]; pa[q] = h[q + 1] - h[q]; }
        return new double[][]{pb, pa};
    }

    /** {доход б.п.·лот/мин, исполнений/сут, доля пусто, средний q, доля у потолка}. */
    static double[] eval(Ham hb, Ham ha, int Q, double[] pb, double[] pa, double mu) {
        double[] pi = new double[Q + 1], rb = new double[Q + 1], ra = new double[Q + 1], ib = new double[Q + 1], ia = new double[Q + 1];
        for (int q = 0; q <= Q; q++) {
            if (q < Q) { rb[q] = hb.R(pb[q] + mu); ib[q] = hb.I(pb[q] + mu); }
            if (q > 0) { ra[q] = ha.R(pa[q - 1] + mu); ia[q] = ha.I(pa[q - 1] + mu); }
        }
        pi[0] = 1;
        for (int q = 0; q < Q; q++) pi[q + 1] = ra[q + 1] > 0 ? pi[q] * rb[q] / ra[q + 1] : 0;
        double s = Arrays.stream(pi).sum(), inc = 0, f = 0, qm = 0;
        for (int q = 0; q <= Q; q++) { pi[q] /= s; inc += pi[q] * (ib[q] + ia[q]); f += pi[q] * (rb[q] + ra[q]); qm += pi[q] * q; }
        return new double[]{inc, f * 1440, pi[0], qm, pi[Q]};
    }

    /** По сторонам: {исполнений бида/сут, аска/сут, доход бида б.п.·лот/сут, аска}. */
    static double[] sides(Ham hb, Ham ha, int Q, double[] pb, double[] pa) {
        double[] pi = new double[Q + 1], rb = new double[Q + 1], ra = new double[Q + 1], ib = new double[Q + 1], ia = new double[Q + 1];
        for (int q = 0; q <= Q; q++) { if (q < Q) { rb[q] = hb.R(pb[q]); ib[q] = hb.I(pb[q]); } if (q > 0) { ra[q] = ha.R(pa[q - 1]); ia[q] = ha.I(pa[q - 1]); } }
        pi[0] = 1; for (int q = 0; q < Q; q++) pi[q + 1] = ra[q + 1] > 0 ? pi[q] * rb[q] / ra[q + 1] : 0;
        double s = Arrays.stream(pi).sum(), fb = 0, fa = 0, xb = 0, xa = 0;
        for (int q = 0; q <= Q; q++) { pi[q] /= s; fb += pi[q] * rb[q]; fa += pi[q] * ra[q]; xb += pi[q] * ib[q]; xa += pi[q] * ia[q]; }
        return new double[]{fb * 1440, fa * 1440, xb * 1440, xa * 1440};
    }

    public static void main(String[] a) throws Exception {
        String coin = a[0]; double target = Double.parseDouble(a[3]); Path out = Path.of(a[4]);
        int[] qs = Arrays.stream(a[5].split(",")).mapToInt(Integer::parseInt).toArray();
        double[] gs = Arrays.stream(a[6].split(",")).mapToDouble(Double::parseDouble).toArray();
        double[] bs = Arrays.stream(a[7].split(",")).mapToDouble(Double::parseDouble).toArray();
        int every = a.length > 8 ? Integer.parseInt(a[8]) : 1;
        List<List<double[]>> rows = List.of(new ArrayList<>(), new ArrayList<>());
        TreeMap<Long, Double> mid = new TreeMap<>();
        int[] ln = new int[2];
        for (String l : Files.readAllLines(Path.of(a[1]))) {
            String[] p = l.split(";"); long t = Long.parseLong(p[0]);
            if ((t - T0) / 86_400_000L > 20) continue;
            int sd = Integer.parseInt(p[1]);
            if (sd == 0) mid.put(t, Double.parseDouble(p[2]));
            if (ln[sd]++ % every != 0) continue;
            double[] r = new double[4 + DL.length];
            r[0] = Double.parseDouble(p[3]); r[1] = Double.parseDouble(p[5]) > 0 ? 1 : 0; r[2] = Math.log1p(Double.parseDouble(p[7]));
            for (int k = 0; k < DL.length; k++) { String v = p[8 + 5 * k]; r[4 + k] = v.equals("NaN") ? Double.NaN : Double.parseDouble(v); }
            rows.get(sd).add(r);
        }
        // σ² минутного хода опоры, б.п.²
        double ss = 0; int sn = 0;
        for (var e : mid.entrySet()) { Double nx = mid.get(e.getKey() + 60_000L); if (nx != null) { double x = Math.log(nx / e.getValue()) * 1e4; ss += x * x; sn++; } }
        double s2 = ss / sn;
        double[] d = Arrays.stream(DL).filter(x -> x >= 4).asDoubleStream().toArray();
        Side[] sides = new Side[2];
        for (int sd = 0; sd < 2; sd++) {
            double[] c = coef(a[2], coin, sd); List<double[]> rs = rows.get(sd);
            double[][] lam = new double[rs.size()][d.length], m = new double[rs.size()][d.length];
            for (int i = 0; i < rs.size(); i++) {
                double[] r = rs.get(i);
                for (int j = 0; j < d.length; j++) {
                    double ah = r[4 + j + 1];              // DL[0] = 2 пропущен
                    if (Double.isNaN(ah)) { lam[i][j] = 0; m[i][j] = 0; continue; }
                    double L = Math.log1p(ah), dd = d[j];
                    double[] x = {1, dd, dd * dd, L, dd * L, r[1], dd * r[1], r[2], r[0]};
                    double z = 0, mm = 0; for (int k = 0; k < 9; k++) { z += c[k] * x[k]; mm += c[9 + k] * x[k]; }
                    double P = Math.min(0.999, 1 / (1 + Math.exp(-z)));
                    lam[i][j] = -Math.log1p(-P) / 5.0; m[i][j] = mm;
                }
            }
            sides[sd] = new Side(d, lam, m);
        }
        Ham hb0 = new Ham(sides[0]), ha0 = new Ham(sides[1]);
        StringBuilder o = new StringBuilder();
        System.out.printf(Locale.ROOT, "%n## %s: состояний бид %d, аск %d, σ² минуты %.1f б.п.²%n", coin, rows.get(0).size(), rows.get(1).size(), s2);
        for (int Q : qs) {
            double lo = 0.01, hi = 100;
            for (int it = 0; it < 60; it++) { double mk = Math.sqrt(lo * hi); double f = eval(new Ham(hb0, mk), new Ham(ha0, mk), Q, new double[Q], new double[Q], 0)[1]; if (f > target) hi = mk; else lo = mk; }
            double k = Math.sqrt(lo * hi); Ham hb = new Ham(hb0, k), ha = new Ham(ha0, k);
            double[] my = eval(hb, ha, Q, new double[Q], new double[Q], 0);
            System.out.printf(Locale.ROOT, "%n### Потолок %d лотов, калибровка λ ×%.3f%nБлизорукая: доход %.2f б.п.·лот/мин (%.0f/сут), исполнений %.0f/сут, пусто %.0f%%, средний запас %.2f%n",
                    Q, k, my[0], my[0] * 1440, my[1], 100 * my[2], my[3]);
            for (double g : gs) {
                double[][] t = solve(hb, ha, Q, g, s2, 0); double[] ev = eval(hb, ha, Q, t[0], t[1], 0); double[] sd2 = sides(hb, ha, Q, t[0], t[1]); System.out.printf(Locale.ROOT, "  ПО СТОРОНАМ: бид %.1f исп/сут, доход %.0f б.п.·лот/сут (маркаут на исполнение %.2f); аск %.1f исп/сут, доход %.0f (%.2f)%n", sd2[0], sd2[2], sd2[2] / sd2[0], sd2[1], sd2[3], sd2[3] / sd2[1]);
                System.out.printf(Locale.ROOT, "γ = %s: доход %.0f б.п.·лот/сут (%+.0f%%), исполнений %.0f/сут, пусто %.0f%%, у потолка %.0f%%, средний запас %.2f%n  p бида q=0..%d: %s%n  p аска q=1..%d: %s%n  δ бида: %s%n  δ аска: %s%n",
                        g, ev[0] * 1440, 100 * (ev[0] / my[0] - 1), ev[1], 100 * ev[2], 100 * ev[4], ev[3], Q - 1, f(t[0]), Q, f(t[1]), dm(sides[0], t[0]), dm(sides[1], t[1]));
                o.append(coin).append(';').append(Q).append(';').append(g).append(";0").append(csv(t[0])).append('\n');
                o.append(coin).append(';').append(Q).append(';').append(g).append(";1").append(csv(t[1])).append('\n');
                for (double b : bs) {
                    double ml = 0, mh = 40;
                    for (int it = 0; it < 30; it++) { double mm = (ml + mh) / 2; double[][] tt = solve(hb, ha, Q, g, s2, mm); if (eval(hb, ha, Q, tt[0], tt[1], mm)[1] > b) ml = mm; else mh = mm; }
                    double mu = (ml + mh) / 2; double[][] tt = solve(hb, ha, Q, g, s2, mu); double[] e2 = eval(hb, ha, Q, tt[0], tt[1], mu);
                    System.out.printf(Locale.ROOT, "  бюджет %.0f: μ = %.2f б.п., доход %.0f б.п.·лот/сут, исполнений %.0f%n", b, mu, e2[0] * 1440, e2[1]);
                    o.append(coin).append(';').append(Q).append(';').append(g).append(";mu;").append(b).append(';').append(String.format(Locale.ROOT, "%.3f", mu)).append('\n');
                }
            }
        }
        Files.writeString(out, o.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String f(double[] x) { StringBuilder b = new StringBuilder(); for (double v : x) b.append(String.format(Locale.ROOT, " %+.1f", v)); return b.toString(); }
    static String csv(double[] x) { StringBuilder b = new StringBuilder(); for (double v : x) b.append(String.format(Locale.ROOT, ";%.3f", v)); return b.toString(); }
    static String dm(Side s, double[] p) { StringBuilder b = new StringBuilder(); for (double v : p) b.append(String.format(Locale.ROOT, " %.1f", s.stats(v)[3])); return b.toString(); }
}
