package org.home.data.theory.alloc;

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
 * Стенд сравнения аллокаторов (ТЗ 65, позиции каталога E1 + E4).
 *
 * <p>Порядок §9 соблюдён буквально: сначала тривиальный бенчмарк {@code EW} и
 * ориентиры {@code BEST_SINGLE} / {@code BEST_FIXED}, затем <b>точка
 * остановки</b> — бьёт ли недостижимый ориентир {@code BEST_FIXED} однострочный
 * {@code SMA200}. Если не бьёт, вопрос об аллокации поверх этого пула закрыт
 * целиком, и всё, что считается дальше, — справочный материал, а не кандидаты во
 * внедрение. Отчёт говорит это первой строкой вердикта.
 *
 * <p>CLI: {@code --theory=alloc [--out=reports/theory]}.
 */
@Component
@Lazy
public class AllocBench {

    private static final Logger log = LoggerFactory.getLogger(AllocBench.class);

    /** Версия алгоритма стенда: менять при любом изменении правил §3–§5. */
    public static final String ALGO_VERSION = "alloc-1.0";

    private final CurveBuilder builder;
    private final AllocConfig cfg;
    private final RunLog runLog;

    public AllocBench(CurveBuilder builder, AllocConfig cfg, RunLog runLog) {
        this.builder = builder;
        this.cfg = cfg;
        this.runLog = runLog;
    }

    /** Строка отчёта: результат одного прогона. */
    private record Row(String id, String group, AllocEngine.Result result, Metrics metrics,
                       double regretVsSingle, double regretVsFixed, double bound) {
    }

    public void run(String outDir) {
        CurveSet set = builder.build();
        List<Row> rows = new ArrayList<>();
        List<Object[]> series = new ArrayList<>();
        List<Object[]> gridRows = new ArrayList<>();

        AllocEngine engine = new AllocEngine(set, cfg.switchCost());
        int n = set.size();
        int t = set.length();
        double scale = Allocators.observedScale(set.retMatrix(), n);
        double etaStar = Allocators.etaStar(n, t, scale);
        double bound = Metrics.hedgeRegretBound(set.retMatrix(), n, t);

        // ---- этап 3 §9: тривиальный бенчмарк и ориентиры — первыми ----
        AllocEngine.Result ewAll = engine.run(new Allocators.EqualWeight("EW", Allocators.Normalization.ALL));
        AllocEngine.Result ewAvail = engine.run(new Allocators.EqualWeight("EW_AVAIL_ONLY", Allocators.Normalization.AVAILABLE_ONLY));
        int bestSingle = engine.bestSingleIndex();
        double[] singleMix = new double[n];
        singleMix[bestSingle] = 1;
        AllocEngine.Result single = engine.run(new Allocators.Fixed("BEST_SINGLE", singleMix, true));
        double[] fixedMix = engine.bestFixedMix(cfg.bestFixedIterations(), cfg.bestFixedStep());
        AllocEngine.Result fixed = engine.run(new Allocators.Fixed("BEST_FIXED", fixedMix, true));

        // ---- этап 5 §9: остальные аллокаторы ----
        List<Allocator> rest = new ArrayList<>();
        rest.add(new Allocators.Detector());
        rest.add(new Allocators.Hedge("HEDGE", etaStar * cfg.hedgeEtaMultiplier(), cfg.hedgeWindow()));
        rest.add(new Allocators.ExponentiatedGradient(cfg.egEta()));
        rest.add(new Allocators.OnlineNewtonStep(cfg.onsBeta(), cfg.onsEpsilon()));
        rest.add(new Allocators.RandomWeights("RANDOM", cfg.seed(), cfg.randomRedrawDays()));
        rest.add(new Allocators.LazyHedge(
                new Allocators.Hedge("HEDGE", etaStar * cfg.hedgeEtaMultiplier(), cfg.hedgeWindow()),
                cfg.lazyThreshold()));

        List<AllocEngine.Result> results = new ArrayList<>(List.of(ewAll, ewAvail, single, fixed));
        for (Allocator a : rest) {
            results.add(engine.run(a));
        }
        // бенчмарки как отдельные «портфели» на том же движке издержек
        Map<String, AllocEngine.Result> benchmarkResults = new LinkedHashMap<>();
        for (Map.Entry<String, Curve> e : set.benchmarks().entrySet()) {
            benchmarkResults.put(e.getKey(), benchmarkResult(set, e.getValue()));
        }

        int trials = results.size() + benchmarkResults.size();
        for (AllocEngine.Result r : results) {
            rows.add(row(r.allocId(), group(r.allocId()), r, set, single, fixed, bound, trials));
        }
        for (Map.Entry<String, AllocEngine.Result> e : benchmarkResults.entrySet()) {
            rows.add(row(e.getKey(), "бенчмарк", e.getValue(), set, single, fixed, bound, trials));
        }
        for (Row r : rows) {
            for (int day = 0; day < t; day++) {
                series.add(new Object[]{r.id(), set.days()[day], set.dayMs()[day],
                        r.result().ret()[day], r.result().equity()[day + 1]});
            }
        }

        // ---- §5: обязательные прогоны (сетки чувствительности) ----
        double[] randomFixed = randomFixedCagr(set, cfg.randomFixedDraws(), trials);
        gridRows.addAll(etaGrid(set, etaStar, trials));
        gridRows.addAll(costGrid(set, etaStar, trials));
        gridRows.addAll(windowGrid(set, etaStar, trials));
        List<Object[]> loo = leaveOneOut(set, etaStar, trials);
        gridRows.addAll(loo);

        Path out = Path.of(outDir);
        ParquetOut.write(out.resolve("alloc_series.parquet"),
                columns("alloc_id", "VARCHAR", "day", "VARCHAR", "day_ms", "BIGINT",
                        "ret", "DOUBLE", "equity", "DOUBLE"), series);
        ParquetOut.write(out.resolve("alloc_grid.parquet"),
                columns("run", "VARCHAR", "param", "VARCHAR", "value", "DOUBLE",
                        "cagr", "DOUBLE", "sharpe", "DOUBLE", "maxdd", "DOUBLE"), gridRows);

        Map<String, Object> result = new LinkedHashMap<>();
        for (Row r : rows) {
            result.put(r.id() + ".cagr", r.metrics().cagr());
            result.put(r.id() + ".maxdd", r.metrics().maxDrawdown());
        }
        long fromMs = set.dayMs()[0];
        long toMs = set.dayMs()[set.length() - 1];
        String runId = runLog.record("alloc", ALGO_VERSION, "alloc-bench", fromMs, toMs, cfg.seed(),
                cfg.asMap(), result);

        writeReport(out.resolve("alloc_bench.md"), runId, set, rows, gridRows, loo,
                etaStar, scale, bound, trials, fixedMix, bestSingle, randomFixed);
        Jlog.info(log, "alloc.done", Map.of("run_id", runId, "rows", rows.size(),
                "out", out.resolve("alloc_bench.md").toString()));
    }

    /** Пары «имя колонки → тип DuckDB» в порядке следования значений в строках. */
    private static Map<String, String> columns(String... nameThenType) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenType.length; i += 2) {
            m.put(nameThenType[i], nameThenType[i + 1]);
        }
        return m;
    }

    /** Индекс стратегии с наибольшим весом в миксе. */
    private static int dominantIndex(double[] mix) {
        int best = 0;
        for (int i = 1; i < mix.length; i++) {
            if (mix[i] > mix[best]) {
                best = i;
            }
        }
        return best;
    }

    private static String group(String id) {
        return switch (id) {
            case "BEST_FIXED", "BEST_SINGLE" -> "ориентир (смотрит в будущее)";
            case "RANDOM" -> "контроль";
            default -> "аллокатор";
        };
    }

    private Row row(String id, String group, AllocEngine.Result r, CurveSet set,
                    AllocEngine.Result single, AllocEngine.Result fixed, double bound, int trials) {
        Metrics m = Metrics.of(r.ret(), r.equity(), set.cash(), set.days(), trials);
        return new Row(id, group, r, m,
                Metrics.realizedRegret(r.ret(), single.ret()),
                Metrics.realizedRegret(r.ret(), fixed.ret()), bound);
    }

    /** Бенчмарк прогоняется через тот же движок: кривая как пул из одной стратегии. */
    private AllocEngine.Result benchmarkResult(CurveSet set, Curve curve) {
        CurveSet one = new CurveSet(set.days(), set.dayMs(), List.of(curve), set.benchmarks(),
                set.cash(), set.regime(), set.cyclePhase(), set.missing());
        return new AllocEngine(one, cfg.switchCost())
                .run(new Allocators.Fixed(curve.id(), new double[]{1.0}, false));
    }

    // ------------------------------------------------------------------ сетки

    /**
     * Контроль «случайный <b>постоянный</b> микс» (§4.1). Ежедневно
     * перевыбираемые случайные веса меряют в основном издержки собственного
     * churn-а, поэтому обойти их легко и это ничего не доказывает. Честный
     * контроль — распределение CAGR по {@code draws} случайным постоянным
     * миксам: если аллокатор не выше их медианы, выбор весов ценности не даёт.
     *
     * @return отсортированный массив CAGR по случайным миксам
     */
    private double[] randomFixedCagr(CurveSet set, int draws, int trials) {
        java.util.Random rnd = new java.util.Random(cfg.seed());
        AllocEngine engine = new AllocEngine(set, cfg.switchCost());
        double[] out = new double[draws];
        for (int i = 0; i < draws; i++) {
            double[] w = Allocators.randomSimplex(rnd, set.size());
            AllocEngine.Result r = engine.run(new Allocators.Fixed("RANDOM_FIXED", w, false));
            out[i] = Metrics.of(r.ret(), r.equity(), set.cash(), set.days(), trials).cagr();
        }
        java.util.Arrays.sort(out);
        return out;
    }

    private static double percentileOf(double[] sorted, double value) {
        int below = 0;
        for (double v : sorted) {
            if (v < value) {
                below++;
            }
        }
        return sorted.length == 0 ? Double.NaN : 100.0 * below / sorted.length;
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private List<Object[]> etaGrid(CurveSet set, double etaStar, int trials) {
        List<Object[]> out = new ArrayList<>();
        AllocEngine engine = new AllocEngine(set, cfg.switchCost());
        for (double k : new double[]{0.5, 1, 2, 5, 10}) {
            AllocEngine.Result r = engine.run(new Allocators.Hedge("HEDGE", etaStar * k, cfg.hedgeWindow()));
            Metrics m = Metrics.of(r.ret(), r.equity(), set.cash(), set.days(), trials);
            out.add(new Object[]{"eta", "k×η*", k, m.cagr(), m.sharpe(), m.maxDrawdown()});
        }
        return out;
    }

    private List<Object[]> costGrid(CurveSet set, double etaStar, int trials) {
        List<Object[]> out = new ArrayList<>();
        for (double c : new double[]{0.0, 0.0005, 0.0010, 0.0015, 0.0020, 0.0050, cfg.switchCost() * 1.5}) {
            AllocEngine engine = new AllocEngine(set, c);
            AllocEngine.Result hedge = engine.run(new Allocators.Hedge("HEDGE", etaStar, cfg.hedgeWindow()));
            AllocEngine.Result ew = engine.run(new Allocators.EqualWeight("EW", Allocators.Normalization.ALL));
            Metrics mh = Metrics.of(hedge.ret(), hedge.equity(), set.cash(), set.days(), trials);
            Metrics me = Metrics.of(ew.ret(), ew.equity(), set.cash(), set.days(), trials);
            out.add(new Object[]{"cost.hedge", "c_switch", c, mh.cagr(), mh.sharpe(), mh.maxDrawdown()});
            out.add(new Object[]{"cost.ew", "c_switch", c, me.cagr(), me.sharpe(), me.maxDrawdown()});
        }
        return out;
    }

    private List<Object[]> windowGrid(CurveSet set, double etaStar, int trials) {
        List<Object[]> out = new ArrayList<>();
        AllocEngine engine = new AllocEngine(set, cfg.switchCost());
        for (int w : new int[]{0, 90, 180, 365}) {
            AllocEngine.Result r = engine.run(new Allocators.Hedge("HEDGE", etaStar, w));
            Metrics m = Metrics.of(r.ret(), r.equity(), set.cash(), set.days(), trials);
            out.add(new Object[]{"window", "дней", (double) w, m.cagr(), m.sharpe(), m.maxDrawdown()});
        }
        return out;
    }

    /**
     * Leave-one-out (§5, обязателен): держится ли результат на одной стратегии.
     * Если удаление S5 обрушивает всё — аллокатор не нашёл ничего, он нашёл S5.
     */
    private List<Object[]> leaveOneOut(CurveSet set, double etaStar, int trials) {
        List<Object[]> out = new ArrayList<>();
        for (Curve c : set.pool()) {
            CurveSet reduced = set.without(c.id());
            AllocEngine engine = new AllocEngine(reduced, cfg.switchCost());
            double eta = Allocators.etaStar(reduced.size(), reduced.length(),
                    Allocators.observedScale(reduced.retMatrix(), reduced.size())) * cfg.hedgeEtaMultiplier();
            AllocEngine.Result hedge = engine.run(new Allocators.Hedge("HEDGE", eta, cfg.hedgeWindow()));
            AllocEngine.Result ew = engine.run(new Allocators.EqualWeight("EW", Allocators.Normalization.ALL));
            double[] mix = engine.bestFixedMix(cfg.bestFixedIterations(), cfg.bestFixedStep());
            AllocEngine.Result best = engine.run(new Allocators.Fixed("BEST_FIXED", mix, true));
            Metrics mh = Metrics.of(hedge.ret(), hedge.equity(), reduced.cash(), reduced.days(), trials);
            Metrics me = Metrics.of(ew.ret(), ew.equity(), reduced.cash(), reduced.days(), trials);
            Metrics mb = Metrics.of(best.ret(), best.equity(), reduced.cash(), reduced.days(), trials);
            out.add(new Object[]{"loo.hedge", "без " + c.id(), 0.0, mh.cagr(), mh.sharpe(), mh.maxDrawdown()});
            out.add(new Object[]{"loo.ew", "без " + c.id(), 0.0, me.cagr(), me.sharpe(), me.maxDrawdown()});
            out.add(new Object[]{"loo.best_fixed", "без " + c.id(), 0.0, mb.cagr(), mb.sharpe(), mb.maxDrawdown()});
        }
        return out;
    }

    // ----------------------------------------------------------------- отчёт

    private void writeReport(Path out, String runId, CurveSet set, List<Row> rows, List<Object[]> grid,
                             List<Object[]> loo, double etaStar, double scale, double bound, int trials,
                             double[] fixedMix, int bestSingle, double[] randomFixed) {
        StringBuilder sb = new StringBuilder();
        Row fixed = find(rows, "BEST_FIXED");
        Row sma = find(rows, "SMA200");
        Row ew = find(rows, "EW");
        Row hedge = find(rows, "HEDGE");
        Row random = find(rows, "RANDOM");
        Row detector = find(rows, "DETECTOR");
        boolean fixedBeatsSma = fixed.metrics().cagr() > sma.metrics().cagr();

        sb.append("# Стенд аллокации поверх пула стратегий (ТЗ 65, каталог E1+E4)\n\n");
        if (set.anyInSample()) {
            sb.append("> **Результаты — верхняя граница, не оценка.** В пуле есть кривые `in_sample`: ")
                    .append("параметры стратегий выбраны со знанием истории, аллокатор наследует это смещение ")
                    .append("целиком (§0 п.1, §3.3).\n\n");
        }
        sb.append("Прогон `").append(runId).append("`, код `").append(runLog.gitHash())
                .append("`, версия алгоритма `").append(ALGO_VERSION).append("`. ")
                .append("Окно ").append(set.days()[0]).append(" … ").append(set.days()[set.length() - 1])
                .append(" (").append(set.length()).append(" дней, N=").append(set.size())
                .append(" стратегий).\n\n")
                .append("η взято из теории: η* = √(8 ln N / T) / G = ").append(fmt(etaStar, 2))
                .append(", где G = ").append(fmt(scale, 4))
                .append(" — наблюдённый размах дневных доходностей пула. Классическая граница Hedge выведена ")
                .append("для выигрышей в [0,1], поэтому и η, и граница масштабируются на G — иначе при ")
                .append("долях процента в день Hedge не отличает стратегии, а регрет вылезает за границу. ")
                .append("G — единственное место, где используется полный размах выборки; на выбор весов внутри ")
                .append("выборки он влияет только через масштаб η (§8: η не подбирается по результату).\n\n");

        // --- §6.3 вопрос 1 идёт первым: это точка остановки §9 этап 4 ---
        sb.append("## Вердикт по точке остановки (§9 этап 4)\n\n");
        sb.append(fixedBeatsSma
                        ? "**BEST_FIXED превосходит SMA200** → вопрос об аллокации поверх пула остаётся открытым, "
                        + "остальные разделы имеют смысл.\n\n"
                        : "**BEST_FIXED НЕ превосходит SMA200.** Недостижимый ориентир — лучший постоянный микс, "
                        + "выбранный задним числом, — проигрывает однострочнику. Значит **никакой** аллокатор "
                        + "поверх этого пула (ни Hedge, ни матрица детектора, ни идеальный оракул) не мог бы "
                        + "дать больше. Вопрос об аллокации поверх этого пула закрыт целиком; всё ниже — "
                        + "справочный материал, а не кандидаты во внедрение.\n\n")
                .append(String.format(Locale.ROOT, "BEST_FIXED CAGR %.1f%% против SMA200 %.1f%% "
                                + "(разница %.1f п.п.).%n%n",
                        fixed.metrics().cagr() * 100, sma.metrics().cagr() * 100,
                        (fixed.metrics().cagr() - sma.metrics().cagr()) * 100));
        int dominant = dominantIndex(fixedMix);
        if (fixedBeatsSma && fixedMix[dominant] > 0.5) {
            sb.append(String.format(Locale.ROOT,
                    "**Но ориентир сосредоточен в одной кривой — %s (%.0f%%)**, и это не вывод об аллокации: "
                            + "верхняя граница здесь измеряет качество одной кривой пула, а не пользу от "
                            + "распределения между ними. Ограничения этой кривой перечислены в разделе "
                            + "«Данные» и переносятся на вывод целиком.%n%n",
                    set.ids()[dominant], fixedMix[dominant] * 100));
        }

        // --- Данные ---
        sb.append("## Данные (§6.1)\n\n");
        sb.append("| Стратегия | Источник кривой | Доля дней доступности | Средн. дн. доходность (задейств.) |\n");
        sb.append("|---|---|---|---|\n");
        for (Curve c : set.pool()) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %.0f%% | %.3f%% |%n",
                    c.id(), c.kind(), c.availabilityShare() * 100, meanDeployed(c) * 100));
        }
        sb.append('\n');
        for (Curve c : set.pool()) {
            sb.append("- **").append(c.id()).append("** — ").append(c.note()).append('\n');
        }
        if (!set.missing().isEmpty()) {
            sb.append("\n**Стратегии пула без кривой** (перечислены до прогона, не исключены по результату):\n\n");
            set.missing().forEach((id, why) -> sb.append("- `").append(id).append("` — ").append(why).append('\n'));
        }
        sb.append("\nСтавка кэша — FRED DFF по датам (не константа 8%): среднегодовая ")
                .append(String.format(Locale.ROOT, "%.2f%%", meanCashAnnual(set) * 100)).append(".\n\n");

        // --- Аллокаторы ---
        sb.append("## Аллокаторы (§6.2)\n\n");
        sb.append("| ID | Роль | CAGR | Vol | Sharpe ± SE | Sharpe-defl | MaxDD (дата) | "
                + "регрет vs BEST_SINGLE | vs BEST_FIXED | Σ\\|Δw\\|/день | издержки | кэш |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            Metrics m = r.metrics();
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %.1f%% | %.1f%% | %.2f ± %.2f | %.2f | %.1f%% (%s) | %.2f | %.2f | %.3f | %.1f%% | %.0f%% |%n",
                    r.id(), r.group(), m.cagr() * 100, m.vol() * 100, m.sharpe(), m.sharpeSe(),
                    m.sharpeDeflated(), m.maxDrawdown() * 100, m.maxDrawdownDay(),
                    r.regretVsSingle(), r.regretVsFixed(), r.result().meanAbsDw(),
                    r.result().switchCostSum() * 100, r.result().cashShare() * 100));
        }
        sb.append("\nТеоретическая граница регрета Hedge при этих T и N: **").append(fmt(bound, 2))
                .append("** (в единицах суммы дневных доходностей; G — наблюдённый размах). ")
                .append("Реализованный регрет HEDGE против BEST_SINGLE: **")
                .append(fmt(hedge.regretVsSingle(), 2)).append("** → ")
                .append(hedge.regretVsSingle() <= bound
                        ? "укладывается в границу.\n\n"
                        : "**ГРАНИЦА НАРУШЕНА — это дефект реализации, а не результат; прогон недействителен "
                        + "(§6.4).**\n\n");

        sb.append("Лучший постоянный микс задним числом (`BEST_FIXED`): ");
        String[] ids = set.ids();
        for (int i = 0; i < ids.length; i++) {
            if (fixedMix[i] > 0.005) {
                sb.append(String.format(Locale.ROOT, "%s %.0f%%; ", ids[i], fixedMix[i] * 100));
            }
        }
        sb.append("лучшая одиночная (`BEST_SINGLE`): **").append(ids[bestSingle]).append("**.\n\n");

        if (fixedMix[dominant] > 0.5) {
            Curve dominantCurve = set.pool().get(dominant);
            sb.append(String.format(Locale.ROOT,
                    "> **Верхний ориентир сосредоточен в одной кривой: %s (%.0f%%).** Ответ на вопрос 1 "
                            + "вердикта целиком опирается на неё, поэтому её оговорка переносится на весь "
                            + "вывод: %s%n%n", ids[dominant], fixedMix[dominant] * 100, dominantCurve.note()));
        }

        sb.append("### Разложение вклада по стратегиям\n\n");
        sb.append("| Аллокатор | ").append(String.join(" | ", ids)).append(" | кэш | издержки |\n");
        sb.append("|---".repeat(ids.length + 3)).append("|\n");
        for (Row r : rows) {
            if (r.result().contribution().length != ids.length) {
                continue;
            }
            sb.append("| ").append(r.id());
            for (double c : r.result().contribution()) {
                sb.append(String.format(Locale.ROOT, " | %.2f", c));
            }
            sb.append(String.format(Locale.ROOT, " | %.2f | %.2f |%n",
                    r.result().cashPnl(), r.result().costPnl()));
        }

        // --- Сетки ---
        sb.append("\n## Прогоны чувствительности (§5)\n\n");
        sb.append("| Прогон | Параметр | Значение | CAGR | Sharpe | MaxDD |\n|---|---|---|---|---|---|\n");
        for (Object[] g : grid) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %.1f%% | %.2f | %.1f%% |%n",
                    g[0], g[1], g[0].toString().startsWith("loo") ? "—" : fmt((Double) g[2], 4),
                    (Double) g[3] * 100, (Double) g[4], (Double) g[5] * 100));
        }

        // --- Вердикт ---
        sb.append("\n## Вердикт (§6.3)\n\n");
        sb.append("1. **Превосходит ли BEST_FIXED (недостижимый ориентир) SMA200?** ")
                .append(fixedBeatsSma ? "Да" : "**Нет**")
                .append(String.format(Locale.ROOT, " (%.1f%% против %.1f%%). %s%n%n",
                        fixed.metrics().cagr() * 100, sma.metrics().cagr() * 100,
                        fixedBeatsSma ? "" : "Вопрос об аллокации поверх этого пула закрыт целиком."));
        sb.append("2. **HEDGE против матрицы DETECTOR:** ")
                .append(String.format(Locale.ROOT, "%.1f%% против %.1f%% → разница %.1f п.п.%n%n",
                        hedge.metrics().cagr() * 100, detector.metrics().cagr() * 100,
                        (hedge.metrics().cagr() - detector.metrics().cagr()) * 100));
        sb.append("3. **HEDGE против равных весов EW:** ")
                .append(String.format(Locale.ROOT, "%.1f%% против %.1f%% → %s.%n%n",
                        hedge.metrics().cagr() * 100, ew.metrics().cagr() * 100,
                        hedge.metrics().cagr() > ew.metrics().cagr() ? "превосходит"
                                : "**не превосходит — алгоритм не добавляет ценности сверх диверсификации**"));
        double randomPercentile = percentileOf(randomFixed, hedge.metrics().cagr());
        sb.append("4. **HEDGE против случайных весов:** против ежедневно перевыбираемых `RANDOM` — ")
                .append(String.format(Locale.ROOT, "%.1f%% против %.1f%% (%s), но этот контроль меряет в "
                                + "основном издержки собственного churn-а. Честный контроль — %d случайных "
                                + "**постоянных** миксов: медиана %.1f%%, 5-й процентиль %.1f%%, 95-й %.1f%%; "
                                + "HEDGE стоит на **%.0f-м процентиле** этого распределения → %s.%n%n",
                        hedge.metrics().cagr() * 100, random.metrics().cagr() * 100,
                        hedge.metrics().cagr() > random.metrics().cagr() ? "превосходит" : "не превосходит",
                        randomFixed.length, quantile(randomFixed, 0.5) * 100,
                        quantile(randomFixed, 0.05) * 100, quantile(randomFixed, 0.95) * 100,
                        randomPercentile,
                        randomPercentile >= 50
                                ? "выбор весов даёт больше, чем случайная диверсификация"
                                : "**выбор весов не добавляет ценности сверх случайного микса**"));
        sb.append("5. **При какой `c_switch` преимущество HEDGE над EW обращается в ноль:** ")
                .append(breakEvenCost(grid)).append("\n\n");
        sb.append("6. **Укладывается ли реализованный регрет в теоретическую границу:** ")
                .append(hedge.regretVsSingle() <= bound ? "да" : "**нет — прогон недействителен**")
                .append(String.format(Locale.ROOT, " (%.2f при границе %.2f).%n%n",
                        hedge.regretVsSingle(), bound));
        sb.append("7. **Leave-one-out:** см. строки `loo.*` таблицы выше. ")
                .append(looVerdict(loo, hedge.metrics().cagr())).append("\n\n");
        sb.append("8. **Сосредоточен ли вклад в одном годе:** ").append(yearConcentration(set, hedge))
                .append("\n\n");

        sb.append("### Подпериоды (§5)\n\n");
        sb.append(subperiods(set, rows));

        sb.append("\n## Оговорки (§10)\n\n")
                .append("- **Граница регрета относительна.** Она не обещает прибыли: пул из убыточных стратегий "
                        + "даёт убыточный портфель при полном соблюдении гарантии.\n")
                .append("- **Результат на `in_sample` кривых — верхняя граница**, а не оценка.\n")
                .append("- **Стенд не оправдывает ни одну стратегию пула.** Улучшение аллокации на закрытом пуле "
                        + "не делает пул рабочим.\n")
                .append("- **Отрицательный результат — полноценный результат:** он закрывает вопрос, который "
                        + "сейчас держится на детекторе, не прошедшем собственную проверку (док. 20).\n");

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
     * Доля суммарного P&amp;L, пришедшая из лучшего года (§6.3 вопрос 8). Считается
     * по сумме дневных доходностей: если один год даёт больше половины, результат
     * — эпизод, а не свойство конструкции.
     */
    private static String yearConcentration(CurveSet set, Row r) {
        Map<String, Double> byYear = new LinkedHashMap<>();
        double total = 0;
        for (int i = 0; i < set.length(); i++) {
            String year = set.days()[i].substring(0, 4);
            byYear.merge(year, r.result().ret()[i], Double::sum);
            total += r.result().ret()[i];
        }
        String bestYear = "—";
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> e : byYear.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestYear = e.getKey();
            }
        }
        if (total <= 0) {
            return String.format(Locale.ROOT, "суммарный P&L %s не положителен (%.2f), доли считать не от чего; "
                    + "лучший год — %s (%.2f).", r.id(), total, bestYear, best);
        }
        double share = best / total;
        return String.format(Locale.ROOT, "у %s на лучший год (%s) приходится **%.0f%%** суммарного P&L%s",
                r.id(), bestYear, share * 100,
                share > 0.5 ? " → результат держится на одном годе, это эпизод, а не свойство конструкции."
                        : ".");
    }

    private String subperiods(CurveSet set, List<Row> rows) {
        StringBuilder sb = new StringBuilder("| Аллокатор | ");
        List<String> years = new ArrayList<>();
        for (String d : set.days()) {
            String y = d.substring(0, 4);
            if (!years.contains(y)) {
                years.add(y);
            }
        }
        sb.append(String.join(" | ", years)).append(" | BULL | BEAR |\n");
        sb.append("|---".repeat(years.size() + 3)).append("|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.id());
            for (String y : years) {
                sb.append(String.format(Locale.ROOT, " | %.1f%%", annualized(set, r, d -> d.startsWith(y)) * 100));
            }
            sb.append(String.format(Locale.ROOT, " | %.1f%% | %.1f%% |%n",
                    regimeAnnualized(set, r, "BULL") * 100, regimeAnnualized(set, r, "BEAR") * 100));
        }
        return sb.toString();
    }

    private double annualized(CurveSet set, Row r, java.util.function.Predicate<String> dayFilter) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < set.length(); i++) {
            if (dayFilter.test(set.days()[i])) {
                sum += r.result().ret()[i];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n * 365;
    }

    private double regimeAnnualized(CurveSet set, Row r, String state) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < set.length(); i++) {
            if (state.equals(set.regime()[i])) {
                sum += r.result().ret()[i];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n * 365;
    }

    private static String breakEvenCost(List<Object[]> grid) {
        Double lastHedge = null;
        for (Object[] g : grid) {
            if ("cost.hedge".equals(g[0])) {
                lastHedge = (Double) g[3];
                continue;
            }
            if ("cost.ew".equals(g[0]) && lastHedge != null && lastHedge <= (Double) g[3]) {
                return String.format(Locale.ROOT, "уже при c_switch = %.2f%% HEDGE не лучше EW",
                        (Double) g[2] * 100);
            }
        }
        return "на всей сетке издержек (0…0.5%) HEDGE сохраняет знак преимущества над EW";
    }

    private static String looVerdict(List<Object[]> loo, double baseCagr) {
        String worst = "—";
        double worstCagr = Double.POSITIVE_INFINITY;
        for (Object[] g : loo) {
            if ("loo.hedge".equals(g[0]) && (Double) g[3] < worstCagr) {
                worstCagr = (Double) g[3];
                worst = String.valueOf(g[1]);
            }
        }
        return String.format(Locale.ROOT,
                "Сильнее всего результат HEDGE падает при удалении «%s»: CAGR %.1f%% против %.1f%% на полном пуле%s",
                worst, worstCagr * 100, baseCagr * 100,
                worstCagr < baseCagr * 0.5
                        ? " → **преимущество держится на одной стратегии, а не на аллокаторе**."
                        : ".");
    }

    private static double meanDeployed(Curve c) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < c.length(); i++) {
            if (c.available()[i]) {
                sum += c.ret()[i];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double meanCashAnnual(CurveSet set) {
        double sum = 0;
        for (double c : set.cash()) {
            sum += c;
        }
        return set.length() == 0 ? 0 : sum / set.length() * 365;
    }

    private static Row find(List<Row> rows, String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("нет строки отчёта " + id));
    }

    private static String fmt(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
