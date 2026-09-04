package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свободные лоты в {@code /free} округляются ВНИЗ.
 *
 * 04.09.2026 там стоял {@code %.1f}: свободно было 9.859 лота, напечаталось
 * «9.9», человек скопировал подсказку в {@code /claim 9.9} и получил отказ —
 * «просит 1.2375E-4, свободно 1.2324E-4». Число, которое предлагают ввести,
 * обязано быть заведомо принимаемым.
 */
class FreeLotsRoundingTest {

    private static final double LOT = 0.0000125;

    private static double shown(double freeQty) {
        return Math.floor(freeQty / LOT * 10) / 10;
    }

    @Test
    void theLiveCaseNoLongerLies() {
        double free = 0.00012324;                  // 9.8592 лота
        assertTrue(shown(free) * LOT <= free,
                "показанное " + shown(free) + " не влезает в свободные " + free);
        // Занижение допустимо, но в пределах шага округления: 9.859 → 9.8,
        // теряется 0.059 лота, то есть шесть центов на нашем масштабе.
        assertTrue(shown(free) >= 9.8, "занижено сверх шага округления: " + shown(free));
        assertTrue(free / LOT - shown(free) < 0.1, "потеря больше десятой лота");
    }

    @Test
    void shownAmountIsAlwaysClaimable() {
        // Любое свободное количество: показанное обязано проходить проверку claim.
        for (int i = 1; i <= 400; i++) {
            double free = i * LOT / 7.0;           // намеренно некратные лоту величины
            double lots = shown(free);
            assertTrue(lots * LOT <= free + 1e-15,
                    "при свободных " + free + " показано " + lots + " лота — это больше");
        }
    }

    @Test
    void exactMultipleIsNotSpoiled() {
        // Ровно десять лотов обязаны показаться десятью, а не 9.9.
        assertTrue(Math.abs(shown(10 * LOT) - 10.0) < 1e-9, "показано " + shown(10 * LOT));
    }

    @Test
    void nothingFreeShowsZero() {
        assertTrue(shown(0) == 0.0);
        assertTrue(shown(LOT / 100) == 0.0, "меньше десятой лота — это ноль, а не 0.1");
    }
}
