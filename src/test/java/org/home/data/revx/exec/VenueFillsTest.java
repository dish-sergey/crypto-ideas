package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Этап 3: чья сделка в ленте читателя. */
class VenueFillsTest {

    @Test
    void своюУзнаёмПоМеткеИлиПоПамяти() {
        assertTrue(QuoteLoop.mine("f", false, "f"));          // читатель знает хозяина
        assertTrue(QuoteLoop.mine(null, true, "f"));          // читатель ещё не спросил, но заявка наша
        assertFalse(QuoteLoop.mine("b", false, "f"));         // соседа не берём
        assertFalse(QuoteLoop.mine(null, false, "f"));        // ручная или неизвестная
    }

    @Test
    void поУмолчаниюВыключено() {
        assertFalse(QuoteLoop.VENUE_FILLS);
    }
}
