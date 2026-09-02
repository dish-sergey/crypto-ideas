package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контроли C3 и C4 (док. 132 §1). Оба обязаны отличаться от стратегии ровно
 * одним, поэтому тест проверяет не результат, а ИЗОЛЯЦИЮ: что осталось прежним
 * и что именно выключено.
 */
class FairSourceControlsTest {

    private static final double D = 0.001;                   // отступ 10 б.п.
    private static final double K = 0.001;                   // скос 0.10%
    private static final double CAP = 20.0;

    private static Quoter.Params params() {
        return new Quoter.Params(D, 1.0, CAP, K, 0.00005, 0.0);
    }

    @Test
    void noSkewQuotesSymmetricallyAtAnyInventory() {
        QuotePolicy c3 = QuotePolicy.noSkew(params());
        // У стратегии при половине потолка котировки перекошены; у C3 — нет.
        Quoter.Quotes strategy = new Quoter(params()).quotes(100.0, CAP / 2);
        assertTrue(100.0 - strategy.bid() != strategy.ask() - 100.0,
                "у стратегии со скосом расстояния обязаны различаться");

        Quoter.Quotes control = c3.quotes(100.0, CAP / 2);
        assertEquals(100.0 * (1 - D), control.bid(), 1e-12);
        assertEquals(100.0 * (1 + D), control.ask(), 1e-12);
    }

    @Test
    void noSkewKeepsInventoryBoundaries() {
        // Без границ контроль ушёл бы в шорт на споте, и сравнивать было бы нечего.
        QuotePolicy c3 = QuotePolicy.noSkew(params());
        assertNull(c3.quotes(100.0, 0.0).ask(), "с пустым инвентарём продавать нечего");
        assertNull(c3.quotes(100.0, CAP).bid(), "на потолке покупать нельзя");
    }

    @Test
    void ownBookMidIgnoresBasketFairPrice() {
        QuotePolicy c4 = QuotePolicy.ownBookMid(params());
        // Книга стоит на 99/101, а корзинная справедливая цена — 100.
        c4.onWindow(BookView.of(new double[][]{{99, 1}}, new double[][]{{101, 1}}));
        Quoter.Quotes q = c4.quotes(100.0, CAP / 2);
        assertEquals(100.0 * (1 - D), q.bid(), 1e-12, "середина книги здесь тоже 100");

        // А теперь книга уехала, справедливая цена — нет. C4 обязан пойти за книгой.
        c4.onWindow(BookView.of(new double[][]{{109, 1}}, new double[][]{{111, 1}}));
        Quoter.Quotes moved = c4.quotes(100.0, CAP / 2);
        assertEquals(110.0 * (1 - D), moved.bid(), 1e-12,
                "C4 котирует от своей книги, а не от корзины");
    }

    @Test
    void ownBookMidStandsAsideWithoutABook() {
        QuotePolicy c4 = QuotePolicy.ownBookMid(params());
        c4.onWindow(new BookView(List.of(), List.of()));
        Quoter.Quotes q = c4.quotes(100.0, CAP / 2);
        assertNull(q.bid());
        assertNull(q.ask());
    }
}
