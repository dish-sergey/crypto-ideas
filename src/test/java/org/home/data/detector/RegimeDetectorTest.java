package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Юнит-тесты чистых функций компонентов детектора (док. 01). */
class RegimeDetectorTest {

    private static final double EPS = 1e-9;

    @Test
    void c1TrendUpDownFlat() {
        // цена сильно выше SMA, наклон растёт -> +1
        assertEquals(1.0, RegimeDetector.computeC1(200, 100, 90, 10), EPS);
        // сильно ниже, наклон падает -> -1
        assertEquals(-1.0, RegimeDetector.computeC1(0, 100, 110, 10), EPS);
        // на SMA, плоско -> 0
        assertEquals(0.0, RegimeDetector.computeC1(100, 100, 100, 10), EPS);
        // отказ данных -> null
        assertNull(RegimeDetector.computeC1(100, Double.NaN, 100, 10));
        assertNull(RegimeDetector.computeC1(100, 100, 100, 0)); // atr<=0
    }

    @Test
    void c5Breadth() {
        assertEquals(1.0, RegimeDetector.computeC5(new long[]{100, 100}), EPS); // 100% выше
        assertEquals(-1.0, RegimeDetector.computeC5(new long[]{0, 100}), EPS);
        assertEquals(0.0, RegimeDetector.computeC5(new long[]{50, 100}), EPS);
        assertEquals(1.0, RegimeDetector.computeC5(new long[]{70, 100}), EPS);  // 70% -> +1
        assertNull(RegimeDetector.computeC5(null));
        assertNull(RegimeDetector.computeC5(new long[]{5, 10}));                // всего < 20
    }

    @Test
    void c3Funding() {
        assertEquals(1.0, RegimeDetector.computeC3Funding(0.0001), EPS);   // 0.01%/8ч — здоровое плато
        assertEquals(-1.0, RegimeDetector.computeC3Funding(-0.0002), EPS); // устойчиво отрицательный
        assertEquals(0.0, RegimeDetector.computeC3Funding(0.0), EPS);
        assertEquals(0.0, RegimeDetector.computeC3Funding(0.0008), EPS);   // перегрев 0.08% -> 0
        assertNull(RegimeDetector.computeC3Funding(Double.NaN));
    }

    @Test
    void c4Macro() {
        // QE (баланс растёт) + смягчение (ставка падает) + слабый доллар -> +1
        assertEquals(1.0, RegimeDetector.computeC4(1, 2, 100, 90, 90, 100), EPS);
        // ужесточение + QT + сильный доллар -> -1
        assertEquals(-1.0, RegimeDetector.computeC4(2, 1, 90, 100, 100, 90), EPS);
        assertNull(RegimeDetector.computeC4(Double.NaN, 1, 1, 1, 1, 1));
    }

    @Test
    void c2MvrvZscore() {
        // подобрано: mean=90, std=10, mc=100, mvrv=2 -> rc=50, z=5 -> 1-clip(1.6)= -0.6
        assertEquals(-0.6, RegimeDetector.computeC2(2, 100, 18000, 1_640_000, 200), 1e-6);
        assertNull(RegimeDetector.computeC2(2, 100, 18000, 1_640_000, 199)); // мало истории
        assertNull(RegimeDetector.computeC2(0, 100, 18000, 1_640_000, 200)); // mvrv<=0
    }

    @Test
    void compositeRedistributesNulls() {
        // все компоненты +1 -> взвешенная сумма = 1
        assertEquals(1.0, RegimeDetector.composite(1.0, 1.0, 1.0, 1.0, 1.0), EPS);
        // только C1 -> вес перенормирован -> = C1
        assertEquals(0.5, RegimeDetector.composite(0.5, null, null, null, null), EPS);
        // C1=+1, C3=-1, остальное null -> (0.30·1 + 0.20·(-1)) / (0.30+0.20) = 0.2
        assertEquals(0.2, RegimeDetector.composite(1.0, null, -1.0, null, null), EPS);
        // всё null -> null
        assertNull(RegimeDetector.composite(null, null, null, null, null));
    }
}
