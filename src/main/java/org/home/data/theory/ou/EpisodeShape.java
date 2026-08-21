package org.home.data.theory.ou;

/**
 * Форма одного эпизода отклонения (ТЗ 72 §4.2, И1 и И2).
 *
 * <p>Двадцатичасовой эпизод при полупериоде 1.7 минуты внутренне противоречив: за
 * 20 часов процесс с таким κ вернулся бы к уровню сотни раз. Значит эпизод либо
 * другой процесс, либо вовсе не отклонение, а <b>сдвиг уровня</b>. Различить их
 * можно по форме: отклонение с возвратом затухает экспоненциально, сдвиг уровня
 * даёт плато и скачок.
 *
 * <p>Поэтому к каждому эпизоду подгоняются две модели и сравниваются по {@code R²}:
 * экспоненциальный возврат к предэпизодному уровню и «две константы с разрывом».
 *
 * @param durationMinutes  длительность эпизода
 * @param depth            максимальное отклонение от уровня
 * @param decayR2          качество подгонки экспоненциального затухания
 * @param stepR2           качество подгонки «плато + скачок»
 * @param startModFunding  час начала эпизода по модулю интервала funding
 * @param endModFunding    час конца эпизода по модулю интервала funding
 * @param profile          прореженный профиль значений — чтобы можно было посмотреть глазами
 */
public record EpisodeShape(double durationMinutes, double depth, double decayR2, double stepR2,
                           double startModFunding, double endModFunding, double[] profile) {

    /** Сколько точек профиля печатать в отчёте. */
    private static final int PROFILE_POINTS = 12;

    /** Эпизод короче четырёх точек формы не имеет — его нельзя ни к чему отнести. */
    public boolean classified() {
        return !Double.isNaN(decayR2()) && !Double.isNaN(stepR2());
    }

    /** Какая из двух моделей описывает эпизод лучше (для неклассифицируемых — прочерк). */
    public String shape() {
        if (!classified()) {
            return "—";
        }
        return decayR2() > stepR2() ? "затухание" : "**ступенька**";
    }

    public static EpisodeShape of(OuSeries series, OuSeries.Episode episode, double theta,
                                  double fundingIntervalHours) {
        double[] values = series.values();
        double[] times = series.times();
        int from = episode.from();
        int to = episode.to();
        int n = to - from + 1;
        double[] x = new double[n];
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = values[from + i];
            t[i] = times[from + i] - times[from];
        }
        double startHours = times[from] / 60.0;
        double endHours = times[to] / 60.0;
        return new EpisodeShape(episode.duration(), episode.depth(),
                decayR2(t, x, theta), stepR2(x),
                modulo(startHours, fundingIntervalHours), modulo(endHours, fundingIntervalHours),
                profile(x));
    }

    /**
     * Экспоненциальный возврат к уровню: {@code x(t) = θ + (x₀ − θ)·e^{−k·t}}.
     * Скорость {@code k} подбирается по минимуму квадратов — это подгонка формы,
     * а не оценка κ процесса.
     */
    private static double decayR2(double[] t, double[] x, double theta) {
        double amplitude = x[0] - theta;
        if (Math.abs(amplitude) < 1e-12 || x.length < 4) {
            return Double.NaN;
        }
        double best = Double.POSITIVE_INFINITY;
        double span = Math.max(t[t.length - 1], 1e-9);
        // сетка по скорости затухания: от «почти не затухает» до «затухает за первый шаг»
        for (int i = 0; i <= 200; i++) {
            double k = Math.pow(10, -6 + 8.0 * i / 200) / span;
            double sse = 0;
            for (int j = 0; j < x.length; j++) {
                double fitted = theta + amplitude * Math.exp(-k * t[j]);
                sse += (x[j] - fitted) * (x[j] - fitted);
            }
            best = Math.min(best, sse);
        }
        return rSquared(best, x);
    }

    /**
     * «Плато и скачок»: две константы с разрывом в наилучшей точке. Точка разрыва
     * ищется перебором по префиксным суммам — линейно, а не квадратично.
     */
    private static double stepR2(double[] x) {
        int n = x.length;
        if (n < 4) {
            return Double.NaN;
        }
        double[] prefix = new double[n + 1];
        double[] squares = new double[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + x[i];
            squares[i + 1] = squares[i] + x[i] * x[i];
        }
        double best = Double.POSITIVE_INFINITY;
        for (int b = 1; b < n; b++) {
            double m1 = prefix[b] / b;
            double m2 = (prefix[n] - prefix[b]) / (n - b);
            double sse = squares[b] - b * m1 * m1 + (squares[n] - squares[b]) - (n - b) * m2 * m2;
            best = Math.min(best, sse);
        }
        return rSquared(best, x);
    }

    private static double rSquared(double sse, double[] x) {
        double mean = OuCalibration.mean(x);
        double total = 0;
        for (double v : x) {
            total += (v - mean) * (v - mean);
        }
        return total > 0 ? 1 - sse / total : Double.NaN;
    }

    private static double[] profile(double[] x) {
        int points = Math.min(PROFILE_POINTS, x.length);
        double[] out = new double[points];
        for (int i = 0; i < points; i++) {
            out[i] = x[(int) Math.round((double) i * (x.length - 1) / Math.max(points - 1, 1))];
        }
        return out;
    }

    private static double modulo(double value, double period) {
        double m = value % period;
        return m < 0 ? m + period : m;
    }
}
