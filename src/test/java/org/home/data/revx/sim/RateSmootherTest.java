package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сглаживание курса USDC/USD (задача A45, вопрос про ШУМ, а не про уровень).
 *
 * Тест закрепляет три свойства, каждое из которых решает отдельный вопрос:
 * <ul>
 *   <li><b>уровень не меняется</b>: на постоянном курсе сглаженный равен ему.
 *       Это и есть разница между «убрать дёрганье» и «переехать на прямую
 *       книгу», где уровень уезжает на 5–6 б.п.;</li>
 *   <li><b>вес по ВРЕМЕНИ</b>: два тика через секунду сглаживают слабее, чем
 *       один через две. Иначе одна и та же настройка означала бы разное
 *       сглаживание на быстром и медленном ярусе сбора;</li>
 *   <li><b>разрыв сбрасывает состояние</b>: после простоя старый курс не
 *       «догоняется» — он уже не имеет отношения к рынку.</li>
 * </ul>
 */
class RateSmootherTest {

    @Test
    void constantRateIsNotChanged() {
        RateSmoother s = new RateSmoother(60);
        assertEquals(1.0001, s.next(0, 1.0001), 1e-12);
        assertEquals(1.0001, s.next(60_000, 1.0001), 1e-12);
        assertEquals(1.0001, s.next(600_000 - 1, 1.0001), 1e-12);
    }

    @Test
    void firstObservationIsTakenAsIs() {
        assertEquals(0.9995, new RateSmoother(60).next(1_000, 0.9995), 1e-12);
    }

    @Test
    void jumpIsDampedAndDecaysWithHalfLife() {
        RateSmoother s = new RateSmoother(60);
        s.next(0, 1.0);
        // Скачок на 10 б.п. через полупериод: доходит 1 − e^(−1) ≈ 63%.
        double after = s.next(60_000, 1.001);
        assertTrue(after > 1.0 && after < 1.001, "скачок дошёл не весь");
        assertEquals(1.0 + 0.001 * (1 - Math.exp(-1)), after, 1e-9);
    }

    @Test
    void weightFollowsElapsedTimeNotTickCount() {
        RateSmoother fast = new RateSmoother(60);
        RateSmoother slow = new RateSmoother(60);
        fast.next(0, 1.0);
        slow.next(0, 1.0);
        // Два тика по секунде против одного через две секунды: итог одинаков.
        fast.next(1_000, 1.001);
        double twoTicks = fast.next(2_000, 1.001);
        double oneTick = slow.next(2_000, 1.001);
        assertEquals(oneTick, twoTicks, 1e-9);
    }

    @Test
    void gapResetsState() {
        RateSmoother s = new RateSmoother(60);
        s.next(0, 1.0);
        // Простой сбора длиннее порога: новое значение берётся как есть.
        assertEquals(1.002, s.next(RateSmoother.GAP_MS + 1, 1.002), 1e-12);
    }

    @Test
    void nonPositiveRateLeavesStateAlone() {
        RateSmoother s = new RateSmoother(60);
        s.next(0, 1.0);
        assertEquals(1.0, s.next(1_000, 0), 1e-12);
        assertEquals(1.0, s.next(2_000, -1), 1e-12);
    }

    @Test
    void resetStartsTheSeriesAgain() {
        RateSmoother s = new RateSmoother(60);
        s.next(0, 1.0);
        s.reset();
        assertEquals(1.005, s.next(1_000, 1.005), 1e-12);
    }
}
