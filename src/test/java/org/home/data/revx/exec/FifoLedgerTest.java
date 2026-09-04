package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Книга партий FIFO. Числа взяты с живого бота A от 03–04.09.2026, чтобы тест
 * проверял ту же арифметику, которую потом читают в Telegram.
 */
class FifoLedgerTest {

    private static final double LOT = 0.0000125;

    @Test
    void buyThenSellHigherRealisesTheSpread() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0);
        l.add(2000, false, LOT, 80_100, 0);
        assertEquals(LOT * 100, l.realisedSince(0), 1e-12);
        assertEquals(0.0, l.position().qty(), 1e-15, "после закрытия остатка нет");
        assertEquals(1, l.closedSince(0));
    }

    @Test
    void fifoClosesTheOLDESTLotFirst() {
        // Две покупки по разной цене, одна продажа. FIFO обязан закрыть ПЕРВУЮ.
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0);
        l.add(2000, true, LOT, 81_000, 0);
        l.add(3000, false, LOT, 80_500, 0);
        assertEquals(LOT * 500, l.realisedSince(0), 1e-12,
                "закрыть должно партию по 80 000, а не по 81 000");
        FifoLedger.Position p = l.position();
        assertEquals(LOT, p.qty(), 1e-15);
        assertEquals(81_000, p.averagePrice(), 1e-9, "в остатке дорогая партия");
    }

    @Test
    void partialSellSplitsTheLot() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0);
        l.add(2000, false, LOT / 2, 80_200, 0);
        assertEquals(LOT / 2 * 200, l.realisedSince(0), 1e-12);
        FifoLedger.Position p = l.position();
        assertEquals(LOT / 2, p.qty(), 1e-15, "половина партии осталась");
        assertEquals(80_000, p.averagePrice(), 1e-9, "цена входа у остатка прежняя");
        assertEquals(1, p.lots());
    }

    @Test
    void realisedIsDatedByTheCLOSINGtrade() {
        // Куплено давно, продано только что: «реализовано за час» обязано это увидеть.
        FifoLedger l = new FifoLedger();
        l.add(1_000_000, true, LOT, 80_000, 0);
        l.add(9_000_000, false, LOT, 80_400, 0);
        assertEquals(0.0, l.realisedSince(9_500_000), 1e-12, "после закрытия окна — ноль");
        assertEquals(LOT * 400, l.realisedSince(8_000_000), 1e-12,
                "окно, накрывающее ПРОДАЖУ, обязано увидеть всю прибыль");
        assertEquals(LOT * 400, l.realisedSince(2_000_000), 1e-12,
                "окно, начавшееся ПОСЛЕ покупки, видит то же самое");
    }

    @Test
    void unrealisedMarksTheRemainderAtCurrentPrice() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0);
        l.add(2000, true, LOT, 80_400, 0);
        FifoLedger.Position p = l.position();
        assertEquals(2 * LOT, p.qty(), 1e-15);
        assertEquals(80_200, p.averagePrice(), 1e-9);
        assertEquals(2 * LOT * 800, p.unrealised(81_000), 1e-12, "рынок выше входа");
        assertEquals(-2 * LOT * 200, p.unrealised(80_000), 1e-12, "рынок ниже входа");
        assertEquals(0.0, p.unrealised(0), 1e-15, "без цены переоценки нет");
    }

    @Test
    void sellingMoreThanHeldOpensAShortLot() {
        // Спот-бот в шорт уходить не должен, но если позиция разъехалась с
        // площадкой — сделку надо УВИДЕТЬ, а не потерять.
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0);
        l.add(2000, false, 2 * LOT, 80_300, 0);
        assertEquals(LOT * 300, l.realisedSince(0), 1e-12, "первая половина закрыла лонг");
        FifoLedger.Position p = l.position();
        assertTrue(p.qty() < 0, "остаток обязан быть коротким: " + p.qty());
        assertEquals(-LOT, p.qty(), 1e-15);
        assertEquals(80_300, p.averagePrice(), 1e-9);
        // Короткая партия дорожает, когда цена ПАДАЕТ.
        assertEquals(LOT * 300, p.unrealised(80_000), 1e-12);
    }

    @Test
    void shortLotIsClosedByALaterBuy() {
        FifoLedger l = new FifoLedger();
        l.add(1000, false, LOT, 80_500, 0);
        l.add(2000, true, LOT, 80_100, 0);
        assertEquals(LOT * 400, l.realisedSince(0), 1e-12, "шорт закрыт дешевле — это прибыль");
        assertEquals(0.0, l.position().qty(), 1e-15);
    }

    @Test
    void feesAreAccumulatedSeparately() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, LOT, 80_000, 0.001);
        l.add(2000, false, LOT, 80_100, 0.002);
        assertEquals(0.003, l.fees(), 1e-12);
        // Комиссии НЕ вычитаются из реализованного молча: это отдельная строка,
        // иначе «захват спреда» перестанет сходиться с моделью.
        assertEquals(LOT * 100, l.realisedSince(0), 1e-12);
    }
}
