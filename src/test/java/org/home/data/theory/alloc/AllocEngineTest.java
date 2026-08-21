package org.home.data.theory.alloc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Корректность данных и движка — ТЗ 65 §7.2, воспроизводимость — §7.3.
 */
class AllocEngineTest {

    @Test
    @DisplayName("маска доступности: капитал недоступной стратегии идёт в кэш по ставке, не теряется и не удваивается")
    void unavailableCapitalGoesToCash() {
        int t = 10;
        double[] a = SyntheticCurves.constant(t, 0.10);
        double[] b = SyntheticCurves.constant(t, 0.10);
        boolean[][] avail = {SyntheticCurves.all(t, true), SyntheticCurves.all(t, false)};
        double[] cash = SyntheticCurves.constant(t, 0.01);
        CurveSet set = SyntheticCurves.of(new double[][]{a, b}, avail, cash);

        AllocEngine.Result r = new AllocEngine(set, 0)
                .run(new Allocators.EqualWeight("EW", Allocators.Normalization.ALL));
        // 50% в доступной (10%) + 50% в кэше (1%) = 5.5% в день
        for (double daily : r.ret()) {
            assertEquals(0.055, daily, 1e-12);
        }
        assertEquals(Math.pow(1.055, t), r.finalEquity(), 1e-9);
    }

    @Test
    @DisplayName("незанятый резерв (Σw < 1) тоже лежит в кэше и считается один раз")
    void reserveGoesToCash() {
        int t = 5;
        double[] a = SyntheticCurves.constant(t, 0.10);
        double[] cash = SyntheticCurves.constant(t, 0.02);
        CurveSet set = SyntheticCurves.of(new double[][]{a},
                new boolean[][]{SyntheticCurves.all(t, true)}, cash);
        AllocEngine.Result r = new AllocEngine(set, 0)
                .run(new Allocators.Fixed("HALF", new double[]{0.5}, false));
        for (double daily : r.ret()) {
            assertEquals(0.5 * 0.10 + 0.5 * 0.02, daily, 1e-12);
        }
    }

    @Test
    @DisplayName("нормировка: Σw = 1 на каждом шаге с точностью до эпсилон")
    void weightsSumToOne() {
        int t = 200;
        Random rnd = new Random(3);
        double[][] rets = new double[3][t];
        for (double[] row : rets) {
            for (int i = 0; i < t; i++) {
                row[i] = rnd.nextGaussian() * 0.01;
            }
        }
        CurveSet set = SyntheticCurves.of(rets[0], rets[1], rets[2]);
        for (Allocator a : java.util.List.of(
                new Allocators.EqualWeight("EW", Allocators.Normalization.ALL),
                new Allocators.Hedge("HEDGE", Allocators.etaStar(3, t, Allocators.observedScale(set.retMatrix(), 3)), 0),
                new Allocators.ExponentiatedGradient(0.05),
                new Allocators.OnlineNewtonStep(1.0, 1.0),
                new Allocators.RandomWeights("RANDOM", 42, 1))) {
            AllocEngine.Result r = new AllocEngine(set, 0).run(a);
            for (double[] w : r.weights()) {
                assertEquals(1.0, Arrays.stream(w).sum(), 1e-9, a.id() + ": Σw ≠ 1");
            }
        }
    }

    @Test
    @DisplayName("отсутствие look-ahead: подмена всех данных после дня t не меняет веса на t+1")
    void noLookAhead() {
        int t = 300;
        Random rnd = new Random(5);
        double[][] rets = new double[3][t];
        for (double[] row : rets) {
            for (int i = 0; i < t; i++) {
                row[i] = rnd.nextGaussian() * 0.01;
            }
        }
        CurveSet full = SyntheticCurves.of(rets[0], rets[1], rets[2]);
        int cut = 150;
        double[][] truncated = new double[3][t];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(rets[i], 0, truncated[i], 0, t);
            Arrays.fill(truncated[i], cut + 1, t, Double.NaN);
        }
        CurveSet blinded = SyntheticCurves.of(truncated[0], truncated[1], truncated[2]);

        for (Allocator a : java.util.List.of(
                new Allocators.EqualWeight("EW", Allocators.Normalization.ALL),
                new Allocators.Hedge("HEDGE",
                        Allocators.etaStar(3, t, Allocators.observedScale(full.retMatrix(), 3)), 0),
                new Allocators.ExponentiatedGradient(0.05),
                new Allocators.OnlineNewtonStep(1.0, 1.0))) {
            double[] wFull = weightsForDay(full, a, cut);
            double[] wBlind = weightsForDay(blinded, a, cut);
            assertArrayEquals(wFull, wBlind, 1e-12, a.id() + ": веса зависят от будущего");
        }
    }

    /** Прогоняет аллокатор последовательно до дня {@code cut} и возвращает веса на {@code cut + 1}. */
    private static double[] weightsForDay(CurveSet set, Allocator allocator, int cut) {
        History h = new History(set.retMatrix(), set.availMatrix(), set.cash(),
                set.regime(), set.cyclePhase(), set.ids());
        allocator.reset(set.size());
        double[] w = null;
        for (int day = -1; day <= cut; day++) {
            h.advanceTo(day);
            w = allocator.weights(h);
        }
        return w;
    }

    @Test
    @DisplayName("look-ahead невозможен структурно: обращение к дню > today бросает исключение")
    void historyBlocksFuture() {
        CurveSet set = SyntheticCurves.of(SyntheticCurves.constant(10, 0.01));
        History h = new History(set.retMatrix(), set.availMatrix(), set.cash(),
                set.regime(), set.cyclePhase(), set.ids());
        h.advanceTo(3);
        assertEquals(0.01, h.ret(3, 0), 1e-12);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> h.ret(4, 0));
    }

    @Test
    @DisplayName("издержки переключения считаются один раз и только на изменение весов")
    void switchCostChargedOnce() {
        int t = 4;
        double[] a = new double[t];
        double[] b = new double[t];
        CurveSet set = SyntheticCurves.of(a, b);
        double cost = 0.002;

        // постоянные веса: издержка только в первый день (переход из нулевого портфеля)
        AllocEngine.Result constant = new AllocEngine(set, cost)
                .run(new Allocators.Fixed("CONST", new double[]{0.5, 0.5}, false));
        assertEquals(cost * 1.0 / 2, -constant.ret()[0], 1e-12);
        for (int day = 1; day < t; day++) {
            assertEquals(0.0, constant.ret()[day], 1e-12, "без движения весов издержек нет");
        }
        assertEquals(cost / 2, constant.switchCostSum(), 1e-12);
    }

    @Test
    @DisplayName("разложение вклада: сумма по стратегиям + кэш − издержки = общий P&L")
    void attributionMatchesPnl() {
        int t = 120;
        Random rnd = new Random(9);
        double[][] rets = new double[3][t];
        boolean[][] avail = new boolean[3][t];
        for (int i = 0; i < 3; i++) {
            for (int day = 0; day < t; day++) {
                rets[i][day] = rnd.nextGaussian() * 0.02;
                avail[i][day] = rnd.nextDouble() > 0.3;
            }
        }
        CurveSet set = SyntheticCurves.of(rets, avail, SyntheticCurves.constant(t, 0.0001));
        AllocEngine.Result r = new AllocEngine(set, 0.0015)
                .run(new Allocators.Hedge("HEDGE", Allocators.etaStar(3, t, Allocators.observedScale(set.retMatrix(), 3)), 0));

        double sum = Arrays.stream(r.contribution()).sum() + r.cashPnl() + r.costPnl();
        assertEquals(r.finalEquity() - 1.0, sum, 1e-9, "вклады обязаны сходиться к общему P&L");
    }

    @Test
    @DisplayName("воспроизводимость: тот же seed — побитово тот же результат")
    void reproducible() {
        int t = 150;
        Random rnd = new Random(13);
        double[][] rets = new double[3][t];
        for (double[] row : rets) {
            for (int i = 0; i < t; i++) {
                row[i] = rnd.nextGaussian() * 0.01;
            }
        }
        CurveSet set = SyntheticCurves.of(rets[0], rets[1], rets[2]);
        AllocEngine engine = new AllocEngine(set, 0.001);
        double[] first = engine.run(new Allocators.RandomWeights("RANDOM", 42, 1)).ret();
        double[] second = engine.run(new Allocators.RandomWeights("RANDOM", 42, 1)).ret();
        assertArrayEquals(first, second, 0.0, "RANDOM с тем же seed обязан повторяться побитово");
    }

    @Test
    @DisplayName("BEST_FIXED не хуже лучшей одиночной стратегии")
    void bestFixedDominatesBestSingle() {
        int t = 600;
        double[] a = new double[t];
        double[] b = new double[t];
        for (int day = 0; day < t; day++) {
            boolean first = (day / 30) % 2 == 0;
            a[day] = first ? 0.005 : -0.004;
            b[day] = first ? -0.004 : 0.005;
        }
        CurveSet set = SyntheticCurves.of(a, b);
        AllocEngine engine = new AllocEngine(set, 0);
        double[] mix = engine.bestFixedMix(4000, 1.0);
        double fixed = engine.run(new Allocators.Fixed("BEST_FIXED", mix, true)).finalEquity();
        double[] singleMix = new double[2];
        singleMix[engine.bestSingleIndex()] = 1;
        double single = engine.run(new Allocators.Fixed("BEST_SINGLE", singleMix, true)).finalEquity();
        assertTrue(fixed >= single - 1e-9, "лучший постоянный микс не может быть хуже лучшей одиночной");
    }
}
