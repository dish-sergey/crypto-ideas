package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ось лестницы и κ по эффективному отступу (док. 154 §V, блок 2).
 *
 * Тест закрепляет две вещи, на которых прибор уже один раз сломался:
 * <ul>
 *   <li><b>восстановление цены стоящей заявки</b> правилом перевыставления:
 *       заявка не переезжает, пока цель не уехала дальше порога. Без этого
 *       время считалось бы по цели, а цель всегда стоит в своих 12 б.п. от
 *       свежей опоры — и знаменатель {@code λ(δ)} перестал бы иметь отношение к
 *       тому, где заявка действительно была;</li>
 *   <li><b>наклон {@code ln λ}</b>: κ положительна, когда поток падает с
 *       расстоянием, и корзины с горсткой исполнений в регрессию не идут.</li>
 * </ul>
 */
class OffsetAxisTest {

    @Test
    void restingOrderStaysUntilTargetMovesPastThreshold() {
        // Порог 0.5 б.п. от цены: сдвиг цели на 0.2 б.п. заявку не двигает.
        double resting = 100.0;
        assertEquals(100.0, OffsetAxis.moved(resting, 100.002), 1e-9);
        // А сдвиг на 1 б.п. — двигает.
        assertEquals(100.01, OffsetAxis.moved(resting, 100.01), 1e-9);
    }

    @Test
    void emptySlotTakesCurrentTarget() {
        assertEquals(99.5, OffsetAxis.moved(0, 99.5), 1e-9);
    }

    @Test
    void suppressedSideLeavesBookEmpty() {
        // Сторону не котируют — в книге её нет, и время ей начислять нельзя.
        assertEquals(0, OffsetAxis.moved(100.0, 0), 1e-9);
    }

    @Test
    void kappaIsPositiveWhenFlowFallsWithDistance() {
        // λ падает вдвое на каждые два базисных пункта: κ = ln2/2 ≈ 0.347.
        TreeMap<Integer, Double> time = new TreeMap<>();
        TreeMap<Integer, Integer> fills = new TreeMap<>();
        double day = 86_400_000.0;
        time.put(6, day);
        fills.put(6, 80);
        time.put(8, day);
        fills.put(8, 40);
        time.put(10, day);
        fills.put(10, 20);
        time.put(12, day);
        fills.put(12, 10);
        double[] fit = OffsetAxis.fit(time, fills);
        assertEquals(Math.log(2) / 2, fit[0], 0.01);
        assertEquals(4, (int) fit[1]);
    }

    @Test
    void thinBucketsAreDroppedFromTheFit() {
        // Две корзины с потоком и одна с тремя исполнениями: точек меньше трёх,
        // и прибор обязан отказаться считать, а не выдать наклон по двум точкам.
        TreeMap<Integer, Double> time = new TreeMap<>();
        TreeMap<Integer, Integer> fills = new TreeMap<>();
        time.put(6, 86_400_000.0);
        fills.put(6, 50);
        time.put(8, 86_400_000.0);
        fills.put(8, 25);
        time.put(10, 86_400_000.0);
        fills.put(10, 3);
        double[] fit = OffsetAxis.fit(time, fills);
        assertTrue(fit[0] < -900, "корзин мало — наклон не считается");
    }

    @Test
    void bucketsAreOneBasisPointWide() {
        assertEquals(7, OffsetAxis.bucket(7.4));
        assertEquals(7, OffsetAxis.bucket(7.9));
        assertEquals(8, OffsetAxis.bucket(8.0));
        assertEquals(-1, OffsetAxis.bucket(-0.5), "бид выше опоры — корзина отрицательная");
    }
}
