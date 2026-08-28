package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гейт страховки по режиму (док. 105 §5).
 *
 * Дрейф-скос стоит −50.6 на боковике и даёт +170.5 на тренде. Платить страховку
 * постоянно незачем, если режим наступает 29% времени — но и включаться она
 * обязана строго по реализованной величине, а не по прогнозу.
 */
class EfficiencyRatioTest {

    private static final long W = 24 * 3600_000L;

    @Test
    void straightLineIsFullyEfficient() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        double last = Double.NaN;
        for (int i = 0; i <= 48; i++) {
            last = er.accept(i * 1800_000L, 100.0 + i);
        }
        assertEquals(1.0, last, 1e-9, "монотонный ряд — ER равен единице");
    }

    @Test
    void pureOscillationIsZeroEfficient() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        double last = Double.NaN;
        for (int i = 0; i <= 48; i++) {
            last = er.accept(i * 1800_000L, 100.0 + (i % 2));
        }
        assertEquals(0.0, last, 1e-9, "чистое колебание — ER ноль");
    }

    @Test
    void windowSlidesSoOldTrendStopsCounting() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        // Сутки роста, затем сутки пилы: старый тренд обязан выйти из окна.
        for (int i = 0; i <= 48; i++) {
            er.accept(i * 1800_000L, 100.0 + i);
        }
        double afterTrend = er.current();
        double last = Double.NaN;
        for (int i = 49; i <= 97; i++) {
            last = er.accept(i * 1800_000L, 148.0 + (i % 2));
        }
        assertTrue(afterTrend > 0.9, "сначала тренд: " + afterTrend);
        assertTrue(last < 0.1, "потом пила, и тренд из окна ушёл: " + last);
    }

    @Test
    void unfilledWindowKeepsTheGateClosed() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        for (int i = 0; i < 5; i++) {
            er.accept(i * 1800_000L, 100.0 + i);          // всего 2.5 часа
        }
        assertTrue(Double.isNaN(er.current()), "по огрызку окна ER не считается");
        assertFalse(er.open(0.40),
                "неизвестный режим = гейт закрыт: цена страховки измерена, польза нет");
    }

    @Test
    void zeroThresholdMeansNoGate() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        assertTrue(er.open(0.0), "порог 0 — историческое поведение, страховка всегда включена");
        assertTrue(er.open(-1), "и отрицательный тоже");
    }

    @Test
    void gateOpensExactlyAtThreshold() {
        EfficiencyRatio er = new EfficiencyRatio(W, 1800_000L);
        // Ряд с ER ровно 0.5: половина шагов вверх, четверть вниз.
        double price = 100;
        for (int i = 0; i <= 48; i++) {
            er.accept(i * 1800_000L, price);
            price += (i % 4 == 3) ? -1 : 1;
        }
        double value = er.current();
        assertTrue(value > 0.4 && value < 0.6, "контрольный ряд около 0.5: " + value);
        assertTrue(er.open(value - 1e-9), "на пороге гейт открыт");
        assertFalse(er.open(value + 1e-9), "чуть выше — закрыт");
    }
}
