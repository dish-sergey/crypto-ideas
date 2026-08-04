package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тесты v3: statesFromPrice (оси D/T из чистой цены, для кросс-рынка). */
class RegimeDetectorV3Test {

    /** Устойчивый рост -> к концу серии BULL; прогревочные дни без состояния. */
    @Test
    void upTrendBecomesBull() {
        int n = 700;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 * Math.exp(0.004 * i);   // устойчивый лог-линейный рост
            h[i] = c[i];
            l[i] = c[i];
        }
        String[] st = RegimeDetectorV3.statesFromPrice(h, l, c);
        assertNull(st[0], "прогрев — состояния нет");
        assertNull(st[228], "до WARMUP (229) состояния нет");
        assertEquals("BULL", st[n - 1]);
        // после прогрева состояния появляются
        long nonNull = java.util.Arrays.stream(st).filter(s -> s != null).count();
        assertTrue(nonNull > 400);
    }

    /** Короткая серия (< прогрева) — все состояния null, без падений. */
    @Test
    void shortSeriesNoStates() {
        int n = 100;
        double[] a = new double[n];
        java.util.Arrays.fill(a, 100);
        String[] st = RegimeDetectorV3.statesFromPrice(a, a, a);
        for (String s : st) {
            assertNull(s);
        }
    }
}
