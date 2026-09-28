package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Окно :38 — зависание площадки каждый час в HH:38:29–30. */
class QuietWindowTest {

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void окноВнутриМинуты38() {
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:29.300Z")));   // сам затык 28.09
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:20.000Z")));
        assertTrue(QuoteLoop.inQuietWindow(t("2026-09-28T23:38:39.999Z")));
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:40.000Z")));
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:38:19.999Z")));
        assertFalse(QuoteLoop.inQuietWindow(t("2026-09-28T09:37:30.000Z")));
    }
}
