package org.home.data.theory.ou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетика для модуля 2 — ТЗ 72 §7.2. Без этого теста результат по пяти реальным
 * эпизодам не интерпретируется: надо сначала показать, что модуль вообще различает
 * сдвиг уровня и отклонение с возвратом.
 */
class EpisodeShapeTest {

    private static final double FUNDING_HOURS = 8;

    /** Ряд из готовых значений: время в минутах, эпизод — весь ряд целиком. */
    private static OuSeries series(double[] values) {
        double[] times = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i;
        }
        boolean[] inEpisode = new boolean[values.length];
        java.util.Arrays.fill(inEpisode, true);
        return new OuSeries("SYNTH", "синтетика", times, values, inEpisode, "весь ряд",
                "доля", 0, "тест", false);
    }

    private static EpisodeShape shapeOf(double[] values, double theta) {
        OuSeries s = series(values);
        OuSeries.Episode e = new OuSeries.Episode(0, values.length - 1, values.length - 1,
                maxDeviation(values, theta), true);
        return EpisodeShape.of(s, e, theta, FUNDING_HOURS);
    }

    private static double maxDeviation(double[] values, double theta) {
        double max = 0;
        for (double v : values) {
            max = Math.max(max, Math.abs(v - theta));
        }
        return max;
    }

    /**
     * Сдвиг уровня: скачок вверх и плато до конца. Экспоненциальный возврат к θ
     * такую форму описать не может — подгонка обязана быть плохой.
     */
    @Test
    @DisplayName("синтетическая ступенька: подгонка затухания плохая, ступеньки — хорошая")
    void levelShiftLooksLikeStep() {
        int n = 600;
        double[] x = new double[n];
        Random rnd = new Random(21);
        for (int i = 0; i < n; i++) {
            x[i] = (i < 200 ? 0.0 : 0.01) + rnd.nextGaussian() * 0.0002;
        }
        EpisodeShape shape = shapeOf(x, 0.0);

        assertTrue(shape.stepR2() > 0.9, "две константы с разрывом описывают плато почти точно: "
                + shape.stepR2());
        assertTrue(shape.decayR2() < 0.5, "затухание к θ плато не описывает: " + shape.decayR2());
        assertEquals("**ступенька**", shape.shape(), "модуль относит эпизод к сдвигу уровня");
    }

    /**
     * Отклонение с возвратом: OU стартует далеко от θ и возвращается. Подгонка
     * затухания обязана быть хорошей и лучше ступеньки.
     */
    @Test
    @DisplayName("синтетический выброс OU: подгонка затухания хорошая и лучше ступеньки")
    void ouExcursionLooksLikeDecay() {
        int n = 400;
        double kappa = 0.05;
        double theta = 0.0;
        double[] x = new double[n];
        x[0] = 0.01;
        Random rnd = new Random(22);
        for (int i = 1; i < n; i++) {
            // точное дискретное решение OU на шаге 1 минута
            double phi = Math.exp(-kappa);
            double sd = 0.0002 * Math.sqrt((1 - phi * phi) / (2 * kappa));
            x[i] = theta + (x[i - 1] - theta) * phi + rnd.nextGaussian() * sd;
        }
        EpisodeShape shape = shapeOf(x, theta);

        assertTrue(shape.decayR2() > 0.9, "экспоненциальный возврат описывает выброс: "
                + shape.decayR2());
        assertTrue(shape.decayR2() > shape.stepR2(),
                "затухание описывает лучше ступеньки: " + shape.decayR2() + " против " + shape.stepR2());
        assertEquals("затухание", shape.shape(), "модуль относит эпизод к отклонению с возвратом");
    }

    /**
     * Главный критерий §7.2: модуль обязан различать эти два случая, а не относить
     * оба к одному классу. Проверяется на одной и той же амплитуде и длине.
     */
    @Test
    @DisplayName("модуль различает сдвиг уровня и отклонение с возвратом")
    void moduleSeparatesTheTwoCases() {
        int n = 400;
        Random rnd = new Random(23);
        double[] step = new double[n];
        double[] decay = new double[n];
        for (int i = 0; i < n; i++) {
            step[i] = (i < 100 ? 0.0 : 0.01) + rnd.nextGaussian() * 0.0002;
            decay[i] = 0.01 * Math.exp(-0.02 * i) + rnd.nextGaussian() * 0.0002;
        }
        EpisodeShape stepShape = shapeOf(step, 0.0);
        EpisodeShape decayShape = shapeOf(decay, 0.0);

        assertEquals("**ступенька**", stepShape.shape());
        assertEquals("затухание", decayShape.shape());
        assertTrue(stepShape.stepR2() - stepShape.decayR2() > 0.3, "у ступеньки разрыв в пользу "
                + "ступеньки заметный: " + (stepShape.stepR2() - stepShape.decayR2()));
        assertTrue(decayShape.decayR2() - decayShape.stepR2() > 0.1, "у затухания — в пользу "
                + "затухания: " + (decayShape.decayR2() - decayShape.stepR2()));
    }

    /** Эпизод короче четырёх точек формы не имеет и не должен попадать в счёт. */
    @Test
    @DisplayName("слишком короткий эпизод не классифицируется")
    void shortEpisodeIsNotClassified() {
        EpisodeShape shape = shapeOf(new double[]{0.01, 0.009}, 0.0);
        assertFalse(shape.classified(), "два наблюдения — не форма");
        assertEquals("—", shape.shape());
    }

    /** Час границы по модулю интервала funding: 0 = момент расчёта, значение всегда в [0, 8). */
    @Test
    @DisplayName("часы по модулю интервала funding считаются от начала ряда и лежат в [0, 8)")
    void fundingModuloIsInRange() {
        int n = 700;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = 0.01 * Math.exp(-0.01 * i);
        }
        EpisodeShape shape = shapeOf(x, 0.0);
        assertTrue(shape.startModFunding() >= 0 && shape.startModFunding() < FUNDING_HOURS,
                "старт в пределах интервала: " + shape.startModFunding());
        // ряд начинается в минуте 0 и длится 699 минут = 11.65 часа → конец на 3.65 ч
        assertEquals(0.0, shape.startModFunding(), 1e-9);
        assertEquals((n - 1) / 60.0 % FUNDING_HOURS, shape.endModFunding(), 1e-9);
    }
}
