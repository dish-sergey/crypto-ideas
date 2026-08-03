package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Тесты классификатора v3: четыре состояния, dwell-lock без исключений, нет CRASH. */
class RegimeFsmV3Test {

    @Test
    void entersBull() {
        RegimeFsmV3 fsm = new RegimeFsmV3();
        RegimeFsmV3.State st = null;
        for (int i = 0; i < 20; i++) {
            st = fsm.step(0.6, 0.7);
        }
        assertEquals(RegimeFsmV3.State.BULL, st);
    }

    @Test
    void lowTrendinessIsRange() {
        RegimeFsmV3 fsm = new RegimeFsmV3();
        RegimeFsmV3.State st = null;
        for (int i = 0; i < 20; i++) {
            st = fsm.step(0.6, 0.2);   // T<0.4 -> RANGE независимо от D
        }
        assertEquals(RegimeFsmV3.State.RANGE, st);
    }

    /** Dwell-lock без исключений: даже сильный разворот держится 15 дней. */
    @Test
    void dwellLockNoException() {
        RegimeFsmV3 fsm = new RegimeFsmV3();
        fsm.step(0.6, 0.7);            // вход в BULL, days_in_state=1
        for (int i = 0; i < 14; i++) {
            assertEquals(RegimeFsmV3.State.BULL, fsm.step(-0.6, 0.7),
                    "день " + i + " должен быть залочен в BULL");
        }
        assertEquals(RegimeFsmV3.State.BEAR, fsm.step(-0.6, 0.7));  // 15-й день -> разлок
    }

    @Test
    void classifyRules() {
        assertEquals(RegimeFsmV3.State.RANGE, RegimeFsmV3.classify(0.9, 0.3));
        assertEquals(RegimeFsmV3.State.BULL, RegimeFsmV3.classify(0.5, 0.6));
        assertEquals(RegimeFsmV3.State.BEAR, RegimeFsmV3.classify(-0.5, 0.6));
        assertEquals(RegimeFsmV3.State.TRANSITION, RegimeFsmV3.classify(0.0, 0.6));
    }
}
