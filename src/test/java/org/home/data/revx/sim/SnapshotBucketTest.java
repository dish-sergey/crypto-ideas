package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Разбиение снимков на моменты времени. Курс должен считаться по срезу рынка:
 * если в одну корзину попали два снимка одной пары, берётся последний, а пары
 * из разных корзин не смешиваются.
 */
class SnapshotBucketTest {

    private static PairQuote quote(String base, long availableAt, double midUsd) {
        return new PairQuote(base, 100.0, midUsd, 0.005, 0.0005, false, availableAt);
    }

    @Test
    void keepsLatestQuotePerPairInsideBucket() {
        List<PairQuote> quotes = List.of(
                quote("BTC", 1_000, 99.0),
                quote("BTC", 4_900, 101.0),      // та же корзина 0-5000, свежее
                quote("ETH", 2_000, 100.0));

        var buckets = SnapshotReader.bucket(quotes, 5_000);

        assertEquals(1, buckets.size());
        Map<String, Double> mids = buckets.get(0).getValue().stream()
                .collect(java.util.stream.Collectors.toMap(PairQuote::base, PairQuote::midUsd));
        assertEquals(101.0, mids.get("BTC"), 1e-9, "внутри корзины берётся последний снимок пары");
        assertEquals(100.0, mids.get("ETH"), 1e-9);
    }

    @Test
    void separatesBucketsByTime() {
        List<PairQuote> quotes = List.of(
                quote("BTC", 1_000, 99.0),
                quote("BTC", 6_000, 101.0),
                quote("BTC", 11_000, 102.0));

        var buckets = SnapshotReader.bucket(quotes, 5_000);

        assertEquals(3, buckets.size(), "снимки из разных корзин не сливаются");
        assertEquals(0L, buckets.get(0).getKey());
        assertEquals(5_000L, buckets.get(1).getKey());
        assertEquals(10_000L, buckets.get(2).getKey());
    }

    @Test
    void bucketsComeInChronologicalOrder() {
        List<PairQuote> quotes = List.of(
                quote("BTC", 20_000, 99.0),
                quote("ETH", 1_000, 100.0),
                quote("SOL", 10_000, 101.0));

        var buckets = SnapshotReader.bucket(quotes, 5_000);

        assertEquals(List.of(0L, 10_000L, 20_000L), buckets.stream().map(Map.Entry::getKey).toList());
    }
}
