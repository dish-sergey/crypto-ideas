package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Цена парковки печатается по шагу пары (02.11.2026: d получал 400 на {@code 2385.9500000000003}). */
class ParkPriceTest {

    @Test
    void binaryNoiseIsDropped() {
        assertEquals("2385.95", Park.fmtPrice(2385.9500000000003, 0.01));
        assertEquals("82275.9", Park.fmtPrice(82275.90000000001, 0.01));
        assertEquals("93.581", Park.fmtPrice(93.58099999999999, 0.001));
    }

    @Test
    void noStepPrintsAsIs() {
        assertEquals("2385.95", Park.fmtPrice(2385.95, 0));
    }
}
