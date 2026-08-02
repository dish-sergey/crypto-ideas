package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Юнит-тесты чистых функций детектора v2 (док. 01-detektor-rezhima-v2). */
class RegimeDetectorV2Test {

    private static final double EPS = 1e-9;

    @Test
    void directionUpDownFlat() {
        // цена сильно выше SMA (>=4 ATR), наклон растёт (>=1.5 ATR) -> +1
        assertEquals(1.0, RegimeDetectorV2.direction(200, 100, 80, 10).d(), EPS);
        // сильно ниже, наклон падает -> -1
        assertEquals(-1.0, RegimeDetectorV2.direction(0, 100, 120, 10).d(), EPS);
        // на SMA, плоско -> 0
        assertEquals(0.0, RegimeDetectorV2.direction(100, 100, 100, 10).d(), EPS);
        // составляющие доступны для agreement
        var dir = RegimeDetectorV2.direction(140, 100, 100, 10);  // dist=4/4=1, slope=0
        assertEquals(1.0, dir.distTerm(), EPS);
        assertEquals(0.0, dir.slopeTerm(), EPS);
        assertEquals(0.7, dir.d(), EPS);
        // отказ данных -> null
        assertNull(RegimeDetectorV2.direction(100, Double.NaN, 100, 10));
        assertNull(RegimeDetectorV2.direction(100, 100, 100, 0)); // atr<=0
    }

    @Test
    void median3Works() {
        assertEquals(0.5, RegimeDetectorV2.median3(0.1, 0.5, 0.9), EPS);
        assertEquals(0.5, RegimeDetectorV2.median3(0.9, 0.5, 0.1), EPS);
        assertEquals(0.5, RegimeDetectorV2.median3(0.5, 0.5, 0.9), EPS);
    }

    @Test
    void cyclePhaseThresholds() {
        assertNull(RegimeDetectorV2.cyclePhase(null));
        assertEquals("ACCUMULATION", RegimeDetectorV2.cyclePhase(-0.5));
        assertEquals("EARLY", RegimeDetectorV2.cyclePhase(0.5));
        assertEquals("MID", RegimeDetectorV2.cyclePhase(1.5));
        assertEquals("LATE", RegimeDetectorV2.cyclePhase(3.0));
        assertEquals("EUPHORIA", RegimeDetectorV2.cyclePhase(4.0));
    }

    @Test
    void mvrvZScore() {
        // mean=90, std=10, mc(scaled)=100, mvrv=2 -> rc=50, z=(100-50)/10=5
        assertEquals(5.0, RegimeDetectorV2.mvrvZ(2, 100e9, 18000, 1_640_000, 200), 1e-6);
        assertNull(RegimeDetectorV2.mvrvZ(2, 100e9, 18000, 1_640_000, 199)); // мало истории
        assertNull(RegimeDetectorV2.mvrvZ(0, 100e9, 18000, 1_640_000, 200)); // mvrv<=0
    }

    @Test
    void macroFlagSign() {
        // QE + смягчение + слабый доллар -> +1
        assertEquals(1, RegimeDetectorV2.macroFlag(1, 2, 100, 90, 90, 100));
        // ужесточение + QT + сильный доллар -> -1
        assertEquals(-1, RegimeDetectorV2.macroFlag(2, 1, 90, 100, 100, 90));
        assertNull(RegimeDetectorV2.macroFlag(Double.NaN, 1, 1, 1, 1, 1));
    }

    @Test
    void breadthFraction() {
        assertEquals(1.0, RegimeDetectorV2.breadth(new long[]{100, 100}), EPS);
        assertEquals(0.0, RegimeDetectorV2.breadth(new long[]{0, 100}), EPS);
        assertEquals(0.5, RegimeDetectorV2.breadth(new long[]{50, 100}), EPS);
        assertNull(RegimeDetectorV2.breadth(null));
        assertNull(RegimeDetectorV2.breadth(new long[]{5, 10}));   // всего < 20
    }

    @Test
    void confidenceFromAgreement() {
        // идеальное согласие (dist==slope), сильный тренд, устоявшееся состояние -> высокий
        double hi = RegimeDetectorV2.confidence(1.0, 1.0, 1.0, 30, false, 0);
        assertEquals(1.0, hi, EPS);  // 0.4·1 + 0.3·1 + 0.3·1
        // разногласие внутри D (dist=+1, slope=-1) роняет agreement до 0
        double lo = RegimeDetectorV2.confidence(0.2, 1.0, -1.0, 30, false, 0);
        assertTrue(lo < hi);
        // leverage_warning множит на 0.7
        assertEquals(0.7, RegimeDetectorV2.confidence(1.0, 1.0, 1.0, 30, true, 0), EPS);
        // отвалившийся источник множит на (1-0.15)
        assertEquals(0.85, RegimeDetectorV2.confidence(1.0, 1.0, 1.0, 30, false, 1), EPS);
    }

    @Test
    void efficiencyRatioTrendVsChop() {
        int n = 120;
        double[] up = new double[n], chop = new double[n];
        for (int i = 0; i < n; i++) {
            up[i] = 100 + i;                       // монотонный рост -> ER ~ 1
            chop[i] = 100 + (i % 2 == 0 ? 0 : 1);  // пила -> ER ~ 0
        }
        assertEquals(1.0, RegimeDetectorV2.efficiencyRatio(up, n - 1), 1e-6);
        assertTrue(RegimeDetectorV2.efficiencyRatio(chop, n - 1) < 0.2);
    }

    @Test
    void logR2StraightLineIsOne() {
        int n = 120;
        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = Math.exp(0.01 * i);   // лог-линейный -> R²=1
        }
        assertEquals(1.0, RegimeDetectorV2.logR2(px, n - 1), 1e-6);
    }

    @Test
    void percentileRankMonotone() {
        double[] raw = {0.1, 0.2, 0.3, 0.4, 0.5};
        double[] rank = new double[5];
        RegimeDetectorV2.percentileRank(raw, rank);
        // каждый следующий — новый максимум окна -> ранг 1.0
        for (double r : rank) {
            assertEquals(1.0, r, EPS);
        }
        double[] raw2 = {0.5, 0.4, 0.3};   // убывание: последний — минимум -> ранг 1/3
        double[] rank2 = new double[3];
        RegimeDetectorV2.percentileRank(raw2, rank2);
        assertEquals(1.0, rank2[0], EPS);
        assertEquals(0.5, rank2[1], EPS);
        assertEquals(1.0 / 3, rank2[2], EPS);
    }
}
