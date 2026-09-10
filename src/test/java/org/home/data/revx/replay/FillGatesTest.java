package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Счётчики отсева: куда девается принт, не ставший нашим исполнением.
 *
 * <h2>Зачем закреплять тестом</h2>
 *
 * Эти счётчики — прибор, по которому решается, чинить модель или расстановку.
 * Сломать его рефакторингом легче, чем заметить: числа останутся
 * правдоподобными, а вывод изменится на противоположный. Единственная защита —
 * <b>тождество</b>: метка у принта ровно одна, поэтому сумма всех граф обязана
 * равняться числу принтов на ленте. Каждый тест ниже проверяет и тождество, и
 * попадание в нужную графу.
 */
class FillGatesTest {

    private static final double LOT = 1.0;

    /** Биды 100/99/98, аски 102/103/104 по 1000 лотов. */
    private static BookView book() {
        return new BookView(
                List.of(new BookView.Level(100, 1000), new BookView.Level(99, 1000),
                        new BookView.Level(98, 1000)),
                List.of(new BookView.Level(102, 1000), new BookView.Level(103, 1000),
                        new BookView.Level(104, 1000)));
    }

    private static MarketData market(List<MarketTrade> trades) {
        return MarketData.of(trades, new long[]{0}, List.of(book()));
    }

    private static void sumMatchesPrints(MarketFillModel.Gates g) {
        long sum = g.noAggressor() + g.noOrderOnSide() + g.notReached()
                + g.invisible() + g.queueBlocked() + g.slotSpent() + g.noVolumeLeft() + g.taken();
        assertEquals(g.prints(), sum, "метка у принта одна: сумма граф = число принтов");
    }

    @Test
    void printOnTheOtherSideCountsAsNoOrder() {
        // У нас только покупка, а принт бьёт по аскам — заявки на этой стороне нет.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 103, LOT, Side.BUY))));
        List<FillModel.Resting> bid = List.of(new FillModel.Resting("b", true, 99, LOT, 0));
        m.advance(0, bid);
        m.advance(2000, bid);

        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.prints());
        assertEquals(1, g.noOrderOnSide());
        assertEquals(0, g.reached());
        sumMatchesPrints(g);
    }

    @Test
    void printThatFallsShortIsCountedWithItsDistance() {
        // Наш бид на 98, продавец отдал по 99 — не долетел на 1 пункт = ~101 б.п.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 99, LOT, Side.SELL))));
        List<FillModel.Resting> bid = List.of(new FillModel.Resting("b", true, 98, LOT, 0));
        m.advance(0, bid);
        m.advance(2000, bid);

        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.notReached());
        assertEquals(0, g.taken());
        assertEquals(1, g.missBp().size());
        assertEquals(102.0, g.missBp().get(0), 1.0, "недолёт считается в базисных пунктах");
        sumMatchesPrints(g);
    }

    @Test
    void reachedPrintIsCountedAsTaken() {
        // Бид ВНУТРИ спреда (101, лучше лучшего бида 100) — очереди перед ним нет.
        // Продавец отдал по 100.5, то есть за нашей ценой: перехват.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 100.5, LOT, Side.SELL))));
        List<FillModel.Resting> bid = List.of(new FillModel.Resting("b", true, 101, LOT, 0));
        m.advance(0, bid);
        m.placed(bid.get(0));
        List<FillModel.Filled> filled = m.advance(2000, bid);

        assertEquals(1, filled.size());
        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.taken());
        assertEquals(1, g.reached());
        assertTrue(g.missBp().isEmpty());
        sumMatchesPrints(g);
    }

    @Test
    void orderOutsideTheVisibleBookIsCountedAsInvisible() {
        // Бид на 50 — глубже пятого видимого уровня (98), принт до него дошёл.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 49, LOT, Side.SELL))));
        List<FillModel.Resting> deep = List.of(new FillModel.Resting("b", true, 50, LOT, 0));
        m.advance(0, deep);
        m.placed(deep.get(0));
        List<FillModel.Filled> filled = m.advance(2000, deep);

        assertEquals(0, filled.size());
        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.invisible());
        assertEquals(1, g.reached(), "«дошло» включает и отсеянные — это потолок, а не итог");
        sumMatchesPrints(g);
    }

    @Test
    void printBlockedByTheQueueIsCountedAsQueue() {
        // Бид на 98 стоит ВНУТРИ книги: впереди 1000 лотов на его же уровне.
        // Принт в один лот до нас доходит, но очередь не выбирает.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 97, LOT, Side.SELL))));
        List<FillModel.Resting> bid = List.of(new FillModel.Resting("b", true, 98, LOT, 0));
        m.advance(0, bid);
        m.placed(bid.get(0));
        List<FillModel.Filled> filled = m.advance(2000, bid);

        assertEquals(0, filled.size());
        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.queueBlocked());
        assertEquals(1, g.reached());
        assertEquals(0, g.taken());
        sumMatchesPrints(g);
    }

    @Test
    void burstOfPrintsInOneMillisecondIsNotACaseOfSlowRestock() {
        // ⚠️ Ради этого различения графа и заведена отдельно. Одна рыночная
        // заявка, разметающая книгу, приходит на ленту НЕСКОЛЬКИМИ принтами с
        // одной отметкой времени. Наш единственный лот берёт первый из них,
        // остальные попадают в «слот уже выбран» — но живой бот с тем же одним
        // лотом не взял бы их тоже. Отличить это от медленного восстановления
        // можно ТОЛЬКО по разрыву: ноль миллисекунд — пачка, полторы секунды —
        // задержка. На живом окне 09-10.09 медиана оказалась 0 мс.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 100.5, LOT, Side.SELL),
                new MarketTrade(1000, 100.0, LOT, Side.SELL),
                new MarketTrade(1000, 99.5, LOT, Side.SELL))));
        List<FillModel.Resting> bid = List.of(new FillModel.Resting("b", true, 101, LOT, 0));
        m.advance(0, bid);
        m.placed(bid.get(0));
        List<FillModel.Filled> filled = m.advance(2000, bid);

        assertEquals(1, filled.size(), "один лот берёт только первый принт пачки");
        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.taken());
        assertEquals(2, g.slotSpent());
        assertEquals(2, g.spentGapMs().size());
        assertTrue(g.spentGapMs().stream().allMatch(v -> v == 0.0),
                "у пачки разрыв нулевой — это НЕ задержка восстановления");
        sumMatchesPrints(g);
    }

    @Test
    void onePrintReachingTwoOfOurOrdersIsLabelledOnce() {
        // Обе покупки внутри спреда (101 и 100.5), принт по 100 достаёт обе.
        // Меток должно быть одна, а не две.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 100, 5 * LOT, Side.SELL))));
        List<FillModel.Resting> two = List.of(
                new FillModel.Resting("inner", true, 101, LOT, 0),
                new FillModel.Resting("outer", true, 100.5, LOT, 0));
        m.advance(0, two);
        two.forEach(m::placed);
        List<FillModel.Filled> filled = m.advance(2000, two);

        assertEquals(2, filled.size(), "объёма хватило обеим заявкам");
        MarketFillModel.Gates g = m.gates();
        assertEquals(1, g.prints());
        assertEquals(1, g.taken());
        sumMatchesPrints(g);
    }
}
