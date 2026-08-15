package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Монитор деградации (doc 54 §3): порог из принципа — rolling-40 &lt; 0 ЛИБО два подряд отрицательных
 * rolling-20. Проверяем, что нормальная дисперсия НЕ триггерит, а устойчивый минус — триггерит.
 */
class DegradationMonitorTest {

    private static void feed(DegradationMonitor m, int n, double v) { for (int i = 0; i < n; i++) m.record(v); }

    @Test void noSignalOnHealthyPremium() {
        DegradationMonitor m = new DegradationMonitor();
        feed(m, 60, 0.025);                       // стабильно +2.5%
        assertFalse(m.pauseSignalled());
        assertEquals(0.025, m.rolling(20), 1e-9);
        assertEquals(0.025, m.rolling(40), 1e-9);
    }

    @Test void consecutiveNegativeRolling20Pauses() {
        DegradationMonitor m = new DegradationMonitor();
        feed(m, 40, 0.03);                        // хорошая база (rolling-40 > 0)
        feed(m, 20, -0.03);                       // подряд отрицательные → два подряд rolling-20 < 0
        assertTrue(m.pauseSignalled(), "устойчивый минус (два подряд отриц. rolling-20) = деградация");
    }

    @Test void normalDispersionDoesNotTrigger() {
        DegradationMonitor m = new DegradationMonitor();
        // чередование +/- с положительным средним: rolling-20 колеблется, но не два подряд < 0, rolling-40 > 0
        for (int i = 0; i < 60; i++) m.record(i % 2 == 0 ? 0.08 : -0.02); // среднее +3%
        assertNotNull(m.rolling(40));
        assertTrue(m.rolling(40) > 0);
        assertFalse(m.pauseSignalled(), "нормальная дисперсия при положительном среднем не паузит");
    }

    @Test void sustainedNegativeRolling40Pauses() {
        DegradationMonitor m = new DegradationMonitor();
        feed(m, 40, 0.02);                        // хорошая история
        feed(m, 40, -0.05);                       // затяжной минус → rolling-40 < 0
        assertTrue(m.pauseSignalled(), "rolling-40 < 0 → пауза");
    }

    @Test void clearResetsSignal() {
        DegradationMonitor m = new DegradationMonitor();
        feed(m, 40, -0.05);
        assertTrue(m.pauseSignalled());
        m.clearPauseSignal();
        assertFalse(m.pauseSignalled());
    }

    @Test void rollingNullBeforeEnoughSamples() {
        DegradationMonitor m = new DegradationMonitor();
        feed(m, 10, 0.02);
        assertNull(m.rolling(20));
        assertNull(m.rolling(40));
        assertFalse(m.pauseSignalled());
    }
}
