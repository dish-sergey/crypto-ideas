package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReinvestPoolTest {

    private static ReinvestPool pool(boolean shrink) {
        return new ReinvestPool(Map.of("a", 100.0, "b", 100.0, "c", 100.0), 5, 0.5, 3, shrink);
    }

    @Test
    void startsWithReservedBuffer() {
        ReinvestPool p = pool(true);
        assertEquals(15.0, p.buffer(), 1e-12);
    }

    @Test
    void excessSplitEquallyRegardlessOfWhoEarned() {
        ReinvestPool p = pool(true);
        p.step(Map.of("a", 30.0));                 // буфер 45, цель 15 → излишек 30
        // после раздачи цель растёт: 5% от 300+x, буфер = цель
        double cap = p.capital("a");
        assertEquals(cap, p.capital("b"), 1e-12);
        assertEquals(cap, p.capital("c"), 1e-12);
        assertEquals(p.target(), p.buffer(), 1e-9);
        // деньги сохраняются: капиталы + буфер = старт + доход
        assertEquals(300 + 15 + 30, 3 * cap + p.buffer(), 1e-9);
    }

    @Test
    void lossEatsBufferFirstThenCapitalsEqually() {
        ReinvestPool p = pool(true);
        p.step(Map.of("b", -10.0));
        assertEquals(5.0, p.buffer(), 1e-12);
        assertEquals(100.0, p.capital("a"), 1e-12);
        p.step(Map.of("b", -11.0));                // буфер −6 → по −2 с каждого
        assertEquals(0.0, p.buffer(), 1e-12);
        assertEquals(98.0, p.capital("a"), 1e-12);
        assertEquals(98.0, p.capital("c"), 1e-12);
    }

    @Test
    void noShrinkKeepsNegativeBuffer() {
        ReinvestPool p = pool(false);
        p.step(Map.of("a", -21.0));
        assertEquals(-6.0, p.buffer(), 1e-12);
        assertEquals(100.0, p.capital("a"), 1e-12);
    }

    @Test
    void ceilingLeavesRestToOthersAndBuffer() {
        ReinvestPool p = new ReinvestPool(Map.of("a", 10.0, "b", 100.0), 5, 0.5, 3, true);
        p.step(Map.of("b", 1000.0));
        assertEquals(30.0, p.capital("a"), 1e-9);  // упёрся в 3×
        assertEquals(300.0, p.capital("b"), 1e-9);
        assertEquals(10 + 100 + 5.5 + 1000, p.capital("a") + p.capital("b") + p.buffer(), 1e-9);
    }
}
