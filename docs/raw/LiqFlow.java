import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 244: состояние «ликвидации» с порогом по встречному потоку. Берёт общий файл интервалов (три монеты одинаково) и для каждой
// монеты оставляет только те секунды, где встречного потока НЕТ: доля тейкеров-покупателей Revolut за последние W с < S
// (если оборот за W с ≥ $MIN; при меньшем обороте встречного потока не видно — состояние остаётся). Только прошлые принты.
// Аргументы: <файл интервалов> <W, с> <S, доля> <MIN, $> <выход> <db>…
public class LiqFlow {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        long W = Long.parseLong(a[1]) * 1000; double S = Double.parseDouble(a[2]), MIN = Double.parseDouble(a[3]);
        List<long[]> iv = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(a[0]))) { String[] p = l.split(";"); if (p.length == 3 && p[0].equals("BTC")) iv.add(new long[]{Long.parseLong(p[1]), Long.parseLong(p[2])}); }
        StringBuilder sb = new StringBuilder("# лонги Bybit+OKX ≥ $1 млн за 5 мин И доля покупателей Revolut по монете за " + a[1] + " с < " + a[2] + " (при обороте ≥ $" + a[3] + "); МОНЕТА;с_мс;по_мс\n");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            TreeMap<Long, double[]> tr = new TreeMap<>(); Set<String> seen = new HashSet<>();
            for (int k = 5; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement("select trade_id, ts_ms, price*qty, side from revx_trade where symbol = ? and ts_ms >= ? and ts_ms < ?")) {
                ps.setString(1, coin + "/USDC");
                for (long[] x : iv) { ps.setLong(2, x[0] - W); ps.setLong(3, x[1]); try (ResultSet r = ps.executeQuery()) { while (r.next()) { if (!seen.add(r.getString(1))) continue; double[] v = tr.computeIfAbsent(r.getLong(2), z -> new double[2]); v[ "buy".equalsIgnoreCase(r.getString(4)) ? 1 : 0] += r.getDouble(3); } } }
            }
            long on = 0, tot = 0; int n = 0;
            for (long[] x : iv) {
                long st = -1;
                for (long t = x[0]; t < x[1]; t += 1000) {
                    double s0 = 0, s1 = 0; for (double[] v : tr.subMap(t - W, true, t, true).values()) { s0 += v[0]; s1 += v[1]; }
                    boolean keep = s0 + s1 < MIN || s1 / (s0 + s1) < S;
                    tot += 1000; if (keep) on += 1000;
                    if (keep && st < 0) st = t; else if (!keep && st >= 0) { sb.append(coin).append(';').append(st).append(';').append(t).append('\n'); n++; st = -1; }
                }
                if (st >= 0) { sb.append(coin).append(';').append(st).append(';').append(x[1]).append('\n'); n++; }
            }
            System.out.printf(Locale.ROOT, "%s: принтов %d, интервалов %d, состояние остаётся %.0f%% своего времени%n", coin, tr.size(), n, 100.0 * on / Math.max(1, tot));
        }
        Files.writeString(Path.of(a[4]), sb.toString());
    }
}
