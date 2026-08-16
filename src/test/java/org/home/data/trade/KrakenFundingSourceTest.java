package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Оценка 5д funding из публичного tickers + маппинг базы в перп (BTC→XBT). */
class KrakenFundingSourceTest {

    // ETH: (-0.5/3000)*120 = -0.02 < порог -0.015 → дорогой шорт
    private static final String TICKERS =
            "{\"result\":\"success\",\"tickers\":["
            + "{\"symbol\":\"PF_XBTUSD\",\"markPrice\":63000,\"fundingRate\":0.063},"
            + "{\"symbol\":\"PF_ETHUSD\",\"markPrice\":3000,\"fundingRate\":-0.5}]}";

    private static KrakenFundingSource src() {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/tickers", TICKERS);
        return new KrakenFundingSource(api);
    }

    @Test void mapsBtcToXbtAndComputes5d() throws Exception {
        assertEquals((0.063 / 63000) * 120, src().estimate5dFunding("BTC"), 1e-12);
    }

    @Test void positiveFundingIsCheapForShort() throws Exception {
        assertTrue(src().estimate5dFunding("BTC") > 0, "funding>0 → шорт получает");
    }

    @Test void negativeFundingBelowThresholdIsExpensive() throws Exception {
        double f = src().estimate5dFunding("ETH");
        assertTrue(f < -0.015, "дорогой шорт: " + f);
    }

    @Test void unknownSymbolNeutral() throws Exception {
        assertEquals(0.0, src().estimate5dFunding("DOGE"), 1e-12, "нет перпа → нейтрально");
    }
}
