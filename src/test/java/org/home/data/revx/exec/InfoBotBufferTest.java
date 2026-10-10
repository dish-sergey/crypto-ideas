package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Буфер в /all: ничейное как доля потолков, ⚠️ ниже 30% по монете и 60% по USDC. */
class InfoBotBufferTest {

    private static final Map<String, Double> CAPS = new TreeMap<>(Map.of(
            "BTC", 0.0007028, "ETH", 0.02198, "SOL", 0.52864));
    private static final Map<String, Double> PX = Map.of(
            "BTC", 83_000.0, "ETH", 2_512.0, "SOL", 110.5, "USDC", 1.0);

    @Test
    void срез10октябряПроходит() {
        String s = InfoBot.bufferText(CAPS, Map.of("BTC", 0.00023836, "ETH", 0.00766804,
                "SOL", 0.172578, "USDC", 130.66), PX);
        assertTrue(s.contains("BTC 34% · ETH 35% · SOL 33% · USDC 76%"), s);
        assertFalse(s.contains("⚠️"), s);
    }

    @Test
    void нехваткаПомечается() {
        String s = InfoBot.bufferText(CAPS, Map.of("BTC", 0.0001, "ETH", 0.00766804,
                "SOL", 0.172578, "USDC", 50.0), PX);
        assertTrue(s.contains("⚠️ БУФЕР"), s);
        assertTrue(s.contains("BTC 14%⚠️"), s);
        assertTrue(s.contains("USDC 29%⚠️"), s);
        assertFalse(s.contains("ETH 35%⚠️"), s);
    }
}
