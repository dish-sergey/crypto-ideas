import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

// Док. 245, П1: разложение базы по состояниям рынка. Одиночные прогоны базы (Hv5allo{acb|edf}n), окна wall / 28–30.10 / 06–10.11.
// Деньги поминутно как в StandE: захват (отступ сделки от опоры × объём) + перенос (запас × Δопоры). Состояние минуты по монете:
// «ликвидации» (fall-lq5m1m) перекрывает класс σ̂ — СКО минутных ходов Бинанса за прошлый час против границ этапа 5 (тихо ≤p40,
// средне, бурно p80–95, буря >p95). По монете (два бота вместе) и состоянию: доля времени, захват / перенос / итог в $ и на час,
// оборот, средний запас; ошибка переноса на час — по суткам. Худшие 5% часов окна по итогу монеты: доля их минут и их убытка в состоянии.
// Аргументы: <hp> <bnb1s> <fall-файл> <окна через запятую>
public class P1State245 {
    static final String[] COINS = {"BTC", "ETH", "SOL"};
    static final double[][] CUT = {{3.39, 5.74, 9.21}, {4.65, 8.06, 12.80}, {5.78, 9.30, 14.82}};
    static final String[] ST = {"тихо", "средне", "бурно", "буря", "ликвидации"};

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        TreeMap<Long, Long> iv = new TreeMap<>();
        for (String l : Files.readAllLines(Path.of(a[2]))) { String[] p = l.split(";"); if (p.length == 3 && p[0].equals("BTC")) iv.put(Long.parseLong(p[1]), Long.parseLong(p[2])); }
        for (int ci = 0; ci < 3; ci++) {
            String coin = COINS[ci];
            Map<Long, Double> sig = sigma(a[1], coin);
            String[][] bots = {{coin.equals("BTC") ? "a" : coin.equals("ETH") ? "c" : "b", "acb"}, {coin.equals("BTC") ? "e" : coin.equals("ETH") ? "d" : "f", "edf"}};
            for (String w : a[3].split(",")) {
                // поминутно: захват, перенос, оборот, запас·опора (сумма двух ботов)
                TreeMap<Long, double[]> mn = new TreeMap<>();
                for (String[] b : bots) {
                    String db = a[0] + "/j-" + w + "-dNF100000r0.95Hv5allo" + b[1] + "n-" + coin + "/bot-" + b[0] + ".db";
                    TreeMap<Long, double[]> m = new TreeMap<>();
                    try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                        try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms")) { while (r.next()) m.put(r.getLong(1) / 60000, new double[]{r.getDouble(2), r.getDouble(3), 0, 0}); }
                        try (ResultSet r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill order by ts_ms")) {
                            while (r.next()) { var e = m.floorEntry(r.getLong(1) / 60000); if (e == null) continue; double v = r.getDouble(3) * r.getDouble(4), px = r.getDouble(4), f = e.getValue()[0];
                                e.getValue()[2] += (r.getString(2).equals("BUY") ? f / px - 1 : 1 - f / px) * v; e.getValue()[3] += v; }
                        }
                    }
                    double[] last = null;
                    for (var x : m.entrySet()) { double[] v = x.getValue(); double cr = last == null ? 0 : last[1] * (v[0] - last[0]);
                        double[] t = mn.computeIfAbsent(x.getKey(), k -> new double[4]); t[0] += v[2]; t[1] += cr; t[2] += v[3]; t[3] += v[1] * v[0]; last = v; }
                }
                // состояние минуты
                double[][] s = new double[5][6]; // минут, захват, перенос, оборот, запас·мин, -
                Map<Long, double[]> hour = new TreeMap<>(); Map<Long, int[]> hourSt = new HashMap<>(); Map<Long, double[][]> hourStPnl = new HashMap<>();
                Map<String, double[]> dayCarry = new HashMap<>();   // state|day → перенос, минут
                for (var x : mn.entrySet()) {
                    long mm = x.getKey(); double[] v = x.getValue();
                    int st; var e = iv.floorEntry(mm * 60000);
                    if (e != null && mm * 60000 < e.getValue()) st = 4;
                    else { Double sg = sig.get(mm - 1); if (sg == null) continue; st = sg <= CUT[ci][0] ? 0 : sg <= CUT[ci][1] ? 1 : sg <= CUT[ci][2] ? 2 : 3; }
                    s[st][0]++; s[st][1] += v[0]; s[st][2] += v[1]; s[st][3] += v[2]; s[st][4] += v[3];
                    long h = mm / 60; hour.computeIfAbsent(h, k -> new double[1])[0] += v[0] + v[1];
                    hourSt.computeIfAbsent(h, k -> new int[5])[st]++; hourStPnl.computeIfAbsent(h, k -> new double[5][1])[st][0] += v[0] + v[1];
                    double[] dc = dayCarry.computeIfAbsent(st + "|" + mm / 1440, k -> new double[2]); dc[0] += v[1]; dc[1]++;
                }
                double tot = Arrays.stream(s).mapToDouble(r -> r[0]).sum();
                System.out.printf(Locale.ROOT, "%n######## %s %s: минут %.0f%n   состояние | доля времени | захват $ | перенос $ | итог $ | итог $/ч | захват $/ч | перенос $/ч ± по суткам | оборот $/ч | средний запас $%n", coin, w, tot);
                for (int k = 0; k < 5; k++) {
                    if (s[k][0] == 0) continue; double hrs = s[k][0] / 60;
                    // ошибка переноса на час — по суткам (сутки с ≥ 30 мин состояния)
                    List<Double> dv = new ArrayList<>(); for (var d : dayCarry.entrySet()) if (d.getKey().startsWith(k + "|") && d.getValue()[1] >= 30) dv.add(d.getValue()[0] / (d.getValue()[1] / 60));
                    double mean = s[k][2] / hrs, se = Double.NaN;
                    if (dv.size() > 1) { double mm2 = dv.stream().mapToDouble(z -> z).average().orElse(0), vv = 0; for (double z : dv) vv += (z - mm2) * (z - mm2); se = Math.sqrt(vv / (dv.size() - 1) / dv.size()); }
                    System.out.printf(Locale.ROOT, "   %-10s | %5.1f%% | %+8.2f | %+8.2f | %+8.2f | %+7.4f | %+7.4f | %+7.4f ± %.4f (%d сут.) | %7.1f | %6.1f%n", ST[k], 100 * s[k][0] / tot, s[k][1], s[k][2], s[k][1] + s[k][2],
                            (s[k][1] + s[k][2]) / hrs, s[k][1] / hrs, mean, se, dv.size(), s[k][3] / hrs, s[k][4] / s[k][0]);
                }
                // худшие 5% часов
                List<Long> hs = new ArrayList<>(hour.keySet()); hs.removeIf(h -> Arrays.stream(hourSt.get(h)).sum() < 50);
                hs.sort(Comparator.comparingDouble(h -> hour.get(h)[0]));
                int nW = Math.max(1, hs.size() / 20); double[] mW = new double[5], lW = new double[5]; double lossW = 0;
                for (int i = 0; i < nW; i++) { long h = hs.get(i); int[] cnt = hourSt.get(h); for (int k = 0; k < 5; k++) { mW[k] += cnt[k]; lW[k] += hourStPnl.get(h)[k][0]; } lossW += hour.get(h)[0]; }
                double mt = Arrays.stream(mW).sum();
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "   худшие 5%% часов (%d ч, итог %+.2f $): доля минут / доля итога по состояниям:", nW, lossW));
                for (int k = 0; k < 5; k++) sb.append(String.format(Locale.ROOT, " %s %.0f%% / %.0f%%;", ST[k], 100 * mW[k] / mt, 100 * lW[k] / lossW));
                System.out.println(sb);
            }
        }
    }

    static Map<Long, Double> sigma(String dir, String coin) throws IOException {
        TreeMap<Long, Float> close = new TreeMap<>();
        try (var st = Files.list(Path.of(dir))) {
            for (Path z : st.filter(p -> p.getFileName().toString().startsWith(coin + "USDC-1s-")).sorted().toList())
                try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(Files.newInputStream(z), 1 << 16))) {
                    if (zi.getNextEntry() == null) continue;
                    BufferedReader r = new BufferedReader(new InputStreamReader(zi, StandardCharsets.UTF_8), 1 << 16);
                    for (String l; (l = r.readLine()) != null; ) { if (l.isEmpty() || !Character.isDigit(l.charAt(0))) continue; String[] p = l.split(",", 6); long t = Long.parseLong(p[0]); if (t > 1e14) t /= 1000; close.put(t / 60000, Float.parseFloat(p[4])); }
                }
        }
        Map<Long, Double> sig = new HashMap<>(); ArrayDeque<Double> w = new ArrayDeque<>(); double s = 0, q = 0; Long pm = null; float pc = 0;
        for (var e : close.entrySet()) {
            if (pm != null && e.getKey() == pm + 1 && pc > 0) { double r = Math.log(e.getValue() / pc) * 1e4; w.addLast(r); s += r; q += r * r; if (w.size() > 60) { double o = w.pollFirst(); s -= o; q -= o * o; }
                if (w.size() == 60) sig.put(e.getKey(), Math.sqrt(Math.max(0, q / 60 - (s / 60) * (s / 60)))); }
            else { w.clear(); s = 0; q = 0; }
            pm = e.getKey(); pc = e.getValue();
        }
        return sig;
    }
}
