package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Эскалация напоминаний: каждый порог один раз, самый срочный при пропуске, снятие по clear. */
class ApprovalRemindersTest {

    private static final long ENTRY = 100_000_000L;     // произвольный момент входа, сек

    @Test void tooEarlyNoReminder() {
        ApprovalReminders r = new ApprovalReminders();
        assertNull(r.due("x", ENTRY, ENTRY - 48 * 3600), "за 48ч — рано, первый порог 24ч");
    }

    @Test void firesEachThresholdOnceInOrder() {
        ApprovalReminders r = new ApprovalReminders();
        assertEquals(24, r.due("x", ENTRY, ENTRY - 20 * 3600), "20ч осталось → 24ч-порог");
        assertNull(r.due("x", ENTRY, ENTRY - 20 * 3600), "тот же порог повторно не шлём");
        assertEquals(12, r.due("x", ENTRY, ENTRY - 10 * 3600));
        assertEquals(3, r.due("x", ENTRY, ENTRY - 2 * 3600));
        assertEquals(1, r.due("x", ENTRY, ENTRY - 1800));       // 0.5ч
        assertNull(r.due("x", ENTRY, ENTRY - 60), "все пороги пройдены");
    }

    @Test void skippedThresholdsCollapseToMostUrgent() {
        ApprovalReminders r = new ApprovalReminders();
        // демон стоял: сразу осталось 2ч — шлём один (3ч), более срочный ещё не наступил
        assertEquals(3, r.due("x", ENTRY, ENTRY - 2 * 3600));
        // 24 и 12 считаются отправленными (моот), их уже не будет
        assertEquals(1, r.due("x", ENTRY, ENTRY - 1800));
    }

    @Test void pastEntryStillFinalReminderOnce() {
        ApprovalReminders r = new ApprovalReminders();
        assertEquals(1, r.due("x", ENTRY, ENTRY + 3600), "вход уже прошёл → один финальный");
        assertNull(r.due("x", ENTRY, ENTRY + 7200));
    }

    @Test void clearResetsTracking() {
        ApprovalReminders r = new ApprovalReminders();
        r.due("x", ENTRY, ENTRY - 20 * 3600);
        assertTrue(r.isTracked("x"));
        r.clear("x");
        assertFalse(r.isTracked("x"));
        assertEquals(24, r.due("x", ENTRY, ENTRY - 20 * 3600), "после clear пороги снова свежие");
    }

    @Test void independentPerEvent() {
        ApprovalReminders r = new ApprovalReminders();
        assertEquals(24, r.due("a", ENTRY, ENTRY - 20 * 3600));
        assertEquals(24, r.due("b", ENTRY, ENTRY - 20 * 3600), "разные события — свой учёт");
    }
}
