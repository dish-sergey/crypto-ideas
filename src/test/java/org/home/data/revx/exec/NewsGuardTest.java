package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsGuardTest {

    private static final String WEEK = """
            [{"title":"CPI m/m","country":"USD","date":"2026-10-14T08:30:00-04:00","impact":"High"},
             {"title":"Core CPI m/m","country":"USD","date":"2026-10-14T08:30:00-04:00","impact":"High"},
             {"title":"Fed Chairman Warsh Speaks","country":"USD","date":"2026-10-15T23:30:00-04:00","impact":"High"},
             {"title":"GDP m/m","country":"GBP","date":"2026-10-15T06:00:00+00:00","impact":"High"},
             {"title":"Bank Holiday","country":"USD","date":"2026-10-12T07:00:00-04:00","impact":"Holiday"},
             {"title":"PPI m/m","country":"USD","date":"2026-10-15T08:30:00-04:00","impact":"High"},
             {"title":"FOMC Press Conference","country":"USD","date":"2026-10-28T14:30:00-04:00","impact":"High"},
             {"title":"Building Permits","country":"USD","date":"2026-10-16T08:30:00-04:00","impact":"Medium"}]
            """;

    @Test
    void windowAroundUsdHighEventsOnly() throws Exception {
        Path f = Files.createTempFile("ff", ".json");
        Files.writeString(f, WEEK);
        NewsGuard g = new NewsGuard(f, 30_000, 90_000, Set.of("High"), Set.of("USD"), NewsGuard.parseLong("Press Conference=4500"));
        long cpi = Instant.parse("2026-10-14T12:30:00Z").toEpochMilli();

        assertNull(g.active(cpi - 31_000));
        NewsGuard.Event e = g.active(cpi - 30_000);
        assertNotNull(e);
        assertEquals("CPI m/m, Core CPI m/m", e.title());     // один момент — одно событие
        assertNotNull(g.active(cpi + 90_000));
        assertNull(g.active(cpi + 91_000));

        // следующее после CPI — PPI, а не выступление, не Великобритания и не Medium
        assertEquals(Instant.parse("2026-10-15T12:30:00Z").toEpochMilli(), g.next(cpi + 91_000).tsMs());
        // пресс-конференция ФРС держит окно 75 мин, а не 90 с
        long pc = Instant.parse("2026-10-28T18:30:00Z").toEpochMilli();
        assertEquals(pc, g.next(Instant.parse("2026-10-15T12:32:00Z").toEpochMilli()).tsMs());
        assertNotNull(g.active(pc + 74 * 60_000L));
        assertNull(g.active(pc + 76 * 60_000L));
        // «прийти пустым» за 15 мин: окно с 18:15
        assertNull(g.within(pc - 15 * 60_000L - 1, 15 * 60_000L));
        assertNotNull(g.within(pc - 15 * 60_000L, 15 * 60_000L));
        assertNull(g.active(pc - 15 * 60_000L));
    }

    @Test
    void missingFileMeansNoProtection() {
        NewsGuard g = new NewsGuard(Path.of("нет-такого-файла.json"), 30_000, 90_000, Set.of("High"), Set.of("USD"), NewsGuard.parseLong("Press Conference=4500"));
        assertNull(g.active(0));
        assertEquals(-1, g.fileAgeMs(0));
    }
}
