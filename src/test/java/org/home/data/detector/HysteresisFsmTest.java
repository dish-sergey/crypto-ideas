package org.home.data.detector;

import org.home.data.detector.HysteresisFsm.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HysteresisFsmTest {

    private static State stepN(HysteresisFsm f, double score, int n) {
        State s = f.state();
        for (int i = 0; i < n; i++) {
            s = f.step(score);
        }
        return s;
    }

    @Test
    void startsInTransition() {
        assertEquals(State.TRANSITION, new HysteresisFsm().state());
    }

    @Test
    void bullEntryNeedsTenDays() {
        HysteresisFsm f = new HysteresisFsm();
        assertEquals(State.TRANSITION, stepN(f, 0.5, 9)); // 9 дней — ещё не вход
        assertEquals(State.BULL, f.step(0.5));            // 10-й день — вход в BULL
    }

    @Test
    void bullHoldsInsideHysteresisBand() {
        HysteresisFsm f = new HysteresisFsm();
        stepN(f, 0.5, 10); // BULL
        // score в зоне [0.10, 0.30) не выводит из BULL (гистерезис)
        assertEquals(State.BULL, stepN(f, 0.15, 5));
    }

    @Test
    void bullExitsBelowExitThreshold() {
        HysteresisFsm f = new HysteresisFsm();
        stepN(f, 0.5, 10);
        assertEquals(State.TRANSITION, f.step(0.05)); // < 0.10 -> выход
    }

    @Test
    void bearIsSymmetric() {
        HysteresisFsm f = new HysteresisFsm();
        assertEquals(State.TRANSITION, stepN(f, -0.5, 9));
        assertEquals(State.BEAR, f.step(-0.5));
        assertEquals(State.TRANSITION, f.step(-0.05)); // > -0.10 -> выход
    }

    @Test
    void rangeStabilizationNeedsFifteenDays() {
        HysteresisFsm f = new HysteresisFsm(); // старт TRANSITION
        assertEquals(State.TRANSITION, stepN(f, 0.0, 14));
        assertEquals(State.RANGE, f.step(0.0)); // 15-й день штиля -> RANGE
    }

    @Test
    void daysInStateResetsOnChange() {
        HysteresisFsm f = new HysteresisFsm();
        stepN(f, 0.5, 10); // вход в BULL
        assertEquals(1, f.daysInState());
        f.step(0.5);
        assertEquals(2, f.daysInState());
        f.step(0.05); // -> TRANSITION
        assertEquals(1, f.daysInState());
    }

    @Test
    void bullCrashesToBearViaTransition() {
        HysteresisFsm f = new HysteresisFsm();
        stepN(f, 0.5, 10);            // BULL
        assertEquals(State.TRANSITION, f.step(0.05));
        assertEquals(State.BEAR, stepN(f, -0.5, 10)); // 10 дней вниз -> BEAR
    }
}
