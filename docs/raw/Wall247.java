import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

// Идея владельца 14.11 (продолжение 239/242): неподвижные заявки в стакане перпа Бинанса и прогноз их прорыва по тейкерам.
// 1) Книга: снимок (perpsnap, REST, ~1000 уровней) + цепочка изменений depth@500ms (pu == u предыдущего). Каждый следующий снимок —
//    сверка восстановленной книги: доля уровней в ±0.5% от середины, где объём не совпал (печатается).
// 2) Крупный уровень: объём ≥ порога (верхние TOP% уровней в ±100 б.п. по первому снимку). Жизнь уровня: рост объёма > 5% —
//    новая заявка (рождение заново), снижение (съели или частично сняли) — та же. НЕПОДВИЖНЫЙ = прожил ≥ 60 с и середина за жизнь
//    сходила ≥ 5 б.п.; ПОДВИЖНЫЙ (плацебо) — крупный, но не неподвижный.
// 3) Подход: середина впервые за жизнь уровня ближе A б.п. В момент подхода: $ уровня, возраст, съедено тейкерами по его цене с
//    рождения (доля), встречный поток тейкеров за 30 и 60 с (для бида — продавцы, для аска — покупатели; $, и к $ уровня).
// 4) Исход за H = 5 мин: ПРОРВАН — сделка за ценой уровня (бид: ниже, аск: выше); УДЕРЖАЛ — середина ушла от уровня на ≥ 10 б.п.,
//    уровень цел (≥ 50% объёма при подходе); СНЯТ — уровень исчез (< 20%) без прорыва; иначе — НЕ РЕШЁН.
// Вывод: события в CSV и сводка: доля прорывов у неподвижных и подвижных, по корзинам «поток 60 с / $ уровня» и «съедено».
// Аргументы: <символ, напр. BTCUSDT> <perpsnap.jsonl.gz> <perp.jsonl.gz> <aggTrades.zip> <выход.csv> [TOP%=1] [A б.п.=3]
public class Wall247 {
    static final long H = 300_000L;
    static boolean DIRTY; static long TRUNC, DIRTYSKIP;  // обрезанное сообщение (буфер websocat 64 КБ): книга ненадёжна до следующего снимка
    static int DBG = Integer.getInteger("dbg", 100), DBGB = 0; static double BIG; static long BIGN, BIGBAD;
    static final double CMPIN = Double.parseDouble(System.getProperty("cmpin", "0.001"));

    static final class Lv {
        double px, qmax, qNow, eaten, qAtApp, eatenAtApp, usdAtApp, f30, f60, minMid = 1e18, maxMid = -1e18;
        boolean bid, approached, done; long born, tApp; int outcome = -1; boolean stat;
    }

    public static void main(String[] a) throws Exception {
        String sym = a[0]; double TOP = a.length > 5 ? Double.parseDouble(a[5]) : 1, A = a.length > 6 ? Double.parseDouble(a[6]) : 3;
        String streamKey = sym.toLowerCase() + "@depth";
        // снимки
        TreeMap<Long, String> snaps = new TreeMap<>();   // lastUpdateId → строка
        try (BufferedReader br = gz(a[1])) { for (String l; (l = br.readLine()) != null; ) if (l.contains("\"s\":\"" + sym + "\"")) snaps.put(num(l, "\"lastUpdateId\":"), l); }
        // сделки: время, цена, $, продавец-агрессор
        List<double[]> tr = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(a[3]))) { z.getNextEntry(); BufferedReader br = new BufferedReader(new InputStreamReader(z, StandardCharsets.UTF_8), 1 << 20);
            for (String l; (l = br.readLine()) != null; ) { if (!Character.isDigit(l.charAt(0))) continue; String[] p = l.split(",");
                double px = Double.parseDouble(p[1]); tr.add(new double[]{Long.parseLong(p[5]), px, px * Double.parseDouble(p[2]), p[6].equals("true") ? 1 : 0}); } }
        tr.sort(Comparator.comparingDouble(x -> x[0]));
        System.out.printf("%s: снимков %d, сделок %d%n", sym, snaps.size(), tr.size());

        TreeMap<Double, Double> bids = new TreeMap<>(Comparator.reverseOrder()), asks = new TreeMap<>();
        var first = snaps.firstEntry(); loadSnap(first.getValue(), bids, asks); long L = first.getKey();
        double mid0 = (bids.firstKey() + asks.firstKey()) / 2;
        List<Double> sizes = new ArrayList<>();
        for (var e : bids.entrySet()) if (e.getKey() > mid0 * 0.99) sizes.add(e.getKey() * e.getValue());
        for (var e : asks.entrySet()) if (e.getKey() < mid0 * 1.01) sizes.add(e.getKey() * e.getValue());
        Collections.sort(sizes); double minUsd = sizes.get((int) (sizes.size() * (1 - TOP / 100)));
        BIG = minUsd; System.out.printf(Locale.ROOT, "порог крупного уровня $%.0f (верхние %.1f%% в ±100 б.п.), подход ближе %.0f б.п.%n", minUsd, TOP, A);

        Map<Double, Lv> lb = new HashMap<>(), la = new HashMap<>(); List<Lv> fin = new ArrayList<>();
        boolean synced = false; long lastU = -1; int ti = 0; long msgs = 0, chainBreaks = 0;
        long chkN = 0, chkBad = 0; Iterator<Map.Entry<Long, String>> snapIt = snaps.tailMap(L, false).entrySet().iterator(); Map.Entry<Long, String> nextSnap = snapIt.hasNext() ? snapIt.next() : null;
        ArrayDeque<double[]> flow = new ArrayDeque<>();   // для потока: t, $, продавец?
        try (BufferedReader br = gz(a[2])) {
            for (String l; (l = br.readLine()) != null; ) {
                if (!l.contains(streamKey)) continue;
                long U = num(l, "\"U\":"), u = num(l, "\"u\":"), pu = num(l, "\"pu\":"), E = num(l, "\"E\":");
                if (!synced) { if (u < L) continue; if (U > L) { System.out.println("первое изменение позже снимка — пропуск"); } synced = true; }
                else if (pu != lastU) { chainBreaks++; }
                lastU = u; msgs++;
                // сделки до этого момента
                double mid = (bids.firstKey() + asks.firstKey()) / 2;
                while (ti < tr.size() && tr.get(ti)[0] <= E) { double[] t = tr.get(ti++); trade(t, lb, la, mid); flow.add(t); }
                while (!flow.isEmpty() && flow.peekFirst()[0] < E - 60_000) flow.pollFirst();
                apply(l, "\"b\":[", bids, lb, true, E, minUsd, mid, fin);
                apply(l, "\"a\":[", asks, la, false, E, minUsd, mid, fin);
                if (bids.isEmpty() || asks.isEmpty()) continue;
                mid = (bids.firstKey() + asks.firstKey()) / 2;
                step(lb, E, mid, A, flow, fin); step(la, E, mid, A, flow, fin);
                // сверка со снимком: когда цепочка дошла до его номера
                while (nextSnap != null && u >= nextSnap.getKey()) {
                    TreeMap<Double, Double> sb = new TreeMap<>(Comparator.reverseOrder()), sa = new TreeMap<>(); loadSnap(nextSnap.getValue(), sb, sa);
                    long[] c = cmp(bids, sb, mid) ; long[] d = cmp(asks, sa, mid); chkN += c[0] + d[0]; chkBad += c[1] + d[1];
                    // снимок покрывает ~1000 уровней (BTC ±20 б.п.): в его охвате книга берётся из снимка — так в неё попадают
                    // уровни, которые давно не менялись (их нет в потоке изменений). Возраст такого уровня — с момента, когда увидели.
                    merge(bids, sb, lb, true, E, minUsd, mid, fin); merge(asks, sa, la, false, E, minUsd, mid, fin); DIRTY = false;
                    nextSnap = snapIt.hasNext() ? snapIt.next() : null;
                }
            }
        }
        for (Lv v : lb.values()) close(v, fin); for (Lv v : la.values()) close(v, fin);
        System.out.printf(Locale.ROOT, "изменений %d, разрывов цепочки %d; сверка со снимками: уровней в ±0.5%% %d, не совпало %d (%.3f%%); крупных уровней в ±1%% %d, не совпало (> 2%% объёма) %d (%.2f%%)%n", msgs, chainBreaks, chkN, chkBad, 100.0 * chkBad / Math.max(1, chkN), BIGN, BIGBAD, 100.0 * BIGBAD / Math.max(1, BIGN));
        // вывод
        try (PrintWriter w = new PrintWriter(a[4], StandardCharsets.UTF_8)) {
            w.println("t_app;сторона;неподвижный;цена;usd;возраст_с;съедено_доля;поток30_usd;поток60_usd;исход");
            for (Lv v : fin) if (v.approached) w.printf(Locale.ROOT, "%d;%s;%d;%.4f;%.0f;%.0f;%.3f;%.0f;%.0f;%d%n", v.tApp, v.bid ? "bid" : "ask", v.stat ? 1 : 0, v.px, v.usdAtApp,
                    (v.tApp - v.born) / 1000.0, v.eatenAtApp / Math.max(1e-9, v.eatenAtApp + v.qAtApp * v.px), v.f30, v.f60, v.outcome);
        }
        System.out.printf("обрезанных сообщений %d (книга до следующего снимка ненадёжна), подходов пропущено из-за этого %d%n", TRUNC, DIRTYSKIP);
        String[] OUT = {"прорван", "удержал", "снят", "не решён"};
        for (int s = 1; s >= 0; s--) {
            int[] n = new int[4]; for (Lv v : fin) if (v.approached && (v.stat ? 1 : 0) == s) n[v.outcome]++;
            int tot = Arrays.stream(n).sum();
            System.out.printf(Locale.ROOT, "%s: подходов %d — прорван %d (%.0f%%), удержал %d (%.0f%%), снят %d (%.0f%%), не решён %d%n", s == 1 ? "НЕПОДВИЖНЫЕ" : "подвижные (плацебо)", tot,
                    n[0], 100.0 * n[0] / Math.max(1, tot), n[1], 100.0 * n[1] / Math.max(1, tot), n[2], 100.0 * n[2] / Math.max(1, tot), n[3]);
            double[] cb = {0, 0.25, 0.5, 1, 2, 1e9}; StringBuilder sb = new StringBuilder("   прорван по «поток 60 с / $ уровня»:");
            for (int k = 0; k + 1 < cb.length; k++) { int br = 0, al = 0; for (Lv v : fin) if (v.approached && (v.stat ? 1 : 0) == s && v.outcome != 3) { double r = v.f60 / v.usdAtApp; if (r >= cb[k] && r < cb[k + 1]) { al++; if (v.outcome == 0) br++; } }
                sb.append(String.format(Locale.ROOT, " [%s–%s) %d/%d", fmt(cb[k]), fmt(cb[k + 1]), br, al)); }
            System.out.println(sb);
            double[] eb = {0, 0.01, 0.1, 0.3, 1.01}; sb = new StringBuilder("   прорван по «съедено с рождения»:");
            for (int k = 0; k + 1 < eb.length; k++) { int br = 0, al = 0; for (Lv v : fin) if (v.approached && (v.stat ? 1 : 0) == s && v.outcome != 3) { double r = v.eatenAtApp / Math.max(1e-9, v.eatenAtApp + v.qAtApp * v.px); if (r >= eb[k] && r < eb[k + 1]) { al++; if (v.outcome == 0) br++; } }
                sb.append(String.format(Locale.ROOT, " [%s–%s) %d/%d", fmt(eb[k]), fmt(Math.min(1, eb[k + 1])), br, al)); }
            System.out.println(sb);
        }
    }

    static String fmt(double x) { return x > 1e8 ? "∞" : String.format(Locale.ROOT, "%.2f", x).replaceAll("\\.?0+$", ""); }

    static void trade(double[] t, Map<Double, Lv> lb, Map<Double, Lv> la, double mid) {
        boolean sellAggr = t[3] == 1; double px = t[1];
        if (sellAggr) { Lv v = lb.get(px); if (v != null) v.eaten += t[2];
            for (Lv x : lb.values()) if (x.approached && x.outcome < 0 && px < x.px - 1e-9) x.outcome = 0; }
        else { Lv v = la.get(px); if (v != null) v.eaten += t[2];
            for (Lv x : la.values()) if (x.approached && x.outcome < 0 && px > x.px + 1e-9) x.outcome = 0; }
    }

    static void apply(String l, String key, TreeMap<Double, Double> side, Map<Double, Lv> lv, boolean bid, long E, double minUsd, double mid, List<Lv> fin) {
        int i = l.indexOf(key); if (i < 0) return; i += key.length();
        while (i < l.length() && l.charAt(i) == '[') {
            int j = l.indexOf(']', i); if (j < 0) { DIRTY = true; TRUNC++; return; } String[] p = l.substring(i + 1, j).replace("\"", "").split(",");
            double px = Double.parseDouble(p[0]), q = Double.parseDouble(p[1]);
            if (q == 0) side.remove(px); else side.put(px, q);
            Lv v = lv.get(px);
            boolean inBand = Math.abs(px / mid - 1) < 0.01;
            if (v != null) {
                if (q > v.qmax * 1.05) { close(v, fin); lv.remove(px); v = null; }       // доложили — новая заявка
                else { v.qNow = q; if (q * px < 0.2 * Math.max(1, v.qAtApp * px) && v.approached && v.outcome < 0) v.outcome = 2;
                    if (q == 0) { close(v, fin); lv.remove(px); continue; } }
            }
            if (v == null && inBand && q * px >= minUsd) { v = new Lv(); v.px = px; v.qmax = q; v.qNow = q; v.bid = bid; v.born = E; lv.put(px, v); }
            i = j + 1; if (i < l.length() && l.charAt(i) == ',') i++;
        }
    }

    static void step(Map<Double, Lv> lv, long E, double mid, double A, ArrayDeque<double[]> flow, List<Lv> fin) {
        for (Iterator<Lv> it = lv.values().iterator(); it.hasNext(); ) {
            Lv v = it.next();
            v.minMid = Math.min(v.minMid, mid); v.maxMid = Math.max(v.maxMid, mid);
            double dist = (v.bid ? mid / v.px - 1 : v.px / mid - 1) * 1e4;
            if (!v.approached && dist >= 0 && dist < A && DIRTY) { DIRTYSKIP++; v.approached = true; v.done = true; }
            if (!v.approached && dist >= 0 && dist < A) {
                v.approached = true; v.tApp = E; v.qAtApp = v.qNow; v.eatenAtApp = v.eaten; v.usdAtApp = v.qNow * v.px;
                v.stat = E - v.born >= 60_000 && (v.maxMid / v.minMid - 1) * 1e4 >= 5;
                for (double[] t : flow) { boolean toward = v.bid ? t[3] == 1 : t[3] == 0; if (!toward) continue; v.f60 += t[2]; if (t[0] >= E - 30_000) v.f30 += t[2]; }
            }
            if (v.approached && v.outcome < 0) {
                if (dist >= 10 && v.qNow >= 0.5 * v.qAtApp) v.outcome = 1;
                else if (E - v.tApp > H) v.outcome = 3;
            }
            if (v.approached && v.outcome >= 0 && E - v.tApp > H) { close(v, fin); it.remove(); }
        }
    }

    static void close(Lv v, List<Lv> fin) { if (v.done) return; v.done = true; if (v.approached) { if (v.outcome < 0) v.outcome = 3; fin.add(v); } }

    static void merge(TreeMap<Double, Double> book, TreeMap<Double, Double> snap, Map<Double, Lv> lv, boolean bid, long E, double minUsd, double mid, List<Lv> fin) {
        if (snap.isEmpty()) return;
        double lo = Math.min(snap.firstKey(), snap.lastKey()), hi = Math.max(snap.firstKey(), snap.lastKey());
        book.keySet().removeIf(p -> p >= lo && p <= hi && !snap.containsKey(p));
        for (var e : snap.entrySet()) {
            book.put(e.getKey(), e.getValue());
            double px = e.getKey(), q = e.getValue();
            if (!lv.containsKey(px) && Math.abs(px / mid - 1) < 0.01 && q * px >= minUsd) { Lv v = new Lv(); v.px = px; v.qmax = q; v.qNow = q; v.bid = bid; v.born = E; lv.put(px, v); }
        }
        for (Iterator<Map.Entry<Double, Lv>> it = lv.entrySet().iterator(); it.hasNext(); ) { var e = it.next(); double p = e.getKey();
            if (p >= lo && p <= hi && !snap.containsKey(p)) { close(e.getValue(), fin); it.remove(); } }
    }

    static long[] cmp(TreeMap<Double, Double> book, TreeMap<Double, Double> snap, double mid) {
        long n = 0, bad = 0; final double LO = snap.isEmpty() ? 0 : Math.min(snap.firstKey(), snap.lastKey()), HI = snap.isEmpty() ? 0 : Math.max(snap.firstKey(), snap.lastKey());
        for (var e : snap.entrySet()) { if (e.getKey() < LO || e.getKey() > HI || Math.abs(e.getKey() / mid - 1) > 0.005 || Math.abs(e.getKey() / mid - 1) < CMPIN) continue; n++; Double q = book.get(e.getKey()); if (q == null || Math.abs(q - e.getValue()) > 1e-9 * Math.max(1, q)) { bad++; if (DBG++ < 40) System.out.println("   расхождение " + e.getKey() + ": книга " + q + ", снимок " + e.getValue()); } }
        for (var e : book.entrySet()) { if (e.getKey() < LO || e.getKey() > HI || Math.abs(e.getKey() / mid - 1) > 0.005 || Math.abs(e.getKey() / mid - 1) < CMPIN) continue; if (!snap.containsKey(e.getKey())) { n++; bad++; if (DBG++ < 40) System.out.println("   лишний в книге " + e.getKey() + " " + e.getValue()); } }
        // крупные уровни (≥ BIG $ в снимке или в книге): совпал ли объём в пределах 2% — то, на чём стоит разбор
        long nb = 0, bb = 0;
        java.util.Set<Double> keys = new java.util.HashSet<>(snap.keySet()); keys.addAll(book.keySet());
        for (double p : keys) { if (p < LO || p > HI || Math.abs(p / mid - 1) > 0.01) continue; double s = snap.getOrDefault(p, 0.0), b = book.getOrDefault(p, 0.0);
            if (Math.max(s, b) * p < BIG) continue; nb++; if (Math.abs(s - b) > 0.02 * Math.max(s, b)) { bb++; if (DBGB++ < 10) System.out.println("   крупный не совпал " + p + ": книга " + b + ", снимок " + s); } }
        BIGN += nb; BIGBAD += bb;
        return new long[]{n, bad};
    }

    static void loadSnap(String l, TreeMap<Double, Double> b, TreeMap<Double, Double> a) {
        b.clear(); a.clear(); fill(l, "\"bids\":[", b); fill(l, "\"asks\":[", a);
    }

    static void fill(String l, String key, TreeMap<Double, Double> m) {
        int i = l.indexOf(key); if (i < 0) return; i += key.length();
        while (i < l.length() && l.charAt(i) == '[') { int j = l.indexOf(']', i); String[] p = l.substring(i + 1, j).replace("\"", "").split(",");
            m.put(Double.parseDouble(p[0]), Double.parseDouble(p[1])); i = j + 1; if (i < l.length() && l.charAt(i) == ',') i++; }
    }

    static BufferedReader gz(String f) throws IOException { return new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(f), 1 << 16), StandardCharsets.UTF_8), 1 << 20); }
    static long num(String l, String key) { int i = l.indexOf(key); if (i < 0) return Long.MIN_VALUE; i += key.length(); int j = i; while (j < l.length() && Character.isDigit(l.charAt(j))) j++; return Long.parseLong(l.substring(i, j)); }
}
