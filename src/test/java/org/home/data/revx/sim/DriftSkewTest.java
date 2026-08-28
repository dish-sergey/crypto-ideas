package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Слагаемое дрейфа в скосе (док. 98 §3).
 *
 * Инвентарная часть скоса — ставка на возврат движения: «набрали на падении,
 * хотим вернуться к нулю». Подтверждённый факт проекта обратный: резкие движения
 * продолжаются. Слагаемое дрейфа разворачивает эту ставку, но обязано делать это
 * правильно с ОБЕИХ сторон, иначе лечит одно и ломает другое.
 */
class DriftSkewTest {

    private static final double D = 0.0014;      // отступ 0.14%
    private static final double K = 0.001;       // скос 0.10%
    private static final double CAP = 20.0;

    private static Quoter quoter(double beta) {
        return new Quoter(new Quoter.Params(D, 1.0, CAP, K, 0.0, beta, 1.0,
                1_800_000L, 0.0, 0.00005, 0.0));
    }

    /** Расстояния до бида и аска в долях справедливой цены. */
    private static double[] distances(Quoter q, double inventory, double drift) {
        Quoter.Quotes quotes = q.quotes(100.0, inventory, drift);
        return new double[]{
                quotes.hasBid() ? (100.0 - quotes.bid()) / 100.0 : Double.NaN,
                quotes.hasAsk() ? (quotes.ask() - 100.0) / 100.0 : Double.NaN};
    }

    @Test
    void betaComesFromGeometryNotFromData() {
        // Насыщение там, где рынок прошёл всю ширину котировки 2d.
        assertEquals(1.0 / (2 * D), Quoter.betaFromGeometry(D), 1e-9);
        assertEquals(357.14, Quoter.betaFromGeometry(D), 0.01);
    }

    @Test
    void zeroBetaKeepsHistoricBehaviour() {
        double[] withDrift = distances(quoter(0.0), CAP / 2, -0.01);
        double[] withoutDrift = distances(quoter(0.0), CAP / 2, 0.0);
        assertEquals(withoutDrift[0], withDrift[0], 1e-12, "выключено — дрейф не влияет");
        assertEquals(withoutDrift[1], withDrift[1], 1e-12);
    }

    @Test
    void fallingMarketStopsBuyingAndUnloadsHarder() {
        Quoter q = quoter(Quoter.betaFromGeometry(D));
        double[] flat = distances(q, CAP / 2, 0.0);
        double[] falling = distances(q, CAP / 2, -0.005);

        assertTrue(falling[0] > flat[0],
                "на падении бид ОТОДВИГАЕТСЯ: " + falling[0] + " против " + flat[0]);
        assertTrue(falling[1] < flat[1],
                "и аск ПРИДВИГАЕТСЯ: " + falling[1] + " против " + flat[1]);
    }

    @Test
    void risingMarketHoldsPositionLonger() {
        Quoter q = quoter(Quoter.betaFromGeometry(D));
        double[] flat = distances(q, CAP / 2, 0.0);
        double[] rising = distances(q, CAP / 2, 0.005);

        assertTrue(rising[0] < flat[0], "на росте бид придвигается: " + rising[0]);
        assertTrue(rising[1] > flat[1], "и аск отодвигается — держим дольше: " + rising[1]);
    }

    @Test
    void skewStaysWithinScaleOnExtremeDrift() {
        Quoter q = quoter(Quoter.betaFromGeometry(D));
        for (double drift : new double[]{-0.5, -0.05, 0, 0.05, 0.5}) {
            for (double inv : new double[]{0, CAP / 2, CAP}) {
                double[] dist = distances(q, inv, drift);
                for (double x : dist) {
                    if (!Double.isNaN(x)) {
                        assertTrue(x >= D - K - 1e-12 && x <= D + K + 1e-12,
                                "котировка вне шкалы при дрейфе " + drift + ": " + x);
                    }
                }
            }
        }
    }

    @Test
    void controlUsesTheSameSkewIncludingDrift() {
        // Иначе контроль перестаёт быть контролем: он окажется просто более лонг.
        Quoter.Params params = new Quoter.Params(D, 1.0, CAP, K, 0.0,
                Quoter.betaFromGeometry(D), 1.0, 1_800_000L, 0.0, 0.00005, 0.0);
        QuotePolicy control = QuotePolicy.random(params, 42);

        Quoter.Quotes flat = control.quotes(100.0, CAP / 2, 0.0);
        Quoter.Quotes falling = QuotePolicy.random(params, 42).quotes(100.0, CAP / 2, -0.005);

        assertTrue(falling.ask() < flat.ask(),
                "у контроля дрейф обязан двигать котировки так же: "
                        + falling.ask() + " против " + flat.ask());
    }

    @Test
    void asymmetricBuyRatioSlowsAccumulationOnly() {
        Quoter.Params params = new Quoter.Params(D, 1.0, CAP, K, 0.0, 0.0, 0.25,
                0L, 0.0, 0.00005, 0.0);
        assertEquals(0.25, params.sizeFor(Side.BUY, 0), 1e-12, "покупаем вчетверо медленнее");
        assertEquals(1.0, params.sizeFor(Side.SELL, 0), 1e-12, "а разгружаемся полным лотом");
    }
}
