package org.home.data.revx.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.home.data.revx.sim.BookView.Level;
import org.junit.jupiter.api.Test;

/** Отступ от очереди: подход к рынку при толпе впереди, отход к следующей заявке при пустоте. */
class QueueAdaptTest {

    // Цена 100, шаг 0.01, 1 б.п. = 0.01: in = 2 б.п. = 0.02, out = 5 б.п. = 0.05.
    static double buy(double base, List<Level> bids) {
        return QuoteLoop.queueAdaptPrice(true, base, bids, 0.01, 0.02, 0.05, 1000, 25);
    }

    static double sell(double base, List<Level> asks) {
        return QuoteLoop.queueAdaptPrice(false, base, asks, 0.01, 0.02, 0.05, 1000, 25);
    }

    @Test
    void crowdAheadMovesCloserWithinBand() {
        // На нашей цене 99.90 стоит $1500 — встаём на шаг перед ней, 99.91.
        assertEquals(99.91, buy(99.90, List.of(new Level(99.95, 1), new Level(99.90, 15))), 1e-9);
        // Толпа выше нас на 99.95 ($2000): перед ней — 99.96, но не ближе 2 б.п. → 99.92.
        assertEquals(99.92, buy(99.90, List.of(new Level(99.95, 20), new Level(99.90, 1))), 1e-9);
        // Аск зеркально: $1500 на 100.10 → 100.09.
        assertEquals(100.09, sell(100.10, List.of(new Level(100.05, 1), new Level(100.10, 15))), 1e-9);
        // Покупке подход запрещён (in = 0) — стоит на базе.
        assertEquals(99.90, QuoteLoop.queueAdaptPrice(true, 99.90, List.of(new Level(99.90, 15)), 0.01, 0, 0.05, 1000, 25), 1e-9);
    }

    @Test
    void emptyAheadMovesAwayToNextOrder() {
        // Впереди никого, следующая чужая на 99.87 → встаём на 99.88.
        assertEquals(99.88, buy(99.90, List.of(new Level(99.87, 5))), 1e-9);
        // Следующая далеко (99.70) — не дальше 5 б.п.: 99.85.
        assertEquals(99.85, buy(99.90, List.of(new Level(99.70, 5))), 1e-9);
        // Пыль впереди ($10) не в счёт: идём к крупной на 99.86 → 99.87.
        assertEquals(99.87, buy(99.90, List.of(new Level(99.95, 0.1), new Level(99.86, 5))), 1e-9);
        // Аск: следующая на 100.13 → 100.12.
        assertEquals(100.12, sell(100.10, List.of(new Level(100.13, 5))), 1e-9);
    }

    @Test
    void someoneAheadButBelowThresholdStays() {
        assertEquals(99.90, buy(99.90, List.of(new Level(99.95, 5), new Level(99.80, 50))), 1e-9);
    }

    @Test
    void wallFrontStepsBeforeFirstWall() {
        // Бид: $500 на 99.95, $4000 на 99.85 — накопленное достигает $3750 на 99.85, встаём на 99.86.
        assertEquals(99.86, QuoteLoop.wallFrontPrice(true, List.of(new Level(99.95, 5), new Level(99.85, 40)), 0.01, 3750), 1e-9);
        // Аск: стена набирается из нескольких уровней — на 100.20 накоплено $4020.
        assertEquals(100.19, QuoteLoop.wallFrontPrice(false, List.of(new Level(100.05, 20), new Level(100.10, 10), new Level(100.20, 10.2)), 0.01, 3750), 1e-9);
        // Стены нет — NaN.
        assertEquals(Double.NaN, QuoteLoop.wallFrontPrice(true, List.of(new Level(99.95, 5)), 0.01, 3750));
    }

    @Test
    void wallInBandCountsFromBandStart() {
        // Бид, полоса [99.92; 99.86]: $4000 на 99.95 вне полосы не в счёт; в полосе $500 на 99.90 и $4000 на 99.88 → 99.89.
        assertEquals(99.89, QuoteLoop.wallInBand(true, List.of(new Level(99.95, 40), new Level(99.90, 5), new Level(99.88, 40)), 0.01, 3750, 99.92, 99.86), 1e-9);
        // В полосе стены нет — NaN.
        assertEquals(Double.NaN, QuoteLoop.wallInBand(true, List.of(new Level(99.90, 5), new Level(99.80, 40)), 0.01, 3750, 99.92, 99.86));
        // Аск, стена на краю полосы: шаг перед ней, но не ближе края 100.08.
        assertEquals(100.08, QuoteLoop.wallInBand(false, List.of(new Level(100.08, 40)), 0.01, 3750, 100.08, 100.14), 1e-9);
    }

    @Test
    void funcValueIsProbabilityTimesMarkout() {
        // z = 0 → P = 0.5; M = 2 + 1·δ → при δ = 4 доход 0.5 × 6 = 3.
        double[] c = new double[18];
        c[9] = 2; c[10] = 1;
        assertEquals(3.0, QuoteLoop.funcValue(c, 4, 0, 0, 0, 0), 1e-12);
    }
}
