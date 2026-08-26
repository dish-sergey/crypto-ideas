package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Учёт ПЕРЕХВАТА принтов (док. 79 §2, поправка стенда).
 *
 * Перехват — единственное реально связывающее допущение модели на этой площадке:
 * принт, прошедший по цене хуже нашей котировки, засчитывается как доставшийся
 * нам. Тест проверяет, что счётчик различает перехват и исполнение ровно по
 * нашей цене, и что дальность считается в правильную сторону для обеих сторон.
 */
class InterceptTest {

    private static final ExecutionModel.Limits LIMITS = new ExecutionModel.Limits(1e-8, 1e-9);

    /** Книга с широким спредом: бид 99, аск 101 — котировка внутри неё улучшает книгу. */
    private static BookView wideBook() {
        return BookView.of(new double[][]{{99.0, 10.0}}, new double[][]{{101.0, 10.0}});
    }

    @Test
    void принтПоНашейЦенеПерехватомНеСчитается() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.0, 1.0, wideBook(), 0);
        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(1_000, 100.0, 1.0)), 100.0);

        assertEquals(1, fills.size());
        assertEquals(0.0, model.stats().interceptedFillShare(), 1e-12);
    }

    /**
     * Принт прошёл по 99 — ниже нашего бида 100. Модель считает, что продавец
     * отдал бы нам. Это перехват, и дальность равна 100 б.п. от нашей цены.
     */
    @Test
    void принтНижеНашегоБидаЭтоПерехват() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.0, 1.0, wideBook(), 0);
        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(1_000, 99.0, 1.0)), 100.0);

        assertEquals(1, fills.size());
        assertEquals(1.0, model.stats().interceptedFillShare(), 1e-12);
        assertEquals(100.0, model.stats().interceptDistanceBp().get(0), 0.01);
    }

    /** Для продажи «хуже нашей цены» — это ВЫШЕ неё; перепутанный знак валит тест. */
    @Test
    void дляПродажиПерехватСчитаетсяВДругуюСторону() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.SELL, 100.0, 1.0, wideBook(), 0);
        List<Fill> fills = model.onWindow(List.of(MarketTrade.buy(1_000, 101.0, 1.0)), 100.0);

        assertEquals(1, fills.size());
        assertEquals(1.0, model.stats().interceptedFillShare(), 1e-12);
        assertEquals(100.0, model.stats().interceptDistanceBp().get(0), 0.01);
    }

    /** Котировка внутри широкого спреда очередь не встречает — очередь равна нулю. */
    @Test
    void внутриСпредаОчередиНет() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 100.0, 1.0, wideBook(), 0);
        model.observe();
        model.onWindow(List.of(MarketTrade.sell(1_000, 99.5, 1.0)), 100.0);

        assertEquals(1, model.stats().improvingWindows());
        assertEquals(0.0, model.stats().queueAtFill().get(0), 1e-12);
        assertTrue(model.stats().improvingFillShare() > 0.99);
    }

    /**
     * Совпали с существующим уровнем — очередь берётся из книги, и исполнение
     * приходит только после того, как она выбрана.
     */
    @Test
    void наСуществующемУровнеОчередьРаботает() {
        ExecutionModel model = new ExecutionModel(LIMITS);
        model.place(Side.BUY, 99.0, 1.0, wideBook(), 0);
        model.observe();

        // объёма 4 не хватает: перед нами 10
        assertTrue(model.onWindow(List.of(MarketTrade.sell(1_000, 99.0, 4.0)), 99.0).isEmpty());
        // ещё 7 — очередь выбрана, остаток достаётся нам
        List<Fill> fills = model.onWindow(List.of(MarketTrade.sell(2_000, 99.0, 7.0)), 99.0);
        assertEquals(1, fills.size());
        assertEquals(1, model.stats().joiningWindows());
        assertEquals(10.0, model.stats().queueAtFill().get(0), 1e-9);
    }
}
