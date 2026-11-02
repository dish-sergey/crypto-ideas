package org.home.data.revx.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Цена запаса по таблице и цена постановки от остатка до предела (док. 225, 224 §6.0). */
class FuncPriceTest {

    @Test
    void headMuZeroBelowThresholdAndFullAtCap() {
        assertEquals(0, QuoteLoop.headMu(50, 100, 10, 0.6, 2), 1e-12);
        assertEquals(0, QuoteLoop.headMu(60, 100, 10, 0.6, 2), 1e-12);
        assertEquals(2.5, QuoteLoop.headMu(80, 100, 10, 0.6, 2), 1e-9);   // x = 0.5
        assertEquals(10, QuoteLoop.headMu(100, 100, 10, 0.6, 2), 1e-9);
        assertEquals(10, QuoteLoop.headMu(130, 100, 10, 0.6, 2), 1e-9);   // за пределом не растёт
    }

    @Test
    void invPriceInterpolatesAndClamps() {
        double[] row = {-4, -1, 2};
        assertEquals(-4, QuoteLoop.invPrice(row, -0.5), 1e-12);
        assertEquals(-2.5, QuoteLoop.invPrice(row, 0.5), 1e-12);
        assertEquals(2, QuoteLoop.invPrice(row, 2.3), 1e-12);
    }
}
