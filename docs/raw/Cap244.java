import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 4: ёмкость по глубине — сколько $ агрессивного потока Revolut доходит до расстояния δ от середины книги, за час.
// Принт: расстояние от середины последнего снимка книги ДО него (продавец-агрессор бьёт биды: (mid − p)/mid; покупатель — аски: (p − mid)/mid).
// C(δ) = Σ $ принтов с расстоянием ≥ δ / часов. Заявка на δ, стоящая первой, сняла бы из этого потока не больше C(δ).
// σ̂ — СКО минутных ходов Бинанса (спот USDC, 1 с → минуты) за прошлый час, б.п./мин; δ в единицах σ̂ — то, что нужно этапу 2.
// Ошибка — по суткам (СКО суточных значений / √суток). Наши живые сделки на ленте есть и в счёт входят: это тоже поток, дошедший до заявки.
// Аргументы: <bnb1s> <db>…
public class Cap244 {
    static final double[] DBP = {0, 4, 6, 8, 10, 12, 14, 18, 25, 40};
    static final double[] DSG = {0, 0.5, 1, 1.5, 2, 3, 5};

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            String sym = coin + "/USDC";
            // σ̂ по минутам из Бинанса
            TreeMap<Long, Float> close = new TreeMap<>();
            try (var st = Files.list(Path.of(a[0]))) {
                for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDC-1s-")).sorted().toList()) load(z, close);
            }
            Map<Long, Double> sig = new HashMap<>();
            ArrayDeque<Double> win = new ArrayDeque<>(); double s = 0, q = 0; Long prevM = null; float prevC = 0;
            for (var e : close.entrySet()) {
                if (prevM != null && e.getKey() == prevM + 1 && prevC > 0) {
                    double r = Math.log(e.getValue() / prevC) * 1e4; win.addLast(r); s += r; q += r * r;
                    if (win.size() > 60) { double o = win.pollFirst(); s -= o; q -= o * o; }
                    if (win.size() == 60) sig.put(e.getKey(), Math.sqrt(Math.max(0, q / 60 - (s / 60) * (s / 60))));
                } else { win.clear(); s = 0; q = 0; }
                prevM = e.getKey(); prevC = e.getValue();
            }
            double[] sv = sig.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double t1 = sv[sv.length / 3], t2 = sv[2 * sv.length / 3];
            // принты
            Set<String> seen = new HashSet<>();
            List<double[]> pr = new ArrayList<>(); // ts, side(0 бид / 1 аск), dist bp, usd, σ̂
            long minutesCov = 0; Map<Long, Long> dayMin = new TreeMap<>(); long[] clsMin = new long[3];
            for (int k = 1; k < a.length; k++) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro")) {
                    long b0, b1;
                    try (ResultSet r = c.createStatement().executeQuery("select min(t_recv_ms), max(t_recv_ms) from revx_book where symbol = '" + sym + "'")) { r.next(); b0 = r.getLong(1); b1 = r.getLong(2); }
                    for (long m = b0 / 60000 + 1; m < b1 / 60000; m++) {
                        Double sg = sig.get(m); if (sg == null) continue;
                        dayMin.merge(m / 1440, 1L, Long::sum); minutesCov++; clsMin[sg <= t1 ? 0 : sg <= t2 ? 1 : 2]++;
                    }
                    try (PreparedStatement ps = c.prepareStatement("select bp1, ap1 from revx_book where symbol = ? and t_recv_ms <= ? order by t_recv_ms desc limit 1");
                         ResultSet r = c.createStatement().executeQuery("select trade_id, ts_ms, price, qty, side from revx_trade where symbol = '" + sym + "' order by ts_ms")) {
                        ps.setString(1, sym);
                        while (r.next()) {
                            if (!seen.add(r.getString(1))) continue;
                            long t = r.getLong(2); Double sg = sig.get(t / 60000 - 1); if (sg == null) continue;
                            ps.setLong(2, t - 1);
                            double mid;
                            try (ResultSet b = ps.executeQuery()) { if (!b.next() || !(b.getDouble(1) > 0) || !(b.getDouble(2) > 0)) continue; mid = (b.getDouble(1) + b.getDouble(2)) / 2; }
                            double px = r.getDouble(3); boolean sell = "sell".equalsIgnoreCase(r.getString(5));
                            double d = (sell ? (mid - px) : (px - mid)) / mid * 1e4;
                            pr.add(new double[]{t, sell ? 0 : 1, d, px * r.getDouble(4), sg});
                        }
                    }
                }
            }
            double hours = minutesCov / 60.0;
            System.out.printf(Locale.ROOT, "%n######## %s: принтов %d, часов покрытия %.0f (суток %d), σ̂ терции %.2f / %.2f б.п./мин, медиана σ̂ %.2f%n", coin, pr.size(), hours, dayMin.size(), t1, t2, sv[sv.length / 2]);
            for (int side = 0; side < 2; side++) {
                final int sd = side;
                List<double[]> ps = pr.stream().filter(x -> x[1] == sd).toList();
                System.out.printf("%n== %s (агрессор %s): ёмкость C(δ) — $ в час, дошедших до δ и дальше%n", side == 0 ? "БИД" : "АСК", side == 0 ? "продавец" : "покупатель");
                System.out.println("   δ, б.п. | C(δ) $/ч | ± по суткам | принтов/ч | медиана принта $ | доля $ от принтов ≥ $1000");
                for (double dl : DBP) {
                    List<double[]> g = ps.stream().filter(x -> x[2] >= dl).toList();
                    double usd = g.stream().mapToDouble(x -> x[3]).sum(), big = g.stream().filter(x -> x[3] >= 1000).mapToDouble(x -> x[3]).sum();
                    double[] med = g.stream().mapToDouble(x -> x[3]).sorted().toArray();
                    System.out.printf(Locale.ROOT, "   %5.0f | %9.0f | %8.0f | %6.2f | %7.0f | %4.0f%%%n", dl, usd / hours, err(g, dayMin), g.size() / hours, med.length == 0 ? 0 : med[med.length / 2], usd > 0 ? 100 * big / usd : 0);
                }
                System.out.println("   в единицах σ̂ (δ/σ̂), по терциям σ̂ за час: C $/ч  тихо | средне | бурно (часов " + String.format(Locale.ROOT, "%.0f / %.0f / %.0f", clsMin[0] / 60.0, clsMin[1] / 60.0, clsMin[2] / 60.0) + ")");
                for (double k : DSG) {
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "   δ ≥ %3.1f σ̂ |", k));
                    double all = 0;
                    for (int cl = 0; cl < 3; cl++) {
                        final int cc = cl; final double kk = k;
                        double usd = ps.stream().filter(x -> cls(x[4], t1, t2) == cc && x[2] >= kk * x[4]).mapToDouble(x -> x[3]).sum();
                        all += usd;
                        sb.append(String.format(Locale.ROOT, " %8.0f", usd / Math.max(1e-9, clsMin[cl] / 60.0)));
                    }
                    sb.append(String.format(Locale.ROOT, " | все %8.0f", all / hours));
                    System.out.println(sb);
                }
            }
        }
    }

    static int cls(double s, double t1, double t2) { return s <= t1 ? 0 : s <= t2 ? 1 : 2; }

    static double err(List<double[]> g, Map<Long, Long> dayMin) {
        Map<Long, Double> d = new HashMap<>(); for (double[] x : g) d.merge((long) x[0] / 86_400_000L, x[3], Double::sum);
        List<Double> v = new ArrayList<>();
        for (var e : dayMin.entrySet()) if (e.getValue() >= 600) v.add(d.getOrDefault(e.getKey(), 0.0) / (e.getValue() / 60.0));
        if (v.size() < 2) return Double.NaN;
        double m = v.stream().mapToDouble(x -> x).average().orElse(0), s2 = v.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / (v.size() - 1);
        return Math.sqrt(s2 / v.size());
    }

    static void load(Path z, TreeMap<Long, Float> close) throws IOException {
        try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
            if (zi.getNextEntry() == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue;
                String[] p = l.split(",", 6); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000; // мкс → мс
                close.put(t / 60000, Float.parseFloat(p[4])); // последняя секунда минуты — закрытие минуты
            }
        }
    }
}
