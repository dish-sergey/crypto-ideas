package org.home.data.eval.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тесты кандидатов стенда (док. 15 §5). */
class CandidatesTest {

    @Test
    void alwaysRange() {
        double[] c = new double[300];
        java.util.Arrays.fill(c, 100);
        String[] s = new Candidates.AlwaysRange().predict(c, c, c);
        for (String x : s) {
            assertEquals("RANGE", x);
        }
    }

    @Test
    void sma200UpDown() {
        int n = 400;
        double[] up = new double[n], down = new double[n];
        for (int i = 0; i < n; i++) {
            up[i] = 100 + i;
            down[i] = 500 - i;
        }
        String[] su = new Candidates.Sma200().predict(up, up, up);
        String[] sd = new Candidates.Sma200().predict(down, down, down);
        assertNull(su[198], "до 200 баров SMA не определена");
        assertEquals("BULL", su[n - 1]);
        assertEquals("BEAR", sd[n - 1]);
    }

    @Test
    void cusumTriggersOnTrend() {
        int n = 200;
        double[] up = new double[n];
        for (int i = 0; i < n; i++) {
            up[i] = 100 * Math.exp(0.02 * i);   // сильный устойчивый рост
        }
        String[] s = new Candidates.Cusum(0.01, 0.15).predict(up, up, up);
        assertEquals("BULL", s[n - 1]);
        assertEquals("RANGE", s[0]);            // до первого срабатывания
    }

    /** Каузальность: усечение ряда не меняет прошлых состояний (§7.1). */
    @Test
    void cusumCausal() {
        int n = 150;
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 + 10 * Math.sin(i / 7.0) + i * 0.3;
        }
        var cand = new Candidates.Cusum(0.01, 0.15);
        String[] full = cand.predict(c, c, c);
        String[] trunc = cand.predict(java.util.Arrays.copyOf(c, 100),
                java.util.Arrays.copyOf(c, 100), java.util.Arrays.copyOf(c, 100));
        assertEquals(full[99], trunc[99]);
        assertTrue(true);
    }
}
