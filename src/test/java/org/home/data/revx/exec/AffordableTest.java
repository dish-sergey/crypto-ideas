package org.home.data.revx.exec;

import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сколько можно поставить на сторону. Числа взяты из аварии в ночь на
 * 29.08.2026: остатки площадки были {@code BTC available 0 / reserved 0.0000125
 * / total 0.0000125}, счётчик позиции показывал полный лот, и цикл раз в секунду
 * просил продать то, чего у него не было — 766 отказов подряд и весь суточный
 * лимит постановок.
 */
class AffordableTest {

    private static final double LOT = 0.0000125;
    private static final double PRICE = 78_500.0;

    @Test
    void reservedInventoryIsNotSellable() {
        // Ровно ночная авария: позиция есть, но она вся под ЧУЖОЙ заявкой,
        // своей заявки нет — значит ставить нечего.
        double size = QuoteLoop.affordable(Side.SELL, PRICE, 0.0, 40.0, 0.0, 0.0);
        assertEquals(0.0, size, 1e-12, "на зарезервированное поставить нельзя");
    }

    @Test
    void ownRestingOrderWidensWhatWeCanPlace() {
        // Своя заявка держит тот же резерв, но замена его возвращает. Если этого
        // не учесть, полностью зарезервированную заявку станет нельзя двигать.
        double size = QuoteLoop.affordable(Side.SELL, PRICE, 0.0, 40.0, LOT, PRICE);
        assertEquals(LOT, size, 1e-12);
    }

    @Test
    void buySideCountsAvailableQuote() {
        double free = QuoteLoop.affordable(Side.BUY, PRICE, 0.0, 40.0, 0.0, 0.0);
        assertEquals(40.0 / PRICE, free, 1e-12);

        // Стоящий бид морозит свой номинал; замена его освобождает.
        double withOwn = QuoteLoop.affordable(Side.BUY, PRICE, 0.0, 39.02, LOT, PRICE);
        assertTrue(withOwn > free, "своя заявка расширяет доступное");
        assertEquals((39.02 + LOT * PRICE) / PRICE, withOwn, 1e-12);
    }

    /**
     * Своя надкушенная заявка возвращает при замене не больше неисполненного остатка — в сторону «поставить
     * меньше» (14.11.2026). ⚠️ Отказы c в тот день шли не отсюда, а от «пыли в заявку» мимо доступного — см.
     * {@code HardCapTest}. Числа взяты из того случая.
     */
    @Test
    void partiallyFilledOwnOrderWidensOnlyByItsRemainder() {
        double own = QuoteLoop.ownRemaining(0.01945439, 0.00016178);
        assertEquals(0.01929261, own, 1e-12);
        assertEquals(0.01929261, QuoteLoop.affordable(Side.SELL, 1830.0, 0.0, 0.0, own, 1830.0), 1e-12);
        assertEquals(0.0, QuoteLoop.ownRemaining(0.01, 0.02), 1e-12);
    }

    @Test
    void neverNegativeAndNeverDividesByZeroPrice() {
        assertEquals(0.0, QuoteLoop.affordable(Side.SELL, PRICE, -1.0, 40.0, 0.0, 0.0), 1e-12);
        assertEquals(0.0, QuoteLoop.affordable(Side.BUY, 0.0, 0.0, 40.0, 0.0, 0.0), 1e-12);
    }
}
