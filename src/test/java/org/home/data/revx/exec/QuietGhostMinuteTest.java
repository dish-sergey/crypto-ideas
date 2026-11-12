package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Минута затыков по призракам (13.11.2026): :38 — основная, :36 — вторая; затыки во все минуты выбор не сбивают. */
class QuietGhostMinuteTest {
    static long at(int hour, int minute) {
        return 1794000000000L / 3_600_000L * 3_600_000L + hour * 3_600_000L + minute * 60_000L + 50_000L;
    }

    @Test
    void ghostsPickMinuteAndSecond() {
        List<Long> g = new ArrayList<>();
        for (int h = 0; h < 14; h++) g.add(at(h, 38));
        g.add(at(20, 36));
        g.add(at(21, 36));
        g.add(at(22, 56));
        assertEquals(38, QuoteLoop.pickQuietMinute(g));
        assertEquals(36, QuoteLoop.secondGhostMinute(g, 38));
    }

    @Test
    void noSecondWhenRare() {
        List<Long> g = new ArrayList<>();
        for (int h = 0; h < 30; h++) g.add(at(h, 38));
        g.add(at(40, 36));
        assertEquals(-1, QuoteLoop.secondGhostMinute(g, 38));
    }

    @Test
    void stallsEverywhereDoNotPick() {
        List<Long> s = new ArrayList<>();
        for (int h = 0; h < 20; h++) for (int m = 0; m < 60; m += 3) s.add(at(h, m));
        for (int h = 0; h < 20; h++) s.add(at(h, 38));
        assertEquals(-1, QuoteLoop.pickQuietMinute(s));
    }
}
