import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 246 §3: файл состояния «ликвидации» с ОТНОСИТЕЛЬНЫМ порогом для стенда. Тот же разбор, что LiqState (лонги Bybit BUY +
// OKX SELL с контрактами, три монеты вместе, по available_at, окно W). Порог суток D — уровень, выше которого сумма лежала P%
// времени на [D − 30 сут., D) (пересчёт раз в сутки в 00:00 UTC, как служба на micro). Истории до 15.09 — из архива crypto.db.
// Аргументы: <crypto.db архива> <до, мс (что брать из архива)> <W, с> <P, %> <выход> <liqms.csv>…
public class LiqRel {
    public static void main(String[] a) throws Exception {
        long W = Long.parseLong(a[2]) * 1000; double P = Double.parseDouble(a[3]);
        TreeMap<String, double[]> ev = new TreeMap<>();   // ключ дедупа → t, $
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[0] + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("select exchange, symbol, ts, side, price, qty, available_at from liquidations where available_at < " + a[1]
                     + " and available_at >= " + (Long.parseLong(a[1]) - 40L * 86_400_000L) + " and exchange in ('bybit','okx') and (symbol like 'BTC%' or symbol like 'ETH%' or symbol like 'SOL%')")) {
            while (r.next()) add(ev, r.getString(1), r.getString(2), r.getLong(3), r.getString(4), r.getDouble(5), r.getDouble(6), r.getLong(7));
        }
        for (int k = 5; k < a.length; k++) for (String l : Files.readAllLines(Path.of(a[k]))) { String[] p = l.split(",");
            add(ev, p[0], p[1], Long.parseLong(p[2]), p[3], Double.parseDouble(p[4]), Double.parseDouble(p[5]), Long.parseLong(p[6])); }
        long[] t = new long[ev.size()]; double[] v = new double[ev.size()]; int i = 0;
        List<double[]> xs = new ArrayList<>(ev.values()); xs.sort(Comparator.comparingDouble(x -> x[0]));
        for (double[] x : xs) { t[i] = (long) x[0]; v[i] = x[1]; i++; }
        System.out.printf("лонгов %d, %s – %s%n", t.length, java.time.Instant.ofEpochMilli(t[0]), java.time.Instant.ofEpochMilli(t[t.length - 1]));
        // ступенчатая сумма за окно
        TreeMap<Long, Double> d = new TreeMap<>();
        for (int j = 0; j < t.length; j++) { d.merge(t[j], v[j], Double::sum); d.merge(t[j] + W, -v[j], Double::sum); }
        long day0 = 1789430400000L, dayN = (t[t.length - 1] / 86_400_000L) * 86_400_000L;   // 15.09 … последние полные сутки
        StringBuilder sb = new StringBuilder("# лонги Bybit+OKX по available_at, три монеты, за " + a[2] + " с ≥ порога (" + a[3] + "% времени за прошлые 30 сут., пересчёт в 00:00); МОНЕТА;с_мс;по_мс\n");
        List<long[]> iv = new ArrayList<>(); long totOn = 0, span = 0;
        for (long D = day0; D <= dayN; D += 86_400_000L) {
            double thr = thr(d, Math.max(t[0], D - 30L * 86_400_000L), D, P);
            // интервалы в сутках D по этому порогу
            double s = 0; long on = -1, onDay = 0;
            for (var e : d.headMap(D, true).entrySet()) s += e.getValue();   // значение на D (включая события в D)
            if (s >= thr) on = D;
            for (var e : d.subMap(D, false, D + 86_400_000L, false).entrySet()) {
                s += e.getValue();
                if (s >= thr && on < 0) on = e.getKey();
                else if (s < thr - 1e-6 && on >= 0) { iv.add(new long[]{on, e.getKey()}); onDay += e.getKey() - on; on = -1; }
            }
            if (on >= 0) { iv.add(new long[]{on, D + 86_400_000L}); onDay += D + 86_400_000L - on; }
            totOn += onDay; span += 86_400_000L;
            System.out.printf(Locale.ROOT, "%s порог $%.0f, горит %.2f%%%n", java.time.LocalDate.ofEpochDay(D / 86_400_000L), thr, 100.0 * onDay / 86_400_000L);
        }
        // склеить интервалы на стыке суток
        List<long[]> m = new ArrayList<>();
        for (long[] x : iv) { if (!m.isEmpty() && m.get(m.size() - 1)[1] >= x[0]) m.get(m.size() - 1)[1] = Math.max(m.get(m.size() - 1)[1], x[1]); else m.add(x.clone()); }
        for (String c : new String[]{"BTC", "ETH", "SOL"}) for (long[] x : m) sb.append(c).append(';').append(x[0]).append(';').append(x[1]).append('\n');
        Files.writeString(Path.of(a[4]), sb.toString());
        System.out.printf(Locale.ROOT, "интервалов %d, горит %.2f%% времени%n", m.size(), 100.0 * totOn / span);
    }

    static void add(TreeMap<String, double[]> ev, String ex, String sym, long ts, String side, double px, double qty, long av) {
        boolean lng = ex.equals("bybit") ? side.equals("BUY") : ex.equals("okx") && side.equals("SELL"); if (!lng) return;
        if (!(sym.startsWith("BTC") || sym.startsWith("ETH") || sym.startsWith("SOL"))) return;
        double ct = ex.equals("okx") ? (sym.startsWith("BTC") ? 0.01 : sym.startsWith("ETH") ? 0.1 : 1) : 1;
        ev.putIfAbsent(ex + "|" + sym + "|" + ts + "|" + side + "|" + px + "|" + qty, new double[]{av, px * qty * ct});
    }

    static double thr(TreeMap<Long, Double> d, long from, long to, double P) {
        List<double[]> st = new ArrayList<>(); double s = 0; long prev = from;
        for (var e : d.headMap(to, false).entrySet()) {
            long k = e.getKey();
            if (k > from) { if (k > prev) st.add(new double[]{s, k - prev}); prev = k; }
            s += e.getValue();
        }
        if (to > prev) st.add(new double[]{s, to - prev});
        double total = st.stream().mapToDouble(x -> x[1]).sum(), want = total * P / 100, acc = 0;
        st.sort((x, y) -> Double.compare(y[0], x[0]));
        for (double[] x : st) { acc += x[1]; if (acc >= want) return Math.max(1, x[0]); }
        return 1;
    }
}
