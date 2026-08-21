package org.home.data.theory.ou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетика для модуля 1 — ТЗ 72 §7.1. Вопрос ТЗ: не унаследовал ли вердикт «не OU»
 * по базису дефект сетки, ведь ряд с полупериодом 1.7 минуты тестировался с шагом
 * 28 минут. Ответ обязан быть получен на данных с заведомо известной κ.
 */
class BasisVerifyTest {

    /** Полупериод реального базиса, минуты (прогон 69). */
    private static final double HALF_LIFE = 1.7;

    /** Соответствующая ему κ: {@code ln2 / полупериод}. */
    private static final double KAPPA = Math.log(2) / HALF_LIFE;

    /** Фактический шаг прореживания прогона 69: ceil(554970 / 20000). */
    private static final int RUN69_STEP = 28;

    private static double[] steps(int n, double step) {
        double[] dt = new double[n - 1];
        Arrays.fill(dt, step);
        return dt;
    }

    private static double kappaOn(double[] x, double stepMinutes) {
        return OuCalibration.ols(steps(x.length, stepMinutes), x).kappa();
    }

    private static OuSeries series(String id, double[] values, double stepMinutes) {
        double[] times = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i * stepMinutes;
        }
        double mean = OuCalibration.mean(values);
        double sd = Math.sqrt(variance(values));
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i] - mean) > 2 * sd;
        }
        return new OuSeries(id, "синтетика", times, values, inEpisode, "|x − среднее| > 2σ",
                "доля", 0, "тест", false);
    }

    private static double variance(double[] v) {
        double m = OuCalibration.mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return v.length > 1 ? s / (v.length - 1) : 0;
    }

    private static OuAdmission.Result admit(OuSeries s) {
        double[] dt = s.steps();
        OuCalibration.Fit fit = OuCalibration.ols(dt, s.values());
        double halfLife95 = OuCalibration.Bootstrap.quantile(
                OuCalibration.bootstrap(fit, dt, 200, 42).halfLives(), 0.95);
        return OuAdmission.evaluate(s, fit, halfLife95, 250, 0.05, 0, 0.5, 30, 1.0, 30);
    }

    private static boolean passed(OuAdmission.Result r, String prefix) {
        return r.checks().stream().filter(c -> c.id().startsWith(prefix)).findFirst().orElseThrow()
                .passed();
    }

    /**
     * Опорный тест: на минутной сетке κ восстанавливается. Всё остальное в этом
     * классе имеет смысл только потому, что этот тест проходит.
     */
    @Test
    @DisplayName("полный минутный ряд: κ с полупериодом 1.7 мин восстанавливается")
    void fullGridRecoversKappa() {
        double[] x = OuCalibration.simulate(KAPPA, 0, 0.001, steps(60000, 1), new Random(11));
        double kappa = kappaOn(x, 1);
        assertEquals(KAPPA, kappa, 0.03 * KAPPA, "на шаге 1 мин κ восстановлена");
        assertEquals(HALF_LIFE, Math.log(2) / kappa, 0.1, "полупериод восстановлен");
    }

    /**
     * Главное измерение §7.1. Ожидание ТЗ («прореженная оценка смещена») на чистом
     * OU выполняется лишь отчасти, и это содержательный ответ: прореживание само по
     * себе κ почти не смещает, пока шаг сопоставим с полупериодом, — оценка ломается,
     * когда шаг уходит далеко за полупериод и автокорреляция становится неотличима
     * от нуля. Тест измеряет обе величины.
     */
    @Test
    @DisplayName("прореживание: на 5 минутах κ ещё восстановима, на шаге прогона 69 — уже нет")
    void thinningBiasIsMeasured() {
        double[] x = OuCalibration.simulate(KAPPA, 0, 0.001, steps(560000, 1), new Random(12));

        double full = kappaOn(x, 1);
        double five = kappaOn(BasisVerify.thinBy(x, 5), 5);
        double asRun = kappaOn(BasisVerify.thinBy(x, RUN69_STEP), RUN69_STEP);

        double biasFive = (five - KAPPA) / KAPPA;
        double biasAsRun = (asRun - KAPPA) / KAPPA;
        System.out.printf("κ истинная %.4f; полный ряд %.4f (%.1f%%); шаг 5 мин %.4f (%+.1f%%); "
                + "шаг %d мин %.4f (%+.1f%%)%n", KAPPA, full, 100 * (full - KAPPA) / KAPPA,
                five, 100 * biasFive, RUN69_STEP, asRun, 100 * biasAsRun);

        assertTrue(Math.abs(biasFive) < 0.10,
                "шаг 5 мин ≈ 3 полупериода: автокорреляция ещё видна, смещение " + biasFive);
        // На шаге 28 минут ρ = exp(−κ·28) ≈ 1e-5 — статистически неотличимо от нуля,
        // и оценка держится на шуме: κ занижается почти вдвое, а полупериод ровно
        // на столько же завышается. Направление то же, что и на реальном ряде
        // (1.7 мин на полной сетке против 26 мин на сетке прогона 69).
        assertTrue(biasAsRun < -0.3, "на шаге прогона 69 κ занижена кратно: " + biasAsRun);
        assertTrue(Math.log(2) / asRun > 1.5 * HALF_LIFE,
                "полупериод на грубой сетке завышен: " + Math.log(2) / asRun);
    }

    /**
     * Тот же ряд, тот же известный ответ — но сравниваются вердикты, а не числа.
     * Если сетка искажает вердикт на синтетике, где ответ «это OU» известен, то
     * подозрение ТЗ по реальному ряду обосновано.
     */
    @Test
    @DisplayName("вердикты T1/T3 на полном и прореженном ряде сравнены на известном ответе")
    void verdictsComparedOnKnownAnswer() {
        double[] x = OuCalibration.simulate(KAPPA, 0, 0.001, steps(40000, 1), new Random(13));
        OuAdmission.Result full = admit(series("SYNTH_FULL", x, 1));
        OuAdmission.Result thinned = admit(series("SYNTH_THIN", BasisVerify.thinBy(x, RUN69_STEP),
                RUN69_STEP));

        assertTrue(passed(full, "T1"), "на полной сетке заведомый OU проходит T1");
        assertTrue(passed(full, "T3"), "на полной сетке κ стабильна");
        System.out.printf("T1: полный %s, прорежённый %s; T3: полный %s, прорежённый %s%n",
                passed(full, "T1"), passed(thinned, "T1"), passed(full, "T3"), passed(thinned, "T3"));
        // Вердикт по стационарности прореживание переживает — оно бьёт по оценке
        // скорости, а не по факту возврата. Именно поэтому в реальном прогоне
        // расхождения по T1 не нашлось.
        assertTrue(passed(thinned, "T1"), "заведомый OU остаётся стационарным и на грубой сетке");
    }

    /**
     * Проверка, что второе определение ряда (§3.2) вообще работает: уровень дрейфует,
     * отклонения — нет.
     */
    @Test
    @DisplayName("уровень дрейфует, отклонения стационарны: LEVEL проваливает T1, DEVIATION проходит")
    void deviationSeparatesDriftFromReversion() {
        int n = 40000;
        double[] ou = OuCalibration.simulate(KAPPA, 0, 0.001, steps(n, 1), new Random(14));
        double[] level = new double[n];
        double drift = 0;
        Random rnd = new Random(15);
        for (int i = 0; i < n; i++) {
            // медленный случайный дрейф базовой линии: единичный корень с крошечным шагом
            drift += rnd.nextGaussian() * 0.0004;
            level[i] = ou[i] + drift;
        }
        double[] dev = BasisVerify.deviation(level, 480);

        OuAdmission.Result levelResult = admit(series("SYNTH_LEVEL", level, 1));
        OuAdmission.Result devResult = admit(series("SYNTH_DEV", dev, 1));

        assertFalse(passed(levelResult, "T1"), "дрейфующий уровень не обязан быть стационарным");
        assertTrue(passed(devResult, "T1"), "отклонения от скользящей средней стационарны");
    }

    /** Прореживание — это именно выборка каждого k-го наблюдения, без сглаживания. */
    @Test
    @DisplayName("прореживание берёт каждое k-е наблюдение и не трогает значения")
    void thinningTakesEveryKth() {
        double[] x = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertArrayEquals(new double[]{0, 3, 6}, BasisVerify.thinBy(x, 3));
        assertArrayEquals(x, BasisVerify.thinBy(x, 1));
    }

    /** Отклонение — ряд минус собственная скользящая средняя; на константе это ноль. */
    @Test
    @DisplayName("отклонение от скользящей средней: на константе ноль, на скачке — реакция")
    void deviationOfConstantIsZero() {
        double[] flat = new double[100];
        Arrays.fill(flat, 0.7);
        for (double v : BasisVerify.deviation(flat, 10)) {
            assertEquals(0, v, 1e-12, "постоянный ряд не отклоняется от своей средней");
        }
        double[] jump = new double[100];
        for (int i = 0; i < 100; i++) {
            jump[i] = i < 50 ? 0 : 1;
        }
        double[] dev = BasisVerify.deviation(jump, 10);
        assertTrue(dev[50] > 0.5, "сразу после скачка отклонение максимально: " + dev[50]);
        assertEquals(0, dev[99], 1e-12, "через окно после скачка средняя догнала уровень");
    }

    private static void assertArrayEquals(double[] expected, double[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, 1e-12);
    }

    /** Воспроизводимость по seed — ТЗ 72 §7.4. */
    @Test
    @DisplayName("тот же seed — тот же ряд и та же оценка")
    void reproducible() {
        double[] a = OuCalibration.simulate(KAPPA, 0, 0.001, steps(5000, 1), new Random(7));
        double[] b = OuCalibration.simulate(KAPPA, 0, 0.001, steps(5000, 1), new Random(7));
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, 0.0);
        assertEquals(kappaOn(a, 1), kappaOn(b, 1), 0.0);
    }
}
