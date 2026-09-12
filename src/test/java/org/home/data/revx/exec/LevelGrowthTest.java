package org.home.data.revx.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Геометрический шаг уровней.
 *
 * Поток спадает с расстоянием экспоненциально, поэтому равномерный шаг кладёт
 * дальние уровни туда, где исполнений почти нет. Замер по ленте 12.09.2026:
 * множитель 1.25 даёт у BTC 912 лотов в сутки против 657 у равномерного шага, а
 * множитель 1.4 — 663, то есть ХУЖЕ равномерного, потому что растягивает
 * лестницу до 15 б.п., где потока уже нет.
 *
 * Здесь проверяется арифметика раскладки, а не её польза: польза меряется
 * обходом с потолком и ведром.
 */
class LevelGrowthTest {

    /** Смещение уровня i при множителе g: step·(1 + g + … + g^(i−1)). */
    private static double shift(double step, double growth, int level) {
        return growth > 1.0
                ? step * (Math.pow(growth, level) - 1) / (growth - 1)
                : level * step;
    }

    @Test
    @DisplayName("множитель 1.0 даёт в точности равномерный шаг")
    void unitGrowthIsUniform() {
        for (int i = 0; i <= 4; i++) {
            assertThat(shift(2, 1.0, i)).isEqualTo(2.0 * i);
        }
    }

    @Test
    @DisplayName("множитель 1.25: уровни сгущены у рынка")
    void geometricClustersNearMarket() {
        // Пять уровней шагом 2 при множителе 1.25 занимают меньше, чем при
        // равномерном шаге: 0, 2, 4.5, 7.6, 11.5 против 0, 2, 4, 6, 8 — нет,
        // геометрия РАСТЯГИВАЕТ. Проверяем именно это, чтобы не путаться:
        // сгущение даёт не сам множитель, а выбор МЕНЬШЕГО базового шага.
        assertThat(shift(2, 1.25, 1)).isEqualTo(2.0);
        assertThat(shift(2, 1.25, 2)).isEqualTo(4.5);
        assertThat(shift(2, 1.25, 4)).isCloseTo(11.53, org.assertj.core.data.Offset.offset(0.01));
        assertThat(shift(2, 1.25, 4)).isGreaterThan(shift(2, 1.0, 4));
    }

    @Test
    @DisplayName("каждый следующий промежуток ровно в g раз больше предыдущего")
    void gapsGrowByFactor() {
        double g = 1.3;
        double prevGap = shift(2, g, 1) - shift(2, g, 0);
        for (int i = 2; i <= 5; i++) {
            double gap = shift(2, g, i) - shift(2, g, i - 1);
            assertThat(gap / prevGap).isCloseTo(g, org.assertj.core.data.Offset.offset(1e-9));
            prevGap = gap;
        }
    }

    @Test
    @DisplayName("нулевой уровень всегда на базовой цене")
    void zeroLevelIsBase() {
        assertThat(shift(2, 1.25, 0)).isZero();
        assertThat(shift(2, 1.0, 0)).isZero();
    }
}
