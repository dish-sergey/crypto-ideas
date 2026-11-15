package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Поток ликвидаций для живых (док. 246 §3): разбор лонгов как у стенда и относительный порог по времени. */
class LiqStateWriterTest {
    @Test
    void longsLikeStand() {
        assertEquals(100.0, LiqStateWriter.longUsd("bybit", "BTCUSDT", "BUY", 100, 1), 1e-9);
        assertEquals(0.0, LiqStateWriter.longUsd("bybit", "BTCUSDT", "SELL", 100, 1), 1e-9);
        assertEquals(1.0, LiqStateWriter.longUsd("okx", "BTC-USDT-SWAP", "SELL", 100, 1), 1e-9);    // контракт 0.01 BTC
        assertEquals(10.0, LiqStateWriter.longUsd("okx", "ETH-USDT-SWAP", "SELL", 100, 1), 1e-9);   // 0.1 ETH
        assertEquals(0.0, LiqStateWriter.longUsd("binance", "BTCUSDT", "SELL", 100, 1), 1e-9);
    }

    @Test
    void thresholdByTimeShare() {
        // окно 100 мс на отрезке [0, 10000): одна ликвидация $5 в t=0 (горит 100 мс = 1%), одна $3 в t=5000 (ещё 1%)
        long[] t = {0, 5000};
        double[] v = {5, 3};
        assertEquals(5.0, LiqStateWriter.thresholdOf(t, v, 0, 10_000, 100, 1.0), 1e-9);   // 1% времени — только $5
        assertEquals(3.0, LiqStateWriter.thresholdOf(t, v, 0, 10_000, 100, 2.0), 1e-9);   // 2% — обе ступени
    }
}
