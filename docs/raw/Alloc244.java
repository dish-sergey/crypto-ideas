import java.sql.*;
import java.util.*;

// Док. 244, этап 0: допустимая просадка D в % капитала → раскладка. Капитал 6 × 74.17 = 445.02 (как на стенде, NOPOOL).
// Кривая денег бота поминутно как в StandE (захват + перенос). Доля потолка w ∈ [0, 1] шагом 0.05 масштабирует кривую линейно
// (лот, потолок и стоп × w, свободная часть капитала лежит в USDC). У d и f ещё выбор числа лотов: 1.2 (база) / 2.33 / 5 / 10.
// Условие: просадка суммы ≤ D·капитал на КАЖДОМ из трёх окон. Цель: наибольшая сумма итогов трёх окон.
// Поиск: покоординатный подъём из нескольких стартов. Плюс два простых ответа: общий масштаб базы и каждый бот сам по себе.
// Аргументы: <каталог журналов> <D%>…
public class Alloc244 {
    static final String[] BOT = {"a", "e", "c", "d", "b", "f"}, COIN = {"BTC", "BTC", "ETH", "ETH", "SOL", "SOL"};
    static final String[] WIN = {"w0610", "w2830", "wall"};
    static final String P = "NF100000r0.95H";
    static final String[] VAR = {"", "D2.33", "D5", "D10"}, VNAME = {"1.2", "2.33", "5", "10"};
    static final double CAP1 = 74.17, CAP = 6 * CAP1;
    static double[][][][] eq = new double[3][6][4][]; // окно, бот, вариант, минуты общей сетки окна

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (int w = 0; w < 3; w++) {
            List<TreeMap<Long, Double>> raw = new ArrayList<>();
            TreeSet<Long> keys = new TreeSet<>();
            for (int b = 0; b < 6; b++) for (int v = 0; v < 4; v++) {
                TreeMap<Long, Double> m = null;
                if (v == 0 || BOT[b].equals("d") || BOT[b].equals("f")) {
                    String tag = v == 0 ? P + "v5allo" + ("acb".contains(BOT[b]) ? "acb" : "edf") + "n" : P + "v5all" + VAR[v] + "o" + BOT[b] + "n";
                    m = load(a[0] + "/j-" + WIN[w] + "-d" + tag + "-" + COIN[b] + "/bot-" + BOT[b] + ".db");
                    if (m != null) keys.addAll(m.keySet());
                }
                raw.add(m);
            }
            long[] ks = keys.stream().mapToLong(Long::longValue).toArray();
            for (int b = 0; b < 6; b++) for (int v = 0; v < 4; v++) {
                TreeMap<Long, Double> m = raw.get(b * 4 + v);
                if (m == null) continue;
                double[] x = new double[ks.length];
                for (int i = 0; i < ks.length; i++) { var f = m.floorEntry(ks[i]); x[i] = f == null ? 0 : f.getValue(); }
                eq[w][b][v] = x;
            }
        }
        // одиночные справки
        System.out.println("== Каждый бот один, на своём капитале 74.17: итог / просадка в % капитала по окнам w0610 | w2830 | wall");
        for (int b = 0; b < 6; b++) for (int v = 0; v < 4; v++) {
            if (eq[0][b][v] == null) continue;
            StringBuilder sb = new StringBuilder(String.format("   %s %s, %s лота:", BOT[b], COIN[b], VNAME[v]));
            for (int w = 0; w < 3; w++) { double[] r = res(eq[w][b][v]); sb.append(String.format(Locale.ROOT, " %6.2f / %.2f%% |", r[0], 100 * r[1] / CAP1)); }
            System.out.println(sb);
        }
        double[] base = evalAll(new double[]{1, 1, 1, 1, 1, 1}, new int[6]);
        System.out.printf(Locale.ROOT, "%n== Нынешняя база, все шесть на полном потолке: итог %.2f / %.2f / %.2f, просадка %.2f%% / %.2f%% / %.2f%% капитала %.0f%n",
                base[0], base[1], base[2], 100 * base[3] / CAP, 100 * base[4] / CAP, 100 * base[5] / CAP, CAP);
        for (int k = 1; k < a.length; k++) {
            double D = Double.parseDouble(a[k]) / 100 * CAP;
            System.out.printf(Locale.ROOT, "%n######## допустимая просадка %s%% = $%.2f%n", a[k], D);
            // общий масштаб базы
            double s = 1; double[] r = base; double worst = Math.max(base[3], Math.max(base[4], base[5]));
            if (worst > D) s = D / worst;
            r = evalAll(new double[]{s, s, s, s, s, s}, new int[6]);
            System.out.printf(Locale.ROOT, "   А) всем одна доля потолка %.2f: итог %.2f / %.2f / %.2f (сумма %.2f), просадка %.2f / %.2f / %.2f, в деле %.0f$ из %.0f%n",
                    s, r[0], r[1], r[2], r[0] + r[1] + r[2], r[3], r[4], r[5], 6 * s * CAP1, CAP);
            // каждый бот сам: просадка бота ≤ D% своего капитала
            StringBuilder sb = new StringBuilder("   Б) каждый бот сам, просадка бота ≤ D% его 74.17:");
            double[] wB = new double[6]; int[] vB = new int[6];
            for (int b = 0; b < 6; b++) {
                double best = -1e9;
                for (int v = 0; v < 4; v++) {
                    if (eq[0][b][v] == null) continue;
                    double mx = 0; for (int w = 0; w < 3; w++) mx = Math.max(mx, res(eq[w][b][v])[1]);
                    double ww = Math.min(1, Math.floor(D / 6 / mx * 20) / 20);
                    double inc = 0; for (int w = 0; w < 3; w++) inc += ww * res(eq[w][b][v])[0];
                    if (inc > best) { best = inc; wB[b] = ww; vB[b] = v; }
                }
                sb.append(String.format(Locale.ROOT, " %s %.2f×%s", BOT[b], wB[b], VNAME[vB[b]]));
            }
            r = evalAll(wB, vB);
            System.out.println(sb);
            System.out.printf(Locale.ROOT, "      итог %.2f / %.2f / %.2f (сумма %.2f), просадка суммы %.2f / %.2f / %.2f, в деле %.0f$%n", r[0], r[1], r[2], r[0] + r[1] + r[2], r[3], r[4], r[5], Arrays.stream(wB).sum() * CAP1);
            // В) совместный подбор
            double bestS = -1e9; double[] bw = null; int[] bv = null;
            Random rnd = new Random(7);
            for (int st = 0; st < 8; st++) {
                double[] wt = new double[6]; int[] vt = new int[6];
                if (st > 0) for (int b = 0; b < 6; b++) { vt[b] = eq[0][b][1] != null ? rnd.nextInt(4) : 0; }
                for (int it = 0; it < 20; it++) {
                    boolean ch = false;
                    Integer[] ord = {0, 1, 2, 3, 4, 5}; Collections.shuffle(Arrays.asList(ord), rnd);
                    for (int b : ord) {
                        double cur = score(wt, vt, D); double bw0 = wt[b]; int bv0 = vt[b];
                        for (int v = 0; v < 4; v++) {
                            if (eq[0][b][v] == null) continue;
                            for (int q = 0; q <= 20; q++) {
                                double ow = wt[b]; int ov = vt[b]; wt[b] = q / 20.0; vt[b] = v;
                                double sc = score(wt, vt, D);
                                if (sc > cur + 1e-9) { cur = sc; bw0 = wt[b]; bv0 = v; }
                                wt[b] = ow; vt[b] = ov;
                            }
                        }
                        if (bw0 != wt[b] || bv0 != vt[b]) ch = true;
                        wt[b] = bw0; vt[b] = bv0;
                    }
                    if (!ch) break;
                }
                double sc = score(wt, vt, D);
                if (sc > bestS) { bestS = sc; bw = wt.clone(); bv = vt.clone(); }
            }
            r = evalAll(bw, bv);
            sb = new StringBuilder("   В) подбор по сумме:");
            for (int b = 0; b < 6; b++) sb.append(String.format(Locale.ROOT, " %s %.2f×%s", BOT[b], bw[b], VNAME[bv[b]]));
            System.out.println(sb);
            System.out.printf(Locale.ROOT, "      итог %.2f / %.2f / %.2f (сумма %.2f), просадка суммы %.2f / %.2f / %.2f, в деле %.0f$%n", r[0], r[1], r[2], r[0] + r[1] + r[2], r[3], r[4], r[5], Arrays.stream(bw).sum() * CAP1);
            for (int w = 0; w < 3; w++) {
                StringBuilder pb = new StringBuilder("      " + WIN[w] + " по ботам:");
                for (int b = 0; b < 6; b++) { if (bw[b] == 0) continue; double[] x = res(eq[w][b][bv[b]]); pb.append(String.format(Locale.ROOT, " %s %.2f / %.2f", BOT[b], bw[b] * x[0], bw[b] * x[1])); }
                System.out.println(pb);
            }
        }
    }

    static double score(double[] wt, int[] vt, double D) {
        double[] r = evalAll(wt, vt);
        if (r[3] > D || r[4] > D || r[5] > D) return -1e9;
        return r[0] + r[1] + r[2];
    }

    static double[] evalAll(double[] wt, int[] vt) {
        double[] out = new double[6];
        for (int w = 0; w < 3; w++) {
            int n = eq[w][0][0].length; double pk = 0, d = 0, v = 0;
            for (int i = 0; i < n; i++) {
                v = 0; for (int b = 0; b < 6; b++) if (wt[b] > 0) v += wt[b] * eq[w][b][vt[b]][i];
                pk = Math.max(pk, v); d = Math.max(d, pk - v);
            }
            out[w] = v; out[3 + w] = d;
        }
        return out;
    }

    static double[] res(double[] x) { double pk = 0, d = 0; for (double v : x) { pk = Math.max(pk, v); d = Math.max(d, pk - v); } return new double[]{x[x.length - 1], d}; }

    static TreeMap<Long, Double> load(String db) throws Exception {
        if (!new java.io.File(db).exists()) { System.err.println("нет " + db); return null; }
        TreeMap<Long, double[]> m = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
            try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) {
                while (r.next()) m.put(r.getLong(1) / 60000, new double[]{r.getDouble(2), r.getDouble(3), 0});
            }
            try (ResultSet r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill order by ts_ms")) {
                while (r.next()) {
                    var e = m.floorEntry(r.getLong(1) / 60000); if (e == null) continue;
                    double v = r.getDouble(3) * r.getDouble(4), px = r.getDouble(4), f = e.getValue()[0];
                    e.getValue()[2] += (r.getString(2).equals("BUY") ? f / px - 1 : 1 - f / px) * v;
                }
            }
        }
        TreeMap<Long, Double> eq = new TreeMap<>(); double e = 0; double[] last = null;
        for (var x : m.entrySet()) { double[] v = x.getValue(); if (last != null) e += last[1] * (v[0] - last[0]); e += v[2]; eq.put(x.getKey(), e); last = v; }
        return eq;
    }
}
