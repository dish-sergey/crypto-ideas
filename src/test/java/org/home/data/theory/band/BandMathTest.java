package org.home.data.theory.band;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Аналитика и численное решение полосы — ТЗ 68 §7.1, §7.2.
 */
class BandMathTest {

    @Test
    @DisplayName("π* при γ=1 равна μ/σ²; при удвоении γ уменьшается вдвое")
    void targetWeightScalesWithGamma() {
        double mu = 0.2;
        double sigma = 0.6;
        BandMath.TargetWeight one = BandMath.targetWeight(mu, sigma, 1, OptionalDouble.empty());
        BandMath.TargetWeight two = BandMath.targetWeight(mu, sigma, 2, OptionalDouble.empty());
        assertEquals(mu / (sigma * sigma), one.piStar(), 1e-12);
        assertEquals(one.piStar() / 2, two.piStar(), 1e-12);
        assertTrue(!one.shrinkageApplied(), "без SE усадка не применяется и это видно в результате");
    }

    @Test
    @DisplayName("без измеренной μ функция отказывается работать (§3.2)")
    void refusesWithoutMu() {
        assertThrows(IllegalArgumentException.class,
                () -> BandMath.targetWeight(Double.NaN, 0.6, 1, OptionalDouble.empty()));
    }

    @Test
    @DisplayName("переданная SE(μ) включает усадку и уменьшает целевую долю")
    void shrinkageReducesTarget() {
        BandMath.TargetWeight shrunk = BandMath.targetWeight(0.2, 0.6, 1, OptionalDouble.of(0.15));
        assertTrue(shrunk.shrinkageApplied());
        assertTrue(shrunk.piStarShrunk() < shrunk.piStar());
        assertTrue(shrunk.ciLow() < shrunk.piStar() && shrunk.ciHigh() > shrunk.piStar());
    }

    @Test
    @DisplayName("асимптотическая ширина растёт как ε^{1/3}: восьмикратный рост ε даёт двукратную ширину")
    void asymptoticWidthScalesAsCubeRoot() {
        double narrow = BandMath.bandAsymptotic(0.4, 1, 0.6, 0.0001).width();
        double wide = BandMath.bandAsymptotic(0.4, 1, 0.6, 0.0008).width();
        assertEquals(2.0, wide / narrow, 1e-9, "проверяется численно, а не глазами");
    }

    @Test
    @DisplayName("ширина обращается в ноль при π* = 0 и π* = 1")
    void widthVanishesAtEdges() {
        assertEquals(0, BandMath.bandAsymptotic(0, 1, 0.6, 0.0015).width(), 1e-12);
        assertEquals(0, BandMath.bandAsymptotic(1, 1, 0.6, 0.0015).width(), 1e-12);
    }

    @Test
    @DisplayName("positive control: при малых ε численная и асимптотическая ширины согласованы и отношение стабильно")
    void numericMatchesAsymptoticAtSmallCosts() {
        double sigma = 0.6;
        double piStar = 0.4;
        double mu = piStar * sigma * sigma;
        double ratioTiny = ratio(mu, sigma, piStar, 0.00001);
        double ratioSmall = ratio(mu, sigma, piStar, 0.0001);
        assertTrue(ratioTiny > 0.7 && ratioTiny < 1.7,
                "при ε → 0 численная ширина обязана быть того же порядка: " + ratioTiny);
        assertEquals(ratioTiny, ratioSmall, 0.25,
                "отношение обязано быть стабильным по ε — иначе это расхождение порядка, а не коэффициент");
    }

    private static double ratio(double mu, double sigma, double piStar, double epsilon) {
        double asymptotic = BandMath.bandAsymptotic(piStar, 1, sigma, epsilon).width();
        double numeric = BandMath.bandNumeric(mu, sigma, 1, epsilon, 200, 20).width();
        return numeric / asymptotic;
    }

    @Test
    @DisplayName("выведенная константа 3/(2γ) совпадает с численным оптимумом, а константа ТЗ отличается на 2^{1/3}")
    void derivedConstantMatchesNumericOptimum() {
        double sigma = 0.6;
        double piStar = 0.4;
        double mu = piStar * sigma * sigma;
        for (double epsilon : new double[]{0.00001, 0.0001, 0.001}) {
            double numeric = BandMath.bandNumeric(mu, sigma, 1, epsilon, 200, 20).width();
            double derived = BandMath.bandAsymptoticDerived(piStar, 1, sigma, epsilon).width();
            double tz = BandMath.bandAsymptotic(piStar, 1, sigma, epsilon).width();
            assertEquals(1.0, numeric / derived, 0.1,
                    "численный оптимум обязан совпасть с выведенной константой при ε=" + epsilon);
            assertEquals(BandMath.TZ_CONSTANT_RATIO, derived / tz, 1e-9,
                    "константы ТЗ и вывода отличаются ровно на 2^{1/3}");
        }
    }

    @Test
    @DisplayName("сходимость по сетке: удвоение числа узлов почти не меняет оптимальную ширину")
    void convergesWithGridResolution() {
        double sigma = 0.6;
        double mu = 0.4 * sigma * sigma;
        double coarse = BandMath.bandNumeric(mu, sigma, 1, 0.0015, 100, 20).width();
        double fine = BandMath.bandNumeric(mu, sigma, 1, 0.0015, 400, 20).width();
        assertEquals(coarse, fine, 0.1 * coarse, "оценка обязана стабилизироваться по сетке");
    }

    @Test
    @DisplayName("ширина растёт с издержками, но остаётся внутри допустимого диапазона доли")
    void widthGrowsWithCostsAndStaysBounded() {
        double sigma = 0.6;
        double mu = 0.4 * sigma * sigma;
        double small = BandMath.bandNumeric(mu, sigma, 1, 0.0005, 200, 20).width();
        double large = BandMath.bandNumeric(mu, sigma, 1, 0.02, 200, 20).width();
        assertTrue(large > small, "при больших издержках полоса шире: " + large + " против " + small);
        BandMath.Band huge = BandMath.bandNumeric(mu, sigma, 1, 0.5, 200, 20);
        assertTrue(huge.lower() >= 0 && huge.upper() <= 1,
                "вырожденный случай обрабатывается, а не падает");
    }

    @Test
    @DisplayName("темп роста в оптимуме не ниже, чем при асимптотической ширине")
    void numericIsNotWorseThanAsymptotic() {
        double sigma = 0.8;
        double piStar = 0.4;
        double mu = piStar * sigma * sigma;
        double epsilon = 0.0015;
        BandMath.Band numeric = BandMath.bandNumeric(mu, sigma, 1, epsilon, 200, 20);
        BandMath.Band asymptotic = BandMath.bandAsymptotic(piStar, 1, sigma, epsilon);
        double growthNumeric = BandMath.growthRate(mu, sigma, 1, epsilon,
                numeric.lower(), numeric.upper(), 200);
        double growthAsymptotic = BandMath.growthRate(mu, sigma, 1, epsilon,
                asymptotic.lower(), asymptotic.upper(), 200);
        assertTrue(growthNumeric >= growthAsymptotic - 1e-12,
                "численно найденная полоса не может быть хуже асимптотической");
    }
}
