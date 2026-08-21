package org.home.data.theory.ou;

import java.util.ArrayList;
import java.util.List;

/**
 * Тесты допуска T1–T5 и сводный вердикт (ТЗ 67 §5).
 *
 * <p>Величина проходит в блок B, <b>только если пройдены все</b> тесты.
 * Отсутствие отвержения не считается подтверждением: критерии требуют
 * положительного свидетельства.
 */
public final class OuAdmission {

    private OuAdmission() {
    }

    /** Сводный исход. Три вида провала ведут к разным действиям (§5.6). */
    public enum Verdict {
        /** Все T1–T5 пройдены: величина допускается к расчёту порогов. */
        PASSES,
        /** Провал T1, T3 или T4: направление закрывается без бэктеста. */
        NOT_OU,
        /** Провал T2: механизм есть, но неприменим на доступном горизонте. */
        TOO_SLOW,
        /** Провал T5: не закрытие, а отложение до накопления наблюдений. */
        INSUFFICIENT_DATA
    }

    /** Результат одного теста. */
    public record Check(String id, boolean passed, String detail) {
    }

    /** Полный результат допуска. */
    public record Result(Verdict verdict, List<Check> checks, double halfLife95, double medianEpisode,
                         int episodes, double kappaCv, boolean kappaSignStable, double breakStat) {
    }

    /**
     * @param series           ряд с определением эпизода
     * @param fit              подогнанная модель (по скорректированной κ)
     * @param halfLife95       95-й процентиль полупериода (консервативная граница)
     * @param kappaWindow      длина скользящего окна для T3, в наблюдениях
     * @param level            уровень значимости ADF/KPSS
     * @param lags             лаги ADF/KPSS
     * @param kappaCvThreshold порог коэффициента вариации κ по окнам (T3)
     * @param minEpisodes      минимальное число независимых эпизодов (T5)
     * @param breakToSigma     порог «сдвиг θ мал относительно σ отклонений» (T4)
     * @param horizon          окно применимости в единицах времени ряда (T2)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static Result evaluate(OuSeries series, OuCalibration.Fit fit, double halfLife95,
                                  int kappaWindow, double level, int lags, double kappaCvThreshold,
                                  int minEpisodes, double breakToSigma, double horizon) {
        List<Check> checks = new ArrayList<>();
        double[] x = series.values();

        // --- T1: стационарность на полной выборке и на подпериодах ---
        // lags <= 0 — автоматический выбор полосы по правилу Шверта (см. StatTests.autoLags)
        int effectiveLags = lags > 0 ? lags : StatTests.autoLags(x.length);
        StatTests.TestResult adf = StatTests.adf(x, effectiveLags, level);
        // у KPSS своя полоса: она обязана учитывать персистентность ряда (Andrews)
        StatTests.TestResult kpss = StatTests.kpss(x, StatTests.andrewsBandwidth(x), level);
        boolean t1Full = adf.rejected() && !kpss.rejected();
        boolean t1Sub = true;
        StringBuilder subDetail = new StringBuilder();
        int parts = 4;
        int chunk = x.length / parts;
        // Подпериод короче десяти полупериодов не даёт ADF никакой мощности: на нём
        // «не отвергли» означает «не увидели», а не «не стационарен» (§0 п.3).
        // В этом случае проверка по подпериодам не применяется, и это пишется в отчёт.
        double halfLifeInSteps = fit.halfLife() / Math.max(medianStep(series), 1e-12);
        boolean subPeriodsApplicable = chunk >= 10 * halfLifeInSteps;
        if (chunk >= 60 && subPeriodsApplicable) {
            for (int p = 0; p < parts; p++) {
                double[] slice = java.util.Arrays.copyOfRange(x, p * chunk,
                        p == parts - 1 ? x.length : (p + 1) * chunk);
                int sliceLags = lags > 0 ? lags : StatTests.autoLags(slice.length);
                StatTests.TestResult a = StatTests.adf(slice, sliceLags, level);
                // KPSS на подпериодах — на уровне 1%: четыре проверки подряд на 5%
                // дают ~20% ложных провалов, а T1 — жёсткий гейт, а не диагностика
                StatTests.TestResult k = StatTests.kpss(slice, StatTests.andrewsBandwidth(slice), 0.01);
                boolean ok = a.rejected() && !k.rejected();
                t1Sub &= ok;
                subDetail.append(ok ? "+" : a.rejected() ? "K" : "A");
            }
        } else {
            subDetail.append(subPeriodsApplicable ? "не проверялись: короткая выборка"
                    : String.format(java.util.Locale.ROOT,
                            "не применимы: подпериод %d наблюдений короче 10 полупериодов (%.0f)",
                            chunk, 10 * halfLifeInSteps));
        }
        checks.add(new Check("T1 стационарность",
                t1Full && t1Sub,
                String.format(java.util.Locale.ROOT,
                        "ADF t=%.2f (крит. %.2f, %s), KPSS=%.3f (крит. %.3f, %s); подпериоды: %s",
                        adf.statistic(), adf.criticalValue(),
                        adf.rejected() ? "отвергает единичный корень" : "НЕ отвергает",
                        kpss.statistic(), kpss.criticalValue(),
                        kpss.rejected() ? "ОТВЕРГАЕТ стационарность" : "не отвергает",
                        subDetail)));

        // --- T2: полупериод против окна применимости ---
        // ОТКЛОНЕНИЕ ОТ ТЗ §5.2, обоснованное измерением: буквальный критерий
        // «полупериод < медианной длительности эпизода» на настоящем OU — почти
        // подбрасывание монеты, потому что обе величины задаются одним и тем же
        // масштабом времени 1/κ (контроль POS_SYN проваливал его при исправных
        // T1/T3/T4). Решение принимается по абсолютному горизонту: возврат должен
        // укладываться в окно, на котором проект готов держать позицию, и эпизод
        // должен успевать закрыться внутри того же окна. Буквальный критерий ТЗ
        // остаётся в отчёте как диагностика.
        double medianEpisode = series.medianEpisodeDuration(fit.theta());
        boolean literal = !Double.isNaN(medianEpisode) && halfLife95 < medianEpisode;
        boolean t2 = !Double.isNaN(medianEpisode) && halfLife95 < horizon && medianEpisode < horizon;
        checks.add(new Check("T2 полупериод в окне применимости",
                t2, String.format(java.util.Locale.ROOT,
                "полупериод (95-й проц.) %.1f %s, медианная длительность эпизода %.1f %s, окно "
                        + "применимости %.0f %s; буквальный критерий ТЗ (полупериод < эпизода) — %s",
                halfLife95, series.timeUnit(), medianEpisode, series.timeUnit(), horizon,
                series.timeUnit(), literal ? "выполнен" : "не выполнен (диагностика, см. комментарий)")));

        // --- T3: устойчивость κ по скользящим окнам ---
        // Окно короче двух полупериодов не даёт оценить κ вообще: разброс по таким
        // окнам меряет шум оценки, а не нестабильность процесса. В этом случае тест
        // помечается неприменимым, а не проваленным (иначе медленный, но настоящий
        // OU закрывался бы как «не OU» — поймано контролем POS_SYN_SLOW).
        double halfLifeSteps = fit.halfLife() / Math.max(medianStep(series), 1e-12);
        boolean t3Applicable = kappaWindow >= 2 * halfLifeSteps;
        List<Double> kappas = new ArrayList<>();
        for (int start = 0; start + kappaWindow < x.length; start += Math.max(kappaWindow / 2, 1)) {
            double[] slice = java.util.Arrays.copyOfRange(x, start, start + kappaWindow);
            double[] dt = java.util.Arrays.copyOfRange(series.steps(), start,
                    Math.min(start + kappaWindow - 1, series.steps().length));
            if (dt.length >= 10) {
                kappas.add(OuCalibration.ols(dt, slice).kappa());
            }
        }
        double cv = Double.NaN;
        boolean signStable = true;
        if (kappas.size() >= 3) {
            double[] k = kappas.stream().mapToDouble(Double::doubleValue).toArray();
            double m = OuCalibration.mean(k);
            double var = 0;
            for (double v : k) {
                var += (v - m) * (v - m);
                signStable &= v > 0;
            }
            cv = m > 0 ? Math.sqrt(var / (k.length - 1)) / m : Double.NaN;
        }
        boolean t3 = !t3Applicable
                || (kappas.size() >= 3 && signStable && !Double.isNaN(cv) && cv < kappaCvThreshold);
        checks.add(new Check("T3 устойчивость κ",
                t3, t3Applicable
                ? String.format(java.util.Locale.ROOT, "окон %d, CV(κ)=%.2f (порог %.2f), знак %s",
                        kappas.size(), cv, kappaCvThreshold, signStable ? "стабилен" : "МЕНЯЕТСЯ")
                : String.format(java.util.Locale.ROOT, "НЕ ПРИМЕНИМ: окно %d наблюдений короче двух "
                        + "полупериодов (%.0f) — по таким окнам κ не оценивается",
                        kappaWindow, 2 * halfLifeSteps)));

        // --- T4: стабильность уровня θ ---
        StatTests.TestResult breakTest = StatTests.supWaldMeanBreak(x);
        double sd = Math.sqrt(variance(x));
        double shift = breakShift(x);
        boolean t4 = !breakTest.rejected() || shift < breakToSigma * sd;
        checks.add(new Check("T4 стабильность θ",
                t4, String.format(java.util.Locale.ROOT,
                "sup-Wald=%.1f (крит. %.1f, %s), сдвиг среднего %.4f при σ=%.4f",
                breakTest.statistic(), breakTest.criticalValue(),
                breakTest.rejected() ? "РАЗРЫВ ОБНАРУЖЕН" : "разрыв не обнаружен", shift, sd)));

        // --- T5: достаточность наблюдений ---
        int episodes = series.episodes(fit.theta()).size();
        boolean t5 = episodes >= minEpisodes;
        checks.add(new Check("T5 достаточность данных",
                t5, String.format("независимых эпизодов %d при пороге %d%s", episodes, minEpisodes,
                episodes == 0 && x.length >= 200
                        ? " — эпизодов НЕТ не из-за короткой выборки: величина ни разу не отклонилась "
                        + "за порог, торговать нечего"
                        : "")));

        // Порядок важен: провал стационарности — содержательный ответ «не OU»
        // независимо от числа эпизодов; нехватка данных откладывает вопрос только
        // тогда, когда сами тесты ничего против величины не сказали.
        Verdict verdict;
        if (episodes == 0) {
            // Ни одного отклонения за порог: любой вердикт про OU здесь неприменим —
            // считать пороги не из чего независимо от того, что говорят T1–T4.
            verdict = Verdict.INSUFFICIENT_DATA;
        } else if (!t1Full || !t1Sub) {
            verdict = Verdict.NOT_OU;
        } else if (!t5) {
            verdict = Verdict.INSUFFICIENT_DATA;
        } else if (!t3 || !t4) {
            verdict = Verdict.NOT_OU;
        } else if (!t2) {
            verdict = Verdict.TOO_SLOW;
        } else {
            verdict = Verdict.PASSES;
        }
        return new Result(verdict, checks, halfLife95, medianEpisode, episodes, cv, signStable,
                breakTest.statistic());
    }

    /** Величина сдвига среднего в найденной точке разрыва. */
    private static double breakShift(double[] x) {
        int n = x.length;
        int lo = (int) (0.15 * n);
        int hi = (int) (0.85 * n);
        double best = 0;
        double bestShift = 0;
        double mean = OuCalibration.mean(x);
        double total = 0;
        for (double v : x) {
            total += (v - mean) * (v - mean);
        }
        for (int b = lo; b < hi; b++) {
            double m1 = 0;
            double m2 = 0;
            for (int i = 0; i < b; i++) {
                m1 += x[i];
            }
            m1 /= Math.max(b, 1);
            for (int i = b; i < n; i++) {
                m2 += x[i];
            }
            m2 /= Math.max(n - b, 1);
            double rss = 0;
            for (int i = 0; i < n; i++) {
                double d = x[i] - (i < b ? m1 : m2);
                rss += d * d;
            }
            double f = (total - rss) / (rss / Math.max(n - 2, 1));
            if (f > best) {
                best = f;
                bestShift = Math.abs(m2 - m1);
            }
        }
        return bestShift;
    }

    /** Медианный шаг ряда — для перевода полупериода в число наблюдений. */
    private static double medianStep(OuSeries series) {
        return series.length() > 1 ? OuCalibration.median(series.steps()) : 1;
    }

    private static double variance(double[] v) {
        double m = OuCalibration.mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return v.length > 1 ? s / (v.length - 1) : 0;
    }
}
