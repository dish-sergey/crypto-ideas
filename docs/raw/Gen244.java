import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 2: общая функция на безразмерных признаках. Мысленные заявки d222/fpv-*.csv (как FuncV: сетка δ 2…30, снятие и
// значение = δ + маркаут у снятой). σ̂ — СКО минутных ходов Бинанса (спот USDC) за прошлый час, б.п./мин.
// Модели (снятие — логистическая, значение снятой — МНК; выбор δ — максимум P·M по сетке, как в боте):
//   A своя, в б.п. (признаки FuncV: δ, δ², ln $ впереди, δ·ln, сделка, δ·сделка, норма часа, спред);
//   B своя, безразмерная: x = δ/σ̂, x², ln($ впереди / медиана монеты), x·ln, сделка, x·сделка, норма часа, спред/σ̂; цель M/σ̂;
//   C чужая: B, обученная на двух других монетах; D общая: B на трёх монетах.
// Обучение — сутки 0–20 от 15.09, проверка — сутки 21+. Мерка — доход на постановку вне выборки, б.п., и доля, где ставит.
// Аргументы: <каталог fpv> <bnb1s>
public class Gen244 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30};
    static final long T0 = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli();
    static final String[] COINS = {"BTC", "ETH", "SOL"};
    static final int[] LIVE = {12, 14, 20};
    record Row(int coin, int side, int day, double flow, double lnorm, double spr, double sig, double[] ahead, int[] f, double[] mk) {}

    public static void main(String[] a) throws Exception {
        List<Row> all = new ArrayList<>();
        double[] medAhead = new double[3];
        for (int ci = 0; ci < 3; ci++) {
            Map<Long, Double> sig = sigma(a[1], COINS[ci]);
            List<Double> ah = new ArrayList<>();
            int miss = 0, n = 0;
            for (String l : Files.readAllLines(Path.of(a[0], "fpv-" + COINS[ci] + ".csv"))) {
                String[] p = l.split(";"); long t = Long.parseLong(p[0]);
                Double s = sig.get(t / 60000 - 1); n++;
                if (s == null || s <= 0.3) { miss++; continue; }
                double[] h = new double[11], mk = new double[11]; int[] f = new int[11];
                for (int k = 0; k < 11; k++) { int o = 8 + 5 * k; h[k] = d(p[o]); f[k] = p[o + 2].equals("1") ? 1 : 0; mk[k] = d(p[o + 4]); if (!Double.isNaN(h[k]) && h[k] > 0) ah.add(h[k]); }
                all.add(new Row(ci, Integer.parseInt(p[1]), (int) ((t - T0) / 86_400_000L), Double.parseDouble(p[5]) > 0 ? 1 : 0, Math.log1p(Double.parseDouble(p[7])), Double.parseDouble(p[3]), s, h, f, mk));
            }
            Collections.sort(ah); medAhead[ci] = ah.get(ah.size() / 2);
            System.out.printf(Locale.ROOT, "%s: строк %d, без σ̂ %d, медиана $ впереди %.0f%n", COINS[ci], n, miss, medAhead[ci]);
        }
        final double[] MA = medAhead;
        for (int side = 0; side < 2; side++) {
            System.out.printf("%n## %s — доход на постановку вне выборки (сутки 21+), б.п.%n%n| монета | живой δ | A своя, б.п. | B своя, безразмерная | C чужая (две другие) | D общая (три) | куда ставит C: медиана δ / медиана δ/σ̂ |%n|---|---|---|---|---|---|---|%n", side == 0 ? "Бид" : "Аск");
            for (int ci = 0; ci < 3; ci++) {
                final int c = ci, sd = side;
                List<Row> own = all.stream().filter(r -> r.coin() == c && r.side() == sd).toList();
                List<Row> tr = own.stream().filter(r -> r.day() <= 20).toList(), te = own.stream().filter(r -> r.day() > 20).toList();
                List<Row> other = all.stream().filter(r -> r.coin() != c && r.side() == sd && r.day() <= 20).toList();
                List<Row> pool = all.stream().filter(r -> r.side() == sd && r.day() <= 20).toList();
                int kl = Arrays.binarySearch(DL, LIVE[ci]);
                double live = te.stream().mapToDouble(r -> val(r, kl)).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
                double[] A = fit(tr, false, MA), B = fit(tr, true, MA), C = fit(other, true, MA), D = fit(pool, true, MA);
                List<Double> pickD = new ArrayList<>(), pickX = new ArrayList<>();
                double eC = ev(C, true, te, MA, pickD, pickX);
                Collections.sort(pickD); Collections.sort(pickX);
                System.out.printf(Locale.ROOT, "| %s | %+.3f | %+.3f | %+.3f | **%+.3f** | %+.3f | %.0f / %.2f |%n", COINS[ci], live, ev(A, false, te, MA, null, null), ev(B, true, te, MA, null, null), eC, ev(D, true, te, MA, null, null),
                        pickD.get(pickD.size() / 2), pickX.get(pickX.size() / 2));
            }
        }
    }

    // модель: [nf][2] — коэффициенты снятия и значения, плюс нормировка
    static double[] feat(Row r, int k, boolean dimless, double[] ma) {
        double dl = DL[k], la = Math.log1p(r.ahead()[k]);
        if (!dimless) return new double[]{1, dl, dl * dl, la, dl * la, r.flow(), dl * r.flow(), r.lnorm(), r.spr()};
        double x = dl / r.sig(), lr = Math.log((r.ahead()[k] + 1) / (ma[r.coin()] + 1));
        return new double[]{1, x, x * x, lr, x * lr, r.flow(), x * r.flow(), r.lnorm(), r.spr() / r.sig()};
    }

    static final int NF = 9;
    static double[] fit(List<Row> tr, boolean dimless, double[] ma) {
        List<double[]> X = new ArrayList<>(), Xm = new ArrayList<>(); List<Integer> Y = new ArrayList<>(); List<Double> Ym = new ArrayList<>();
        for (Row r : tr) for (int k = 0; k < 11; k++) {
            if (Double.isNaN(r.ahead()[k]) || (r.f()[k] == 1 && Double.isNaN(r.mk()[k]))) continue;
            double[] x = feat(r, k, dimless, ma); X.add(x); Y.add(r.f()[k]);
            if (r.f()[k] == 1) { Xm.add(x); Ym.add(dimless ? r.mk()[k] / r.sig() : r.mk()[k]); }
        }
        double[] mu = new double[NF], sg = new double[NF]; sg[0] = 1;
        for (int j = 1; j < NF; j++) { double s = 0, s2 = 0; for (double[] x : X) { s += x[j]; s2 += x[j] * x[j]; } mu[j] = s / X.size(); sg[j] = Math.sqrt(Math.max(1e-12, s2 / X.size() - mu[j] * mu[j])); }
        double[][] Xn = X.stream().map(x -> nrm(x, mu, sg)).toArray(double[][]::new), Xmn = Xm.stream().map(x -> nrm(x, mu, sg)).toArray(double[][]::new);
        double[] bp = logit(Xn, Y.stream().mapToInt(Integer::intValue).toArray()), bm = ols(Xmn, Ym.stream().mapToDouble(Double::doubleValue).toArray());
        double[] m = new double[4 * NF];
        System.arraycopy(mu, 0, m, 0, NF); System.arraycopy(sg, 0, m, NF, NF); System.arraycopy(bp, 0, m, 2 * NF, NF); System.arraycopy(bm, 0, m, 3 * NF, NF);
        return m;
    }

    static double ev(double[] m, boolean dimless, List<Row> te, double[] ma, List<Double> pd, List<Double> px) {
        double s = 0; int n = 0;
        for (Row r : te) {
            int fk = -1; double best = -1e18;
            for (int k = 0; k < 11; k++) {
                if (Double.isNaN(r.ahead()[k])) continue;
                double[] x = nrm(feat(r, k, dimless, ma), Arrays.copyOfRange(m, 0, NF), Arrays.copyOfRange(m, NF, 2 * NF));
                double p = 1 / (1 + Math.exp(-dot(Arrays.copyOfRange(m, 2 * NF, 3 * NF), x))), v = dot(Arrays.copyOfRange(m, 3 * NF, 4 * NF), x) * (dimless ? r.sig() : 1);
                if (p * v > best) { best = p * v; fk = k; }
            }
            if (fk < 0) continue;
            double v = val(r, fk); if (Double.isNaN(v)) continue;
            s += v; n++;
            if (pd != null) { pd.add((double) DL[fk]); px.add(DL[fk] / r.sig()); }
        }
        return s / Math.max(1, n);
    }

    static double val(Row r, int k) { if (k < 0 || Double.isNaN(r.ahead()[k])) return Double.NaN; if (r.f()[k] == 1) return Double.isNaN(r.mk()[k]) ? Double.NaN : r.mk()[k]; return 0; }
    static double d(String s) { return s.equals("NaN") ? Double.NaN : Double.parseDouble(s); }
    static double[] nrm(double[] x, double[] mu, double[] sg) { double[] y = new double[NF]; y[0] = 1; for (int j = 1; j < NF; j++) y[j] = (x[j] - mu[j]) / sg[j]; return y; }
    static double dot(double[] b, double[] x) { double s = 0; for (int j = 0; j < b.length; j++) s += b[j] * x[j]; return s; }

    static double[] logit(double[][] X, int[] y) {
        int p = X[0].length; double[] b = new double[p];
        for (int it = 0; it < 25; it++) {
            double[][] H = new double[p][p]; double[] g = new double[p];
            for (int i = 0; i < X.length; i++) { double pr = 1 / (1 + Math.exp(-dot(b, X[i]))), w = Math.max(pr * (1 - pr), 1e-9);
                for (int j = 0; j < p; j++) { g[j] += (y[i] - pr) * X[i][j]; for (int l = j; l < p; l++) H[j][l] += w * X[i][j] * X[i][l]; } }
            for (int j = 0; j < p; j++) { for (int l = 0; l < j; l++) H[j][l] = H[l][j]; H[j][j] += 1e-6; }
            double[] st = solve(H, g); double mx = 0; for (int j = 0; j < p; j++) { b[j] += st[j]; mx = Math.max(mx, Math.abs(st[j])); } if (mx < 1e-7) break;
        }
        return b;
    }
    static double[] ols(double[][] X, double[] y) {
        int p = X[0].length; double[][] H = new double[p][p]; double[] g = new double[p];
        for (int i = 0; i < X.length; i++) for (int j = 0; j < p; j++) { g[j] += X[i][j] * y[i]; for (int l = 0; l < p; l++) H[j][l] += X[i][j] * X[i][l]; }
        for (int j = 0; j < p; j++) H[j][j] += 1e-6; return solve(H, g);
    }
    static double[] solve(double[][] A, double[] b) {
        int n = b.length; double[][] M = new double[n][n + 1]; for (int i = 0; i < n; i++) { System.arraycopy(A[i], 0, M[i], 0, n); M[i][n] = b[i]; }
        for (int i = 0; i < n; i++) { int p = i; for (int r = i + 1; r < n; r++) if (Math.abs(M[r][i]) > Math.abs(M[p][i])) p = r; double[] t = M[i]; M[i] = M[p]; M[p] = t;
            for (int r = 0; r < n; r++) if (r != i) { double f = M[r][i] / M[i][i]; for (int c = i; c <= n; c++) M[r][c] -= f * M[i][c]; } }
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = M[i][n] / M[i][i]; return x;
    }

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
