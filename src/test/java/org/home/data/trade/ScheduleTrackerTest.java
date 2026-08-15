package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Отслеживание пересмотра расписаний (doc 55 §2.3/§2.4): перенос/отмена после входа → закрыть; учёт частоты. */
class ScheduleTrackerTest {

    @Test void unchangedScheduleDoesNotTrigger() {
        ScheduleTracker t = new ScheduleTracker();
        t.onEntry("PF_APTUSD", 20005);
        assertFalse(t.scheduleChanged("PF_APTUSD", 20005L, 20000));
        assertTrue(t.revisions().isEmpty());
    }

    @Test void postponedScheduleTriggersAndLogged() {
        ScheduleTracker t = new ScheduleTracker();
        t.onEntry("PF_APTUSD", 20005);
        assertTrue(t.scheduleChanged("PF_APTUSD", 20030L, 20003), "разлок перенесён → закрыть");
        assertEquals(1, t.revisions().size());
        var r = t.revisions().get(0);
        assertEquals(20005, r.expectedDay());
        assertEquals(20030L, r.newDay());
        assertEquals(2, r.daysToDateWhenSeen()); // 20005 − 20003
    }

    @Test void cancelledScheduleTriggers() {
        ScheduleTracker t = new ScheduleTracker();
        t.onEntry("PF_APTUSD", 20005);
        assertTrue(t.scheduleChanged("PF_APTUSD", null, 20002), "разлок отменён (нет в фиде) → закрыть");
        assertNull(t.revisions().get(0).newDay());
    }

    @Test void untrackedSymbolIgnored() {
        ScheduleTracker t = new ScheduleTracker();
        assertFalse(t.scheduleChanged("PF_XXXUSD", null, 20000));
    }

    @Test void onExitStopsTracking() {
        ScheduleTracker t = new ScheduleTracker();
        t.onEntry("PF_APTUSD", 20005);
        assertEquals(1, t.tracked());
        t.onExit("PF_APTUSD");
        assertEquals(0, t.tracked());
        assertFalse(t.scheduleChanged("PF_APTUSD", null, 20000));
    }
}
