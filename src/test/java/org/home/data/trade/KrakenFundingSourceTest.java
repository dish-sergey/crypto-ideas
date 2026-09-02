package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Оценка стоимости удержания для фильтра дорогого шорта.
 *
 * С док. 141 оценка берётся по СУТОЧНОМУ среднему относительной ставки, а не по
 * мгновенной: на данных всей вселенной Kraken (2.25 млн часовых ставок)
 * corr(оценка, факт) поднимается с 0.317 до 0.385. Мгновенная ставка осталась
 * запасным путём на случай, когда истории нет.
 */
class KrakenFundingSourceTest {

    // ETH: (-0.5/3000)*120 = -0.02 < порог -0.015 → дорогой шорт
    private static final String TICKERS =
            "{\"result\":\"success\",\"tickers\":["
            + "{\"symbol\":\"PF_XBTUSD\",\"markPrice\":63000,\"fundingRate\":0.063},"
            + "{\"symbol\":\"PF_ETHUSD\",\"markPrice\":3000,\"fundingRate\":-0.5}]}";

    /** История: {@code n} часов с постоянной относительной ставкой {@code rate}. */
    private static String history(double rate, int n) {
        StringBuilder sb = new StringBuilder("{\"rates\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"timestamp\":\"2026-09-0")
                    .append(1 + i / 24).append("T").append(String.format("%02d", i % 24))
                    .append(":00:00.000Z\",\"relativeFundingRate\":").append(rate).append('}');
        }
        return sb.append("]}").toString();
    }

    private static FakeKrakenApi api() {
        FakeKrakenApi api = new FakeKrakenApi();
        api.getResponses.put("/api/v3/tickers", TICKERS);
        return api;
    }

    private static KrakenFundingSource src() {
        return new KrakenFundingSource(api());
    }

    @Test void mapsBtcToXbt() {
        assertEquals("PF_XBTUSD", KrakenFundingSource.perp("BTC"));
        assertEquals("PF_ETHUSD", KrakenFundingSource.perp("eth"));
    }

    @Test void usesDailyAverageWhenHistoryIsAvailable() throws Exception {
        FakeKrakenApi api = api();
        // Суточное среднее −1e−4/ч → −1e−4 × 120 = −1.2% за горизонт.
        api.getResponses.put("/api/v4/historicalfundingrates?symbol=PF_XBTUSD",
                history(-1e-4, 200));
        assertEquals(-0.012, new KrakenFundingSource(api).estimate5dFunding("BTC"), 1e-12);
    }

    /**
     * Мгновенный всплеск не должен решать за сутки — ради этого сглаживание и
     * вводилось. Последний час резко отрицательный, остальные 23 — нулевые.
     */
    @Test void singleSpikeDoesNotDominate() throws Exception {
        FakeKrakenApi api = api();
        StringBuilder sb = new StringBuilder("{\"rates\":[");
        for (int i = 0; i < 23; i++) {
            sb.append("{\"timestamp\":\"2026-09-01T00:00:00.000Z\",\"relativeFundingRate\":0},");
        }
        sb.append("{\"timestamp\":\"2026-09-02T00:00:00.000Z\",\"relativeFundingRate\":-0.0024}]}");
        api.getResponses.put("/api/v4/historicalfundingrates?symbol=PF_XBTUSD", sb.toString());

        double f = new KrakenFundingSource(api).estimate5dFunding("BTC");
        // Мгновенная оценка дала бы −0.0024 × 120 = −28.8%; суточная — в 24 раза меньше.
        assertEquals(-0.0024 / 24 * 120, f, 1e-12);
        assertTrue(f > -0.015, "одиночный всплеск не должен закрывать событие: " + f);
    }

    @Test void fallsBackToTickerWithoutHistory() throws Exception {
        // История не подложена — FakeKrakenApi отдаёт "{}", и оценка идёт из tickers.
        assertEquals((0.063 / 63000) * 120, src().estimate5dFunding("BTC"), 1e-12);
    }

    @Test void fallbackKeepsExpensiveShortVerdict() throws Exception {
        double f = src().estimate5dFunding("ETH");
        assertTrue(f < -0.015, "дорогой шорт по запасному пути: " + f);
    }

    @Test void positiveFundingIsCheapForShort() throws Exception {
        assertTrue(src().estimate5dFunding("BTC") > 0, "funding>0 → шорт получает");
    }

    @Test void unknownSymbolNeutral() throws Exception {
        assertEquals(0.0, src().estimate5dFunding("DOGE"), 1e-12, "нет перпа → нейтрально");
    }
}
