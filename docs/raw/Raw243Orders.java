import java.sql.*;
import java.util.*;

// 12.11 (владелец, docs/raw, к п. 1 — покупки из bid241*.txt): каждая покупка стенда с историей её заявки.
// Цепочка замен: заявка B — наследник A, если та же сторона и уровень и B открыта ровно в момент закрытия A (так стенд пишет replace).
// Аргументы: <окно> <бот> <монета> <лот $> <bot.db> … (по пять). Выход CSV (одна строка — один кусок исполнения exec_fill).
public class Raw243Orders {
    record O(String id, String side, int level, double price, double size, long open, long close, String status) {}

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        System.out.println(String.join(",",
                "window,bot,coin,fill_ts_utc,fill_ts_ms,venue_id,piece_no,qty,price,fill_usd,fair,offset_bp,bucket,inv_usd_before,inv_lots_before",
                "mk5_bp,mk15_bp,mk5_usd,mk15_usd",
                "order_size,order_opened_ms,age_at_price_s,order_status,filled_before_on_order,bitten_before,share_filled_before",
                "offset_at_open_bp,chain_start_ms,chain_age_s,replaces_in_chain,chain_start_price,chain_start_offset_bp,prev_price_in_chain,last_move_bp,median_gap_between_replaces_s",
                "ev_partial,ev_partial_done,ev_cancel,ev_replace_race,ev_fate_unknown,ev_ghost,stalls_60s_before,stalls_in_chain_life"));
        java.util.regex.Pattern UUID = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        String[] kinds = {"partial", "partial_done", "cancel", "replace_race", "fate_unknown", "ghost_replace"};
        for (int k = 0; k + 4 < a.length; k += 5) {
            String win = a[k], bot = a[k + 1], coin = a[k + 2], db = a[k + 4];
            double lot = Double.parseDouble(a[k + 3]);
            TreeMap<Long, double[]> q = new TreeMap<>();
            Map<String, O> ord = new HashMap<>(), byClose = new HashMap<>();
            Map<String, int[]> ev = new HashMap<>();
            TreeSet<Long> stalls = new TreeSet<>();
            Set<String> placedAt = new HashSet<>();
            List<Object[]> fills = new ArrayList<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro"); Statement s = c.createStatement()) {
                try (ResultSet r = s.executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) {
                    while (r.next()) q.put(r.getLong(1), new double[]{r.getDouble(2), r.getDouble(3)});
                }
                try (ResultSet r = s.executeQuery("select venue_id, side, level, price, size, opened_ms, closed_ms, status from exec_open_order")) {
                    while (r.next()) {
                        O o = new O(r.getString(1), r.getString(2), r.getInt(3), r.getDouble(4), r.getDouble(5), r.getLong(6), r.getLong(7), r.getString(8));
                        ord.put(o.id, o);
                        if (r.getObject(7) != null) byClose.put(o.side + "|" + o.level + "|" + o.close, o);
                    }
                }
                try (ResultSet r = s.executeQuery("select ts_ms, kind, detail from exec_event")) {
                    while (r.next()) {
                        String kd = r.getString(2), d = r.getString(3);
                        if (kd.equals("venue_stall")) { stalls.add(r.getLong(1)); continue; }
                        if (kd.equals("placed")) { placedAt.add((d == null ? "" : d.toUpperCase()) + "|" + r.getLong(1)); continue; }
                        int ix = Arrays.asList(kinds).indexOf(kd);
                        if (ix < 0 || d == null) continue;
                        var mm = UUID.matcher(d);
                        while (mm.find()) ev.computeIfAbsent(mm.group(), z -> new int[6])[ix]++;
                    }
                }
                try (ResultSet r = s.executeQuery("select ts_ms, venue_id, qty, price from exec_fill where upper(side) = 'BUY' order by ts_ms")) {
                    while (r.next()) fills.add(new Object[]{r.getLong(1), r.getString(2), r.getDouble(3), r.getDouble(4)});
                }
            }
            Map<String, double[]> seen = new HashMap<>();
            for (Object[] f : fills) {
                long t = (Long) f[0];
                String id = (String) f[1];
                double qty = (Double) f[2], px = (Double) f[3], v = qty * px;
                var e = q.floorEntry(t);
                if (e == null) continue;
                double fair = e.getValue()[0], off = (fair - px) / fair * 1e4;
                double[] sn = seen.computeIfAbsent(id, z -> new double[2]);
                int piece = (int) sn[0] + 1;
                double before = sn[1];
                sn[0]++; sn[1] += qty;
                var eb = q.lowerEntry(t);
                double invB = eb == null ? 0 : eb.getValue()[1] * eb.getValue()[0];
                var e5 = q.ceilingEntry(t + 300_000);
                var e15 = q.ceilingEntry(t + 900_000);
                String m5 = "", m15 = "", m5u = "", m15u = "";
                if (e5 != null && e5.getKey() <= t + 360_000) { double x = (e5.getValue()[0] / px - 1) * 1e4; m5 = f2(x); m5u = f4(x * v / 1e4); }
                if (e15 != null && e15.getKey() <= t + 960_000) { double x = (e15.getValue()[0] / px - 1) * 1e4; m15 = f2(x); m15u = f4(x * v / 1e4); }
                O o = ord.get(id);
                String oSize = "", oOpen = "", age = "", st = "", offOpen = "", cStart = "", cAge = "", nRep = "", cPx = "", cOff = "", prevPx = "", lastMove = "", gap = "";
                int[] evs = new int[6];
                long stIn = 0;
                if (o != null) {
                    oSize = f8(o.size); oOpen = "" + o.open; age = f1((t - o.open) / 1000.0); st = o.status == null ? "open" : o.status;
                    var eo = q.floorEntry(o.open);
                    if (eo != null) offOpen = f2((eo.getValue()[0] - o.price) / eo.getValue()[0] * 1e4);
                    List<O> chain = new ArrayList<>();
                    chain.add(o);
                    O cur = o;
                    while (chain.size() < 100000) {
                        O p = byClose.get(cur.side + "|" + cur.level + "|" + cur.open);
                        if (p == null || p == cur || "filled".equals(p.status) || placedAt.contains(cur.side.toUpperCase() + "|" + cur.open)) break;
                        chain.add(p); cur = p;
                    }
                    O first = chain.get(chain.size() - 1);
                    cStart = "" + first.open; cAge = f1((t - first.open) / 1000.0); nRep = "" + (chain.size() - 1); cPx = f2(first.price);
                    var ef = q.floorEntry(first.open);
                    if (ef != null) cOff = f2((ef.getValue()[0] - first.price) / ef.getValue()[0] * 1e4);
                    if (chain.size() > 1) { O p = chain.get(1); prevPx = f2(p.price); lastMove = f2((o.price / p.price - 1) * 1e4); }
                    if (chain.size() > 2) {
                        List<Long> g = new ArrayList<>();
                        for (int i = 0; i + 1 < chain.size(); i++) g.add(chain.get(i).open - chain.get(i + 1).open);
                        Collections.sort(g);
                        gap = f1(g.get(g.size() / 2) / 1000.0);
                    }
                    for (O x : chain) { int[] z = ev.get(x.id); if (z != null) for (int i = 0; i < 6; i++) evs[i] += z[i]; }
                    stIn = stalls.subSet(first.open, true, t, true).size();
                }
                long st60 = stalls.subSet(t - 60_000, true, t, true).size();
                String b = off < 6 ? "0-6" : off < 8 ? "6-8" : off < 10 ? "8-10" : off < 12 ? "10-12" : off < 14 ? "12-14" : off < 18 ? "14-18" : "18+";
                System.out.println(String.join(",", win, bot, coin, java.time.Instant.ofEpochMilli(t).toString(), "" + t, id, "" + piece, f8(qty), f2(px), f4(v), f2(fair), f2(off), b, f2(invB), f2(invB / lot),
                        m5, m15, m5u, m15u, oSize, oOpen, age, st, f8(before), before > 0 ? "1" : "0", o == null ? "" : f2(before / o.size),
                        offOpen, cStart, cAge, nRep, cPx, cOff, prevPx, lastMove, gap,
                        "" + evs[0], "" + evs[1], "" + evs[2], "" + evs[3], "" + evs[4], "" + evs[5], "" + st60, "" + stIn));
            }
        }
    }

    static String f1(double x) { return String.format(Locale.ROOT, "%.1f", x); }
    static String f2(double x) { return String.format(Locale.ROOT, "%.2f", x); }
    static String f4(double x) { return String.format(Locale.ROOT, "%.4f", x); }
    static String f8(double x) { return String.format(Locale.ROOT, "%.8f", x); }
}
