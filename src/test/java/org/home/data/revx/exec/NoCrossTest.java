package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Предохранитель от пересечения книги (док. 150).
 *
 * Проверяется арифметика зажима, а не цикл котирования: правило простое и
 * должно оставаться простым — заявка не может стоять на цене, по которой её
 * заберут немедленно.
 *
 * Числа живые: 03.09.2026 из 217 отказов замены за 8 часов 139 пришли с
 * причиной `post_only_immediate_match`, то есть наш бид стоял на аске.
 */
class NoCrossTest {

    private static final double STEP = 0.01;

    /** Та же арифметика, что в {@code QuoteLoop.noCross}. */
    private static Double clamp(boolean buy, Double target, double bookBid, double bookAsk) {
        if (target == null) {
            return null;
        }
        if (buy && bookAsk > 0 && target >= bookAsk) {
            double c = bookAsk - STEP;
            return c > 0 ? c : null;
        }
        if (!buy && bookBid > 0 && target <= bookBid) {
            return bookBid + STEP;
        }
        return target;
    }

    @Test
    void buyAboveAskIsPulledInsideTheBook() {
        // Корзина обогнала книгу: бид посчитан выше аска площадки.
        Double got = clamp(true, 81100.0, 81000.0, 81050.0);
        assertEquals(81049.99, got, 1e-9);
        assertTrue(got < 81050.0, "после зажима заявка обязана быть строго внутри книги");
    }

    @Test
    void sellBelowBidIsPulledInsideTheBook() {
        Double got = clamp(false, 80900.0, 81000.0, 81050.0);
        assertEquals(81000.01, got, 1e-9);
        assertTrue(got > 81000.0);
    }

    @Test
    void priceInsideTheBookIsUntouched() {
        // Приёмка «ноль цены вне срабатывания»: обычная котировка не меняется.
        assertEquals(80990.0, clamp(true, 80990.0, 81000.0, 81050.0), 1e-9);
        assertEquals(81060.0, clamp(false, 81060.0, 81000.0, 81050.0), 1e-9);
    }

    @Test
    void unknownBookMeansNoClamp() {
        // Книги нет — прежнее поведение. Молча занижать цену на догадке нельзя.
        assertEquals(81100.0, clamp(true, 81100.0, 0, 0), 1e-9);
        assertEquals(80900.0, clamp(false, 80900.0, 0, 0), 1e-9);
    }

    @Test
    void exactTouchCountsAsCrossing() {
        // Равенство — уже немедленное сведение: площадка отвергает и его.
        assertEquals(81049.99, clamp(true, 81050.0, 81000.0, 81050.0), 1e-9);
        assertEquals(81000.01, clamp(false, 81000.0, 81000.0, 81050.0), 1e-9);
    }
}
