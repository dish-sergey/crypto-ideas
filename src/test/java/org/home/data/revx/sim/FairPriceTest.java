package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Справедливая цена и предохранители (ТЗ §4.1, §7 «Данные»).
 *
 * Ключевой сценарий — не выдуманный: 19.08.2026 на движении ETH в 18% опорная
 * книга ETH/USD раздвинулась до 1.18% при 0.016% у BTC/USD, а implied по ETH
 * ушёл на −3.5%, тогда как остальные 22 пары дали 0.9999. Медиана обязана
 * устоять, а ETH — выключиться.
 */
class FairPriceTest {

    private static final FairPrice.Limits LIMITS = new FairPrice.Limits(8, 0.10, 0.20, 0.50);

    /** Здоровый рынок: 22 пары около 0.9999, спреды опоры узкие. */
    private static List<PairQuote> healthyMarket() {
        List<PairQuote> quotes = new ArrayList<>();
        String[] bases = {"BTC", "SOL", "BNB", "AVAX", "LINK", "XRP", "ADA", "BCH", "DOT",
                "ENA", "HBAR", "JUP", "LTC", "SUI", "TON", "TRX", "XLM"};
        for (int i = 0; i < bases.length; i++) {
            double implied = 0.9999 + (i % 3 - 1) * 0.00005;      // разброс в сотые доли процента
            double midUsdc = 100.0;
            quotes.add(new PairQuote(bases[i], midUsdc, midUsdc * implied,
                    0.005, 0.0005, false, 1_000L + i));
        }
        return quotes;
    }

    @Test
    void medianSurvivesSingleDislocatedPair() {
        List<PairQuote> quotes = healthyMarket();
        // ETH: опора шире собственной книги и implied на −3.5%
        quotes.add(new PairQuote("ETH", 2273.0, 2273.0 * 0.965, 0.0058, 0.0118, false, 1_100L));

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);

        assertEquals(0.9999, result.rate(), 1e-4, "одна разъехавшаяся пара не должна двигать курс");
        assertTrue(result.reliable(), "медиана здорова — общий гейт срабатывать не должен");
    }

    @Test
    void dislocatedPairIsPausedEvenWhenRateIsHealthy() {
        List<PairQuote> quotes = healthyMarket();
        quotes.add(new PairQuote("ETH", 2273.0, 2273.0 * 0.965, 0.0058, 0.0118, false, 1_100L));

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);
        FairPrice.PairState eth = result.pair("ETH");

        // именно этот случай общий гейт ТЗ пропускает: курс в порядке, сломана опора пары
        assertFalse(eth.quotable(), "котировать ETH от книги со спредом 1.18% нельзя");
        assertTrue(eth.pausedReason().startsWith("опорная книга широка"), eth.pausedReason());
        assertTrue(result.pair("BTC").quotable(), "здоровые пары остаются в работе");
    }

    @Test
    void wideResidualPausesPairEvenWithNarrowReference() {
        List<PairQuote> quotes = healthyMarket();
        // опора узкая (0.05%), но implied ушёл на −1.5%: это уже не «возможность», а расхождение
        quotes.add(new PairQuote("PEPE", 100.0, 98.5, 0.008, 0.0005, false, 1_100L));

        FairPrice.PairState pepe = FairPrice.compute(quotes, LIMITS).pair("PEPE");

        assertFalse(pepe.quotable());
        assertTrue(pepe.pausedReason().startsWith("опора разошлась"), pepe.pausedReason());
    }

    @Test
    void globalGateStopsEverythingWhenPairsScatter() {
        List<PairQuote> quotes = new ArrayList<>();
        double[] implied = {0.990, 0.995, 1.000, 1.005, 1.010, 0.985, 1.015, 0.992, 1.008};
        for (int i = 0; i < implied.length; i++) {
            quotes.add(new PairQuote("P" + i, 100.0, 100.0 * implied[i], 0.005, 0.0005, false, 1_000L));
        }

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);

        assertFalse(result.reliable(), "разброс в целый процент — курс брать неоткуда");
        assertTrue(result.unreliableReason().startsWith("разброс implied"), result.unreliableReason());
        assertTrue(result.pairs().values().stream().noneMatch(FairPrice.PairState::quotable),
                "при ненадёжном курсе останавливается всё котирование, а не часть");
    }

    @Test
    void tooFewPairsIsAlsoUnreliable() {
        List<PairQuote> quotes = List.of(
                new PairQuote("BTC", 100.0, 99.99, 0.005, 0.0005, false, 1L),
                new PairQuote("ETH", 100.0, 99.99, 0.005, 0.0005, false, 1L));

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);

        assertFalse(result.reliable());
        assertTrue(result.unreliableReason().contains("минимуме 8"), result.unreliableReason());
    }

    @Test
    void memecoinsDoNotEnterRateButStayQuotable() {
        List<PairQuote> quotes = healthyMarket();
        // мемкоин с сильно другим implied: на курс влиять не должен
        quotes.add(new PairQuote("SHIB", 100.0, 100.0 * 1.02, 0.008, 0.0005, true, 1_100L));

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);

        assertEquals(0.9999, result.rate(), 1e-4, "мемкоин не входит в расчёт курса (ТЗ §4.1)");
        assertEquals(17, result.pairsUsed(), "в расчёте только неммемкоины");
        assertTrue(result.pairs().containsKey("SHIB"), "но сам мемкоин из выдачи не исчезает");
    }

    @Test
    void fairPriceComesFromReferenceBookNotFromTradedOne() {
        List<PairQuote> quotes = healthyMarket();
        // ТЗ §4.6 п.3: mid книги, в которой торгуем, справедливой ценой быть не может
        quotes.add(new PairQuote("SOLX", 2000.0, 2100.0, 0.02, 0.001, false, 1_100L));

        FairPrice.Result result = FairPrice.compute(quotes, LIMITS);
        FairPrice.PairState state = result.pair("SOLX");

        assertEquals(2100.0 / result.rate(), state.fairUsdc(), 1e-9);
    }

    @Test
    void ratesAreUnaffectedByPairOrder() {
        List<PairQuote> quotes = healthyMarket();
        List<PairQuote> reversed = new ArrayList<>(quotes);
        java.util.Collections.reverse(reversed);

        assertEquals(FairPrice.compute(quotes, LIMITS).rate(),
                FairPrice.compute(reversed, LIMITS).rate(), 1e-12,
                "порядок пар не должен влиять на курс — иначе воспроизводимости нет (ТЗ §7)");
    }
}
