import java.nio.file.*;
import java.time.*;
import java.util.*;

// Док. 245, П4: состояние «ликвидации» с Binance. Лонги: Binance (SELL), Bybit (BUY), OKX (SELL, контракты 0.01/0.1/1); по времени
// получения на micro; горит, пока сумма за W с ≥ X. Порог X подбирается по ДОЛЕ ВРЕМЕНИ (цель — аргумент), а не по итогу окон.
// Печатает долю времени с Binance и без, и включения/выключения вокруг заданных моментов.
// Аргументы: <liqms.csv> <W, с> <цель доли времени> <выход> <момент ISO>…
public class LiqStateB {
    record Ev(long t, double usd, boolean bnb) {}

    public static void main(String[] a) throws Exception {
        long W = Long.parseLong(a[1]) * 1000; double target = Double.parseDouble(a[2]);
        List<Ev> q = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(a[0]))) {
            String[] p = l.split(","); String ex = p[0];
            boolean lng = ex.equals("bybit") ? p[3].equals("BUY") : p[3].equals("SELL"); if (!lng) continue;
            double ct = ex.equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1;
            q.add(new Ev(Long.parseLong(p[6]), Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct, ex.equals("binance")));
        }
        q.sort(Comparator.comparingLong(Ev::t));
        long t0 = q.get(0).t(), t1 = q.get(q.size() - 1).t();
        System.out.printf(Locale.ROOT, "лонгов %d, %s – %s%n", q.size(), Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t1));
        System.out.printf(Locale.ROOT, "без Binance, порог $1 млн: горит %.2f%% времени%n", 100 * share(intervals(q, W, 1e6, false), t0, t1));
        double lo = 1e5, hi = 1e8;
        for (int i = 0; i < 60; i++) { double m = Math.sqrt(lo * hi); if (share(intervals(q, W, m, true), t0, t1) > target) lo = m; else hi = m; }
        double X = Math.sqrt(lo * hi);
        List<long[]> iv = intervals(q, W, X, true);
        System.out.printf(Locale.ROOT, "с Binance: порог $%.2f млн → горит %.2f%% времени, интервалов %d%n", X / 1e6, 100 * share(iv, t0, t1), iv.size());
        StringBuilder sb = new StringBuilder("# лонги Binance+Bybit+OKX по available_at, три монеты, за " + a[1] + " с ≥ $" + String.format(Locale.ROOT, "%.0f", X) + "; МОНЕТА;с_мс;по_мс\n");
        for (String c : new String[]{"BTC", "ETH", "SOL"}) for (long[] x : iv) sb.append(c).append(';').append(x[0]).append(';').append(x[1]).append('\n');
        Files.writeString(Path.of(a[3]), sb.toString());
        List<long[]> ivNo = intervals(q, W, 1e6, false);
        for (int k = 4; k < a.length; k++) {
            long m = Instant.parse(a[k]).toEpochMilli();
            System.out.printf("%n%s:%n", a[k]);
            for (String nm : new String[]{"с Binance", "без Binance ($1 млн)"}) {
                List<long[]> L = nm.startsWith("с ") ? iv : ivNo;
                for (long[] x : L) if (x[1] >= m - 3_600_000L && x[0] <= m + 3_600_000L)
                    System.out.printf(Locale.ROOT, "   %-20s %s – %s (включилось за %+.0f с до момента)%n", nm, Instant.ofEpochMilli(x[0]).toString().substring(11, 19), Instant.ofEpochMilli(x[1]).toString().substring(11, 19), (m - x[0]) / 1000.0);
            }
        }
    }

    static double share(List<long[]> iv, long t0, long t1) { long s = 0; for (long[] x : iv) s += x[1] - x[0]; return (double) s / (t1 - t0); }

    static List<long[]> intervals(List<Ev> q, long W, double X, boolean bnb) {
        TreeMap<Long, Double> d = new TreeMap<>();
        for (Ev e : q) { if (e.bnb() && !bnb) continue; d.merge(e.t(), e.usd(), Double::sum); d.merge(e.t() + W, -e.usd(), Double::sum); }
        List<long[]> iv = new ArrayList<>(); double s = 0; long on = -1;
        for (var e : d.entrySet()) { s += e.getValue(); if (s >= X && on < 0) on = e.getKey(); else if (s < X - 1e-6 && on >= 0) { iv.add(new long[]{on, e.getKey()}); on = -1; } }
        return iv;
    }
}
