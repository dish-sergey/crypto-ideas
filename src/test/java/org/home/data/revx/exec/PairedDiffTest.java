package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Парный опыт по совпадающим отрезкам (док. 154 §VII, блок 5).
 *
 * ⚠️ Главное, что закрепляет тест: круг идёт в отрезок, только если ОБА конца
 * внутри. Разметка по моменту закрытия приписывает убыток моменту фиксации, а
 * не создания, и у бота A знак «тихого» режима от этого переворачивался
 * (док. 153 §VIII.2). Второе — арифметика ошибки: она должна падать как
 * {@code 1/√n}, иначе «сколько ещё ждать» отвечается неверно.
 */
class PairedDiffTest {

    @Test
    void roundCountsOnlyWhenBothEndsAreInsideTheSegment() {
        List<PairedDiff.Round> rounds = List.of(
                new PairedDiff.Round(1_000, 2_000, 10),      // целиком внутри
                new PairedDiff.Round(500, 2_000, 20),        // открылся раньше отрезка
                new PairedDiff.Round(1_000, 9_000, 30));     // закрылся позже
        List<Double> in = PairedDiff.inSegment(rounds, 800, 5_000);
        assertEquals(1, in.size());
        assertEquals(10, in.get(0), 1e-9);
    }

    @Test
    void meanAndStandardErrorOfDifferences() {
        // Разности 2, 4, 6: среднее 4, СКО 2, ошибка 2/√3 ≈ 1.155, t ≈ 3.46.
        double[] s = PairedDiff.stats(List.of(2.0, 4.0, 6.0));
        assertEquals(4.0, s[0], 1e-9);
        assertEquals(2.0 / Math.sqrt(3), s[1], 1e-6);
        assertEquals(4.0 / (2.0 / Math.sqrt(3)), s[2], 1e-6);
    }

    @Test
    void zeroDifferenceGivesZeroT() {
        double[] s = PairedDiff.stats(List.of(0.0, 0.0, 0.0, 0.0));
        assertEquals(0.0, s[0], 1e-12);
        assertEquals(0.0, s[2], 1e-12);
    }

    @Test
    void neededSegmentsScaleAsOneOverSqrtN() {
        // При t = 1 на 10 отрезках до |t| = 2 нужно вчетверо больше.
        double[] s = {1.0, 1.0, 1.0};
        assertEquals(40, PairedDiff.needed(s, 10), 1e-6);
        // При t = 2 ждать больше нечего.
        double[] ready = {2.0, 1.0, 2.0};
        assertEquals(10, PairedDiff.needed(ready, 10), 1e-6);
    }

    @Test
    void singleDifferenceIsNotEnoughForAnyStatement() {
        double[] s = PairedDiff.stats(List.of(5.0));
        assertTrue(s[1] == 0 && s[2] == 0, "по одной разности ошибка не определена");
    }
}
