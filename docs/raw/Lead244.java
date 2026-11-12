import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244: опережают ли ликвидации свипы на Revolut (и успевает ли бот снять бид).
// Свип вниз на Revolut: принты тейкера-продавца, склеенные при разрыве ≤ 100 мс; глубокий — дошёл до ≥ D б.п. ниже середины книги
// перед первым принтом и набрал ≥ $1000. Ликвидации лонгов: Bybit (BUY), OKX (SELL, контракты 0.01/0.1/1), Binance (SELL); время —
// available_at (когда коллектор на micro их получил), три монеты вместе. Для каждого свипа: сколько лонгов пришло за 2/5/10/30/60 с до
// первого принта, и за сколько секунд до него сработал триггер «за 10 с ≥ X». Контроль — те же величины в каждую 10-ю секунду окна.
// Второй триггер для сравнения — ход Бинанса (спот USDC, 1 с) за 3 с ≤ −J б.п.
// Аргументы: <liqms.csv> <bnb1s> <db>…
public class Lead244 {
    static final String[] COINS = {"BTC", "ETH", "SOL"};
    static final double[] DEPTH = {20, 40};
    static final double[] TRG = {1e5, 2.5e5, 1e6};
    static final int[] WIN = {2, 10, 60, 180, 300, 600};
    static long[] lt; static double[] lc; static long[] ltB; static double[] lcB;

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        List<double[]> all = new ArrayList<>(), nob = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(a[0]))) {
            String[] p = l.split(","); String ex = p[0];
            boolean lng = ex.equals("bybit") ? p[3].equals("BUY") : p[3].equals("SELL"); if (!lng) continue;
            double ct = ex.equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1;
            double[] x = {Long.parseLong(p[6]), Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct, Long.parseLong(p[2])};
            all.add(x); if (!ex.equals("binance")) nob.add(x);
        }
        Object[] A = cumul(all), B = cumul(nob); lt = (long[]) A[0]; lc = (double[]) A[1]; ltB = (long[]) B[0]; lcB = (double[]) B[1];
        double lagMed = all.stream().mapToDouble(x -> x[0] - x[2]).sorted().skip(all.size() / 2).findFirst().orElse(0);
        System.out.printf(Locale.ROOT, "ликвидаций лонгов %d (без Binance %d); задержка available_at − время биржи: медиана %.0f мс%n", all.size(), nob.size(), lagMed);
        for (String coin : COINS) {
            TreeMap<Long, Float> bn = bnb(a[1], coin);
            String sym = coin + "/USDC"; Set<String> seen = new HashSet<>();
            List<double[]> sw = new ArrayList<>(); // t0, $, глубина б.п.
            long tMin = Long.MAX_VALUE, tMax = 0;
            for (int k = 2; k < a.length; k++) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[k] + "?mode=ro");
                 PreparedStatement pb = c.prepareStatement("select bp1, ap1 from revx_book where symbol = ? and t_recv_ms <= ? order by t_recv_ms desc limit 1");
                 ResultSet r = c.createStatement().executeQuery("select trade_id, ts_ms, price, qty, side from revx_trade where symbol = '" + sym + "' and ts_ms >= 1789444800000 order by ts_ms")) {
                pb.setString(1, sym);
                long st = -1, last = -1; double usd = 0, minPx = 1e18;
                while (true) {
                    boolean has = r.next();
                    if (has && !seen.add(r.getString(1))) continue;
                    long t = has ? r.getLong(2) : Long.MAX_VALUE; boolean sell = has && "sell".equalsIgnoreCase(r.getString(5));
                    if (st >= 0 && (!has || t - last > 100 || !sell)) {
                        if (usd >= 1000) { pb.setLong(2, st - 1); try (ResultSet b = pb.executeQuery()) { if (b.next() && b.getDouble(1) > 0 && b.getDouble(2) > 0) { double mid = (b.getDouble(1) + b.getDouble(2)) / 2; sw.add(new double[]{st, usd, (mid - minPx) / mid * 1e4}); } } }
                        st = -1;
                    }
                    if (!has) break;
                    tMin = Math.min(tMin, t); tMax = Math.max(tMax, t);
                    if (!sell) continue;
                    if (st < 0) { st = t; usd = 0; minPx = 1e18; }
                    last = t; usd += r.getDouble(3) * r.getDouble(4); minPx = Math.min(minPx, r.getDouble(3));
                }
            }
            sw.sort(Comparator.comparingDouble(x -> x[0]));
            System.out.printf(Locale.ROOT, "%n######## %s: свипов вниз ≥ $1000 %d, окно %s – %s%n", coin, sw.size(), Instant.ofEpochMilli(tMin), Instant.ofEpochMilli(tMax));
            // контроль: каждая 10-я секунда, где есть лента (минуты с принтами)
            Set<Long> liveMin = new HashSet<>(); for (double[] x : sw) liveMin.add((long) x[0] / 3_600_000L);
            List<Long> ctl = new ArrayList<>(); for (long t = tMin; t < tMax; t += 10_000) ctl.add(t);
            for (double dp : DEPTH) {
                List<double[]> g = sw.stream().filter(x -> x[2] >= dp).toList();
                System.out.printf(Locale.ROOT, "%n== глубокие свипы ≥ %.0f б.п.: %d (в сутки %.1f)%n", dp, g.size(), g.size() / ((tMax - tMin) / 86_400_000.0));
                System.out.println("   доля свипов (и доля секунд контроля), где лонгов за окно до первого принта ≥ порога; все биржи | без Binance");
                for (double th : TRG) {
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "   ≥ $%5.0fk:", th / 1e3));
                    for (int w : WIN) {
                        final int ww = w;
                        double fs = g.stream().filter(x -> L(lt, lc, (long) x[0], ww) >= th).count() / (double) Math.max(1, g.size());
                        double fc = ctl.stream().filter(t -> L(lt, lc, t, ww) >= th).count() / (double) ctl.size();
                        double fsB = g.stream().filter(x -> L(ltB, lcB, (long) x[0], ww) >= th).count() / (double) Math.max(1, g.size());
                        sb.append(String.format(Locale.ROOT, " %2d с: %4.1f%% (%.2f%%) | %4.1f%%;", w, 100 * fs, 100 * fc, 100 * fsB));
                    }
                    System.out.println(sb);
                }
                // опережение триггера «за 10 с ≥ X»: первый момент в [t0 − 120 с, t0], когда сработал
                System.out.println("   опережение: за сколько секунд до первого принта свипа впервые сработал триггер (в пределах 120 с); доля свипов с опережением ≥ 1 / 2 / 5 с");
                for (double th : TRG) {
                    List<Double> ld = new ArrayList<>(); int n1 = 0, n2 = 0, n5 = 0;
                    for (double[] x : g) { double v = leadLiq((long) x[0], th); if (v >= 0) { ld.add(v); if (v >= 1) n1++; if (v >= 2) n2++; if (v >= 5) n5++; } }
                    Collections.sort(ld);
                    System.out.printf(Locale.ROOT, "   ликвидации ≥ $%.0fk за 10 с: сработал до свипа у %d из %d; медиана опережения %.1f с; ≥1 с %.0f%%, ≥2 с %.0f%%, ≥5 с %.0f%%%n",
                            th / 1e3, ld.size(), g.size(), ld.isEmpty() ? Double.NaN : ld.get(ld.size() / 2), 100.0 * n1 / Math.max(1, g.size()), 100.0 * n2 / Math.max(1, g.size()), 100.0 * n5 / Math.max(1, g.size()));
                }
                for (double j : new double[]{10, 20}) {
                    List<Double> ld = new ArrayList<>(); int n1 = 0, n2 = 0, n5 = 0, cov = 0;
                    for (double[] x : g) { if (bn.floorKey((long) x[0] / 1000) == null || (long) x[0] / 1000 - bn.floorKey((long) x[0] / 1000) > 5) continue; cov++; double v = leadBnb(bn, (long) x[0], j); if (v >= 0) { ld.add(v); if (v >= 1) n1++; if (v >= 2) n2++; if (v >= 5) n5++; } }
                    long fire = 0, tot = 0; for (long t : ctl) { Float c0 = bn.get(t / 1000), c3 = bn.get(t / 1000 - 3); if (c0 == null || c3 == null) continue; tot++; if ((c0 / c3 - 1) * 1e4 <= -j) fire++; }
                    Collections.sort(ld);
                    System.out.printf(Locale.ROOT, "   Бинанс за 3 с ≤ −%.0f б.п.: сработал до свипа у %d из %d (с данными); медиана опережения %.1f с; ≥1 с %.0f%%, ≥2 с %.0f%%, ≥5 с %.0f%%; горит в %.2f%% секунд контроля%n",
                            j, ld.size(), cov, ld.isEmpty() ? Double.NaN : ld.get(ld.size() / 2), 100.0 * n1 / Math.max(1, cov), 100.0 * n2 / Math.max(1, cov), 100.0 * n5 / Math.max(1, cov), 100.0 * fire / Math.max(1, tot));
                }
                // обратная сторона: после срабатывания ликвидационного триггера — как часто глубокий свип в ближайшие 30 / 120 с
                for (double th : TRG) {
                    List<Long> fires = new ArrayList<>(); long lastF = -1;
                    for (int i = 0; i < lt.length; i++) { long t = lt[i]; if (t < tMin || t > tMax) continue; if (L(lt, lc, t, 10) >= th && (lastF < 0 || t - lastF > 300_000)) { fires.add(t); lastF = t; } }
                    int h30 = 0, h120 = 0; double[] ts = g.stream().mapToDouble(x -> x[0]).toArray();
                    for (long f : fires) { int i = Arrays.binarySearch(ts, f); if (i < 0) i = -i - 1; if (i < ts.length && ts[i] - f <= 30_000) h30++; if (i < ts.length && ts[i] - f <= 120_000) h120++; }
                    System.out.printf(Locale.ROOT, "   триггер ≥ $%.0fk (включений, не чаще раза в 5 мин: %d, %.1f в сутки): глубокий свип в 30 с после — %.0f%%, в 120 с — %.0f%%%n",
                            th / 1e3, fires.size(), fires.size() / ((tMax - tMin) / 86_400_000.0), 100.0 * h30 / Math.max(1, fires.size()), 100.0 * h120 / Math.max(1, fires.size()));
                }
            }
            // хронология трёх вечеров
            long[][] ev = {{ms("2026-10-29T18:00:00Z"), ms("2026-10-29T23:59:00Z")}, {ms("2026-11-05T14:30:00Z"), ms("2026-11-05T15:30:00Z")}, {ms("2026-11-08T21:40:00Z"), ms("2026-11-08T22:00:00Z")}};
            System.out.println("\n   хронология: свипы ≥ 20 б.п. — время, $, глубина; лонгов за 10 с до (все / без Binance), $ тыс.; ход Бинанса за 3 с до, б.п.; первое срабатывание «≥ $250k за 10 с» и «Бинанс ≤ −10 б.п. за 3 с» до свипа, с");
            for (long[] e : ev) for (double[] x : sw) {
                if (x[0] < e[0] || x[0] > e[1] || x[2] < 20) continue; long t = (long) x[0];
                Float c0 = bn.get(t / 1000), c3 = bn.get(t / 1000 - 3);
                System.out.printf(Locale.ROOT, "   %s  $%7.0f  %5.1f б.п. | лонги %6.0f / %6.0f | Бинанс %s | ликв. −%s с, Бинанс −%s с%n", Instant.ofEpochMilli(t).toString().substring(5, 23), x[1], x[2],
                        L(lt, lc, t, 10) / 1e3, L(ltB, lcB, t, 10) / 1e3, c0 == null || c3 == null ? "—" : String.format(Locale.ROOT, "%+.1f", (c0 / c3 - 1) * 1e4),
                        fmt(leadLiq(t, 2.5e5)), fmt(leadBnb(bn, t, 10)));
            }
        }
    }

    static String fmt(double v) { return v < 0 ? "нет" : String.format(Locale.ROOT, "%.1f", v); }
    static long ms(String s) { return Instant.parse(s).toEpochMilli(); }

    static Object[] cumul(List<double[]> x) {
        x.sort(Comparator.comparingDouble(y -> y[0])); long[] t = new long[x.size()]; double[] c = new double[x.size()]; double z = 0;
        for (int i = 0; i < x.size(); i++) { t[i] = (long) x.get(i)[0]; z += x.get(i)[1]; c[i] = z; }
        return new Object[]{t, c};
    }
    static double cum(long[] t, double[] c, long q) { int l = 0, h = t.length; while (l < h) { int m = (l + h) >>> 1; if (t[m] <= q) l = m + 1; else h = m; } return l == 0 ? 0 : c[l - 1]; }
    static double L(long[] t, double[] c, long q, int sec) { return cum(t, c, q) - cum(t, c, q - sec * 1000L); }

    // первый момент в [t0 − 120 с, t0), когда «лонги за 10 с ≥ th» (проверяем в моменты прихода ликвидаций): опережение, с; −1 — нет
    static double leadLiq(long t0, double th) {
        int l = 0, h = lt.length; long from = t0 - 120_000; while (l < h) { int m = (l + h) >>> 1; if (lt[m] < from) l = m + 1; else h = m; }
        for (int i = l; i < lt.length && lt[i] < t0; i++) if (L(lt, lc, lt[i], 10) >= th) return (t0 - lt[i]) / 1000.0;
        return -1;
    }
    // Бинанс: первая секунда s в [t0 − 120, t0), где close(s) / close(s − 3) − 1 ≤ −j б.п.; данные секунды s известны в конце секунды (s + 1)
    static double leadBnb(TreeMap<Long, Float> bn, long t0, double j) {
        long s0 = t0 / 1000;
        for (long s = s0 - 120; s < s0; s++) { Float c0 = bn.get(s), c3 = bn.get(s - 3); if (c0 == null || c3 == null) continue; if ((c0 / c3 - 1) * 1e4 <= -j) { double v = (t0 - (s + 1) * 1000.0) / 1000.0; return v >= 0 ? v : -1; } }
        return -1;
    }

    static TreeMap<Long, Float> bnb(String dir, String coin) throws IOException {
        TreeMap<Long, Float> m = new TreeMap<>();
        try (var st = Files.list(Path.of(dir))) {
            for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDC-1s-")).sorted().toList())
                try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
                    if (zi.getNextEntry() == null) continue;
                    BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
                    for (String l; (l = r.readLine()) != null; ) { if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue; String[] p = l.split(",", 6); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000; m.put(t / 1000, Float.parseFloat(p[4])); }
                }
        }
        return m;
    }
}
