package org.home.data.revx.exec;

import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Потолок продажи по СВОЕЙ позиции (найдено 04.09.2026).
 *
 * Три бота делят один счёт площадки, и {@code affordable} для продажи смотрит на
 * общий остаток. Без отдельного потолка заявка одного исполняется против
 * инвентаря, набранного другим, и бот уходит в шорт, которого на споте быть не
 * может.
 *
 * Так и случилось: бот A весь день 03.09 простоял в шорте на 7–13 лотов и прошёл
 * в нём ралли 77 000 → 81 400. Продаж 201 против 192 покупок при нулевой
 * затравке.
 */
class OwnPositionCapTest {

    private static final double LOT = 0.0000125;
    private static final double PRICE = 80_000;

    /** Та же арифметика, что в {@code QuoteLoop.sizeFor}. */
    private static double sellSize(double want, double inventory, double sharedBalance,
                                   double ownSize) {
        double affordable = QuoteLoop.affordable(Side.SELL, PRICE, sharedBalance, 0, ownSize, PRICE);
        double cap = Math.max(0, inventory);
        return Math.min(want, Math.min(affordable, cap));
    }

    @Test
    void cannotSellMoreThanOwnPositionEvenWhenAccountIsRich() {
        // На счёте лежит инвентарь ТРЁХ ботов, у нас своего — один лот.
        double size = sellSize(LOT, LOT, 20 * LOT, 0);
        assertEquals(LOT, size, 1e-15, "продаём ровно свой лот, а не чужие двадцать");
    }

    @Test
    void emptyOwnPositionMeansNoAsk() {
        // Ровно тот случай, который увёл бота A в шорт: своей позиции нет,
        // а на общем счёте полно чужого.
        double size = sellSize(LOT, 0, 20 * LOT, 0);
        assertEquals(0.0, size, 1e-15, "без своей позиции аск ставить нечем");
    }

    @Test
    void negativePositionNeverProducesANegativeSize() {
        // Позиция уже разъехалась в минус — из этого не должно родиться
        // отрицательного размера заявки.
        double size = sellSize(LOT, -3 * LOT, 20 * LOT, 0);
        assertEquals(0.0, size, 1e-15);
        assertTrue(size >= 0);
    }

    @Test
    void sharedBalanceStillLimitsWhenItIsSmallerThanOwnPosition() {
        // Обратный случай: наша позиция больше, чем реально доступно на счёте
        // (остальное в резерве под чужими заявками). Потолок берёт меньшее.
        double size = sellSize(2 * LOT, 5 * LOT, LOT, 0);
        assertEquals(LOT, size, 1e-15, "доступное на счёте всё ещё ограничивает");
    }

    @Test
    void ownRestingAskDoesNotInflateThePosition() {
        // Своя стоящая заявка расширяет ДОСТУПНОЕ (замена возвращает резерв),
        // но позицию не увеличивает: продать сверх позиции всё равно нельзя.
        double size = sellSize(3 * LOT, LOT, 0, 2 * LOT);
        assertEquals(LOT, size, 1e-15);
    }
}
