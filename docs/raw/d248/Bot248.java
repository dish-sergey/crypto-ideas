import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

// Док. 248 (вопрос владельца 14.11): почему SOL в плюсе, а ETH в минусе за 7 дней при похожем ходе рынка.
// Живые журналы (exp7d.sh): q-<бот>.csv.gz (ts,fair,bid,ask,inv,quotable,...), f-<бот>.csv (ts,id,side,qty,price,fair,level).
// Аргументы: <каталог> <бот>…   Печатает по боту: итог = захват + перенос, рынок (тренд/возврат), маркауты покупок и продаж,
// где в местном размахе покупает, запас против будущего хода, отступы функции по запасу.
public class Bot248 {
    record Q(long t, double fair, double bid, double ask, double inv, boolean on) {}
    record F(long t, boolean buy, double qty, double px) {}

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        for (int k = 1; k < a.length; k++) run(dir, a[k]);
    }

    static double at(long[] mt, double[] mf, long t) {   // опора на минуте t (последняя известная)
        int i = Arrays.binarySearch(mt, t / 60000); if (i < 0) i = -i - 2; if (i < 0) return Double.NaN; return mf[i];
    }

    static void run(String dir, String bot) throws Exception {
        List<Q> qs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(dir + "/q-" + bot + ".csv.gz"))))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] p = l.split(",", -1); if (p[1].isEmpty()) continue;
                double f = Double.parseDouble(p[1]); if (!(f > 0)) continue;
                qs.add(new Q(Long.parseLong(p[0]), f, p[2].isEmpty() ? Double.NaN : Double.parseDouble(p[2]), p[3].isEmpty() ? Double.NaN : Double.parseDouble(p[3]), Double.parseDouble(p[4]), p[5].equals("1")));
            }
        }
        List<F> fs = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(dir + "/f-" + bot + ".csv"))) { String[] p = l.split(","); fs.add(new F(Long.parseLong(p[0]), p[2].equalsIgnoreCase("BUY"), Double.parseDouble(p[3]), Double.parseDouble(p[4]))); }
        fs.sort(Comparator.comparingLong(F::t));
        // минутная сетка опоры и запаса
        TreeMap<Long, double[]> mm = new TreeMap<>();
        for (Q q : qs) mm.put(q.t / 60000, new double[]{q.fair, q.inv});
        long[] mt = mm.keySet().stream().mapToLong(x -> x).toArray(); double[] mf = new double[mt.length], mi = new double[mt.length];
        int j = 0; for (double[] v : mm.values()) { mf[j] = v[0]; mi[j] = v[1]; j++; }
        int n = mt.length; double f0 = mf[0], f1 = mf[n - 1];
        double maxInv = 0; for (double x : mi) maxInv = Math.max(maxInv, x);
        // итог = захват + перенос (поминутно, запас на начало минуты)
        double capt = 0, carry = 0, turn = 0; int nb = 0, ns = 0; double carryUp = 0, carryDn = 0, invUp = 0, invDn = 0; int hUp = 0, hDn = 0;
        for (F f : fs) { double fr = at(mt, mf, f.t); capt += (f.buy ? fr - f.px : f.px - fr) * f.qty; turn += f.qty * f.px; if (f.buy) nb++; else ns++; }
        for (int i = 1; i < n; i++) carry += mi[i - 1] * (mf[i] - mf[i - 1]);
        // по часам: перенос в падающих и растущих часах
        for (int i = 0; i + 60 < n; i += 60) {
            double c = 0, iv = 0; for (int m = i + 1; m <= i + 60; m++) { c += mi[m - 1] * (mf[m] - mf[m - 1]); iv += mi[m - 1] * mf[m - 1]; }
            if (mf[i + 60] >= mf[i]) { carryUp += c; invUp += iv / 60; hUp++; } else { carryDn += c; invDn += iv / 60; hDn++; }
        }
        // рынок: СКО минутного хода, отношение дисперсий (тренд > 1, возврат < 1)
        double v1 = 0; int c1 = 0; for (int i = 1; i < n; i++) if (mt[i] - mt[i - 1] == 1) { double d = Math.log(mf[i] / mf[i - 1]); v1 += d * d; c1++; }
        v1 /= c1;
        StringBuilder vr = new StringBuilder();
        for (int h : new int[]{5, 15, 60, 240}) { double v = 0; int c = 0; for (int i = h; i < n; i += h) { double d = Math.log(mf[i] / mf[i - h]); v += d * d; c++; } vr.append(String.format(Locale.ROOT, " %d мин %.2f", h, v / c / (h * v1))); }
        System.out.printf(Locale.ROOT, "%n===== бот %s: %d минут, опора %.4f → %.4f (%+.2f%%), запас max %.6f%n", bot, n, f0, f1, 100 * (f1 / f0 - 1), maxInv);
        System.out.printf(Locale.ROOT, "  итог %+.3f = захват %+.3f + перенос %+.3f | покупок %d, продаж %d, оборот $%.0f, захват на оборот %.2f б.п.%n", capt + carry, capt, carry, nb, ns, turn, 1e4 * capt / turn);
        System.out.printf(Locale.ROOT, "  перенос: растущие часы %+.3f (%d ч, ср. запас $%.2f) | падающие %+.3f (%d ч, ср. запас $%.2f)%n", carryUp, hUp, invUp / Math.max(hUp, 1), carryDn, hDn, invDn / Math.max(hDn, 1));
        System.out.printf(Locale.ROOT, "  рынок: СКО минуты %.2f б.п.; отношение дисперсий (1 = случайно, >1 тренд, <1 возврат):%s%n", 1e4 * Math.sqrt(v1), vr);
        // маркауты и ход до сделки
        int[] H = {1, 5, 15, 60, 240};
        for (boolean side : new boolean[]{true, false}) {
            StringBuilder s = new StringBuilder(side ? "  покупки: маркаут б.п." : "  продажи: маркаут б.п.");
            for (int h : H) { double sm = 0, w = 0; for (F f : fs) if (f.buy == side) { double fx = at(mt, mf, f.t + h * 60000L); if (Double.isNaN(fx) || f.t / 60000 + h > mt[n - 1]) continue; double m = (side ? fx / f.px - 1 : 1 - fx / f.px) * 1e4; sm += m * f.qty * f.px; w += f.qty * f.px; } s.append(String.format(Locale.ROOT, " %dм %+.1f", h, sm / w)); }
            s.append(" | ход опоры ДО сделки:");
            for (int h : new int[]{5, 30, 120}) { double sm = 0, w = 0; for (F f : fs) if (f.buy == side) { double fb = at(mt, mf, f.t - h * 60000L), fr = at(mt, mf, f.t); if (Double.isNaN(fb)) continue; sm += (fr / fb - 1) * 1e4 * f.qty * f.px; w += f.qty * f.px; } s.append(String.format(Locale.ROOT, " %dм %+.1f", h, sm / w)); }
            // место в размахе ±60 мин (0 — у минимума, 1 — у максимума)
            double pos = 0, w = 0; int top = 0, bot0 = 0, cnt = 0;
            for (F f : fs) if (f.buy == side) {
                int i0 = Arrays.binarySearch(mt, f.t / 60000 - 60), i1 = Arrays.binarySearch(mt, f.t / 60000 + 60); if (i0 < 0) i0 = -i0 - 1; if (i1 < 0) i1 = -i1 - 2; if (i1 <= i0) continue;
                double lo = 1e18, hi = -1e18; for (int m = i0; m <= i1; m++) { lo = Math.min(lo, mf[m]); hi = Math.max(hi, mf[m]); }
                if (hi - lo <= 0) continue; double p = Math.max(0, Math.min(1, (f.px - lo) / (hi - lo))); pos += p * f.qty * f.px; w += f.qty * f.px; cnt++; if (p > 0.75) top++; if (p < 0.25) bot0++;
            }
            s.append(String.format(Locale.ROOT, " | место в размахе ±1 ч %.2f (верхняя четверть %d%%, нижняя %d%%)", pos / w, 100 * top / Math.max(cnt, 1), 100 * bot0 / Math.max(cnt, 1)));
            System.out.println(s);
        }
        // запас против будущего хода за час: средний запас $ по квинтилям хода следующего часа
        List<double[]> hr = new ArrayList<>();
        for (int i = 0; i + 60 < n; i += 15) hr.add(new double[]{mf[i + 60] / mf[i] - 1, mi[i] * mf[i]});
        hr.sort(Comparator.comparingDouble(x -> x[0]));
        StringBuilder s = new StringBuilder("  запас $ на входе в час по квинтилю хода этого часа (худший → лучший):");
        for (int qn = 0; qn < 5; qn++) { double sm = 0, sr = 0; int lo = qn * hr.size() / 5, hi = (qn + 1) * hr.size() / 5; for (int i = lo; i < hi; i++) { sm += hr.get(i)[1]; sr += hr.get(i)[0]; } s.append(String.format(Locale.ROOT, " %.2f (%+.2f%%)", sm / (hi - lo), 100 * sr / (hi - lo))); }
        System.out.println(s);
        // запас против ПРОШЛОГО хода: бот набирает запас после падения?
        List<double[]> hp = new ArrayList<>();
        for (int i = 60; i < n; i += 15) hp.add(new double[]{mf[i] / mf[i - 60] - 1, mi[i] * mf[i]});
        hp.sort(Comparator.comparingDouble(x -> x[0]));
        s = new StringBuilder("  запас $ по квинтилю хода ПРОШЛОГО часа (упало сильнее всего → выросло):");
        for (int qn = 0; qn < 5; qn++) { double sm = 0, sr = 0; int lo = qn * hp.size() / 5, hi = (qn + 1) * hp.size() / 5; for (int i = lo; i < hi; i++) { sm += hp.get(i)[1]; sr += hp.get(i)[0]; } s.append(String.format(Locale.ROOT, " %.2f (%+.2f%%)", sm / (hi - lo), 100 * sr / (hi - lo))); }
        System.out.println(s);
        // функция: отступ бида и аска (б.п. от опоры) по доле запаса от максимума
        double[][] bins = new double[6][4]; int[] bc = new int[6]; List<List<Double>> bb = new ArrayList<>(), ab = new ArrayList<>();
        for (int i = 0; i < 6; i++) { bb.add(new ArrayList<>()); ab.add(new ArrayList<>()); }
        for (Q q : qs) { if (!q.on) continue; double fr = q.inv / maxInv; int b = q.inv <= maxInv * 0.02 ? 0 : Math.min(5, 1 + (int) (fr * 4.999 * 0.999)); if (!Double.isNaN(q.bid)) bb.get(b).add((q.fair - q.bid) / q.fair * 1e4); if (!Double.isNaN(q.ask)) ab.get(b).add((q.ask - q.fair) / q.fair * 1e4); bc[b]++; }
        String[] nm = {"пусто", "≤20%", "20–40%", "40–60%", "60–80%", ">80%"};
        s = new StringBuilder("  функция (медиана отступа бид / аск, б.п.; доля времени):");
        long tot = Arrays.stream(bc).sum();
        for (int i = 0; i < 6; i++) s.append(String.format(Locale.ROOT, " %s %s/%s %d%%;", nm[i], med(bb.get(i)), med(ab.get(i)), 100 * bc[i] / Math.max(tot, 1)));
        System.out.println(s);
        // по суткам
        s = new StringBuilder("  по суткам (ход %, итог $):");
        long d0 = mt[0] / 1440;
        for (long d = d0; d <= mt[n - 1] / 1440; d++) {
            int i0 = Arrays.binarySearch(mt, d * 1440), i1 = Arrays.binarySearch(mt, d * 1440 + 1439); if (i0 < 0) i0 = -i0 - 1; if (i1 < 0) i1 = -i1 - 2; if (i1 <= i0) continue;
            double cr = 0; for (int m = i0 + 1; m <= i1; m++) cr += mi[m - 1] * (mf[m] - mf[m - 1]);
            double cp = 0; for (F f : fs) if (f.t / 60000 >= mt[i0] && f.t / 60000 <= mt[i1]) { double fr = at(mt, mf, f.t); cp += (f.buy ? fr - f.px : f.px - fr) * f.qty; }
            s.append(String.format(Locale.ROOT, " %s %+.1f%% %+.2f;", java.time.LocalDate.ofEpochDay(d).toString().substring(5), 100 * (mf[i1] / mf[i0] - 1), cr + cp));
        }
        System.out.println(s);
    }

    static String med(List<Double> l) { if (l.isEmpty()) return "-"; Collections.sort(l); return String.format(Locale.ROOT, "%.1f", l.get(l.size() / 2)); }
}
