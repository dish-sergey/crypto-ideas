import java.nio.file.*;
import java.time.*;
import java.util.*;

// Идея владельца 14.11: синхронные ликвидации на нескольких биржах — большая проблема, одиночные — сбой одной биржи.
// Состояние «ликвидации» горит, пока за W с: сумма лонгов Bybit + OKX ≥ X1 (как lq5m1m) ИЛИ сумма ≥ X0 и у КАЖДОЙ биржи ≥ S
// доли суммы (синхронно). Разбор как LiqState (Bybit BUY, OKX SELL с контрактами, три монеты вместе, по available_at).
// Печатает долю времени и включения вокруг моментов.
// Аргументы: <W, с> <X1, $> <X0, $> <S, доля> <выход> <момент ISO,…> <liqms.csv>…
public class LiqSync {
    public static void main(String[] a) throws Exception {
        long W = Long.parseLong(a[0]) * 1000; double X1 = Double.parseDouble(a[1]), X0 = Double.parseDouble(a[2]), S = Double.parseDouble(a[3]);
        Map<String, double[]> ev = new HashMap<>();
        for (int k = 6; k < a.length; k++) for (String l : Files.readAllLines(Path.of(a[k]))) {
            String[] p = l.split(","); String ex = p[0]; if (ex.equals("binance")) continue;
            boolean lng = ex.equals("bybit") ? p[3].equals("BUY") : p[3].equals("SELL"); if (!lng) continue;
            if (!(p[1].startsWith("BTC") || p[1].startsWith("ETH") || p[1].startsWith("SOL"))) continue;
            double ct = ex.equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1;
            ev.putIfAbsent(l.substring(0, l.lastIndexOf(',')), new double[]{Long.parseLong(p[6]), Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct, ex.equals("bybit") ? 0 : 1});
        }
        // ступени: Δ по биржам
        TreeMap<Long, double[]> d = new TreeMap<>();
        for (double[] x : ev.values()) { int e = (int) x[2];
            d.computeIfAbsent((long) x[0], z -> new double[2])[e] += x[1]; d.computeIfAbsent((long) x[0] + W, z -> new double[2])[e] -= x[1]; }
        double by = 0, ok = 0; long on = -1, first = d.firstKey(), last = d.lastKey();
        List<long[]> iv = new ArrayList<>(); List<Boolean> syncStart = new ArrayList<>();
        for (var e : d.entrySet()) {
            by += e.getValue()[0]; ok += e.getValue()[1]; double s = by + ok;
            boolean big = s >= X1, sync = s >= X0 && by >= S * s && ok >= S * s;
            boolean st = big || sync;
            if (st && on < 0) { on = e.getKey(); syncStart.add(!big); }
            else if (!st && on >= 0) { iv.add(new long[]{on, e.getKey()}); on = -1; }
        }
        long tot = 0; for (long[] x : iv) tot += x[1] - x[0];
        long nSync = syncStart.stream().filter(b -> b).count();
        StringBuilder sb = new StringBuilder("# лонги Bybit+OKX по available_at, три монеты, за " + a[0] + " с: ≥ $" + a[1] + " или ≥ $" + a[2] + " и у каждой биржи ≥ " + a[3] + " суммы; МОНЕТА;с_мс;по_мс\n");
        for (String c : new String[]{"BTC", "ETH", "SOL"}) for (long[] x : iv) sb.append(c).append(';').append(x[0]).append(';').append(x[1]).append('\n');
        Files.writeString(Path.of(a[4]), sb.toString());
        System.out.printf(Locale.ROOT, "интервалов %d (начато синхронным ниже $%s: %d), горит %.2f%% времени%n", iv.size(), a[1], nSync, 100.0 * tot / (last - first));
        for (String m : a[5].split(",")) {
            long t = Instant.parse(m).toEpochMilli(); StringBuilder s = new StringBuilder("   около " + m + ":");
            for (long[] x : iv) if (x[1] > t - 3_600_000L && x[0] < t + 3_600_000L)
                s.append(' ').append(Instant.ofEpochMilli(x[0]).toString().substring(11, 19)).append('–').append(Instant.ofEpochMilli(x[1]).toString().substring(11, 19));
            System.out.println(s);
        }
    }
}
