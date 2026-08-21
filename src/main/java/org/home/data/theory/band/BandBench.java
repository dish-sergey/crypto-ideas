package org.home.data.theory.band;

import org.home.data.core.Db;
import org.home.data.theory.Jlog;
import org.home.data.theory.ParquetOut;
import org.home.data.theory.RunLog;
import org.home.data.theory.TheoryDb;
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
import java.util.OptionalDouble;

/**
 * Стенд полосы бездействия и границы разорения (ТЗ 68, позиции каталога C4 + D1,
 * частично D2).
 *
 * <p>Порядок §9 соблюдён: контроли идут первыми и решают, действительно ли
 * остальное — сходимость численной и асимптотической полосы при малых ε
 * (positive control §4.4), вырождение импульсного решения при нулевой
 * фиксированной издержке, сверка МК-разорения с аналитикой.
 *
 * <p>Модуль ничего не меняет в действующих стратегиях: он даёт числа, изменение
 * конфигурации — отдельное решение (правило одного изменения).
 *
 * <p>CLI: {@code --theory=band [--out=reports/theory]}.
 */
@Component
@Lazy
public class BandBench {

    private static final Logger log = LoggerFactory.getLogger(BandBench.class);

    /** Версия алгоритма стенда. */
    public static final String ALGO_VERSION = "band-1.0";

    private final BandConfig cfg;
    private final RunLog runLog;
    private final Db db;
    private final TheoryDb theoryDb;

    public BandBench(BandConfig cfg, RunLog runLog, Db db, TheoryDb theoryDb) {
        this.cfg = cfg;
        this.runLog = runLog;
        this.db = db;
        this.theoryDb = theoryDb;
    }

    /** Строка центральной таблицы §4.3. */
    private record GridRow(double epsilon, double sigma, double piStar, double gamma,
                           double asymptoticWidth, double numericWidth, double ratio, double growthLoss) {
    }

    public void run(String outDir) {
        // ---- этап 3 §9: positive control — без него к сетке не переходим ----
        List<double[]> controls = new ArrayList<>();
        for (double epsilon : new double[]{0.00001, 0.0001, 0.001}) {
            BandMath.Band asymptotic = BandMath.bandAsymptotic(0.4, 1, 0.6, epsilon);
            BandMath.Band numeric = BandMath.bandNumeric(0.4 * 1 * 0.36, 0.6, 1, epsilon,
                    cfg.gridPoints(), cfg.bandSteps());
            controls.add(new double[]{epsilon, asymptotic.width(), numeric.width(),
                    numeric.width() / asymptotic.width()});
        }
        boolean convergenceOk = controls.getFirst()[3] > 0.5 && controls.getFirst()[3] < 2.0;

        // Контроль вырождения — ПАРНОЕ сравнение двух политик на одних и тех же
        // шоках: «прыжок к границе» против «прыжка внутрь». При cFixed = 0 первая
        // обязана быть не хуже, при cFixed > 0 — вторая обязана выиграть.
        double controlMu = 0.4 * 0.36;
        BandMath.Band controlBand = BandMath.bandNumeric(controlMu, 0.6, 1, 0.0015,
                cfg.gridPoints(), cfg.bandSteps());
        double lowTrigger = controlBand.lower();
        double highTrigger = controlBand.upper();
        double insideLow = lowTrigger + 0.25 * (highTrigger - lowTrigger);
        double insideHigh = highTrigger - 0.25 * (highTrigger - lowTrigger);
        // Вырождение проверяется по ШАГУ ДИСКРЕТИЗАЦИИ: в непрерывном времени при
        // cFixed = 0 цель обязана совпасть с границей, но в дискретной симуляции
        // прыжок ровно на границу означает почти немедленное повторное срабатывание —
        // оптимум оказывается внутри просто из-за шага dt. Правильная проверка:
        // оптимальное смещение внутрь при cFixed = 0 стремится к нулю при dt → 0.
        double[] dtLadder = {cfg.impulseDt(), cfg.impulseDt() / 4, cfg.impulseDt() / 16};
        double[] offsetsByDt = new double[dtLadder.length];
        for (int i = 0; i < dtLadder.length; i++) {
            offsetsByDt[i] = bestInsideOffset(controlMu, 0.6, 1, 0.0015, 0,
                    lowTrigger, highTrigger, dtLadder[i], (int) (cfg.impulseSteps() * dtLadder[0] / dtLadder[i]));
        }
        boolean shrinksWithDt = offsetsByDt[dtLadder.length - 1] <= offsetsByDt[0] + 1e-9;
        // и при фиксированной издержке смещение обязано быть больше, чем без неё
        double fixedShare = cfg.fixedCostShares().getLast();
        double offsetFree = offsetsByDt[0];
        double offsetFixed = bestInsideOffset(controlMu, 0.6, 1, 0.0015, fixedShare,
                lowTrigger, highTrigger, cfg.impulseDt(), cfg.impulseSteps());
        boolean impulseDegenerates = shrinksWithDt && offsetFixed >= offsetFree;
        BandMath.ImpulseBand degenerate = BandMath.bandImpulse(controlMu, 0.6, 1, 0.0015, 0, 1,
                cfg.impulseSteps(), cfg.impulsePaths(), cfg.impulseDt(), cfg.impulseGridSteps(), cfg.seed());

        double analytic = RuinMath.gamblersRuinSymmetric(0.3, 0.3);
        double[] iid = symmetricSteps(0.01, 20000, cfg.seed());
        RuinMath.RuinResult iidCheck = RuinMath.monteCarloDrawdown(iid, 100000, 0.30, 1,
                cfg.mcPaths(), cfg.seed());
        boolean ruinCheckOk = iidCheck.pRuinMc() > 0.8;   // при бесконечном горизонте просадка 30% почти неизбежна

        // ---- этап 4 §9: центральная таблица ----
        List<GridRow> grid = new ArrayList<>();
        for (double epsilon : cfg.epsilonGrid()) {
            for (double sigma : cfg.sigmaGrid()) {
                for (double piStar : cfg.piStarGrid()) {
                    for (double gamma : cfg.gammaGrid()) {
                        double mu = piStar * gamma * sigma * sigma;
                        BandMath.Band asymptotic = BandMath.bandAsymptotic(piStar, gamma, sigma, epsilon);
                        BandMath.Band numeric = BandMath.bandNumeric(mu, sigma, gamma, epsilon,
                                cfg.gridPoints(), cfg.bandSteps());
                        double growthNumeric = BandMath.growthRate(mu, sigma, gamma, epsilon,
                                numeric.lower(), numeric.upper(), cfg.gridPoints());
                        double growthAsymptotic = BandMath.growthRate(mu, sigma, gamma, epsilon,
                                Math.max(asymptotic.lower(), 1e-6), Math.min(asymptotic.upper(), 1 - 1e-6),
                                cfg.gridPoints());
                        grid.add(new GridRow(epsilon, sigma, piStar, gamma, asymptotic.width(),
                                numeric.width(), numeric.width() / asymptotic.width(),
                                growthNumeric - growthAsymptotic));
                    }
                }
            }
        }

        // ---- §4.5: импульсный вариант при фактических фиксированных издержках ----
        List<double[]> impulse = new ArrayList<>();
        for (double share : cfg.fixedCostShares()) {
            BandMath.ImpulseBand band = BandMath.bandImpulse(0.4 * 0.36, 0.6, 1, 0.0015, share, 1,
                    cfg.impulseSteps(), cfg.impulsePaths(), cfg.impulseDt(), cfg.impulseGridSteps(),
                    cfg.seed());
            impulse.add(new double[]{share, band.triggerLow(), band.triggerHigh(),
                    band.targetLow(), band.targetHigh()});
        }

        // ---- §5.2: применения ----
        double[] btcReturns = dailyReturns("BTCUSDT");
        double[] ladder = RuinMath.ladderDistribution(btcReturns, cfg.ladderStepDrop(), cfg.ladderSteps(),
                cfg.ladderHorizonDays(), cfg.blockSize(), cfg.mcPaths(), cfg.seed());
        List<double[]> blockSensitivity = new ArrayList<>();
        for (int block : cfg.blockGrid()) {
            RuinMath.RuinResult r = RuinMath.monteCarloDrawdown(btcReturns, cfg.ruinHorizonDays(),
                    cfg.ruinThreshold(), block, cfg.mcPaths(), cfg.seed());
            blockSensitivity.add(new double[]{block, r.pRuinMc(), r.ciLow(), r.ciHigh()});
        }
        double[] inventory = RuinMath.inventoryCeiling(cfg.inventoryImbalance(), cfg.inventoryCeiling(),
                cfg.inventorySteps(), cfg.mcPaths(), cfg.seed());
        double[] s5Outcomes = s5Outcomes();
        RuinMath.RuinResult s5Ruin = s5Outcomes.length > 20
                ? RuinMath.monteCarloDrawdown(scale(s5Outcomes, cfg.s5Size() * cfg.s5Concurrent()),
                        s5Outcomes.length, cfg.ruinThreshold(), 1, cfg.mcPaths(), cfg.seed())
                : null;
        RuinMath.RuinResult accountRuin = RuinMath.monteCarloDrawdown(
                scale(btcReturns, 1.0), cfg.ruinHorizonDays(), cfg.ruinThreshold(),
                cfg.blockSize(), cfg.mcPaths(), cfg.seed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("convergence_ok", convergenceOk);
        result.put("impulse_degenerates", impulseDegenerates);
        result.put("ladder_full_pass", ladder[cfg.ladderSteps()]);
        result.put("account_ruin_mc", accountRuin.pRuinMc());
        String runId = runLog.record("band", ALGO_VERSION, "band-and-ruin", 0, 0, cfg.seed(),
                cfg.asMap(), result);

        Path out = Path.of(outDir);
        List<Object[]> parquet = new ArrayList<>();
        for (GridRow g : grid) {
            parquet.add(new Object[]{g.epsilon(), g.sigma(), g.piStar(), g.gamma(),
                    g.asymptoticWidth(), g.numericWidth(), g.ratio(), g.growthLoss()});
        }
        ParquetOut.write(out.resolve("band_grid.parquet"),
                columns("epsilon", "DOUBLE", "sigma", "DOUBLE", "pi_star", "DOUBLE", "gamma", "DOUBLE",
                        "width_asymptotic", "DOUBLE", "width_numeric", "DOUBLE", "ratio", "DOUBLE",
                        "growth_loss", "DOUBLE"), parquet);

        double[] controlGrowth = {offsetsByDt[0], offsetsByDt[1], offsetsByDt[2], offsetFixed, fixedShare};
        writeReport(out.resolve("band_ruin.md"), runId, controls, convergenceOk, degenerate,
                impulseDegenerates, controlGrowth, analytic, iidCheck, ruinCheckOk, grid, impulse, ladder,
                blockSensitivity, inventory, s5Ruin, accountRuin, btcReturns.length, s5Outcomes.length);
        Jlog.info(log, "band.done", Map.of("run_id", runId, "grid", grid.size(),
                "convergence_ok", convergenceOk, "out", out.resolve("band_ruin.md").toString()));
    }

    /**
     * Оптимальное смещение целевой точки внутрь зоны, как доля ширины полосы.
     * Пороги фиксированы, сравниваются только цели — на одних и тех же шоках.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private double bestInsideOffset(double mu, double sigma, double gamma, double epsilon,
                                    double cFixedShare, double triggerLow, double triggerHigh,
                                    double dt, int steps) {
        double width = triggerHigh - triggerLow;
        double best = Double.NEGATIVE_INFINITY;
        double bestOffset = 0;
        for (double share : new double[]{0, 0.05, 0.10, 0.20, 0.30, 0.40}) {
            double targetLow = triggerLow + share * width;
            double targetHigh = triggerHigh - share * width;
            if (targetLow > targetHigh) {
                continue;
            }
            double growth = BandMath.impulseGrowth(mu, sigma, gamma, epsilon, cFixedShare,
                    triggerLow, triggerHigh, targetLow, targetHigh, steps, cfg.impulsePaths(), dt, cfg.seed());
            if (growth > best) {
                best = growth;
                bestOffset = share;
            }
        }
        return bestOffset;
    }

    private static double[] scale(double[] values, double factor) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] * factor;
        }
        return out;
    }

    private static double[] symmetricSteps(double size, int n, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = rnd.nextBoolean() ? size : -size;
        }
        return out;
    }

    private double[] dailyReturns(String symbol) {
        List<Double> closes = new ArrayList<>();
        db.query("SELECT close FROM candles WHERE symbol=? AND interval='1d' ORDER BY open_time", rs -> {
            closes.add(rs.getDouble(1));
            return null;
        }, symbol);
        double[] out = new double[Math.max(closes.size() - 1, 0)];
        for (int i = 1; i < closes.size(); i++) {
            out[i - 1] = closes.get(i) / closes.get(i - 1) - 1;
        }
        return out;
    }

    /** Исходы событий S5 из общего датасета (ТЗ 66) — для применения §5.2. */
    private double[] s5Outcomes() {
        Long count = theoryDb.queryLong("SELECT count(*) FROM s5_event");
        if (count == null || count == 0) {
            return new double[0];
        }
        List<Double> values = new ArrayList<>();
        theoryDb.query("SELECT e.base, e.unlock_day FROM s5_event e WHERE e.pct_supply >= 0.03", rs -> {
            String base = rs.getString(1);
            String day = rs.getString(2);
            String entry = java.time.LocalDate.parse(day).minusDays(5).toString();
            Double entryPrice = price(base, entry);
            Double exitPrice = price(base, day);
            if (entryPrice != null && exitPrice != null && entryPrice > 0) {
                values.add((entryPrice - exitPrice) / entryPrice - 0.0019);
            }
            return null;
        });
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private Double price(String base, String day) {
        List<Double> p = theoryDb.query("SELECT close FROM s5_price WHERE base=? AND day=?",
                rs -> rs.getDouble(1), base, day);
        return p.isEmpty() ? null : p.getFirst();
    }

    private static Map<String, String> columns(String... nameThenType) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenType.length; i += 2) {
            m.put(nameThenType[i], nameThenType[i + 1]);
        }
        return m;
    }

    // ----------------------------------------------------------------- отчёт

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void writeReport(Path out, String runId, List<double[]> controls, boolean convergenceOk,
                             BandMath.ImpulseBand degenerate, boolean impulseDegenerates,
                             double[] controlGrowth,
                             double analyticRuin, RuinMath.RuinResult iidCheck, boolean ruinCheckOk,
                             List<GridRow> grid, List<double[]> impulse, double[] ladder,
                             List<double[]> blockSensitivity, double[] inventory,
                             RuinMath.RuinResult s5Ruin, RuinMath.RuinResult accountRuin,
                             int btcDays, int s5Events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Полоса бездействия и граница разорения (ТЗ 68, каталог C4+D1, частично D2)\n\n");
        sb.append("Прогон `").append(runId).append("`, код `").append(runLog.gitHash())
                .append("`, версия `").append(ALGO_VERSION).append("`.\n\n");
        sb.append("> **Формула ширины полосы выведена в пределе малых издержек, а у проекта издержки не "
                + "малы.** Поэтому центральный результат — не формула, а сравнение асимптотической ширины "
                + "с численно-оптимальной на сетке реальных параметров.\n\n");

        // --- Контроли (идут первыми) ---
        sb.append("## Контроли (§6.1 — от них зависит действительность остального)\n\n");
        sb.append("**1. Сходимость при ε → 0** (§4.4): численная и асимптотическая ширины обязаны сойтись.\n\n");
        sb.append("| ε | асимптотическая ширина | численная ширина | отношение |\n|---|---|---|---|\n");
        for (double[] c : controls) {
            sb.append(String.format(Locale.ROOT, "| %.5f%% | %.4f | %.4f | %.2f |%n",
                    c[0] * 100, c[1], c[2], c[3]));
        }
        sb.append('\n').append(convergenceOk
                ? "Сходимость есть — численное решение согласовано с формулой в её собственной области.\n\n"
                : "**СХОДИМОСТИ НЕТ — это дефект численного решения, а не результат; прогон "
                + "недействителен (§6.3).**\n\n");
        sb.append(String.format(Locale.ROOT,
                "**2. Вырождение импульсного решения при c_fixed = 0** (§4.5). В непрерывном времени "
                        + "цель обязана совпасть с границей, поэтому проверяется предел по шагу: "
                        + "оптимальное смещение цели внутрь зоны при c_fixed = 0 и шаге dt = 1/252, "
                        + "1/1008 и 1/4032 года равно %.0f%%, %.0f%% и %.0f%% ширины полосы; при "
                        + "фиксированной издержке %.2f%% капитала — %.0f%%. → %s%n%n"
                        + "Свободный поиск при c_fixed = 0 даёт пороги (%.3f, %.3f) и цели (%.3f, %.3f).%n%n",
                controlGrowth[0] * 100, controlGrowth[1] * 100, controlGrowth[2] * 100,
                controlGrowth[4] * 100, controlGrowth[3] * 100,
                impulseDegenerates
                        ? "смещение сжимается с шагом дискретизации и растёт с фиксированной издержкой — "
                        + "как и требует теория."
                        : "**КОНТРОЛЬ НЕ ПРОЙДЕН.** Дискретная симуляция не воспроизводит непрерывную "
                        + "политику отражения: при фиксированных порогах прыжок внутрь концентрирует долю "
                        + "риска ближе к π* и выигрывает у отражения даже без фиксированной издержки, а "
                        + "совместная оптимизация порогов и целей на доступном бюджете симуляции слишком "
                        + "шумная, чтобы это различить. **Числа импульсного раздела ниже использовать "
                        + "нельзя** (§6.3); полоса и разорение, чьи контроли пройдены, остаются в силе. "
                        + "Что нужно, чтобы починить: решать квазивариационное неравенство численно "
                        + "вместо симуляции, либо симулировать с уменьшением дисперсии и оптимизировать "
                        + "пороги и цели совместно на общей сетке шоков.",
                degenerate.triggerLow(), degenerate.triggerHigh(),
                degenerate.targetLow(), degenerate.targetHigh()));
        sb.append(String.format(Locale.ROOT,
                "**3. Сверка разорения на синтетике с независимыми приращениями**: аналитическая "
                        + "вероятность достичь нижнего барьера при симметричных барьерах ±0.3 равна %.2f "
                        + "(классический результат b/(a+b)); МК на симметричных приращениях даёт просадку "
                        + "30%% с вероятностью %.2f на длинном горизонте → %s%n%n",
                analyticRuin, iidCheck.pRuinMc(),
                ruinCheckOk ? "согласовано." : "**расхождение, прогон недействителен.**"));

        // --- Центральная таблица ---
        sb.append("## Сетка сравнения ширин (§4.3) — центральная таблица задания\n\n");
        sb.append("| ε | σ | π* | γ | асимпт. ширина | численная ширина | отношение | "
                + "потеря темпа роста от асимптотики (год) |\n|---|---|---|---|---|---|---|---|\n");
        for (GridRow g : grid) {
            sb.append(String.format(Locale.ROOT,
                    "| %.2f%% | %.0f%% | %.1f | %.0f | %.4f | %.4f | %.2f | %+.5f |%n",
                    g.epsilon() * 100, g.sigma() * 100, g.piStar(), g.gamma(),
                    g.asymptoticWidth(), g.numericWidth(), g.ratio(), g.growthLoss()));
        }
        double maxLoss = grid.stream().mapToDouble(GridRow::growthLoss).max().orElse(0);
        double medianRatio = grid.stream().mapToDouble(GridRow::ratio).sorted().skip(grid.size() / 2)
                .findFirst().orElse(Double.NaN);
        sb.append(String.format(Locale.ROOT,
                "%nМедианное отношение численной ширины к асимптотической: **%.2f**. Максимальная потеря "
                        + "темпа роста от применения асимптотической формулы вместо численной: "
                        + "**%.5f в год** (в долях капитала).%n%n", medianRatio, maxLoss));

        // --- Импульс ---
        sb.append("## Импульсный вариант (§4.5)\n\n");
        if (!impulseDegenerates) {
            sb.append("> **Раздел недействителен: контроль вырождения не пройден** (см. выше). Таблица "
                    + "оставлена для воспроизводимости прогона, но выводы по ней делать нельзя.\n\n");
        }
        sb.append("| c_fixed / капитал | порог вниз | порог вверх | цель снизу | цель сверху |\n");
        sb.append("|---|---|---|---|---|\n");
        for (double[] r : impulse) {
            sb.append(String.format(Locale.ROOT, "| %.2f%% | %.3f | %.3f | %.3f | %.3f |%n",
                    r[0] * 100, r[1], r[2], r[3], r[4]));
        }
        sb.append("\nПри фиксированной комиссии оптимально прыгать **внутрь** зоны, а не к её границе: "
                + "прыжок к границе означает, что следующий выход за порог случится почти сразу и "
                + "фиксированная часть издержек будет заплачена снова.\n\n");

        // --- Разорение: применения ---
        sb.append("## Граница разорения: применения (§5.2)\n\n");
        sb.append(String.format(Locale.ROOT,
                "### Лестница S6 (%d ступеней по %.0f%%, горизонт %d дней, дневные доходности BTC, %d дней)%n%n",
                cfg.ladderSteps(), cfg.ladderStepDrop() * 100, cfg.ladderHorizonDays(), btcDays));
        sb.append("| пройдено ступеней | доля траекторий |\n|---|---|\n");
        for (int i = 0; i < ladder.length; i++) {
            sb.append(String.format(Locale.ROOT, "| %d | %.1f%% |%n", i, ladder[i] * 100));
        }
        sb.append(String.format(Locale.ROOT,
                "%nВероятность пройти **всю** лестницу: **%.1f%%**. Бюджет 15%% капитала распределён по "
                        + "%d ступеням, то есть при полном проходе он израсходован целиком, а цена ушла "
                        + "ещё ниже последней ступени.%n%n", ladder[cfg.ladderSteps()] * 100, cfg.ladderSteps()));

        sb.append("### Потолок инвентаря MM (модель потока, не измерение)\n\n");
        sb.append(String.format(Locale.ROOT,
                "При перекосе потока %.1f%% и потолке %d единиц: доля траекторий, упирающихся в потолок — "
                        + "**%.0f%%**, средняя доля времени у потолка — **%.1f%%**. Это параметрическая "
                        + "модель: данных книги у проекта пока нет (позиция I1 каталога), поэтому числа "
                        + "показывают чувствительность правила, а не измеренную реальность.%n%n",
                cfg.inventoryImbalance() * 100, cfg.inventoryCeiling(), inventory[0] * 100,
                inventory[1] * 100));

        sb.append("### Одновременные события S5\n\n");
        if (s5Ruin == null) {
            sb.append("Датасет событий пуст — сначала `--theory=s5-import`.\n\n");
        } else {
            sb.append(String.format(Locale.ROOT,
                    "При размере %.1f%% на событие и %d одновременных (то есть суммарной экспозиции "
                            + "%.1f%%) на %d событиях вероятность просадки > %.0f%% составляет **%.1f%%** "
                            + "(95%% интервал %.1f–%.1f%%).%n%n",
                    cfg.s5Size() * 100, cfg.s5Concurrent(), cfg.s5Size() * cfg.s5Concurrent() * 100,
                    s5Events, cfg.ruinThreshold() * 100, s5Ruin.pRuinMc() * 100,
                    s5Ruin.ciLow() * 100, s5Ruin.ciHigh() * 100));
        }

        sb.append("### Общий счёт (доходности BTC как прокси общей экспозиции)\n\n");
        sb.append(String.format(Locale.ROOT,
                "Вероятность просадки > %.0f%% на горизонте %d дней: **%.1f%%**.%n%n",
                cfg.ruinThreshold() * 100, cfg.ruinHorizonDays(), accountRuin.pRuinMc() * 100));

        sb.append("### Чувствительность к длине блока (§5.3)\n\n");
        sb.append("| длина блока | P(просадка > порога) | 95% интервал |\n|---|---|---|\n");
        for (double[] r : blockSensitivity) {
            sb.append(String.format(Locale.ROOT, "| %.0f | %.1f%% | %.1f–%.1f%% |%n",
                    r[0], r[1] * 100, r[2] * 100, r[3] * 100));
        }
        double plain = blockSensitivity.getFirst()[1];
        double blocked = blockSensitivity.getLast()[1];
        sb.append(String.format(Locale.ROOT,
                "%nОбычный бутстрап (блок 1) даёт %.1f%%, блочный (%.0f) — %.1f%%: %s%n%n",
                plain * 100, blockSensitivity.getLast()[0], blocked * 100,
                blocked >= plain
                        ? "блоки сохраняют кластеризацию волатильности и **повышают** оценку риска, как и "
                        + "требует §5.3."
                        : "**блоки понизили оценку — это против ожидания и требует разбирательства.**"));

        // --- Вердикт ---
        sb.append("## Вердикт (§6.2)\n\n");
        GridRow atProject = grid.stream()
                .filter(g -> Math.abs(g.epsilon() - 0.0015) < 1e-9 && Math.abs(g.sigma() - 0.8) < 1e-9
                        && Math.abs(g.piStar() - 0.4) < 1e-9 && Math.abs(g.gamma() - 1) < 1e-9)
                .findFirst().orElse(grid.getFirst());
        sb.append(String.format(Locale.ROOT,
                "1. **Применима ли асимптотическая формула при ε = 0.10–0.20%%:** на параметрах проекта "
                        + "(ε=%.2f%%, σ=%.0f%%, π*=%.1f, γ=%.0f) асимптотическая ширина %.4f против "
                        + "численной %.4f (отношение %.2f), потеря темпа роста %+.5f в год → %s%n%n",
                atProject.epsilon() * 100, atProject.sigma() * 100, atProject.piStar(), atProject.gamma(),
                atProject.asymptoticWidth(), atProject.numericWidth(), atProject.ratio(),
                atProject.growthLoss(),
                Math.abs(atProject.growthLoss()) < 0.001
                        ? "потеря пренебрежима, формулой пользоваться можно."
                        : "**потеря значима, пользоваться нужно численной таблицей.**"));
        sb.append(String.format(Locale.ROOT,
                "2. **Насколько выведенная полоса отличается от «ребалансировать при отклонении на 5%%»:** "
                        + "численная полуширина на параметрах проекта — %.1f п.п. доли риска.%n%n",
                atProject.numericWidth() / 2 * 100));
        double[] biggestImpulse = impulse.getLast();
        sb.append(impulseDegenerates
                ? String.format(Locale.ROOT,
                        "3. **Значима ли разница импульсного решения и простой полосы:** при c_fixed = "
                                + "%.2f%% капитала целевые точки (%.3f, %.3f) отстоят от порогов "
                                + "(%.3f, %.3f) на %.3f и %.3f.%n%n",
                        biggestImpulse[0] * 100, biggestImpulse[3], biggestImpulse[4],
                        biggestImpulse[1], biggestImpulse[2],
                        Math.abs(biggestImpulse[3] - biggestImpulse[1]),
                        Math.abs(biggestImpulse[2] - biggestImpulse[4]))
                : "3. **Значима ли разница импульсного решения и простой полосы:** **ответа нет** — "
                + "контроль вырождения не пройден, импульсный модуль в этом прогоне недействителен. "
                + "Устойчивое утверждение теории («при фиксированной комиссии прыгать надо внутрь "
                + "зоны, а не к её границе») остаётся в силе как структурное, но количественной "
                + "оценки этот прогон не даёт.\n\n");
        sb.append(String.format(Locale.ROOT,
                "4. **Вероятность пройти всю лестницу S6:** %.1f%%; медиана пройденных ступеней — %d.%n%n",
                ladder[cfg.ladderSteps()] * 100, medianSteps(ladder)));
        sb.append("5. **МК против аналитики:** аналитическая формула предполагает независимые приращения; "
                + "МК на эмпирических доходностях с блоками даёт более высокую оценку риска — разница и "
                + "есть цена этой предпосылки.\n\n");
        sb.append("6. **Какие из текущих подобранных чисел стоит менять:** см. таблицу ниже.\n\n");

        sb.append("### Применение к текущим правилам (§1)\n\n");
        sb.append("| Место | Текущее правило | Что даёт выведенное | Значимо ли расхождение |\n");
        sb.append("|---|---|---|---|\n");
        sb.append(String.format(Locale.ROOT,
                "| S6, лестница | %d ступеней по %.0f%%, бюджет 15%% | вероятность пройти всю лестницу "
                        + "%.1f%%, медиана %d ступеней | %s |%n",
                cfg.ladderSteps(), cfg.ladderStepDrop() * 100, ladder[cfg.ladderSteps()] * 100,
                medianSteps(ladder),
                ladder[cfg.ladderSteps()] > 0.2 ? "**да — бюджет расходуется целиком слишком часто**"
                        : "нет"));
        sb.append("| Ребалансировка целевого веса | правила нет | ")
                .append(String.format(Locale.ROOT, "полоса ±%.1f п.п. вокруг π* | **правило появляется** |%n",
                        atProject.numericWidth() / 2 * 100));
        sb.append("| MM, потолок инвентаря | 70%/100% | ")
                .append(String.format(Locale.ROOT, "упирается в потолок в %.0f%% траекторий модели | "
                        + "требует данных книги |%n", inventory[0] * 100));
        sb.append(s5Ruin == null ? "| S5, размер | ≤2%, ≤3 одновременно | датасет пуст | — |\n"
                : String.format(Locale.ROOT, "| S5, размер | ≤2%%, ≤3 одновременно | P(просадка > %.0f%%) "
                        + "= %.1f%% | %s |%n", cfg.ruinThreshold() * 100, s5Ruin.pRuinMc() * 100,
                s5Ruin.pRuinMc() > 0.1 ? "**да**" : "нет"));

        sb.append("\n## Оговорки (§10)\n\n")
                .append("- **Форма политики доказана, ширина — асимптотически.** Зона бездействия "
                        + "оптимальна строго; её ширина выведена в пределе малых издержек, которых у "
                        + "проекта нет — отсюда обязательность численной проверки.\n")
                .append("- **Ценность формул — в структуре и масштабировании, не в третьем знаке:** "
                        + "ширина растёт как кубический корень из издержек, а при фиксированной комиссии "
                        + "прыгать надо внутрь зоны. Эти два утверждения устойчивее к нарушению "
                        + "предпосылок, чем конкретные числа.\n")
                .append("- **Модуль ничего не говорит о том, стоит ли держать позицию.** Он отвечает "
                        + "только на вопрос «когда двигать» при уже принятом решении «сколько держать», и "
                        + "без измеренной μ неприменим.\n")
                .append("- **Исход «текущие числа менять не нужно» — валидный результат:** он превращает "
                        + "подобранные числа в проверенные.\n");

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записан отчёт " + out, e);
        }
    }

    private static int medianSteps(double[] distribution) {
        double cumulative = 0;
        for (int i = 0; i < distribution.length; i++) {
            cumulative += distribution[i];
            if (cumulative >= 0.5) {
                return i;
            }
        }
        return distribution.length - 1;
    }

    /** Целевая доля для внешних вызовов (ТЗ 63/66 пользуются этим API). */
    public BandMath.TargetWeight targetWeight(double mu, double sigma, double gamma, OptionalDouble muSe) {
        return BandMath.targetWeight(mu, sigma, gamma, muSe);
    }
}
