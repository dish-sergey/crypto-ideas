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

    public void run(String symbol, int hours, String out) {
        long toMs = System.currentTimeMillis();
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
        // Контроль: случайные котировки (тот же присутствие в книге, цены наугад)
        SimEngine.Result randomResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.random(base, cfg.simRandomSeed())).run(data.windows());
        runs.add(new Run("контроль: случайные котировки", randomResult, cfg.simMakerFee(),
                base.offset(), base.inventoryCap()));

        for (Run run : runs) {
            registry.record(run.label(), symbol, data.fromMs(), data.toMs(),
                    configOf(run, base, limits), resultOf(run.result(), data));
        }

        String markdown = render(symbol, hours, data, base, limits, runs, baseResult);
        write(out, markdown);
        log.info("{}: {} прогонов, базовый total={} (спред {} + инвентарь {}), исполнений {} → {}",
                symbol, runs.size(), round(baseResult.pnl().total(), 4),
                round(baseResult.pnl().spreadCapture(), 4), round(baseResult.pnl().inventoryPnl(), 4),
                baseResult.fills().size(), out);
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
        for (long horizon : new long[]{0, 10_000, 60_000, 300_000}) {
            Markout.Stats stats = Markout.compute(result.fills(), result.fairSeries(), horizon);
            out.put("markout_" + horizon / 1000 + "s_mean", stats.mean());
            out.put("markout_" + horizon / 1000 + "s_median", stats.median());
            out.put("markout_" + horizon / 1000 + "s_fills", stats.fills());
        }
        return out;
    }

    private String render(String symbol, int hours, SimDataReader.Dataset data, Quoter.Params base,
                          ExecutionModel.Limits limits, List<Run> runs, SimEngine.Result baseResult) {
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

        sb.append("\n## Контроли (ТЗ §4.7 — главные)\n\n");
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
        sb.append("| Горизонт | Среднее | Медиана | Исполнений в выборке |\n|---|---|---|---|\n");
        for (long horizon : new long[]{0, 10_000, 60_000, 300_000}) {
            Markout.Stats stats = Markout.compute(baseResult.fills(), baseResult.fairSeries(), horizon);
            sb.append("| ").append(horizon / 1000).append(" с | ").append(round(stats.mean(), 6))
                    .append(" | ").append(round(stats.median(), 6))
                    .append(" | ").append(stats.fills()).append(" |\n");
        }
        sb.append("\nОтрицательный markout(60 с) означает смерть стратегии независимо от эквити.\n\n");

        sb.append("## Реализуемость (ТЗ §5.4 п.6)\n\n");
        double requotesPerDay = baseResult.requotesPerDay(data.spanMs());
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Перевыставлений в сутки | ").append(Math.round(requotesPerDay)).append(" |\n");
        sb.append("| Лимит площадки на постановку ордеров | 1000 в сутки |\n");
        sb.append("| Вердикт | ").append(requotesPerDay <= 1000
                ? "влезаем" : "**НЕ ВЛЕЗАЕМ** — требуется replace вместо place либо реже котировать")
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
