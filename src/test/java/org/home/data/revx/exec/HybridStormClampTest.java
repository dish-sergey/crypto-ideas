package org.home.data.revx.exec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Размах Бинанса для поводка бури: окно, половина окна до первого ответа, дыра длиннее окна. */
class HybridStormClampTest {

    @AfterEach
    void clear() {
        System.clearProperty("revx.fair.hybrid-clamp-storm-sec");
    }

    @Test
    void rangeOverWindow() {
        System.setProperty("revx.fair.hybrid-clamp-storm-sec", "10");
        HybridCore c = new HybridCore(0.25, 10, 180, 50);
        assertTrue(Double.isNaN(c.bnbRangeBp(0, 100)));          // окно не набрано
        c.bnbRangeBp(1_000, 101);
        c.bnbRangeBp(2_000, 100);
        c.bnbRangeBp(3_000, 100);
        c.bnbRangeBp(4_000, 100);
        double r = c.bnbRangeBp(5_000, 100);                     // половина окна — уже отвечает
        assertEquals(1 / 100.5 * 1e4, r, 1e-6);
        // через 10 с пик 101 (сек 1) выпадает из окна
        for (long s = 6; s <= 11; s++) {
            r = c.bnbRangeBp(s * 1000, 100);
        }
        assertEquals(0, r, 1e-9);
        // вторая цена той же секунды точку не добавляет
        assertEquals(0, c.bnbRangeBp(11_500, 99), 1e-9);
    }

    @Test
    void gapLongerThanWindowRestarts() {
        System.setProperty("revx.fair.hybrid-clamp-storm-sec", "10");
        HybridCore c = new HybridCore(0.25, 10, 180, 50);
        for (long s = 0; s <= 10; s++) {
            c.bnbRangeBp(s * 1000, 100 + s);
        }
        assertTrue(Double.isNaN(c.bnbRangeBp(60_000, 100)));
    }
}
