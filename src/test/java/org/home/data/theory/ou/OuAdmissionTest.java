package org.home.data.theory.ou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты допуска — ТЗ 67 §7.2, §7.3. Ключевое: модуль обязан отличать «не OU» от
 * «OU, но бесполезный» и ловить сдвиг уровня, который сам по себе может пройти
 * тест на стационарность.
 */
class OuAdmissionTest {

    private static OuSeries series(String id, double[] values, double thresholdSd, String unit) {
        double[] times = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i;
        }
        double mean = OuCalibration.mean(values);
        double sd = Math.sqrt(variance(values));
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i] - mean) > thresholdSd * sd;
        }
        return new OuSeries(id, "синтетика", times, values, inEpisode,
                "|x − среднее| > " + thresholdSd + "σ", unit, 0, "тест", false);
    }

    private static OuAdmission.Result evaluate(OuSeries s, double horizon) {
        double[] dt = s.steps();
        OuCalibration.Fit fit = OuCalibration.ols(dt, s.values());
        double halfLife95 = OuCalibration.Bootstrap.quantile(
                OuCalibration.bootstrap(fit, dt, 200, 42).halfLives(), 0.95);
        return OuAdmission.evaluate(s, fit, halfLife95, 250, 0.05, 0, 0.5, 30, 1.0, horizon);
    }

    private static OuAdmission.Check check(OuAdmission.Result r, String prefix) {
        return r.checks().stream().filter(c -> c.id().startsWith(prefix)).findFirst().orElseThrow();
    }

    private static double variance(double[] v) {
        double m = OuCalibration.mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return v.length > 1 ? s / (v.length - 1) : 0;
    }

    @Test
    @DisplayName("быстрый OU проходит все тесты допуска")
    void fastOuPasses() {
        double[] dt = new double[1999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(0.25, 0, 0.1, dt, new Random(1));
        OuAdmission.Result r = evaluate(series("FAST", x, 0.25, "дни"), 30);
        assertEquals(OuAdmission.Verdict.PASSES, r.verdict(), r.checks().toString());
    }

    @Test
    @DisplayName("медленный OU проваливает ИМЕННО T2, а не T1")
    void slowOuFailsOnlyT2() {
        double[] dt = new double[4999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(Math.log(2) / 200, 0, 0.1, dt, new Random(2));
        OuAdmission.Result r = evaluate(series("SLOW", x, 0.25, "дни"), 30);
        assertTrue(check(r, "T1").passed(), "медленный OU обязан пройти T1: " + check(r, "T1").detail());
        assertTrue(!check(r, "T2").passed(), "и провалиться на T2: " + check(r, "T2").detail());
        assertEquals(OuAdmission.Verdict.TOO_SLOW, r.verdict());
    }

    @Test
    @DisplayName("разрыв уровня θ в середине ловится T4")
    void levelBreakCaughtByT4() {
        double[] dt = new double[3999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(0.25, 0, 0.1, dt, new Random(3));
        for (int i = x.length / 2; i < x.length; i++) {
            x[i] += 0.5;                            // сдвиг уровня в 2.5 σ стационарного распределения
        }
        OuAdmission.Result r = evaluate(series("BREAK", x, 0.25, "дни"), 30);
        assertTrue(!check(r, "T4").passed(), "сдвиг уровня обязан ловиться: " + check(r, "T4").detail());
        assertEquals(OuAdmission.Verdict.NOT_OU, r.verdict());
    }

    @Test
    @DisplayName("κ, меняющаяся вдвое между половинами, ловится T3")
    void changingKappaCaughtByT3() {
        double[] dtHalf = new double[2499];
        Arrays.fill(dtHalf, 1.0);
        double[] fast = OuCalibration.simulate(0.5, 0, 0.1, dtHalf, new Random(4));
        double[] slow = OuCalibration.simulate(0.05, 0, 0.0316, dtHalf, new Random(5));
        double[] x = new double[fast.length + slow.length];
        System.arraycopy(fast, 0, x, 0, fast.length);
        System.arraycopy(slow, 0, x, fast.length, slow.length);
        OuAdmission.Result r = evaluate(series("KAPPA_SHIFT", x, 0.25, "дни"), 30);
        assertTrue(!check(r, "T3").passed(),
                "разная скорость возврата в половинах обязана ловиться: " + check(r, "T3").detail());
    }

    @Test
    @DisplayName("отсутствие эпизодов даёт «недостаточно данных», а не «не OU»")
    void noEpisodesMeansInsufficientData() {
        double[] dt = new double[999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(0.25, 0, 0.1, dt, new Random(6));
        boolean[] never = new boolean[x.length];    // порог не достигается никогда
        double[] times = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            times[i] = i;
        }
        OuSeries s = new OuSeries("NO_EPISODES", "синтетика", times, x, never,
                "порог недостижим", "дни", 0, "тест", false);
        assertEquals(OuAdmission.Verdict.INSUFFICIENT_DATA, evaluate(s, 30).verdict());
    }

    @Test
    @DisplayName("определение эпизода воспроизводимо: тот же ряд — то же число эпизодов")
    void episodesReproducible() {
        double[] dt = new double[999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(0.25, 0, 0.1, dt, new Random(7));
        OuSeries s = series("REPRO", x, 0.5, "дни");
        List<OuSeries.Episode> first = s.episodes(0);
        List<OuSeries.Episode> second = s.episodes(0);
        assertEquals(first.size(), second.size());
        assertTrue(first.size() > 5, "эпизоды вообще находятся: " + first.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).from(), second.get(i).from());
            assertEquals(first.get(i).duration(), second.get(i).duration(), 0.0);
        }
    }

    @Test
    @DisplayName("эпизод длится до возврата к уровню, а не до первого нырка под порог")
    void episodeEndsAtLevelCrossing() {
        // ряд: уходит вверх за порог, колеблется выше уровня, потом пересекает уровень
        double[] values = {0, 2, 1.5, 1.8, 1.2, 0.9, -0.1, 0.2, 0.1};
        double[] times = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i;
        }
        boolean[] inEpisode = new boolean[values.length];
        inEpisode[1] = true;
        OuSeries s = new OuSeries("EP", "синтетика", times, values, inEpisode, "x > 1.5", "дни", 0, "тест", false);
        List<OuSeries.Episode> eps = s.episodes(0);
        assertEquals(1, eps.size());
        assertEquals(1, eps.getFirst().from());
        assertEquals(6, eps.getFirst().to(), "эпизод обязан длиться до пересечения уровня θ=0");
        assertTrue(eps.getFirst().returned());
    }

    @Test
    @DisplayName("KPSS с полосой по Andrews не отвергает стационарность на персистентном OU")
    void kpssHandlesPersistence() {
        double[] dt = new double[4999];
        Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(Math.log(2) / 200, 0, 0.1, dt, new Random(8));
        StatTests.TestResult withFixedLag = StatTests.kpss(x, 1, 0.05);
        StatTests.TestResult withAndrews = StatTests.kpss(x, StatTests.andrewsBandwidth(x), 0.05);
        assertTrue(withFixedLag.rejected(), "с одним лагом KPSS ошибочно отвергает стационарность");
        assertTrue(!withAndrews.rejected(), "с полосой по Andrews — не отвергает: " + withAndrews.statistic());
    }
}
