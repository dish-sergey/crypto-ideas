package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Порог бури по монете из revx.exec.storm-vol (05.11.2026). */
class StormThresholdTest {

    @Test
    void порогПоМонете() {
        String s = "BTC:3.81, ETH:5.12,SOL:6.38";
        assertEquals(3.81, QuoteLoop.stormThreshold(s, "BTC"), 1e-12);
        assertEquals(6.38, QuoteLoop.stormThreshold(s, "sol"), 1e-12);
        assertTrue(Double.isNaN(QuoteLoop.stormThreshold(s, "XRP")));
        assertTrue(Double.isNaN(QuoteLoop.stormThreshold(null, "BTC")));
    }
}
