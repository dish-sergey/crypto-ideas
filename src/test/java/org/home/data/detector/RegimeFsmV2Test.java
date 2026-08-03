package org.home.data.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Тесты классификатора v2: правила §3, dwell-lock, приоритет CRASH. */
class RegimeFsmV2Test {

    /** Стабильный бычий тренд без стресса -> BULL. */
    @Test
    void entersBull() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        RegimeFsmV2.State st = null;
        for (int i = 0; i < 20; i++) {
            st = fsm.step(0.6, 0.7, 0.1);   // D>0.2, T>=0.4, S низкий
        }
        assertEquals(RegimeFsmV2.State.BULL, st);
    }

    /** Низкая трендовость -> RANGE независимо от знака D. */
    @Test
    void lowTrendinessIsRange() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        RegimeFsmV2.State st = null;
        for (int i = 0; i < 20; i++) {
            st = fsm.step(0.6, 0.2, 0.1);   // D бычье, но T<0.4
        }
        assertEquals(RegimeFsmV2.State.RANGE, st);
    }

    /** CRASH обходит dwell-lock: срабатывает мгновенно из любого состояния. */
    @Test
    void crashBypassesDwell() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        for (int i = 0; i < 20; i++) {
            fsm.step(0.6, 0.7, 0.1);        // устоялись в BULL
        }
        assertEquals(RegimeFsmV2.State.BULL, fsm.state());
        RegimeFsmV2.State st = fsm.step(-0.5, 0.7, 0.9);  // S>=0.8
        assertEquals(RegimeFsmV2.State.CRASH, st);
        assertEquals(1, fsm.daysInState());
    }

    /** Выход из CRASH — только после 3 спокойных дней подряд (S<0.5), затем пересчёт. */
    @Test
    void crashExitsAfterThreeCalmDays() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        fsm.step(-0.5, 0.7, 0.9);           // -> CRASH
        assertEquals(RegimeFsmV2.State.CRASH, fsm.step(-0.5, 0.7, 0.6)); // S>0.5, держит
        assertEquals(RegimeFsmV2.State.CRASH, fsm.step(-0.5, 0.7, 0.3)); // 1 спокойный
        assertEquals(RegimeFsmV2.State.CRASH, fsm.step(-0.5, 0.7, 0.3)); // 2 спокойных
        // 3-й спокойный -> пересчёт: T>=0.4, D<-0.2 -> BEAR
        assertEquals(RegimeFsmV2.State.BEAR, fsm.step(-0.5, 0.7, 0.3));
        assertEquals(1, fsm.daysInState());
    }

    /** Спокойный день, прерванный стрессом, обнуляет счётчик выхода. */
    @Test
    void crashExitStreakResets() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        fsm.step(-0.5, 0.7, 0.9);           // CRASH
        fsm.step(-0.5, 0.7, 0.3);           // 1 спокойный
        fsm.step(-0.5, 0.7, 0.9);           // стресс вернулся -> счётчик сброшен, всё ещё CRASH
        assertEquals(RegimeFsmV2.State.CRASH, fsm.state());
        fsm.step(-0.5, 0.7, 0.3);           // 1
        fsm.step(-0.5, 0.7, 0.3);           // 2
        assertEquals(RegimeFsmV2.State.CRASH, fsm.state()); // ещё не 3
    }

    /**
     * Dwell-lock — минимум 15 дней в состоянии: сразу после входа в BULL развернувшийся
     * медвежий сигнал держится залоченным, пока не набежит 15 дней, и только потом BEAR.
     */
    @Test
    void dwellLockHoldsState() {
        RegimeFsmV2 fsm = new RegimeFsmV2();
        fsm.step(0.6, 0.7, 0.1);            // вход в BULL, days_in_state=1
        // 14 медвежьих дней (days_in_state 2..15) держатся залоченными в BULL
        for (int i = 0; i < 14; i++) {
            assertEquals(RegimeFsmV2.State.BULL, fsm.step(-0.6, 0.7, 0.1),
                    "день " + i + " должен быть залочен в BULL");
        }
        // 15-й медвежий день: lock снят (days_in_state=15) -> переключение в BEAR
        assertEquals(RegimeFsmV2.State.BEAR, fsm.step(-0.6, 0.7, 0.1));
    }

    /** Флаг crashEnabled=false: S≥0.80 не переводит в CRASH (вариант A4.3). */
    @Test
    void crashDisabledNeverEntersCrash() {
        RegimeFsmV2 fsm = new RegimeFsmV2(false);
        RegimeFsmV2.State st = null;
        for (int i = 0; i < 30; i++) {
            st = fsm.step(-0.8, 0.9, 1.0);   // сильный стресс + медвежье направление
        }
        // без CRASH день классифицируется правилами 3–6: T>=0.4, D<-0.2 -> BEAR
        assertEquals(RegimeFsmV2.State.BEAR, st);
    }

    /** Правила classifyFresh: T,D -> состояние (без dwell/CRASH). */
    @Test
    void classifyFreshRules() {
        assertEquals(RegimeFsmV2.State.RANGE, RegimeFsmV2.classifyFresh(0.9, 0.3));   // T<0.4
        assertEquals(RegimeFsmV2.State.BULL, RegimeFsmV2.classifyFresh(0.5, 0.6));
        assertEquals(RegimeFsmV2.State.BEAR, RegimeFsmV2.classifyFresh(-0.5, 0.6));
        assertEquals(RegimeFsmV2.State.TRANSITION, RegimeFsmV2.classifyFresh(0.0, 0.6)); // |D|<0.2
    }
}
