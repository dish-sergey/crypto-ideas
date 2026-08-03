package org.home.data.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Ex-post разметка режимов по цене (док. 15 §3 / док. 01-v2 §5.1) — эталон для
 * оценки детекторов. Алгоритм датировки пиков/впадин (в духе Lunde–Timmermann):
 * <pre>
 *   1. Кандидаты в локальные экстремумы — окно ±30 дней.
 *   2. Чередование пик→впадина→пик; два подряд одного типа — оставить экстремальный.
 *   3. Подъём впадина→пик ≥ +50% → BULL; падение пик→впадина ≥ −30% → BEAR; иначе RANGE.
 *   4. Отрезки короче 30 дней поглощаются предыдущим.
 * </pre>
 *
 * <b>Смотрит в будущее по построению</b> — допустимо только в оценке (eval),
 * никогда как признак детектора (док. 15 §3, изоляция разметки).
 */
public final class PeakTroughLabeler {

    private PeakTroughLabeler() {
    }

    private static final int WIN = 30;
    private static final double BULL_TH = 0.50;
    private static final double BEAR_TH = 0.30;
    private static final int MIN_LEN = 30;

    private record Tp(int idx, int type) {}   // type: +1 пик, −1 впадина
    private record Run(int start, int end, String label) {}

    public static String[] label(double[] close) {
        return label(close, WIN, BULL_TH, BEAR_TH, MIN_LEN);
    }

    static String[] label(double[] close, int win, double bullTh, double bearTh, int minLen) {
        int n = close.length;
        String[] out = new String[n];
        if (n == 0) {
            return out;
        }
        // 1. кандидаты в экстремумы (окно ±win)
        List<Tp> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - win), hi = Math.min(n - 1, i + win);
            double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
            for (int j = lo; j <= hi; j++) {
                mx = Math.max(mx, close[j]);
                mn = Math.min(mn, close[j]);
            }
            boolean isPeak = close[i] >= mx;
            boolean isTrough = close[i] <= mn;
            if (isPeak && !isTrough) {
                cand.add(new Tp(i, 1));
            } else if (isTrough && !isPeak) {
                cand.add(new Tp(i, -1));
            }
        }
        // 2. чередование: подряд одинаковые — оставить экстремальный
        List<Tp> tp = new ArrayList<>();
        for (Tp c : cand) {
            if (tp.isEmpty()) {
                tp.add(c);
                continue;
            }
            Tp last = tp.get(tp.size() - 1);
            if (last.type() == c.type()) {
                boolean moreExtreme = c.type() == 1 ? close[c.idx()] >= close[last.idx()]
                        : close[c.idx()] <= close[last.idx()];
                if (moreExtreme) {
                    tp.set(tp.size() - 1, c);
                }
            } else {
                tp.add(c);
            }
        }
        // 3. отрезки между соседними TP
        java.util.Arrays.fill(out, "RANGE");
        for (int k = 0; k + 1 < tp.size(); k++) {
            Tp a = tp.get(k), b = tp.get(k + 1);
            String lab = "RANGE";
            if (a.type() == -1 && b.type() == 1) {
                double rise = (close[b.idx()] - close[a.idx()]) / close[a.idx()];
                if (rise >= bullTh) {
                    lab = "BULL";
                }
            } else if (a.type() == 1 && b.type() == -1) {
                double fall = (close[a.idx()] - close[b.idx()]) / close[a.idx()];
                if (fall >= bearTh) {
                    lab = "BEAR";
                }
            }
            for (int i = a.idx(); i <= b.idx(); i++) {
                out[i] = lab;
            }
        }
        // 4. отрезки короче minLen поглощаются предыдущим
        absorbShort(out, minLen);
        return out;
    }

    /** Слить прогоны короче minLen в предыдущий (первый прогон оставить как есть). */
    private static void absorbShort(String[] out, int minLen) {
        List<Run> runs = runs(out);
        for (int k = 1; k < runs.size(); k++) {
            Run r = runs.get(k);
            if (r.end() - r.start() + 1 < minLen) {
                String prev = runs.get(k - 1).label();
                for (int i = r.start(); i <= r.end(); i++) {
                    out[i] = prev;
                }
                runs.set(k, new Run(r.start(), r.end(), prev));
            }
        }
    }

    private static List<Run> runs(String[] out) {
        List<Run> runs = new ArrayList<>();
        int s = 0;
        for (int i = 1; i <= out.length; i++) {
            if (i == out.length || !out[i].equals(out[s])) {
                runs.add(new Run(s, i - 1, out[s]));
                s = i;
            }
        }
        return runs;
    }
}
