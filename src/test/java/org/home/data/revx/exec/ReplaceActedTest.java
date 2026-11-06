package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Неудачная замена, после которой наследник возможен (05.11.2026: 500 в затык оставил призрака). */
class ReplaceActedTest {

    @Test
    void ответыПослеКоторыхЗаменаМоглаПройти() {
        assertTrue(QuoteLoop.replaceMayHaveActed(new Venue.Response(422, "{}", 3000)));
        assertTrue(QuoteLoop.replaceMayHaveActed(new Venue.Response(500, "{\"status\":500,\"message\":\"Something went wrong!\"}", 5443)));
        assertTrue(QuoteLoop.replaceMayHaveActed(new Venue.Response(503, null, 2000)));
        assertTrue(QuoteLoop.replaceMayHaveActed(new Venue.Response(-1, null, 10000)));
    }

    @Test
    void ответыБезЗамены() {
        assertFalse(QuoteLoop.replaceMayHaveActed(new Venue.Response(404, "{}", 60)));
        assertFalse(QuoteLoop.replaceMayHaveActed(new Venue.Response(429, "{}", 5)));
        assertFalse(QuoteLoop.replaceMayHaveActed(new Venue.Response(400, "{}", 50)));
    }
}
