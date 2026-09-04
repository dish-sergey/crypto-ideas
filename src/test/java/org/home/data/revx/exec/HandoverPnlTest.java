package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Передача инвентаря и кассы НЕ должна попадать в торговый результат.
 *
 * 04.09.2026 бот B после {@code /release} отчитался об убытке −6.77 USDC,
 * которого не было: база для кассы в расчёте была захардкожена нулём, поэтому
 * захват денег выглядел прибылью, а освобождение — убытком.
 */
class HandoverPnlTest {

    private static final double FAIR = 80_000;
    private static final double LOT = 0.0000125;

    @Test
    void claimingCashIsNotProfit() {
        // До захвата: пусто.
        double cash = 0, seedCash = 0, inv = 0, seedInv = 0;
        assertEquals(0.0, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12);
        // Взяли 18.27 USDC — это передача, а не заработок.
        cash += 18.27;
        seedCash += 18.27;
        assertEquals(0.0, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12,
                "захват кассы обязан оставить результат нулевым");
    }

    @Test
    void claimingLotsIsNotProfitEither() {
        double cash = 0, seedCash = 0, inv = 0, seedInv = 0;
        inv += 8 * LOT;
        seedInv += 8 * LOT;
        assertEquals(0.0, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12);
    }

    @Test
    void releasingEverythingIsNotALoss() {
        // Взяли монеты и деньги, ничего не наторговали, отдали обратно.
        double cash = 14.19, seedCash = 14.19, inv = 6 * LOT, seedInv = 6 * LOT;
        assertEquals(0.0, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12);
        cash -= 14.19;
        seedCash -= 14.19;
        inv -= 6 * LOT;
        seedInv -= 6 * LOT;
        assertEquals(0.0, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12,
                "освобождение обязано оставить результат нулевым, а не показать убыток");
    }

    @Test
    void realTradingStillShowsUp() {
        // Взяли денег, купили лот дешевле, продали дороже — вот это прибыль.
        double cash = 20, seedCash = 20, inv = 0, seedInv = 0;
        cash -= LOT * 79_000;             // покупка
        inv += LOT;
        cash += LOT * 80_000;             // продажа дороже
        inv -= LOT;
        double pnl = QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR);
        assertEquals(LOT * 1000, pnl, 1e-12, "заработок обязан остаться видимым");
        assertTrue(pnl > 0);
    }

    @Test
    void unrealisedInventoryIsValuedAtFair() {
        // Купили и держим: результат — переоценка остатка, и она видна.
        double cash = 20, seedCash = 20, inv = 0, seedInv = 0;
        cash -= LOT * 79_000;
        inv += LOT;
        assertEquals(LOT * 1000, QuoteLoop.tradingPnl(cash, seedCash, inv, seedInv, FAIR), 1e-12);
    }
}
