package org.home.data.revx.sim;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Обязательные прогоны (ТЗ §4.7) и отчёт §5.3 по одной паре.
 *
 * Два контроля — главные. Buy & hold отвечает, не был ли весь результат
 * направленным движением рынка. Случайные котировки отвечают, есть ли ценность
 * в ВЫБОРЕ цен или работает сам факт присутствия в книге. Стратегия, не
 * превосходящая оба контроля, отклоняется.
 *
 * Параметры не подбираются по результату (ТЗ §8): базовые значения заданы в
 * конфиге до прогона, а таблицы чувствительности показывают, где конструкция
 * умирает, а не где она красивее.
 */
@Component
@Lazy
public class SimRunner {

    private static final Logger log = LoggerFactory.getLogger(SimRunner.class);

    /**
     * Горизонты markout. Час добавлен не для полноты: измеренная медиана времени
     * удержания инвентаря по BTC — 1.6 часа, то есть горизонты из ТЗ §4.5 (до 5 минут)
     * короче реальной жизни позиции в двадцать раз. Без часового горизонта порог
     * по комиссии считался бы по куску, который позиция едва прожила.
     */
    private static final long[] HORIZONS_MS = {0, 10_000, 60_000, 300_000, 3_600_000};

    private final SimDataReader reader;
    private final RunRegistry registry;
    private final RevxConfig cfg;
    private final RevxDb db;

    public SimRunner(SimDataReader reader, RunRegistry registry, RevxConfig cfg, RevxDb db) {
        this.reader = reader;
        this.registry = registry;
        this.cfg = cfg;
        this.db = db;
    }

    private record Run(String label, SimEngine.Result result, double makerFee, double offset,
                       double cap) {
    }

    /** Ступень лестницы отступа: сама стратегия и её нулевое распределение. */
    private record Rung(double offset, SimEngine.Result result, Null nulls) {
    }

    /** Ступень лестницы скоса. Нулевое распределение тут не нужно: скос — не выбор цен. */
    private record SkewRung(double skew, SimEngine.Result result) {
    }

    /**
     * Нулевое распределение контроля: N прогонов случайных котировок с разными
     * seed'ами. {@code capturePercentile} — доля прогонов контроля, которые
     * стратегия обошла по захвату спреда; 50% означает «неотличима от случайной».
     */
    private record Null(int seeds, double captureMean, double captureSd, double captureP05,
                        double captureP95, double capturePercentile,
                        double totalMean, double totalPercentile) {
    }

    public void run(String symbol, int hours, String out) {
        run(symbol, hours, System.currentTimeMillis(), out);
    }

    /**
     * Конец окна задаётся явно, когда хвост данных непригоден. 25.08.2026 темп сбора
     * упал с 720 снимков в час до 18 (завис ночной {@code sqlite3 .backup}), и окно
     * «последние N часов» смешало бы чистые сутки с прорежёнными.
     */
    public void run(String symbol, int hours, long toMs, String out) {
        long fromMs = toMs - hours * 3600_000L;
        long bucketMs = cfg.authBookPeriodSeconds() * 1000L;

        SimDataReader.Dataset data = reader.read(symbol, fromMs, toMs, bucketMs);
        if (data.windows().size() < 100) {
            log.warn("{}: окон всего {} — данных слишком мало для выводов", symbol, data.windows().size());
            if (data.windows().isEmpty()) {
                return;
            }
        }

        double[] steps = pairSteps(symbol);
        ExecutionModel.Limits limits = new ExecutionModel.Limits(steps[0], 1e-9);
        Quoter.Params base = new Quoter.Params(cfg.simOffset(), cfg.simSize(), cfg.simInventoryCap(),
                cfg.simSkewK(), cfg.simRequoteThreshold(), steps[1]);

        List<Run> runs = new ArrayList<>();
        SimEngine.Result baseResult = new SimEngine(base, limits, cfg.simMakerFee()).run(data.windows());
        runs.add(new Run("базовый", baseResult, cfg.simMakerFee(), base.offset(), base.inventoryCap()));

        // Чувствительность к комиссии: при какой maker-ставке конструкция умирает
        for (double fee : cfg.simFeeLadder()) {
            if (fee == cfg.simMakerFee()) {
                continue;
            }
            runs.add(new Run(String.format("maker %.3f%%", fee * 100),
                    new SimEngine(base, limits, fee).run(data.windows()), fee, base.offset(),
                    base.inventoryCap()));
        }
        // Пессимистичный прогон: промо отменили И ещё в полтора раза хуже
        double pessimistic = cfg.simPessimisticFee();
        runs.add(new Run(String.format("издержки ×1.5 (maker %.3f%%)", pessimistic * 100),
                new SimEngine(base, limits, pessimistic).run(data.windows()), pessimistic,
                base.offset(), base.inventoryCap()));

        // Чувствительность к отступу
        for (double offset : cfg.simOffsetLadder()) {
            Quoter.Params params = withOffset(base, offset);
            runs.add(new Run(String.format("отступ %.3f%%", offset * 100),
                    new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows()),
                    cfg.simMakerFee(), offset, base.inventoryCap()));
        }
        // Чувствительность к потолку инвентаря
        for (double capFactor : new double[]{0.5, 2.0}) {
            Quoter.Params params = withCap(base, base.inventoryCap() * capFactor);
            runs.add(new Run(String.format("потолок инвентаря ×%.1f", capFactor),
                    new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows()),
                    cfg.simMakerFee(), base.offset(), params.inventoryCap()));
        }
        // Лестница скоса. Скос сдвигает обе цены вниз по мере роста инвентаря, делая
        // аск агрессивнее; измеренная цена этой страховки лежит НЕ в захвате спреда,
        // а в markout (док. 79 §7), поэтому ступени сравниваются по краю.
        List<SkewRung> skewLadder = new ArrayList<>();
        for (double skew : skews(base)) {
            Quoter.Params params = new Quoter.Params(base.offset(), base.size(),
                    base.inventoryCap(), skew, base.requoteThreshold(), base.quoteStep());
            SimEngine.Result result = skew == base.skewK() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            skewLadder.add(new SkewRung(skew, result));
            if (skew != base.skewK()) {
                runs.add(new Run(String.format("скос %.4f%%", skew * 100), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        // Контроль: случайные котировки (то же присутствие в книге, цены наугад)
        SimEngine.Result randomResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.random(base, cfg.simRandomSeed())).run(data.windows());
        runs.add(new Run("контроль: случайные котировки", randomResult, cfg.simMakerFee(),
                base.offset(), base.inventoryCap()));

        // Лестница отступа с контролем на КАЖДОЙ ступени (док. 75 §5). Раньше и
        // buy & hold, и случайные считались только при базовом d, а вердикт «kill-критерий
        // сработал» переносился на всю конструкцию. Между тем d двигает и число
        // исполнений, и знак markout — то есть ровно то, что этими критериями и меряется.
        List<Rung> ladder = new ArrayList<>();
        for (double offset : offsets(base)) {
            Quoter.Params params = withOffset(base, offset);
            SimEngine.Result result = offset == base.offset() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            ladder.add(new Rung(offset, result, nullDistribution(params, limits, data, result)));
        }

        for (Run run : runs) {
            registry.record(run.label(), symbol, data.fromMs(), data.toMs(),
                    configOf(run, base, limits), resultOf(run.result(), data));
        }
        for (Rung rung : ladder) {
            registry.record(String.format("нулевое распределение ×%d, отступ %.3f%%",
                            cfg.simRandomSeeds(), rung.offset() * 100),
                    symbol, data.fromMs(), data.toMs(),
                    configOf(new Run("", rung.result(), cfg.simMakerFee(), rung.offset(),
                            base.inventoryCap()), base, limits),
                    nullOf(rung, data));
        }

        String markdown = render(symbol, hours, data, base, limits, runs, baseResult, ladder,
                skewLadder);
        write(out, markdown);
        log.info("{}: {} прогонов, базовый total={} (спред {} + инвентарь {}), исполнений {} → {}",
                symbol, runs.size(), round(baseResult.pnl().total(), 4),
                round(baseResult.pnl().spreadCapture(), 4), round(baseResult.pnl().inventoryPnl(), 4),
                baseResult.fills().size(), out);
    }

    /** Базовый отступ плюс лестница, по возрастанию и без дублей. */
    private double[] offsets(Quoter.Params base) {
        return java.util.stream.DoubleStream
                .concat(java.util.stream.DoubleStream.of(base.offset()),
                        java.util.Arrays.stream(cfg.simOffsetLadder()))
                .distinct().sorted().toArray();
    }

    /** Базовый скос плюс лестница, по возрастанию и без дублей. */
    private double[] skews(Quoter.Params base) {
        return java.util.stream.DoubleStream
                .concat(java.util.stream.DoubleStream.of(base.skewK()),
                        java.util.Arrays.stream(cfg.simSkewLadder()))
                .distinct().sorted().toArray();
    }

    private Null nullDistribution(Quoter.Params params, ExecutionModel.Limits limits,
                                  SimDataReader.Dataset data, SimEngine.Result strategy) {
        int seeds = Math.max(1, cfg.simRandomSeeds());
        double[] captures = new double[seeds];
        double[] totals = new double[seeds];
        for (int i = 0; i < seeds; i++) {
            SimEngine.Result result = new SimEngine(params, limits, cfg.simMakerFee(),
                    QuotePolicy.random(params, cfg.simRandomSeed() + i)).run(data.windows());
            captures[i] = result.pnl().spreadCapture();
            totals[i] = result.pnl().total();
        }
        double captureMean = java.util.Arrays.stream(captures).average().orElse(Double.NaN);
        double variance = java.util.Arrays.stream(captures)
                .map(v -> (v - captureMean) * (v - captureMean)).sum() / Math.max(1, seeds - 1);
        double[] sortedCaptures = captures.clone();
        java.util.Arrays.sort(sortedCaptures);
        return new Null(seeds, captureMean, Math.sqrt(variance),
                pct(sortedCaptures, 0.05), pct(sortedCaptures, 0.95),
                share(captures, strategy.pnl().spreadCapture()),
                java.util.Arrays.stream(totals).average().orElse(Double.NaN),
                share(totals, strategy.pnl().total()));
    }

    /** Доля значений контроля, которые стратегия превзошла. */
    private static double share(double[] values, double strategy) {
        long below = java.util.Arrays.stream(values).filter(v -> v < strategy).count();
        return 100.0 * below / values.length;
    }

    private static double pct(double[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.round(q * (sorted.length - 1))))];
    }

    /** Секунды/минуты/часы: «4200000 мс» в отчёте не читается. */
    private static String humanMs(double ms) {
        if (Double.isNaN(ms)) {
            return "—";
        }
        if (ms < 60_000) {
            return round(ms / 1000, 1) + " с";
        }
        if (ms < 3_600_000) {
            return round(ms / 60_000, 1) + " мин";
        }
        return round(ms / 3_600_000, 1) + " ч";
    }

    private static double turnover(SimEngine.Result result) {
        return result.fills().stream().mapToDouble(Fill::notional).sum();
    }

    /**
     * Чистый край на единицу оборота — величина, которую только и можно сравнивать
     * с комиссией (док. 75 §3). Захват меряется в markout(0), то есть ДО того, как
     * неблагоприятный отбор заберёт своё; комиссия же платится с оборота. markout
     * в этом стенде уже домножен на объём, поэтому сумма markout(0) тождественно
     * равна захвату, а сумма markout(Δ) — это то, что от него осталось к горизонту Δ.
     */
    private static double netEdgeBp(SimEngine.Result result, long horizonMs) {
        return netEdgeBp(result.fills(), result, horizonMs);
    }

    /**
     * Чистый край ОДНОЙ стороны — решающая проверка на бету. Если край держится
     * только на покупках, а продажи в минусе, то это не преимущество котирования,
     * а рынок, который рос: покупка «в среднем угадала» просто оттого, что после
     * неё всё дорожало. Настоящий край обязан быть положительным с обеих сторон.
     */
    private static double netEdgeBp(SimEngine.Result result, long horizonMs, Side side) {
        return netEdgeBp(result.fills().stream().filter(f -> f.side() == side).toList(),
                result, horizonMs);
    }

    private static double netEdgeBp(List<Fill> fills, SimEngine.Result result, long horizonMs) {
        List<Fill> withHorizon = Markout.withHorizon(fills, result.fairSeries(), horizonMs);
        double turnover = withHorizon.stream().mapToDouble(Fill::notional).sum();
        if (turnover <= 0) {
            return Double.NaN;
        }
        Markout.Stats atFill = Markout.compute(withHorizon, result.fairSeries(), 0);
        Markout.Stats later = Markout.compute(withHorizon, result.fairSeries(), horizonMs);
        double net = atFill.mean() * atFill.fills() + later.mean() * later.fills();
        return net / turnover * 10_000;
    }

    /** base_step и quote_step берутся из спецификации пары (ТЗ §4.6 п.6). */
    private double[] pairSteps(String symbol) {
        List<double[]> rows = db.query(
                "SELECT base_step, quote_step FROM revx_pair WHERE symbol = ?",
                rs -> new double[]{rs.getDouble("base_step"), rs.getDouble("quote_step")}, symbol);
        if (rows.isEmpty()) {
            throw new IllegalStateException("нет спецификации пары " + symbol + " — сначала --revx-pairs");
        }
        return rows.get(0);
    }

    private static Quoter.Params withOffset(Quoter.Params base, double offset) {
        return new Quoter.Params(offset, base.size(), base.inventoryCap(), base.skewK(),
                base.requoteThreshold(), base.quoteStep());
    }

    private static Quoter.Params withCap(Quoter.Params base, double cap) {
        return new Quoter.Params(base.offset(), base.size(), cap, base.skewK(),
                base.requoteThreshold(), base.quoteStep());
    }

    private Map<String, Object> configOf(Run run, Quoter.Params base, ExecutionModel.Limits limits) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("offset", run.offset());
        config.put("size", base.size());
        config.put("inventory_cap", run.cap());
        config.put("skew_k", base.skewK());
        config.put("requote_threshold", base.requoteThreshold());
        config.put("maker_fee", run.makerFee());
        config.put("base_step", limits.baseStep());
        config.put("quote_step", base.quoteStep());
        config.put("fair_min_pairs", cfg.fairMinPairs());
        config.put("fair_max_dispersion_pct", cfg.fairMaxDispersionPct());
        config.put("fair_max_reference_spread_pct", cfg.fairMaxReferenceSpreadPct());
        config.put("fair_max_residual_pct", cfg.fairMaxResidualPct());
        config.put("random_seed", cfg.simRandomSeed());
        return config;
    }

    private Map<String, Object> nullOf(Rung rung, SimDataReader.Dataset data) {
        Map<String, Object> out = new LinkedHashMap<>(resultOf(rung.result(), data));
        Null nulls = rung.nulls();
        out.put("null_seeds", nulls.seeds());
        out.put("null_capture_mean", nulls.captureMean());
        out.put("null_capture_sd", nulls.captureSd());
        out.put("null_capture_p05", nulls.captureP05());
        out.put("null_capture_p95", nulls.captureP95());
        out.put("strategy_capture_percentile", nulls.capturePercentile());
        out.put("null_total_mean", nulls.totalMean());
        out.put("strategy_total_percentile", nulls.totalPercentile());
        return out;
    }

    private Map<String, Object> resultOf(SimEngine.Result result, SimDataReader.Dataset data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", result.pnl().total());
        out.put("spread_capture", result.pnl().spreadCapture());
        out.put("inventory_pnl", result.pnl().inventoryPnl());
        out.put("fees", result.pnl().fees());
        out.put("reconciles", result.pnl().reconciles(1e-6));
        out.put("fills", result.fills().size());
        out.put("flow_share", result.flowShare());
        out.put("requotes_per_day", result.requotesPerDay(data.spanMs()));
        out.put("max_drawdown", result.maxDrawdown());
        out.put("avg_inventory", result.avgInventory());
        out.put("max_inventory", result.maxInventory());
        out.put("windows_at_cap", result.windowsAtCap());
        out.put("windows_paused", result.windowsPaused());
        out.put("buy_and_hold", result.buyAndHoldPnl());
        double turnover = turnover(result);
        out.put("turnover", turnover);
        out.put("capture_per_turnover_bp",
                turnover > 0 ? result.pnl().spreadCapture() / turnover * 10_000 : Double.NaN);
        out.put("net_edge_60s_bp", netEdgeBp(result, 60_000));
        out.put("net_edge_300s_bp", netEdgeBp(result, 300_000));
        HoldingTime.Stats holding = HoldingTime.compute(result.fills());
        out.put("holding_median_ms", holding.medianMs());
        out.put("holding_p90_ms", holding.p90Ms());
        out.put("holding_mean_ms", holding.meanMs());
        out.put("holding_unclosed_share", holding.unclosedShare());
        for (long horizon : HORIZONS_MS) {
            Markout.Stats stats = Markout.compute(result.fills(), result.fairSeries(), horizon);
            out.put("markout_" + horizon / 1000 + "s_mean", stats.mean());
            out.put("markout_" + horizon / 1000 + "s_median", stats.median());
            out.put("markout_" + horizon / 1000 + "s_fills", stats.fills());
        }
        return out;
    }

    private String render(String symbol, int hours, SimDataReader.Dataset data, Quoter.Params base,
                          ExecutionModel.Limits limits, List<Run> runs, SimEngine.Result baseResult,
                          List<Rung> ladder, List<SkewRung> skewLadder) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Симуляция маркет-мейкинга: ").append(symbol).append("\n\n");
        sb.append("Прогоны ТЗ §4.7, отчёт §5.3. Код `").append(registry.gitHash())
                .append("`, модель исполнения `").append(RunRegistry.MODEL_VERSION)
                .append("`. Все прогоны записаны в `revx_run`.\n\n");
        sb.append("> **Читать с презумпцией ошибки.** ТЗ §0: если результат красив, ")
                .append("первая гипотеза — дефект модели исполнения, а не работающая стратегия.\n\n");

        sb.append("## Данные\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(data.fromMs())).append(" — ")
                .append(Instant.ofEpochMilli(data.toMs())).append(" (").append(hours).append(" ч) |\n");
        sb.append("| Окон симуляции | ").append(data.windows().size()).append(" |\n");
        sb.append("| Окон с выключенным котированием | ").append(data.windowsPaused()).append(" (")
                .append(round(100.0 * data.windowsPaused() / Math.max(1, data.windows().size()), 1))
                .append("%) |\n");
        sb.append("| Сделок в ленте | ").append(data.tradesTotal()).append(" |\n");
        sb.append("| Сделок без стороны агрессора | ").append(data.tradesUnknownSide()).append(" (")
                .append(round(100.0 * data.tradesUnknownSide() / Math.max(1, data.tradesTotal()), 2))
                .append("%) |\n");
        sb.append("| base_step / quote_step | ").append(limits.baseStep()).append(" / ")
                .append(base.quoteStep()).append(" |\n\n");

        sb.append("## Прогоны\n\n");
        sb.append("| Прогон | Total | Спред | Инвентарь | Комиссии | Исполнений | Доля потока | Просадка |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Run run : runs) {
            PnlBook.Decomposition pnl = run.result().pnl();
            sb.append("| ").append(run.label())
                    .append(" | ").append(round(pnl.total(), 4))
                    .append(" | ").append(round(pnl.spreadCapture(), 4))
                    .append(" | ").append(round(pnl.inventoryPnl(), 4))
                    .append(" | ").append(round(pnl.fees(), 4))
                    .append(" | ").append(pnl.fillCount())
                    .append(" | ").append(round(100 * run.result().flowShare(), 2)).append("%")
                    .append(" | ").append(round(run.result().maxDrawdown(), 4))
                    .append(" |\n");
        }

        sb.append("\n## Лестница отступа: где на самом деле рабочая точка (док. 75 §5)\n\n");
        sb.append("Контроли считаются на КАЖДОЙ ступени, а не только при базовом `d`. "
                + "Чистый край = `markout(0) + markout(Δ)` на единицу оборота: только его "
                + "и можно сравнивать с комиссией.\n\n");
        sb.append("| Отступ `d` | Исполнений/сут | Оборот стратегии | Захват, б.п. "
                + "| Чистый край 60 с | 300 с | 1 ч | Край 60 с: покупки / продажи "
                + "| Порог maker (60 с) | **Край × оборот** | Доля инвентаря "
                + "| Время с полным инвентарём | Total | Buy & hold | Случайные: процентиль |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Rung rung : ladder) {
            SimEngine.Result r = rung.result();
            double perDay = r.fills().size() / (data.spanMs() / 86_400_000.0);
            double net60 = netEdgeBp(r, 60_000);
            sb.append("| ").append(round(rung.offset() * 100, 3)).append("%")
                    .append(rung.offset() == base.offset() ? " (базовый)" : "")
                    .append(" | ").append(Math.round(perDay))
                    .append(" | ").append(round(turnover(r), 0))
                    .append(" | ").append(round(turnover(r) > 0
                            ? r.pnl().spreadCapture() / turnover(r) * 10_000 : Double.NaN, 2))
                    .append(" | ").append(round(net60, 2))
                    .append(" | ").append(round(netEdgeBp(r, 300_000), 2))
                    .append(" | ").append(round(netEdgeBp(r, 3_600_000), 2))
                    .append(" | ").append(round(netEdgeBp(r, 60_000, Side.BUY), 2)).append(" / ")
                    .append(round(netEdgeBp(r, 60_000, Side.SELL), 2))
                    .append(" | ").append(round(net60 / 100, 4)).append("%")
                    // Край на единицу оборота, умноженный на оборот: ожидаемый доход
                    // ОТ КРАЯ, без инвентарной компоненты. Край монотонно растёт с
                    // отступом, оборот монотонно падает — рабочая точка там, где их
                    // произведение максимально, и в этой величине нет беты, которая
                    // делает `total` непригодным для выбора параметра.
                    .append(" | **").append(round(net60 * turnover(r) / 10_000, 1)).append("**")
                    .append(" | ").append(round(100 * r.pnl().inventoryPnl()
                            / (Math.abs(r.pnl().total()) < 1e-9 ? 1 : r.pnl().total()), 1))
                    .append("%")
                    // При полном инвентаре бид не выставляется: стратегия перестаёт быть
                    // двусторонней. Широкий отступ этот показатель ухудшает — реже
                    // исполняешься, дольше сидишь в позиции.
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" | ").append(round(r.buyAndHoldPnl(), 1))
                    .append(" | ").append(round(rung.nulls().capturePercentile(), 0)).append("%")
                    .append(" |\n");
        }
        sb.append("\n**«Край × оборот» — метрика выбора рабочей точки, а не `total`.** "
                + "Край на единицу оборота растёт с отступом монотонно, оборот падает; "
                + "рабочая точка там, где произведение максимально. В отличие от `total` "
                + "в ней нет инвентарной компоненты, то есть нет беты, которая на "
                + "растущем окне двигает `total` сильнее самого края.\n\n"
                + "«Исполнений/сут» равно числу постановок: после каждого исполнения "
                + "заявку надо создать заново (`POST /orders`, 1 000/сутки), тогда как "
                + "перевыставление цены — это `replace` без суточного потолка. Поэтому "
                + "ВМЕСТИМОСТЬ ПО ПАРАМ управляется отступом, а не темпом запросов.\n\n");
        sb.append("**Колонка «покупки / продажи» — главная в этой таблице.** Край, "
                + "положительный с обеих сторон, — это преимущество котирования. Край, "
                + "который держится на покупках при отрицательных продажах, — это рынок, "
                + "который рос в окне наблюдения, и на падении он сменит знак. "
                + "Горизонт 1 ч приведён для сопоставления со временем удержания, но "
                + "измеряет он уже не отбор, а дрейф: на нём разброс markout на порядок "
                + "больше самого края.\n\n");

        sb.append("### Нулевое распределение контроля (док. 75 §4)\n\n");
        sb.append("Один seed случайных котировок — это монетка, а не вердикт. Ниже — ")
                .append(cfg.simRandomSeeds())
                .append(" независимых прогонов контроля на каждой ступени; процентиль ")
                .append("показывает, какую долю из них стратегия обошла по захвату спреда. ")
                .append("50% означает «неотличима от случайной», и вопрос «побит контроль ")
                .append("или нет» на этом закрывается числом.\n\n");
        sb.append("| Отступ | Захват стратегии | Контроль: среднее | σ | 5-й … 95-й процентиль "
                + "| Стратегия выше по захвату | (σ) | Стратегия выше по `total` |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Rung rung : ladder) {
            Null n = rung.nulls();
            double capture = rung.result().pnl().spreadCapture();
            double z = n.captureSd() > 0 ? (capture - n.captureMean()) / n.captureSd() : Double.NaN;
            sb.append("| ").append(round(rung.offset() * 100, 3)).append("%")
                    .append(" | ").append(round(capture, 1))
                    .append(" | ").append(round(n.captureMean(), 1))
                    .append(" | ").append(round(n.captureSd(), 1))
                    .append(" | ").append(round(n.captureP05(), 1)).append(" … ")
                    .append(round(n.captureP95(), 1))
                    .append(" | ").append(round(n.capturePercentile(), 0)).append("%")
                    .append(" | ").append(round(z, 1)).append("σ")
                    .append(" | ").append(round(n.totalPercentile(), 0)).append("% |\n");
        }
        sb.append("\nДве последние колонки отвечают на разные вопросы. Захват изолирует "
                + "ВЫБОР ЦЕН: инвентарная компонента у контроля устроена так же, поэтому "
                + "разница по захвату — это и есть вклад котирования. `total` смешивает "
                + "его с бетой, и именно поэтому вердикт по `total` на одном seed'е "
                + "(док. 74) оказался неустойчивым.\n\n");

        sb.append("### Диагностика модели исполнения по ступеням (ТЗ §0)\n\n");
        sb.append("Лестница монотонна, значит первое подозрение — на допущения об "
                + "очереди. Заявка, улучшающая книгу, встаёт одна на новом уровне и "
                + "очередь перед ней нулевая; заявка, совпавшая с существующим уровнем, "
                + "встаёт в конец очереди. Если весь результат широкого отступа держится "
                + "на исполнениях первого типа, значит модель просто разрешила нам "
                + "стоять там, где в реальности пришлось бы ждать.\n\n");
        sb.append("| Отступ | Улучшаем книгу, % окон | Встаём в очередь | Вне видимых уровней "
                + "| Исполнений от улучшающих заявок | Очередь при исполнении: медиана / 90-й "
                + "| Перехваченных принтов | **Зависит от маршрутизации** "
                + "| Дальность перехвата, б.п.: медиана / 90-й |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Rung rung : ladder) {
            ExecutionModel.Stats stats = rung.result().execution();
            double[] queues = stats.queueAtFill().stream().mapToDouble(Double::doubleValue)
                    .filter(Double::isFinite).sorted().toArray();
            double[] distances = stats.interceptDistanceBp().stream()
                    .mapToDouble(Double::doubleValue).filter(Double::isFinite).sorted().toArray();
            sb.append("| ").append(round(rung.offset() * 100, 3)).append("%")
                    .append(" | ").append(round(100 * stats.improvingShare(), 1)).append("%")
                    .append(" | ").append(round(100 * (1 - stats.improvingShare()
                            - stats.invisibleShare()), 1)).append("%")
                    .append(" | ").append(round(100 * stats.invisibleShare(), 1)).append("%")
                    .append(" | ").append(round(100 * stats.improvingFillShare(), 1)).append("%")
                    .append(" | ").append(queues.length == 0 ? "—"
                            : round(pct(queues, 0.5), 4) + " / " + round(pct(queues, 0.9), 4))
                    .append(" | ").append(round(100 * stats.interceptedFillShare(), 1)).append("%")
                    .append(" | **").append(round(100 * stats.improvingFillShare(), 1)).append("%**")
                    .append(" | ").append(distances.length == 0 ? "—"
                            : round(pct(distances, 0.5), 2) + " / " + round(pct(distances, 0.9), 2))
                    .append(" |\n");
        }
        sb.append("\nЧитать так. Доля «улучшаем книгу», близкая к 100%, означает, что "
                + "очередь нам просто не встречается — предохранитель ТЗ §4.3 в таких "
                + "прогонах не работает, и считать их консервативными по этой причине "
                + "нельзя.\n\n"
                + "**Перехват и маршрутизация — разные вещи, и путать их нельзя.** "
                + "Перехваченным считается любое исполнение, где принт прошёл по цене "
                + "хуже нашей. Таких оказывается 100% — но за этой цифрой стоят два "
                + "совершенно разных случая:\n\n"
                + "1. Котировка стоит ВНУТРИ спреда (улучшает книгу), а принт прошёл по "
                + "дальнему краю. Модель утверждает, что тейкер пришёл бы к нам. На бирже "
                + "с приоритетом цены так и есть — но доходит ли до книги поток Revolut, "
                + "или он матчится внутри, симуляцией не проверить (док. 62 §10). "
                + "**Это и есть колонка «зависит от маршрутизации».**\n"
                + "2. Котировка стоит ГЛУБЖЕ лучшей цены, и принт смёл уровни до нашего. "
                + "Тут исполнение механически обязано случиться, никакого допущения нет.\n\n"
                + "Поэтому широкая котировка не только приносит больше края: она "
                + "переносит вес со случая 1 на случай 2, то есть делает результат менее "
                + "зависимым от непроверяемого. Дальность перехвата показывает, насколько "
                + "далеко модель «дотягивается» за чужой сделкой.\n\n");

        sb.append("### Кто кого ведёт: опора или котируемая книга\n\n");
        LeadLag.Result leadLag = LeadLag.compute(data.windows(), 6);
        sb.append("Положительный markout с обеих сторон объясняется двояко: либо это "
                + "настоящая мини-реверсия, либо опорная книга ОТСТАЁТ и markout просто "
                + "догоняет уже случившееся. Различает их сдвиг взаимной корреляции "
                + "приращений: `lag > 0` означает, что опора повторяет котируемую книгу "
                + "с запозданием.\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Окон в расчёте | ").append(leadLag.windows()).append(" |\n");
        sb.append("| Доля окон, где менялась опора / книга USDC | ")
                .append(round(100 * leadLag.referenceChangeRate(), 1)).append("% / ")
                .append(round(100 * leadLag.quotedChangeRate(), 1)).append("% |\n");
        if (leadLag.peak() != null) {
            sb.append("| Пик корреляции | сдвиг ").append(leadLag.peak().lagWindows())
                    .append(" окон (").append(leadLag.peak().lagWindows()
                            * cfg.authBookPeriodSeconds()).append(" с), corr = ")
                    .append(round(leadLag.peak().correlation(), 4)).append(" |\n");
        }
        sb.append("\n| Сдвиг, окон | Корреляция |\n|---|---|\n");
        for (LeadLag.Point point : leadLag.points()) {
            sb.append("| ").append(point.lagWindows()).append(" | ")
                    .append(round(point.correlation(), 4)).append(" |\n");
        }
        sb.append("\nПик на нуле означает, что книги живут синхронно и объяснение "
                + "«устаревшая опора» отпадает. Пик справа — опора отстаёт, и уровень "
                + "захвата завышен ровно на величину этого запаздывания.\n\n");

        sb.append("### Лестница скоса — по краю, а не по `total`\n\n");
        sb.append("Скос меняет не цену исполнения, а момент: при его выключении захват "
                + "спреда остаётся прежним, а весь эффект приходит в markout. В `total` "
                + "он тонет в бете, поэтому ступени сравниваются по краю и по разрыву "
                + "между сторонами (док. 79 §7).\n\n");
        sb.append("| Скос `skew_k` | Чистый край 60 с | Разрыв сторон | Исполнений "
                + "| Средний филл | Оборот | **Край × оборот** | Инвентарь: средний "
                + "| **Время с полным инвентарём** | Total |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (SkewRung rung : skewLadder) {
            SimEngine.Result r = rung.result();
            double buy = netEdgeBp(r, 60_000, Side.BUY);
            double sell = netEdgeBp(r, 60_000, Side.SELL);
            double edge = netEdgeBp(r, 60_000);
            int fills = r.fills().size();
            sb.append("| ").append(round(rung.skew() * 100, 4)).append("%")
                    .append(rung.skew() == base.skewK() ? " (базовый)" : "")
                    .append(" | ").append(round(edge, 2))
                    .append(" | ").append(round(buy - sell, 2))
                    .append(" | ").append(fills)
                    // Средний размер исполнения обязан стоять рядом: он МЕНЯЕТСЯ по
                    // ступеням, и «оборот пропорционален числу исполнений» — неверно.
                    .append(" | ").append(fills == 0 ? "—" : round(turnover(r) / fills, 0))
                    .append(" | ").append(round(turnover(r), 0))
                    .append(" | **").append(round(edge * turnover(r) / 10_000, 1)).append("**")
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | **").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%**")
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" |\n");
        }
        sb.append("\nЕсли разрыв между сторонами схлопывается при уменьшении скоса, "
                + "асимметрия — свойство котировщика, а не рынка. `total` в этой таблице "
                + "читать надо с оглядкой на режим окна: меньший скос означает «дольше "
                + "держать позицию», и на растущем окне это автоматически прибавляет беты.\n\n"
                + "**«Время с полным инвентарём» — решающая колонка.** При полном инвентаре "
                + "бид не выставляется вовсе, то есть стратегия перестаёт быть двусторонней "
                + "и превращается в удержание позиции. Слабый скос экономит край на бумаге, "
                + "но платит за это долей времени, когда котирования просто нет.\n\n"
                + "Средний филл стоит рядом с числом исполнений не для полноты: он "
                + "**меняется по ступеням**, поэтому «оборот пропорционален числу "
                + "исполнений» — неверно, и считать «край × оборот» через число "
                + "исполнений нельзя.\n\n");

        sb.append("### Край по режимам рынка внутри окна\n\n");
        sb.append("Окно поймало рост BTC на 14%, поэтому «край, который есть в среднем» "
                + "может оказаться краем, который есть только на росте. Признак режима — "
                + "дрейф опорной цены за ЧАС ПЕРЕД исполнением: он известен до сделки и "
                + "не пересекается с горизонтом markout, иначе связь появилась бы "
                + "механически. Граница плоского режима — ±0.2% за час.\n\n");
        sb.append("| Режим в час перед исполнением | Исполнений | Оборот "
                + "| Чистый край 60 с | покупки | продажи |\n|---|---|---|---|---|---|\n");
        for (RegimeSplit.Bucket bucket : RegimeSplit.compute(baseResult.fills(),
                baseResult.fairSeries(), 3_600_000L, 60_000L, 0.2)) {
            sb.append("| ").append(bucket.label())
                    .append(" | ").append(bucket.fills())
                    .append(" | ").append(round(bucket.turnover(), 0))
                    .append(" | ").append(round(bucket.netEdgeBp(), 2))
                    .append(" | ").append(round(bucket.buyEdgeBp(), 2))
                    .append(" | ").append(round(bucket.sellEdgeBp(), 2))
                    .append(" |\n");
        }
        sb.append("\nЧитать так: край, положительный ТОЛЬКО в строке «рынок рос», — это "
                + "бета, и на развороте он исчезнет. Край, сохраняющий знак во всех трёх "
                + "строках, пережил смену режима внутри окна — это самая сильная проверка, "
                + "доступная на собранных данных, пока в выборке нет настоящего падения.\n\n");

        sb.append("## Правдоподобие величины (ТЗ §0)\n\n");
        // Проверка, которой в отчёте не было, а она сильнее любого отдельного прогона:
        // приведённая к году доходность на ЗАДЕЙСТВОВАННЫЙ капитал. Профессиональный
        // маркет-мейкинг на крупных площадках даёт десятки процентов годовых; трёхзначная
        // величина у REST-поллера с пятисекундным опросом — не доказательство ошибки,
        // но указание, куда смотреть.
        double price = baseResult.fairLast() > 0 ? baseResult.fairLast() : baseResult.fairFirst();
        // Капитал считается по ПОТОЛКУ, а не по среднему инвентарю: чтобы котировать обе
        // стороны, нужен и запас базовой валюты на продажу, и запас котируемой на покупку.
        double capital = 2 * base.inventoryCap() * price;
        double edgeAbs = netEdgeBp(baseResult, 60_000) * turnover(baseResult) / 10_000;
        double yearFactor = data.spanMs() > 0 ? 31_557_600_000.0 / data.spanMs() : 0;
        double feeAtMarket = turnover(baseResult) * 0.0002;
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Задействованный капитал (2 × потолок × цена) | ")
                .append(round(capital, 0)).append(" |\n");
        sb.append("| Чистый край за окно | ").append(round(edgeAbs, 1)).append(" |\n");
        sb.append("| То же в год | ").append(round(edgeAbs * yearFactor, 0)).append(" |\n");
        sb.append("| **Годовых на задействованный капитал, край** | **")
                .append(capital > 0 ? round(100 * edgeAbs * yearFactor / capital, 0) : Double.NaN)
                .append("%** |\n");
        sb.append("| То же за вычетом maker 0.02% | ")
                .append(capital > 0
                        ? round(100 * (edgeAbs - feeAtMarket) * yearFactor / capital, 0) : Double.NaN)
                .append("% |\n");
        sb.append("| Для сравнения: `total` в год на тот же капитал | ")
                .append(capital > 0
                        ? round(100 * baseResult.pnl().total() * yearFactor / capital, 0) : Double.NaN)
                .append("% |\n\n");
        sb.append("Ориентир: профессиональный маркет-мейкинг на крупных площадках — "
                + "десятки процентов годовых на задействованный капитал. Трёхзначная "
                + "величина здесь не доказывает ошибку — ниши бывают, — но это **самый "
                + "сильный сигнал в отчёте**, и указывает он туда же, куда и колонка "
                + "«зависит от маршрутизации»: на единственное допущение, которого "
                + "симуляция не проверяет. Пока оно не проверено живыми заявками, эти "
                + "проценты — верхняя граница, а не план.\n\n");

        sb.append("## Сколько живёт инвентарь (ТЗ §5.3)\n\n");
        HoldingTime.Stats holding = HoldingTime.compute(baseResult.fills());
        sb.append("От этого числа зависит, какой горизонт markout сравнивать с комиссией "
                + "(док. 75 §3), поэтому оно стоит рядом с порогом, а не в приложении. "
                + "Сопоставление лотов — FIFO, то есть самое длинное из возможных: "
                + "ответ дан в невыгодную для стратегии сторону.\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Закрытых лотов | ").append(holding.lots()).append(" |\n");
        sb.append("| Время удержания: медиана / 90-й процентиль | ")
                .append(humanMs(holding.medianMs())).append(" / ")
                .append(humanMs(holding.p90Ms())).append(" |\n");
        sb.append("| Средневзвешенное по объёму | ").append(humanMs(holding.meanMs())).append(" |\n");
        sb.append("| Инвентарь, не разгруженный до конца окна | ")
                .append(round(holding.unclosedQty(), 4)).append(" (")
                .append(round(100 * holding.unclosedShare(), 1)).append("% купленного) |\n\n");
        long longest = HORIZONS_MS[HORIZONS_MS.length - 1];
        if (holding.medianMs() > longest) {
            // Это не мелочь оформления: если позиция живёт дольше любого измеренного
            // горизонта, то «неблагоприятный отбор» на этих горизонтах не описывает
            // риск, который стратегия на себя берёт. Остаток уходит в inventory_pnl,
            // то есть в бету — и никакой порог по комиссии его не покрывает.
            sb.append("**Горизонты markout короче жизни позиции в ")
                    .append(round(holding.medianMs() / longest, 1)).append(" раз.** Медиана ")
                    .append("удержания — ").append(humanMs(holding.medianMs()))
                    .append(", самый длинный измеренный горизонт — ").append(humanMs(longest))
                    .append(". Значит порог по комиссии, посчитанный на любом из них, ")
                    .append("**оптимистичен**: то, что происходит с ценой за оставшиеся ")
                    .append(humanMs(holding.medianMs() - longest))
                    .append(", попадает не в markout, а в переоценку инвентаря — в бету. ")
                    .append("Конструкция с таким удержанием — не маркет-мейкинг в смысле ")
                    .append("ТЗ §4.5, а позиционная торговля с котируемым входом.\n\n");
        } else {
            long applicable = holding.medianMs() <= 60_000 ? 60_000 : 300_000;
            sb.append("**Применимый горизонт: ").append(applicable / 1000).append(" с** — по медиане ")
                    .append("времени удержания. Порог по комиссии на нём: **")
                    .append(round(netEdgeBp(baseResult, applicable) / 100, 4)).append("%**")
                    .append(" (при базовом `d`).\n\n");
        }

        sb.append("## Контроли (ТЗ §4.7 — главные)\n\n");
        SimEngine.Result random = runs.stream()
                .filter(r -> r.label().startsWith("контроль"))
                .findFirst().orElseThrow().result();
        double buyHold = baseResult.buyAndHoldPnl();
        sb.append("| Что | Результат | Вердикт |\n|---|---|---|\n");
        sb.append("| Стратегия | ").append(round(baseResult.pnl().total(), 4)).append(" | — |\n");
        sb.append("| Buy & hold на среднем инвентаре ")
                .append(round(baseResult.avgInventory(), 4)).append(" | ").append(round(buyHold, 4))
                .append(" | ").append(verdict(baseResult.pnl().total() > buyHold)).append(" |\n");
        sb.append("| Случайные котировки | ").append(round(random.pnl().total(), 4))
                .append(" | ").append(verdict(baseResult.pnl().total() > random.pnl().total()))
                .append(" |\n\n");

        sb.append("## Неблагоприятный отбор (ТЗ §4.5)\n\n");
        sb.append("Стороны разделены намеренно. На растущем рынке markout покупок "
                + "положителен, а продаж отрицателен просто оттого, что цена шла вверх; "
                + "агрегат по обеим сторонам в таком окне показывает «край» там, где "
                + "работало направление. Если плюс держится ТОЛЬКО на покупках — это "
                + "незакрытая длинная позиция, а не преимущество котирования.\n\n");
        sb.append("| Горизонт | Среднее | Медиана | Только покупки | Только продажи "
                + "| Исполнений в выборке |\n|---|---|---|---|---|---|\n");
        for (long horizon : HORIZONS_MS) {
            Markout.Stats stats = Markout.compute(baseResult.fills(), baseResult.fairSeries(), horizon);
            Markout.Stats buys = Markout.compute(baseResult.fills(), baseResult.fairSeries(),
                    horizon, Side.BUY);
            Markout.Stats sells = Markout.compute(baseResult.fills(), baseResult.fairSeries(),
                    horizon, Side.SELL);
            sb.append("| ").append(horizon / 1000).append(" с | ").append(round(stats.mean(), 6))
                    .append(" | ").append(round(stats.median(), 6))
                    .append(" | ").append(round(buys.mean(), 6)).append(" (").append(buys.fills())
                    .append(") | ").append(round(sells.mean(), 6)).append(" (").append(sells.fills())
                    .append(") | ").append(stats.fills()).append(" |\n");
        }
        // ТЗ §5.5 в исходной формулировке закрывал направление на ЛЮБОМ отрицательном
        // markout — включая −0.010 при захваченном крае +0.156. Экономически значим
        // край на сделку целиком: half_spread + markout (ТЗ §4.5), и он же тут считается.
        Markout.Stats atFill = Markout.compute(baseResult.fills(), baseResult.fairSeries(), 0);
        Markout.Stats at60 = Markout.compute(baseResult.fills(), baseResult.fairSeries(), 60_000);
        Markout.Stats at300 = Markout.compute(baseResult.fills(), baseResult.fairSeries(), 300_000);
        double edge60 = atFill.mean() + at60.mean();
        sb.append("\n### Край на сделку, а не знак markout (правка ТЗ §5.5 по док. 71 §3.1)\n\n");
        sb.append("| Величина | Значение |\n|---|---|\n");
        sb.append("| Захваченный край на исполнение, `markout(0)` | ").append(round(atFill.mean(), 4))
                .append(" |\n");
        sb.append("| `markout(60 с)` | ").append(round(at60.mean(), 4)).append(" |\n");
        sb.append("| **Край на сделку через 60 с** | **").append(round(edge60, 4)).append("** |\n");
        sb.append("| Неблагоприятный отбор как доля края, 60 с | ")
                .append(atFill.mean() > 0 ? round(-at60.mean() / atFill.mean() * 100, 1) + "%" : "—")
                .append(" |\n");
        sb.append("| То же, 300 с | ")
                .append(atFill.mean() > 0 ? round(-at300.mean() / atFill.mean() * 100, 1) + "%" : "—")
                .append(" |\n\n");
        if (atFill.fills() == 0 || Double.isNaN(edge60)) {
            sb.append("Исполнений в выборке нет — край на сделку не считается.\n\n");
        } else {
            sb.append(edge60 > 0
                    ? "**Край на сделку положителен**: поток не токсичен в том смысле, который "
                    + "закрывает направление. Отдельный отрицательный `markout(60 с)` при этом — не "
                    + "приговор, а стоимость немедленности, которую край покрывает.\n\n"
                    : "**Край на сделку не положителен** — вот это и закрывает направление: то, что "
                    + "захватывается на исполнении, уходит на неблагоприятный отбор в течение "
                    + "минуты.\n\n");
        }

        // Захват спреда «на исполнение» несопоставим между прогонами при частичных
        // исполнениях: то же ТЗ §5.3 требует приводить метрику к обороту (док. 71 §3.3).
        sb.append("### Распределение исполненного объёма (правка ТЗ §5.3 по док. 71 §3.3)\n\n");
        double[] quantities = baseResult.fills().stream().mapToDouble(Fill::qty).sorted().toArray();
        double turnover = baseResult.fills().stream().mapToDouble(Fill::notional).sum();
        if (quantities.length == 0) {
            sb.append("Исполнений нет — распределение не считается.\n\n");
        } else {
            double totalQty = java.util.Arrays.stream(quantities).sum();
            double median = quantities[quantities.length / 2];
            double p90 = quantities[(int) (0.9 * (quantities.length - 1))];
            sb.append("| Показатель | Значение |\n|---|---|\n");
            sb.append("| Исполнений | ").append(quantities.length).append(" |\n");
            sb.append("| Объём: сумма / медиана / 90-й процентиль | ").append(round(totalQty, 4))
                    .append(" / ").append(round(median, 6)).append(" / ").append(round(p90, 6))
                    .append(" |\n");
            sb.append("| Средний объём на исполнение | ").append(round(totalQty / quantities.length, 6))
                    .append(" |\n");
            // Оборот СТРАТЕГИИ (что прошло через наши заявки) — не путать с оборотом
            // рынка в отчёте по парам: их отношение и есть доля потока (док. 75 §6).
            sb.append("| Оборот стратегии (нотионал) | ").append(round(turnover, 2)).append(" |\n");
            sb.append("| Захват спреда на единицу оборота, до отбора | ")
                    .append(turnover > 0 ? round(baseResult.pnl().spreadCapture() / turnover * 10_000, 2)
                            + " б.п." : "—")
                    .append(" |\n");
            sb.append("| **Чистый край на оборот, 60 с / 300 с** | **")
                    .append(round(netEdgeBp(baseResult, 60_000), 2)).append(" / ")
                    .append(round(netEdgeBp(baseResult, 300_000), 2)).append(" б.п.** |\n");
            sb.append("| Захват спреда на исполнение | ")
                    .append(round(baseResult.pnl().spreadCapture() / quantities.length, 4)).append(" |\n\n");
            sb.append("Метрика «на оборот» — основная: при частичных исполнениях «на исполнение» "
                    + "несопоставима между прогонами, потому что зависит от того, как поток "
                    + "раздробил заявку.\n\n");
            // Захват по построению близок к котируемому отступу d, и совпадение этой
            // величины между парами не говорит о площадке ничего (док. 75 §2). Смысл
            // отношения другой: сколько котируемого края доходит до нас в момент
            // исполнения, то есть чего стоят очередь и частичные исполнения.
            sb.append("Захват — это ")
                    .append(round(100 * (turnover > 0
                            ? baseResult.pnl().spreadCapture() / turnover : 0) / base.offset(), 0))
                    .append("% котируемого отступа `d` = ").append(round(base.offset() * 100, 3))
                    .append("%. Совпадение этой доли между парами — арифметика, а не свойство "
                            + "площадки: читать её нужно как «сколько котируемого края доживает "
                            + "до исполнения», и потери дальше идут в markout, а не в очередь.\n\n");
        }

        sb.append("## Реализуемость (ТЗ §5.4 п.6)\n\n");
        double requotesPerDay = baseResult.requotesPerDay(data.spanMs());
        sb.append("| Показатель | Значение |\n|---|---|\n");
        // Два РАЗНЫХ лимита площадки (developer.revolut.com/docs/x-api): POST /orders —
        // 10/с и 1000/сутки, PUT /orders/{id} (replace) — 10/с и БЕЗ суточного потолка.
        // Перевыставление цены — это replace, поэтому суточный потолок оно не ест;
        // новую постановку требует только исполненная заявка. Считать перевыставления
        // против 1000/сутки (как было в первой версии) — арифметически неверно.
        double fillsPerDay = baseResult.fills().size() / (data.spanMs() / 86_400_000.0);
        sb.append("| Перевыставлений в сутки (`replace`) | ").append(Math.round(requotesPerDay))
                .append(" = ").append(round(requotesPerDay / 86_400.0, 2)).append(" /с |\n");
        sb.append("| Лимит `PUT /orders/{id}` | 10/с, суточного потолка нет |\n");
        sb.append("| Новых постановок в сутки (`place` после исполнения) | ")
                .append(Math.round(fillsPerDay)).append(" |\n");
        sb.append("| Лимит `POST /orders` | 10/с и **1000 в сутки** |\n");
        boolean replaceOk = requotesPerDay / 86_400.0 <= 10.0;
        boolean placeOk = fillsPerDay <= 1000;
        sb.append("| Вердикт | ").append(replaceOk && placeOk
                        ? "влезаем, но **условно**: схема обязана быть replace-first — "
                          + "«отменить и поставить заново» упирается в 1000/сутки сразу"
                        : (!replaceOk ? "**НЕ ВЛЕЗАЕМ по темпу replace**"
                                      : "**НЕ ВЛЕЗАЕМ по постановкам** — исполнений больше 1000/сутки"))
                .append(" |\n");
        sb.append("| Инвентарь: средний / максимум | ").append(round(baseResult.avgInventory(), 4))
                .append(" / ").append(round(baseResult.maxInventory(), 4)).append(" |\n");
        sb.append("| Доля времени с полным инвентарём | ")
                .append(round(100.0 * baseResult.windowsAtCap() / Math.max(1, baseResult.windows()), 1))
                .append("% |\n");
        sb.append("| Разложение P&L сходится | ")
                .append(baseResult.pnl().reconciles(1e-6) ? "да" : "**НЕТ — считать результат нельзя**")
                .append(" |\n");
        return sb.toString();
    }

    private static String verdict(boolean strategyWins) {
        return strategyWins ? "стратегия выше" : "**контроль не побит**";
    }

    private void write(String out, String markdown) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записался отчёт " + out, e);
        }
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }
}
