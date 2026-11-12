import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

// Док. 244, этап 3: цена риска единицы запаса по длинной истории. Минутки перпа Бинанса 12.2021 – 11.2026 (как Hist240).
// Состояние в момент i (раз в 5 мин): σ̂ — СКО минутных ходов за прошлый час, б.п.; классы — по процентилям σ̂ на обучении (до split).
// Для удержания T = 15 / 30 / 60 мин: дисперсия хода на минуту Var(R_T)/T (её сравниваем с σ² таблицы inv-v1: BTC 22.1, ETH 44.1),
// средний ход, CVaR 5% хода, CVaR 5% худшей точки пути (минимум за [i, i+T] — спотовый запас теряет на просадке пути, а не только на конце),
// доля провалов пути глубже −100 б.п. Плюс опорное окно стенда (15.09 – 09.11.2026) — там σ² должна сойтись с таблицей.
// Аргументы: <klines мес.> <klines сут.> <from> <to> <split>
public class Risk244 {
    static final int[] T = {15, 30, 60};
    static final double[] PCT = {0, 20, 40, 60, 80, 95, 99, 100};
    static final double[] INV_S2 = {22.1, 44.1, Double.NaN}; // σ² минуты в inv-v1 (d225/inv.md); у SOL таблицы нет

    public static void main(String[] a) throws Exception {
        LocalDate from = LocalDate.parse(a[2]), to = LocalDate.parse(a[3]);
        long m0 = from.minusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 60;
        int N = (int) (to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 60 - m0);
        long split = LocalDate.parse(a[4]).atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 60 - m0;
        long refA = LocalDate.parse("2026-09-15").atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 60 - m0, refB = LocalDate.parse("2026-11-10").atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 60 - m0;
        String[] coins = {"BTC", "ETH", "SOL"};
        for (int ci = 0; ci < 3; ci++) {
            String sym = coins[ci] + "USDT";
            float[] c = new float[N];
            for (String dir : new String[]{a[0], a[1]}) try (var st = Files.list(Path.of(dir))) {
                for (Path z : st.filter(p -> p.getFileName().toString().startsWith(sym + "-1m-") && p.toString().endsWith(".zip")).toList()) load(z, c, m0, N);
            }
            int have = 0; for (float v : c) if (v > 0) have++;
            boolean[] real = new boolean[N]; for (int i = 0; i < N; i++) real[i] = c[i] > 0;
            for (int i = 1; i < N; i++) if (c[i] == 0) c[i] = c[i - 1];
            double[] cs = new double[N + 1], cq = new double[N + 1];
            for (int i = 1; i < N; i++) { double r = c[i - 1] > 0 && c[i] > 0 ? Math.log(c[i] / c[i - 1]) * 1e4 : 0; cs[i + 1] = cs[i] + r; cq[i + 1] = cq[i] + r * r; }
            // моменты
            List<double[]> rows = new ArrayList<>(); // i, σ̂, R15, M15, R30, M30, R60, M60
            for (int i = 2 * 1440; i + 61 < N; i += 5) {
                if (!real[i] || !real[i - 60] || !real[i + 60] || c[i] <= 0) continue;
                double n = 60, s = cs[i + 1] - cs[i - 59], q = cq[i + 1] - cq[i - 59];
                double sig = Math.sqrt(Math.max(0, q / n - (s / n) * (s / n)));
                double[] r = new double[2 + 2 * T.length]; r[0] = i; r[1] = sig;
                double mn = 0; int ti = 0;
                for (int j = 1; j <= 60; j++) {
                    mn = Math.min(mn, Math.log(c[i + j] / c[i]) * 1e4);
                    if (ti < T.length && j == T[ti]) { r[2 + 2 * ti] = Math.log(c[i + j] / c[i]) * 1e4; r[3 + 2 * ti] = mn; ti++; }
                }
                rows.add(r);
            }
            double[] trSig = rows.stream().filter(r -> r[0] < split).mapToDouble(r -> r[1]).sorted().toArray();
            double[] cut = new double[PCT.length];
            for (int k = 0; k < PCT.length; k++) cut[k] = k == 0 ? -1 : k == PCT.length - 1 ? 1e18 : trSig[(int) Math.min(trSig.length - 1, PCT[k] / 100 * trSig.length)];
            System.out.printf(Locale.ROOT, "%n######## %s: минут с ценой %d из %d, моментов %d (обучение %d)%n", sym, have, N, rows.size(), trSig.length);
            System.out.print("   границы σ̂ за час по процентилям обучения, б.п./мин:");
            for (int k = 1; k < PCT.length - 1; k++) System.out.printf(Locale.ROOT, " p%.0f=%.2f", PCT[k], cut[k]);
            System.out.println();
            for (String part : new String[]{"обучение 12.2021–12.2024", "проверка 01.2025–11.2026", "опорное окно стенда 15.09–09.11.2026"}) {
                System.out.printf("%n== %s%n", part);
                System.out.println("   класс σ̂ (проц.) | моментов | σ̂ ср. | T мин | Var/T б.п.² | к σ² inv-v1 | ср. ход | CVaR5% конца | CVaR5% пути | путь < −100, %");
                double[][] all = new double[T.length][];
                for (int k = 0; k + 1 < PCT.length; k++) {
                    final int kk = k;
                    List<double[]> g = new ArrayList<>();
                    for (double[] r : rows) {
                        boolean in = part.startsWith("обуч") ? r[0] < split : part.startsWith("пров") ? r[0] >= split : r[0] >= refA && r[0] < refB;
                        if (in && r[1] > cut[kk] && r[1] <= cut[kk + 1]) g.add(r);
                    }
                    if (g.size() < 30) continue;
                    double sg = g.stream().mapToDouble(r -> r[1]).average().orElse(0);
                    for (int ti = 0; ti < T.length; ti++) {
                        final int t2 = ti;
                        double[] R = g.stream().mapToDouble(r -> r[2 + 2 * t2]).toArray(), M = g.stream().mapToDouble(r -> r[3 + 2 * t2]).toArray();
                        double mu = Arrays.stream(R).average().orElse(0), v = Arrays.stream(R).map(x -> (x - mu) * (x - mu)).sum() / Math.max(1, R.length - 1);
                        long deep = Arrays.stream(M).filter(x -> x < -100).count();
                        System.out.printf(Locale.ROOT, "   %3.0f–%-3.0f | %7d | %5.2f | %3d | %7.1f | %5.2f | %+6.2f | %7.1f | %7.1f | %5.2f%n", PCT[kk], PCT[kk + 1], g.size(), sg, T[ti], v / T[ti],
                                v / T[ti] / INV_S2[ci], mu, cvar(R), cvar(M), 100.0 * deep / M.length);
                    }
                }
                // всё вместе
                List<double[]> g = new ArrayList<>();
                for (double[] r : rows) { boolean in = part.startsWith("обуч") ? r[0] < split : part.startsWith("пров") ? r[0] >= split : r[0] >= refA && r[0] < refB; if (in) g.add(r); }
                for (int ti = 0; ti < T.length; ti++) {
                    final int t2 = ti;
                    double[] R = g.stream().mapToDouble(r -> r[2 + 2 * t2]).toArray(), M = g.stream().mapToDouble(r -> r[3 + 2 * t2]).toArray();
                    double mu = Arrays.stream(R).average().orElse(0), v = Arrays.stream(R).map(x -> (x - mu) * (x - mu)).sum() / Math.max(1, R.length - 1);
                    System.out.printf(Locale.ROOT, "   все       | %7d |       | %3d | %7.1f | %5.2f | %+6.2f | %7.1f | %7.1f | %5.2f%n", g.size(), T[ti], v / T[ti], v / T[ti] / INV_S2[ci], mu, cvar(R), cvar(M),
                            100.0 * Arrays.stream(M).filter(x -> x < -100).count() / Math.max(1, M.length));
                }
            }
        }
    }

    static double cvar(double[] x) { double[] s = x.clone(); Arrays.sort(s); int n = Math.max(1, s.length / 20); double z = 0; for (int i = 0; i < n; i++) z += s[i]; return z / n; }

    static void load(Path z, float[] c, long m0, int N) throws IOException {
        try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
            if (zi.getNextEntry() == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue;
                int p1 = l.indexOf(','); long t = Long.parseLong(l.substring(0, p1)); if (t > 1e14) t /= 1000;
                int i = (int) (t / 60000 - m0);
                if (i < 0 || i >= N) continue;
                String[] p = l.split(",", 6);
                c[i] = Float.parseFloat(p[4]);
            }
        }
    }
}
