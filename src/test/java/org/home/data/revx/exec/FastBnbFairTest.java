package org.home.data.revx.exec;

import org.home.data.revx.sim.BookView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Быстрая опора пилота ADA: цена ADAUSDT ÷ USDCUSDT, свежесть, ход для правила рывка, и
 * книга без своих заявок для «встать за лучшим чужим аском».
 */
class FastBnbFairTest {

    private static FastBnbFair fair(BnbStream s) {
        // Базы стенда нет: книга вернётся пустой, опоре это не мешает.
        return new FastBnbFair(s, "ADA/USDC", "ADAUSDT", "USDCUSDT", "нет-такой-базы.db");
    }

    @Test
    void priceIsAdaUsdtDividedByUsdcUsdt() throws Exception {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair f = fair(s);
        s.handle("{\"stream\":\"adausdt@bookTicker\",\"data\":{\"u\":1,\"s\":\"ADAUSDT\",\"b\":\"0.2400\","
                + "\"B\":\"100\",\"a\":\"0.2402\",\"A\":\"100\"}}");
        assertFalse(f.price() > 0, "без курса USDC/USDT цены нет — нельзя котировать по USDT как по USDC");
        s.handle("{\"stream\":\"usdcusdt@bookTicker\",\"data\":{\"u\":2,\"s\":\"USDCUSDT\",\"b\":\"1.0007\","
                + "\"B\":\"1\",\"a\":\"1.0009\",\"A\":\"1\"}}");
        assertEquals(0.2401 / 1.0008, f.price(), 1e-12);
        StandReader.Fair q = f.latest("ADA", 30_000);
        assertTrue(q.quotable());
        assertEquals(0.2401 / 1.0008, q.price(), 1e-12);
    }

    @Test
    void silentBinanceIsNotQuotableButKeepsLastPrice() {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair f = fair(s);
        long old = System.currentTimeMillis() - FastBnbFair.MAX_AGE_MS - 1_000;
        s.inject("USDCUSDT", 1.0, 1.0, old);
        s.inject("ADAUSDT", 0.24, 0.24, old);      // сессия молчит: ни одного сообщения 4 с
        StandReader.Fair q = f.latest("ADA", 30_000);
        assertFalse(q.quotable(), "старая цена — котировщик обязан отвести заявки");
        assertTrue(q.pausedReason().contains("молчит"));
        assertEquals(0.24, q.price(), 1e-12, "последняя цена нужна для отвода на 10%");
    }

    /**
     * Сухой прогон 14.10: {@code bookTicker} шлёт только изменения верха, курс USDCUSDT
     * стоит секундами при живой сессии — это не сбой (было 156 ложных отводов за час).
     */
    @Test
    void quietLegWithLiveSessionIsQuotable() {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair f = fair(s);
        long now = System.currentTimeMillis();
        s.inject("USDCUSDT", 1.0, 1.0, now - 20_000);
        s.inject("ADAUSDT", 0.24, 0.24, now);
        assertTrue(f.latest("ADA", 30_000).quotable(), "курс не менялся 20 с, сессия жива");

        s.inject("USDCUSDT", 1.0, 1.0, now - FastBnbFair.LEG_MAX_AGE_MS - 1_000);
        StandReader.Fair q = f.latest("ADA", 30_000);
        assertFalse(q.quotable(), "пара не менялась дольше минуты — подписка отвалилась");
        assertTrue(q.pausedReason().contains("не менялась"), q.pausedReason());
    }

    @Test
    void noPriceAtAllIsNotQuotable() {
        FastBnbFair f = fair(new BnbStream("ADAUSDT", "USDCUSDT"));
        StandReader.Fair q = f.latest("ADA", 30_000);
        assertFalse(q.quotable());
        assertFalse(q.price() > 0);
    }

    @Test
    void movesMeasureDropFromHighAndRiseFromLow() {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair f = fair(s);
        long now = System.currentTimeMillis();
        s.inject("USDCUSDT", 1.0, 1.0, now);
        s.inject("ADAUSDT", 1.0, 1.0, now);
        s.inject("ADAUSDT", 0.998, 0.998, now);     // −20 б.п. от максимума
        double[] mv = f.moves(System.currentTimeMillis(), 1_000);
        assertEquals(20.0, mv[0], 1e-6, "падение от максимума окна — опасно для бида");
        assertEquals(0.0, mv[1], 1e-6);
        s.inject("ADAUSDT", 1.001, 1.001, now);     // отскок: от минимума +30.06 б.п.
        mv = f.moves(System.currentTimeMillis(), 1_000);
        assertEquals(0.0, mv[0], 1e-6);
        assertEquals((1.001 / 0.998 - 1) * 1e4, mv[1], 1e-6);
    }

    @Test
    void ownOrderIsRemovedFromBook() {
        // Наш аск 0.2410 на 41.5 ADA — единственный на лучшей цене; чужой на 0.2412.
        List<BookView.Level> asks = List.of(new BookView.Level(0.2410, 41.5), new BookView.Level(0.2412, 1000));
        List<BookView.Level> other = QuoteLoop.withoutOwn(asks, List.of(new double[]{0.2410, 41.5}));
        assertEquals(1, other.size(), "без вычета бот прилипал бы к собственной цене");
        assertEquals(0.2412, other.get(0).price(), 1e-12);
        // Рядом с нами на той же цене стоит чужой — уровень остаётся с его объёмом.
        other = QuoteLoop.withoutOwn(List.of(new BookView.Level(0.2410, 1041.5)), List.of(new double[]{0.2410, 41.5}));
        assertEquals(1000, other.get(0).qty(), 1e-9);
        // Округление количества площадкой: остаток в тысячную нашего — это мы сами.
        other = QuoteLoop.withoutOwn(List.of(new BookView.Level(0.2410, 41.5004)), List.of(new double[]{0.2410, 41.5}));
        assertTrue(other.isEmpty());
    }

    @Test
    void unknownBookIsNull() {
        assertNull(fair(new BnbStream("ADAUSDT")).bookNow(System.currentTimeMillis()));
    }
}
