package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Порог новой продажи: мелкую не ставим, распродажа продаёт всё (04.11.2026, док. 236). */
class SellMinTest {

    private static final double MIN = 0.1;

    @Test
    void безФлагаКакБыло() {
        assertEquals(0.15, QuoteLoop.sellPlaceMin(MIN, false, 0), 1e-12);
        assertEquals(0.15, QuoteLoop.sellPlaceMin(MIN, true, 0), 1e-12);
    }

    @Test
    void обычнаяПродажаНеМельчеПорога() {
        assertEquals(1.0, QuoteLoop.sellPlaceMin(MIN, false, 1.0), 1e-12);
        assertEquals(0.15, QuoteLoop.sellPlaceMin(MIN, false, 0.05), 1e-12);
    }

    @Test
    void распродажаПродаётПочтиДоМинимумаПлощадки() {
        assertEquals(0.101, QuoteLoop.sellPlaceMin(MIN, true, 1.0), 1e-12);
    }
}
