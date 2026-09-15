package org.home.data.revx.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СУТОЧНОЕ ОТНОШЕНИЕ СЧИТАЕТСЯ ПО СУММАМ, А НЕ ПО СРЕДНИМ ЗА СУТКИ.
 *
 * <h2>Зачем тест</h2>
 *
 * Обход считает каждые сутки отдельным прогоном и складывает клетки. Первая
 * версия складывала СРЕДНИЕ и делила на число суток — то есть сутки с тремя
 * кругами весили столько же, сколько сутки с тремястами. На окне 10–14.09.2026
 * это дало у BTC @6 «отношение +2.84» при фактических годовых −65%: знак решали
 * редкие сутки. Теперь копятся суммы, и среднее с СКО считаются один раз по
 * всему окну.
 *
 * Второе свойство, тоже проверяемое здесь: круг весит СВОЮ ДОЛЮ ЛОТА. Одна
 * продажа закрывает несколько частично набранных партий (на сутках 11.09 —
 * 176 записей при 92 продажах), и поштучный счёт дал бы надкусанному кругу тот
 * же вес, что целому.
 */
class RoundStatsTest {

    private static Forecast.BotResult result(double sum, double sumSq, double count, double days) {
        return new Forecast.BotResult("a", 12, 0, 0, 0, 0, 0, 0, days, "", 0, 0, 0, 0, 0,
                java.util.List.of(), 0, new long[0], 0, 0, 0,
                sum, sumSq, count, 0, 0);
    }

    /** Взвешенные суммы по набору кругов {значение, вес}. */
    private static Forecast.BotResult of(double days, double[][] rounds) {
        double sum = 0;
        double sumSq = 0;
        double count = 0;
        for (double[] r : rounds) {
            sum += r[0] * r[1];
            sumSq += r[0] * r[0] * r[1];
            count += r[1];
        }
        return result(sum, sumSq, count, days);
    }

    @Test
    void meanAndSdOverTheWholeWindow() {
        // Четыре целых круга: +10, +10, −10, −10 б.п. Среднее 0, СКО 11.55.
        Forecast.BotResult r = of(1, new double[][]{{10, 1}, {10, 1}, {-10, 1}, {-10, 1}});
        assertEquals(0.0, r.roundMeanBp(), 1e-9);
        assertEquals(Math.sqrt(400.0 / 3), r.roundSdBp(), 1e-9);
        assertEquals(0.0, r.ratioPerDay(), 1e-9);
    }

    @Test
    void ratioGrowsAsSquareRootOfRoundsPerDay() {
        // Одинаковые круги, но вчетверо больше за те же сутки: отношение вдвое.
        Forecast.BotResult few = of(1, new double[][]{{6, 1}, {2, 1}, {6, 1}, {2, 1}});
        Forecast.BotResult many = of(1, new double[][]{
                {6, 1}, {2, 1}, {6, 1}, {2, 1}, {6, 1}, {2, 1}, {6, 1}, {2, 1},
                {6, 1}, {2, 1}, {6, 1}, {2, 1}, {6, 1}, {2, 1}, {6, 1}, {2, 1}});
        assertEquals(few.roundMeanBp(), many.roundMeanBp(), 1e-9);
        // ⚠️ Ровно двойки не будет: СКО считается с поправкой на малую выборку
        // (делитель n−1), и у четырёх кругов оно выше, чем у шестнадцати. На
        // живых числах кругов сотни, и поправка пренебрежима, но в тесте она
        // видна — 2.24 вместо 2.00.
        assertEquals(2.0, many.ratioPerDay() / few.ratioPerDay(), 0.3);
    }

    @Test
    void partialRoundWeighsItsShareOfTheLot() {
        // Целый круг +10 и треть круга −10: среднее должно быть +5, а не 0.
        Forecast.BotResult r = of(1, new double[][]{{10, 1}, {-10, 1.0 / 3}});
        assertEquals(5.0, r.roundMeanBp(), 1e-9);
    }

    @Test
    void ratioSignFollowsTheMean() {
        assertTrue(of(1, new double[][]{{-5, 1}, {-15, 1}, {+2, 1}}).ratioPerDay() < 0);
        assertTrue(of(1, new double[][]{{+5, 1}, {+15, 1}, {-2, 1}}).ratioPerDay() > 0);
    }

    @Test
    void singleRoundGivesNoRatio() {
        // По одному кругу СКО не определено — отношение обязано быть нулём,
        // а не бесконечностью.
        assertEquals(0.0, of(1, new double[][]{{10, 1}}).ratioPerDay(), 1e-12);
    }

    @Test
    void longerWindowLowersRoundsPerDayAndTheRatio() {
        double[][] rounds = {{6, 1}, {2, 1}, {6, 1}, {2, 1}};
        assertTrue(of(4, rounds).ratioPerDay() < of(1, rounds).ratioPerDay(),
                "те же круги, растянутые на четверо суток, дают меньшее суточное отношение");
    }
}
