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
    private static final long[] HORIZONS_MS = {10_000, 60_000, 300_000, 3_600_000};

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

    /**
     * Ступень лестницы отступа: сама стратегия и ДВА её нулевых распределения.
     *
     * Второе появилось по док. 127 §9: у первого контроля случайно и расстояние
     * тоже, поэтому проигрыш ему смешан с лестницей отступа. Их надо читать
     * рядом, а не вместо друг друга.
     */
    private record Rung(double offset, SimEngine.Result result, Null nulls, Null anchorNulls) {
    }

    /** Ступень лестницы скоса. Нулевое распределение тут не нужно: скос — не выбор цен. */
    private record SkewRung(double skew, SimEngine.Result result) {
    }

    /** Ступень лестницы потолка инвентаря — вопрос ёмкости, а не качества котировок. */
    private record CapRung(double factor, double cap, SimEngine.Result result) {
    }

    /** Ступень лестницы задержки котирования. */
    private record LatencyRung(int seconds, SimEngine.Result result) {
    }

    private record DriftRung(double beta, SimEngine.Result result) {
    }

    private record RatioRung(double ratio, SimEngine.Result result) {
    }

    /** Ступень сетки: своя политика, поэтому нужен и сам котировщик — за книгой лотов. */
    private record GridRung(double margin, double widening, int lots, double cap,
                            GridQuoter quoter, SimEngine.Result result) {
    }

    /** Клетка кросса: два механизма торможения набора одновременно. */
    private record CrossCell(double beta, double eta, SimEngine.Result result) {
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
                cfg.simSkewK(), cfg.simSkewTarget(), cfg.simDriftBeta(), cfg.simBuySizeRatio(),
                cfg.simDriftWindowMs(), cfg.simSizeShapeEta(), cfg.simDriftGateEr(),
                cfg.simErWindowMs(), cfg.simErSampleMs(), cfg.simStopDrawdownPct(),
                Quoter.Sticky.OFF, Quoter.Frozen.OFF, Quoter.Hedge.OFF, cfg.simStopCoolOffMs(),
                cfg.simRequoteThreshold(), steps[1]);

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
        // Лестница потолка инвентаря — вопрос ЁМКОСТИ: базисные пункты превращает
        // в деньги именно потолок (док. 84 §3). Показывает механическое насыщение;
        // рыночного влияния модель не видит, поэтому это верхняя граница.
        List<CapRung> capLadder = new ArrayList<>();
        for (double factor : capFactors()) {
            Quoter.Params params = withCap(base, base.inventoryCap() * factor);
            SimEngine.Result result = factor == 1.0 ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            capLadder.add(new CapRung(factor, params.inventoryCap(), result));
            if (factor != 1.0) {
                runs.add(new Run(String.format("потолок инвентаря ×%.1f", factor), result,
                        cfg.simMakerFee(), base.offset(), params.inventoryCap()));
            }
        }
        // Лестница скоса. Скос сдвигает обе цены вниз по мере роста инвентаря, делая
        // аск агрессивнее; измеренная цена этой страховки лежит НЕ в захвате спреда,
        // а в markout (док. 79 §7), поэтому ступени сравниваются по краю.
        List<SkewRung> skewLadder = new ArrayList<>();
        for (double skew : skews(base)) {
            Quoter.Params params = withSkewK(base, skew);
            SimEngine.Result result = skew == base.skewK() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            skewLadder.add(new SkewRung(skew, result));
            if (skew != base.skewK()) {
                runs.add(new Run(String.format("скос %.4f%%", skew * 100), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        // Лестница веса дрейфа и лестница асимметрии набора (док. 98 §3, §6).
        // Обе двигают одно и то же — СКОРОСТЬ НАБОРА инвентаря, — поэтому стоят
        // рядом: если грубая асимметрия даёт то же, что дрейф, брать надо её.
        List<DriftRung> driftLadder = new ArrayList<>();
        for (double beta : cfg.simDriftBetaLadder()) {
            Quoter.Params params = withDriftBeta(base, beta);
            SimEngine.Result result = beta == base.driftBeta() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            driftLadder.add(new DriftRung(beta, result));
            if (beta != base.driftBeta()) {
                runs.add(new Run(String.format("дрейф-скос β=%.0f", beta), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }
        List<RatioRung> ratioLadder = new ArrayList<>();
        for (double ratio : cfg.simBuyRatioLadder()) {
            Quoter.Params params = withBuyRatio(base, ratio);
            SimEngine.Result result = ratio == base.buySizeRatio() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            ratioLadder.add(new RatioRung(ratio, result));
            if (ratio != base.buySizeRatio()) {
                runs.add(new Run(String.format("набор ×%.2f", ratio), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        List<RatioRung> shapeLadder = new ArrayList<>();
        for (double eta : cfg.simShapeLadder()) {
            Quoter.Params params = withShapeEta(base, eta);
            SimEngine.Result result = eta == base.sizeShapeEta() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            shapeLadder.add(new RatioRung(eta, result));
            if (eta != base.sizeShapeEta()) {
                runs.add(new Run(String.format("шейп η=%.1f", eta), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        List<RatioRung> stopLadder = new ArrayList<>();
        for (double pct : cfg.simStopLadder()) {
            Quoter.Params params = base.withStopDrawdownPct(pct);
            SimEngine.Result result = pct == base.stopDrawdownPct() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            stopLadder.add(new RatioRung(pct, result));
            if (pct != base.stopDrawdownPct()) {
                runs.add(new Run(String.format("стоп %.2f%%", pct), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        // Лестница ЦЕЛИ скоса. Отдельно от лестницы его силы: сила задаёт, как
        // резко контроллер тянет инвентарь, цель — КУДА он его тянет. При цели 0
        // это пустой счёт, а на споте там нельзя выставить аск, и стратегия
        // становится односторонней (док. 93 §4).
        List<RatioRung> targetLadder = new ArrayList<>();
        for (double target : cfg.simSkewTargetLadder()) {
            Quoter.Params params = base.withSkewTarget(target);
            SimEngine.Result result = target == base.skewTarget() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            targetLadder.add(new RatioRung(target, result));
            if (target != base.skewTarget()) {
                runs.add(new Run(String.format("цель скоса %.2f", target), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }
        // Та же лестница, но с ПОЛОМ ПО СЕБЕСТОИМОСТИ. Порознь эти две правки
        // отвечают на разные вопросы, а вместе — на один: цель говорит «держи
        // столько-то», пол не даёт сбросить это в убыток. Без пола ненулевая цель
        // означала «держи и продавай дешевле, чем купил», и мерилось именно оно
        // (док. 111 §7).
        List<RatioRung> targetFloored = new ArrayList<>();
        for (double target : cfg.simSkewTargetLadder()) {
            Quoter.Params params = base.withSkewTarget(target);
            QuotePolicy policy = new CostFloorPolicy(new Quoter(params), 0.0, base.quoteStep());
            targetFloored.add(new RatioRung(target,
                    new SimEngine(params, limits, cfg.simMakerFee(), policy).run(data.windows())));
        }

        // Хедж шортом на перпе (док. 122). Единственный механизм, который может
        // сделать падение БЕЗУБЫТОЧНЫМ, а не отложенным: всё остальное, что
        // проверялось, лишь переносило убыток во времени.
        List<RatioRung> hedgeLadder = new ArrayList<>();
        for (long ms : cfg.simHedgeRebalanceLadder()) {
            // Шаг контракта — ПО ПАРЕ: у каждого перпа он свой, и BTC-шаг,
            // подставленный SOL, делает хедж в тысячи раз тоньше настоящего.
            Quoter.Params p = base.withHedge(cfg.simHedge(symbol).withRebalance(ms));
            hedgeLadder.add(new RatioRung(ms,
                    new SimEngine(p, limits, cfg.simMakerFee()).run(data.windows())));
        }

        // Бид от ЦЕНЫ ВХОДА с поводком (док. 119). Отвечает на вопрос, который
        // растущий шаг обошёл: заявка на 2% ниже справедливой не исполняется
        // никогда, потому что для этого нужен ВЫНОС такой глубины, а не приход
        // цены. Поводок задаёт всё семейство: равен отступу — прежняя привязка к
        // рынку, бесконечен — чистая сетка.
        List<RatioRung> leashLadder = new ArrayList<>();
        for (double leash : cfg.simAnchorLeashLadder()) {
            QuotePolicy floored = new CostFloorPolicy(new Quoter(base), 0.0, base.quoteStep());
            QuotePolicy policy = new AnchoredBidPolicy(floored, base.offset(),
                    cfg.simGridWidening(), base.offset(), leash, base.size(),
                    base.inventoryCap(), base.quoteStep());
            leashLadder.add(new RatioRung(leash,
                    new SimEngine(base, limits, cfg.simMakerFee(), policy).run(data.windows())));
        }

        // Растущий шаг покупок (док. 117). Отвечает на вопрос ЁМКОСТИ: сколько
        // процентов падения конструкция отрабатывает, прежде чем упрётся в потолок.
        // Ставится ВМЕСТЕ с полом по себестоимости — это и есть кандидат-конструкция
        // второго бота, поэтому мерить их надо в паре, а не по отдельности.
        List<RatioRung> wideStep = new ArrayList<>();
        for (double eta : cfg.simWideningLadder()) {
            QuotePolicy inner = new CostFloorPolicy(new Quoter(base), 0.0, base.quoteStep());
            QuotePolicy policy = new WideningBidPolicy(inner, base.offset(), eta,
                    cfg.simWideningMaxStep(), base.size(), base.inventoryCap(),
                    base.quoteStep());
            wideStep.add(new RatioRung(eta,
                    new SimEngine(base, limits, cfg.simMakerFee(), policy).run(data.windows())));
        }

        // Пол по себестоимости (док. 116). Точечная правка нынешней конструкции:
        // всё как в базовом прогоне, но аск не опускается ниже средней цены входа.
        // Вмешательство происходит ТОЛЬКО там, где старое правило велело продать
        // в минус, поэтому на растущем окне ступени обязаны почти совпасть с базой.
        List<RatioRung> costFloor = new ArrayList<>();
        for (double margin : cfg.simCostFloorLadder()) {
            QuotePolicy floored = new CostFloorPolicy(new Quoter(base), margin, base.quoteStep());
            costFloor.add(new RatioRung(margin,
                    new SimEngine(base, limits, cfg.simMakerFee(), floored).run(data.windows())));
        }

        // Сетка с якорем на себестоимости (док. 115). Это не настройка котировщика,
        // а ДРУГАЯ политика: аск привязан к цене покупки лота, а не к рынку.
        // Три лестницы разводят три разных вопроса: сколько просить сверх входа,
        // как быстро тормозить набор и сколько капитала нужно, чтобы дожить.
        List<GridRung> gridMargin = new ArrayList<>();
        for (double margin : cfg.simGridMarginLadder()) {
            gridMargin.add(gridRun(base, limits, data, margin, cfg.simGridWidening(),
                    defaultGridLots(base)));
        }
        List<GridRung> gridWidening = new ArrayList<>();
        for (double widening : cfg.simGridWideningLadder()) {
            gridWidening.add(gridRun(base, limits, data, cfg.simGridMargin(), widening,
                    defaultGridLots(base)));
        }
        List<GridRung> gridLots = new ArrayList<>();
        for (int lots : cfg.simGridLotsLadder()) {
            gridLots.add(gridRun(base, limits, data, cfg.simGridMargin(),
                    cfg.simGridWidening(), lots));
        }

        // Замороженная пара (док. 114). Заявки не двигаются ВООБЩЕ, пока одна не
        // исполнится; после исполнения пауза, затем обе стороны выставляются заново
        // и пара замерзает снова. Две лестницы разводят два разных вопроса:
        // сколько ждать после исполнения и надо ли вообще спасать зависшую пару.
        List<RatioRung> frozenCool = new ArrayList<>();
        for (long cool : cfg.simFrozenCoolOffLadder()) {
            Quoter.Params p = base.withFrozen(new Quoter.Frozen(true, cool,
                    cfg.simFrozen().maxAgeMs()));
            frozenCool.add(new RatioRung(cool,
                    new SimEngine(p, limits, cfg.simMakerFee()).run(data.windows())));
        }
        List<RatioRung> frozenAge = new ArrayList<>();
        for (long age : cfg.simFrozenMaxAgeLadder()) {
            Quoter.Params p = base.withFrozen(new Quoter.Frozen(true,
                    cfg.simFrozen().coolOffMs(), age));
            frozenAge.add(new RatioRung(age,
                    new SimEngine(p, limits, cfg.simMakerFee()).run(data.windows())));
        }

        // Лестницы липкой котировки. Базовая точка — ВЫКЛЮЧЕННАЯ липкость, и это
        // приёмочное условие задания: она обязана дать в точности прежние числа.
        List<RatioRung> stickyOuter = new ArrayList<>();
        for (double outer : cfg.simStickyOuterLadder()) {
            Quoter.Params p = base.withSticky(cfg.simSticky().withOuter(outer));
            stickyOuter.add(new RatioRung(outer,
                    new SimEngine(p, limits, cfg.simMakerFee()).run(data.windows())));
        }
        List<RatioRung> stickyInner = new ArrayList<>();
        for (double inner : cfg.simStickyInnerLadder()) {
            Quoter.Params p = base.withSticky(cfg.simSticky().withInner(inner));
            stickyInner.add(new RatioRung(inner,
                    new SimEngine(p, limits, cfg.simMakerFee()).run(data.windows())));
        }
        List<java.util.Map.Entry<String, SimEngine.Result>> queueControl = new ArrayList<>();
        queueControl.add(java.util.Map.entry("база (переставляем каждый тик)", baseResult));
        double bestOuter = cfg.simStickyOuterLadder()[cfg.simStickyOuterLadder().length - 1];
        Quoter.Sticky stickyBest = cfg.simSticky().withOuter(bestOuter);
        queueControl.add(java.util.Map.entry(String.format("липкая outer=%.1f", bestOuter),
                new SimEngine(base.withSticky(stickyBest), limits, cfg.simMakerFee())
                        .run(data.windows())));
        queueControl.add(java.util.Map.entry("та же, очередь сброшена",
                new SimEngine(base.withSticky(stickyBest.withResetQueue(true)), limits,
                        cfg.simMakerFee()).run(data.windows())));

        // Кросс двух тормозов набора. Профили цены у них разные: η зависит только
        // от собственной позиции, β — от внешней величины с нулевым IC, поэтому
        // выбор между ними по одномерным лестницам делался вслепую (док. 103 §3).
        List<CrossCell> cross = new ArrayList<>();
        for (double beta : cfg.simCrossBeta()) {
            for (double eta : cfg.simCrossEta()) {
                Quoter.Params params = withShapeEta(withDriftBeta(base, beta), eta);
                SimEngine.Result result =
                        beta == base.driftBeta() && eta == base.sizeShapeEta() ? baseResult
                                : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
                cross.add(new CrossCell(beta, eta, result));
            }
        }

        // Лестница задержки котирования — цена отсутствия WebSocket (док. 86 §7).
        // Правка «захват против цены момента исполнения» создала механизм
        // устаревания заявки; здесь он прогоняется с разным периодом решений.
        List<LatencyRung> latencyLadder = new ArrayList<>();
        double windowSec = data.windowPeriodSec();
        for (int seconds : latencySeconds(windowSec)) {
            // Рунг переводится в окна по ФАКТИЧЕСКОМУ шагу данных.
            int periodWindows = Math.max(1, (int) Math.round(seconds / windowSec));
            SimEngine.Result result = periodWindows == 1 ? baseResult
                    : new SimEngine(base, limits, cfg.simMakerFee(), new Quoter(base), periodWindows)
                            .run(data.windows());
            latencyLadder.add(new LatencyRung(seconds, result));
            if (periodWindows != 1) {
                runs.add(new Run(String.format("задержка %d с", seconds), result,
                        cfg.simMakerFee(), base.offset(), base.inventoryCap()));
            }
        }

        // Контроль: случайные котировки (то же присутствие в книге, цены наугад)
        SimEngine.Result randomResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.random(base, cfg.simRandomSeed())).run(data.windows());
        runs.add(new Run("контроль: случайные котировки", randomResult, cfg.simMakerFee(),
                base.offset(), base.inventoryCap()));

        // Второй контроль (док. 127 §9): расстояние ±d и скос — как у стратегии,
        // случаен только ЦЕНТР. Отвечает на «помогает ли слежение за справедливой
        // ценой», не смешивая ответ с лестницей отступа.
        SimEngine.Result anchorResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.staleAnchor(base, cfg.simRandomSeed(), cfg.simControlAnchorWindows()))
                .run(data.windows());
        runs.add(new Run("контроль: случайный якорь, те же расстояния", anchorResult,
                cfg.simMakerFee(), base.offset(), base.inventoryCap()));

        // Лестница ГЛУБИНЫ ЯКОРЯ (док. 132 §1). Возражение: контроль со
        // случайным якорем меряет не «слежение», а устаревание, цена которого
        // уже известна из лестницы задержки — 2.86·√(t/5) б.п. Проверяется это
        // одним прогоном: если счёт контроля идёт по формуле, вопрос закрыт.
        List<RatioRung> anchorDepth = new ArrayList<>();
        for (int windows : cfg.simControlAnchorLadder()) {
            SimEngine.Result r = new SimEngine(base, limits, cfg.simMakerFee(),
                    QuotePolicy.staleAnchor(base, cfg.simRandomSeed(), windows)).run(data.windows());
            anchorDepth.add(new RatioRung(windows, r));
        }

        // Контроли C3 и C4 (док. 132 §1): оба на ТЕКУЩЕЙ справедливой цене,
        // то есть без устаревания вовсе. Только они отвечают на исходный вопрос
        // док. 127 §9 — добавляют ли что-то скос и корзинная справедливая цена.
        SimEngine.Result noSkewResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.noSkew(base)).run(data.windows());
        runs.add(new Run("контроль C3: без скоса, цена текущая", noSkewResult,
                cfg.simMakerFee(), base.offset(), base.inventoryCap()));
        SimEngine.Result ownBookResult = new SimEngine(base, limits, cfg.simMakerFee(),
                QuotePolicy.ownBookMid(base)).run(data.windows());
        runs.add(new Run("контроль C4: от середины своей книги, без скоса", ownBookResult,
                cfg.simMakerFee(), base.offset(), base.inventoryCap()));

        // Лестница отступа с контролем на КАЖДОЙ ступени (док. 75 §5). Раньше и
        // buy & hold, и случайные считались только при базовом d, а вердикт «kill-критерий
        // сработал» переносился на всю конструкцию. Между тем d двигает и число
        // исполнений, и знак markout — то есть ровно то, что этими критериями и меряется.
        List<Rung> ladder = new ArrayList<>();
        for (double offset : offsets(base)) {
            Quoter.Params params = withOffset(base, offset);
            SimEngine.Result result = offset == base.offset() ? baseResult
                    : new SimEngine(params, limits, cfg.simMakerFee()).run(data.windows());
            ladder.add(new Rung(offset, result,
                    nullDistribution(params, limits, data, result),
                    anchorNullDistribution(params, limits, data, result)));
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
                skewLadder, capLadder, latencyLadder, driftLadder, ratioLadder, shapeLadder,
                cross, stopLadder, targetLadder, targetFloored, hedgeLadder, leashLadder, wideStep, costFloor, gridMargin, gridWidening, gridLots,
                frozenCool, frozenAge, stickyOuter, stickyInner, queueControl,
                anchorDepth, noSkewResult, ownBookResult);
        write(out, markdown);
        log.info("{}: {} прогонов, базовый total={} (спред {} + инвентарь {}), исполнений {} → {}",
                symbol, runs.size(), round(baseResult.pnl().total(), 4),
                round(baseResult.pnl().spreadCapture(), 4), round(baseResult.pnl().inventoryPnl(), 4),
                baseResult.fills().size(), out);
    }

    /** Сколько лотов держит базовый потолок — точка отсчёта для лестницы капитала. */
    private static int defaultGridLots(Quoter.Params base) {
        return base.size() > 0 ? (int) Math.round(base.inventoryCap() / base.size()) : 5;
    }

    /**
     * Один прогон сетки. Потолок задаётся в ЛОТАХ, а не в монетах: вопрос ступени —
     * «на сколько докупок хватает капитала», и в лотах он читается прямо.
     */
    private GridRung gridRun(Quoter.Params base, ExecutionModel.Limits limits,
                             SimDataReader.Dataset data, double margin, double widening,
                             int lots) {
        double cap = base.size() * lots;
        GridQuoter grid = new GridQuoter(base.size(), margin, cfg.simGridBaseStep(),
                widening, cfg.simGridMaxStep(), cap, base.quoteStep());
        // Потолок передаётся и в Params: по нему движок считает «время с полным
        // инвентарём» и размер стопа, и разойтись эти два числа не должны.
        Quoter.Params params = base.withCap(cap).withOffset(cfg.simGridBaseStep());
        SimEngine.Result result = new SimEngine(params, limits, cfg.simMakerFee(), grid)
                .run(data.windows());
        return new GridRung(margin, widening, lots, cap, grid, result);
    }

    private static void appendTargetHeader(StringBuilder sb) {
        sb.append("| Цель | Исполнений | Захват, б.п. | Чистый край | **Край × оборот** "
                + "| Ср. инвентарь | **Время с нулевым** | Время с полным | Просадка "
                + "| **Total** | **Buy & hold** | **Альфа** | **При возврате** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    }

    private static void appendTargetRow(StringBuilder sb, RatioRung rung,
                                        Quoter.Params base, boolean markBase) {
        SimEngine.Result r = rung.result();
        double edge = netEdgeBp(r, 60_000);
        sb.append("| ").append(round(rung.ratio(), 2))
                .append(markBase && rung.ratio() == base.skewTarget() ? " (базовый)" : "")
                .append(" | ").append(r.fills().size())
                .append(" | ").append(round(captureBp(r), 2))
                .append(" | ").append(round(edge, 2))
                .append(" | **").append(round(edge * turnover(r) / 10_000, 1)).append("**")
                .append(" | ").append(round(r.avgInventory(), 4))
                .append(" | **").append(round(100.0 * r.windowsAtZero()
                        / Math.max(1, r.windows()), 1)).append("%**")
                .append(" | ").append(round(100.0 * r.windowsAtCap()
                        / Math.max(1, r.windows()), 1)).append("%")
                .append(" | ").append(round(r.maxDrawdown(), 1))
                .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                .append(" | **").append(round(r.pnl().total() - r.buyAndHoldPnl(), 1)).append("**")
                .append(" | **").append(round(r.pnlAtStart(), 1)).append("**")
                .append(" |\n");
    }

    /** Ёмкость падения: просадка цены в момент первого заполнения потолка. */
    private static String capacity(SimEngine.Result r) {
        return Double.isNaN(r.capAtDropPct()) ? "—" : round(r.capAtDropPct(), 2) + "%";
    }

    private static void appendGridHeader(StringBuilder sb, String first) {
        sb.append("| ").append(first).append(" | Исполнений | Покупок / продаж "
                + "| Захват, б.п. | Ср. инвентарь | Время с полным | **Просадка** "
                + "| Открытых лотов на конце | **Total** | **Buy & hold** "
                + "| **При возврате цены** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
    }

    private static void appendGridRow(StringBuilder sb, String label, GridRung rung) {
        SimEngine.Result r = rung.result();
        long buys = r.fills().stream().filter(f -> f.side() == Side.BUY).count();
        long sells = r.fills().size() - buys;
        sb.append("| ").append(label)
                .append(" | ").append(r.fills().size())
                .append(" | ").append(buys).append(" / ").append(sells)
                .append(" | ").append(round(captureBp(r), 2))
                .append(" | ").append(round(r.avgInventory(), 4))
                .append(" | ").append(round(100.0 * r.windowsAtCap()
                        / Math.max(1, r.windows()), 1)).append("%")
                .append(" | **").append(round(r.maxDrawdown(), 1)).append("**")
                .append(" | ").append(rung.quoter().openLots())
                .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                .append(" | **").append(round(r.pnlAtStart(), 1)).append("**")
                .append(" |\n");
    }

    /** Строка лестницы замороженной пары. Обе лестницы печатают одно и то же. */
    private void appendFrozenRow(StringBuilder sb, RatioRung rung, SimDataReader.Dataset data) {
        SimEngine.Result r = rung.result();
        double edge = netEdgeBp(r, 60_000);
        double buy = netEdgeBp(r, 60_000, Side.BUY);
        double sell = netEdgeBp(r, 60_000, Side.SELL);
        sb.append("| ").append(rung.ratio() == 0 ? "нет" : round(rung.ratio() / 1000.0, 0) + " с")
                .append(" | ").append(r.frozenCycles())
                .append(" | ").append(round(100.0 * r.frozenHeldWindows()
                        / Math.max(1, r.windows()), 1)).append("%")
                .append(" | ").append(r.fills().size())
                .append(" | ").append(round(captureBp(r), 2))
                .append(" | ").append(round(edge, 2))
                .append(" | ").append(round(buy, 2)).append(" / ").append(round(sell, 2))
                .append(" | **").append(round(edge * turnover(r) / 10_000, 1)).append("**")
                .append(" | ").append(round(r.avgInventory(), 4))
                .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                .append(" |\n");
    }

    /** Базовый отступ плюс лестница, по возрастанию и без дублей. */
    private double[] offsets(Quoter.Params base) {
        return java.util.stream.DoubleStream
                .concat(java.util.stream.DoubleStream.of(base.offset()),
                        java.util.Arrays.stream(cfg.simOffsetLadder()))
                .distinct().sorted().toArray();
    }

    /** Период опроса плюс лестница задержки, по возрастанию и без дублей. */
    private int[] latencySeconds(double windowSec) {
        return java.util.stream.IntStream
                .concat(java.util.stream.IntStream.of(Math.max(1, (int) Math.round(windowSec))),
                        java.util.Arrays.stream(cfg.simLatencyLadder()))
                .distinct().sorted().toArray();
    }

    /** Множитель 1.0 (базовый потолок) плюс лестница, по возрастанию и без дублей. */
    private double[] capFactors() {
        return java.util.stream.DoubleStream
                .concat(java.util.stream.DoubleStream.of(1.0),
                        java.util.Arrays.stream(cfg.simCapLadder()))
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
        return nullDistribution(params, limits, data, strategy,
                seed -> QuotePolicy.random(params, seed));
    }

    /**
     * Нулевое распределение того же вида, но с центром из случайной недавней
     * справедливой цены: расстояние и скос как у стратегии (док. 127 §9).
     */
    private Null anchorNullDistribution(Quoter.Params params, ExecutionModel.Limits limits,
                                        SimDataReader.Dataset data, SimEngine.Result strategy) {
        return nullDistribution(params, limits, data, strategy,
                seed -> QuotePolicy.staleAnchor(params, seed, cfg.simControlAnchorWindows()));
    }

    private Null nullDistribution(Quoter.Params params, ExecutionModel.Limits limits,
                                  SimDataReader.Dataset data, SimEngine.Result strategy,
                                  java.util.function.LongFunction<QuotePolicy> control) {
        int seeds = Math.max(1, cfg.simRandomSeeds());
        double[] captures = new double[seeds];
        double[] totals = new double[seeds];
        for (int i = 0; i < seeds; i++) {
            SimEngine.Result result = new SimEngine(params, limits, cfg.simMakerFee(),
                    control.apply(cfg.simRandomSeed() + i)).run(data.windows());
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
        // Захват берётся из самих исполнений, markout — из ряда справедливых цен.
        // Раньше вместо захвата стоял markout(0), который тождественно равен ему
        // ТОЛЬКО при базе «цена заявки»; при такой базе markout(Δ) тоже содержал
        // захват, и сумма считала его дважды — при нулевом дрейфе выходило 2·d.
        double capture = withHorizon.stream().mapToDouble(Fill::spreadCapture).sum();
        Markout.Stats later = Markout.compute(withHorizon, result.fairSeries(), horizonMs);
        double net = capture + later.mean() * later.fills();
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

    /** Захват на единицу оборота, в базисных пунктах. */
    private static double captureBp(SimEngine.Result result) {
        double turnover = turnover(result);
        return turnover > 0 ? result.pnl().spreadCapture() / turnover * 10_000 : Double.NaN;
    }

    private static Quoter.Params withSkewK(Quoter.Params base, double skewK) {
        return base.withSkewK(skewK);
    }

    /** Ступень веса дрейфа: всё остальное неизменно (док. 98 §3). */
    private static Quoter.Params withDriftBeta(Quoter.Params base, double beta) {
        return base.withDriftBeta(beta);
    }

    /** Ступень асимметрии набора: покупаем медленнее, разгружаемся свободно (док. 98 §6). */
    private static Quoter.Params withBuyRatio(Quoter.Params base, double ratio) {
        return base.withBuyRatio(ratio);
    }

    /** Ступень непрерывного шейпирования размера покупки (док. 101 §3.2). */
    private static Quoter.Params withShapeEta(Quoter.Params base, double eta) {
        return base.withShapeEta(eta);
    }

    private static Quoter.Params withOffset(Quoter.Params base, double offset) {
        return base.withOffset(offset);
    }

    /**
     * Ступень потолка — вместе с ПРОПОРЦИОНАЛЬНЫМ размером заявки.
     *
     * Иначе ступень меряет не то, что написано. При фиксированном лоте 0.05 и
     * потолке ×0.1 = 0.025 лот оказывается ВДВОЕ БОЛЬШЕ потолка: условие «бид
     * ставим, пока инвентарь ниже потолка» пропускает целую заявку, инвентарь
     * улетает до 0.075, и средний инвентарь выходит ВЫШЕ потолка (0.0333 при
     * потолке 0.025). Получается не маленький потолок, а котировщик, который его
     * не соблюдает.
     *
     * Масштабируя лот вместе с потолком, мы держим отношение «лотов на потолок»
     * постоянным — и ступень отвечает ровно на свой вопрос: сколько позиции мы
     * готовы нести (док. 97).
     */
    private static Quoter.Params withCap(Quoter.Params base, double cap) {
        return base.withCap(cap);
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
        out.put("windows_at_zero", result.windowsAtZero());
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
                          List<Rung> ladder, List<SkewRung> skewLadder, List<CapRung> capLadder,
                          List<LatencyRung> latencyLadder,
                          List<DriftRung> driftLadder, List<RatioRung> ratioLadder,
                          List<RatioRung> shapeLadder, List<CrossCell> cross,
                          List<RatioRung> stopLadder, List<RatioRung> targetLadder,
                          List<RatioRung> targetFloored,
                          List<RatioRung> hedgeLadder, List<RatioRung> leashLadder,
                          List<RatioRung> wideStep, List<RatioRung> costFloor,
                          List<GridRung> gridMargin, List<GridRung> gridWidening,
                          List<GridRung> gridLots,
                          List<RatioRung> frozenCool, List<RatioRung> frozenAge,
                          List<RatioRung> stickyOuter, List<RatioRung> stickyInner,
                          List<java.util.Map.Entry<String, SimEngine.Result>> queueControl,
                          List<RatioRung> anchorDepth, SimEngine.Result noSkewResult,
                          SimEngine.Result ownBookResult) {
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
        // Шаг окна — не украшение: он и есть период котирования базового прогона,
        // и именно его молча меняет быстрый ярус. Пока его не печатали, отчёт на
        // пятисекундных данных нельзя было отличить от отчёта на секундных.
        sb.append("| **Шаг окна (период котирования)** | **")
                .append(round(data.windowPeriodSec(), 2)).append(" с** |\n");
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

        sb.append("### Второй контроль: случайный якорь на ТЕХ ЖЕ расстояниях (док. 127 §9)\n\n");
        sb.append("Первый контроль рандомизирует не только положение центра, но и само "
                + "расстояние — оно равномерно на (0, 2d]. Между тем `край × оборот` по "
                + "расстоянию не плоский, у него есть вершина, поэтому смесь расстояний "
                + "вокруг d — это не нейтральная перестановка, а ДРУГАЯ точка лестницы "
                + "отступа. Проигрыш такому контролю смешан с лестницей и сам по себе не "
                + "значит ничего.\n\n");
        sb.append("Здесь отличие ровно одно: расстояние ±d и скос как у стратегии, а центр "
                + "берётся из случайной справедливой цены за последние ")
                .append(cfg.simControlAnchorWindows())
                .append(" окон. Вопрос, на который отвечает таблица: **помогает ли слежение "
                        + "за справедливой ценой** или довольно стоять на правильном "
                        + "удалении от чего угодно похожего на цену.\n\n");
        sb.append("| Отступ | Захват стратегии | Контроль: среднее | σ | 5-й … 95-й процентиль "
                + "| Стратегия выше по захвату | (σ) | Стратегия выше по `total` |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Rung rung : ladder) {
            Null n = rung.anchorNulls();
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
        sb.append("\n**Как читать вместе с предыдущей таблицей.** Проигрыш ОБОИМ контролям "
                + "означает, что мы продаём ликвидность, а не выбираем цены, и "
                + "оптимизировать надо расстояние и стоимость нейтральности, а не логику "
                + "скоса и слежения. Проигрыш только первому — артефакт лестницы отступа, "
                + "и он ничего не говорит о конструкции.\n\n");

        renderAnchorDepth(sb, anchorDepth, base, data);
        renderFairControls(sb, base, baseResult, noSkewResult, ownBookResult);

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

        sb.append("### Закон прихода заявок и замкнутая формула отступа (док. 100)\n\n");
        sb.append("Вся литература по маркет-мейкингу стоит на допущении "
                + "`λ(δ) = A·e^{−κδ}`. Оно эмпирическое и обычно не проверяется. "
                + "Наша лестница отступа — прямое его измерение.\n\n");
        List<ArrivalLaw.Rung> byFills = new ArrayList<>();
        List<ArrivalLaw.Rung> byTurnover = new ArrayList<>();
        for (Rung rung : ladder) {
            byFills.add(new ArrivalLaw.Rung(rung.offset(), rung.result().fills().size()));
            byTurnover.add(new ArrivalLaw.Rung(rung.offset(), turnover(rung.result())));
        }
        ArrivalLaw.Fit fitFills = ArrivalLaw.fit(byFills);
        ArrivalLaw.Fit fitTurnover = ArrivalLaw.fit(byTurnover);
        sb.append("| Подгонка | κ | 1/κ, б.п. | R² | Держится |\n|---|---|---|---|---|\n");
        sb.append("| по числу исполнений | ").append(round(fitFills.kappa(), 1))
                .append(" | ").append(round(10_000 / Math.max(1e-9, fitFills.kappa()), 2))
                .append(" | ").append(round(fitFills.rSquared(), 3))
                .append(" | ").append(fitFills.holds() ? "да" : "**нет**").append(" |\n");
        sb.append("| **по обороту** | ").append(round(fitTurnover.kappa(), 1))
                .append(" | **").append(round(10_000 / Math.max(1e-9, fitTurnover.kappa()), 2))
                .append("** | ").append(round(fitTurnover.rSquared(), 3))
                .append(" | ").append(fitTurnover.holds() ? "да" : "**нет**").append(" |\n\n");
        sb.append("Подгонок две, и это не педантизм: глубокие исполнения приходят от "
                + "крупных выносов, поэтому средний филл РАСТЁТ с дистанцией, и "
                + "оптимизировать надо по обороту, а не по числу сделок. `κ` для "
                + "оборота меньше — то есть оптимальный отступ ШИРЕ учебничного.\n\n");
        double staleBp = 2.86 * Math.sqrt(data.windowPeriodSec() / 5.0);
        double adverse = Math.max(0, captureBp(baseResult) - netEdgeBp(baseResult, 60_000));
        double costFraction = (staleBp + adverse) / 10_000;
        sb.append("| Слагаемое пошлины `c` | б.п. |\n|---|---|\n");
        sb.append("| устаревание при шаге ").append(round(data.windowPeriodSec(), 1))
                .append(" с | ").append(round(staleBp, 2)).append(" |\n");
        sb.append("| неблагоприятный отбор (захват − край 60 с) | ")
                .append(round(adverse, 2)).append(" |\n");
        sb.append("| **итого `c`** | **").append(round(staleBp + adverse, 2)).append("** |\n\n");
        sb.append("| Отступ | б.п. |\n|---|---|\n");
        sb.append("| учебничный `1/κ` (оборот) | ")
                .append(round(10_000 / Math.max(1e-9, fitTurnover.kappa()), 2)).append(" |\n");
        sb.append("| **формула `δ* = c + 1/κ`** | **")
                .append(round(fitTurnover.optimalOffset(costFraction) * 10_000, 2)).append("** |\n");
        sb.append("| наш рабочий `d` | ").append(round(base.offset() * 10_000, 2)).append(" |\n\n");
        sb.append("Замкнутая формула — независимая проверка настройки с другой стороны: "
                + "два измеренных параметра вместо перебора по трём окнам. Без "
                + "слагаемого `c`, которого в моделях нет вовсе, формула дала бы "
                + "отступ, на котором наш измеренный край отрицателен.\n\n");

        sb.append("**Остатки подгонки по ступеням.** Если хвост толще экспоненты, на "
                + "дальних отступах факт окажется выше модели, и настоящий оптимум "
                + "сдвинется вправо от формулы (док. 103 §4).\n\n");
        sb.append("| Отступ, б.п. | Оборот факт | Модель | Факт/модель |\n|---|---|---|---|\n");
        for (Rung rung : ladder) {
            double actual = turnover(rung.result());
            double predicted = fitTurnover.predict(rung.offset());
            sb.append("| ").append(round(rung.offset() * 10_000, 1))
                    .append(" | ").append(round(actual, 0))
                    .append(" | ").append(round(predicted, 0))
                    .append(" | ").append(predicted > 0 ? round(actual / predicted, 2) : "—")
                    .append(" |\n");
        }
        double adverseSe = Markout.standardErrorBp(
                Markout.withHorizon(baseResult.fills(), baseResult.fairSeries(), 60_000),
                baseResult.fairSeries(), 60_000);
        sb.append("\n| Оценка | Значение |\n|---|---|\n");
        sb.append("| Неблагоприятный отбор | ").append(round(adverse, 2)).append(" б.п. |\n");
        sb.append("| **Стандартная ошибка отбора** | **±").append(round(adverseSe, 2))
                .append(" б.п.** |\n");
        sb.append("| Исполнений в оценке | ").append(Markout.withHorizon(baseResult.fills(),
                baseResult.fairSeries(), 60_000).size()).append(" |\n");
        sb.append("| `δ*` при отборе +1 СО | ")
                .append(round(fitTurnover.optimalOffset((staleBp + adverse + adverseSe) / 10_000)
                        * 10_000, 2)).append(" б.п. |\n");
        sb.append("| `δ*` при отборе −1 СО | ")
                .append(round(fitTurnover.optimalOffset((staleBp + adverse - adverseSe) / 10_000)
                        * 10_000, 2)).append(" б.п. |\n\n");
        sb.append("Ошибка обязана стоять рядом с оценкой: на коротком окне отбор "
                + "меряется по десяткам исполнений, и различие между режимами может "
                + "оказаться одной и той же величиной, увиденной дважды. Пока "
                + "доверительный интервал `δ*` перекрывает соседние режимы, делать "
                + "отступ следящим за режимом нельзя.\n\n");

        sb.append("### Персистентность дрейфа: альфа или правило риска (док. 100 §6.1)\n\n");
        DriftPersistence.Stats persistence = DriftPersistence.compute(
                baseResult.fairSeries(), base.driftWindowMs() > 0 ? base.driftWindowMs() : 1_800_000L,
                cfg.simHoldHorizonMs(), 60_000L);
        sb.append("Теорема Guéant et al. предписывает сдвигать центр котировки на "
                + "ОЖИДАЕМОЕ изменение цены за горизонт удержания. Эта величина "
                + "измерима: регрессия будущей доходности на трейлинг-дрейф. Её "
                + "коэффициент и есть теоретический `β` — вместо нашей геометрической "
                + "эвристики.\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Точек регрессии | ").append(persistence.points()).append(" |\n");
        sb.append("| Перекрытие окон | ×").append(round(persistence.overlap(), 0)).append(" |\n");
        sb.append("| Наклон (доля дрейфа, доживающая до горизонта) | ")
                .append(round(persistence.slope(), 4)).append(" |\n");
        sb.append("| **IC (корреляция прошлого с будущим)** | **")
                .append(round(persistence.correlation(), 4)).append("** |\n");
        sb.append("| R² | ").append(round(persistence.rSquared(), 4)).append(" |\n");
        sb.append("| Теоретический `β` = наклон / `k` | ")
                .append(round(persistence.betaFor(base.skewK()), 0)).append(" |\n");
        sb.append("| Наш геометрический `β` | ")
                .append(round(Quoter.betaFromGeometry(base.offset()), 0)).append(" |\n");
        sb.append("| **Вердикт** | ").append(persistence.predictive()
                        ? "в дрейфе есть направленное содержание"
                        : "**предсказуемости нет — дрейф-скос работает как ПРАВИЛО РИСКА, не как альфа**")
                .append(" |\n\n");
        sb.append("Это ответ о ПРИРОДЕ результата, а не о его величине. Если будущее "
                + "из прошлого дрейфа не предсказывается, дрейф-скос законен, но "
                + "описывать его надо как управление риском, и переносить на другие "
                + "площадки как «сигнал» нельзя. Плато по `β` (док. 99 §3) указывало "
                + "туда же заранее: настоящая альфа была бы чувствительна к весу.\n\n"
                + "Перекрытие окон обязано стоять рядом с IC: соседние точки делят "
                + "почти всё окно, независимых наблюдений во столько же раз меньше, и "
                + "без этой поправки слабая корреляция выглядит значимой.\n\n");

        sb.append("### Скорость набора инвентаря: дрейф-скос и асимметрия (док. 98)\n\n");
        sb.append("Инвентарная нога буквально означает «набрали на падении — ждём "
                + "возврата», то есть ставку на ВОЗВРАТ движения. Подтверждённый факт "
                + "проекта, измеренный дважды независимо (S3 и S7), гласит обратное: "
                + "резкие движения продолжаются. Обе лестницы ниже меняют одно и то же — "
                + "скорость набора позиции, — и стоят рядом намеренно: если грубая "
                + "асимметрия даёт то же, что дрейф-скос, брать надо её, она проще и не "
                + "требует никакого сигнала.\n\n");
        sb.append("| β дрейфа | Исполнений | Захват, б.п. | Чистый край 60 с "
                + "| Край × оборот | Средний инвентарь | Время с полным "
                + "| **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (DriftRung rung : driftLadder) {
            SimEngine.Result r = rung.result();
            double edge = netEdgeBp(r, 60_000);
            sb.append("| ").append(round(rung.beta(), 0))
                    .append(rung.beta() == 0 ? " (выкл.)" : "")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(edge, 2))
                    .append(" | ").append(round(edge * turnover(r) / 10_000, 1))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n| Доля лота на покупку | Исполнений | Захват, б.п. | Чистый край 60 с "
                + "| Край × оборот | Средний инвентарь | Время с полным "
                + "| **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : ratioLadder) {
            SimEngine.Result r = rung.result();
            double edge = netEdgeBp(r, 60_000);
            sb.append("| ×").append(round(rung.ratio(), 2))
                    .append(rung.ratio() == 1.0 ? " (симметрично)" : "")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(edge, 2))
                    .append(" | ").append(round(edge * turnover(r) / 10_000, 1))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n| η шейпирования | Исполнений | Захват, б.п. | Чистый край 60 с "
                + "| Край × оборот | Средний инвентарь | Время с полным "
                + "| **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : shapeLadder) {
            SimEngine.Result r = rung.result();
            double edge = netEdgeBp(r, 60_000);
            sb.append("| ").append(round(rung.ratio(), 1))
                    .append(rung.ratio() == 0 ? " (выкл.)" : "")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(edge, 2))
                    .append(" | ").append(round(edge * turnover(r) / 10_000, 1))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Непрерывное шейпирование против ступеньки.** `η` задаёт "
                + "`размер покупки = лот · e^{−η·инвентарь/потолок}`: η = 1.4 означает "
                + "четверть лота у самого потолка и полный лот на пустом счёте. У "
                + "ступеньки `buy-size-ratio` есть порог, и именно к нему оказался "
                + "чувствителен асимметричный набор — знак альфы на падении менялся "
                + "между ×0.5 и ×0.25 (док. 99 §4). У непрерывной формы порога нет "
                + "вовсе (док. 101 §3.2).\n\n");

        sb.append("\n**Читать по `total` против `buy & hold`, а не по краю.** Обе "
                + "лестницы меняют НЕСОМУЮ ПОЗИЦИЮ, а «край × оборот» о ней ничего не "
                + "знает (док. 96 §4): по нему выиграет ступень с наибольшим инвентарём. "
                + "Вопрос здесь другой — уменьшается ли проигрыш простому удержанию.\n\n");

        sb.append("### Лестница ЦЕЛИ скоса — куда контроллер тянет инвентарь\n\n");
        sb.append("Сила скоса задаёт, насколько резко котировки уходят при отклонении "
                + "инвентаря; цель задаёт, от чего это отклонение считается. При цели 0 "
                + "контроллер целится в ПУСТОЙ счёт, а на споте там аск выставить нечем: "
                + "стратегия односторонняя ровно столько времени, сколько стоит в нуле "
                + "(колонка «время с нулевым»).\n\n");
        sb.append("**Читать по краю × оборот, а не по `total`.** Цель прямо меняет "
                + "несомую позицию, поэтому `total` растёт с ней механически — это бета, "
                + "а не заработок котировщика. Вопрос лестницы один: прибавляется ли "
                + "КРАЙ, когда котировка становится двусторонней.\n\n");
        sb.append("**Без пола по себестоимости** (как мерилось в док. 111)\n\n");
        appendTargetHeader(sb);
        for (RatioRung rung : targetLadder) {
            appendTargetRow(sb, rung, base, true);
        }
        sb.append("\n**С полом по себестоимости** — так устроен второй бот\n\n");
        sb.append("Порознь цель и пол отвечают на разные вопросы, вместе — на один. "
                + "Без пола ненулевая цель означала «держи столько-то И продавай дешевле, "
                + "чем купил», и в док. 111 мерилось именно это. Пол убирает вторую "
                + "половину, оставляя первую.\n\n");
        appendTargetHeader(sb);
        for (RatioRung rung : targetFloored) {
            appendTargetRow(sb, rung, base, false);
        }
        sb.append("\n");

        sb.append("### Хедж шортом на перпе: лестница периода (док. 122)\n\n");
        double hedgeStep = cfg.simHedgeStep(symbol);
        sb.append("Против спотового инвентаря держим шорт на перпе Kraken, доводя "
                + "его до `−инвентарь` раз в период. Схема «купили — сразу шорт» на наших "
                + "размерах невозможна: шаг контракта крупнее нашей сделки. "
                + "Хеджируется нетто-позиция, и период — главный размен.\n\n");
        sb.append("**Разрешение хеджа на этом прогоне** (док. 127 §8.4): шаг контракта ")
                .append(trimNum(hedgeStep)).append(", лот ").append(trimNum(base.size()))
                .append(", потолок инвентаря ").append(trimNum(base.inventoryCap()))
                .append(" — то есть **")
                .append(hedgeStep > 0 ? String.valueOf(round(base.inventoryCap() / hedgeStep, 1)) : "∞")
                .append(" ступеней контракта на весь потолок**")
                .append(hedgeStep > 0 && base.inventoryCap() / hedgeStep < 20
                        ? ". Ниже двух десятков ступеней хедж не грубоват, а НЕВОЗМОЖЕН: "
                        + "округление сравнимо с самой позицией."
                        : ".")
                .append(" Округление целевого шорта — ")
                .append(cfg.simHedgeRoundDown() ? "**вниз** (нетто-шорта не возникает)"
                        : "**к ближайшему** (возможен нетто-шорт до половины шага)")
                .append(".\n\n");
        sb.append("**Контроль здесь другой.** Захеджированная конструкция рыночно "
                + "нейтральна, поэтому сравнивать её с `buy & hold` бессмысленно — "
                + "сравнивать надо с **нулём**: весь результат обязан приходить из захвата "
                + "спреда, а не из того, что рынок куда-то сходил.\n\n");
        sb.append("Числа не выдуманы: комиссия 0.05% — фактический тариф Kraken из их же "
                + "API, ставка фондирования 11.5 ppm/ч — среднее по нашей таблице "
                + "`kraken_funding` за 20.08–01.09.2026. Ставка положительна, значит шорт "
                + "её ПОЛУЧАЕТ: это попутный ветер, а не издержка.\n\n");
        sb.append("| Период | Сделок на перпе | Комиссии | Фондирование | Переоценка шорта "
                + "| Остаточная позиция | Нетто-шорт: мин / доля окон | Спот `total` | **С хеджем** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : hedgeLadder) {
            SimEngine.Result r = rung.result();
            long ms = (long) rung.ratio();
            String label = ms >= 3_600_000 ? (ms / 3_600_000) + " ч"
                    : ms >= 60_000 ? (ms / 60_000) + " мин" : (ms / 1000) + " с";
            sb.append("| ").append(label)
                    .append(" | ").append(r.hedgeTrades())
                    .append(" | ").append(round(r.hedgeCost(), 1))
                    .append(" | ").append(round(r.hedgeFunding(), 1))
                    .append(" | ").append(round(r.hedgePnl(), 1))
                    .append(" | ").append(round(r.hedgeResidual(), 5))
                    .append(" | ").append(round(r.hedgeNetMin(), 5))
                    .append(" / ").append(round(100 * r.hedgeShortWindows(), 1)).append("%")
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" | **").append(round(r.hedgedTotal(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Как читать.** «Остаточная позиция» — средняя непокрытая часть "
                + "инвентаря: она равна нулю только при хедже каждое окно и растёт с "
                + "периодом. Именно она и есть то, за что мы платим, экономя на комиссии. "
                + "Разность между `спот total` и `с хеджем` — цена нейтральности.\n\n");
        sb.append("Колонка **нетто-шорта** — приёмка правки док. 127 §8.4, и читать её надо "
                + "по первой строке. При округлении вниз шорт не превышает инвентарь **в "
                + "момент ребалансировки**, поэтому на ступени «каждое окно» нетто-шорта "
                + "нет вовсе. С ростом периода он появляется, но уже по другой причине: "
                + "инвентарь между ребалансировками уменьшается (мы продаём), а шорт стоит "
                + "на старом уровне. Это плата за редкость слежения, а не скрытый дефект "
                + "округления, и она входит в ту же остаточную ногу.\n\n");
        sb.append("Разница принципиальна. Округление к ближайшему создаёт обратную позицию "
                + "СРАЗУ, в момент установки, и её величина не зависит от того, как часто "
                + "мы ребалансируем: на живом масштабе BTC это до 20% потолка. Дрейф между "
                + "ребалансировками, наоборот, лечится периодом.\n\n");

        sb.append("### Бид от цены входа с поводком (док. 119)\n\n");
        sb.append("Обычный бид висит на `справедливая × (1 − шаг)` и пересчитывается "
                + "каждый тик: он никогда не отстаёт, но и исполниться может только от "
                + "ВЫНОСА такой глубины за один принт. По замеру ленты на 20 б.п. до нас "
                + "доходит 1.5 сделки в сутки, на 30 — ноль. Поэтому растущий шаг не "
                + "«покупает глубже», а перестаёт покупать.\n\n");
        sb.append("Здесь бид привязан к цене последней покупки и ЖДЁТ, пока рынок придёт. "
                + "Поводок — единственный параметр — задаёт всё семейство: равен отступу "
                + "(10 б.п.) значит прежняя привязка к рынку, 9.99 значит чистая сетка.\n\n");
        sb.append("| Поводок | Исполнений | Покупок / продаж | Захват, б.п. | Ср. инвентарь "
                + "| Время с полным | **Ёмкость падения** | **Просадка** | **Total** "
                + "| **Buy & hold** | **Альфа** | **При возврате** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : leashLadder) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(rung.ratio() >= 1 ? "∞ (сетка)"
                            : round(rung.ratio() * 10_000, 1) + " б.п.")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.BUY).count())
                    .append(" / ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.SELL).count())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(capacity(r)).append("**")
                    .append(" | **").append(round(r.maxDrawdown(), 1)).append("**")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" | **").append(round(r.pnl().total() - r.buyAndHoldPnl(), 1))
                    .append("**")
                    .append(" | **").append(round(r.pnlAtStart(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Что здесь читать.** Нижняя ступень обязана совпасть со строкой "
                + "«пол по себестоимости» — это приёмка. Дальше вопрос один: покупает ли "
                + "ждущая заявка БОЛЬШЕ на падении (колонка «покупок») и во что это "
                + "обходится на росте, где она остаётся внизу и не исполняется вовсе.\n\n");

        sb.append("### Растущий шаг покупок: ёмкость падения (док. 117)\n\n");
        sb.append("Кандидат-конструкция второго бота: **пол по себестоимости на аске "
                + "плюс растущий шаг на биде**. Шаг = `шаг₀ × (1 + η · лотов)`, потолок 2%. "
                + "Обе правки стоят вместе, потому что порознь они отвечают на разные "
                + "половины одного вопроса: пол убирает продажу в убыток, шаг решает, "
                + "насколько глубокое падение мы вообще отрабатываем.\n\n");
        sb.append("Ёмкость по геометрии: `шаг₀ × (N + η·N(N−1)/2)`. Колонка «ёмкость "
                + "факт» — измеренная просадка в момент первого упора в потолок; она "
                + "обязана сойтись с расчётной, иначе модель шага неверна.\n\n");
        sb.append("| η | Ёмкость расчётная | **Ёмкость факт** | Исполнений | Покупок / продаж "
                + "| Захват, б.п. | Ср. инвентарь | Время с полным | **Просадка** | **Total** "
                + "| **Buy & hold** | **При возврате цены** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        int lots = defaultGridLots(base);
        for (RatioRung rung : wideStep) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(round(rung.ratio(), 2))
                    .append(" | ").append(round(WideningBidPolicy.capacityPct(
                            base.offset(), rung.ratio(), lots), 2)).append("%")
                    .append(" | **").append(capacity(r)).append("**")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.BUY).count())
                    .append(" / ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.SELL).count())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(round(r.maxDrawdown(), 1)).append("**")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" | **").append(round(r.pnlAtStart(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n⚠️ **η = 0 — это НЕ нынешний бот.** Политика заменяет бид целиком и "
                + "тем самым убирает вклад скоса в него: при η = 0 расстояние до покупки "
                + "постоянно и от набранного не зависит вовсе. Нижняя ступень поэтому "
                + "покупает АГРЕССИВНЕЕ сегодняшней конструкции, а не так же. Сравнивать "
                + "надо с разделом «пол по себестоимости».\n\n"
                + "**Расчётная ёмкость расходится с фактической, и это результат, а не "
                + "ошибка.** Формула считает, что каждая покупка требует ровно своего шага "
                + "цены; на деле нужен ВЫНОС, доходящий до заявки, а такие редки. Поэтому "
                + "расчёт — нижняя граница: при η = 0.25 геометрия обещает 1.05%, "
                + "измерено 10.8%.\n\n");

        sb.append("### Пол по себестоимости: не продавать ниже цены входа (док. 116)\n\n");
        sb.append("Точечная правка, а не другой механизм. Отступ, скос и гейты работают "
                + "как прежде; убирается ровно одно правило — то, по которому скос, целясь "
                + "в нулевой инвентарь, велит разгружаться НИЖЕ цены покупки. Пока рынок "
                + "выше себестоимости, поведение в точности прежнее.\n\n");
        sb.append("Дефект, который это чинит, измерен в док. 115 §2: на падении "
                + "котировщик теряет **−439 даже при полном возврате цены**, то есть "
                + "убыток реализованный. Колонка «при возврате цены» и есть проверка: "
                + "если правка работает, там должен исчезнуть минус.\n\n");
        sb.append("**Ёмкость падения** — насколько успела упасть цена от своего пика к "
                + "моменту, когда инвентарь ВПЕРВЫЕ упёрся в потолок. Дальше конструкция "
                + "на покупку не котирует вовсе и просто держит позицию, поэтому это и "
                + "есть глубина падения, которую она способна отработать. «—» означает, "
                + "что потолок не был достигнут ни разу.\n\n");
        sb.append("| Маржа над входом | Исполнений | Покупок / продаж | Захват, б.п. "
                + "| Ср. инвентарь | Время с полным | **Ёмкость падения** | **Просадка** "
                + "| **Total** | **Buy & hold** | **При возврате цены** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        sb.append("| **пола нет (база)** | ").append(baseResult.fills().size())
                .append(" | ").append(baseResult.fills().stream()
                        .filter(f -> f.side() == Side.BUY).count())
                .append(" / ").append(baseResult.fills().stream()
                        .filter(f -> f.side() == Side.SELL).count())
                .append(" | ").append(round(captureBp(baseResult), 2))
                .append(" | ").append(round(baseResult.avgInventory(), 4))
                .append(" | ").append(round(100.0 * baseResult.windowsAtCap()
                        / Math.max(1, baseResult.windows()), 1)).append("%")
                .append(" | **").append(capacity(baseResult)).append("**")
                .append(" | **").append(round(baseResult.maxDrawdown(), 1)).append("**")
                .append(" | **").append(round(baseResult.pnl().total(), 1)).append("**")
                .append(" | **").append(round(baseResult.buyAndHoldPnl(), 1)).append("**")
                .append(" | **").append(round(baseResult.pnlAtStart(), 1)).append("**")
                .append(" |\n");
        for (RatioRung rung : costFloor) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(round(rung.ratio() * 10_000, 1)).append(" б.п.")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.BUY).count())
                    .append(" / ").append(r.fills().stream()
                            .filter(f -> f.side() == Side.SELL).count())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | **").append(capacity(r)).append("**")
                    .append(" | **").append(round(r.maxDrawdown(), 1)).append("**")
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" | **").append(round(r.pnlAtStart(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Цена правки — «время с полным инвентарём».** Разгрузка на падении "
                + "прекращается, инвентарь копится до потолка и стоит. Отложенный убыток "
                + "остаётся отложенным: пол не делает падение прибыльным, он лишь "
                + "перестаёт превращать его в реализованный убыток.\n\n");

        sb.append("### Сетка с якорем на себестоимости (док. 115)\n\n");
        sb.append("Другой механизм, а не настройка. У котировщика аск привязан к рынку "
                + "(`справедливая × (1 + отступ − скос)`), и при уходе цены вниз скос "
                + "велит разгружаться — то есть продавать в убыток. Здесь аск привязан к "
                + "ЦЕНЕ ПОКУПКИ лота (`вход × (1 + маржа)`): продаём только дороже, чем "
                + "купили, а падение переживаем накоплением. Бид ставится на "
                + "`справедливая × (1 − шаг)`, где шаг растёт с числом набранных лотов.\n\n");
        sb.append("**Решающая колонка — «при возврате цены».** Обычный `total` оценивает "
                + "инвентарь по цене на КОНЦЕ окна, а окно падения кончается на дне: там "
                + "любая накопительная стратегия выглядит плохо, и разность с `buy & hold` "
                + "меряет не эдж, а разницу позиций. Колонка «при возврате» отвечает на "
                + "другой вопрос: сколько останется, если цена вернётся к началу окна. "
                + "Контроль `buy & hold` в этой точке тождественно равен нулю, поэтому "
                + "число само по себе и есть альфа сценария возврата.\n\n");
        sb.append("**И решающая строка — падение.** Правило «продавать только выше входа» "
                + "делает каждую закрытую сделку прибыльной по построению; убыток целиком "
                + "уходит в незакрытый инвентарь. Смотреть надо на просадку и на то, "
                + "сколько лотов осталось открытыми.\n\n");

        sb.append("**Опорная точка — обычный котировщик**\n\n");
        sb.append("| | Исполнений | Ср. инвентарь | Просадка | **Total** | **Buy & hold** "
                + "| **При возврате цены** |\n|---|---|---|---|---|---|---|\n");
        sb.append("| котировщик, отступ ").append(round(base.offset() * 10_000, 1))
                .append(" б.п. | ").append(baseResult.fills().size())
                .append(" | ").append(round(baseResult.avgInventory(), 4))
                .append(" | ").append(round(baseResult.maxDrawdown(), 1))
                .append(" | **").append(round(baseResult.pnl().total(), 1)).append("**")
                .append(" | **").append(round(baseResult.buyAndHoldPnl(), 1)).append("**")
                .append(" | **").append(round(baseResult.pnlAtStart(), 1)).append("**")
                .append(" |\n\n");

        sb.append("**Лестница маржи: сколько просить сверх цены входа**\n\n");
        appendGridHeader(sb, "Маржа");
        for (GridRung rung : gridMargin) {
            appendGridRow(sb, round(rung.margin() * 10_000, 1) + " б.п.", rung);
        }
        sb.append("\n**Лестница торможения набора: во сколько раз шаг растёт на лот**\n\n");
        appendGridHeader(sb, "η");
        for (GridRung rung : gridWidening) {
            appendGridRow(sb, String.valueOf(round(rung.widening(), 2)), rung);
        }
        sb.append("\n**Лестница капитала: на сколько докупок хватает потолка**\n\n");
        appendGridHeader(sb, "Лотов");
        for (GridRung rung : gridLots) {
            appendGridRow(sb, rung.lots() + " (потолок " + round(rung.cap(), 3) + ")", rung);
        }
        sb.append("\n**Как читать.** «Открытых лотов на конце» — сколько покупок так и не "
                + "нашли выхода; это и есть перенесённый в будущее убыток. «Время с полным "
                + "инвентарём» показывает, где сетка упёрлась в капитал и перестала быть "
                + "стратегией вовсе.\n\n");

        sb.append("### Замороженная пара: двигаем только ПОСЛЕ исполнения (док. 114)\n\n");
        sb.append("Правило: выставили бид и аск и не трогаем их вовсе, пока одна "
                + "сторона не исполнится. После исполнения — пауза, затем обе стороны "
                + "выставляются заново по текущей справедливой цене и текущему "
                + "инвентарю, и пара снова замерзает.\n\n");
        sb.append("Отличие от липкой котировки: там решение принимается по "
                + "РАССТОЯНИЮ и упирается в невыбираемые пороги (док. 110 §8), здесь — "
                + "по СОБЫТИЮ, и порогов нет вовсе. Против правила работает то же, что "
                + "убило M3: неподвижная заявка при уходе цены оказывается по невыгодную "
                + "сторону справедливой, и исполняется первой именно она. Поэтому "
                + "решающая колонка — **разрыв между покупками и продажами по краю**: "
                + "если выживает всегда та сторона, от которой рынок ушёл, разрыв "
                + "разъедется.\n\n");
        sb.append("**Пауза после исполнения** (предохранитель по возрасту — из конфига)\n\n");
        sb.append("| Пауза | Выпусков пары | Доля времени замороженной | Исполнений "
                + "| Захват, б.п. | Чистый край | Край: покупки / продажи | **Край × оборот** "
                + "| Ср. инвентарь | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : frozenCool) {
            appendFrozenRow(sb, rung, data);
        }
        sb.append("\n**Предохранитель по возрасту: спасать ли зависшую пару** "
                + "(0 = правило в чистом виде)\n\n");
        sb.append("| Возраст | Выпусков пары | Доля времени замороженной | Исполнений "
                + "| Захват, б.п. | Чистый край | Край: покупки / продажи | **Край × оборот** "
                + "| Ср. инвентарь | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : frozenAge) {
            appendFrozenRow(sb, rung, data);
        }
        sb.append("\n**Как читать.** «Выпусков пары» — сколько раз за окно правило "
                + "сработало целиком; малое число означает, что пара зависла и торговли "
                + "не было. «Доля времени замороженной» показывает то же с другой "
                + "стороны. Сравнивать с базовым прогоном надо по **краю × оборот**: "
                + "правило меняет и качество сделки, и их число, и `total` смешивает это "
                + "с бетой.\n\n");

        sb.append("### Липкая котировка — задание Z8 (док. 109 §II)\n\n");
        sb.append("Гипотеза: часть измеренной пошлины создаётся **самим переставлением** "
                + "заявки, а не потоком. Три механизма работают одновременно и разного "
                + "знака, поэтому лестницы разведены: `outer` изолирует M1 (пошлина "
                + "платится один раз на вход), `inner` — M3 (опасный снос: у липкой "
                + "заявки отступ не фиксирован, и бид может оказаться НАД справедливой).\n\n");
        sb.append("**Лестница `outer` при `inner` = 1 — изолирует M1**\n\n");
        sb.append("| outer | Перевыставлений/сут | Исполнений | Захват, б.п. | Чистый край "
                + "| Ср. инвентарь | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : stickyOuter) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(round(rung.ratio(), 2))
                    .append(" | ").append(round(r.requotesPerDay(data.spanMs()), 0))
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(netEdgeBp(r, 60_000), 2))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Лестница `inner` при `outer` = 1 — изолирует M3**\n\n");
        sb.append("| inner | Перевыставлений/сут | Исполнений | Захват, б.п. | Чистый край "
                + "| Ср. инвентарь | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : stickyInner) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(round(rung.ratio(), 2))
                    .append(" | ").append(round(r.requotesPerDay(data.spanMs()), 0))
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(netEdgeBp(r, 60_000), 2))
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Контроль M2: цена липкая, приоритет очереди сброшен**\n\n");
        sb.append("| Прогон | Исполнений | Чистый край | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|\n");
        for (var entry : queueControl) {
            SimEngine.Result r = entry.getValue();
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(netEdgeBp(r, 60_000), 2))
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\nЕсли липкая с сброшенной очередью ≈ базе — весь эффект в **приоритете "
                + "очереди**, и правильный вывод не «липкость», а «не терять приоритет без "
                + "причины». Если ≈ липкой — очередь не даёт ничего, работает M1.\n\n");
        sb.append("**Условия несостоятельности** (объявлены заранее, док. 109 §Z8.7): "
                + "ни одна точка лестниц не обыгрывает базу по двухрежимному `total`; "
                + "или знак меняется между соседними ступенями; или победитель не "
                + "обыгрывает контроль по активности.\n\n");

        sb.append("### Пошлина по позиции в очереди — задание Z9 (док. 109 §III)\n\n");
        sb.append("Зависит ли пошлина от того, КТО стоит внутри нас. Две гипотезы дают "
                + "противоположные рекомендации: **фильтр** — узкий котировщик собирает "
                + "мелкие касания и оставляет нам настоящие свипы (наш markout тогда "
                + "лучше); **снятие сливок** — он успевает убрать заявку перед "
                + "информированным потоком и оставляет токсичное нам (markout хуже, а "
                + "лестница по `d` загрязнена).\n\n");
        sb.append("⚠️ **Сравнивать между корзинами можно только markout.** Объём внутри "
                + "нас входит в модель исполнения, поэтому ЧИСЛО исполнений по корзинам "
                + "различается по построению — вывод из него был бы тавтологией. "
                + "`markout` входом модели не является.\n\n");
        for (var section : java.util.List.of(
                java.util.Map.entry("Мы на лучшей цене или нет",
                        QueueCost.byPresenceInside(baseResult.fills(), baseResult.fairSeries(), 60_000)),
                java.util.Map.entry("По номиналу внутри нас, USDC",
                        QueueCost.byQtyInside(baseResult.fills(), baseResult.fairSeries(), 60_000)),
                java.util.Map.entry("По числу уровней внутри нас",
                        QueueCost.byLevelsInside(baseResult.fills(), baseResult.fairSeries(), 60_000)))) {
            sb.append("**").append(section.getKey()).append("**\n\n");
            sb.append("| Корзина | Исполнений | markout 60 с, б.п. | ±СО | Захват, б.п. "
                    + "| **Чистый край** | **±СО** | n |\n|---|---|---|---|---|---|---|---|\n");
            for (QueueCost.Group g : section.getValue()) {
                sb.append("| ").append(g.label())
                        .append(" | ").append(g.fills())
                        .append(" | **").append(round(g.markoutBp(), 2)).append("**")
                        .append(" | ±").append(round(g.standardErrorBp(), 2))
                        .append(" | ").append(round(g.captureBp(), 2))
                        .append(" | **").append(round(g.netEdgeBp(), 2)).append("**")
                        .append(" | ±").append(round(g.netEdgeSeBp(), 2))
                        .append(" | ").append(g.n())
                        .append(" |\n");
            }
            sb.append("\n");
        }
        sb.append("⚠️ **Колонки markout и захват НЕ независимы.** Край определён как их "
                + "сумма, поэтому по любому разбиению `Δкрай = Δзахват + Δmarkout` "
                + "тождественно: «markout хуже, а захват ровно на столько же выше» — это "
                + "ОДИН факт, записанный дважды, а не два наблюдения, которые удачно "
                + "сократились. Единственная независимая величина таблицы — **чистый "
                + "край**, и вывод строится только по нему (док. 123 §2).\n\n"
                + "Край при этом ШУМНЕЕ markout, а не тише: в него входит и дисперсия "
                + "захвата. Поэтому его собственная ±СО обязательна — без неё «край не "
                + "отличается» означает лишь «мы не увидели бы и большую разницу».\n\n");
        sb.append("Ошибка обязана стоять рядом с оценкой: разница в 0.5 б.п. при СО 0.8 "
                + "не значит ничего, и на этом уже обжигались (док. 104 §4). Если "
                + "интервалы корзин перекрываются — верный вывод «разницы нет», и он не "
                + "хуже остальных: он закрывает переменную и говорит, что `c(d)` есть "
                + "полное описание пошлины.\n\n");

        sb.append("### Стоп по просадке инвентаря (док. 107 §5)\n\n");
        sb.append("Правило ничего не классифицирует. Гейт по режиму провалился потому, "
                + "что различающий признак — «вернётся ли цена» — наблюдаем только "
                + "постфактум (док. 106 §5). Стоп этого вопроса не задаёт: он реагирует "
                + "на **реализованный ущерб**, величину полностью наблюдаемую.\n\n");
        sb.append("Порог — в процентах от номинала потолка, чтобы ступени были "
                + "сопоставимы между размерами. Срабатывание снимает бид и "
                + "переставляет аск на лучший бид: разгрузка платная, половина спреда "
                + "учитывается как обычное исполнение.\n\n");
        sb.append("| Порог, % потолка | Срабатываний | Время в остановке | Исполнений "
                + "| Средний инвентарь | Просадка | **Total** | **Buy & hold** |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (RatioRung rung : stopLadder) {
            SimEngine.Result r = rung.result();
            sb.append("| ").append(round(rung.ratio(), 2))
                    .append(rung.ratio() == 0 ? " (выкл.)" : "")
                    .append(" | ").append(r.stopHits())
                    .append(" | ").append(round(100.0 * r.stoppedWindows()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(r.avgInventory(), 4))
                    .append(" | ").append(round(r.maxDrawdown(), 1))
                    .append(" | **").append(round(r.pnl().total(), 1)).append("**")
                    .append(" | **").append(round(r.buyAndHoldPnl(), 1)).append("**")
                    .append(" |\n");
        }
        sb.append("\n**Что сделает правило несостоятельным:** смена знака альфы между "
                + "соседними ступенями. Это то же условие, что дисквалифицировало "
                + "асимметричный набор в одиночку (док. 99 §4): чувствительность к "
                + "порогу означает, что дело в самом пороге, а не в механизме.\n\n");

        sb.append("### Кросс `β × η`: два тормоза набора вместе (док. 103 §3)\n\n");
        sb.append("Обе лестницы выше шли по одной оси при нулевой второй, то есть выбор "
                + "между механизмами делался вслепую. А профили у них разные: `η` "
                + "тормозит набор ПО ФАКТУ ПОЗИЦИИ и на шум не реагирует вовсе, `β` — "
                + "по внешней величине, у которой измеренный IC равен нулю. Цена "
                + "починки поэтому тоже разная, и комбинация не обязана быть суммой.\n\n");
        sb.append("| β \\ η |");
        double[] etas = cfg.simCrossEta();
        for (double eta : etas) {
            sb.append(" η=").append(round(eta, 1)).append(" |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < etas.length; i++) {
            sb.append("---|");
        }
        sb.append("\n");
        for (double beta : cfg.simCrossBeta()) {
            sb.append("| **β=").append(round(beta, 0)).append("** |");
            for (double eta : etas) {
                CrossCell cell = cross.stream()
                        .filter(c -> c.beta() == beta && c.eta() == eta)
                        .findFirst().orElse(null);
                if (cell == null) {
                    sb.append(" — |");
                    continue;
                }
                double alpha = cell.result().pnl().total() - cell.result().buyAndHoldPnl();
                sb.append(" ").append(round(alpha, 1))
                        .append(" <br><sub>").append(cell.result().fills().size())
                        .append(" филлов, инв ").append(round(cell.result().avgInventory(), 3))
                        .append("</sub> |");
            }
            sb.append("\n");
        }
        sb.append("\nВ клетках — **альфа над buy & hold**, под ней число исполнений и "
                + "средний инвентарь. `total` тут читать нельзя: механизмы меняют "
                + "несомую позицию, и сравнивать надо с тем, сколько дало бы простое "
                + "удержание той же позиции.\n\n");

        sb.append("### Лестница задержки: чего стоит REST вместо WebSocket\n\n");
        sb.append("Раз во сколько мы смотрим на рынок и переставляем заявку. Между "
                + "решениями котировка висит по старой цене, рынок уходит, и часть "
                + "исполнений достаётся нам устаревшими — те самые «разобрали». "
                + "Быстрее периода опроса промоделировать нечего: данные собраны с "
                + "этим шагом. Но наклон кривой показывает, сколько стоит каждая "
                + "ступень задержки, и тем самым — чего стоило бы её сокращение.\n\n");
        sb.append("| Задержка | Исполнений | Захват | **Чистый край 60 с** "
                + "| **Разобрали, % оборота** | Оборот | Край × оборот "
                + "| Перевыставлений/сут | Total |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (LatencyRung rung : latencyLadder) {
            SimEngine.Result r = rung.result();
            List<Fill> withHorizon = Markout.withHorizon(r.fills(), r.fairSeries(), 60_000);
            double turnover = withHorizon.stream().mapToDouble(Fill::notional).sum();
            double negative = withHorizon.stream()
                    .filter(f -> f.spreadCapture() < 0)
                    .mapToDouble(Fill::notional).sum();
            double capture = withHorizon.stream().mapToDouble(Fill::spreadCapture).sum();
            double edge = netEdgeBp(r, 60_000);
            sb.append("| ").append(rung.seconds()).append(" с")
                    .append(rung.seconds() == cfg.authBookPeriodSeconds() ? " (текущая)" : "")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(turnover > 0 ? round(capture / turnover * 10_000, 2) : "—")
                    .append(" | **").append(round(edge, 2)).append("**")
                    .append(" | **").append(turnover > 0
                            ? round(100 * negative / turnover, 1) + "%" : "—").append("**")
                    .append(" | ").append(round(turnover(r), 0))
                    .append(" | ").append(round(edge * turnover(r) / 10_000, 1))
                    .append(" | ").append(Math.round(r.requotesPerDay(data.spanMs())))
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" |\n");
        }
        sb.append("\nПобочно эта таблица отвечает и на вопрос лимита запросов: реже "
                + "котируешь — меньше перевыставлений. Если край при этом падает "
                + "медленно, у конструкции есть запас по частоте, которого мы не "
                + "знали.\n\n");

        sb.append("### Лестница потолка инвентаря: ёмкость (док. 84 §3)\n\n");
        sb.append("Базисные пункты превращает в деньги именно потолок. Таблица "
                + "отвечает, где наступает **механическое насыщение** — то есть где мы "
                + "перестаём упираться в потолок и его дальнейший рост уже ничего "
                + "не добавляет.\n\n");
        sb.append("| Потолок | ×  | Исполнений | Оборот | Доля рыночного потока "
                + "| Чистый край | **Край × оборот** | Средний инвентарь "
                + "| **Край × оборот на единицу инвентаря** | Время с полным инвентарём "
                + "| Медиана удержания |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CapRung rung : capLadder) {
            SimEngine.Result r = rung.result();
            double edge = netEdgeBp(r, 60_000);
            HoldingTime.Stats holdingAtCap = HoldingTime.compute(r.fills());
            sb.append("| ").append(round(rung.cap(), 4))
                    .append(" | ×").append(round(rung.factor(), 1))
                    .append(rung.factor() == 1.0 ? " (базовый)" : "")
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(turnover(r), 0))
                    .append(" | ").append(round(100 * r.flowShare(), 1)).append("%")
                    .append(" | ").append(round(edge, 2))
                    .append(" | **").append(round(edge * turnover(r) / 10_000, 1)).append("**")
                    .append(" | ").append(round(r.avgInventory(), 4))
                    // «Край × оборот» не знает о НЕСОМОЙ ПОЗИЦИИ, поэтому отбор по ней
                    // всегда смещён в сторону большего инвентаря. На скосе это сошло с
                    // рук, на потолке — нет: метрика растёт вправо монотонно, а инвентарь
                    // растёт быстрее, и максимум пришёлся бы на максимальную позицию,
                    // то есть ровно на то, что убивает конструкцию на падении (док. 96 §4).
                    .append(" | **").append(r.avgInventory() > 0
                            ? round(edge * turnover(r) / 10_000 / r.avgInventory(), 0) : "—")
                    .append("**")
                    .append(" | ").append(round(100.0 * r.windowsAtCap()
                            / Math.max(1, r.windows()), 1)).append("%")
                    .append(" | ").append(humanMs(holdingAtCap.medianMs()))
                    .append(" |\n");
        }
        sb.append("\n**Читать эту таблицу по «край × оборот» НЕЛЬЗЯ.** Метрика убрала "
                + "бету из числителя, но ничего не знает о позиции, которую ради этого "
                + "приходится нести, а потолок влияет именно на позицию. По ней максимум "
                + "всегда окажется на самом большом потолке — то есть там, где на "
                + "падающем рынке конструкция замерзает с полным инвентарём и теряет "
                + "больше, чем зарабатывает на растущем (док. 95 §4.2). Колонка «на "
                + "единицу инвентаря» — минимальная поправка на риск; окончательный "
                + "критерий ещё жёстче: ожидание по ОБОИМ режимам, растущему и "
                + "падающему (док. 96 §3).\n\n");
        sb.append("**Чего эта таблица НЕ показывает — ёмкости рынка.** Модель "
                + "проигрывает исторические сделки против гипотетической котировки и не "
                + "знает, что при доле потока в четверть поток стал бы другим: конкурент "
                + "сузился бы, часть принтов не случилась бы вовсе. Поэтому строки с "
                + "большой долей рынка — **верхняя граница ёмкости, а не ёмкость**. "
                + "Ёмкость и маршрутизация — один и тот же вопрос, и отвечает на него "
                + "только живое исполнение.\n\n");

        sb.append("### Лестница скоса — по краю, а не по `total`\n\n");
        sb.append("Скос меняет не цену исполнения, а момент: при его выключении захват "
                + "спреда остаётся прежним, а весь эффект приходит в markout. В `total` "
                + "он тонет в бете, поэтому ступени сравниваются по краю и по разрыву "
                + "между сторонами (док. 79 §7).\n\n");
        sb.append("| Скос `skew_k` | Чистый край 60 с | Разрыв сторон | Исполнений "
                + "| Средний филл | Оборот | **Край × оборот** | Инвентарь: средний "
                + "| **Время с полным инвентарём** | **Время с нулевым** | Total |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
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
                    .append(" | **").append(round(100.0 * r.windowsAtZero()
                            / Math.max(1, r.windows()), 1)).append("%**")
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" |\n");
        }
        sb.append("\nЕсли разрыв между сторонами схлопывается при уменьшении скоса, "
                + "асимметрия — свойство котировщика, а не рынка. `total` в этой таблице "
                + "читать надо с оглядкой на режим окна: меньший скос означает «дольше "
                + "держать позицию», и на растущем окне это автоматически прибавляет беты.\n\n"
                + "**Две колонки времени читать вместе.** Двусторонность теряется с ОБОИХ "
                + "краёв: при полном инвентаре нет бида, при нулевом нет аска — спот, "
                + "продавать нечем. Долго мерилась только левая беда, и правая ускользнула: "
                + "на живом счёте аск оказался тёмным больше половины времени, потому что "
                + "скос целится в НОЛЬ инвентаря, а не в середину (док. 93). Сумма двух "
                + "колонок — это доля времени, когда стратегия односторонняя.\n\n"
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

        sb.append("## Распределение захвата по исполнениям — есть ли у нас «разобрали»\n\n");
        sb.append("Корзины те же, что в отчёте по площадке (`--revx-flow`), поэтому "
                + "таблицы сравнимы строка в строку. Строка «< 0» — исполнения, где "
                + "справедливая цена к моменту сделки ушла дальше нашей котировки, то "
                + "есть нас разобрали по устаревшей заявке. Пока захват считался против "
                + "цены котирования, такой строки в модели не могло быть в принципе.\n\n");
        sb.append("| Захват, б.п. | Исполнений | Оборот | Доля оборота | Средний захват "
                + "| markout 60 с | **Чистый край** |\n|---|---|---|---|---|---|---|\n");
        double[] bucketEdges = {-1e9, 0, 5, 10, 15, 20, 30, 50, 1e9};
        String[] bucketLabels = {"< 0 (разобрали)", "0–5", "5–10", "10–15", "15–20",
                "20–30", "30–50", "> 50"};
        int buckets = bucketLabels.length;
        int[] bucketFills = new int[buckets];
        double[] bucketTurnover = new double[buckets];
        double[] bucketCapture = new double[buckets];
        double[] bucketMarkout = new double[buckets];
        List<Fill> withHorizon = Markout.withHorizon(baseResult.fills(),
                baseResult.fairSeries(), 60_000);
        for (Fill fill : withHorizon) {
            double notional = fill.notional();
            if (!(notional > 0)) {
                continue;
            }
            double captureBp = fill.spreadCapture() / notional * 10_000;
            int index = 0;
            while (index < buckets - 1 && captureBp >= bucketEdges[index + 1]) {
                index++;
            }
            var later = baseResult.fairSeries().floorEntry(fill.tsMs() + 60_000);
            bucketFills[index]++;
            bucketTurnover[index] += notional;
            bucketCapture[index] += fill.spreadCapture();
            if (later != null) {
                bucketMarkout[index] += fill.side().sign()
                        * (later.getValue() - fill.fairAtFill()) * fill.qty();
            }
        }
        double bucketTotal = java.util.Arrays.stream(bucketTurnover).sum();
        for (int i = 0; i < buckets; i++) {
            if (bucketFills[i] == 0) {
                continue;
            }
            double t = bucketTurnover[i];
            sb.append("| ").append(bucketLabels[i])
                    .append(" | ").append(bucketFills[i])
                    .append(" | ").append(round(t, 0))
                    .append(" | ").append(bucketTotal > 0
                            ? round(100 * t / bucketTotal, 1) + "%" : "—")
                    .append(" | ").append(round(bucketCapture[i] / t * 10_000, 2))
                    .append(" | ").append(round(bucketMarkout[i] / t * 10_000, 2))
                    .append(" | **").append(round((bucketCapture[i] + bucketMarkout[i])
                            / t * 10_000, 2)).append("** |\n");
        }
        sb.append("\nДоля строки «< 0» — прямая оценка того, как часто нас будут "
                + "разбирать при нашем отступе и нашем темпе опроса. Это не перенос "
                + "чужой ставки: у контрагента площадки книга обновляется вдвое реже, "
                + "и его 23% относятся к его конфигурации, а не к нашей.\n\n");

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
        double captureMean = baseResult.fills().isEmpty() ? Double.NaN
                : baseResult.fills().stream().mapToDouble(Fill::spreadCapture).sum()
                        / baseResult.fills().size();
        Markout.Stats at60 = Markout.compute(baseResult.fills(), baseResult.fairSeries(), 60_000);
        Markout.Stats at300 = Markout.compute(baseResult.fills(), baseResult.fairSeries(), 300_000);
        double edge60 = captureMean + at60.mean();
        sb.append("\n### Край на сделку, а не знак markout (правка ТЗ §5.5 по док. 71 §3.1)\n\n");
        sb.append("| Величина | Значение |\n|---|---|\n");
        sb.append("| Захваченный край на исполнение | ").append(round(captureMean, 4))
                .append(" |\n");
        sb.append("| `markout(60 с)` | ").append(round(at60.mean(), 4)).append(" |\n");
        sb.append("| **Край на сделку через 60 с** | **").append(round(edge60, 4)).append("** |\n");
        sb.append("| Неблагоприятный отбор как доля края, 60 с | ")
                .append(captureMean > 0 ? round(-at60.mean() / captureMean * 100, 1) + "%" : "—")
                .append(" |\n");
        sb.append("| То же, 300 с | ")
                .append(captureMean > 0 ? round(-at300.mean() / captureMean * 100, 1) + "%" : "—")
                .append(" |\n\n");
        if (baseResult.fills().isEmpty() || Double.isNaN(edge60)) {
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
        // Зеркало предыдущей строки. Измерялось только «полный инвентарь», и из-за
        // этого от модели ускользнуло состояние, которое на живом счёте оказалось
        // куда более частым: инвентарь на нуле, продавать нечем, аск не выставляется
        // вовсе. Односторонний режим — такая же потеря двусторонности, как и полный
        // инвентарь, только с другого края (док. 93).
        sb.append("| Доля времени с НУЛЕВЫМ инвентарём | ")
                .append(round(100.0 * baseResult.windowsAtZero() / Math.max(1, baseResult.windows()), 1))
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

    /**
     * Лестница глубины якоря: устаревание это или слежение (док. 132 §1).
     *
     * Возражение к контролю со случайным якорем звучит так: «не следить за
     * ценой» и «стоять с устаревшей котировкой» — одно и то же, а цена
     * устаревания уже измерена лестницей задержки, {@code 2.86·√(t/5)} б.п.
     * Значит счёт контроля обязан идти по формуле, и никакого нового знания в
     * нём нет.
     *
     * Проверка требует правильных единиц: захват здесь считается **в базисных
     * пунктах оборота**, а не в валюте, иначе сравнивать с формулой нечего.
     */
    private void renderAnchorDepth(StringBuilder sb, List<RatioRung> ladder,
                                   Quoter.Params base, SimDataReader.Dataset data) {
        if (ladder.isEmpty()) {
            return;
        }
        double stepSec = data.windowPeriodSec();
        sb.append("### Глубина якоря: это устаревание или слежение? (док. 132 §1)\n\n");
        sb.append("Возражение к предыдущему контролю: «не следить за ценой» и «стоять с "
                + "устаревшей котировкой» — одно и то же, а цена устаревания уже измерена "
                + "лестницей задержки. Если счёт контроля идёт по `d − 2.86·√(t/5)`, то "
                + "новый контроль меряет свежесть, а не выбор цен, и вопрос док. 127 §9 "
                + "им не закрыт.\n\n");
        sb.append("Средний возраст якоря — половина глубины: центр выбирается равномерно "
                + "среди последних N окон. Захват здесь **в б.п. оборота**, иначе с "
                + "формулой сравнивать нечего.\n\n");
        sb.append("| Глубина, окон | Средний возраст, с | Захват, б.п. оборота "
                + "| Предсказание `d − 2.86·√(t/5)` | Разница | Исполнений |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (RatioRung rung : ladder) {
            SimEngine.Result r = rung.result();
            double ageSec = rung.ratio() * stepSec / 2;
            double predicted = base.offset() * 10_000 - 2.86 * Math.sqrt(ageSec / 5.0);
            double actual = captureBp(r);
            sb.append("| ").append((int) rung.ratio())
                    .append(" | ").append(round(ageSec, 1))
                    .append(" | ").append(round(actual, 2))
                    .append(" | ").append(round(predicted, 2))
                    .append(" | ").append(round(actual - predicted, 2))
                    .append(" | ").append(r.fills().size())
                    .append(" |\n");
        }
        sb.append("\n**Как читать.** Если «разница» мала по всей лестнице, контроль меряет "
                + "устаревание и только его, а его преимущество над стратегией — "
                + "переизмерение лестницы задержки, а не проверка выбора цен.\n\n");
        sb.append("Систематически ОТРИЦАТЕЛЬНАЯ разница, растущая с возрастом, означает "
                + "большее: устаревшую котировку не просто сносит ценой, её ещё и выбирают "
                + "— число исполнений растёт вместе с глубиной якоря, потому что "
                + "промахнувшаяся цена оказывается привлекательной для той стороны, "
                + "которой она выгодна. Формула `2.86·√(t/5)` тогда занижает цену "
                + "несвежести на длинных возрастах.\n\n");
    }

    /**
     * Контроли C3 и C4 (док. 132 §1) — единственные, что отвечают на исходный
     * вопрос дока 127 §9. Оба стоят на ТЕКУЩЕЙ справедливой цене, поэтому
     * устареванием их разница со стратегией объясниться не может.
     */
    private void renderFairControls(StringBuilder sb, Quoter.Params base,
                                    SimEngine.Result strategy, SimEngine.Result noSkew,
                                    SimEngine.Result ownBook) {
        sb.append("### Контроли C3 и C4: что даёт скос и что даёт корзина (док. 132 §1)\n\n");
        sb.append("Оба контроля котируют на текущей справедливой цене и на том же "
                + "отступе, поэтому свежесть у всех троих одинаковая, и разница — "
                + "не устаревание.\n\n");
        sb.append("- **C3** выключает скос: котирует `справедливая ± d` симметрично. "
                + "Изолирует вклад скоса — единственного «умного» элемента, про который "
                + "доки 111 и 118 уже говорили, что он регулятор беты, а не источник края.\n");
        sb.append("- **C4** выключает и скос, и саму корзину: котирует от **середины "
                + "стакана своей пары**. Изолирует вклад справедливой цены из 23 пар с "
                + "implied-курсом USDC — самой дорогой части системы, которую ни разу не "
                + "сравнивали с тривиальной альтернативой.\n\n");
        sb.append("| Прогон | Захват, б.п. оборота | Захват, валюта | Исполнений "
                + "| Оборот | Ср. инвентарь | `total` |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        record Line(String label, SimEngine.Result r) {
        }
        for (Line line : List.of(new Line("стратегия", strategy),
                new Line("**C3: без скоса**", noSkew),
                new Line("**C4: от своей книги**", ownBook))) {
            SimEngine.Result r = line.r();
            sb.append("| ").append(line.label())
                    .append(" | ").append(round(captureBp(r), 2))
                    .append(" | ").append(round(r.pnl().spreadCapture(), 1))
                    .append(" | ").append(r.fills().size())
                    .append(" | ").append(round(turnover(r), 0))
                    .append(" | ").append(round(r.avgInventory(), 5))
                    .append(" | ").append(round(r.pnl().total(), 1))
                    .append(" |\n");
        }
        double strategyBp = captureBp(strategy);
        double noSkewBp = captureBp(noSkew);
        double ownBookBp = captureBp(ownBook);
        sb.append("\n**Разложение, которое читается только из этой таблицы.** Сравнивать "
                + "надо захват НА ЕДИНИЦУ ОБОРОТА: `total` у контролей выше просто "
                + "потому, что они несут больше инвентаря, а это бета, а не край.\n\n");
        sb.append("| Что изолируем | Разность | Вывод |\n|---|---|---|\n");
        sb.append("| **Вклад скоса** (стратегия − C3) | ")
                .append(round(strategyBp - noSkewBp, 2)).append(" б.п. | ")
                .append(strategyBp - noSkewBp > 0.5 ? "скос приносит край"
                        : strategyBp - noSkewBp < -0.5
                        ? "скос края НЕ приносит, а стоит — он регулятор беты (доки 111, 118)"
                        : "скос по краю нейтрален")
                .append(" |\n");
        sb.append("| **Вклад корзины** (C3 − C4) | ")
                .append(round(noSkewBp - ownBookBp, 2)).append(" б.п. | ")
                .append(noSkewBp - ownBookBp > 0.5
                        ? "справедливая цена из 23 пар окупается: середина своего стакана хуже"
                        : "корзина не окупается, тривиальная альтернатива не хуже")
                .append(" |\n\n");
        sb.append("И отдельная колонка, мимо которой пройти нельзя: **средний инвентарь**. "
                + "У C4 он в разы больше — котируя от середины собственного стакана, "
                + "конструкция едет вместе с ним и накапливает позицию. Это цена, "
                + "которой нет в захвате, но которая целиком видна в `total` и в риске.\n\n");
    }

    /** Шаги бывают мельче 1e-8: без %f они печатаются нулём. */
    private static String trimNum(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
