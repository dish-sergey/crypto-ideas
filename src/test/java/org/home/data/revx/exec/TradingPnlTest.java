package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Предохранитель по торговому убытку. Числа — из трёх ложных срабатываний
 * 30.08–01.09.2026 (остатки взяты из журнала исполнителя дословно).
 *
 * Порог тогда стоял 1 USDC при заявке в ~0.98 USDC, поэтому ошибка на размер
 * резерва означала не «неточность», а гарантированную остановку при каждом
 * висящем биде.
 */
class TradingPnlTest {

    private static final double START_QUOTE = 41.877007;   // USDC total на старте
    private static final double FAIR = 78_900.0;
    private static final double LIMIT = ExecLimits.MAX_TRADING_LOSS_USDC;

    @Test
    void restingBidIsNotALoss() {
        // 30.08 20:31:53: счёт байт в байт стартовый, из него 0.983889 в резерве
        // под нашим же бидом. Правильный ответ — ноль.
        double pnl = QuoteLoop.tradingPnl(41.877007, START_QUOTE, 0.0, 0.0, FAIR);
        assertEquals(0.0, pnl, 1e-9, "деньги в резерве — это наши деньги");
        assertTrue(pnl > -LIMIT, "предохранитель не должен срабатывать");
    }

    @Test
    void availableInsteadOfTotalManufacturesTheLoss() {
        // Ровно то, что делала первая версия: available вместо total.
        // Два зарезервированных бида — и «убыток» превышает предел вдвое.
        double wrong = QuoteLoop.tradingPnl(38.920851, START_QUOTE, 0.0, 0.0, FAIR);
        assertEquals(-2.956156, wrong, 1e-6);
        assertTrue(wrong < -LIMIT, "именно так и получались ложные остановки");
    }

    @Test
    void inventoryIsValuedAtFair() {
        // 01.09 06:26:10: USDC total 40.890380 и 0.0000125 BTC.
        // Купленное на 0.9866 USDC стоит ровно столько же — P&L около нуля.
        double pnl = QuoteLoop.tradingPnl(40.890380, START_QUOTE, 0.0000125, 0.0, 78_930.0);
        assertEquals(0.0, pnl, 0.01, "покупка по справедливой цене P&L не меняет");
    }

    @Test
    void realLossIsStillCaught() {
        // Предохранитель обязан остаться рабочим: настоящая потеря денег видна.
        double pnl = QuoteLoop.tradingPnl(START_QUOTE - 1.5, START_QUOTE, 0.0, 0.0, FAIR);
        assertTrue(pnl < -LIMIT, "реальный убыток должен останавливать котирование");
    }
}
