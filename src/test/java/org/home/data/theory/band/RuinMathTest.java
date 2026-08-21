package org.home.data.theory.band;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разорение — ТЗ 68 §7.1 (точная сверка с классическим результатом), §7.3
 * (блочный бутстрап) и §7.4 (воспроизводимость).
 */
class RuinMathTest {

    @Test
    @DisplayName("симметричное блуждание с барьерами a и b: вероятность достичь a равна b/(a+b)")
    void gamblersRuinExact() {
        assertEquals(0.5, RuinMath.gamblersRuinSymmetric(0.3, 0.3), 1e-12);
        assertEquals(0.75, RuinMath.gamblersRuinSymmetric(1, 3), 1e-12);
        // диффузионный предел при нулевом дрейфе обязан совпасть с классической формулой
        assertEquals(RuinMath.gamblersRuinSymmetric(1, 3),
                RuinMath.diffusionRuin(0, 0.2, 1, 3), 1e-12);
    }

    @Test
    @DisplayName("положительный дрейф снижает вероятность разорения, отрицательный повышает")
    void driftMovesRuinProbability() {
        double neutral = RuinMath.diffusionRuin(0, 0.2, 1, 1);
        double positive = RuinMath.diffusionRuin(0.05, 0.2, 1, 1);
        double negative = RuinMath.diffusionRuin(-0.05, 0.2, 1, 1);
        assertTrue(positive < neutral, "дрейф вверх снижает вероятность дойти до нижнего барьера");
        assertTrue(negative > neutral, "дрейф вниз — повышает");
    }

    @Test
    @DisplayName("нулевая волатильность: разорение либо неизбежно, либо невозможно — по знаку дрейфа")
    void zeroVolatilityIsDeterministic() {
        assertEquals(1, RuinMath.diffusionRuin(-0.05, 0, 1, 1), 1e-12);
        assertEquals(0, RuinMath.diffusionRuin(0.05, 0, 1, 1), 1e-12);
    }

    @Test
    @DisplayName("МК на независимых приращениях воспроизводит аналитическую вероятность")
    void monteCarloMatchesAnalyticOnIid() {
        // симметричные шаги ±1%: вероятность просадки 20% на длинном горизонте близка к 1
        Random rnd = new Random(1);
        double[] steps = new double[20000];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = rnd.nextBoolean() ? 0.01 : -0.01;
        }
        RuinMath.RuinResult result = RuinMath.monteCarloDrawdown(steps, 200000, 0.20, 1, 2000, 7);
        assertTrue(result.pRuinMc() > 0.95,
                "на бесконечном горизонте просадка 20% практически неизбежна: " + result.pRuinMc());
        assertTrue(result.ciLow() <= result.pRuinMc() && result.ciHigh() >= result.pRuinMc());
    }

    @Test
    @DisplayName("блочный бутстрап на данных с кластеризацией волатильности даёт БОЛЬШЕ разорений")
    void blockBootstrapRaisesRuinOnClusteredData() {
        // ряд с искусственной кластеризацией: чередуются спокойные и бурные блоки
        Random rnd = new Random(2);
        double[] returns = new double[4000];
        for (int i = 0; i < returns.length; i++) {
            boolean stormy = (i / 100) % 2 == 0;
            returns[i] = rnd.nextGaussian() * (stormy ? 0.06 : 0.005);
        }
        // горизонт и порог подобраны так, чтобы вероятность не упиралась в 100%:
        // на насыщенной шкале сравнивать методы бессмысленно
        double plain = RuinMath.monteCarloDrawdown(returns, 60, 0.40, 1, 20000, 3).pRuinMc();
        double blocked = RuinMath.monteCarloDrawdown(returns, 60, 0.40, 30, 20000, 3).pRuinMc();
        assertTrue(plain < 0.9 && plain > 0.01, "контроль насыщения: обычный бутстрап даёт " + plain);
        assertTrue(blocked > plain,
                "блоки обязаны сохранять кластеризацию и повышать оценку риска: "
                        + blocked + " против " + plain);
    }

    @Test
    @DisplayName("лестница: доли сходятся к единице, полное прохождение растёт с волатильностью")
    void ladderDistributionIsProper() {
        Random rnd = new Random(3);
        double[] calm = new double[2000];
        double[] wild = new double[2000];
        for (int i = 0; i < calm.length; i++) {
            calm[i] = rnd.nextGaussian() * 0.005;
            wild[i] = rnd.nextGaussian() * 0.05;
        }
        double[] calmDistribution = RuinMath.ladderDistribution(calm, 0.08, 5, 180, 1, 5000, 4);
        double[] wildDistribution = RuinMath.ladderDistribution(wild, 0.08, 5, 180, 1, 5000, 4);
        double sum = 0;
        for (double p : calmDistribution) {
            sum += p;
        }
        assertEquals(1.0, sum, 1e-9, "распределение числа пройденных ступеней обязано быть полным");
        assertTrue(wildDistribution[5] > calmDistribution[5],
                "на волатильном рынке лестница проходится целиком чаще");
    }

    @Test
    @DisplayName("воспроизводимость: тот же seed — тот же результат")
    void reproducible() {
        Random rnd = new Random(5);
        double[] returns = new double[1000];
        for (int i = 0; i < returns.length; i++) {
            returns[i] = rnd.nextGaussian() * 0.02;
        }
        double first = RuinMath.monteCarloDrawdown(returns, 500, 0.3, 5, 2000, 11).pRuinMc();
        double second = RuinMath.monteCarloDrawdown(returns, 500, 0.3, 5, 2000, 11).pRuinMc();
        assertEquals(first, second, 0.0);
    }

    @Test
    @DisplayName("потолок инвентаря: чем больше перекос потока, тем чаще упираемся")
    void inventoryCeilingReactsToImbalance() {
        double[] small = RuinMath.inventoryCeiling(0.0, 40, 2000, 2000, 6);
        double[] large = RuinMath.inventoryCeiling(0.05, 40, 2000, 2000, 6);
        assertTrue(large[0] >= small[0], "перекос потока обязан повышать долю упирающихся траекторий");
        assertTrue(large[1] >= small[1], "и долю времени у потолка");
    }
}
