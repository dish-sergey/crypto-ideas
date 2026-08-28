package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Частота режима по дневным свечам (док. 107 §4).
 *
 * Считать это стало нужно после того, как классификация режима провалилась по
 * существу: предсказать «вернётся ли цена» нельзя, а посчитать, как часто она НЕ
 * возвращается, — можно, и книга заявок для этого не требуется.
 */
class RegimeFrequencyTest {

    @Test
    void findsPlantedDrawdownWithDepthAndRecovery() {
        // 100 → 80 (−20%) за 2 дня, возврат к 100 на 5-й день от пика.
        double[] closes = {100, 90, 80, 90, 95, 101, 102};

        List<RegimeFrequency.Episode> episodes = RegimeFrequency.drawdowns(closes, 5);

        assertEquals(1, episodes.size(), "просадка ровно одна");
        RegimeFrequency.Episode e = episodes.getFirst();
        assertEquals(20.0, e.depthPct(), 1e-9, "глубина от пика");
        assertEquals(2, e.daysToTrough());
        assertEquals(5, e.recoveredDays());
        assertTrue(e.recoveredWithin(5));
        assertFalse(e.recoveredWithin(4), "на четвёртый день ещё не вернулась");
    }

    @Test
    void unrecoveredEpisodeIsKeptNotDropped() {
        // Именно незакрытая просадка и есть самое интересное наблюдение.
        double[] closes = {100, 95, 90, 85, 84};

        List<RegimeFrequency.Episode> episodes = RegimeFrequency.drawdowns(closes, 5);

        assertEquals(1, episodes.size());
        assertEquals(-1, episodes.getFirst().recoveredDays(), "не вернулась до конца ряда");
        assertFalse(episodes.getFirst().recoveredWithin(1000));
    }

    @Test
    void shallowNoiseIsFilteredOut() {
        double[] closes = {100, 99.5, 100.2, 99.8, 100.5};
        assertTrue(RegimeFrequency.drawdowns(closes, 5).isEmpty(),
                "просадки мельче порога не эпизоды, а шум");
    }

    @Test
    void bucketsSplitWindowsByMove() {
        // Ряд из трёх участков: рост, падение, плоско.
        double[] closes = new double[60];
        for (int i = 0; i < 20; i++) {
            closes[i] = 100 * (1 + 0.02 * i);
        }
        for (int i = 20; i < 40; i++) {
            closes[i] = closes[19] * (1 - 0.02 * (i - 19));
        }
        for (int i = 40; i < 60; i++) {
            closes[i] = closes[39];
        }

        List<RegimeFrequency.Bucket> buckets =
                RegimeFrequency.buckets(closes, 5, 5.0, 100, 200, -50);

        assertEquals(3, buckets.size());
        double totalShare = buckets.stream().mapToDouble(RegimeFrequency.Bucket::share).sum();
        assertEquals(1.0, totalShare, 1e-9, "доли обязаны складываться в единицу");
        assertTrue(buckets.get(0).windows() > 0, "растущие окна есть");
        assertTrue(buckets.get(1).windows() > 0, "падающие окна есть");
        assertTrue(buckets.get(2).windows() > 0, "плоские окна есть");
    }

    @Test
    void expectationIsShareWeightedPayoff() {
        List<RegimeFrequency.Bucket> buckets = List.of(
                new RegimeFrequency.Bucket("вверх", 10, 0.2, 100),
                new RegimeFrequency.Bucket("вниз", 10, 0.3, 200),
                new RegimeFrequency.Bucket("прочее", 25, 0.5, -50));

        assertEquals(0.2 * 100 + 0.3 * 200 - 0.5 * 50,
                RegimeFrequency.expectation(buckets), 1e-9);
    }

    @Test
    void shareOfDaysCountsTimeNotEpisodes() {
        // Одна короткая просадка и одна длинная: важно ВРЕМЯ, а не число эпизодов.
        double[] closes = {100, 90, 101, 100, 90, 85, 80, 75, 70, 65};

        double share = RegimeFrequency.shareOfDaysInUnrecovered(closes, 5, 1);

        assertTrue(share > 0.5,
                "длинная невозвращённая просадка занимает большую часть ряда: " + share);
        assertTrue(share <= 1.0);
    }
}
