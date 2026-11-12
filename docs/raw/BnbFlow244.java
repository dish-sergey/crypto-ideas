import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, вопрос владельца 12.11: «на падении эфир на Revolut никто не откупал — на Бинансе так же?»
// Доля тейкеров-покупателей в $ в состоянии «ликвидации» (d274/fall/fall-lq5m1m.txt) и вне его, на трёх рынках:
// Revolut (лента revx_trade), спот Бинанса USDC (свечи 1 с: taker_buy_quote / quote_volume), перп Бинанса USDT (свечи 1 мин).
// Плюс вечер 08.11 21:40–22:05 поминутно. Окно — где есть все три источника.
// Аргументы: <интервалы> <bnb1s> <каталог минуток перпа> <db>…
public class BnbFlow244 {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        TreeMap<Long, Long> iv = new TreeMap<>();
        for (String l : Files.readAllLines(Path.of(a[0]))) { String[] p = l.split(";"); if (p.length == 3 && p[0].equals("BTC")) iv.put(Long.parseLong(p[1]), Long.parseLong(p[2])); }
        long ev0 = Instant.parse("2026-11-08T21:40:00Z").toEpochMilli(), ev1 = Instant.parse("2026-11-08T22:05:00Z").toEpochMilli();
        for (String coin : new String[]{"ETH", "BTC", "SOL"}) {
            // [рынок][0 — в состоянии, 1 — вне][покупатели, все]
            double[][][] s = new double[3][2][2];
            TreeMap<Long, double[]> evm = new TreeMap<>(); // минута → rev buy, rev all, spot buy, spot all, perp buy, perp all, close
            long tA = Long.MAX_VALUE, tB = 0;
            // спот 1 с
            try (var st = Files.list(Path.of(a[1]))) {
                for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDC-1s-")).sorted().toList())
                    try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
                        if (zi.getNextEntry() == null) continue;
                        BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
                        for (String l; (l = r.readLine()) != null; ) {
                            if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue;
                            String[] p = l.split(","); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000;
                            if (t < iv.firstKey() - 86_400_000L) continue;
                            double q = Double.parseDouble(p[7]), b = Double.parseDouble(p[10]);
                            int in = on(iv, t) ? 0 : 1; s[1][in][0] += b; s[1][in][1] += q;
                            tA = Math.min(tA, t); tB = Math.max(tB, t);
                            if (t >= ev0 && t < ev1) { double[] m = evm.computeIfAbsent(t / 60000, k -> new double[7]); m[2] += b; m[3] += q; m[6] = Double.parseDouble(p[4]); }
                        }
                    }
            }
            // перп 1 мин
            try (var st = Files.list(Path.of(a[2]))) {
                for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDT-1m-")).sorted().toList())
                    try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
                        if (zi.getNextEntry() == null) continue;
                        BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
                        for (String l; (l = r.readLine()) != null; ) {
                            if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue;
                            String[] p = l.split(","); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000;
                            if (t < tA || t > tB) continue;
                            double q = Double.parseDouble(p[7]), b = Double.parseDouble(p[10]);
                            int in = on(iv, t + 30_000) ? 0 : 1; s[2][in][0] += b; s[2][in][1] += q;   // минута — по её середине
                            if (t >= ev0 && t < ev1) { double[] m = evm.computeIfAbsent(t / 60000, k -> new double[7]); m[4] += b; m[5] += q; }
                        }
                    }
            }
            // Revolut
            Set<String> seen = new HashSet<>();
            for (int k = 3; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement("select trade_id, ts_ms, price*qty, side from revx_trade where symbol = ? and ts_ms >= ? and ts_ms <= ?")) {
                ps.setString(1, coin + "/USDC"); ps.setLong(2, tA); ps.setLong(3, tB);
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        if (!seen.add(r.getString(1))) continue;
                        long t = r.getLong(2); double u = r.getDouble(3); boolean b = "buy".equalsIgnoreCase(r.getString(4));
                        int in = on(iv, t) ? 0 : 1; if (b) s[0][in][0] += u; s[0][in][1] += u;
                        if (t >= ev0 && t < ev1) { double[] m = evm.computeIfAbsent(t / 60000, z -> new double[7]); if (b) m[0] += u; m[1] += u; }
                    }
                }
            }
            String[] nm = {"Revolut", "Бинанс спот USDC", "Бинанс перп USDT"};
            System.out.printf(Locale.ROOT, "%n######## %s: %s – %s%n", coin, Instant.ofEpochMilli(tA).toString().substring(0, 10), Instant.ofEpochMilli(tB).toString().substring(0, 10));
            System.out.println("   рынок | в состоянии «ликвидации»: доля покупателей, оборот $ | вне состояния: доля, оборот $");
            for (int m = 0; m < 3; m++)
                System.out.printf(Locale.ROOT, "   %-18s | %5.1f%%  %,14.0f | %5.1f%%  %,16.0f%n", nm[m], 100 * s[m][0][0] / Math.max(1, s[m][0][1]), s[m][0][1], 100 * s[m][1][0] / Math.max(1, s[m][1][1]), s[m][1][1]);
            System.out.println("   08.11 по минутам (UTC): цена спота на конце минуты | Revolut: покупатели / все $ | спот: доля покупателей, оборот | перп: доля, оборот");
            for (var e : evm.entrySet()) {
                double[] m = e.getValue();
                System.out.printf(Locale.ROOT, "   %s %8.2f | %7.0f / %8.0f (%3.0f%%) | %3.0f%% %,10.0f | %3.0f%% %,13.0f%s%n", Instant.ofEpochMilli(e.getKey() * 60000).toString().substring(11, 16), m[6],
                        m[0], m[1], m[1] > 0 ? 100 * m[0] / m[1] : Double.NaN, m[3] > 0 ? 100 * m[2] / m[3] : Double.NaN, m[3], m[5] > 0 ? 100 * m[4] / m[5] : Double.NaN, m[5], on(iv, e.getKey() * 60000 + 30_000) ? "  ← состояние" : "");
            }
        }
    }

    static boolean on(TreeMap<Long, Long> iv, long t) { var e = iv.floorEntry(t); return e != null && t < e.getValue(); }
}
