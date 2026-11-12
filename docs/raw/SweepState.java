import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 245: состояние «глубокие прострелы» для стенда (fall-file): медиана глубины свипов продавцов Revolut (≥ $200, склейка 100 мс,
// глубина от середины книги до худшей цены) за прошлые 15 мин ≥ D б.п.; по каждой монете своё. Плюс объединение с файлом ликвидаций.
// Аргументы: <D> <выход только прострелов> <файл ликвидаций> <выход объединения> <db>…
public class SweepState {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        double D = Double.parseDouble(a[0]); long W = 900_000L;
        TreeMap<Long, Long> liq = new TreeMap<>();
        for (String l : Files.readAllLines(Path.of(a[2]))) { String[] p = l.split(";"); if (p.length == 3 && p[0].equals("BTC")) liq.put(Long.parseLong(p[1]), Long.parseLong(p[2])); }
        StringBuilder only = new StringBuilder("# медиана глубины свипов продавцов за 15 мин ≥ " + a[0] + " б.п.; МОНЕТА;с_мс;по_мс\n");
        StringBuilder uni = new StringBuilder("# прострелы ≥ " + a[0] + " б.п. ИЛИ ликвидации (" + a[2] + "); МОНЕТА;с_мс;по_мс\n");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            TreeMap<Long, Double> sw = new TreeMap<>(); Set<String> seen = new HashSet<>(); String sym = coin + "/USDC";
            for (int k = 4; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement pb = c.prepareStatement("select bp1, ap1 from revx_book where symbol = ? and t_recv_ms <= ? order by t_recv_ms desc limit 1");
                 ResultSet r = c.createStatement().executeQuery("select trade_id, ts_ms, price, qty, side from revx_trade where symbol = '" + sym + "' and ts_ms >= 1789444800000 order by ts_ms")) {
                pb.setString(1, sym); long st = -1, last = -1; double usd = 0, minPx = 1e18;
                while (true) {
                    boolean has = r.next();
                    if (has && !seen.add(r.getString(1))) continue;
                    long t = has ? r.getLong(2) : Long.MAX_VALUE; boolean sell = has && "sell".equalsIgnoreCase(r.getString(5));
                    if (st >= 0 && (!has || t - last > 100 || !sell)) {
                        if (usd >= 200) { pb.setLong(2, st - 1); try (ResultSet b = pb.executeQuery()) { if (b.next() && b.getDouble(1) > 0 && b.getDouble(2) > 0) { double mid = (b.getDouble(1) + b.getDouble(2)) / 2; sw.put(last, (mid - minPx) / mid * 1e4); } } }
                        st = -1;
                    }
                    if (!has) break; if (!sell) continue;
                    if (st < 0) { st = t; usd = 0; minPx = 1e18; }
                    last = t; usd += r.getDouble(3) * r.getDouble(4); minPx = Math.min(minPx, r.getDouble(3));
                }
            }
            // состояние меняется только при входе свипа в окно (его конец + время) и выходе (через 15 мин)
            TreeSet<Long> ev = new TreeSet<>(); for (long t : sw.keySet()) { ev.add(t + 1); ev.add(t + W + 1); }
            List<long[]> iv = new ArrayList<>(); long on = -1, tot = 0;
            for (long t : ev) {
                var sub = sw.subMap(t - W, true, t, false); boolean s = false;
                if (!sub.isEmpty()) { double[] d = sub.values().stream().mapToDouble(x -> x).sorted().toArray(); s = d[d.length / 2] >= D; }
                if (s && on < 0) on = t; else if (!s && on >= 0) { iv.add(new long[]{on, t}); tot += t - on; on = -1; }
            }
            for (long[] x : iv) only.append(coin).append(';').append(x[0]).append(';').append(x[1]).append('\n');
            // объединение с ликвидациями
            TreeMap<Long, Long> u = new TreeMap<>(liq); for (long[] x : iv) u.merge(x[0], x[1], Math::max);
            long cs = -1, ce = -1;
            for (var e : u.entrySet()) { if (cs < 0) { cs = e.getKey(); ce = e.getValue(); } else if (e.getKey() <= ce) ce = Math.max(ce, e.getValue()); else { uni.append(coin).append(';').append(cs).append(';').append(ce).append('\n'); cs = e.getKey(); ce = e.getValue(); } }
            if (cs >= 0) uni.append(coin).append(';').append(cs).append(';').append(ce).append('\n');
            long span = sw.isEmpty() ? 1 : sw.lastKey() - sw.firstKey();
            System.out.printf(Locale.ROOT, "%s: свипов %d, интервалов прострелов %d, горит %.2f%% времени%n", coin, sw.size(), iv.size(), 100.0 * tot / span);
        }
        Files.writeString(Path.of(a[1]), only.toString()); Files.writeString(Path.of(a[3]), uni.toString());
    }
}
