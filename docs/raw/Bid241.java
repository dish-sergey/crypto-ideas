import java.sql.*;
import java.util.*;

// 11.11 (владелец): бид ETH. По журналу стенда (одиночный прогон) и книге окна:
// 1) средний отступ бида от опоры при пустом запасе / до лота / лот и больше (по тикам с котировкой);
// 2) покупки: отступ от опоры в момент исполнения по корзинам, маркаут по опоре через 5 и 15 мин, б.п. и $;
// 3) книга под нашим бидом (раз в минуту): стоим ли первыми (наш бид выше лучшего чужого), чужой $ в 0–3 / 3–6 б.п. ниже нас,
//    расстояние до первой чужой заявки ≥ $100 ниже нас.
// Аргументы: <bot.db> <book.db> <лот $>
public class Bid241 {
    static String SYM = "ETH/USDC";
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        double lot = Double.parseDouble(a[2]); if (a.length > 3) SYM = a[3];
        TreeMap<Long, double[]> q = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select ts_ms, fair, bid, inventory, quotable from exec_quote where fair > 0 order by ts_ms")) {
            while (r.next()) q.put(r.getLong(1), new double[]{r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getInt(5)});
        }
        double[] sum = new double[3]; long[] n = new long[3];
        for (double[] v : q.values()) if (v[3] == 1 && v[1] > 0) { int g = grp(v[2] * v[0], lot); sum[g] += (v[0] - v[1]) / v[0] * 1e4; n[g]++; }
        String[] gn = {"пусто (< $1)", "до лота", "лот и больше"};
        System.out.println("== средний отступ бида от опоры по тикам с котировкой");
        for (int g = 0; g < 3; g++) System.out.printf(Locale.ROOT, "   %-14s %6.2f б.п.  (тиков %d, %.0f%%)%n", gn[g], sum[g] / Math.max(1, n[g]), n[g], 100.0 * n[g] / (n[0] + n[1] + n[2]));
        // покупки
        double[] edges = {0, 6, 8, 10, 12, 14, 18, 99};
        int B = edges.length - 1; long[] cnt = new long[B]; double[] usd = new double[B], m5 = new double[B], m15 = new double[B], m5u = new double[B], m15u = new double[B]; long[] n15 = new long[B];
        long[][] cg = new long[3][B];
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select ts_ms, qty, price from exec_fill where upper(side) = 'BUY' order by ts_ms")) {
            while (r.next()) {
                long t = r.getLong(1); double qty = r.getDouble(2), px = r.getDouble(3), v = qty * px;
                var e = q.floorEntry(t); if (e == null) continue;
                double f = e.getValue()[0], off = (f - px) / f * 1e4; int g = grp(e.getValue()[2] * f, lot);
                int k = 0; while (k < B - 1 && off >= edges[k + 1]) k++;
                var e5 = q.ceilingEntry(t + 300_000); var e15 = q.ceilingEntry(t + 900_000);
                if (e5 == null || e5.getKey() > t + 360_000) continue;
                cnt[k]++; usd[k] += v; cg[g][k]++;
                double x5 = (e5.getValue()[0] / px - 1) * 1e4; m5[k] += x5 * v; m5u[k] += x5 * v / 1e4;
                if (e15 != null && e15.getKey() <= t + 960_000) { double x15 = (e15.getValue()[0] / px - 1) * 1e4; m15[k] += x15 * v; m15u[k] += x15 * v / 1e4; n15[k]++; }
            }
        }
        System.out.println("== покупки по отступу от опоры в момент исполнения | штук | доля | из них при пустом запасе | маркаут 5 мин, б.п. / $ | 15 мин, б.п. / $");
        long tot = Arrays.stream(cnt).sum();
        for (int k = 0; k < B; k++) if (cnt[k] > 0)
            System.out.printf(Locale.ROOT, "   %4.0f–%-4.0f | %4d | %4.1f%% | %4d | %+6.2f / %+6.2f | %+6.2f / %+6.2f%n", edges[k], edges[k + 1], cnt[k], 100.0 * cnt[k] / tot, cg[0][k],
                    m5[k] / usd[k], m5u[k], m15[k] / usd[k], m15u[k]);
        // книга раз в минуту
        long first = 0, ticks = 0; double thin3 = 0, thin6 = 0; long[] gapH = new long[5]; // <2, 2–4, 4–6, 6–10, ≥10 б.п. до чужого ≥ $100
        long firstGap6 = 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[1] + "?mode=ro");
             PreparedStatement ps = c.prepareStatement("select deep_bids, bp1, bq1, bp2, bq2, bp3, bq3, bp4, bq4, bp5, bq5 from revx_book where symbol = '" + SYM + "' and t_recv_ms <= ? order by t_recv_ms desc limit 1")) {
            long last = -1L << 50;
            for (var e : q.entrySet()) {
                double[] v = e.getValue(); if (v[3] != 1 || !(v[1] > 0) || e.getKey() - last < 60_000) continue; last = e.getKey();
                ps.setLong(1, e.getKey());
                List<double[]> lv = new ArrayList<>();
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) continue;
                    String d = r.getString(1);
                    for (int i = 0; i < 5; i++) { double p = r.getDouble(2 + 2 * i); if (p > 0) lv.add(new double[]{p, r.getDouble(3 + 2 * i)}); }
                    double lowest = lv.isEmpty() ? Double.MAX_VALUE : lv.get(lv.size() - 1)[0];
                    if (d != null && !d.isEmpty()) for (String x : d.split(",")) { String[] p = x.split(":"); double pr = Double.parseDouble(p[0]); if (pr < lowest - 1e-9) lv.add(new double[]{pr, Double.parseDouble(p[1])}); }
                }
                if (lv.isEmpty()) continue;
                double bid = v[1], f = v[0]; ticks++;
                boolean isFirst = bid > lv.get(0)[0] + 1e-9; if (isFirst) first++;
                double s3 = 0, s6 = 0, gap = 99;
                for (double[] l : lv) {
                    if (l[0] >= bid - 1e-9) continue;
                    double bp = (bid - l[0]) / f * 1e4, u = l[0] * l[1];
                    if (bp <= 3) s3 += u; else if (bp <= 6) s6 += u;
                    if (u >= 100 && gap == 99) gap = bp;
                }
                thin3 += s3; thin6 += s6;
                gapH[gap < 2 ? 0 : gap < 4 ? 1 : gap < 6 ? 2 : gap < 10 ? 3 : 4]++;
                if (isFirst && gap >= 6) firstGap6++;
            }
        }
        System.out.printf(Locale.ROOT, "== книга под нашим бидом (раз в минуту, %d точек): первыми стоим %.0f%%; чужих $ ниже нас: 0–3 б.п. %.0f, 3–6 б.п. %.0f (в среднем)%n", ticks, 100.0 * first / ticks, thin3 / ticks, thin6 / ticks);
        System.out.printf(Locale.ROOT, "   до первой чужой заявки ≥ $100 ниже нас: < 2 б.п. %.0f%% | 2–4 %.0f%% | 4–6 %.0f%% | 6–10 %.0f%% | ≥ 10 %.0f%%;  первыми И пусто ≥ 6 б.п.: %.0f%%%n",
                100.0 * gapH[0] / ticks, 100.0 * gapH[1] / ticks, 100.0 * gapH[2] / ticks, 100.0 * gapH[3] / ticks, 100.0 * gapH[4] / ticks, 100.0 * firstGap6 / ticks);
    }
    static int grp(double invUsd, double lot) { return invUsd < 1 ? 0 : invUsd < lot ? 1 : 2; }
}
