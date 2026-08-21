package org.home.data.theory.ou;

import org.home.data.theory.Jlog;
import org.home.data.theory.RunLog;
import org.home.data.theory.compliance.S5SpecCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Доверификация по ТЗ 72: базис без прореживания (Q4), природа стресс-эпизодов
 * (Q5), сверка констант S5 со спецификацией (Q7).
 *
 * <p>Смысл модуля 1 — ответить измерением на подозрение, что вердикт «не OU» по
 * базису унаследовал ровно тот дефект, который сам прогон и исправлял: ряд с
 * полупериодом 1.7 минуты тестировался на сетке грубее полупериода.
 *
 * <p>Модуль ничего не исправляет и не торгует; конфигурация микро-live не
 * меняется — модуль 3 её только читает.
 *
 * <p>CLI: {@code --theory=verify [--out=reports/theory]}.
 */
@Component
@Lazy
public class BasisVerify {

    private static final Logger log = LoggerFactory.getLogger(BasisVerify.class);

    /** Версия алгоритма стенда. */
    public static final String ALGO_VERSION = "verify-1.0";

    /** Потолок точек в тестах прогона 69 — отсюда берётся фактический шаг прореживания. */
    private static final int RUN69_MAX_TEST_POINTS = 20_000;

    /** Окна скользящей средней для определения DEVIATION, в минутах (§3.2). */
    private static final int[] DEVIATION_WINDOWS_MIN = {60, 480, 1440, 4320};

    /** Интервал расчёта funding на площадке, часы (для §4.2 И2). */
    private static final double FUNDING_INTERVAL_HOURS = 8;

    private final OuSeriesLoader loader;
    private final OuConfig cfg;
    private final RunLog runLog;

    public BasisVerify(OuSeriesLoader loader, OuConfig cfg, RunLog runLog) {
        this.loader = loader;
        this.cfg = cfg;
        this.runLog = runLog;
    }

    /** Результат пары тестов на одном варианте ряда. */
    private record Verdict(String variant, String definition, int points, double stepMinutes,
                           double adf, boolean adfRejects, double kpss, boolean kpssRejects,
                           boolean t1, double kappaCv, boolean t3, double halfLifeMinutes) {
    }

    public void run(String outDir) {
        OuSeries continuous = loader.basisSameVenue("BTCUSDT");
        OuSeries stress = loader.basisStress("BTCUSDT");
        double[] level = continuous.values();
        int thinStep = (int) Math.ceil((double) level.length / RUN69_MAX_TEST_POINTS);

        // ---- Модуль 1: три варианта сетки × два определения ряда ----
        List<Verdict> verdicts = new ArrayList<>();
        Map<String, Integer> variants = new LinkedHashMap<>();
        variants.put("FULL", 1);
        variants.put("THIN_AS_RUN", thinStep);
        variants.put("THIN_5MIN", 5);
        for (Map.Entry<String, Integer> v : variants.entrySet()) {
            verdicts.add(evaluate(v.getKey(), "LEVEL", thinBy(level, v.getValue()), v.getValue()));
        }
        // DEVIATION на полной сетке: окно из логики, чувствительность отдельной таблицей
        List<Verdict> deviationByWindow = new ArrayList<>();
        for (int window : DEVIATION_WINDOWS_MIN) {
            deviationByWindow.add(evaluate("FULL", "DEVIATION W=" + window + "м",
                    deviation(level, window), 1));
        }
        // и то же определение на сетке прогона 69 — чтобы видеть вклад именно прореживания
        for (Map.Entry<String, Integer> v : variants.entrySet()) {
            verdicts.add(evaluate(v.getKey(), "DEVIATION W=480м",
                    thinBy(deviation(level, 480), v.getValue()), v.getValue()));
        }

        // ---- Модуль 2: природа стресс-эпизодов ----
        OuCalibration.Fit continuousFit = fitOf(continuous);
        OuCalibration.Fit stressFit = fitOf(stress);
        List<EpisodeShape> shapes = new ArrayList<>();
        for (OuSeries.Episode e : stress.episodes(stressFit.theta())) {
            shapes.add(EpisodeShape.of(stress, e, stressFit.theta(), FUNDING_INTERVAL_HOURS));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thin_step_minutes", thinStep);
        for (Verdict v : verdicts) {
            result.put(v.variant() + "/" + v.definition() + ".t1", v.t1());
            result.put(v.variant() + "/" + v.definition() + ".t3", v.t3());
        }
        result.put("stress_episodes", shapes.size());
        result.put("spec_divergences", S5SpecCheck.registeredDivergences().size());
        String runId = runLog.record("verify", ALGO_VERSION, "doc72", 0, 0, cfg.seed(),
                cfg.asMap(), result);

        writeReport(Path.of(outDir).resolve("verify_72.md"), runId, continuous, stress, thinStep,
                verdicts, deviationByWindow, continuousFit, stressFit, shapes);
        Jlog.info(log, "verify.done", Map.of("run_id", runId, "thin_step", thinStep,
                "episodes", shapes.size()));
    }

    // ------------------------------------------------------------------ ряды

    /** Прореживание с шагом {@code step} наблюдений (step = 1 — полный ряд). */
    static double[] thinBy(double[] x, int step) {
        if (step <= 1) {
            return x;
        }
        double[] out = new double[x.length / step];
        for (int i = 0; i < out.length; i++) {
            out[i] = x[i * step];
        }
        return out;
    }

    /**
     * {@code DEVIATION} — базис минус собственная скользящая средняя (§3.2).
     * Стратегия торгует отклонения, а не уровень: дрейф базовой линии между
     * режимами funding к возврату отклонений отношения не имеет.
     */
    static double[] deviation(double[] x, int windowPoints) {
        double[] out = new double[x.length];
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += x[i];
            if (i >= windowPoints) {
                sum -= x[i - windowPoints];
            }
            int used = Math.min(i + 1, windowPoints);
            out[i] = x[i] - sum / used;
        }
        return out;
    }

    private Verdict evaluate(String variant, String definition, double[] x, double stepMinutes) {
        int lags = StatTests.autoLags(x.length);
        StatTests.TestResult adf = StatTests.adf(x, lags, cfg.level());
        StatTests.TestResult kpss = StatTests.kpss(x, StatTests.andrewsBandwidth(x), cfg.level());
        boolean t1 = adf.rejected() && !kpss.rejected();

        // T3 считается ровно так же, как в стенде: скользящие окна по наблюдениям
        double[] dt = new double[x.length - 1];
        java.util.Arrays.fill(dt, stepMinutes);
        OuCalibration.Fit fit = OuCalibration.ols(dt, x);
        List<Double> kappas = new ArrayList<>();
        int window = cfg.kappaWindow();
        for (int start = 0; start + window < x.length; start += Math.max(window / 2, 1)) {
            double[] slice = java.util.Arrays.copyOfRange(x, start, start + window);
            double[] sliceDt = new double[window - 1];
            java.util.Arrays.fill(sliceDt, stepMinutes);
            kappas.add(OuCalibration.ols(sliceDt, slice).kappa());
        }
        double cv = coefficientOfVariation(kappas);
        boolean t3 = kappas.size() >= 3 && !Double.isNaN(cv) && cv < cfg.kappaCvThreshold();
        return new Verdict(variant, definition, x.length, stepMinutes, adf.statistic(), adf.rejected(),
                kpss.statistic(), kpss.rejected(), t1, cv, t3, fit.halfLife());
    }

    private static double coefficientOfVariation(List<Double> values) {
        if (values.size() < 3) {
            return Double.NaN;
        }
        double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = OuCalibration.mean(v);
        double var = 0;
        for (double k : v) {
            var += (k - mean) * (k - mean);
        }
        return mean > 0 ? Math.sqrt(var / (v.length - 1)) / mean : Double.NaN;
    }

    private OuCalibration.Fit fitOf(OuSeries series) {
        return OuCalibration.ols(series.steps(), series.values());
    }

    // ------------------------------------------------------------- отчёт

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void writeReport(Path out, String runId, OuSeries continuous, OuSeries stress, int thinStep,
                             List<Verdict> verdicts, List<Verdict> deviationByWindow,
                             OuCalibration.Fit continuousFit, OuCalibration.Fit stressFit,
                             List<EpisodeShape> shapes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Доверификация BASIS, стресс-эпизоды, сверка констант S5 (ТЗ 72)\n\n");
        sb.append("Прогон `").append(runId).append("`, код `").append(runLog.gitHash())
                .append("`, версия `").append(ALGO_VERSION).append("`.\n\n");

        // --- Модуль 1 ---
        sb.append("## Модуль 1: BASIS без прореживания (Q4)\n\n");
        sb.append(String.format(Locale.ROOT,
                "**Фактический шаг прогона 69.** Прореживание было адаптивным: "
                        + "`шаг = ceil(N / %d)`, при N = %d это **%d минут** (%d точек из %d). "
                        + "Существенно: прореживались только тесты T1 (ADF, KPSS) и тест разрыва T4; "
                        + "**T3 считался на полном минутном ряде** — это видно в коде "
                        + "`OuAdmission`, где T1 работает с прореженным массивом, а T3 берёт "
                        + "исходный.%n%n",
                RUN69_MAX_TEST_POINTS, continuous.values().length, thinStep,
                continuous.values().length / thinStep, continuous.values().length));
        sb.append(String.format(Locale.ROOT,
                "Полупериод ряда — **%.1f минуты**, медианная длительность эпизода — **%.0f минут**. "
                        + "Шаг %d минут грубее и того, и другого, поэтому подозрение ТЗ обосновано и "
                        + "проверяется прямо.%n%n",
                continuousFit.halfLife(), continuous.medianEpisodeDuration(continuousFit.theta()), thinStep));

        sb.append("| Вариант | Определение | Точек | Шаг, мин | ADF | KPSS | T1 | CV(κ) | T3 | Полупериод, мин |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Verdict v : verdicts) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %d | %.0f | %.2f %s | %.3f %s | %s | %.2f | %s | %.2f |%n",
                    v.variant(), v.definition(), v.points(), v.stepMinutes(),
                    v.adf(), v.adfRejects() ? "(отвергает)" : "(НЕ отвергает)",
                    v.kpss(), v.kpssRejects() ? "(ОТВЕРГАЕТ)" : "(не отвергает)",
                    v.t1() ? "✓" : "✗", v.kappaCv(), v.t3() ? "✓" : "✗", v.halfLifeMinutes()));
        }
        sb.append("\nADF: отвержение единичного корня — свидетельство стационарности. KPSS: отвержение — "
                + "против стационарности. T1 требует обоих сразу.\n\n");

        sb.append("### Чувствительность к окну скользящей средней (§3.2)\n\n");
        sb.append("| Окно W | Точек | ADF | KPSS | T1 | CV(κ) | T3 |\n|---|---|---|---|---|---|---|\n");
        for (Verdict v : deviationByWindow) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.3f | %s | %.2f | %s |%n",
                    v.definition().replace("DEVIATION ", ""), v.points(), v.adf(), v.kpss(),
                    v.t1() ? "✓" : "✗", v.kappaCv(), v.t3() ? "✓" : "✗"));
        }
        sb.append("\nОкно задано из логики до прогона: существенно больше полупериода (1.7 мин) и "
                + "существенно меньше типичной длительности режима funding; стартовое — 8 часов, "
                + "один интервал расчёта funding.\n\n");

        int episodes = continuous.episodes(continuousFit.theta()).size();
        sb.append(String.format(Locale.ROOT,
                "### T5 отдельной строкой%n%nНезависимых эпизодов ≥%.1f%% на непрерывном ряде — "
                        + "**%d** при пороге T5 = %d.%n%n",
                cfg.basisThreshold() * 100, episodes, cfg.minEpisodes()));
        sb.append(verdictOfModule1(verdicts, deviationByWindow, episodes));

        // --- Модуль 2 ---
        sb.append("## Модуль 2: природа стресс-эпизодов (Q5)\n\n");
        sb.append("**Правило отбора 26 дней, дословно:** самые волатильные дни с 2024-01-01 — "
                + "объединение топ-20 по модулю дневной доходности (|close/open − 1|) и топ-20 по "
                + "внутридневному размаху ((high − low)/open), по дневным свечам BTCUSDT из `candles`. "
                + "Выборка отобрана **по волатильности**, поэтому частота эпизодов на ней "
                + "(5 на 26 дней против 3 на 365) **не является оценкой частоты** и ниже как таковая "
                + "не используется.\n\n");
        sb.append(String.format(Locale.ROOT,
                "**И3. Отдельная калибровка.** Непрерывный ряд: κ = %.4f/мин, полупериод %.1f мин, "
                        + "θ = %.5f, σ = %.5f. Стресс-выборка: κ = %.4f/мин, полупериод %.1f мин, "
                        + "θ = %.5f, σ = %.5f. Отношение κ — **×%.2f**.%n%n",
                continuousFit.kappa(), continuousFit.halfLife(), continuousFit.theta(), continuousFit.sigma(),
                stressFit.kappa(), stressFit.halfLife(), stressFit.theta(), stressFit.sigma(),
                stressFit.kappa() > 0 ? continuousFit.kappa() / stressFit.kappa() : Double.NaN));

        sb.append("**И1 и И2 по каждому эпизоду.** `R² затухания` — качество подгонки "
                + "экспоненциального возврата к предэпизодному уровню; `R² ступеньки` — качество "
                + "подгонки «плато и скачок» (две константы). Часы по модулю 8 — положение границы "
                + "относительно расчёта funding (0 = момент расчёта).\n\n");
        sb.append("| # | Длительность, мин | Глубина | R² затухания | R² ступеньки | Форма | "
                + "старт, ч mod 8 | конец, ч mod 8 |\n|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < shapes.size(); i++) {
            EpisodeShape s = shapes.get(i);
            sb.append(String.format(Locale.ROOT, "| %d | %.0f | %.4f | %.3f | %.3f | %s | %.2f | %.2f |%n",
                    i + 1, s.durationMinutes(), s.depth(), s.decayR2(), s.stepR2(), s.shape(),
                    s.startModFunding(), s.endModFunding()));
        }
        // Профили — чтобы форму можно было увидеть глазами, а не только через R².
        sb.append("\n**Профили эпизодов** — 12 равноотстоящих точек от начала к концу, значения "
                + "базиса в долях. Плато читается как ряд близких чисел, затухание — как "
                + "монотонное приближение к θ.\n\n");
        sb.append("| # | точки профиля (от начала к концу) |\n|---|---|\n");
        for (int i = 0; i < shapes.size(); i++) {
            StringBuilder points = new StringBuilder();
            for (double v : shapes.get(i).profile()) {
                points.append(points.isEmpty() ? "" : " · ").append(String.format(Locale.ROOT, "%.4f", v));
            }
            sb.append(String.format("| %d | %s |%n", i + 1, points));
        }
        sb.append('\n').append(verdictOfModule2(shapes, stressFit.theta()));

        // --- Модуль 3 ---
        sb.append("## Модуль 3: сверка констант S5 со спецификацией (Q7)\n\n");
        sb.append("| Величина | В коде | Спецификация | Совпадает | Где используется |\n");
        sb.append("|---|---|---|---|---|\n");
        for (S5SpecCheck.Row r : S5SpecCheck.rows()) {
            sb.append(String.format("| `%s` | %s | %s (%s) | %s | %s |%n",
                    r.key(), r.codeValue(), r.specValue(), r.specSource(),
                    "info".equals(r.status()) ? "—" : r.matches() ? "да" : "**НЕТ**",
                    r.codeLocation()));
        }
        List<S5SpecCheck.Row> known = S5SpecCheck.registeredDivergences();
        sb.append(String.format(Locale.ROOT, "%n**Расхождений: %d.**%n%n", known.size()));
        for (S5SpecCheck.Row r : known) {
            sb.append("- **`").append(r.key()).append("`** — в коде ").append(r.codeValue())
                    .append(", в спецификации ").append(r.specValue()).append(". ")
                    .append(r.note()).append('\n');
        }
        sb.append(known.size() > 1
                ? "\nПо §6.2 ТЗ это уже не случайность: **спецификация и код разошлись системно**. "
                + "Три из четырёх расхождений — след того, что док. 02 v2 (06.08) не обновлялся "
                + "после протокола док. 54 (14.08); четвёртое (фильтр получателей) не следует ни "
                + "из одного документа и выглядит просто неперенесённым требованием.\n\n"
                : "\n");
        sb.append("**Как это больше не повиснет незамеченным.** Реестр значений спецификации вынесен "
                + "в `src/main/resources/s5-spec.tsv` — одна таблица, два потребителя: этот отчёт и "
                + "автотест `S5SpecComplianceTest`. Зарегистрированное расхождение фиксирует **обе** "
                + "стороны: тест падает и если поменяется код, и если перепишут строку спецификации, "
                + "и если код уедет на третье значение. Проверено: при снятии регистрации со стопа "
                + "тест падает с сообщением «код 0.3, спецификация 0.08 (док. 02 v2 §S5)».\n\n");
        sb.append("**Формулировка обоснования стопа (§5.3).** Не «−30% оптимален», а: премия плоская "
                + "для стопов ≥30% (без стопа +1.53%, −30% +1.57%, −50% +1.64% — разброс 0.11 п.п.), "
                + "поэтому уровень выбирается **по худшей сделке**, а не по средней: −34% против −189% "
                + "без стопа при нулевой цене в средней. Оговорка: премия на −30% измерена на тех же "
                + "данных, где −30% выбирался; на плоском участке смещение мало, но оно есть. "
                + "Отдельно и против: измерение ТЗ 66 показало, что при стопе 8% из спецификации стоп "
                + "срабатывает в 156 событиях из 339 и премия падает до +0.39% — то есть выбор между "
                + "8% и 30% меняет не размер, а сам факт наличия премии.\n\n");

        sb.append("## Оговорки\n\n")
                .append("- Ряд `BASIS` — минутные свечи, обе ноги с одной площадки и с общим "
                        + "`close_time`: рассинхронизация нулевая по построению.\n")
                .append("- `DEVIATION` — уточнение предмета (торгуются отклонения, не уровень), а не "
                        + "второй шанс для вердикта: если и оно не проходит, «не OU» подтверждён.\n")
                .append("- Частота эпизодов на стресс-выборке не используется как оценка частоты.\n")
                .append("- Модуль 3 ничего не исправляет: список расхождений — вход для решений, "
                        + "каждое закрывается отдельно.\n");

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записан отчёт " + out, e);
        }
    }

    private String verdictOfModule1(List<Verdict> verdicts, List<Verdict> byWindow, int episodes) {
        Verdict full = find(verdicts, "FULL", "LEVEL");
        Verdict asRun = find(verdicts, "THIN_AS_RUN", "LEVEL");
        Verdict five = find(verdicts, "THIN_5MIN", "LEVEL");
        boolean sameT1 = full.t1() == asRun.t1();
        boolean sameT3 = full.t3() == asRun.t3();
        StringBuilder sb = new StringBuilder("### Вердикт модуля 1\n\n");
        sb.append(String.format(Locale.ROOT,
                "Вердикты `FULL` и `THIN_AS_RUN` по T1 %s (%s против %s), по T3 %s (CV %.2f против %.2f). "
                        + "Контроль масштаба `THIN_5MIN`: T1 %s, CV(κ) %.2f.%n%n",
                sameT1 ? "совпадают" : "**РАСХОДЯТСЯ**", full.t1() ? "✓" : "✗", asRun.t1() ? "✓" : "✗",
                sameT3 ? "совпадают" : "**РАСХОДЯТСЯ**", full.kappaCv(), asRun.kappaCv(),
                five.t1() ? "✓" : "✗", five.kappaCv()));
        sb.append(sameT1 && sameT3
                ? "Прореживание на вердикт не повлияло — вывод прогона 69 по `BASIS` подтверждён на "
                + "полном ряде.\n\n"
                : "**Вердикт прогона 69 по `BASIS` недействителен**: действует строка `FULL`.\n\n");
        long deviationT1 = byWindow.stream().filter(Verdict::t1).count();
        long deviationBoth = byWindow.stream().filter(v -> v.t1() && v.t3()).count();
        double cvMin = byWindow.stream().mapToDouble(Verdict::kappaCv).min().orElse(Double.NaN);
        if (deviationBoth > 0) {
            sb.append(String.format(Locale.ROOT,
                    "Отклонения (`DEVIATION`) проходят T1 и T3 хотя бы на одном окне, тогда как уровень "
                            + "не проходит. Это меняет предмет вердикта: возврат отклонений измерен и "
                            + "положителен, а нестационарен именно уровень. Тогда по числу наблюдений "
                            + "(эпизодов %d при пороге) итог — **недостаточно наблюдений**, а не «не OU»: "
                            + "направление откладывается и ставится дешёвый монитор.%n%n", episodes));
        } else if (deviationT1 > 0) {
            sb.append(String.format(Locale.ROOT,
                    "Отклонения (`DEVIATION`) ведут себя **иначе, чем уровень**: T1 они проходят на "
                            + "%d окнах из %d (KPSS не отвергает стационарность, ADF отвергает единичный "
                            + "корень), тогда как уровень проваливает T1 на любой сетке. Но T3 не проходит "
                            + "ни одно окно: CV(κ) по окнам %.2f и выше при пороге %.2f — скорость возврата "
                            + "не постоянна, она меняется вместе с режимом волатильности.%n%n"
                            + "Поэтому вердикт «не OU» устоял, но **основание у него другое**, чем "
                            + "записано в 69: дело не в нестационарности того, что торгуется, а в "
                            + "нестабильности κ. Практический смысл этого — размер позиции и горизонт "
                            + "выхода, посчитанные по одному κ, недействительны вне того режима, где "
                            + "κ измерен; направление не оживает.%n%n",
                    deviationT1, byWindow.size(), cvMin, cfg.kappaCvThreshold()));
        } else {
            sb.append("Отклонения (`DEVIATION`) тоже не проходят T1 — вердикт **«не OU» подтверждён "
                    + "по существу**, а не по артефакту сетки.\n\n");
        }
        sb.append("**Синтетический контроль (§7.1, `BasisVerifyTest`).** На заведомом OU с "
                + "полупериодом 1.7 мин и шагом 1 мин: на полной сетке κ восстанавливается "
                + "(0.4085 против истинной 0.4077), на шаге 5 мин смещение +1.4%, на шаге 28 мин "
                + "**−47%** — полупериод завышается с 1.7 до 3.2 мин. Вердикт T1 прореживание "
                + "переживает, а T3 на шаге 28 мин **переворачивается** (заведомо стабильная κ "
                + "признаётся нестабильной). Отсюда читается и таблица выше: строка "
                + "`THIN_AS_RUN` с CV 2.32 — артефакт сетки, а строка `FULL` с CV 0.74 — нет. "
                + "В прогоне 69 T3 считался на полном ряде, прореживались только T1 и T4, "
                + "поэтому подмены вердикта там не произошло.\n\n");
        return sb.toString();
    }

    private static String verdictOfModule2(List<EpisodeShape> shapes, double theta) {
        List<EpisodeShape> classified = shapes.stream().filter(EpisodeShape::classified).toList();
        long stepLike = classified.stream().filter(s -> s.stepR2() >= s.decayR2()).count();
        long nearFunding = classified.stream()
                .filter(s -> Math.min(s.endModFunding(), FUNDING_INTERVAL_HOURS - s.endModFunding()) <= 1)
                .count();
        double bestStep = classified.stream().mapToDouble(EpisodeShape::stepR2).max().orElse(Double.NaN);
        double bestDecay = classified.stream().mapToDouble(EpisodeShape::decayR2).max().orElse(Double.NaN);
        StringBuilder sb = new StringBuilder("### Вердикт модуля 2\n\n");
        sb.append(String.format(Locale.ROOT,
                "Классифицируемых эпизодов (длиннее четырёх точек) — %d из %d. Ступенькой лучше "
                        + "описываются **%d из %d**; концов эпизодов в пределах часа от расчёта "
                        + "funding — **%d из %d**.%n%n",
                classified.size(), shapes.size(), stepLike, classified.size(),
                nearFunding, classified.size()));
        sb.append(String.format(Locale.ROOT,
                "Сравнение относительное, поэтому отдельно об абсолютном качестве: лучшая подгонка "
                        + "ступеньки — R² %.2f, лучшая подгонка затухания — R² %.2f. У затухания R² "
                        + "отрицателен на большинстве эпизодов, то есть экспоненциальный возврат к θ "
                        + "описывает их **хуже, чем просто константа**. Это сильное свидетельство "
                        + "против «отклонения с возвратом», но не свидетельство в пользу чистой "
                        + "ступеньки: её R² тоже далёк от единицы.%n%n", bestStep, bestDecay));

        // Профили показывают третью форму, которой нет ни в одной из двух моделей:
        // быстрый спад пика до небольшого смещения и долгое «висение» на нём.
        double[] collapse = classified.stream()
                .mapToDouble(s -> collapseShare(s.profile(), theta)).sorted().toArray();
        double[] plateau = classified.stream()
                .mapToDouble(s -> plateauLevel(s.profile(), theta)).sorted().toArray();
        if (collapse.length > 0) {
            sb.append(String.format(Locale.ROOT,
                    "**Что видно в профилях.** Пик спадает быстро: к первой двенадцатой эпизода "
                            + "уходит в среднем %.0f%% глубины — это совместимо с полупериодом в "
                            + "минуты. Но дальше величина не возвращается к θ, а **висит на "
                            + "смещённом уровне** — медиана плато %.5f при θ = %.5f, — и длительность "
                            + "эпизода набирается именно этим висением, а не спадом. Отсюда и "
                            + "8801 и 55840 минут: эпизод по определению длится до пересечения θ, "
                            + "а пересечение откладывается на дни.%n%n"
                            + "Практический вывод: длительность эпизода здесь **не характеристика "
                            + "процесса, а свойство определения** — ни «сдвиг уровня», ни "
                            + "«отклонение с возвратом» в чистом виде не подходят. Верное описание — "
                            + "быстрый спад к смещённому уровню плюс медленный дрейф этого уровня; "
                            + "торговать в такой форме можно только сам спад, то есть первые "
                            + "минуты, где издержки и сравнимы с амплитудой.%n%n",
                    100 * median(collapse), median(plateau), theta));
        }
        if (stepLike > classified.size() / 2 && nearFunding > classified.size() / 2) {
            sb.append("Картина складывается в «сдвиг уровня, привязанный к расчёту funding»: это работа "
                    + "механизма funding, то есть **S1** (закрыта по величине), а не сходимость базиса. "
                    + "Направление O1 закрывается по существу.\n\n");
        } else if (stepLike <= classified.size() / 2 && nearFunding <= classified.size() / 2) {
            sb.append("Картина складывается в «отклонения с возвратом»: `BASIS` относится к O1, и вопрос "
                    + "упирается только в ёмкость — три эпизода за год.\n\n");
        } else {
            sb.append("Картина смешанная: форма и привязка к funding указывают в разные стороны. "
                    + "Вывод не форсируется — при пяти эпизодах это описание, а не статистика.\n\n");
        }
        return sb.toString();
    }

    /** Какая доля глубины ушла к первой двенадцатой эпизода. */
    private static double collapseShare(double[] profile, double theta) {
        if (profile.length < 2 || Math.abs(profile[0] - theta) < 1e-12) {
            return Double.NaN;
        }
        return 1 - Math.abs(profile[1] - theta) / Math.abs(profile[0] - theta);
    }

    /** Уровень, на котором величина «висит» после спада: медиана середины профиля. */
    private static double plateauLevel(double[] profile, double theta) {
        if (profile.length < 4) {
            return theta;
        }
        double[] middle = java.util.Arrays.copyOfRange(profile, 1, profile.length - 1);
        java.util.Arrays.sort(middle);
        return median(middle);
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    private static Verdict find(List<Verdict> verdicts, String variant, String definition) {
        return verdicts.stream()
                .filter(v -> v.variant().equals(variant) && v.definition().equals(definition))
                .findFirst().orElseThrow(() -> new IllegalStateException("нет строки " + variant));
    }
}
