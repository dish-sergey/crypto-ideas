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

    @Test
    void пределИзЮнитаСводки() {
        System.setProperty("revx.info.caps", "a:160,b:175");
        try {
            org.junit.jupiter.api.Assertions.assertEquals(160, InfoBot.capOf("a"));
            org.junit.jupiter.api.Assertions.assertEquals(175, InfoBot.capOf("B"));
            org.junit.jupiter.api.Assertions.assertEquals(235, InfoBot.capOf("d"));   // нет в карте — прежняя
        } finally {
            System.clearProperty("revx.info.caps");
        }
    }

    @Test
    void нормаПрибылиНаПолныйКапитал() {
        String s = InfoBot.fullCapitalText(2000, 5);
        assertTrue(s.contains("ПОЛНЫЙ КАПИТАЛ (всё на счёте, с буферами): 2000.00 USDC"), s);
        assertTrue(s.contains("+5.0000 USDC = +0.250% на полный капитал"), s);
        assertTrue(InfoBot.fullCapitalText(0, 5).isEmpty());
    }

    /** Порог — один лот на бота (05.11.2026): потолок пары $260, лоты $55.79 + $108.33. */
    @Test
    void порогОдинЛотНаБота() {
        Map<String, Double> caps = new TreeMap<>(Map.of("BTC", 260.0 / 80_000));
        Map<String, Double> lots = Map.of("BTC", 164.12 / 80_000);
        Map<String, Double> px = Map.of("BTC", 80_000.0);
        String low = InfoBot.bufferText(caps, Map.of("BTC", 151.0 / 80_000, "USDC", 200.0), px, lots);
        assertTrue(low.contains("BTC 58%⚠️"), low);
        assertTrue(low.contains("USDC 77%"), low);
        assertTrue(low.contains("порог: один лот на бота, 63% потолков; USDC $200 из $164"), low);
        String ok = InfoBot.bufferText(caps, Map.of("BTC", 170.0 / 80_000, "USDC", 200.0), px, lots);
        assertFalse(ok.contains("⚠️"), ok);
    }
}
