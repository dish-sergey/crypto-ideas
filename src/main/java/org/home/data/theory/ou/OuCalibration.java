package org.home.data.theory.ou;

import java.util.Arrays;
import java.util.Random;

/**
 * Калибровка процесса Орнштейна–Уленбека (ТЗ 67 §4).
 *
 * <p>Дискретизация с шагом {@code Δt}:
 * <pre>X_{t+1} = X_t·e^{−κΔt} + θ(1 − e^{−κΔt}) + ε_t</pre>
 *
 * <p>Обе оценки обязательны (§4.1): МНК через AR(1) и MLE на точной переходной
 * плотности. Расхождение больше порога — <b>сигнал проблемы с данными</b>, а не
 * повод выбрать «лучшую»: в отчёт идут обе.
 *
 * <p>Шаг {@code Δt} берётся пофакту для каждой пары наблюдений, поэтому пропуски
 * и нерегулярная сетка обрабатываются корректно, а не считаются регулярными
 * (§7.3). На регулярной сетке оценка алгебраически совпадает с классической
 * регрессией AR(1) — это проверяется тестом.
 */
public final class OuCalibration {

    private OuCalibration() {
    }

    /**
     * Результат калибровки.
     *
     * @param kappa      скорость возврата
     * @param theta      уровень, к которому идёт возврат
     * @param sigma      мгновенная волатильность
     * @param phi        {@code e^{−κΔt}} на медианном шаге
     * @param halfLife   полупериод {@code ln2/κ} в единицах времени ряда
     * @param n          число использованных переходов
     */
    public record Fit(double kappa, double theta, double sigma, double phi, double halfLife, int n) {

        public static Fit of(double kappa, double theta, double sigma, double medianDt, int n) {
            return new Fit(kappa, theta, sigma, Math.exp(-kappa * medianDt),
                    kappa > 0 ? Math.log(2) / kappa : Double.POSITIVE_INFINITY, n);
        }
    }

    /** Оценка МНК (нелинейный LS на точном условном среднем; на регулярной сетке — AR(1)). */
    public static Fit ols(double[] dt, double[] x) {
        int n = dt.length;
        if (n < 10) {
            return Fit.of(0, mean(x), 0, median(dt), n);
        }
        if (isRegular(dt)) {
            return olsRegular(dt[0], x);
        }
        // У реальных рядов различных шагов единицы (минута плюс несколько разрывов),
        // поэтому exp(−κΔt) считается по РАЗЛИЧНЫМ шагам, а не по каждой точке:
        // на сшитой стресс-выборке это разница между секундами и минутами прогона.
        Steps steps = Steps.of(dt);
        double kappa = goldenSection(k -> -sse(k, steps, x), 1e-6, 50.0);
        double theta = thetaGiven(kappa, steps, x);
        double sigma = sigmaGiven(kappa, theta, steps, x);
        return Fit.of(kappa, theta, sigma, median(dt), n);
    }

    private static boolean isRegular(double[] dt) {
        double first = dt[0];
        double tolerance = Math.max(Math.abs(first) * 1e-9, 1e-12);
        for (double d : dt) {
            if (Math.abs(d - first) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Замкнутая форма для регулярного шага: та же задача наименьших квадратов,
     * что решает золотое сечение, но за один проход вместо сотни. Нужна не ради
     * красоты: на минутном ряде базиса (десятки тысяч точек) бутстрап из 2000
     * реплик с итерационным поиском не считается за разумное время.
     */
    private static Fit olsRegular(double step, double[] x) {
        int n = x.length - 1;
        double meanLag = 0;
        double meanNext = 0;
        for (int i = 0; i < n; i++) {
            meanLag += x[i] / n;
            meanNext += x[i + 1] / n;
        }
        double cov = 0;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double d = x[i] - meanLag;
            cov += d * (x[i + 1] - meanNext);
            var += d * d;
        }
        double phi = var > 0 ? cov / var : 0;
        phi = Math.max(Math.min(phi, 1 - 1e-12), 1e-12);
        double kappa = -Math.log(phi) / step;
        double theta = (meanNext - phi * meanLag) / (1 - phi);
        double residual = 0;
        for (int i = 0; i < n; i++) {
            double r = x[i + 1] - phi * x[i] - theta * (1 - phi);
            residual += r * r;
        }
        double factor = (1 - phi * phi) / (2 * kappa);
        double sigma = factor > 0 ? Math.sqrt(residual / n / factor) : 0;
        return Fit.of(kappa, theta, sigma, step, n);
    }

    /**
     * MLE на точной переходной плотности OU (гауссова) с учётом стационарного
     * распределения первой точки. Отличается от МНК именно этим слагаемым —
     * поэтому расхождение оценок информативно.
     */
    public static Fit mle(double[] dt, double[] x) {
        Fit start = ols(dt, x);
        double[] init = {Math.log(Math.max(start.kappa(), 1e-4)), start.theta(),
                Math.log(Math.max(start.sigma(), 1e-8))};
        double[] best = nelderMead(p -> negLogLikelihood(Math.exp(p[0]), p[1], Math.exp(p[2]), dt, x), init);
        return Fit.of(Math.exp(best[0]), best[1], Math.exp(best[2]), median(dt), dt.length);
    }

    private static double negLogLikelihood(double kappa, double theta, double sigma, double[] dt, double[] x) {
        if (kappa <= 0 || sigma <= 0 || !Double.isFinite(kappa) || !Double.isFinite(sigma)) {
            return Double.MAX_VALUE;
        }
        double stationaryVar = sigma * sigma / (2 * kappa);
        double ll = -0.5 * Math.log(2 * Math.PI * stationaryVar)
                - (x[0] - theta) * (x[0] - theta) / (2 * stationaryVar);
        for (int i = 0; i < dt.length; i++) {
            double a = Math.exp(-kappa * dt[i]);
            double mean = x[i] * a + theta * (1 - a);
            double var = sigma * sigma * (1 - a * a) / (2 * kappa);
            if (var <= 0) {
                return Double.MAX_VALUE;
            }
            double d = x[i + 1] - mean;
            ll += -0.5 * Math.log(2 * Math.PI * var) - d * d / (2 * var);
        }
        return -ll;
    }

    /**
     * Аналитическая поправка смещения Kendall / Marriott–Pope: МНК занижает φ на
     * {@code (1+3φ)/n}, то есть <b>завышает κ</b> и укорачивает полупериод.
     */
    public static double analyticBiasCorrectedKappa(double phi, int n, double medianDt) {
        double corrected = Math.min(phi + (1 + 3 * phi) / n, 0.999999);
        return corrected <= 0 ? 0 : -Math.log(corrected) / medianDt;
    }

    /**
     * Бутстрап-поправка смещения (основная, §4.2): генерируем ряды из подогнанной
     * модели, оцениваем κ на каждом, измеряем {@code E[κ̂] − κ_true} и вычитаем.
     *
     * @return {@code {скорректированная κ, измеренное смещение}}
     */
    public static double[] bootstrapBiasCorrectedKappa(Fit fit, double[] dt, int replicas, long seed) {
        Random rnd = new Random(seed);
        double sum = 0;
        for (int b = 0; b < replicas; b++) {
            double[] path = simulate(fit.kappa(), fit.theta(), fit.sigma(), dt, rnd);
            sum += ols(dt, path).kappa();
        }
        double bias = sum / replicas - fit.kappa();
        return new double[]{Math.max(fit.kappa() - bias, 1e-9), bias};
    }

    /**
     * Параметрический бутстрап: за один проход даёт и поправку смещения (§4.2),
     * и доверительные интервалы (§4.3). Один проход вместо двух — потому что
     * реплики одни и те же, а прогон стенда упирается именно в них.
     *
     * @param correctedKappa κ после вычитания измеренного смещения
     * @param bias           измеренное смещение {@code E[κ̂] − κ}
     * @param kappas         отсортированные реплики κ
     * @param thetas         отсортированные реплики θ
     * @param sigmas         отсортированные реплики σ
     * @param halfLives      отсортированные реплики полупериода
     */
    public record Bootstrap(double correctedKappa, double bias, double[] kappas, double[] thetas,
                            double[] sigmas, double[] halfLives) {

        /** Квантиль реплик; для полупериода решения принимаются по 95-му процентилю. */
        public static double quantile(double[] sorted, double q) {
            return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.round(q * (sorted.length - 1))))];
        }
    }

    public static Bootstrap bootstrap(Fit fit, double[] dt, int replicas, long seed) {
        Random rnd = new Random(seed);
        double[] kappas = new double[replicas];
        double[] thetas = new double[replicas];
        double[] sigmas = new double[replicas];
        double[] halfLives = new double[replicas];
        double sum = 0;
        for (int b = 0; b < replicas; b++) {
            double[] path = simulate(fit.kappa(), fit.theta(), fit.sigma(), dt, rnd);
            Fit f = ols(dt, path);
            kappas[b] = f.kappa();
            thetas[b] = f.theta();
            sigmas[b] = f.sigma();
            halfLives[b] = f.halfLife();
            sum += f.kappa();
        }
        double bias = sum / replicas - fit.kappa();
        Arrays.sort(kappas);
        Arrays.sort(thetas);
        Arrays.sort(sigmas);
        Arrays.sort(halfLives);
        return new Bootstrap(Math.max(fit.kappa() - bias, 1e-9), bias, kappas, thetas, sigmas, halfLives);
    }

    /** Генерация траектории OU по заданной сетке шагов (используется и в контролях §2). */
    public static double[] simulate(double kappa, double theta, double sigma, double[] dt, Random rnd) {
        double[] x = new double[dt.length + 1];
        double stationarySd = Math.sqrt(sigma * sigma / (2 * kappa));
        x[0] = theta + stationarySd * rnd.nextGaussian();
        for (int i = 0; i < dt.length; i++) {
            double a = Math.exp(-kappa * dt[i]);
            double sd = Math.sqrt(sigma * sigma * (1 - a * a) / (2 * kappa));
            x[i + 1] = x[i] * a + theta * (1 - a) + sd * rnd.nextGaussian();
        }
        return x;
    }

    /** Случайное блуждание той же длины — контроль {@code NEG_RW} (§2). */
    public static double[] randomWalk(int n, double sigma, Random rnd) {
        double[] x = new double[n];
        for (int i = 1; i < n; i++) {
            x[i] = x[i - 1] + sigma * rnd.nextGaussian();
        }
        return x;
    }

    // ------------------------------------------------------------------ детали

    /**
     * Сетка шагов: различные значения Δt и индекс шага для каждой пары наблюдений.
     * Позволяет считать exp(−κΔt) по различным шагам (их единицы), а не по каждой
     * точке ряда (их десятки тысяч).
     */
    private record Steps(double[] distinct, int[] index, int n) {

        static Steps of(double[] dt) {
            java.util.LinkedHashMap<Double, Integer> seen = new java.util.LinkedHashMap<>();
            int[] index = new int[dt.length];
            for (int i = 0; i < dt.length; i++) {
                index[i] = seen.computeIfAbsent(dt[i], k -> seen.size());
            }
            double[] distinct = new double[seen.size()];
            for (java.util.Map.Entry<Double, Integer> e : seen.entrySet()) {
                distinct[e.getValue()] = e.getKey();
            }
            return new Steps(distinct, index, dt.length);
        }

        double[] decay(double kappa) {
            double[] a = new double[distinct.length];
            for (int j = 0; j < a.length; j++) {
                a[j] = Math.exp(-kappa * distinct[j]);
            }
            return a;
        }
    }

    private static double sse(double kappa, Steps steps, double[] x) {
        double theta = thetaGiven(kappa, steps, x);
        double[] decay = steps.decay(kappa);
        double sse = 0;
        for (int i = 0; i < steps.n(); i++) {
            double a = decay[steps.index()[i]];
            double r = x[i + 1] - a * x[i] - theta * (1 - a);
            sse += r * r;
        }
        return sse;
    }

    private static double thetaGiven(double kappa, Steps steps, double[] x) {
        double[] decay = steps.decay(kappa);
        double num = 0;
        double den = 0;
        for (int i = 0; i < steps.n(); i++) {
            double a = decay[steps.index()[i]];
            double c = 1 - a;
            num += (x[i + 1] - a * x[i]) * c;
            den += c * c;
        }
        return den > 0 ? num / den : mean(x);
    }

    private static double sigmaGiven(double kappa, double theta, Steps steps, double[] x) {
        double[] decay = steps.decay(kappa);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < steps.n(); i++) {
            double a = decay[steps.index()[i]];
            double r = x[i + 1] - a * x[i] - theta * (1 - a);
            double factor = (1 - a * a) / (2 * kappa);
            if (factor > 0) {
                sum += r * r / factor;
                n++;
            }
        }
        return n > 0 ? Math.sqrt(sum / n) : 0;
    }

    /** Максимизация вогнутой функции золотым сечением. */
    private static double goldenSection(java.util.function.DoubleUnaryOperator f, double low, double high) {
        double phi = (Math.sqrt(5) - 1) / 2;
        double a = low;
        double b = high;
        double c = b - phi * (b - a);
        double d = a + phi * (b - a);
        for (int i = 0; i < 80; i++) {
            if (f.applyAsDouble(c) < f.applyAsDouble(d)) {
                a = c;
            } else {
                b = d;
            }
            c = b - phi * (b - a);
            d = a + phi * (b - a);
        }
        return (a + b) / 2;
    }

    /** Нелдер–Мид: минимизация без производных (для MLE). */
    static double[] nelderMead(java.util.function.ToDoubleFunction<double[]> f, double[] start) {
        int n = start.length;
        double[][] simplex = new double[n + 1][];
        double[] values = new double[n + 1];
        simplex[0] = start.clone();
        for (int i = 0; i < n; i++) {
            double[] p = start.clone();
            p[i] += Math.abs(p[i]) > 1e-8 ? 0.1 * Math.abs(p[i]) : 0.1;
            simplex[i + 1] = p;
        }
        for (int i = 0; i <= n; i++) {
            values[i] = f.applyAsDouble(simplex[i]);
        }
        // 3000 шагов симплекс тратил на ряды в сотни тысяч точек десятки минут, а
        // сходится он на два порядка раньше; выход по стабилизации ниже всё равно есть.
        for (int iter = 0; iter < 400; iter++) {
            Integer[] order = new Integer[n + 1];
            for (int i = 0; i <= n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
            double[][] s = new double[n + 1][];
            double[] v = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                s[i] = simplex[order[i]];
                v[i] = values[order[i]];
            }
            simplex = s;
            System.arraycopy(v, 0, values, 0, v.length);
            if (Math.abs(values[n] - values[0]) < 1e-12) {
                break;
            }
            double[] centroid = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    centroid[j] += simplex[i][j] / n;
                }
            }
            double[] reflected = combine(centroid, simplex[n], 1.0);
            double fr = f.applyAsDouble(reflected);
            if (fr < values[0]) {
                double[] expanded = combine(centroid, simplex[n], 2.0);
                double fe = f.applyAsDouble(expanded);
                simplex[n] = fe < fr ? expanded : reflected;
                values[n] = Math.min(fe, fr);
            } else if (fr < values[n - 1]) {
                simplex[n] = reflected;
                values[n] = fr;
            } else {
                double[] contracted = combine(centroid, simplex[n], -0.5);
                double fc = f.applyAsDouble(contracted);
                if (fc < values[n]) {
                    simplex[n] = contracted;
                    values[n] = fc;
                } else {
                    for (int i = 1; i <= n; i++) {
                        for (int j = 0; j < n; j++) {
                            simplex[i][j] = simplex[0][j] + 0.5 * (simplex[i][j] - simplex[0][j]);
                        }
                        values[i] = f.applyAsDouble(simplex[i]);
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i <= n; i++) {
            if (values[i] < values[best]) {
                best = i;
            }
        }
        return simplex[best];
    }

    private static double[] combine(double[] centroid, double[] worst, double factor) {
        double[] out = new double[centroid.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = centroid[i] + factor * (centroid[i] - worst[i]);
        }
        return out;
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? 0 : s / v.length;
    }

    static double median(double[] v) {
        if (v.length == 0) {
            return 1;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
