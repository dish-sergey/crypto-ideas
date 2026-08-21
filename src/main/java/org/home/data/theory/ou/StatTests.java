package org.home.data.theory.ou;

/**
 * Статистические тесты допуска (ТЗ 67 §5): ADF, KPSS, тест на структурный сдвиг
 * уровня с неизвестной точкой разрыва.
 *
 * <p>Реализация собственная, поэтому §3 требует обязательной проверки на
 * синтетике (§7): реализация, не воспроизводящая известный ответ на
 * сгенерированных данных, не принимается независимо от происхождения.
 *
 * <p><b>Отсутствие отвержения — не подтверждение.</b> ADF на 500 точках плохо
 * различает φ = 0.99 и φ = 1.0, поэтому критерии сформулированы так, чтобы
 * требовать положительного свидетельства: ADF <i>отвергает</i> единичный корень
 * И KPSS <i>не отвергает</i> стационарность.
 */
public final class StatTests {

    /** Критические значения ADF (константа без тренда, асимптотические, Fuller). */
    private static final double ADF_1 = -3.43;
    private static final double ADF_5 = -2.86;
    private static final double ADF_10 = -2.57;

    /** Критические значения KPSS (уровень, Kwiatkowski et al. 1992). */
    private static final double KPSS_10 = 0.347;
    private static final double KPSS_5 = 0.463;
    private static final double KPSS_1 = 0.739;

    /** Критическое значение sup-Wald на разрыв среднего (Andrews, 15% обрезка, 1 параметр). */
    private static final double SUP_WALD_5 = 8.85;

    private StatTests() {
    }

    /**
     * Автоматический выбор числа лагов / полосы (правило Шверта–Ньюи–Уэста):
     * {@code ⌈4·(n/100)^{1/4}⌉}. Фиксированный маленький лаг — прямая ошибка на
     * персистентных рядах: долгосрочная дисперсия AR(1) с φ=0.8 в 25 раз выше
     * краткосрочной, и KPSS с одним лагом отвергает стационарность даже на
     * настоящем OU (поймано контролем {@code POS_SYN}).
     */
    public static int autoLags(int n) {
        return (int) Math.ceil(4 * Math.pow(n / 100.0, 0.25));
    }

    /**
     * Полоса Ньюи–Уэста по данным (Andrews, ядро Бартлетта) через AR(1)-аппроксимацию:
     * <pre>α(1) = 4ρ²/((1−ρ)²(1+ρ)²),  l = 1.1447·(α(1)·n)^{1/3}</pre>
     *
     * <p>Для KPSS это принципиально: у медленного OU (φ ≈ 0.99) долгосрочная
     * дисперсия в сотни раз выше краткосрочной, и правило Шверта её всё ещё
     * занижает — тест отвергает стационарность у настоящего, но медленного
     * OU-процесса (поймано контролем {@code POS_SYN_SLOW}).
     */
    public static int andrewsBandwidth(double[] x) {
        int n = x.length;
        double rho = Math.max(Math.min(lag1Autocorrelation(x), 0.9999), 0);
        double alpha = 4 * rho * rho / (Math.pow(1 - rho, 2) * Math.pow(1 + rho, 2));
        int l = (int) Math.ceil(1.1447 * Math.cbrt(Math.max(alpha, 1e-9) * n));
        return Math.max(1, Math.min(l, n - 2));
    }

    /** Автокорреляция первого порядка — нужна для поправки на персистентность. */
    static double lag1Autocorrelation(double[] x) {
        double mean = OuCalibration.mean(x);
        double num = 0;
        double den = 0;
        for (int i = 0; i < x.length; i++) {
            double d = x[i] - mean;
            den += d * d;
            if (i > 0) {
                num += d * (x[i - 1] - mean);
            }
        }
        return den > 0 ? num / den : 0;
    }

    /** Результат теста: статистика, приблизительный уровень значимости и вердикт. */
    public record TestResult(double statistic, double criticalValue, boolean rejected, String note) {
    }

    /**
     * ADF с константой: регрессия {@code Δx_t = α + ρ·x_{t−1} + Σ γ_i Δx_{t−i} + ε}
     * и t-статистика по {@code ρ}. Отвержение единичного корня — свидетельство
     * стационарности.
     */
    public static TestResult adf(double[] x, int lags, double level) {
        int n = x.length;
        int rows = n - lags - 1;
        if (rows < 20) {
            return new TestResult(Double.NaN, Double.NaN, false, "мало наблюдений");
        }
        int cols = 2 + lags;                       // константа, x_{t−1}, лаги Δx
        double[][] design = new double[rows][cols];
        double[] y = new double[rows];
        for (int i = 0; i < rows; i++) {
            int t = i + lags + 1;
            y[i] = x[t] - x[t - 1];
            design[i][0] = 1;
            design[i][1] = x[t - 1];
            for (int l = 1; l <= lags; l++) {
                design[i][1 + l] = x[t - l] - x[t - l - 1];
            }
        }
        double[] beta = ols(design, y);
        double[] se = standardErrors(design, y, beta);
        double tStat = beta[1] / se[1];
        double critical = criticalAdf(level);
        return new TestResult(tStat, critical, tStat < critical,
                "t по ρ; отвержение = стационарность");
    }

    /**
     * KPSS (уровень): нулевая гипотеза — <b>стационарность</b>, поэтому
     * отвержение говорит против OU. Долгосрочная дисперсия — оценка
     * Ньюи–Уэста с треугольным окном.
     */
    public static TestResult kpss(double[] x, int lags, double level) {
        int n = x.length;
        if (n < 20) {
            return new TestResult(Double.NaN, Double.NaN, false, "мало наблюдений");
        }
        double mean = OuCalibration.mean(x);
        double[] resid = new double[n];
        for (int i = 0; i < n; i++) {
            resid[i] = x[i] - mean;
        }
        double partial = 0;
        double sumSquares = 0;
        for (double r : resid) {
            partial += r;
            sumSquares += partial * partial;
        }
        double s2 = 0;
        for (double r : resid) {
            s2 += r * r;
        }
        s2 /= n;
        for (int l = 1; l <= lags; l++) {
            double cov = 0;
            for (int i = l; i < n; i++) {
                cov += resid[i] * resid[i - l];
            }
            s2 += 2.0 * (1 - l / (lags + 1.0)) * cov / n;
        }
        double stat = sumSquares / (n * (double) n * s2);
        double critical = criticalKpss(level);
        return new TestResult(stat, critical, stat > critical,
                "нулевая гипотеза — стационарность; отвержение = против OU");
    }

    /**
     * Тест на структурный сдвиг уровня {@code θ} с неизвестной точкой разрыва
     * (sup-Wald по всем внутренним точкам с 15% обрезкой). Самый вероятный
     * способ получить ложно-положительный OU: два режима с разными уровнями
     * дают осмысленную общую подгонку и бессмысленное общее {@code θ}.
     */
    public static TestResult supWaldMeanBreak(double[] x) {
        int n = x.length;
        int lo = (int) (0.15 * n);
        int hi = (int) (0.85 * n);
        if (hi - lo < 10) {
            return new TestResult(Double.NaN, SUP_WALD_5, false, "мало наблюдений");
        }
        double best = 0;
        int bestIndex = -1;
        double total = 0;
        double mean = OuCalibration.mean(x);
        for (double v : x) {
            total += (v - mean) * (v - mean);
        }
        for (int b = lo; b < hi; b++) {
            double m1 = 0;
            double m2 = 0;
            for (int i = 0; i < b; i++) {
                m1 += x[i];
            }
            m1 /= b;
            for (int i = b; i < n; i++) {
                m2 += x[i];
            }
            m2 /= (n - b);
            double rss = 0;
            for (int i = 0; i < n; i++) {
                double d = x[i] - (i < b ? m1 : m2);
                rss += d * d;
            }
            double f = (total - rss) / (rss / (n - 2));
            if (f > best) {
                best = f;
                bestIndex = b;
            }
        }
        // Поправка на персистентность: критическое значение выведено для независимых
        // наблюдений, а у OU соседние точки коррелированы — без поправки разрыв
        // «обнаруживается» на любом настоящем OU (поймано контролем POS_SYN).
        double rho = Math.max(Math.min(lag1Autocorrelation(x), 0.999), 0);
        double adjusted = best * (1 - rho) / (1 + rho);
        return new TestResult(adjusted, SUP_WALD_5, adjusted > SUP_WALD_5,
                String.format(java.util.Locale.ROOT, "точка разрыва %d из %d; сырой F=%.1f, "
                        + "поправка на автокорреляцию ρ=%.2f", bestIndex, n, best, rho));
    }

    private static double criticalAdf(double level) {
        if (level <= 0.01) {
            return ADF_1;
        }
        return level <= 0.05 ? ADF_5 : ADF_10;
    }

    private static double criticalKpss(double level) {
        if (level <= 0.01) {
            return KPSS_1;
        }
        return level <= 0.05 ? KPSS_5 : KPSS_10;
    }

    // ------------------------------------------------------------------ МНК

    static double[] ols(double[][] design, double[] y) {
        int n = design.length;
        int k = design[0].length;
        double[][] xtx = new double[k][k];
        double[] xty = new double[k];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < k; a++) {
                xty[a] += design[i][a] * y[i];
                for (int b = 0; b < k; b++) {
                    xtx[a][b] += design[i][a] * design[i][b];
                }
            }
        }
        return solve(xtx, xty);
    }

    static double[] standardErrors(double[][] design, double[] y, double[] beta) {
        int n = design.length;
        int k = beta.length;
        double rss = 0;
        for (int i = 0; i < n; i++) {
            double fitted = 0;
            for (int a = 0; a < k; a++) {
                fitted += design[i][a] * beta[a];
            }
            double r = y[i] - fitted;
            rss += r * r;
        }
        double s2 = rss / (n - k);
        double[][] xtx = new double[k][k];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < k; a++) {
                for (int b = 0; b < k; b++) {
                    xtx[a][b] += design[i][a] * design[i][b];
                }
            }
        }
        double[][] inv = invert(xtx);
        double[] se = new double[k];
        for (int a = 0; a < k; a++) {
            se[a] = Math.sqrt(Math.max(s2 * inv[a][a], 1e-30));
        }
        return se;
    }

    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            double d = m[col][col];
            if (Math.abs(d) < 1e-14) {
                d = Math.signum(d) * 1e-14 + 1e-14;
            }
            for (int c = col; c <= n; c++) {
                m[col][c] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r != col && m[r][col] != 0) {
                    double factor = m[r][col];
                    for (int c = col; c <= n; c++) {
                        m[r][c] -= factor * m[col][c];
                    }
                }
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = m[i][n];
        }
        return out;
    }

    private static double[][] invert(double[][] a) {
        int n = a.length;
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            double[] e = new double[n];
            e[i] = 1;
            double[] col = solve(a, e);
            for (int r = 0; r < n; r++) {
                out[r][i] = col[r];
            }
        }
        return out;
    }
}
