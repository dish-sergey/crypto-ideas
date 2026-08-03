package org.home.data.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тесты ex-post разметки (док. 15 §3). */
class PeakTroughLabelerTest {

    /** Впадина -> подъём +100% -> пик -> падение -50% -> впадина: BULL затем BEAR. */
    @Test
    void bullThenBear() {
        int n = 300;
        double[] px = new double[n];
        // 0..99: рост 100->200 (+100%), 100..199: падение 200->100 (-50%), 200..299: плоско
        for (int i = 0; i < 100; i++) {
            px[i] = 100 + i;                    // 100 -> 199
        }
        for (int i = 100; i < 200; i++) {
            px[i] = 199 - (i - 100);            // 199 -> 100
        }
        for (int i = 200; i < n; i++) {
            px[i] = 100;
        }
        String[] lab = PeakTroughLabeler.label(px);
        // середина роста -> BULL, середина падения -> BEAR
        assertEquals("BULL", lab[50]);
        assertEquals("BEAR", lab[150]);
    }

    /** Слабые колебания (< порогов) не дают ни BULL, ни BEAR — только RANGE. */
    @Test
    void weakMovesAreRange() {
        int n = 400;
        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = 100 + 5 * Math.sin(i / 20.0);   // ±5%, ниже порогов 50/30%
        }
        String[] lab = PeakTroughLabeler.label(px);
        long nonRange = java.util.Arrays.stream(lab).filter(s -> !"RANGE".equals(s)).count();
        assertEquals(0, nonRange);
    }

    /** Разметка не смотрит за пределы массива и заполняет каждый день. */
    @Test
    void everyDayLabeled() {
        int n = 250;
        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = 100 * Math.exp(0.01 * i);       // монотонный рост
        }
        String[] lab = PeakTroughLabeler.label(px);
        assertEquals(n, lab.length);
        for (String s : lab) {
            assertTrue(s != null && !s.isEmpty());
        }
    }
}
