package org.home.data.theory.kelly;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Стенд оптимального размера позиции для S5 (ТЗ 66, каталог C2 + H1, частично H3).
 *
 * <p>Порядок §9 соблюдён: сначала распределение исходов и диагностика профиля
 * проданного опциона, затем точечный Kelly и бутстрап, затем <b>точка
 * остановки</b> — отличен ли 5-й процентиль {@code f*} от нуля. Если нет, вопрос
 * меняется с «сколько ставить» на «есть ли что ставить», и это более важный
 * вопрос.
 *
 * <p>Стенд <b>не меняет</b> конфигурацию идущего микро-live S5 (правило одного
 * изменения): он считает параллельно, применение — отдельным решением.
 *
 * <p>CLI: {@code --theory=kelly [--out=reports/theory]}.
 */
@Component
@Lazy
public class KellyBench {

    private static final Logger log = LoggerFactory.getLogger(KellyBench.class);

    /** Версия алгоритма стенда: менять при любом изменении правил §3–§5. */
    public static final String ALGO_VERSION = "kelly-1.0";

    private final S5Outcomes outcomes;
    private final KellyConfig cfg;
    private final RunLog runLog;

    public KellyBench(S5Outcomes outcomes, KellyConfig cfg, RunLog runLog) {
        this.outcomes = outcomes;
        this.cfg = cfg;
        this.runLog = runLog;
    }

    public void run(String outDir) {
        S5Outcomes.Dataset withStop = outcomes.build(cfg, cfg.stop());
        S5Outcomes.Dataset noStop = outcomes.build(cfg, 0);
        double[] x = withStop.values();
        if (x.length < 20) {
            throw new IllegalStateException("событий слишком мало: " + x.length
                    + " (нужен импорт: --theory=s5-import)");
        }
        double[] xNoStop = noStop.values();

        double fPoint = KellySizing.kellyNumeric(x);
        double fContinuous = KellySizing.kellyContinuous(x);
        double[] boot = KellySizing.bootstrapKelly(x, cfg.bootstrap(), cfg.seed());
        double fP5 = KellySizing.quantile(boot, 0.05);
        double fP25 = KellySizing.quantile(boot, 0.25);
        double fMedian = KellySizing.quantile(boot, 0.5);
        double fSelection = KellySizing.selectionAdjustedKelly(x, cfg.testedHypotheses());

        Map<Double, Double> shrinkGrid = new LinkedHashMap<>();
        for (double s : cfg.priorStrengths()) {
            shrinkGrid.put(s, KellySizing.shrunkKelly(x, s));
        }

        // --- §4.5: одновременные события ---
        long[] days = withStop.outcomes().stream()
                .mapToLong(o -> LocalDate.parse(o.unlockDay()).toEpochDay()).toArray();
        List<double[]> pairs = KellySizing.concurrentGroups(days, x, cfg.lead(), 2);
        List<double[]> triples = KellySizing.concurrentGroups(days, x, cfg.lead(), cfg.maxConcurrent());
        double pairCorrelation = KellySizing.averagePairCorrelation(pairs);
        double jointTotal = KellySizing.jointOptimalTotal(triples, cfg.maxConcurrent());
        double[] concurrency = KellySizing.concurrency(days, cfg.lead());

        // --- §5.1: разделение по времени ---
        int half = x.length / 2;
        double[] early = Arrays.copyOfRange(x, 0, half);
        double[] late = Arrays.copyOfRange(x, half, x.length);
        Map<String, Double> earlyEstimates = new LinkedHashMap<>();
        earlyEstimates.put("точечный", KellySizing.kellyNumeric(early));
        earlyEstimates.put("медиана бутстрапа",
                KellySizing.quantile(KellySizing.bootstrapKelly(early, cfg.bootstrap() / 10, cfg.seed()), 0.5));
        earlyEstimates.put("5-й процентиль бутстрапа",
                KellySizing.quantile(KellySizing.bootstrapKelly(early, cfg.bootstrap() / 10, cfg.seed()), 0.05));
        earlyEstimates.put("усадка (приор s=1)", KellySizing.shrunkKelly(early, 1));
        earlyEstimates.put("поправка на отбор", KellySizing.selectionAdjustedKelly(early, cfg.testedHypotheses()));
        earlyEstimates.put("текущий лимит " + pct(cfg.currentLimit()), cfg.currentLimit());

        // --- §5.2: сетка размеров ---
        List<Double> sizes = new ArrayList<>(cfg.sizeGrid());
        sizes.add(round(fPoint));
        sizes.add(round(Math.max(fP5, 0)));
        sizes.add(round(KellySizing.shrunkKelly(x, 1)));
        List<KellySim.SizeResult> sizeResults = new ArrayList<>();
        for (double f : sizes) {
            sizeResults.add(KellySim.simulate(x, f, cfg.monteCarloPaths(), cfg.ruinThreshold(), cfg.seed()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", x.length);
        result.put("f_point", fPoint);
        result.put("f_p5", fP5);
        result.put("f_shrunk_s1", KellySizing.shrunkKelly(x, 1));
        result.put("f_selection", fSelection);
        result.put("joint_total", jointTotal);
        result.put("pair_correlation", pairCorrelation);

        long fromMs = LocalDate.parse(withStop.outcomes().getFirst().unlockDay())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long toMs = LocalDate.parse(withStop.outcomes().getLast().unlockDay())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        String runId = runLog.record("kelly", ALGO_VERSION, "s5-sizing", fromMs, toMs, cfg.seed(),
                cfg.asMap(), result);

        Path out = Path.of(outDir);
        List<Object[]> parquetOutcomes = new ArrayList<>();
        for (S5Outcomes.Outcome o : withStop.outcomes()) {
            parquetOutcomes.add(new Object[]{o.base(), o.unlockDay(), o.pctSupply(), o.outcome(),
                    o.outcomeNoStop(), o.stopHit(), o.bullRegime(), o.btcMove()});
        }
        ParquetOut.write(out.resolve("kelly_outcomes.parquet"),
                columns("base", "VARCHAR", "unlock_day", "VARCHAR", "pct_supply", "DOUBLE",
                        "outcome", "DOUBLE", "outcome_no_stop", "DOUBLE", "stop_hit", "BOOLEAN",
                        "bull_regime", "BOOLEAN", "btc_move", "DOUBLE"), parquetOutcomes);
        List<Object[]> parquetSizes = new ArrayList<>();
        for (KellySim.SizeResult r : sizeResults) {
            parquetSizes.add(new Object[]{r.f(), r.medianWealth(), r.p5Wealth(), r.p95Wealth(),
                    r.medianMaxDd(), r.p95MaxDd(), r.pDeep(), r.growth()});
        }
        ParquetOut.write(out.resolve("kelly_sizes.parquet"),
                columns("f", "DOUBLE", "median_wealth", "DOUBLE", "p5_wealth", "DOUBLE",
                        "p95_wealth", "DOUBLE", "median_maxdd", "DOUBLE", "p95_maxdd", "DOUBLE",
                        "p_deep", "DOUBLE", "growth", "DOUBLE"), parquetSizes);

        writeReport(out.resolve("kelly_s5.md"), runId, withStop, noStop, x, xNoStop,
                fPoint, fContinuous, boot, fP5, fP25, fMedian, fSelection, shrinkGrid,
                pairs.size(), triples.size(), pairCorrelation, jointTotal,
                concurrency[0], concurrency[1], earlyEstimates, early, late, sizeResults);
        Jlog.info(log, "kelly.done", Map.of("run_id", runId, "events", x.length,
                "f_point", fPoint, "f_p5", fP5, "out", out.resolve("kelly_s5.md").toString()));
    }

    private static Map<String, String> columns(String... nameThenType) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenType.length; i += 2) {
            m.put(nameThenType[i], nameThenType[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void writeReport(Path out, String runId, S5Outcomes.Dataset withStop, S5Outcomes.Dataset noStop,
                             double[] x, double[] xNoStop, double fPoint, double fContinuous, double[] boot,
                             double fP5, double fP25, double fMedian, double fSelection,
                             Map<Double, Double> shrinkGrid, int pairGroups, int tripleGroups,
                             double pairCorrelation, double jointTotal, double observedMaxConcurrent,
                             double observedMeanConcurrent, Map<String, Double> earlyEstimates,
                             double[] early, double[] late, List<KellySim.SizeResult> sizeResults) {
        StringBuilder sb = new StringBuilder();
        double mean = KellySizing.mean(x);
        double sd = Math.sqrt(KellySizing.variance(x));
        double sdNoStop = Math.sqrt(KellySizing.variance(xNoStop));
        long stopped = withStop.outcomes().stream().filter(S5Outcomes.Outcome::stopHit).count();
        double years = (LocalDate.parse(withStop.outcomes().getLast().unlockDay()).toEpochDay()
                - LocalDate.parse(withStop.outcomes().getFirst().unlockDay()).toEpochDay()) / 365.0;

        sb.append("# Размер позиции для S5 с учётом ошибки оценки (ТЗ 66, каталог C2+H1)\n\n");
        sb.append("> **Стенд не проверяет, работает ли S5.** Он принимает эффект как данность и отвечает "
                + "только на вопрос о размере. Конфигурация идущего микро-live не меняется — правило одного "
                + "изменения.\n\n");
        sb.append("Прогон `").append(runId).append("`, код `").append(runLog.gitHash())
                .append("`, версия `").append(ALGO_VERSION).append("`. События ")
                .append(withStop.outcomes().getFirst().unlockDay()).append(" … ")
                .append(withStop.outcomes().getLast().unlockDay())
                .append(String.format(Locale.ROOT, " (%d событий за %.1f года ≈ %.0f в год).%n%n",
                        x.length, years, x.length / Math.max(years, 0.1)));

        // --- §6.1 Данные ---
        sb.append("## Данные (§6.1)\n\n");
        sb.append(String.format(Locale.ROOT,
                "| Показатель | Значение |%n|---|---|%n"
                        + "| Разлоков ≥ %s circ в датасете | %d |%n"
                        + "| Событий с ценой перпа на входе и выходе | %d |%n"
                        + "| Отменено фильтром funding (> %s за удержание) | %d |%n"
                        + "| Сработал стоп %s | %d (%.0f%%) |%n"
                        + "| Доля событий в режиме BULL по SMA200 | %.0f%% |%n"
                        + "| Групп одновременных событий (пересекающиеся окна) | %d пар, %d троек |%n",
                pct(cfg.minPct()), withStop.rawEvents(), x.length, pct(cfg.maxFundingCost()),
                withStop.skippedFunding(), pct(cfg.stop()), stopped, 100.0 * stopped / x.length,
                100.0 * withStop.outcomes().stream().filter(S5Outcomes.Outcome::bullRegime).count() / x.length,
                pairGroups, tripleGroups));
        sb.append('\n');

        // --- вселенная событий: фильтр — это определение стратегии, а не параметр ---
        sb.append("## Вселенная событий: какой фильтр применён (П2 док. 71)\n\n");
        sb.append("Фильтр S5 — **определение стратегии, а не настройка**: ослабив его, получаем другую "
                + "стратегию с другой μ. Поэтому каждое условие док. 02 v2 перечислено явно, с ответом "
                + "«применено или нет».\n\n");
        sb.append("| Условие S5 (док. 02 v2) | В этом прогоне | Комментарий |\n|---|---|---|\n");
        sb.append(String.format(Locale.ROOT,
                "| Разлок ≥ 3%% циркулирующего supply | **%s** | порог прогона `theory.kelly.min-pct`; "
                        + "в датасете хранятся события от 2%%, но в выборку они не попадают |%n",
                pct(cfg.minPct())));
        sb.append("| Получатели — инвесторы/команда | **НЕТ** | в кэше DefiLlama тип получателя не "
                + "размечен надёжно; это расширение вселенной относительно спецификации, и оно "
                + "работает против стратегии, а не за неё |\n");
        sb.append(String.format(Locale.ROOT,
                "| Есть ликвидный перп | **да** | событие берётся, только если есть минутная история "
                        + "перпа Binance на входе и выходе |%n"));
        sb.append(String.format(Locale.ROOT,
                "| Отмена при ожидаемом funding > 1.5%% | **да, %s** | причинно: ожидание берётся по "
                        + "5 дням ДО входа, а не по периоду удержания; отменено %d событий |%n",
                pct(cfg.maxFundingCost()), withStop.skippedFunding()));
        sb.append(String.format(Locale.ROOT,
                "| Стоп против позиции | **%s** (док. 02 v2 называет 8%%) | расхождение разрешается "
                        + "сеткой ниже, а не выбором удобного значения |%n", pct(cfg.stop())));
        sb.append(String.format(Locale.ROOT,
                "| Вход за 5 дней, выход в день разлока | **да, %d дней** | |%n%n", cfg.lead()));

        // --- §3.2 усечения ---
        sb.append("## Усечения распределения (§3.2)\n\n");
        sb.append(String.format(Locale.ROOT,
                "| Вариант | Среднее | σ | Минимум |%n|---|---|---|---|%n"
                        + "| Со стопом %s (то, что торгуется) | %+.2f%% | %.2f%% | %.1f%% |%n"
                        + "| Без стопа (гипотетическое) | %+.2f%% | %.2f%% | %.1f%% |%n%n",
                pct(cfg.stop()), mean * 100, sd * 100, min(x) * 100,
                KellySizing.mean(xNoStop) * 100, sdNoStop * 100, min(xNoStop) * 100));
        sb.append(stopped == 0
                ? "**Стоп не сработал ни разу.** Он не влияет на распределение и не защищает — "
                + "это тоже результат (§3.2).\n\n"
                : String.format(Locale.ROOT, "Стоп сработал в %d случаях; наблюдаемая σ ниже σ без стопа "
                        + "на %.1f п.п. — это и есть цена стопа в терминах сайзинга: "
                        + "размер, посчитанный по усечённой σ, завышен относительно риска без стопа.%n%n",
                stopped, (sdNoStop - sd) * 100));

        // --- §3.3 профиль проданного опциона ---
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        int tail = Math.max(1, x.length / 20);
        double best5 = 0;
        double worst5 = 0;
        for (int i = 0; i < tail; i++) {
            worst5 += sorted[i];
            best5 += sorted[sorted.length - 1 - i];
        }
        double total = KellySizing.mean(x) * x.length;
        double withoutBest = (total - best5) / (x.length - tail);
        boolean soldOption = worst5 / total < -0.5 || KellySizing.skewness(x) < -0.5;
        sb.append("## Профиль распределения (§3.3)\n\n");
        sb.append(String.format(Locale.ROOT,
                "| Метрика | Значение |%n|---|---|%n"
                        + "| Среднее / медиана | %+.2f%% / %+.2f%% |%n"
                        + "| Квартили | %+.2f%% … %+.2f%% |%n"
                        + "| Асимметрия / эксцесс | %.2f / %.2f |%n"
                        + "| Вклад лучших 5%% событий в суммарный P&L | %.0f%% |%n"
                        + "| Вклад худших 5%% событий | %.0f%% |%n"
                        + "| Среднее после удаления лучших 5%% | %+.2f%% |%n%n",
                mean * 100, KellySizing.quantile(sorted, 0.5) * 100,
                KellySizing.quantile(sorted, 0.25) * 100, KellySizing.quantile(sorted, 0.75) * 100,
                KellySizing.skewness(x), KellySizing.excessKurtosis(x),
                100 * best5 / total, 100 * worst5 / total, withoutBest * 100));
        sb.append(soldOption
                ? "**Профиль похож на проданный опцион:** результат держится на частых мелких выигрышах "
                + "против редких крупных потерь. Средние к решению о сайзинге не применяются, Kelly в "
                + "стандартной форме неприменим — решение по хвостовым метрикам (§6.3).\n\n"
                : "Профиля проданного опциона не видно: убыток не сосредоточен в редких крупных потерях "
                + "сильнее, чем прибыль — в редких крупных выигрышах.\n\n");

        // --- П3: разрез по режиму и остаточный контроль «событие или бета» ---
        double[] inBull = withStop.outcomes().stream().filter(S5Outcomes.Outcome::bullRegime)
                .mapToDouble(S5Outcomes.Outcome::outcome).toArray();
        double[] inBear = withStop.outcomes().stream().filter(o -> !o.bullRegime())
                .mapToDouble(S5Outcomes.Outcome::outcome).toArray();
        double[] btcMoves = withStop.outcomes().stream()
                .mapToDouble(S5Outcomes.Outcome::btcMove).toArray();
        double beta = KellySizing.beta(btcMoves, x);
        double[] residual = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            residual[i] = x[i] - beta * btcMoves[i];
        }
        double residualKelly = KellySizing.kellyNumeric(residual);
        double[] residualBoot = KellySizing.bootstrapKelly(residual, cfg.bootstrap(), cfg.seed());
        double residualP5 = KellySizing.quantile(residualBoot, 0.05);

        sb.append("## Событие или бета: разрез по режиму и остаточный контроль (§3.1, П3 док. 71)\n\n");
        sb.append("Шорт-стратегия, у которой эффект растёт во второй половине выборки, обязана быть "
                + "проверена на бету: тот же профиль даёт медвежий рынок. Правило 15 корпуса требует "
                + "остаточную версию сигнала как контрольную группу.\n\n");
        sb.append("| Разрез | Событий | Среднее исхода | σ | t-статистика |\n|---|---|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| Режим BULL по SMA200 на входе | %d | %+.2f%% | %.2f%% | %.2f |%n",
                inBull.length, KellySizing.mean(inBull) * 100,
                Math.sqrt(KellySizing.variance(inBull)) * 100, tStat(inBull)));
        sb.append(String.format(Locale.ROOT, "| Режим BEAR | %d | %+.2f%% | %.2f%% | %.2f |%n",
                inBear.length, KellySizing.mean(inBear) * 100,
                Math.sqrt(KellySizing.variance(inBear)) * 100, tStat(inBear)));
        sb.append(String.format(Locale.ROOT, "| Все события | %d | %+.2f%% | %.2f%% | %.2f |%n%n",
                x.length, mean * 100, sd * 100, tStat(x)));
        sb.append(String.format(Locale.ROOT,
                "**Остаточный контроль.** Регрессия исхода события на ход BTC за то же окно даёт "
                        + "β = **%.2f** (для шорта отрицательная β означает, что доход приходит от падения "
                        + "рынка, а не от события). Остаток `исход − β·ход BTC`: среднее **%+.2f%%** против "
                        + "%+.2f%% у сырого исхода, численный Kelly на остатке **%.1f%%**, 5-й процентиль "
                        + "бутстрапа остатка **%.1f%%**.%n%n",
                beta, KellySizing.mean(residual) * 100, mean * 100,
                residualKelly * 100, residualP5 * 100));
        sb.append(residualP5 > 0
                ? "**Эффект переживает вычитание беты**: на остатке нижняя граница остаётся выше нуля, "
                + "то есть событие даёт что-то сверх движения рынка.\n\n"
                : "**Эффект НЕ переживает вычитание беты**: на остатке нижняя граница уходит к нулю или "
                + "ниже — то есть измеренная премия неотличима от короткой экспозиции к рынку. Это тот "
                + "же исход, который закрыл S2/S9, и он важнее любого вывода о размере.\n\n");

        // --- §4 оценки размера ---
        sb.append("## Оценки размера (§4)\n\n");
        sb.append("| Метод | f | Комментарий |\n|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| Точечный Kelly (численный) | **%.1f%%** | база для сравнения, "
                + "не ответ: подстановка точечной μ̂ завышает размер |%n", fPoint * 100));
        sb.append(String.format(Locale.ROOT, "| Непрерывная формула μ/σ² | %.1f%% | сверка порядка величины |%n",
                fContinuous * 100));
        sb.append(String.format(Locale.ROOT, "| Медиана бутстрапа (%d реплик) | %.1f%% | сама смещена вверх |%n",
                boot.length, fMedian * 100));
        sb.append(String.format(Locale.ROOT, "| 25-й процентиль бутстрапа | %.1f%% | |%n", fP25 * 100));
        sb.append(String.format(Locale.ROOT, "| **5-й процентиль бутстрапа** | **%.1f%%** | "
                + "**решение принимается по нижней границе (§0 п.3)** |%n", fP5 * 100));
        sb.append(String.format(Locale.ROOT, "| Поправка на отбор (N=%d гипотез) | %.1f%% | "
                        + "μ̂ − SE·√(2 ln N), конвенция дефлирования проекта |%n",
                cfg.testedHypotheses(), fSelection * 100));
        for (Map.Entry<Double, Double> e : shrinkGrid.entrySet()) {
            sb.append(String.format(Locale.ROOT, "| Усадка к нулю, сила приора s=%.1f | %.1f%% | |%n",
                    e.getKey(), e.getValue() * 100));
        }
        for (double k : new double[]{1, 0.5, 0.25}) {
            sb.append(String.format(Locale.ROOT, "| Фракционный Kelly k=%.2f от точечного | %.1f%% | "
                    + "эвристика признанной практики, не вывод |%n", k, k * fPoint * 100));
        }
        sb.append(String.format(Locale.ROOT, "| Текущий лимит проекта | %.1f%% | док. 02 v2 |%n%n",
                cfg.currentLimit() * 100));

        // --- точка остановки §9 этап 4 ---
        sb.append("### Точка остановки (§9 этап 4): отличен ли 5-й процентиль f* от нуля?\n\n");
        sb.append(fP5 > 0
                ? String.format(Locale.ROOT, "Да: 5-й процентиль f* = %.1f%% > 0. Вопрос остаётся вопросом "
                + "о размере.%n%n", fP5 * 100)
                : String.format(Locale.ROOT, "**Нет: 5-й процентиль f* = %.1f%% ≤ 0.** При честном учёте "
                + "ошибки оценки эффект неотличим от нуля, и вопрос переходит из «сколько ставить» в "
                + "**«есть ли что ставить»** — это более важный вопрос, и он к этому стенду не относится "
                + "(§6.3).%n%n", fP5 * 100));

        // --- §4.5 одновременные ---
        sb.append("## Одновременные события (§4.5)\n\n");
        sb.append(String.format(Locale.ROOT,
                "Корреляция исходов внутри пересекающихся окон: **%.2f** (по %d парам). "
                        + "Оптимальный **суммарный** размер по группе из %d одновременных: **%.1f%%** "
                        + "против %d × %.1f%% = %.1f%% при независимом сайзинге → %s%n%n",
                pairCorrelation, pairGroups, cfg.maxConcurrent(), jointTotal * 100,
                cfg.maxConcurrent(), fPoint * 100, cfg.maxConcurrent() * fPoint * 100,
                jointTotal < cfg.maxConcurrent() * fPoint
                        ? "**правило «≤ " + cfg.maxConcurrent() + " события» завышает суммарную экспозицию**."
                        : "правило не завышает суммарную экспозицию."));
        sb.append(String.format(Locale.ROOT,
                "Фактическая кластеризация потока: **максимум %.0f** пересекающихся событий, в среднем %.1f. "
                        + "Текущее правило «≤ %d одновременных» — это не описание потока, а отсечение: "
                        + "остальные события пропускаются.%n%n"
                        + "**Потолок из портфельного лимита просадки.** Если все открытые позиции "
                        + "одновременно упрутся в стоп (−%.0f%% с проскальзыванием), суммарная потеря не "
                        + "должна превышать %.0f%%: отсюда размер позиции ≤ **%.1f%%** при наблюдённом "
                        + "максимуме одновременных и ≤ **%.1f%%** при текущем правиле «≤ %d». Это "
                        + "ограничение **сильнее** любой Kelly-оценки выше, и именно оно задаёт рабочий "
                        + "размер.%n%n",
                observedMaxConcurrent, observedMeanConcurrent, cfg.maxConcurrent(),
                (cfg.stop() + cfg.slippage()) * 100, cfg.portfolioDdLimit() * 100,
                KellySizing.drawdownCap(cfg.portfolioDdLimit(), observedMaxConcurrent,
                        cfg.stop() + cfg.slippage()) * 100,
                KellySizing.drawdownCap(cfg.portfolioDdLimit(), cfg.maxConcurrent(),
                        cfg.stop() + cfg.slippage()) * 100,
                cfg.maxConcurrent()));

        // --- §5.1 разделение по времени ---
        sb.append("## Разделение по времени — главный тест (§5.1)\n\n");
        sb.append(String.format(Locale.ROOT, "Первая половина: %d событий, среднее %+.2f%%. "
                        + "Вторая половина: %d событий, среднее %+.2f%%.%n%n",
                early.length, KellySizing.mean(early) * 100, late.length, KellySizing.mean(late) * 100));
        sb.append("| f оценён на первой половине | значение | капитал на второй | max просадка | "
                + "событий до восстановления | доля траекторий с DD>30% | с DD>50% |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        Map<String, double[]> transfer = new LinkedHashMap<>();
        boolean negativeEstimate = false;
        for (Map.Entry<String, Double> e : earlyEstimates.entrySet()) {
            double f = e.getValue();
            negativeEstimate |= f < 0;
            double[] walk = KellySim.walkThrough(late, f);
            KellySim.SizeResult mc30 = KellySim.simulate(late, f, cfg.monteCarloPaths(), 0.30, cfg.seed());
            KellySim.SizeResult mc50 = KellySim.simulate(late, f, cfg.monteCarloPaths(), 0.50, cfg.seed());
            transfer.put(e.getKey(), new double[]{f, walk[0], walk[1], mc30.pDeep(), mc50.pDeep()});
            sb.append(String.format(Locale.ROOT, "| %s | %.1f%% | ×%.2f | %.1f%% | %s | %.0f%% | %.0f%% |%n",
                    e.getKey(), f * 100, walk[0], walk[1] * 100,
                    Double.isNaN(walk[2]) ? "не восстановился" : String.format("%.0f", walk[2]),
                    mc30.pDeep() * 100, mc50.pDeep() * 100));
        }
        sb.append('\n');
        if (negativeEstimate) {
            sb.append("**Отрицательное `f` в таблице означает «ставить против стратегии».** Это не режим "
                    + "работы S5, и читать такую строку надо как «честная оценка на первой половине не "
                    + "положительна → размер ноль». Строки с отрицательным f показаны как есть, потому что "
                    + "они и есть содержание теста: на первой половине выборки нижняя граница уходит ниже "
                    + "нуля.\n\n");
        }

        // --- §5.2 сетка размеров ---
        sb.append("## Сетка размеров и кривая темпа роста (§5.2)\n\n");
        sb.append("Главная таблица задания: плато слева от оптимума и обрыв справа.\n\n");
        sb.append("| f | темп роста E[ln(1+f·X)] | медиана капитала | 5-й проц. | 95-й проц. | "
                + "медиана max DD | 95-й проц. DD | P(DD > " + pct(cfg.ruinThreshold()) + ") |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (KellySim.SizeResult r : sizeResults.stream()
                .sorted((a, b) -> Double.compare(a.f(), b.f())).toList()) {
            sb.append(String.format(Locale.ROOT, "| %.1f%% | %+.5f | ×%.2f | ×%.2f | ×%.2f | %.1f%% | %.1f%% | %.0f%% |%n",
                    r.f() * 100, r.growth(), r.medianWealth(), r.p5Wealth(), r.p95Wealth(),
                    r.medianMaxDd() * 100, r.p95MaxDd() * 100, r.pDeep() * 100));
        }
        double zeroGrowth = zeroGrowthSize(x, fPoint);
        sb.append(String.format(Locale.ROOT, "%nМаксимум темпа роста — при f = %.1f%%; темп роста обращается "
                + "в ноль при f ≈ %.1f%% (за этой точкой ставка уменьшает капитал на длинной дистанции).%n%n",
                fPoint * 100, zeroGrowth * 100));

        // --- чувствительность к стопу ---
        sb.append("## Чувствительность к стопу (§3.2)\n\n");
        sb.append("Док. 02 v2 называет стоп +8%, измеренная модель док. 52 и live — 30%. ")
                .append("Расхождение разрешается сеткой, а не выбором удобного значения.\n\n");
        sb.append("| Стоп | Сработал | Среднее исхода | σ | Точечный Kelly | 5-й проц. бутстрапа | "
                + "потолок из лимита просадки |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (double stop : cfg.stopGrid()) {
            S5Outcomes.Dataset d = outcomes.build(cfg, stop);
            double[] v = d.values();
            double[] b = KellySizing.bootstrapKelly(v, Math.max(cfg.bootstrap() / 10, 100), cfg.seed());
            double cap = KellySizing.drawdownCap(cfg.portfolioDdLimit(), observedMaxConcurrent,
                    stop + cfg.slippage());
            sb.append(String.format(Locale.ROOT,
                    "| %.0f%% | %d | %+.2f%% | %.2f%% | %.1f%% | %.1f%% | %.1f%% |%n",
                    stop * 100, d.outcomes().stream().filter(S5Outcomes.Outcome::stopHit).count(),
                    KellySizing.mean(v) * 100, Math.sqrt(KellySizing.variance(v)) * 100,
                    KellySizing.kellyNumeric(v) * 100, KellySizing.quantile(b, 0.05) * 100, cap * 100));
        }
        sb.append(String.format(Locale.ROOT,
                "%n**Потолок из лимита просадки зависит от стопа линейно** и потому не является "
                        + "самостоятельным результатом: при стопе %s он равен %.1f%%, при стопе 8%% из "
                        + "док. 02 v2 — %.1f%%. Во втором случае ограничение перестаёт связывать вблизи "
                        + "текущего лимита %s, и «сколько ставить» снова упирается в оценку эффекта, а не "
                        + "в арифметику портфеля (П2 док. 71).%n%n",
                pct(cfg.stop()),
                KellySizing.drawdownCap(cfg.portfolioDdLimit(), observedMaxConcurrent,
                        cfg.stop() + cfg.slippage()) * 100,
                KellySizing.drawdownCap(cfg.portfolioDdLimit(), observedMaxConcurrent,
                        0.08 + cfg.slippage()) * 100,
                pct(cfg.currentLimit())));

        // --- вердикт ---
        double shrunk1 = shrinkGrid.getOrDefault(1.0, KellySizing.shrunkKelly(x, 1));
        double ddCap = KellySizing.drawdownCap(cfg.portfolioDdLimit(), observedMaxConcurrent,
                cfg.stop() + cfg.slippage());
        sb.append("## Вердикт (§6.2)\n\n");
        sb.append(String.format(Locale.ROOT, "1. **Точечный Kelly** даёт %.1f%%; **5-й процентиль бутстрапа** — "
                + "%.1f%%.%n", fPoint * 100, fP5 * 100));
        sb.append(String.format(Locale.ROOT, "2. **Отличается ли честный размер от текущих %.0f%%:** "
                        + "нижняя граница %.1f%%, усадка (s=1) %.1f%%, поправка на отбор %.1f%% → %s%n",
                cfg.currentLimit() * 100, fP5 * 100, shrunk1 * 100, fSelection * 100,
                verdictOnLimit(fP5, shrunk1, fSelection, cfg.currentLimit())));
        sb.append(String.format(Locale.ROOT, "3. **Отличен ли 5-й процентиль f* от нуля:** %s.%n",
                fP5 > 0 ? "да" : "**нет — вопрос переходит в «есть ли эффект»**"));
        double[] pointTransfer = transfer.get("точечный");
        double[] limitTransfer = transfer.get("текущий лимит " + pct(cfg.currentLimit()));
        sb.append(String.format(Locale.ROOT,
                "4. **Разделение по времени:** размер, оценённый точечным Kelly на первой половине "
                        + "(%.1f%%), на второй даёт капитал ×%.2f при максимальной просадке %.1f%% и доле "
                        + "траекторий с просадкой > 50%% = %.0f%% → %s. Для сравнения, текущий лимит "
                        + "%.0f%% даёт ×%.2f при просадке %.1f%%.%n",
                pointTransfer[0] * 100, pointTransfer[1], pointTransfer[2] * 100, pointTransfer[4] * 100,
                pointTransfer[4] > 0.5 || pointTransfer[2] < -0.5
                        ? "**оценка непереносима: размер брать по нижней границе и ниже (kill-критерий §6.3)**"
                        : "перенос выдерживает порог просадки",
                cfg.currentLimit() * 100, limitTransfer[1], limitTransfer[2] * 100));
        sb.append(String.format(Locale.ROOT, "5. **Одновременные события:** корреляция %.2f, оптимальный "
                        + "суммарный %.1f%% против %.1f%% при независимом сайзинге.%n",
                pairCorrelation, jointTotal * 100, cfg.maxConcurrent() * fPoint * 100));
        sb.append(String.format(Locale.ROOT, "6. **Профиль проданного опциона:** %s.%n",
                soldOption ? "**да**" : "не обнаружен"));
        KellySim.SizeResult atLimit = KellySim.simulate(x, cfg.currentLimit(), cfg.monteCarloPaths(), 0.50,
                cfg.seed());
        sb.append(String.format(Locale.ROOT, "7. **Вероятность просадки > 50%% при текущем лимите %.0f%% "
                        + "на горизонте %d событий:** %.0f%%.%n%n",
                cfg.currentLimit() * 100, x.length, atLimit.pDeep() * 100));
        sb.append(String.format(Locale.ROOT,
                "**Что связывает размер на практике.** Kelly на одиночном событии даёт большие числа "
                        + "(%.0f%%) просто потому, что дисперсия одного события мала по сравнению со "
                        + "средним; но события идут пачками — максимум %.0f одновременных. Ограничение "
                        + "«одновременный стоп по всем открытым позициям не должен стоить дороже %.0f%% "
                        + "капитала» даёт **%.1f%% на позицию** — это %s текущего лимита %.0f%%. "
                        + "Именно портфельное ограничение, а не Kelly, задаёт рабочий размер.%n%n",
                fPoint * 100, observedMaxConcurrent, cfg.portfolioDdLimit() * 100, ddCap * 100,
                ddCap < cfg.currentLimit() ? "**ниже**" : "выше",
                cfg.currentLimit() * 100));

        // --- kill-критерии ---
        sb.append("### Kill-критерии и пороги действия (§6.3)\n\n");
        sb.append("| Условие | Сработало | Что означает срабатывание |\n|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| 5-й процентиль f* ≤ 0 | %s | эффект неотличим от нуля при "
                + "честном учёте |%n", fP5 <= 0 ? "**ДА**" : "нет"));
        sb.append(String.format(Locale.ROOT, "| Убыток сосредоточен в редких крупных потерях | %s | "
                + "Kelly в стандартной форме неприменим |%n", soldOption ? "**ДА**" : "нет"));
        sb.append(String.format(Locale.ROOT, "| Рекомендуемый размер выше текущих %.0f%% | %s | "
                        + "перепроверить три смещения §0 перед принятием; повышение лимита — отдельное "
                        + "решение, а не следствие расчёта |%n",
                cfg.currentLimit() * 100, fP5 > cfg.currentLimit() ? "**ДА**" : "нет"));
        sb.append(String.format(Locale.ROOT, "| Оптимальный суммарный по %d событиям меньше %d × f | %s | "
                        + "текущее правило завышает суммарную экспозицию |%n",
                cfg.maxConcurrent(), cfg.maxConcurrent(),
                jointTotal < cfg.maxConcurrent() * fPoint ? "**ДА**" : "нет"));

        sb.append("\n## Оговорки (§10)\n\n")
                .append(String.format(Locale.ROOT,
                        "- **%.0f событий в год.** Утверждения об асимптотической оптимальности Kelly на "
                                + "этом горизонте некорректны и в выводах не используются.%n",
                        x.length / Math.max(years, 0.1)))
                .append("- **μ оценена на той же выборке, где эффект найден.** Даже после поправок оценка "
                        + "смещена вверх: направление смещения известно, величина — нет.\n")
                .append(String.format(Locale.ROOT,
                        "- **Стоп %s усекает распределение**, поэтому наблюдаемая σ занижена относительно "
                                + "риска без стопа (σ %.2f%% против %.2f%%).%n",
                        pct(cfg.stop()), sd * 100, sdNoStop * 100))
                .append("- **Расписания разлоков не point-in-time** (архивных снимков нет, док. 52 §3.5) — "
                        + "премия остаётся верхней границей, и размер, выведенный из неё, тоже.\n")
                .append("- **Результат «ставить меньше» — полноценный результат.** Он не уменьшает ценность "
                        + "S5, он уточняет цену её риска.\n");

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записан отчёт " + out, e);
        }
    }

    /** Размер, при котором темп роста обращается в ноль (правый край плато). */
    private static double zeroGrowthSize(double[] x, double fPoint) {
        double f = Math.max(fPoint, 1e-6);
        for (int i = 0; i < 1000; i++) {
            double candidate = f * (1 + 0.01 * i);
            if (KellySizing.growthRate(x, candidate) <= 0) {
                return candidate;
            }
        }
        return Double.NaN;
    }

    private static String verdictOnLimit(double fP5, double shrunk, double selection, double limit) {
        double honest = Math.min(Math.min(fP5, shrunk), selection);
        if (honest <= 0) {
            return "**честная оценка не положительна — ставить по ней нечего, вопрос о размере вторичен**";
        }
        double ratio = honest / limit;
        if (ratio < 0.5) {
            return String.format(Locale.ROOT, "**честный размер втрое-вдвое меньше текущего лимита "
                    + "(×%.2f от него)**", ratio);
        }
        if (ratio > 2) {
            return String.format(Locale.ROOT, "честная оценка выше лимита (×%.2f) — **сначала перепроверить "
                    + "три смещения §0**, повышение лимита не следует из расчёта", ratio);
        }
        return String.format(Locale.ROOT, "того же порядка, что текущий лимит (×%.2f) — практически "
                + "значимого расхождения нет", ratio);
    }

    /** t-статистика среднего: μ̂/SE(μ̂). */
    private static double tStat(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double se = Math.sqrt(KellySizing.variance(v) / v.length);
        return se > 0 ? KellySizing.mean(v) / se : Double.NaN;
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) {
            m = Math.min(m, x);
        }
        return m;
    }

    private static double round(double f) {
        return Math.round(f * 10000) / 10000.0;
    }

    /**
     * Проценты без потери значащих цифр: с {@code %.0f} порог funding 1.5%
     * печатался как «2%», и читатель отчёта справедливо решил, что фильтр другой
     * (П2 док. 71). Дробная часть показывается, когда она есть.
     */
    private static String pct(double v) {
        double percent = v * 100;
        return Math.abs(percent - Math.round(percent)) < 1e-9
                ? String.format(Locale.ROOT, "%.0f%%", percent)
                : String.format(Locale.ROOT, "%.1f%%", percent);
    }
}
