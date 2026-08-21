package org.home.data.theory.alloc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Обязательный набор аллокаторов ТЗ 65 §4.1.
 *
 * <p>Нормировка весов — режим {@link Normalization}: по всем стратегиям пула
 * (деньги недоступной лежат в кэше) либо только по доступным. Это <b>разные
 * стратегии аллокации</b>, а не деталь реализации (§8), поэтому режим —
 * параметр прогона и попадает в запись.
 */
public final class Allocators {

    /** Режим нормировки весов (§3.2, §5). */
    public enum Normalization {
        /** По всем стратегиям пула: вес недоступной означает кэш. */
        ALL,
        /** Только по доступным: капитал недоступной перераспределяется. */
        AVAILABLE_ONLY
    }

    private Allocators() {
    }

    // ------------------------------------------------------------------ EW

    /** Равные веса — тривиальный бенчмарк, считается первым (§9 этап 3). */
    public static final class EqualWeight implements Allocator {

        private final String id;
        private final Normalization mode;

        public EqualWeight(String id, Normalization mode) {
            this.id = id;
            this.mode = mode;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public double[] weights(History h) {
            int n = h.strategies();
            double[] w = new double[n];
            if (mode == Normalization.ALL) {
                Arrays.fill(w, 1.0 / n);
                return w;
            }
            int available = 0;
            for (int i = 0; i < n; i++) {
                if (h.availableToday(i)) {
                    available++;
                }
            }
            if (available == 0) {
                Arrays.fill(w, 1.0 / n);
                return w;
            }
            for (int i = 0; i < n; i++) {
                w[i] = h.availableToday(i) ? 1.0 / available : 0;
            }
            return w;
        }
    }

    // ------------------------------------------------------------ DETECTOR

    /**
     * Матрица аллокации по состояниям детектора — то, что используется сейчас
     * (док. 00 v3 §4). BULL: S4 40% / S2S9 40% / резерв 20%; BEAR: S4 75% /
     * S5 10% / S6 0–15% при {@code cycle_phase = ACCUMULATION} / резерв 15%.
     * Резерв — кэш, поэтому веса стратегий в сумме меньше единицы, а остаток
     * движок кладёт в кэш явной строкой пула не является.
     */
    public static final class Detector implements Allocator {

        private final Map<String, Map<String, Double>> matrix;
        private String[] ids;

        public Detector() {
            Map<String, Double> bull = new LinkedHashMap<>();
            bull.put("S4", 0.40);
            bull.put("S2S9", 0.40);
            Map<String, Double> bear = new LinkedHashMap<>();
            bear.put("S4", 0.75);
            bear.put("S5", 0.10);
            matrix = Map.of("BULL", bull, "BEAR", bear);
        }

        @Override
        public String id() {
            return "DETECTOR";
        }

        @Override
        public void reset(int n) {
            ids = null;
        }

        @Override
        public double[] weights(History h) {
            if (ids == null) {
                ids = h.ids();
            }
            double[] w = new double[ids.length];
            String state = h.regimeToday();
            if (state == null) {
                // до прогрева детектора весь капитал в кэше: сумма весов = 0
                return w;
            }
            Map<String, Double> row = matrix.getOrDefault(state, Map.of());
            for (int i = 0; i < ids.length; i++) {
                w[i] = row.getOrDefault(ids[i], 0.0);
            }
            if ("BEAR".equals(state) && "ACCUMULATION".equals(h.cyclePhaseToday())) {
                // S6 забирает 15% из доли S4 (сноска матрицы)
                for (int i = 0; i < ids.length; i++) {
                    if ("S6".equals(ids[i])) {
                        w[i] = 0.15;
                    } else if ("S4".equals(ids[i])) {
                        w[i] = Math.max(0, w[i] - 0.15);
                    }
                }
            }
            return w;
        }
    }

    // --------------------------------------------------------------- HEDGE

    /**
     * Мультипликативные веса: {@code w_i ∝ exp(η · Σ_{s≤t} ret_i(s))}.
     * Окно {@code window} (0 = без окна) — скользящий накопленный P&L (§5).
     */
    public static final class Hedge implements Allocator {

        private final double eta;
        private final int window;
        private final String id;

        public Hedge(String id, double eta, int window) {
            this.id = id;
            this.eta = eta;
            this.window = window;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public double[] weights(History h) {
            int n = h.strategies();
            double[] score = new double[n];
            int t = h.today();
            int from = window > 0 ? Math.max(0, t - window + 1) : 0;
            for (int day = from; day <= t; day++) {
                for (int i = 0; i < n; i++) {
                    score[i] += h.ret(day, i);
                }
            }
            return softmax(score, eta);
        }
    }

    /**
     * Инерционный Hedge (§4.3, постановка Блум–Калаи): веса пересматриваются,
     * только если {@code Σ|Δw|} превышает порог, иначе остаются вчерашними.
     * Порог выводится из логики: пересмотр стоит {@code c_switch·Σ|Δw|/2}, и
     * ниже {@code threshold} ожидаемая экономия издержек не окупает потерю от
     * устаревших весов. Значение — из конфига, чувствительность — сеткой (§5).
     */
    public static final class LazyHedge implements Allocator {

        private final Hedge inner;
        private final double threshold;
        private double[] current;

        public LazyHedge(Hedge inner, double threshold) {
            this.inner = inner;
            this.threshold = threshold;
        }

        @Override
        public String id() {
            return "HEDGE_LAZY";
        }

        @Override
        public void reset(int n) {
            inner.reset(n);
            current = null;
        }

        @Override
        public double[] weights(History h) {
            double[] target = inner.weights(h);
            if (current == null) {
                current = target;
                return current.clone();
            }
            double move = 0;
            for (int i = 0; i < target.length; i++) {
                move += Math.abs(target[i] - current[i]);
            }
            if (move >= threshold) {
                current = target;
            }
            return current.clone();
        }
    }

    // ------------------------------------------------------------------ EG

    /**
     * Exponentiated Gradient (Helmbold et al.): {@code w_i ← w_i · exp(η · x_i / (w·x))},
     * где {@code x = 1 + ret} — ценовые отношения. Градиент считается по
     * доходности портфеля, а не по одиночной стратегии — этим EG отличается от
     * Hedge, работающего с накопленным P&L каждой стратегии по отдельности.
     */
    public static final class ExponentiatedGradient implements Allocator {

        private final double eta;
        private double[] w;

        public ExponentiatedGradient(double eta) {
            this.eta = eta;
        }

        @Override
        public String id() {
            return "EG";
        }

        @Override
        public void reset(int n) {
            w = new double[n];
            Arrays.fill(w, 1.0 / n);
        }

        @Override
        public double[] weights(History h) {
            int n = h.strategies();
            if (w == null) {
                reset(n);
            }
            int t = h.today();
            if (t < 0) {
                return w.clone();
            }
            double[] x = new double[n];
            double dot = 0;
            for (int i = 0; i < n; i++) {
                x[i] = 1 + h.ret(t, i);
                dot += w[i] * x[i];
            }
            if (dot <= 0 || !Double.isFinite(dot)) {
                return w.clone();
            }
            double[] next = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                next[i] = w[i] * Math.exp(eta * x[i] / dot);
                sum += next[i];
            }
            for (int i = 0; i < n; i++) {
                next[i] /= sum;
            }
            w = next;
            return w.clone();
        }
    }

    // ----------------------------------------------------------------- ONS

    /**
     * Online Newton Step (Agarwal–Hazan–Kale–Schapire): регрет {@code O(log T)}
     * против лучшего постоянного микса. Второй порядок: накапливает матрицу
     * {@code A = Σ ∇∇ᵀ}, шаг — ньютоновский с проекцией на симплекс в норме A.
     * Здесь используется диагональное приближение A: полная матрица при N ≈ 7
     * дала бы тот же порядок регрета ценой обращения матрицы на каждом шаге,
     * а диагональ достаточна для вопроса §6.3 (бьёт ли ONS равные веса).
     */
    public static final class OnlineNewtonStep implements Allocator {

        private final double beta;
        private final double epsilon;
        private double[] w;
        private double[] a;

        /**
         * @param beta    параметр экспоненциальной вогнутости шага (β в постановке AHKS)
         * @param epsilon начальная диагональ матрицы A₀ = εI
         */
        public OnlineNewtonStep(double beta, double epsilon) {
            this.beta = beta;
            this.epsilon = epsilon;
        }

        @Override
        public String id() {
            return "ONS";
        }

        @Override
        public void reset(int n) {
            w = new double[n];
            Arrays.fill(w, 1.0 / n);
            a = new double[n];
            Arrays.fill(a, epsilon);
        }

        @Override
        public double[] weights(History h) {
            int n = h.strategies();
            if (w == null) {
                reset(n);
            }
            int t = h.today();
            if (t < 0) {
                return w.clone();
            }
            double dot = 0;
            double[] x = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = 1 + h.ret(t, i);
                dot += w[i] * x[i];
            }
            if (dot <= 0 || !Double.isFinite(dot)) {
                return w.clone();
            }
            double[] target = new double[n];
            for (int i = 0; i < n; i++) {
                double grad = -x[i] / dot;                  // градиент −log(w·x)
                a[i] += grad * grad;
                target[i] = w[i] - grad / (beta * a[i]);    // ньютоновский шаг, A диагональна
            }
            w = projectToSimplex(target);
            return w.clone();
        }
    }

    // -------------------------------------------------------------- RANDOM

    /**
     * Случайные веса из симплекса с фиксированным seed — контроль (§4.1):
     * отвечает, добавляет ли ценность <b>выбор</b> весов или работает сам факт
     * диверсификации. Аллокатор, не превосходящий этот, отклоняется.
     */
    public static final class RandomWeights implements Allocator {

        private final long seed;
        private final int redrawEvery;
        private final String id;
        private Random rnd;
        private double[] w;
        private int sinceDraw;

        public RandomWeights(String id, long seed, int redrawEvery) {
            this.id = id;
            this.seed = seed;
            this.redrawEvery = Math.max(1, redrawEvery);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void reset(int n) {
            rnd = new Random(seed);
            w = draw(n);
            sinceDraw = 0;
        }

        @Override
        public double[] weights(History h) {
            if (w == null) {
                reset(h.strategies());
            }
            if (++sinceDraw >= redrawEvery) {
                w = draw(h.strategies());
                sinceDraw = 0;
            }
            return w.clone();
        }

        private double[] draw(int n) {
            return randomSimplex(rnd, n);
        }
    }

    // ------------------------------------------------------------ ОРИЕНТИРЫ

    /** Постоянные веса (используется для BEST_FIXED и BEST_SINGLE). */
    public static final class Fixed implements Allocator {

        private final String id;
        private final double[] w;
        private final boolean hindsight;

        public Fixed(String id, double[] w, boolean hindsight) {
            this.id = id;
            this.w = w.clone();
            this.hindsight = hindsight;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public double[] weights(History h) {
            return w.clone();
        }

        @Override
        public boolean hindsight() {
            return hindsight;
        }

        public double[] mix() {
            return w.clone();
        }
    }

    // ------------------------------------------------------------ утилиты

    /** Равномерная точка симплекса (Дирихле(1,…,1)): экспоненциальные веса с нормировкой. */
    public static double[] randomSimplex(Random rnd, int n) {
        double[] v = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            v[i] = -Math.log(Math.max(rnd.nextDouble(), 1e-12));
            sum += v[i];
        }
        for (int i = 0; i < n; i++) {
            v[i] /= sum;
        }
        return v;
    }

    static double[] softmax(double[] score, double eta) {
        int n = score.length;
        double max = Double.NEGATIVE_INFINITY;
        for (double s : score) {
            max = Math.max(max, s);
        }
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            w[i] = Math.exp(eta * (score[i] - max));   // сдвиг на max: защита от переполнения
            sum += w[i];
        }
        for (int i = 0; i < n; i++) {
            w[i] /= sum;
        }
        return w;
    }

    /** Евклидова проекция на симплекс (Duchi et al.). */
    static double[] projectToSimplex(double[] v) {
        int n = v.length;
        double[] u = v.clone();
        Arrays.sort(u);
        // u по убыванию
        for (int i = 0; i < n / 2; i++) {
            double tmp = u[i];
            u[i] = u[n - 1 - i];
            u[n - 1 - i] = tmp;
        }
        double cumulative = 0;
        double theta = 0;
        int rho = -1;
        for (int i = 0; i < n; i++) {
            cumulative += u[i];
            double candidate = (cumulative - 1) / (i + 1);
            if (u[i] - candidate > 0) {
                rho = i;
                theta = candidate;
            }
        }
        if (rho < 0) {
            double[] w = new double[n];
            Arrays.fill(w, 1.0 / n);
            return w;
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = Math.max(v[i] - theta, 0);
        }
        return w;
    }

    /**
     * Теоретическое η* = √(8 ln N / T) (§8: берётся из теории, не подбирается) —
     * в <b>нормированных</b> единицах, где дневная доходность лежит в [0,1].
     *
     * <p>Классическая граница Hedge выведена для выигрышей в [0,1]. Дневные
     * доходности стратегий лежат в диапазоне шириной {@code scale} (у крипто-
     * стратегий это доли процента, а не единицы), поэтому и η, и граница
     * масштабируются на {@code scale}: {@code η_raw = η* / scale}, граница
     * {@code scale·√(T ln N/2)}. Без этого масштабирования Hedge при η*
     * практически не отличает стратегии, регрет вылезает за границу, и это
     * выглядит как дефект алгоритма, хотя дефект — в единицах измерения.
     */
    public static double etaStar(int n, int t, double scale) {
        if (scale <= 0) {
            throw new IllegalArgumentException("масштаб доходностей должен быть > 0");
        }
        return Math.sqrt(8 * Math.log(Math.max(n, 2)) / Math.max(t, 1)) / scale;
    }

    /** Наблюдённый размах дневных доходностей пула — масштаб для η и границы регрета. */
    public static double observedScale(double[][] ret, int n) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : ret) {
            for (int i = 0; i < n; i++) {
                if (Double.isFinite(row[i])) {
                    min = Math.min(min, row[i]);
                    max = Math.max(max, row[i]);
                }
            }
        }
        double span = max - min;
        return span > 0 ? span : 1;
    }
}
