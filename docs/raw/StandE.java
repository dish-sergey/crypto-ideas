import java.sql.*;
import java.util.*;

// Док. 237, блок Е: пересортировка готовых прогонов стенда (без реинвеста). Деньги как в Dd: перенос запаса + захват поминутно.
// Сумма шести ботов с переносом значения вперёд по общей сетке минут. Итог и просадка по всему окну и по участкам суток
// (рост 0–10, флет 11–20, падение 21–32 от начала окна), средний запас, перенос и захват порознь, доля оборота с маркаутом
// ≤ −84 / ≤ −50 б.п. по опоре через H минут (H = 5 и 15; сделка без тика в пределах H+1 мин не считается, как FillQ2).
// Аргументы: <каталог журналов> <окно> <метка=тег>…
public class StandE {
    static final String[][] BOTS = {{"BTC", "a"}, {"BTC", "e"}, {"ETH", "c"}, {"ETH", "d"}, {"SOL", "b"}, {"SOL", "f"}};
    static final int[][] SEG = {{0, 11}, {11, 21}, {21, 33}};

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        System.out.println("метка|итог|просадка|итог/просадка|рост: итог/просадка|флет: итог/просадка|падение: итог/просадка|средний запас $|итог на $ запаса|захват|перенос|оборот $|плохие круги −84/−50, % оборота|постановки: пик за скользящие 24 ч по ботам | пик суммы шести | упоров в предел");
        for (int k = 2; k < a.length; k++) {
            String[] lt = a[k].split("=", 2);
            List<TreeMap<Long, Double>> eqs = new ArrayList<>();
            double invAll = 0, capt = 0, carry = 0, turn = 0, b84a = 0, b50a = 0, b84b = 0, b50b = 0, turnA = 0, turnB = 0;
            long t0 = Long.MAX_VALUE;
            List<Long> allPlaced = new ArrayList<>(); StringBuilder perBot = new StringBuilder(); long limits = 0;
            for (String[] b : BOTS) {
                String db = a[0] + "/j-" + a[1] + "-d" + lt[1].replace("{g}", "acb".contains(b[1]) ? "acb" : "edf").replace("{b}", b[1]) + "-" + b[0] + "/bot-" + b[1] + ".db";
                if (!new java.io.File(db).exists()) { eqs.add(new TreeMap<>()); continue; }
                TreeMap<Long, double[]> m = new TreeMap<>(); ArrayDeque<double[]> lots = new ArrayDeque<>();
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                    try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) {
                        while (r.next()) { long t = r.getLong(1) / 60000; m.put(t, new double[]{r.getDouble(2), r.getDouble(3), 0}); }
                    }
                    List<Long> pl = new ArrayList<>();
                    try (ResultSet r = c.createStatement().executeQuery("select ts_ms from exec_event where kind = 'placed' order by ts_ms")) { while (r.next()) pl.add(r.getLong(1)); }
                    try (ResultSet r = c.createStatement().executeQuery("select count(*) from exec_event where kind in ('limit_blocked', 'sell_only')")) { r.next(); limits += r.getLong(1); }
                    allPlaced.addAll(pl); perBot.append(b[1]).append(' ').append(peak(pl)).append(' ');
                    try (ResultSet r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill order by ts_ms")) {
                        while (r.next()) {
                            long tm = r.getLong(1) / 60000;
                            var e = m.floorEntry(tm); if (e == null) continue;
                            boolean buy = r.getString(2).equals("BUY");
                            double v = r.getDouble(3) * r.getDouble(4), px = r.getDouble(4), f = e.getValue()[0];
                            double cp = (buy ? f / px - 1 : 1 - f / px) * v;
                            e.getValue()[2] += cp; capt += cp; turn += v;
                            if (buy) lots.addLast(new double[]{r.getDouble(3), px});
                            else { double left = r.getDouble(3); while (left > 1e-12 && !lots.isEmpty()) { double[] l = lots.peekFirst(); double t = Math.min(left, l[0]), vv = t * l[1], bp = (px / l[1] - 1) * 1e4; turnA += vv; if (bp <= -84) b84a += vv; if (bp <= -50) b50a += vv; l[0] -= t; left -= t; if (l[0] <= 1e-12) lots.pollFirst(); } }
                        }
                    }
                }
                if (m.isEmpty()) { eqs.add(new TreeMap<>()); continue; }
                t0 = Math.min(t0, m.firstKey());
                TreeMap<Long, Double> eq = new TreeMap<>();
                double e = 0, s = 0; int n = 0; double[] last = null;
                for (var x : m.entrySet()) {
                    double[] v = x.getValue();
                    if (last != null) { double cr = last[1] * (v[0] - last[0]); e += cr; carry += cr; }
                    e += v[2]; eq.put(x.getKey(), e);
                    s += v[1] * v[0]; n++; last = v;
                }
                invAll += s / Math.max(1, n);
                eqs.add(eq);
            }
            TreeSet<Long> keys = new TreeSet<>(); for (var q : eqs) keys.addAll(q.keySet());
            TreeMap<Long, Double> sum = new TreeMap<>();
            for (long t : keys) { double z = 0; for (var q : eqs) { var f = q.floorEntry(t); if (f != null) z += f.getValue(); } sum.put(t, z); }
            double[] all = dd(sum, Long.MIN_VALUE, Long.MAX_VALUE);
            StringBuilder sb = new StringBuilder();
            for (int[] sg : SEG) {
                double[] r = dd(sum, t0 + sg[0] * 1440L, t0 + sg[1] * 1440L);
                sb.append(String.format(Locale.ROOT, "|%.2f / %.2f", r[0], r[1]));
            }
            System.out.printf(Locale.ROOT, "%s|%.2f|%.2f|%.1f%s|%.1f|%.3f|%.2f|%.2f|%.0f|%.1f / %.1f%n", lt[0], all[0], all[1], all[0] / Math.max(0.01, all[1]), sb,
                    invAll, all[0] / Math.max(0.01, invAll), capt, carry, turn, 100 * b84a / turnA, 100 * b50a / turnA);
            Collections.sort(allPlaced); System.out.printf(Locale.ROOT, "   постановки %s: %s| сумма шести %d | упоров %d%n", lt[0], perBot, peak(allPlaced), limits);
            StringBuilder pb = new StringBuilder(); for (int i = 0; i < BOTS.length; i++) { if (eqs.get(i).isEmpty()) { pb.append(" ").append(BOTS[i][1]).append(" — |"); continue; } double[] r = dd(eqs.get(i), Long.MIN_VALUE, Long.MAX_VALUE); pb.append(String.format(Locale.ROOT, " %s %s %.2f / %.2f |", BOTS[i][1], BOTS[i][0], r[0], r[1])); }
            System.out.printf("   по ботам %s:%s%n", lt[0], pb);
        }
    }

    static int peak(List<Long> ts) { int best = 0, j = 0; for (int i = 0; i < ts.size(); i++) { while (ts.get(i) - ts.get(j) >= 86_400_000L) j++; best = Math.max(best, i - j + 1); } return best; }

    // итог и наибольшая просадка суммы на [from, to) минут; итог — от последнего значения до участка
    static double[] dd(TreeMap<Long, Double> sum, long from, long to) {
        var start = sum.lowerEntry(from);
        double base = start != null ? start.getValue() : 0, pk = base, d = 0, end = base;
        for (double v : sum.subMap(from == Long.MIN_VALUE ? sum.firstKey() : from, true, to == Long.MAX_VALUE ? sum.lastKey() + 1 : to, false).values()) {
            pk = Math.max(pk, v); d = Math.max(d, pk - v); end = v;
        }
        return new double[]{end - base, d};
    }
}
