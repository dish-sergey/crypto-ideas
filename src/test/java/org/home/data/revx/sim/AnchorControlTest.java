package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Второй контроль: случайный якорь на ТЕХ ЖЕ расстояниях (док. 127 §9).
 *
 * Смысл теста — приёмка отличия от первого контроля. У {@link QuotePolicy#random}
 * случайно и расстояние тоже (равномерно на (0, 2d]), поэтому проигрыш ему
 * смешан с лестницей отступа. У нового контроля расстояние обязано быть в
 * точности d, а случаен только центр — иначе он отвечает не на тот вопрос.
 */
class AnchorControlTest {

    private static final double D = 0.001;                   // отступ 10 б.п.
    private static final double CAP = 20.0;

    /** Скос выключен: тест про расстояния, а не про инвентарь. */
    private static Quoter.Params params() {
        return new Quoter.Params(D, 1.0, CAP, 0.0, 0.00005, 0.0);
    }

    @Test
    void distancesFromAnchorAreExactlyTheOffset() {
        QuotePolicy control = QuotePolicy.staleAnchor(params(), 42L, 16);
        // Первый вызов: истории ещё нет, значит якорь может быть только текущей
        // ценой — расстояния обязаны совпасть с отступом до последнего знака.
        Quoter.Quotes q = control.quotes(100.0, CAP / 2);
        assertEquals(100.0 * (1 - D), q.bid(), 1e-12);
        assertEquals(100.0 * (1 + D), q.ask(), 1e-12);
    }

    @Test
    void anchorDriftsAwayFromCurrentFairButSpreadStaysTwiceTheOffset() {
        QuotePolicy control = QuotePolicy.staleAnchor(params(), 7L, 64);
        double fair = 100.0;
        boolean sawStaleAnchor = false;
        for (int i = 0; i < 200; i++) {
            fair *= 1.0005;                                  // цена уверенно уходит вверх
            Quoter.Quotes q = control.quotes(fair, CAP / 2);
            double anchor = (q.bid() + q.ask()) / 2;
            // Ширина между котировками — свойство расстояния, и оно фиксировано.
            assertEquals(2 * D * anchor, q.ask() - q.bid(), 1e-9,
                    "расстояние обязано остаться ровно ±d от якоря");
            if (Math.abs(anchor - fair) / fair > D) {
                sawStaleAnchor = true;                       // якорь ушёл дальше отступа
            }
        }
        assertTrue(sawStaleAnchor,
                "на уходящей цене случайный якорь обязан отставать — иначе контроль "
                        + "неотличим от стратегии");
    }

    @Test
    void randomControlRandomisesDistanceToo() {
        // Приёмка самого различия: у первого контроля ширина ПЛАВАЕТ, у второго — нет.
        QuotePolicy random = QuotePolicy.random(params(), 1L);
        double first = width(random, 100.0);
        boolean varies = false;
        for (int i = 0; i < 50 && !varies; i++) {
            varies = Math.abs(width(random, 100.0) - first) > 1e-9;
        }
        assertTrue(varies, "контроль случайных котировок обязан менять и расстояние");
    }

    private static double width(QuotePolicy policy, double fair) {
        Quoter.Quotes q = policy.quotes(fair, CAP / 2);
        return q.ask() - q.bid();
    }

    @Test
    void isReproducibleForTheSameSeed() {
        // ТЗ §7: любая случайность в стенде обязана быть воспроизводимой.
        QuotePolicy a = QuotePolicy.staleAnchor(params(), 99L, 32);
        QuotePolicy b = QuotePolicy.staleAnchor(params(), 99L, 32);
        for (int i = 0; i < 100; i++) {
            double fair = 100.0 + i;
            assertEquals(a.quotes(fair, 1.0).bid(), b.quotes(fair, 1.0).bid(), 1e-12);
        }
    }
}
