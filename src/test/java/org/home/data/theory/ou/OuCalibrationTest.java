package org.home.data.theory.ou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Восстановление параметров и демонстрация смещения — ТЗ 67 §7.1, §7.3.
 * Реализация, не воспроизводящая известный ответ на сгенерированных данных, не
 * принимается независимо от происхождения (§3).
 */
class OuCalibrationTest {

    private static double[] regularSteps(int n) {
        double[] dt = new double[n - 1];
        Arrays.fill(dt, 1.0);
        return dt;
    }

    @Test
    @DisplayName("большое N: оценки сходятся к истинным κ, θ, σ")
    void recoversParameters() {
        double kappa = 0.3;
        double theta = 0.05;
        double sigma = 0.2;
        double[] dt = regularSteps(20000);
        double[] x = OuCalibration.simulate(kappa, theta, sigma, dt, new Random(1));

        OuCalibration.Fit ols = OuCalibration.ols(dt, x);
        assertEquals(kappa, ols.kappa(), 0.05 * kappa, "κ восстановлена");
        assertEquals(theta, ols.theta(), 0.02, "θ восстановлена");
        assertEquals(sigma, ols.sigma(), 0.05 * sigma, "σ восстановлена");

        OuCalibration.Fit mle = OuCalibration.mle(dt, x);
        assertEquals(kappa, mle.kappa(), 0.05 * kappa, "MLE даёт ту же κ");
    }

    @Test
    @DisplayName("на регулярной сетке МНК совпадает с классической регрессией AR(1)")
    void matchesClassicAr1OnRegularGrid() {
        double[] dt = regularSteps(3000);
        double[] x = OuCalibration.simulate(0.2, 0.0, 0.1, dt, new Random(2));
        // классический AR(1): x_{t+1} = a + b·x_t
        int n = x.length - 1;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i] / n;
            my += x[i + 1] / n;
        }
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (x[i + 1] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
        }
        double phi = sxy / sxx;
        double kappaClassic = -Math.log(phi);
        assertEquals(kappaClassic, OuCalibration.ols(dt, x).kappa(), 1e-6,
                "обобщённая оценка обязана совпасть с AR(1) на регулярном шаге");
    }

    @Test
    @DisplayName("N = 500: сырая κ систематически выше истинной, бутстрап-поправка её убирает")
    void biasIsUpwardAndCorrectionRemovesIt() {
        double kappa = 0.05;                       // полупериод ~14 шагов
        double[] dt = regularSteps(500);
        Random rnd = new Random(3);
        int reps = 200;
        double rawSum = 0;
        double correctedSum = 0;
        for (int r = 0; r < reps; r++) {
            double[] x = OuCalibration.simulate(kappa, 0, 0.1, dt, rnd);
            OuCalibration.Fit fit = OuCalibration.ols(dt, x);
            rawSum += fit.kappa();
            correctedSum += OuCalibration.bootstrap(fit, dt, 60, 100 + r).correctedKappa();
        }
        double rawMean = rawSum / reps;
        double correctedMean = correctedSum / reps;
        assertTrue(rawMean > kappa * 1.05,
                "сырая κ обязана быть систематически выше истинной: " + rawMean + " против " + kappa);
        assertTrue(Math.abs(correctedMean - kappa) < Math.abs(rawMean - kappa),
                "бутстрап-поправка обязана уменьшать смещение: " + correctedMean + " против " + rawMean);
    }

    @Test
    @DisplayName("случайное блуждание: κ̂ близка к нулю, ADF не отвергает единичный корень")
    void randomWalkLooksLikeUnitRoot() {
        double[] x = OuCalibration.randomWalk(2000, 0.01, new Random(4));
        double[] dt = regularSteps(x.length);
        OuCalibration.Fit fit = OuCalibration.ols(dt, x);
        assertTrue(fit.kappa() < 0.01, "κ случайного блуждания статистически неотличима от нуля: " + fit.kappa());
        StatTests.TestResult adf = StatTests.adf(x, StatTests.autoLags(x.length), 0.05);
        assertTrue(!adf.rejected(), "ADF не должен отвергать единичный корень на блуждании");
    }

    @Test
    @DisplayName("пропуски в ряду не сдвигают оценку κ (шаг берётся по факту)")
    void gapsDoNotShiftKappa() {
        double kappa = 0.2;
        double[] dt = regularSteps(6000);
        double[] x = OuCalibration.simulate(kappa, 0, 0.1, dt, new Random(5));
        double full = OuCalibration.ols(dt, x).kappa();

        // выкидываем каждое третье наблюдение: шаги становятся нерегулярными
        int keep = 0;
        double[] sparseX = new double[x.length];
        double[] times = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            if (i % 3 != 1) {
                sparseX[keep] = x[i];
                times[keep] = i;
                keep++;
            }
        }
        double[] sparse = Arrays.copyOf(sparseX, keep);
        double[] sparseDt = new double[keep - 1];
        for (int i = 0; i < keep - 1; i++) {
            sparseDt[i] = times[i + 1] - times[i];
        }
        double gapped = OuCalibration.ols(sparseDt, sparse).kappa();
        assertEquals(full, gapped, 0.15 * full, "пропуски не должны сдвигать κ: " + gapped + " против " + full);
    }

    @Test
    @DisplayName("нерегулярный шаг не считается регулярным: игнорирование Δt даёт другую оценку")
    void irregularStepIsNotTreatedAsRegular() {
        double kappa = 0.2;
        int n = 4000;
        Random rnd = new Random(6);
        double[] dt = new double[n - 1];
        for (int i = 0; i < dt.length; i++) {
            dt[i] = i % 2 == 0 ? 0.5 : 2.5;        // средний шаг 1.5, разброс большой
        }
        double[] x = OuCalibration.simulate(kappa, 0, 0.1, dt, rnd);
        double correct = OuCalibration.ols(dt, x).kappa();
        double[] pretendRegular = new double[dt.length];
        Arrays.fill(pretendRegular, 1.0);
        double wrong = OuCalibration.ols(pretendRegular, x).kappa();
        assertEquals(kappa, correct, 0.15 * kappa, "с фактическими шагами κ восстанавливается");
        assertTrue(Math.abs(wrong - kappa) > Math.abs(correct - kappa),
                "игнорирование Δt обязано портить оценку: " + wrong + " против " + correct);
    }

    @Test
    @DisplayName("воспроизводимость: тот же seed — побитово тот же результат")
    void reproducible() {
        double[] dt = regularSteps(500);
        double[] first = OuCalibration.simulate(0.2, 0, 0.1, dt, new Random(7));
        double[] second = OuCalibration.simulate(0.2, 0, 0.1, dt, new Random(7));
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second, 0.0);
        OuCalibration.Fit fit = OuCalibration.ols(dt, first);
        assertEquals(OuCalibration.bootstrap(fit, dt, 50, 9).correctedKappa(),
                OuCalibration.bootstrap(fit, dt, 50, 9).correctedKappa(), 0.0);
    }
}
