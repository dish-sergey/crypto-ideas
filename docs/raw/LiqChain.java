import java.nio.file.*;
import java.time.*;
import java.util.*;

// Идея владельца 14.11: цепная реакция ликвидаций — включать рано, а выключать, как только цепь прекратилась, и торговать как обычно.
// Разбор как LiqState (лонги Bybit BUY + OKX SELL с контрактами, три монеты вместе, по available_at). Шаг 1 с.
// Включение: ON = сумма за 300 с ≥ X1, или (если X0 > 0) сумма ≥ X0 синхронно (у каждой биржи ≥ 20%).
// Выключение: состояние держится, пока цепь идёт; цепь ПРЕКРАТИЛАСЬ, когда сумма за последние 60 с < Q $ подряд T с —
// тогда выключаем, даже если сумма за 300 с ещё выше порога. Новое включение — по тому же правилу ON.
// Аргументы: <X1> <X0 (0 — без раннего)> <Q, $ за 60 с> <T, с> <выход> <моменты ISO,…> <liqms.csv>…
public class LiqChain {
    public static void main(String[] a) throws Exception {
        double X1 = Double.parseDouble(a[0]), X0 = Double.parseDouble(a[1]), Q = Double.parseDouble(a[2]); long T = Long.parseLong(a[3]);
        Map<String, double[]> ev = new HashMap<>();
        for (int k = 6; k < a.length; k++) for (String l : Files.readAllLines(Path.of(a[k]))) {
            String[] p = l.split(","); String ex = p[0]; if (ex.equals("binance")) continue;
            boolean lng = ex.equals("bybit") ? p[3].equals("BUY") : p[3].equals("SELL"); if (!lng) continue;
            if (!(p[1].startsWith("BTC") || p[1].startsWith("ETH") || p[1].startsWith("SOL"))) continue;
            double ct = ex.equals("okx") ? (p[1].startsWith("BTC") ? 0.01 : p[1].startsWith("ETH") ? 0.1 : 1) : 1;
            ev.putIfAbsent(l.substring(0, l.lastIndexOf(',')), new double[]{Long.parseLong(p[6]) / 1000, Double.parseDouble(p[4]) * Double.parseDouble(p[5]) * ct, ex.equals("bybit") ? 0 : 1});
        }
        long s0 = Long.MAX_VALUE, s1 = 0; for (double[] x : ev.values()) { s0 = Math.min(s0, (long) x[0]); s1 = Math.max(s1, (long) x[0]); }
        int n = (int) (s1 - s0 + 301); double[][] per = new double[2][n];
        for (double[] x : ev.values()) per[(int) x[2]][(int) ((long) x[0] - s0)] += x[1];
        double by300 = 0, ok300 = 0, s60 = 0; boolean on = false; long quiet = 0, onAt = 0, tot = 0; int nIv = 0, nEarlyOff = 0;
        StringBuilder sb = new StringBuilder("# цепь ликвидаций: ON сумма 300 с ≥ $" + a[0] + (X0 > 0 ? " или синхронно ≥ $" + a[1] : "") + "; OFF — за 60 с < $" + a[2] + " подряд " + a[3] + " с; МОНЕТА;с_мс;по_мс\n");
        List<long[]> iv = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            by300 += per[0][i]; ok300 += per[1][i]; s60 += per[0][i] + per[1][i];
            if (i >= 300) { by300 -= per[0][i - 300]; ok300 -= per[1][i - 300]; }
            if (i >= 60) s60 -= per[0][i - 60] + per[1][i - 60];
            double s = by300 + ok300;
            boolean trig = s >= X1 || (X0 > 0 && s >= X0 && by300 >= 0.2 * s && ok300 >= 0.2 * s);
            quiet = s60 < Q ? quiet + 1 : 0;
            long t = (s0 + i) * 1000;
            if (!on && trig && quiet == 0) { on = true; onAt = t; }
            else if (on && quiet >= T) { on = false; iv.add(new long[]{onAt, t}); tot += t - onAt; if (s >= X1) nEarlyOff++; }
        }
        if (on) iv.add(new long[]{onAt, (s0 + n) * 1000});
        for (String c : new String[]{"BTC", "ETH", "SOL"}) for (long[] x : iv) sb.append(c).append(';').append(x[0]).append(';').append(x[1]).append('\n');
        Files.writeString(Path.of(a[4]), sb.toString());
        System.out.printf(Locale.ROOT, "интервалов %d, горит %.2f%% времени, выключено при сумме 300 с ещё ≥ порога: %d%n", iv.size(), 100.0 * tot / ((s1 - s0) * 1000.0), nEarlyOff);
        for (String m : a[5].split(",")) {
            long t = Instant.parse(m).toEpochMilli(); StringBuilder s = new StringBuilder("   около " + m + ":");
            for (long[] x : iv) if (x[1] > t - 3_600_000L && x[0] < t + 3_600_000L) s.append(' ').append(Instant.ofEpochMilli(x[0]).toString().substring(11, 19)).append('–').append(Instant.ofEpochMilli(x[1]).toString().substring(11, 19));
            System.out.println(s);
        }
    }
}
