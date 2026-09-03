package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Два измерителя из доков 100/101, оба — про природу результата, а не про его
 * величину, поэтому их корректность важнее удобства.
 */
class ArrivalLawTest {

    @Test
    void recoversKnownExponent() {
        // Синтетика с известным ответом: λ = 500·e^{−400δ}.
        double a = 500;
        double kappa = 400;
        List<ArrivalLaw.Rung> rungs = List.of(
                new ArrivalLaw.Rung(0.0004, a * Math.exp(-kappa * 0.0004)),
                new ArrivalLaw.Rung(0.0008, a * Math.exp(-kappa * 0.0008)),
                new ArrivalLaw.Rung(0.0012, a * Math.exp(-kappa * 0.0012)),
                new ArrivalLaw.Rung(0.0016, a * Math.exp(-kappa * 0.0016)));

        ArrivalLaw.Fit fit = ArrivalLaw.fit(rungs);

        assertEquals(kappa, fit.kappa(), 1e-6, "κ обязан восстановиться точно");
        assertEquals(a, fit.a(), 1e-6);
        assertEquals(1.0, fit.rSquared(), 1e-9);
        assertTrue(fit.holds());
    }

    @Test
    void optimalOffsetAddsTheCostModelsDoNotHave() {
        ArrivalLaw.Fit fit = new ArrivalLaw.Fit(1, 200, 0.99, 6);
        // Учебничный ответ: 1/κ = 50 б.п.
        assertEquals(0.005, fit.optimalOffset(0), 1e-12);
        // С пошлиной 3.9 б.п. оптимум сдвигается ровно на неё.
        assertEquals(0.005 + 0.00039, fit.optimalOffset(0.00039), 1e-12);
    }

    @Test
    void refusesToFitNoise() {
        ArrivalLaw.Fit fit = ArrivalLaw.fit(List.of(
                new ArrivalLaw.Rung(0.0004, 100),
                new ArrivalLaw.Rung(0.0008, 300),
                new ArrivalLaw.Rung(0.0012, 90),
                new ArrivalLaw.Rung(0.0016, 280)));
        assertFalse(fit.holds(), "на шуме подгонку читать нельзя: R² = " + fit.rSquared());
    }

    @Test
    void persistenceFindsPlantedContinuation() {
        // Ряд с заведомым продолжением: каждый шаг повторяет предыдущий на 50%.
        //
        // ⚠️ Длина ряда — часть требования, а не оформление. Точки берутся раз в
        // минуту при горизонте 120 минут, то есть перекрываются ×120, и на прежних
        // 4000 минутах независимых наблюдений было 32: при них 2σ недостижимы даже
        // для посаженного сигнала (IC 0.117 давал 0.66σ). Шестьдесят тысяч минут
        // дают ~500 независимых наблюдений — вот сколько данных нужно, чтобы
        // направление дрейфа на этом горизонте вообще стало измеримым.
        TreeMap<Long, Double> series = new TreeMap<>();
        double price = 100;
        double step = 0.001;
        for (int i = 0; i < 60_000; i++) {
            series.put(i * 60_000L, price);
            step = 0.5 * step + 0.5 * ((i % 7) - 3) * 0.0004;
            price *= 1 + step;
        }

        DriftPersistence.Stats stats = DriftPersistence.compute(
                series, 30 * 60_000L, 120 * 60_000L, 60_000L);

        assertTrue(stats.points() > 1000, "точек должно хватать: " + stats.points());
        assertTrue(Math.abs(stats.correlation()) > 0.05,
                "посаженное продолжение обязано найтись: IC = " + stats.correlation());
        assertTrue(stats.effectivePoints() > 100,
                "независимых наблюдений должно хватать: " + stats.effectivePoints());
        assertTrue(stats.predictive(),
                "посаженный сигнал обязан быть значим: t = " + stats.tStat());
    }

    @Test
    void persistenceReportsNoSignalOnRandomWalk() {
        // Случайное блуждание с фиксированным seed: продолжения нет по построению.
        TreeMap<Long, Double> series = new TreeMap<>();
        java.util.random.RandomGenerator rng =
                java.util.random.RandomGeneratorFactory.of("L64X128MixRandom").create(7);
        double price = 100;
        for (int i = 0; i < 4000; i++) {
            series.put(i * 60_000L, price);
            price *= 1 + (rng.nextDouble() - 0.5) * 0.002;
        }

        DriftPersistence.Stats stats = DriftPersistence.compute(
                series, 30 * 60_000L, 120 * 60_000L, 60_000L);

        assertTrue(Math.abs(stats.correlation()) < 0.25,
                "на блуждании корреляции быть не должно: " + stats.correlation());
    }

    @Test
    void overlapIsReportedSoWeakCorrelationIsNotOversold() {
        TreeMap<Long, Double> series = new TreeMap<>();
        for (int i = 0; i < 1000; i++) {
            series.put(i * 60_000L, 100.0 + i * 0.01);
        }
        DriftPersistence.Stats stats = DriftPersistence.compute(
                series, 30 * 60_000L, 120 * 60_000L, 60_000L);

        // Горизонт 120 минут при шаге 1 минута — стократное перекрытие.
        assertEquals(120.0, stats.overlap(), 1e-9);
    }

    @Test
    void continuousShapingHasNoThreshold() {
        Quoter.Params params = new Quoter.Params(0.0014, 1.0, 20.0, 0.001, 0.00005, 0.0)
                .withShapeEta(Math.log(4));

        double atEmpty = params.sizeFor(Side.BUY, 0);
        double atHalf = params.sizeFor(Side.BUY, 10);
        double atCap = params.sizeFor(Side.BUY, 20);

        assertEquals(1.0, atEmpty, 1e-12, "на пустом счёте покупаем полным лотом");
        assertEquals(0.25, atCap, 1e-12, "у потолка — четвертью, как у ступеньки ×0.25");
        assertTrue(atHalf < atEmpty && atHalf > atCap,
                "и между ними монотонно, без порога: " + atHalf);
        assertEquals(1.0, params.sizeFor(Side.SELL, 20), 1e-12, "продажа не шейпится");
    }
}
