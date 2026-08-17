package org.home.data.trade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор ответов Kraken + построение ордеров (на фейке, без сети/ключей). */
class KrakenFuturesExchangeTest {

    private static final String TICKERS =
            "{\"result\":\"success\",\"tickers\":[{\"symbol\":\"PF_XBTUSD\",\"markPrice\":63004.5,\"last\":63000}]}";
    private static final String OPENPOS =
            "{\"result\":\"success\",\"openPositions\":["
            + "{\"side\":\"short\",\"symbol\":\"pf_xbtusd\",\"price\":63000.0,\"size\":0.1},"
            + "{\"side\":\"long\",\"symbol\":\"pf_ethusd\",\"price\":3000.0,\"size\":0}]}";   // size 0 отфильтруется
    private static final String ACCOUNTS =
            "{\"result\":\"success\",\"accounts\":{\"flex\":{\"portfolioValue\":1234.5,\"availableMargin\":1000}}}";
    private static final String FILLED =
            "{\"result\":\"success\",\"sendStatus\":{\"order_id\":\"o1\",\"status\":\"placed\","
            + "\"orderEvents\":[{\"type\":\"EXECUTION\",\"price\":63000,\"amount\":0.1}]}}";

    @Test void parsesOpenPositions() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/openpositions", OPENPOS);
        List<Position> ps = new KrakenFuturesExchange(api).positions();
        assertEquals(1, ps.size(), "нулевой размер отфильтрован");
        assertEquals("PF_XBTUSD", ps.get(0).symbol());
        assertEquals(Side.SHORT, ps.get(0).side());
        assertEquals(0.1, ps.get(0).qty(), 1e-9);
        assertEquals(63000.0, ps.get(0).entryPx(), 1e-9);
    }

    @Test void parsesBalanceFromFlex() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/accounts", ACCOUNTS);
        assertEquals(1234.5, new KrakenFuturesExchange(api).balance(), 1e-9);
    }

    @Test void markReadsTickers() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/tickers", TICKERS);
        KrakenFuturesExchange ex = new KrakenFuturesExchange(api);
        assertEquals(63004.5, ex.mark("PF_XBTUSD"), 1e-9);
        assertEquals(0.0, ex.mark("PF_UNKNOWNUSD"), 1e-9);
    }

    @Test void openShortSellsAndParsesFill() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.postResponses.put("/api/v3/sendorder", FILLED);
        OrderResult r = new KrakenFuturesExchange(api).openShort("PF_XBTUSD", 0.1);
        assertTrue(r.isFilled());
        assertEquals(63000.0, r.fillPx(), 1e-9);
        assertEquals(0.1, r.fillQty(), 1e-9);
        assertEquals("o1", r.orderId());
        assertTrue(api.lastPostBody.contains("side=sell"));
        assertTrue(api.lastPostBody.contains("size=0.1"));
        assertFalse(api.lastPostBody.contains("reduceOnly"), "открытие — не reduceOnly");
    }

    @Test void closeShortBuysReduceOnly() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.postResponses.put("/api/v3/sendorder", FILLED);
        new KrakenFuturesExchange(api).closeShort("PF_XBTUSD", 0.1);
        assertTrue(api.lastPostBody.contains("side=buy"));
        assertTrue(api.lastPostBody.contains("reduceOnly=true"));
    }

    @Test void apiErrorIsRejectedNotException() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.postResponses.put("/api/v3/sendorder", "{\"result\":\"error\",\"error\":\"insufficientAvailableFunds\"}");
        OrderResult r = new KrakenFuturesExchange(api).openShort("PF_XBTUSD", 0.1);
        assertFalse(r.isFilled());
        assertEquals(OrderResult.Status.REJECTED, r.status());
        assertTrue(r.error().contains("insufficient"));
    }

    @Test void fillPriceFallsBackToMarkWhenNoExecutions() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/tickers", TICKERS);
        api.postResponses.put("/api/v3/sendorder",
                "{\"result\":\"success\",\"sendStatus\":{\"order_id\":\"o2\",\"status\":\"placed\",\"orderEvents\":[]}}");
        OrderResult r = new KrakenFuturesExchange(api).openShort("PF_XBTUSD", 0.1);
        assertTrue(r.isFilled());
        assertEquals(63004.5, r.fillPx(), 1e-9, "нет событий исполнения → марка");
    }

    private static final String INSTRUMENTS =
            "{\"result\":\"success\",\"instruments\":["
            + "{\"symbol\":\"PF_XBTUSD\",\"tradeable\":true,\"contractValueTradePrecision\":4},"
            + "{\"symbol\":\"PF_KAITOUSD\",\"tradeable\":true,\"contractValueTradePrecision\":0},"
            + "{\"symbol\":\"PF_DEADUSD\",\"tradeable\":false,\"contractValueTradePrecision\":0}]}";

    @Test void minOrderSizeFromInstruments() throws Exception {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/instruments", INSTRUMENTS);
        KrakenFuturesExchange ex = new KrakenFuturesExchange(api);
        assertEquals(0.0001, ex.minOrderSize("PF_XBTUSD"), 1e-12, "cvtp=4 → 10^-4");
        assertEquals(1.0, ex.minOrderSize("pf_kaitousd"), 1e-12, "cvtp=0 → 1 (регистр не важен)");
        assertEquals(0.0, ex.minOrderSize("PF_DEADUSD"), 1e-12, "не tradeable → 0");
        assertEquals(0.0, ex.minOrderSize("PF_UNKNOWNUSD"), 1e-12, "нет в списке → 0");
    }

    @Test void networkFailureBecomesDisconnect() {
        FakeKrakenApi api = new FakeKrakenApi();
        api.failure = new RuntimeException("connection reset");
        KrakenFuturesExchange ex = new KrakenFuturesExchange(api);
        assertThrows(ExchangeDisconnectedException.class, () -> ex.positions());
        assertThrows(ExchangeDisconnectedException.class, () -> ex.openShort("PF_XBTUSD", 0.1));
    }
}
