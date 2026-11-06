package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Функция с ходом опоры за 15 мин (06.11.2026): при нулевых новых коэффициентах — прежняя, падение снижает доход. */
class FuncExtTest {

    @Test
    void безНовыхПризнаковСовпадаетСПрежней() {
        double[] c18 = {-2.7, -0.13, 0.0025, -0.019, -0.0058, 0.84, 0.003, 0.26, 0.018, 0.53, 1.06, -0.009, -0.25, 0.03, -0.2, -0.04, 0.1, 0.01};
        double[] c24 = new double[24];
        System.arraycopy(c18, 0, c24, 0, 9);
        System.arraycopy(c18, 9, c24, 12, 9);
        double a = QuoteLoop.funcRateValue(c18, 12, 7, 1, 0.5, 3, 1);
        double b = QuoteLoop.funcRateValue(c24, 12, 7, 1, 0.5, 3, 1, -40);
        assertEquals(a, b, 1e-12);
    }

    @Test
    void падениеСнижаетМаркаут() {
        double[] c24 = new double[24];
        c24[0] = -2;
        c24[12] = 10;
        c24[12 + 9] = 0.15;            // M растёт с R
        assertTrue(QuoteLoop.funcValue(c24, 12, 7, 0, 0, 3, -40) < QuoteLoop.funcValue(c24, 12, 7, 0, 0, 3, 0));
    }
}
