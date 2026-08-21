package org.home.data.theory.kelly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетика с известным ответом — ТЗ 66 §7.1. Главные сценарии — второй и
 * третий: модуль обязан показывать смещение оценки при малом N и отрицательную
 * нижнюю границу на заведомо нулевом эффекте.
 */
class KellySizingTest {

    private static double[] normalOutcomes(Random rnd, int n, double mu, double sigma) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = mu + sigma * rnd.nextGaussian();
        }
        return x;
    }

    @Test
    @DisplayName("большое N: численный f* сходится к μ/σ²")
    void convergesToContinuousFormula() {
        Random rnd = new Random(1);
        double mu = 0.02;
        double sigma = 0.15;
        double[] x = normalOutcomes(rnd, 200_000, mu, sigma);
        double numeric = KellySizing.kellyNumeric(x);
        double continuous = mu / (sigma * sigma);
        assertEquals(continuous, numeric, 0.15 * continuous,
                "при большом N численный Kelly обязан сойтись к μ/σ²");
    }

    @Test
    @DisplayName("N = 40: бутстрап-распределение f* широкое, медиана выше истинного f* (смещение)")
    void smallSampleBiasIsVisible() {
        double mu = 0.02;
        double sigma = 0.15;
        double trueF = mu / (sigma * sigma);
        Random rnd = new Random(2);
        List<Double> medians = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        for (int rep = 0; rep < 40; rep++) {
            double[] sample = normalOutcomes(rnd, 40, mu, sigma);
            double[] boot = KellySizing.bootstrapKelly(sample, 400, 7 + rep);
            medians.add(KellySizing.quantile(boot, 0.5));
            widths.add(KellySizing.quantile(boot, 0.95) - KellySizing.quantile(boot, 0.05));
        }
        double meanMedian = medians.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double meanWidth = widths.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertTrue(meanWidth > trueF, "при N=40 разброс f* обязан быть сопоставим с самим f*: " + meanWidth);
        assertTrue(meanMedian > trueF * 0.9,
                "медиана бутстрапа при малом N не должна систематически занижать f*: "
                        + meanMedian + " против " + trueF);
    }

    @Test
    @DisplayName("нулевой эффект, N = 40: 5-й процентиль f* отрицателен, точечный при этом часто положителен")
    void zeroEffectShowsNegativeLowerBound() {
        Random rnd = new Random(3);
        int positivePoint = 0;
        int negativeLower = 0;
        int reps = 50;
        for (int rep = 0; rep < reps; rep++) {
            double[] sample = normalOutcomes(rnd, 40, 0.0, 0.15);
            double point = KellySizing.kellyNumeric(sample);
            double[] boot = KellySizing.bootstrapKelly(sample, 400, 11 + rep);
            if (point > 0) {
                positivePoint++;
            }
            if (KellySizing.quantile(boot, 0.05) < 0) {
                negativeLower++;
            }
        }
        assertTrue(positivePoint > reps / 4,
                "на нулевом эффекте точечный Kelly обязан быть положительным в части реплик: " + positivePoint);
        assertTrue(negativeLower > reps * 0.8,
                "на нулевом эффекте нижняя граница обязана уходить ниже нуля почти всегда: " + negativeLower);
    }

    @Test
    @DisplayName("две полностью коррелированные ставки: суммарный оптимум равен одиночному")
    void perfectlyCorrelatedBetsDoNotAddCapacity() {
        Random rnd = new Random(4);
        double[] x = normalOutcomes(rnd, 5000, 0.02, 0.15);
        List<double[]> groups = new ArrayList<>();
        for (double v : x) {
            groups.add(new double[]{v, v});                 // корреляция 1
        }
        double single = KellySizing.kellyNumeric(x);
        double joint = KellySizing.jointOptimalTotal(groups, 2);
        assertEquals(single, joint, 0.02 * Math.abs(single),
                "при корреляции 1 суммарный размер обязан равняться одиночному, а не двойному");
    }

    @Test
    @DisplayName("две независимые ставки: суммарный оптимум — двойной одиночного (индивидуальный не меняется)")
    void independentBetsDoubleTheTotal() {
        Random rnd = new Random(5);
        int n = 20000;
        double[] a = normalOutcomes(rnd, n, 0.02, 0.15);
        double[] b = normalOutcomes(rnd, n, 0.02, 0.15);
        List<double[]> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            groups.add(new double[]{a[i], b[i]});
        }
        double single = KellySizing.kellyNumeric(a);
        double joint = KellySizing.jointOptimalTotal(groups, 2);
        assertEquals(2 * single, joint, 0.10 * single,
                "при независимости суммарный оптимум равен двойному одиночному: "
                        + "дисперсия среднего вдвое ниже, индивидуальный размер остаётся тем же");
    }

    @Test
    @DisplayName("положительно коррелированные ставки: суммарный оптимум между одиночным и двойным")
    void correlatedBetsAddLessCapacity() {
        Random rnd = new Random(15);
        int n = 20000;
        double rho = 0.5;
        double[] a = new double[n];
        List<double[]> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double common = rnd.nextGaussian();
            double x = 0.02 + 0.15 * (Math.sqrt(rho) * common + Math.sqrt(1 - rho) * rnd.nextGaussian());
            double y = 0.02 + 0.15 * (Math.sqrt(rho) * common + Math.sqrt(1 - rho) * rnd.nextGaussian());
            a[i] = x;
            groups.add(new double[]{x, y});
        }
        double single = KellySizing.kellyNumeric(a);
        double joint = KellySizing.jointOptimalTotal(groups, 2);
        assertTrue(joint > single * 1.05, "корреляция 0.5 всё же добавляет ёмкости: " + joint);
        assertTrue(joint < 2 * single * 0.98,
                "но меньше, чем при независимости: " + joint + " против " + (2 * single));
    }

    @Test
    @DisplayName("ставка при f > 2f*: симулированный темп роста отрицателен")
    void oversizedBetHasNegativeGrowth() {
        Random rnd = new Random(6);
        double[] x = normalOutcomes(rnd, 50000, 0.02, 0.15);
        double f = KellySizing.kellyNumeric(x);
        assertTrue(KellySizing.growthRate(x, f) > 0, "в оптимуме темп роста положителен");
        assertTrue(KellySizing.growthRate(x, 2.2 * f) < 0,
                "за точкой 2f* темп роста обязан стать отрицательным");
    }

    @Test
    @DisplayName("усечение стопом воспроизводится: искусственный стоп снижает σ")
    void stopTruncationReducesSigma() {
        Random rnd = new Random(7);
        double[] raw = normalOutcomes(rnd, 5000, 0.02, 0.20);
        double[] stopped = new double[raw.length];
        double stop = 0.30;
        for (int i = 0; i < raw.length; i++) {
            stopped[i] = Math.max(raw[i], -stop);
        }
        assertTrue(Math.sqrt(KellySizing.variance(stopped)) < Math.sqrt(KellySizing.variance(raw)),
                "усечение убытка обязано снижать наблюдаемую σ");
    }

    @Test
    @DisplayName("усадка и поправка на отбор уменьшают размер, но не меняют знак на положительной выборке")
    void shrinkageReducesSize() {
        Random rnd = new Random(8);
        double[] x = normalOutcomes(rnd, 300, 0.02, 0.15);
        double point = KellySizing.kellyNumeric(x);
        double shrunk = KellySizing.shrunkKelly(x, 1);
        double selection = KellySizing.selectionAdjustedKelly(x, 12);
        assertTrue(shrunk < point, "усадка обязана уменьшать размер");
        assertTrue(selection < point, "поправка на отбор обязана уменьшать размер");
        assertTrue(shrunk > 0, "на выборке с положительным средним усадка s=1 не должна менять знак");
    }

    @Test
    @DisplayName("бутстрап с фиксированным seed воспроизводим побитово")
    void bootstrapReproducible() {
        Random rnd = new Random(9);
        double[] x = normalOutcomes(rnd, 100, 0.01, 0.12);
        double[] first = KellySizing.bootstrapKelly(x, 500, 42);
        double[] second = KellySizing.bootstrapKelly(x, 500, 42);
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second, 0.0);
    }

    @Test
    @DisplayName("кластеризация: максимум и среднее одновременных считаются по пересечению окон")
    void concurrencyCounted() {
        long[] days = {100, 102, 103, 200};
        double[] c = KellySizing.concurrency(days, 5);
        assertEquals(3, c[0], 1e-9, "три события в пределах окна пересекаются");
        assertEquals((3 + 3 + 3 + 1) / 4.0, c[1], 1e-9);
    }

    @Test
    @DisplayName("потолок из лимита просадки: k позиций по стопу не дороже лимита")
    void drawdownCapMatchesLimit() {
        double cap = KellySizing.drawdownCap(0.25, 5, 0.31);
        assertEquals(0.25 / (5 * 0.31), cap, 1e-12);
        assertTrue(cap * 5 * 0.31 <= 0.25 + 1e-12, "суммарный стоп по k позициям не превышает лимит");
    }
}
