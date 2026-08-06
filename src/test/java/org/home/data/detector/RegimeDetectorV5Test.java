package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Юнит-тесты чистых функций детектора v5 (док. 01-detektor-rezhima-v5 §3). */
class RegimeDetectorV5Test {

    private static final double EPS = 1e-9;

    @Test
    void confidenceHeuristic() {
        // далеко от SMA (|dist|>=2 -> clarity 1) и давно в состоянии (>=30 -> persistence 1) -> 1.0
        assertEquals(1.0, RegimeDetectorV5.confidence(2.0, 30, false, 0), EPS);
        // на самой SMA, первый день -> 0.6*0 + 0.4*(1/30) ≈ 0.0133
        assertEquals(0.4 * (1.0 / 30), RegimeDetectorV5.confidence(0.0, 1, false, 0), EPS);
        // leverage_warning множит на 0.7
        assertEquals(0.7, RegimeDetectorV5.confidence(2.0, 30, true, 0), EPS);
        // отвалившийся источник множит на (1-0.15)
        assertEquals(0.85, RegimeDetectorV5.confidence(2.0, 30, false, 1), EPS);
        // знак dist не важен (модуль)
        assertEquals(RegimeDetectorV5.confidence(1.5, 10, false, 0),
                RegimeDetectorV5.confidence(-1.5, 10, false, 0), EPS);
    }

    @Test
    void confidenceInRange() {
        for (double d = -5; d <= 5; d += 0.5) {
            for (int days = 1; days <= 60; days += 10) {
                double c = RegimeDetectorV5.confidence(d, days, days % 2 == 0, days % 3);
                assertTrue(c >= 0 && c <= 1, "confidence вне [0,1]: " + c);
            }
        }
    }
}
