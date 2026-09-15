package org.home.data.revx.exec;

import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ЗАПЕРТО, А ЗАЯВКИ НЕТ: арифметика четвёртого сторожа.
 *
 * <h2>Зачем</h2>
 *
 * Три прежних сторожа сверяли наши книги друг с другом. Ни один не смотрел на
 * то, ЧЕМ монета заперта, и эта беда пряталась дольше всех: 15.09.2026 на счёте
 * оказались заперты все ETH, BTC и SOL — площадка держала их в {@code reserved},
 * не показывая ни одной заявки. Пока так, {@code available} ноль и продать
 * нельзя; выглядит это как «нет потока», а не как поломка.
 *
 * Здесь закреплена сама арифметика: запертое минус то, что объясняют ВИДИМЫЕ
 * продажи по нашей паре. Чужие пары и покупки в счёт не идут — покупка запирает
 * котируемую валюту, а не базовую.
 */
class FrozenReserveTest {

    private static ActiveOrder order(String symbol, Side side, double size) {
        return new ActiveOrder("id-" + Math.random(), "cccccccc-x", symbol, side,
                2500.0, size, 0, "new", 0);
    }

    /** То же правило, что в {@code QuoteLoop.checkFrozen}. */
    private static double frozen(double reserved, String symbol, List<ActiveOrder> orders) {
        double visible = 0;
        for (ActiveOrder o : orders) {
            if (o.side() == Side.SELL && ActiveOrder.normalize(symbol).equals(o.symbol())) {
                visible += o.size();
            }
        }
        return reserved - visible;
    }

    @Test
    void sellOrdersExplainTheReserve() {
        List<ActiveOrder> orders = List.of(
                order("ETH/USDC", Side.SELL, 0.00120795),
                order("ETH/USDC", Side.SELL, 0.00040265));
        assertEquals(0.0, frozen(0.0016106, "ETH/USDC", orders), 1e-12);
    }

    @Test
    void reserveWithoutAnyOrderIsFrozen() {
        assertEquals(0.0016106, frozen(0.0016106, "ETH/USDC", List.of()), 1e-12,
                "заперто всё, а заявок нет — это и есть беда 15.09.2026");
    }

    @Test
    void buyOrdersDoNotLockTheBaseCurrency() {
        // Покупка запирает USDC, а не ETH: считать её здесь значит проглядеть
        // залипший резерв ровно на размер собственных бидов.
        List<ActiveOrder> orders = List.of(order("ETH/USDC", Side.BUY, 0.00120795));
        assertEquals(0.0016106, frozen(0.0016106, "ETH/USDC", orders), 1e-12);
    }

    @Test
    void foreignPairIsNotCounted() {
        List<ActiveOrder> orders = List.of(order("BTC/USDC", Side.SELL, 0.00120795));
        assertEquals(0.0016106, frozen(0.0016106, "ETH/USDC", orders), 1e-12);
    }

    @Test
    void neighbourBotSellCountsBecauseAccountIsShared() {
        // Счёт общий: продажа соседа по ТОЙ ЖЕ паре честно объясняет резерв,
        // даже если метка чужая. Иначе сторож кричал бы на каждого соседа.
        ActiveOrder neighbour = new ActiveOrder("id", "dddddddd-x", "ETH/USDC", Side.SELL,
                2500.0, 0.0016106, 0, "new", 0);
        assertEquals(0.0, frozen(0.0016106, "ETH/USDC", List.of(neighbour)), 1e-12);
    }

    @Test
    void smallMismatchIsBelowTheLotThreshold() {
        // Мгновенное расхождение штатно: остатки и список заявок читаются
        // разными запросами, между ними успевает пройти замена.
        double lot = 0.00040265;
        double mismatch = frozen(0.0016106 + 0.00000003, "ETH/USDC", List.of(
                order("ETH/USDC", Side.SELL, 0.0016106)));
        assertTrue(mismatch < lot, "пыль ниже лота тревогой не считается");
    }
}
