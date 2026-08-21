package org.home.data.theory.alloc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Корректность алгоритмов на синтетике с известным ответом — ТЗ 65 §7.1.
 * Главный тест корректности — проверка границы регрета: реализация Hedge,
 * нарушающая собственную теоретическую границу, содержит баг.
 */
class AllocatorsTest {

    private static final double SWITCH_COST = 0;   // издержки проверяются отдельно (§7.2)

    private static double[] hedgeWeightsAfter(CurveSet set, double eta, int day) {
        History h = new History(set.retMatrix(), set.availMatrix(), set.cash(),
                set.regime(), set.cyclePhase(), set.ids());
        h.advanceTo(day);
        return new Allocators.Hedge("HEDGE", eta, 0).weights(h);
    }

    @Test
    @DisplayName("одна стратегия доминирует: HEDGE сходится к ней, регрет в границе")
    void dominantStrategy() {
        int t = 500;
        double[] winner = SyntheticCurves.constant(t, 0.002);
        double[] flat1 = new double[t];
        double[] flat2 = new double[t];
        CurveSet set = SyntheticCurves.of(winner, flat1, flat2);
        double eta = Allocators.etaStar(3, t, Allocators.observedScale(set.retMatrix(), 3));

        double[] w = hedgeWeightsAfter(set, eta, t - 1);
        assertTrue(w[0] > w[1] && w[0] > w[2], "вес доминирующей стратегии должен быть наибольшим");

        AllocEngine engine = new AllocEngine(set, SWITCH_COST);
        AllocEngine.Result hedge = engine.run(new Allocators.Hedge("HEDGE", eta, 0));
        double[] single = new double[3];
        single[engine.bestSingleIndex()] = 1;
        AllocEngine.Result best = engine.run(new Allocators.Fixed("BEST_SINGLE", single, true));

        double regret = Metrics.realizedRegret(hedge.ret(), best.ret());
        double bound = Metrics.hedgeRegretBound(set.retMatrix(), 3, t);
        assertTrue(regret > 0, "против лучшей одиночной регрет положителен");
        assertTrue(regret <= bound, "регрет " + regret + " превысил границу " + bound);
    }

    @Test
    @DisplayName("независимый шум с нулевым средним: HEDGE ≈ EW, веса не расходятся")
    void noiseKeepsWeightsFlat() {
        int t = 1000;
        int n = 4;
        Random rnd = new Random(7);
        double[][] rets = new double[n][t];
        for (int i = 0; i < n; i++) {
            for (int day = 0; day < t; day++) {
                rets[i][day] = rnd.nextGaussian() * 0.01;
            }
        }
        CurveSet set = SyntheticCurves.of(rets[0], rets[1], rets[2], rets[3]);
        double eta = Allocators.etaStar(n, t, Allocators.observedScale(set.retMatrix(), n));
        double[] w = hedgeWeightsAfter(set, eta, t - 1);
        // «не расходятся» = не сходятся в угол симплекса: на случайном блуждании накопленных
        // P&L разброс весов неизбежен (√T·σ), но ни один вес не должен доминировать
        for (double wi : w) {
            assertTrue(wi > 0.5 / n && wi < 2.0 / n,
                    "на чистом шуме вес ушёл в " + wi + " при равном 1/N = " + (1.0 / n));
        }

        AllocEngine engine = new AllocEngine(set, SWITCH_COST);
        double hedgeWealth = engine.run(new Allocators.Hedge("HEDGE", eta, 0)).finalEquity();
        double ewWealth = engine.run(
                new Allocators.EqualWeight("EW", Allocators.Normalization.ALL)).finalEquity();
        assertEquals(ewWealth, hedgeWealth, 0.05 * ewWealth, "HEDGE на шуме должен быть близок к EW");
    }

    @Test
    @DisplayName("две стратегии чередуются блоками: BEST_FIXED бьёт обе одиночные, регрет в границе")
    void alternatingBlocks() {
        int t = 800;
        double[] a = new double[t];
        double[] b = new double[t];
        for (int day = 0; day < t; day++) {
            boolean first = (day / 50) % 2 == 0;
            a[day] = first ? 0.004 : -0.003;
            b[day] = first ? -0.003 : 0.004;
        }
        CurveSet set = SyntheticCurves.of(a, b);
        AllocEngine engine = new AllocEngine(set, SWITCH_COST);
        double eta = Allocators.etaStar(2, t, Allocators.observedScale(set.retMatrix(), 2));

        AllocEngine.Result hedge = engine.run(new Allocators.Hedge("HEDGE", eta, 0));
        double[] mix = engine.bestFixedMix(4000, 1.0);
        AllocEngine.Result fixed = engine.run(new Allocators.Fixed("BEST_FIXED", mix, true));
        AllocEngine.Result onlyA = engine.run(new Allocators.Fixed("A", new double[]{1, 0}, true));
        AllocEngine.Result onlyB = engine.run(new Allocators.Fixed("B", new double[]{0, 1}, true));

        assertTrue(fixed.finalEquity() > onlyA.finalEquity() && fixed.finalEquity() > onlyB.finalEquity(),
                "лучший постоянный микс обязан бить обе одиночные при чередовании");
        assertTrue(hedge.finalEquity() < fixed.finalEquity(),
                "Hedge не может обойти недостижимый ориентир");
        double regret = Metrics.realizedRegret(hedge.ret(),
                onlyA.finalEquity() > onlyB.finalEquity() ? onlyA.ret() : onlyB.ret());
        double bound = Metrics.hedgeRegretBound(set.retMatrix(), 2, t);
        assertTrue(regret <= bound, "регрет " + regret + " превысил границу " + bound);
    }

    @Test
    @DisplayName("все стратегии убыточны одинаково: HEDGE убыточен так же, граница выполняется")
    void allLosingStaysLosing() {
        int t = 400;
        double[] a = SyntheticCurves.constant(t, -0.001);
        double[] b = SyntheticCurves.constant(t, -0.001);
        double[] c = SyntheticCurves.constant(t, -0.001);
        CurveSet set = SyntheticCurves.of(a, b, c);
        AllocEngine engine = new AllocEngine(set, SWITCH_COST);
        AllocEngine.Result hedge = engine.run(
                new Allocators.Hedge("HEDGE", Allocators.etaStar(3, t, Allocators.observedScale(set.retMatrix(), 3)), 0));

        assertTrue(hedge.finalEquity() < 1, "убыточный пул обязан дать убыточный портфель");
        assertEquals(Math.pow(1 - 0.001, t), hedge.finalEquity(), 1e-9,
                "реализация не должна молча «чинить» отрицательный результат");
        double regret = Metrics.realizedRegret(hedge.ret(), a);
        assertTrue(regret <= Metrics.hedgeRegretBound(set.retMatrix(), 3, t),
                "граница выполняется и на убыточном пуле — она относительна");
    }

    @Test
    @DisplayName("стратегия недоступна 100% времени: портфель эквивалентен пулу без неё")
    void permanentlyUnavailableStrategyDoesNotMatter() {
        int t = 300;
        Random rnd = new Random(11);
        double[] a = new double[t];
        double[] b = new double[t];
        double[] ghost = new double[t];
        for (int day = 0; day < t; day++) {
            a[day] = rnd.nextGaussian() * 0.01;
            b[day] = rnd.nextGaussian() * 0.01;
            ghost[day] = 0.05;                        // «доходность», которой не случится
        }
        boolean[][] avail = {SyntheticCurves.all(t, true), SyntheticCurves.all(t, true),
                SyntheticCurves.all(t, false)};
        CurveSet withGhost = SyntheticCurves.of(new double[][]{a, b, ghost}, avail, new double[t]);
        CurveSet without = SyntheticCurves.of(new double[][]{a, b},
                new boolean[][]{SyntheticCurves.all(t, true), SyntheticCurves.all(t, true)}, new double[t]);

        double[] withGhostRet = new AllocEngine(withGhost, SWITCH_COST)
                .run(new Allocators.EqualWeight("EW", Allocators.Normalization.AVAILABLE_ONLY)).ret();
        double[] withoutRet = new AllocEngine(without, SWITCH_COST)
                .run(new Allocators.EqualWeight("EW", Allocators.Normalization.AVAILABLE_ONLY)).ret();
        // день 0 — прогрев: истории нет, доступность неизвестна, веса равные по всему пулу
        for (int day = 1; day < t; day++) {
            assertEquals(withoutRet[day], withGhostRet[day], 1e-12,
                    "вес вечно недоступной стратегии не должен влиять на результат, день " + day);
        }
    }

    @Test
    @DisplayName("проекция на симплекс: сумма 1, неотрицательность")
    void simplexProjection() {
        double[] projected = Allocators.projectToSimplex(new double[]{0.7, -0.4, 0.9, 0.1});
        double sum = 0;
        for (double p : projected) {
            assertTrue(p >= 0, "проекция не даёт отрицательных весов");
            sum += p;
        }
        assertEquals(1.0, sum, 1e-12);
    }
}
