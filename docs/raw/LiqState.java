import java.nio.file.*;
import java.util.*;

// Док. 244: файл интервалов состояния «ликвидации» для стенда (revx.exec.fall-file): лонги Bybit (BUY) + OKX (SELL, контракты
// 0.01/0.1/1), без Binance, BTC+ETH+SOL вместе, по времени получения на micro (available_at); горит, пока сумма за последние W с ≥ X.
// Аргументы: <liqms.csv> <W, с> <X, $> <выход>
public class LiqState {
    public static void main(String[] a) throws Exception {
        long W = Long.parseLong(a[1]) * 1000; double X = Double.parseDouble(a[2]);
        List<double[]> q = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(a[0]))) {
            String[] p = l.split(","); String ex = p[0]; if (ex.equals("binance")) continue;
            boolean lng = ex.equals("bybit") ? p[3].equals("BUY") : p[3].equals("SELL"); if (!lng) continue;
            double ct = ex.equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1;
            q.add(new double[]{Long.parseLong(p[6]), Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct});
        }
        q.sort(Comparator.comparingDouble(x -> x[0]));
        // события: +v в t, −v в t+W; состояние меняется только в эти моменты
        TreeMap<Long, Double> d = new TreeMap<>();
        for (double[] x : q) { d.merge((long) x[0], x[1], Double::sum); d.merge((long) x[0] + W, -x[1], Double::sum); }
        List<long[]> iv = new ArrayList<>(); double s = 0; long on = -1;
        for (var e : d.entrySet()) { s += e.getValue(); if (s >= X && on < 0) on = e.getKey(); else if (s < X - 1e-6 && on >= 0) { iv.add(new long[]{on, e.getKey()}); on = -1; } }
        StringBuilder sb = new StringBuilder("# лонги Bybit+OKX по available_at, три монеты, за " + a[1] + " с ≥ $" + a[2] + "; МОНЕТА;с_мс;по_мс\n");
        long tot = 0; for (long[] x : iv) tot += x[1] - x[0];
        for (String c : new String[]{"BTC", "ETH", "SOL"}) for (long[] x : iv) sb.append(c).append(';').append(x[0]).append(';').append(x[1]).append('\n');
        Files.writeString(Path.of(a[3]), sb.toString());
        double span = (q.get(q.size() - 1)[0] - q.get(0)[0]);
        System.out.printf(Locale.ROOT, "интервалов %d, горит %.2f%% времени (%.1f ч из %.0f)%n", iv.size(), 100 * tot / span, tot / 3.6e6, span / 3.6e6);
    }
}
