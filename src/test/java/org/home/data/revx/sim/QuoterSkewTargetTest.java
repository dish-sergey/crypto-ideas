package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цель скоса: точка, в которой котировки симметричны.
 *
 * Скос вычитается из обеих цен, поэтому равные расстояния до бида и аска бывают
 * ровно там, где он равен нулю. При исторической цели 0 эта точка — пустой
 * инвентарь, а на споте там нельзя выставить аск: контроллер целится в угол.
 */
class QuoterSkewTargetTest {

    private static final double D = 0.0014;      // отступ 0.14%
    private static final double K = 0.001;       // скос 0.10%
    private static final double CAP = 20.0;

    private static Quoter quoter(double target) {
        return new Quoter(new Quoter.Params(D, 1.0, CAP, K, target, 0.00005, 0.0));
    }

    /** Расстояния до бида и до аска в долях от справедливой цены. */
    private static double[] distances(Quoter q, double inventory) {
        Quoter.Quotes quotes = q.quotes(100.0, inventory);
        return new double[]{
                quotes.hasBid() ? (100.0 - quotes.bid()) / 100.0 : Double.NaN,
                quotes.hasAsk() ? (quotes.ask() - 100.0) / 100.0 : Double.NaN};
    }

    @Test
    void defaultTargetIsEmptyInventory() {
        // Историческое поведение обязано сохраниться дословно.
        double[] atZero = distances(quoter(0.0), 0.0);
        assertEquals(D, atZero[0], 1e-12, "при пустом инвентаре бид ровно на отступе");

        double[] atHalf = distances(quoter(0.0), CAP / 2);
        assertTrue(atHalf[1] < atHalf[0],
                "с половиной потолка аск обязан быть ближе бида: " + atHalf[1] + " против " + atHalf[0]);
    }

    @Test
    void targetHalfMakesQuotesSymmetricAtHalfCap() {
        double[] d = distances(quoter(0.5), CAP / 2);
        assertEquals(d[0], d[1], 1e-12,
                "в целевой точке расстояния равны: " + d[0] + " против " + d[1]);
        assertEquals(D, d[0], 1e-12, "и равны отступу");
    }

    @Test
    void targetHalfTiltsToBuyWhenInventoryIsLow() {
        // Именно этой возвращающей силы не хватало: с пустым счётом надо ПОКУПАТЬ
        // охотнее, а не стоять на том же отступе и ждать.
        Quoter q = quoter(0.5);
        double[] low = distances(q, CAP * 0.1);
        assertTrue(low[0] < D, "бид придвинут к справедливой цене: " + low[0]);
        assertTrue(low[1] > D, "аск отодвинут: " + low[1]);

        assertNull(q.quotes(100.0, 0.0).ask(), "с нулём продавать всё равно нечем — это спот");
        assertEquals(D - K, distances(q, 0.0)[0], 1e-12,
                "но бид на краю шкалы придвинут на весь скос");
    }

    @Test
    void skewStaysWithinScaleForAnyTarget() {
        for (double target : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            Quoter q = quoter(target);
            for (double inv = 0; inv <= CAP; inv += CAP / 8) {
                double[] d = distances(q, inv);
                if (!Double.isNaN(d[0])) {
                    assertTrue(d[0] >= D - K - 1e-12 && d[0] <= D + K + 1e-12,
                            "бид вне шкалы при цели " + target + ": " + d[0]);
                }
                if (!Double.isNaN(d[1])) {
                    assertTrue(d[1] >= D - K - 1e-12 && d[1] <= D + K + 1e-12,
                            "аск вне шкалы при цели " + target + ": " + d[1]);
                }
            }
        }
    }
}
