package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Минута затыков: выбор по гистограмме и окно разведения. */
class QuietMinuteTest {

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void выбираетВыраженнуюМинуту() {
        List<Long> stalls = new ArrayList<>();
        for (int h = 0; h < 12; h++) {                     // как живьём: :38 каждый час
            stalls.add(t(String.format("2026-09-28T%02d:38:30Z", h)));
        }
        for (int h = 0; h < 10; h++) {                     // и россыпь по часу
            stalls.add(t(String.format("2026-09-28T%02d:%02d:10Z", h, 5 + h * 4)));
        }
        assertEquals(38, QuoteLoop.pickQuietMinute(stalls));
    }

    @Test
    void невыраженнаяНеВыбирается() {
        List<Long> stalls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            stalls.add(t(String.format("2026-09-28T%02d:%02d:10Z", i % 24, (i * 7) % 60)));
        }
        assertEquals(-1, QuoteLoop.pickQuietMinute(stalls));
        assertEquals(-1, QuoteLoop.pickQuietMinute(List.of(t("2026-09-28T09:38:30Z"))));
    }

    @Test
    void окноСЗапасомДоМинуты() {
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T09:37:51Z"), 38));
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:30Z"), 38));
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:39:00Z"), 38));
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:37:49Z"), 38));
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T09:59:55Z"), 0));   // через час
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:30Z"), -1));
    }
}
