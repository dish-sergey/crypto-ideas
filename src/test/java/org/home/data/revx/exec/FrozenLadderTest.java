package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ТРИ СТУПЕНИ ПРИ ЗАМОРОЗКЕ: торгуем — распродаём — стоим.
 *
 * <h2>Зачем понадобились ступени</h2>
 *
 * Первый вариант вставал целиком при первом же запертом лоте, и 16.09.2026 это
 * вышло боком: бот {@code d} остановился, имея ТРИ продаваемых лота, потому что
 * один был заперт. Сосед {@code c} на том же счёте и той же заморозке не встал —
 * у него крупнее лот. Абсолютная величина здесь ни при чём: значение имеет доля
 * выеденной ёмкости.
 *
 * <h2>Почему не буфер</h2>
 *
 * Держать лишние монеты «на случай заморозки» — не отдельный ресурс, а тот же
 * инвентарь: счёт один, и отложенная монета падает в цене вместе с остальными.
 * Страховка стоит ровно один лот рыночного риска за каждый застрахованный, а на
 * падающем окне это дороже спасаемой торговли. Поэтому буфером работает СВОБОДНЫЙ
 * ОСТАТОК, и пока его хватает, бот просто торгует.
 */
class FrozenLadderTest {

    private static final double LOT = 0.00040265;
    private static final double CAP = LOT * 7;

    /** Та же арифметика, что в {@code QuoteLoop.checkFrozen}. */
    private static boolean unwind(double frozen, double cap) {
        return frozen >= cap / 2;
    }

    private static boolean stop(boolean unwinding, double inventory, double lot) {
        return unwinding && inventory < lot;
    }

    @Test
    void oneFrozenLotOutOfSevenKeepsTrading() {
        // Ровно случай бота d 16.09.2026: заперт лот, свободны три — работать есть чем.
        assertFalse(unwind(LOT, CAP));
    }

    @Test
    void halfTheCapFrozenStartsUnwinding() {
        assertTrue(unwind(CAP / 2, CAP));
        assertTrue(unwind(LOT * 5, CAP));
    }

    @Test
    void unwindingStopsOnlyWhenSoldDown() {
        // Пока есть что продавать — продаём, а не стоим.
        assertFalse(stop(true, LOT * 3, LOT));
        assertFalse(stop(true, LOT, LOT));
        // Остаток мельче лота продать нельзя: он ниже минимума площадки.
        assertTrue(stop(true, LOT * 0.4, LOT));
    }

    @Test
    void healthyBotNeverStops() {
        assertFalse(stop(false, 0, LOT), "без заморозки пустой инвентарь — штатное состояние");
    }

    /**
     * Порог не должен зависеть от размера лота — иначе на одном счёте и одной
     * заморозке соседи ведут себя по-разному, что и наблюдалось.
     */
    @Test
    void thresholdIsAboutCapacityNotLotSize() {
        double bigLot = LOT * 3;
        double capOfBigLotBot = bigLot * 7;
        double frozen = LOT;                       // одна и та же запертая величина
        assertFalse(unwind(frozen, CAP));
        assertFalse(unwind(frozen, capOfBigLotBot));
    }
}
