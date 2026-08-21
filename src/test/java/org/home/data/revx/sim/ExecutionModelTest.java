package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Критерии приёмки модели исполнения (ТЗ §7).
 *
 * Каждый тест здесь запрещает конкретный способ обмануть себя из ТЗ §4.6:
 * исполнение по касанию, исполнение всего объёма одной сделкой, исполнение
 * невидимой заявки, уменьшение очереди от неподтверждённых событий.
 */
class ExecutionModelTest {

    private static final ExecutionModel.Limits LIMITS = ExecutionModel.Limits.of(0.001);

    /** Книга: бид 100 (объём 5), аск 101 (объём 5), по пять уровней с шагом 1. */
    private static BookView book() {
        return BookView.of(
                new double[][]{{100, 5}, {99, 5}, {98, 5}, {97, 5}, {96, 5}},
                new double[][]{{101, 5}, {102, 5}, {103, 5}, {104, 5}, {105, 5}});
    }

    @Test
    void noFillWhenVolumeSmallerThanQueueAhead() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100, 1, book(), 0);      // встаём за объёмом 5 на уровне 100

        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(10, 100, 3)), 100.5);

        assertTrue(fills.isEmpty(), "прошло 3 при очереди 5 — исполнения быть не может");
        assertEquals(2, model.queueAhead(Side.BUY), 1e-9, "очередь должна уменьшиться на 3");
    }

    @Test
    void partialFillWhenVolumeCoversOnlyPart() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100, 4, book(), 0);      // очередь 5, наш объём 4

        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(10, 100, 6)), 100.5);

        assertEquals(1, fills.size());
        assertEquals(1.0, fills.get(0).qty(), 1e-9, "после очереди 5 от сделки 6 нам осталась 1");
        assertEquals(3.0, model.remaining(Side.BUY), 1e-9, "остаток заявки продолжает стоять");
    }

    @Test
    void improvingBookMeansEmptyQueue() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.5, 2, book(), 0);    // лучше лучшего бида — новый уровень

        assertEquals(0, model.queueAhead(Side.BUY), 1e-9);
        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(10, 100.5, 1.5)), 100.7);
        assertEquals(1.5, fills.get(0).qty(), 1e-9, "перед нами никого — исполняемся сразу");
    }

    @Test
    void orderBeyondVisibleDepthNeverFills() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 90, 1, book(), 0);       // глубже пятого видимого уровня (96)

        assertFalse(model.visible(Side.BUY));
        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(10, 89, 100)), 95);

        assertTrue(fills.isEmpty(), "про невидимую заявку мы не знаем даже, кто перед ней");
    }

    @Test
    void orderBecomesFillableOnlyAfterItEntersVisiblePart() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 95, 1, book(), 0);
        assertFalse(model.visible(Side.BUY), "95 глубже видимых 96");

        // рынок сдвинулся вниз: наш уровень попал в видимую часть и оказался лучшим
        BookView shifted = BookView.of(
                new double[][]{{94, 5}, {93, 5}, {92, 5}, {91, 5}, {90, 5}},
                new double[][]{{96, 5}, {97, 5}, {98, 5}, {99, 5}, {100, 5}});
        model.refresh(shifted);

        assertTrue(model.visible(Side.BUY));
        assertEquals(0, model.queueAhead(Side.BUY), 1e-9, "мы теперь лучший бид — очередь пуста");
        assertEquals(1, model.onWindow(List.of(MarketTrade.sell(10, 95, 1)), 95).size());
    }

    @Test
    void tradeWithUnknownAggressorCausesNoFill() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.5, 1, book(), 0);

        List<Fill> fills = model.onWindow(List.of(MarketTrade.unknown(10, 100.5, 5)), 100.6);

        assertTrue(fills.isEmpty(), "при неоднозначности выбирается вариант против нас");
        assertEquals(0, model.queueAhead(Side.BUY), 1e-9, "и очередь такая сделка тоже не двигает");
    }

    @Test
    void tradeOnTheWrongSideDoesNotFillUs() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.5, 1, book(), 0);

        // агрессивная ПОКУПКА бьёт по аскам, наш бид она не трогает
        List<Fill> fills = model.onWindow(List.of(MarketTrade.buy(10, 100.5, 5)), 100.6);

        assertTrue(fills.isEmpty());
    }

    @Test
    void whenBothSidesWouldFillOnlyUnfavourableOneDoes() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.5, 1, book(), 0);
        model.place(Side.SELL, 100.6, 1, book(), 0);

        // в одном окне: и продавец добил до нашего бида, и покупатель взял наш аск.
        // Цена ушла ВНИЗ (fair 99.5) — невыгодна нам покупка, она и должна остаться.
        List<Fill> fills = model.onWindow(List.of(
                MarketTrade.sell(10, 100.5, 1),
                MarketTrade.buy(11, 100.6, 1)), 99.5);

        assertEquals(1, fills.size(), "обе стороны в одном окне исполниться не могут");
        assertEquals(Side.BUY, fills.get(0).side(), "остаётся та сторона, что хуже для нас");
    }

    @Test
    void whenPriceRosePreferredFillIsTheSell() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.5, 1, book(), 0);
        model.place(Side.SELL, 100.6, 1, book(), 0);

        // симметричный случай: цена ушла ВВЕРХ — невыгодна продажа
        List<Fill> fills = model.onWindow(List.of(
                MarketTrade.sell(10, 100.5, 1),
                MarketTrade.buy(11, 100.6, 1)), 102.0);

        assertEquals(1, fills.size());
        assertEquals(Side.SELL, fills.get(0).side());
    }

    @Test
    void fillQuantityIsRoundedDownToBaseStep() {
        ExecutionModel model = new ExecutionModel(new ExecutionModel.Limits(0.5, 1e-9));
        model.place(Side.BUY, 100.5, 10, book(), 0);

        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(10, 100.5, 1.7)), 100.5);

        assertEquals(1.5, fills.get(0).qty(), 1e-9, "1.7 при шаге 0.5 — это 1.5, а не 1.7 и не 2.0");
    }

    @Test
    void queueDoesNotShrinkWithoutTrades() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100, 1, book(), 0);
        double before = model.queueAhead(Side.BUY);

        // пустое окно: чужие заявки могли отмениться, но мы этого не видим (ТЗ §4.3)
        model.onWindow(List.of(), 100);

        assertEquals(before, model.queueAhead(Side.BUY), 1e-9);
    }
}
