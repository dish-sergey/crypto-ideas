package org.home.data.theory.ou;

import org.home.data.theory.Jlog;
import org.home.data.theory.ParquetOut;
import org.home.data.theory.RunLog;
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
 * Предтест блока B: калибровка и допуск OU (ТЗ 67, позиция каталога B5).
 *
 * <p>Порядок §9 соблюдён: сначала контроли ({@code NEG_BTC}, {@code NEG_RW},
 * {@code POS_SYN}, {@code POS_SYN_SLOW}) — это <b>жёсткая точка остановки</b>:
 * модуль, который пропускает цену BTC как OU-процесс, не может ничего сказать о
 * базисе. Реальные величины считаются после и признаются действительными только
 * если контроли отработали как задумано.
 *
 * <p>CLI: {@code --theory=ou [--out=reports/theory]}.
 */
@Component
@Lazy
public class OuBench {

    private static final Logger log = LoggerFactory.getLogger(OuBench.class);

    /** Версия алгоритма стенда. */
    public static final String ALGO_VERSION = "ou-1.0";

    private final OuSeriesLoader loader;
    private final OuConfig cfg;
    private final RunLog runLog;

    public OuBench(OuSeriesLoader loader, OuConfig cfg, RunLog runLog) {
        this.loader = loader;
        this.cfg = cfg;
        this.runLog = runLog;
    }

    /** Полный результат по одной величине. */
    private record Row(OuSeries series, OuCalibration.Fit ols, OuCalibration.Fit mle,
                       double kappaAnalytic, double kappaBootstrap, double bias,
                       double halfLifeRaw, double halfLifeAnalytic, double halfLifeBootstrap,
                       double halfLife95, OuAdmission.Result admission) {
    }

    public void run(String outDir) {
        List<Row> controls = new ArrayList<>();
        // POS_SYN: быстрый OU, порог эпизода низкий — эпизоды длиннее полупериода
        controls.add(analyse(loader.positiveSynthetic(0.25, 0.0, 0.1, 2000, 0.25)));
        // POS_SYN_SLOW: полупериод длиннее окна применимости, выборки хватает на стационарность
        controls.add(analyse(loader.slowSynthetic(200, 0.1, 5000, 0.25)));
        controls.add(analyse(loader.randomWalk(2000, 0.01)));
        controls.add(analyse(loader.btcLogPrice()));

        boolean controlsOk = controlsPass(controls);

        List<Row> candidates = new ArrayList<>();
        candidates.add(analyse(loader.basisSameVenue("BTCUSDT")));
        candidates.add(analyse(loader.basisStress("BTCUSDT")));
        candidates.add(analyse(loader.basis("PF_XBTUSD", "BTCUSDT")));
        OuSeries depeg = loader.depeg("ETH");
        if (depeg != null) {
            candidates.add(analyse(depeg));
        }
        candidates.add(analyse(loader.fundingDifferential("PF_XBTUSD", "BTCUSDT")));
        candidates.add(analyse(loader.pairSpread()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("controls_ok", controlsOk);
        for (Row r : controls) {
            result.put(r.series().id() + ".verdict", r.admission().verdict().name());
        }
        for (Row r : candidates) {
            result.put(r.series().id() + ".verdict", r.admission().verdict().name());
            result.put(r.series().id() + ".half_life_95", r.halfLife95());
        }
        String runId = runLog.record("ou", ALGO_VERSION, "block-b-pretest", 0, 0, cfg.seed(),
                cfg.asMap(), result);

        Path out = Path.of(outDir);
        List<Object[]> parquet = new ArrayList<>();
        for (Row r : concat(controls, candidates)) {
            parquet.add(new Object[]{r.series().id(), r.series().length(), r.ols().kappa(), r.mle().kappa(),
                    r.kappaBootstrap(), r.ols().theta(), r.ols().sigma(), r.halfLifeRaw(),
                    r.halfLifeBootstrap(), r.halfLife95(), r.admission().episodes(),
                    r.admission().verdict().name()});
        }
        ParquetOut.write(out.resolve("ou_admission.parquet"),
                columns("id", "VARCHAR", "points", "BIGINT", "kappa_ols", "DOUBLE", "kappa_mle", "DOUBLE",
                        "kappa_bootstrap", "DOUBLE", "theta", "DOUBLE", "sigma", "DOUBLE",
                        "half_life_raw", "DOUBLE", "half_life_bootstrap", "DOUBLE", "half_life_95", "DOUBLE",
                        "episodes", "BIGINT", "verdict", "VARCHAR"), parquet);

        writeReport(out.resolve("ou_admission.md"), runId, controls, candidates, controlsOk);
        Jlog.info(log, "ou.done", Map.of("run_id", runId, "controls_ok", controlsOk,
                "out", out.resolve("ou_admission.md").toString()));
    }

    private Row analyse(OuSeries series) {
        long startedMs = System.currentTimeMillis();
        Row row = analyseInner(series);
        Jlog.info(log, "ou.analysed", Map.of("id", series.id(), "points", series.length(),
                "ms", System.currentTimeMillis() - startedMs,
                "verdict", row.admission().verdict().name()));
        return row;
    }

    private Row analyseInner(OuSeries series) {
        double[] dt = series.steps();
        double[] x = series.values();
        if (x.length < 30) {
            OuCalibration.Fit empty = OuCalibration.Fit.of(0, 0, 0, 1, x.length);
            return new Row(series, empty, empty, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    new OuAdmission.Result(OuAdmission.Verdict.INSUFFICIENT_DATA,
                            List.of(new OuAdmission.Check("T0 объём данных", false,
                                    "точек " + x.length + " — калибровать не на чем")),
                            Double.NaN, Double.NaN, 0, Double.NaN, false, Double.NaN));
        }
        OuCalibration.Fit ols = OuCalibration.ols(dt, x);
        OuCalibration.Fit mle = OuCalibration.mle(dt, x);
        double medianDt = OuCalibration.median(dt);
        double kappaAnalytic = OuCalibration.analyticBiasCorrectedKappa(ols.phi(), x.length, medianDt);
        // Число реплик бутстрапа обратно пропорционально длине ряда: на минутном
        // ряде в полмиллиона точек 2000 реплик считаются десятки минут, а интервал
        // и так узкий — точность оценки границы упирается не в число реплик.
        int replicas = Math.min(cfg.bootstrap(),
                Math.max(200, (int) (cfg.bootstrap() * 20000L / Math.max(x.length, 1))));
        OuCalibration.Bootstrap bootstrap = OuCalibration.bootstrap(ols, dt, replicas, cfg.seed());
        double kappaBootstrap = bootstrap.correctedKappa();
        double halfLife95 = OuCalibration.Bootstrap.quantile(bootstrap.halfLives(), 0.95);

        OuCalibration.Fit corrected = OuCalibration.Fit.of(kappaBootstrap, ols.theta(), ols.sigma(),
                medianDt, x.length);
        double horizon = horizonIn(series.timeUnit());
        OuAdmission.Result admission = OuAdmission.evaluate(series, corrected, halfLife95,
                cfg.kappaWindow(), cfg.level(), cfg.lags(), cfg.kappaCvThreshold(),
                cfg.minEpisodes(), cfg.breakToSigma(), horizon);

        return new Row(series, ols, mle, kappaAnalytic, kappaBootstrap, bootstrap.bias(),
                ols.halfLife(), Math.log(2) / Math.max(kappaAnalytic, 1e-12),
                Math.log(2) / Math.max(kappaBootstrap, 1e-12), halfLife95, admission);
    }

    /** Окно применимости в единицах времени ряда: дни, часы или минуты. */
    private double horizonIn(String timeUnit) {
        return switch (timeUnit) {
            case "часы" -> cfg.holdingHorizonDays() * 24;
            case "минуты" -> cfg.holdingHorizonDays() * 24 * 60;
            default -> cfg.holdingHorizonDays();
        };
    }

    /**
     * Условие приёмки §0: {@code NEG_*} обязаны не пройти, {@code POS_SYN} —
     * пройти, {@code POS_SYN_SLOW} — провалиться <b>именно по T2</b>.
     */
    private static boolean controlsPass(List<Row> controls) {
        boolean ok = true;
        for (Row r : controls) {
            OuAdmission.Verdict v = r.admission().verdict();
            switch (r.series().id()) {
                case "NEG_BTC", "NEG_RW" -> ok &= v != OuAdmission.Verdict.PASSES;
                case "POS_SYN" -> ok &= v == OuAdmission.Verdict.PASSES;
                case "POS_SYN_SLOW" -> ok &= v == OuAdmission.Verdict.TOO_SLOW;
                default -> {
                }
            }
        }
        return ok;
    }

    private static List<Row> concat(List<Row> a, List<Row> b) {
        List<Row> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Map<String, String> columns(String... nameThenType) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenType.length; i += 2) {
            m.put(nameThenType[i], nameThenType[i + 1]);
        }
        return m;
    }

    // ----------------------------------------------------------------- отчёт

    private void writeReport(Path out, String runId, List<Row> controls, List<Row> candidates,
                             boolean controlsOk) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Калибровка и допуск OU — предтест блока B (ТЗ 67, позиция B5)\n\n");
        sb.append("Прогон `").append(runId).append("`, код `").append(runLog.gitHash())
                .append("`, версия `").append(ALGO_VERSION).append("`.\n\n");
        sb.append(controlsOk
                ? "> **Контроли отработали как задумано** — результаты по реальным величинам "
                + "действительны.\n\n"
                : "> **КОНТРОЛИ НЕ ОТРАБОТАЛИ. Все результаты по реальным величинам недействительны** "
                + "(§6.3): тесты настроены неверно, и чинить надо их, а не выводы.\n\n");

        sb.append("## Контроли (§6.1 — идут первыми)\n\n");
        sb.append(verdictTable(controls));
        sb.append("\nОжидание: `NEG_BTC` и `NEG_RW` не проходят, `POS_SYN` проходит, "
                + "`POS_SYN_SLOW` проваливается **именно по T2** (модуль обязан отличать «не OU» от "
                + "«OU, но бесполезный»).\n\n");

        sb.append("## Кандидаты\n\n");
        sb.append(verdictTable(candidates));
        sb.append('\n');
        for (Row r : candidates) {
            sb.append("- **").append(r.series().id()).append("** — ").append(r.series().description())
                    .append(". Эпизод: ").append(r.series().episodeRule()).append(". ")
                    .append(r.series().note()).append('\n');
        }
        sb.append('\n');

        sb.append("## Калибровка (§6.1)\n\n");
        sb.append("| Величина | точек | κ МНК | κ MLE | κ после бутстрап-поправки | смещение κ | "
                + "полупериод сырой | после аналитической | после бутстрап | 95-й проц. | θ | σ |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : concat(controls, candidates)) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %d | %.4f | %.4f | %.4f | %+.4f | %s | %s | %s | %s | %.4f | %.4f |%n",
                    r.series().id(), r.series().length(), r.ols().kappa(), r.mle().kappa(),
                    r.kappaBootstrap(), r.bias(), halfLife(r.halfLifeRaw()), halfLife(r.halfLifeAnalytic()),
                    halfLife(r.halfLifeBootstrap()), halfLife(r.halfLife95()),
                    r.ols().theta(), r.ols().sigma()));
        }
        sb.append("\nПолупериоды — в единицах времени ряда (дни или часы, см. столбец «Эпизоды»). ");
        sb.append("**Насколько удлинился полупериод после коррекции** — прямая мера того, насколько "
                + "сырая оценка вводила в заблуждение:\n\n");
        for (Row r : concat(controls, candidates)) {
            if (Double.isFinite(r.halfLifeRaw()) && Double.isFinite(r.halfLifeBootstrap())) {
                sb.append(String.format(Locale.ROOT, "- `%s`: %s → %s %s (×%.2f)%n",
                        r.series().id(), halfLife(r.halfLifeRaw()), halfLife(r.halfLifeBootstrap()),
                        r.series().timeUnit(),
                        r.halfLifeBootstrap() / Math.max(r.halfLifeRaw(), 1e-9)));
            }
        }
        sb.append("\nРасхождение МНК и MLE — сигнал проблемы с данными, а не повод выбрать «лучшую» "
                + "оценку (§4.1); обе идут в отчёт:\n\n");
        for (Row r : concat(controls, candidates)) {
            double divergence = Math.abs(r.ols().kappa() - r.mle().kappa())
                    / Math.max(Math.abs(r.ols().kappa()), 1e-9);
            sb.append(String.format(Locale.ROOT, "- `%s`: расхождение %.0f%% %s%n",
                    r.series().id(), divergence * 100,
                    divergence > cfg.olsMleDivergence() ? "— **выше порога, данные под вопросом**" : ""));
        }

        sb.append("\n## Матрица тестов допуска (§6.1)\n\n");
        sb.append("| Величина | T1 стационарность | T2 полупериод | T3 устойчивость κ | T4 уровень θ | "
                + "T5 данные | Вердикт |\n|---|---|---|---|---|---|---|\n");
        for (Row r : concat(controls, candidates)) {
            sb.append("| ").append(r.series().id());
            for (OuAdmission.Check c : r.admission().checks()) {
                sb.append(" | ").append(c.passed() ? "✓" : "✗");
            }
            for (int i = r.admission().checks().size(); i < 5; i++) {
                sb.append(" | —");
            }
            sb.append(" | **").append(verdictName(r.admission().verdict())).append("** |\n");
        }

        sb.append("\n### Подробности тестов\n\n");
        for (Row r : concat(controls, candidates)) {
            sb.append("**").append(r.series().id()).append("**\n");
            for (OuAdmission.Check c : r.admission().checks()) {
                sb.append("- ").append(c.passed() ? "✓" : "✗").append(' ').append(c.id())
                        .append(": ").append(c.detail()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Эпизоды (§6.1)\n\n");
        sb.append("| Величина | эпизодов | медианная длительность | медианная глубина | "
                + "доля вернувшихся к уровню |\n|---|---|---|---|---|\n");
        for (Row r : concat(controls, candidates)) {
            List<OuSeries.Episode> eps = r.series().episodes(r.ols().theta());
            double returned = eps.isEmpty() ? 0
                    : eps.stream().filter(OuSeries.Episode::returned).count() / (double) eps.size();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f %s | %.4f | %.0f%% |%n",
                    r.series().id(), eps.size(), r.admission().medianEpisode(), r.series().timeUnit(),
                    medianDepth(eps), returned * 100));
        }

        sb.append("\n## Вердикт (§6.2)\n\n");
        sb.append("1. **Прошли ли контроли:** ").append(controlsOk ? "да" : "**НЕТ**").append(". ");
        for (Row r : controls) {
            sb.append(String.format("`%s` → %s; ", r.series().id(), verdictName(r.admission().verdict())));
        }
        sb.append("\n\n2. **Какие величины проходят:**\n\n");
        for (Row r : candidates) {
            sb.append(String.format("- `%s` → **%s**%n", r.series().id(),
                    verdictName(r.admission().verdict())));
        }
        sb.append("\n3. **Удлинение полупериода после коррекции смещения** — см. раздел «Калибровка».\n");
        sb.append("\n4. **Запас у прошедших** (полупериод против медианной длительности эпизода):\n\n");
        for (Row r : candidates) {
            if (r.admission().verdict() == OuAdmission.Verdict.PASSES) {
                sb.append(String.format(Locale.ROOT, "- `%s`: %.1f против %.1f %s%n", r.series().id(),
                        r.halfLife95(), r.admission().medianEpisode(), r.series().timeUnit()));
            }
        }
        sb.append("\n5. **Структурный сдвиг θ:** ");
        for (Row r : candidates) {
            sb.append(String.format(Locale.ROOT, "`%s` sup-Wald=%.1f; ", r.series().id(),
                    r.admission().breakStat()));
        }
        sb.append("\n\n6. **Независимых эпизодов:** ");
        for (Row r : candidates) {
            sb.append(String.format("`%s` — %d; ", r.series().id(), r.admission().episodes()));
        }
        sb.append("\n\n");

        boolean anyPasses = candidates.stream()
                .anyMatch(r -> r.admission().verdict() == OuAdmission.Verdict.PASSES);
        sb.append("### Kill-критерии (§6.3)\n\n");
        List<Row> closed = candidates.stream()
                .filter(r -> r.admission().verdict() == OuAdmission.Verdict.NOT_OU
                        || r.admission().verdict() == OuAdmission.Verdict.TOO_SLOW).toList();
        List<Row> deferred = candidates.stream()
                .filter(r -> r.admission().verdict() == OuAdmission.Verdict.INSUFFICIENT_DATA).toList();
        if (anyPasses) {
            sb.append("Как минимум одна величина прошла допуск — для неё имеет смысл считать пороги "
                    + "(ТЗ на B1/B2/B3/B4).\n\n");
        } else if (deferred.isEmpty()) {
            sb.append("**Ни одна из величин не прошла допуск, и ни одна не отложена по данным** → "
                    + "**весь блок B закрывается**. Это положительный результат: сэкономлены недели "
                    + "бэктестов.\n\n");
        } else {
            sb.append("**Ни одна величина не прошла допуск, но закрыт блок B не целиком** — исходы "
                    + "разные и ведут к разным действиям (§5.6):\n\n");
            for (Row r : closed) {
                sb.append(String.format("- `%s` — **%s**: направление закрывается без бэктеста.%n",
                        r.series().id(), verdictName(r.admission().verdict())));
            }
            for (Row r : deferred) {
                sb.append(String.format(Locale.ROOT,
                        "- `%s` — **недостаточно данных** (%d точек, %d эпизодов): это отложение, а не "
                                + "закрытие. Чтобы вопрос стал решаемым, нужны наблюдения: %s%n",
                        r.series().id(), r.series().length(), r.admission().episodes(),
                        r.series().note()));
            }
            sb.append("\nОтдельно стоит отметить величины, у которых **эпизодов ноль не из-за короткой "
                    + "выборки**, а потому что отклонение ни разу не дошло до порога входа: там нечего "
                    + "торговать при текущих издержках, сколько данных ни накопи — менять надо порог "
                    + "или площадку, а не размер выборки.\n\n");
        }

        sb.append("## Оговорки (§10)\n\n")
                .append("- **OU-модель не умеет «перестать быть OU».** Она описывает возврат к уровню и "
                        + "ничего не знает про обрыв механизма привязки: депег без восстановления (UST), "
                        + "делистинг перпа, остановку funding. Прохождение допуска не защищает от этого "
                        + "класса риска.\n")
                .append("- **Механическая привязка обосновывает возврат качественно, а не количественно.** "
                        + "«Базис обязан вернуться» — правда; «вернётся за время, которое вы готовы ждать» — "
                        + "отдельное утверждение, и его проверяет T2.\n")
                .append("- **Сырая оценка κ завышена**, полупериод без коррекции короче истинного; все "
                        + "выводы сделаны по скорректированной оценке и консервативной границе.\n")
                .append("- **Отсутствие отвержения единичного корня — не подтверждение OU:** ADF на "
                        + "коротких выборках слаб, поэтому требуется положительное свидетельство от обоих "
                        + "тестов сразу.\n");

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записан отчёт " + out, e);
        }
    }

    /**
     * Шапка по каждому ряду. Разрешение и доля отброшенных снимков —
     * <b>обязательные</b> поля (ТЗ 67 §3): без них «эпизодов нет» неотличимо от
     * «мерили не тем инструментом и не с тем разрешением».
     */
    private static String verdictTable(List<Row> rows) {
        StringBuilder sb = new StringBuilder("| Величина | точек | шаг ряда | отброшено по "
                + "рассинхронизации | вердикт |\n|---|---|---|---|---|\n");
        for (Row r : rows) {
            double[] steps = r.series().length() > 1 ? r.series().steps() : new double[]{1};
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.3f %s | %.2f%% | **%s** |%n",
                    r.series().id(), r.series().length(), OuCalibration.median(steps),
                    r.series().timeUnit(), r.series().droppedShare() * 100,
                    verdictName(r.admission().verdict())));
        }
        return sb.toString();
    }

    private static double medianDepth(List<OuSeries.Episode> eps) {
        if (eps.isEmpty()) {
            return Double.NaN;
        }
        double[] d = eps.stream().mapToDouble(OuSeries.Episode::depth).sorted().toArray();
        return d[d.length / 2];
    }

    /**
     * Полупериод в читаемом виде: очень малые значения не должны превращаться в
     * «0.0», а κ, неотличимая от нуля, — в астрономическое число дней (возврата
     * попросту нет).
     */
    private static String halfLife(double value) {
        if (!Double.isFinite(value) || value > 1e6) {
            return "возврата нет (κ ≈ 0)";
        }
        if (value < 0.1) {
            return String.format(Locale.ROOT, "%.4f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String verdictName(OuAdmission.Verdict v) {
        return switch (v) {
            case PASSES -> "ПРОХОДИТ";
            case NOT_OU -> "НЕ ПРОХОДИТ: не OU";
            case TOO_SLOW -> "НЕ ПРОХОДИТ: слишком медленно";
            case INSUFFICIENT_DATA -> "НЕДОСТАТОЧНО ДАННЫХ";
        };
    }
}
