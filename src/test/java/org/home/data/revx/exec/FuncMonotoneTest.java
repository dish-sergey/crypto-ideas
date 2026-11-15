package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Монотонная вероятность исполнения (15.11.2026, док. 248): доход по готовым z и M обязан совпадать с прежними
 * формулами, а бегущий минимум z — не давать P расти за удалением (член δ² с плюсом за пределами обучения δ ≤ 30).
 */
class FuncMonotoneTest {

    // строка бида ETH, общие наклоны с маркаутом по δ (d248/coef-poolMd.csv)
    static final double[] C = {-2.8115022, -0.10067513, 0.0020257820, -0.043909499, -0.0037665437, 0.95860082, -0.0057973502,
            0.21538353, 0.012909017, -0.96548971, 1.0112557, 0, 0, 0, 0, 0, 0, 0};

    @Test
    void доходПоГотовымZMСовпадает() {
        double[] zm = QuoteLoop.funcZM(C, 20, 9.1, 0, 1.77, 18.1, 0);
        assertEquals(QuoteLoop.funcValue(C, 20, 9.1, 0, 1.77, 18.1, 0), QuoteLoop.funcValueOf(zm, false, 0), 1e-15);
        assertEquals(QuoteLoop.funcRateValue(C, 20, 9.1, 0, 1.77, 18.1, 2.5, 0), QuoteLoop.funcValueOf(zm, true, 2.5), 1e-15);
    }

    @Test
    void безМонотонностиPРастётЗа30АСНейНет() {
        double z30 = QuoteLoop.funcZM(C, 30, 9.1, 0, 1.77, 18.1, 0)[0], z70 = QuoteLoop.funcZM(C, 70, 13.8, 0, 1.77, 18.1, 0)[0];
        assertEquals(true, z70 > z30, "член δ² тянет P вверх за пределами обучения");
        double zMono = Double.POSITIVE_INFINITY;
        for (int d = 4; d <= 70; d++) {
            double z = Math.min(QuoteLoop.funcZM(C, d, 9.1, 0, 1.77, 18.1, 0)[0], zMono);
            assertEquals(true, z <= zMono, "δ=" + d);
            zMono = z;
        }
    }
}
