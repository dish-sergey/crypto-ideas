package org.home.data.revx.exec;

import java.util.List;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.replay.ReplayRunner;
import org.home.data.revx.sim.FairPrice;
import org.home.data.revx.sim.AnchoredBidPolicy;
import org.home.data.revx.sim.CostFloorPolicy;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.WideningBidPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code --revx-exec}: микро-live. Собирает цикл котирования, управление из
 * Telegram и журнал.
 *
 * Что это НЕ ЕСТЬ: запуск стратегии. Это измерительный прибор для одного числа —
 * доли исполнений, предсказанных моделью. Симуляция ответила на всё, что могла
 * (доки 74–90); единственное, чего она не проверяет по построению, — доходит ли
 * поток до нашей заявки. Ответ стоит десятки долларов и сутки работы.
 *
 * Параметры котирования берутся из ТОГО ЖЕ конфига, что и симуляция: скос, порог
 * перевыставления, гейты. Отличаются два:
 *
 * <ul>
 *   <li><b>размер</b> ({@code revx.exec.size}) — микроскопический, потому что
 *       мерить надо попадание в предсказание, а не P&L;</li>
 *   <li><b>отступ</b> ({@code revx.exec.offset}) — рабочая точка, измеренная вне
 *       выборки (док. 113 §5), тогда как {@code revx.sim.offset} остаётся
 *       историческим базисом доков 74–113. Сверять живое надо со ступенью
 *       лестницы отступа, равной {@code revx.exec.offset}.</li>
 * </ul>
 *
 * Расхождение печатается при старте и пишется в журнал: незамеченное, оно через
 * месяц превратится в «модель не сходится с живым».
 *
 * Справедливая цена читается из базы стенда, а не опрашивается заново: иначе
 * расхождение можно будет списать на разные данные (док. 89 §4).
 */
@Component
@Lazy
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    private final RevxConfig cfg;
    private final String standDbPath;
    private final String symbol;
    private final double size;
    private final double inventoryCap;
    private final long periodMs;
    private final double offset;
    private final BotTag tag;
    private final String journalPath;
    private final String allocPath;
    private final double skewTarget;
    private final double costFloorMargin;
    private final double widening;
    private final double wideningMaxStep;
    private final double anchorLeash;
    private final double anchorWidening;
    private final boolean ownPosition;
    private final double positionSeed;
    private final double parkDistance;
    private final int levels;
    private final double levelStep;
    private final boolean innerFirst;
    private final Panic panic;

    public Executor(RevxConfig cfg,
                    @Value("${revx.exec.stand-db}") String standDbPath,
                    @Value("${revx.exec.symbol}") String symbol,
                    @Value("${revx.exec.size}") double size,
                    @Value("${revx.exec.inventory-cap}") double inventoryCap,
                    @Value("${revx.exec.period-ms}") long periodMs,
                    @Value("${revx.exec.offset}") double offset,
                    @Value("${revx.exec.bot-id}") String botId,
                    @Value("${revx.exec.journal}") String journalPath,
                    @Value("${revx.exec.alloc}") String allocPath,
                    @Value("${revx.exec.skew-target}") double skewTarget,
                    @Value("${revx.exec.cost-floor-margin}") double costFloorMargin,
                    @Value("${revx.exec.widening}") double widening,
                    @Value("${revx.exec.widening-max-step}") double wideningMaxStep,
                    @Value("${revx.exec.anchor-leash}") double anchorLeash,
                    @Value("${revx.exec.anchor-widening}") double anchorWidening,
                    @Value("${revx.exec.own-position}") boolean ownPosition,
                    @Value("${revx.exec.position-seed}") double positionSeed,
                    @Value("${revx.exec.park-distance}") double parkDistance,
                    @Value("${revx.exec.levels}") int levels,
                    @Value("${revx.exec.level-step}") double levelStep,
                    @Value("${revx.exec.inner-first}") boolean innerFirst,
                    Panic panic) {
        this.cfg = cfg;
        this.standDbPath = standDbPath;
        this.symbol = symbol;
        this.size = size;
        this.inventoryCap = inventoryCap;
        this.periodMs = periodMs;
        this.offset = offset;
        this.tag = new BotTag(botId);
        this.journalPath = journalPath;
        this.allocPath = allocPath;
        this.skewTarget = skewTarget;
        this.costFloorMargin = costFloorMargin;
        this.widening = widening;
        this.wideningMaxStep = wideningMaxStep;
        this.anchorLeash = anchorLeash;
        this.anchorWidening = anchorWidening;
        this.ownPosition = ownPosition;
        this.positionSeed = positionSeed;
        this.parkDistance = parkDistance;
        this.levels = levels;
        this.levelStep = levelStep;
        this.innerFirst = innerFirst;
        this.panic = panic;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        ExecJournal journal = new ExecJournal(journalPath);
        // Реестр владения ОБЩИЙ для всех ботов машины: счёт у площадки один,
        // субсчетов нет (проверено 04.09.2026), значит разделять инвентарь
        // приходится нам. Путь намеренно вне каталога бота.
        AllocRegistry alloc = new AllocRegistry(allocPath);
        StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                        cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs());
        TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);

        // Спецификация пары читается У ПЛОЩАДКИ, а не берётся из констант: шаги
        // цены и количества у каждой пары свои. Нет пары в каталоге — НЕ
        // ЗАПУСКАЕМСЯ: подставить чужие шаги хуже, чем упасть, потому что
        // заявка уйдёт с неверной точностью и узнаем мы об этом на живых деньгах.
        this.spec = stand.spec(symbol);
        if (spec == null) {
            throw new IllegalStateException("нет спецификации пары " + symbol
                    + " в каталоге стенда — запускаться нельзя");
        }
        log.warn("спецификация {}: шаг цены {}, шаг количества {}, минимум заявки {}",
                symbol, spec.quoteStep(), spec.baseStep(), spec.minNotional());

        // Скос, порог и всё остальное — из конфига симуляции, чтобы живое и
        // посчитанное отличались ровно одним: реальностью исполнения.
        //
        // Отступ — единственное исключение. `revx.sim.offset` остаётся историческим
        // базисом доков 74-113, а живое стоит на рабочей точке, измеренной ВНЕ
        // ВЫБОРКИ (док. 113 §5). Сверять живое надо со ступенью лестницы, равной
        // `revx.exec.offset`, а не с базовым прогоном симуляции.
        Quoter.Params params = buildParams();
        QuotePolicy policy = buildPolicy(params);
        QuoteLoop loop = new QuoteLoop(client, Clock.system(), stand, journal, params, symbol,
                periodMs, minNotional(), tag, policy, ownPosition, positionSeed, spec.baseStep(),
                parkDistance, alloc, levels, levelStep, innerFirst);
        runLive(loop, journal, client, stand, alloc);
    }

    /**
     * Запись версии в событии {@code boot}: человеческая строка и следом, через
     * {@code |}, машинная.
     *
     * ⚠️ Машинная часть появилась потому, что повтор брал параметры из
     * {@code application.properties}, а живому боту половина приходит из
     * systemd-юнита: {@code skew-target} 0.3 против 0.0, {@code park-distance}
     * 0.10 против −1. Из-за нулевого скоса повтор котировал чистый отступ
     * 10 б.п. там, где живой стоял на 5.7, и сверка сравнивала не логику бота, а
     * две разные настройки. Настройки обязаны ехать вместе с записью, а не
     * подбираться к ней задним числом.
     *
     * Человеческая часть остаётся первой: её печатает {@code /pnl}.
     */
    String bootDetail() {
        return String.format(java.util.Locale.ROOT,
                "%s, лот %s, отступ %.1f б.п.%s, скос k=%.4f, цель %.0f%% потолка | %s",
                // valueOf, а не new BigDecimal(double): конструктор печатает
                // точное двоичное разложение (0.0000125000000000000000108…).
                symbol, java.math.BigDecimal.valueOf(size).stripTrailingZeros().toPlainString(),
                offset * 10_000,
                levels > 1 ? String.format(java.util.Locale.ROOT, " × %d уровня шагом %.1f (%s)",
                        levels, levelStep * 10_000, innerFirst ? "от ближнего" : "от дальнего")
                        : "",
                cfg.simSkewK(), skewTarget * 100, bootJson());
    }

    private String bootJson() {
        return String.format(java.util.Locale.ROOT,
                "{\"symbol\":\"%s\",\"botId\":\"%s\",\"size\":%s,\"inventoryCap\":%s,"
                        + "\"offset\":%s,\"skewK\":%s,\"skewTarget\":%s,\"periodMs\":%d,"
                        + "\"minNotional\":%s,\"baseStep\":%s,\"quoteStep\":%s,"
                        + "\"parkDistance\":%s,\"costFloorMargin\":%s,\"anchorLeash\":%s,"
                        + "\"widening\":%s,\"wideningMaxStep\":%s,\"anchorWidening\":%s,"
                        + "\"ownPosition\":%b,\"levels\":%d,\"levelStep\":%s,"
                        + "\"innerFirst\":%b}",
                symbol, tag.id(), num(size), num(inventoryCap), num(offset),
                num(cfg.simSkewK()), num(skewTarget), periodMs, num(minNotional()),
                num(spec.baseStep()), num(quoteStep()), num(parkDistance),
                num(costFloorMargin), num(anchorLeash), num(widening),
                num(wideningMaxStep), num(anchorWidening), ownPosition,
                levels, num(levelStep), innerFirst);
    }

    private static String num(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /**
     * Параметры котирования. Отдельный метод не ради красоты: ровно эти же числа
     * обязан получить повтор ({@code --revx-replay}), иначе сверка «один в один»
     * сравнивает не логику бота, а две разные настройки.
     */
    private Quoter.Params buildParams() {
        return new Quoter.Params(offset, size, inventoryCap,
                cfg.simSkewK(), skewTarget, cfg.simDriftBeta(), cfg.simBuySizeRatio(),
                cfg.simDriftWindowMs(), cfg.simSizeShapeEta(), cfg.simDriftGateEr(),
                cfg.simErWindowMs(), cfg.simErSampleMs(), cfg.simStopDrawdownPct(),
                Quoter.Sticky.OFF, Quoter.Frozen.OFF, Quoter.Hedge.OFF, cfg.simStopCoolOffMs(),
                cfg.simRequoteThreshold(), quoteStep());
    }

    /**
     * {@code --revx-replay --journal=<путь>}: прогнать записанную сессию через
     * тот же котировщик и сверить котировки тик в тик.
     *
     * Сети не касается вовсе: площадкой служит
     * {@link org.home.data.revx.replay.ReplayVenue}, ценой — записанная в
     * {@code exec_quote}, исполнениями — записанные в {@code exec_fill}.
     * Спецификация пары читается у стенда тем же вызовом, что и в живом режиме:
     * шаги цены и количества входят в округление котировки, и подставить сюда
     * другие значит гарантированно разойтись.
     */
    public void replay(String journalPath, String fillModel) {
        try (StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                        cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs())) {
            this.spec = stand.spec(symbol);
            if (spec == null) {
                throw new IllegalStateException("нет спецификации пары " + symbol);
            }
            long boot = ReplayRunner.lastBoot(journalPath);
            if (boot == 0) {
                throw new IllegalStateException("в журнале нет события boot — "
                        + "нечего считать чистым окном");
            }
            var ticks = ReplayRunner.readTicks(journalPath, boot);
            var fills = ReplayRunner.readFills(journalPath, boot);
            log.warn("повтор {}: тиков {}, исполнений {}, с {}", symbol, ticks.size(),
                    fills.size(), java.time.Instant.ofEpochMilli(boot));

            // ⚠️ Настройки берём ИЗ ЖУРНАЛА, а не из окружения. Живому боту
            // половина приходит из systemd-юнита, и подстановка умолчаний
            // однажды уже превратила сверку в сравнение двух разных настроек.
            org.home.data.revx.replay.BootParams bp =
                    org.home.data.revx.replay.BootParams.parse(
                            ReplayRunner.lastBootDetail(journalPath));
            if (bp == null) {
                throw new IllegalStateException("в событии boot нет машинной части — "
                        + "запись сделана до 05.09.2026. Подставлять окружение нельзя: "
                        + "именно так сверка и врала. Нужен свежий журнал.");
            }
            log.warn("настройки из журнала: отступ {} б.п., скос k={}, цель {}%, "
                            + "лот {}, потолок {}, отвод {}",
                    bp.offset() * 10_000, bp.skewK(), bp.skewTarget() * 100,
                    bp.size(), bp.inventoryCap(), bp.parkDistance());

            Quoter.Params params = new Quoter.Params(bp.offset(), bp.size(),
                    bp.inventoryCap(), bp.skewK(), bp.skewTarget(), cfg.simDriftBeta(),
                    cfg.simBuySizeRatio(), cfg.simDriftWindowMs(), cfg.simSizeShapeEta(),
                    cfg.simDriftGateEr(), cfg.simErWindowMs(), cfg.simErSampleMs(),
                    cfg.simStopDrawdownPct(), Quoter.Sticky.OFF, Quoter.Frozen.OFF,
                    Quoter.Hedge.OFF, cfg.simStopCoolOffMs(), cfg.simRequoteThreshold(),
                    bp.quoteStep());
            QuotePolicy policy = buildPolicy(params, bp.costFloorMargin(), bp.anchorLeash(),
                    bp.anchorWidening(), bp.widening(), bp.wideningMaxStep(), bp.size(),
                    bp.inventoryCap(), bp.quoteStep());
            // Модель исполнения — ЕДИНСТВЕННАЯ неизвестная стенда. Всё остальное
            // проверено повтором: при верных исполнениях котировки сходятся с
            // живым на 99.92%. Поэтому моделей три, и у каждой своя роль.
            org.home.data.revx.replay.FillModel model = switch (fillModel) {
                case "recorded" -> new org.home.data.revx.replay.RecordedFillModel(fills,
                        org.home.data.revx.replay.RecordedFillModel.DETECTION_LAG_MS);
                case "touch" -> new org.home.data.revx.replay.TouchFillModel(
                        org.home.data.revx.replay.MarketData.load(standDbPath, bp.symbol(),
                                ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs()));
                case "market" -> new org.home.data.revx.replay.MarketFillModel(
                        org.home.data.revx.replay.MarketData.load(standDbPath, bp.symbol(),
                                ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs()));
                default -> throw new IllegalArgumentException(
                        "неизвестная модель исполнения: " + fillModel
                                + " (recorded | market | touch)");
            };
            log.warn("модель исполнения: {}", model.describe());

            ReplayRunner.Result result = ReplayRunner.run(ticks, fills, params, policy,
                    bp.symbol(), bp.periodMs(), bp.minNotional(), bp.botId(),
                    bp.baseStep(), bp.parkDistance(),
                    bp.inventoryCap() * 1.2 * ticks.get(0).fair(),
                    model, bp.levels(), bp.levelStep(), bp.innerFirst(), 0);
            log.info("\n{}", ReplayRunner.render(result));
        } catch (Exception e) {
            log.error("повтор не прошёл: {}", e.toString(), e);
        }
    }

    /**
     * {@code --revx-forecast --journal=<путь> --offsets=10,14}: что будет, если
     * котировать с другими отступами.
     *
     * Все боты гоняются РАЗОМ по одной книге: поток на площадке конечен (за
     * 17 часов на BTC/USDC прошло 314 сделок), и по одному их сравнивать
     * бессмысленно — каждому достанется весь поток.
     *
     * ⚠️ Результат печатается по ДВУМ моделям: рабочей и заведомо завышенной.
     * Проверить прогноз записью нельзя по определению — такого бота не было, —
     * поэтому одно число здесь обманывает, а вилка нет.
     */
    public void forecast(String journalPath, String offsets, boolean shareCap,
                         String from, String to, int levels, double levelStepBp,
                         double sizeMult, boolean innerFirst) {
        try (StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                        cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs())) {
            long boot = ReplayRunner.lastBoot(journalPath);
            var bp = org.home.data.revx.replay.BootParams.parse(
                    ReplayRunner.lastBootDetail(journalPath));
            if (bp == null) {
                throw new IllegalStateException("в событии boot нет машинной части");
            }
            // Окно можно задать явно. Падающий отрезок лежит ГЛУБЖЕ последнего
            // запуска — в журнале бота A 600 884 тика с 27.08, а не 61 593 с
            // последнего boot, — и проверять поведение накопленного инвентаря
            // надо именно там, где потолок связывает.
            long fromMs = from == null || from.isBlank() ? boot
                    : java.time.Instant.parse(from).toEpochMilli();
            long toMs = to == null || to.isBlank() ? Long.MAX_VALUE
                    : java.time.Instant.parse(to).toEpochMilli();
            var ticks = ReplayRunner.readTicks(journalPath, fromMs, toMs);
            if (ticks.isEmpty()) {
                throw new IllegalStateException("в журнале нет тиков в этом окне");
            }
            log.warn("окно: {} → {}, цена {} → {} ({}%)",
                    java.time.Instant.ofEpochMilli(ticks.get(0).tsMs()),
                    java.time.Instant.ofEpochMilli(ticks.get(ticks.size() - 1).tsMs()),
                    Math.round(ticks.get(0).fair()),
                    Math.round(ticks.get(ticks.size() - 1).fair()),
                    Math.round((ticks.get(ticks.size() - 1).fair()
                            / ticks.get(0).fair() - 1) * 10000) / 100.0);
            List<org.home.data.revx.replay.Forecast.BotSpec> bots = new java.util.ArrayList<>();
            String[] parts = offsets.split(",");
            for (int i = 0; i < parts.length; i++) {
                double bpOffset = Double.parseDouble(parts[i].trim());
                bots.add(new org.home.data.revx.replay.Forecast.BotSpec(
                        String.valueOf((char) ('a' + i)), bpOffset / 10_000, bp.skewTarget(),
                        shareCap ? bp.inventoryCap() / parts.length : bp.inventoryCap(),
                        levels, levelStepBp / 10_000, bp.size() * sizeMult, innerFirst));
            }
            log.warn("прогноз {}: тиков {}, ботов {}, отступы {} б.п.",
                    bp.symbol(), ticks.size(), bots.size(), offsets
                            + (shareCap ? " (потолок ДЕЛИТСЯ: нормировка на капитал)" : ""));

            StringBuilder out = new StringBuilder();
            for (String name : new String[]{"market", "touch"}) {
                var market = org.home.data.revx.replay.MarketData.load(standDbPath, bp.symbol(),
                        ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs());
                org.home.data.revx.replay.FillModel model = "touch".equals(name)
                        ? new org.home.data.revx.replay.TouchFillModel(market)
                        : new org.home.data.revx.replay.MarketFillModel(market);
                var results = org.home.data.revx.replay.Forecast.run(ticks, model, bp, bots, cfg);
                out.append('\n').append(org.home.data.revx.replay.Forecast.render(
                        model.describe(), results));
                out.append(org.home.data.revx.replay.Forecast.renderDays(
                        model.describe(), results));
            }
            log.info("\n=== Прогноз: вилка по двум моделям исполнения ==={}", out);
        } catch (Exception e) {
            log.error("прогноз не прошёл: {}", e.toString(), e);
        }
    }

    /**
     * {@code --revx-ladder --offsets=8,9,10,...}: лестница отступов.
     *
     * ⚠️ Каждая ступень гоняется ОТДЕЛЬНО, по одному боту. Прогнать всю лестницу
     * разом было бы ответом на другой вопрос: боты отъели бы поток друг у друга
     * (он конечен — 314 сделок за 17 часов), и «лучшим» вышел бы тот, кто просто
     * стоял ближе к рынку. Вопрос «какой отступ лучше для ОДНОГО бота» требует,
     * чтобы на каждой ступени бот был один.
     *
     * Отсюда же считается {@code κ}: частота исполнений падает с отступом как
     * {@code λ(δ) = A·e^{−κδ}}, значит наклон {@code ln λ} по {@code δ} и есть
     * {@code −κ}. Живьём эта величина не мерилась ни разу (док. 132 §5), а она
     * задаёт {@code δ* = c + 1/κ} и весь выбор пар.
     */
    public void ladder(String journalPath, String offsets) {
        try {
            long boot = ReplayRunner.lastBoot(journalPath);
            var bp = org.home.data.revx.replay.BootParams.parse(
                    ReplayRunner.lastBootDetail(journalPath));
            if (bp == null) {
                throw new IllegalStateException("в событии boot нет машинной части");
            }
            var ticks = ReplayRunner.readTicks(journalPath, boot);
            var market0 = org.home.data.revx.replay.MarketData.load(standDbPath, bp.symbol(),
                    ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs());
            log.warn("лестница {}: тиков {}, сделок на рынке {}, ступени {} б.п.",
                    bp.symbol(), ticks.size(), market0.tradeCount(), offsets);

            StringBuilder out = new StringBuilder();
            for (String name : new String[]{"market", "touch"}) {
                List<double[]> points = new java.util.ArrayList<>();
                out.append(String.format("%n%-28s | исполнений | реализовано | инвентарь%n",
                        "модель " + name + ", отступ"));
                for (String p : offsets.split(",")) {
                    double bpOffset = Double.parseDouble(p.trim());
                    var market = org.home.data.revx.replay.MarketData.load(standDbPath,
                            bp.symbol(), ticks.get(0).tsMs(),
                            ticks.get(ticks.size() - 1).tsMs());
                    org.home.data.revx.replay.FillModel model = "touch".equals(name)
                            ? new org.home.data.revx.replay.TouchFillModel(market)
                            : new org.home.data.revx.replay.MarketFillModel(market);
                    var r = org.home.data.revx.replay.Forecast.run(ticks, model, bp,
                            List.of(new org.home.data.revx.replay.Forecast.BotSpec(
                                    "a", bpOffset / 10_000, bp.skewTarget(),
                                    bp.inventoryCap(), 1, 0, bp.size(), true)), cfg).get(0);
                    out.append(String.format(java.util.Locale.ROOT,
                            "%25.1f б.п. | %10d | %+11.4f | %8.1f%n",
                            bpOffset, r.fills(), r.realised(), r.inventoryLots()));
                    if (r.fills() > 0) {
                        points.add(new double[]{bpOffset, Math.log(r.fills())});
                    }
                }
                out.append(fitKappa(points));
            }
            log.info("\n=== Лестница отступов: каждая ступень отдельным ботом ==={}", out);
        } catch (Exception e) {
            log.error("лестница не прошла: {}", e.toString(), e);
        }
    }

    /**
     * Наклон {@code ln λ} по отступу. Это и есть {@code −κ} закона прихода.
     *
     * Меньше трёх точек — не считаем: по двум прямая проводится всегда, и число
     * получится, а смысла в нём не будет.
     */
    private static String fitKappa(List<double[]> points) {
        if (points.size() < 3) {
            return "  κ не считаю: точек меньше трёх\n";
        }
        double n = points.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (double[] p : points) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double kappa = -slope;
        return String.format(java.util.Locale.ROOT,
                "  κ = %.4f на б.п. по %d ступеням; 1/κ = %.1f б.п., δ* = c + %.1f б.п.%n",
                kappa, points.size(), kappa > 0 ? 1 / kappa : Double.NaN,
                kappa > 0 ? 1 / kappa : Double.NaN);
    }

    private void runLive(QuoteLoop loop, ExecJournal journal, TradeClient client,
                         StandReader stand, AllocRegistry alloc) {
        log.warn("""

                === МИКРО-LIVE, РЕАЛЬНЫЕ ОРДЕРА ===
                пара {}, размер {} {}, период {} мс
                отступ {} б.п. (базис симуляции {} б.п.), скос {}%
                котирование ВЫКЛЮЧЕНО до команды /start
                {}""", symbol, size, symbol.substring(0, symbol.indexOf('/')), periodMs,
                offset * 10_000, cfg.simOffset() * 10_000, cfg.simSkewK() * 100,
                ExecLimits.describe(tag.id()));
        // Точка отсчёта для «с запуска» в /pnl. Пишем ПАРАМЕТРЫ, а не просто
        // отметку времени: сравнение версий имеет смысл только рядом с тем, чем
        // они отличались — отступом, размером лота и скосом.
        // ⚠️ Отступ берём ЖИВОЙ (revx.exec.offset), а не cfg.simOffset(): они
        // намеренно разные (10 против 14 б.п., док. 113 §5). Скос k живой берёт
        // из конфига симуляции, а вот цель — своя (revx.exec.skew-target), и
        // без неё запись версии не полна: цель и есть то, что крутят.
        journal.event("boot", bootDetail());

        if (offset != cfg.simOffset()) {
            // Расхождение намеренное, но молчать о нём нельзя: иначе через месяц
            // живое сравнят с базовым прогоном и не поймут, почему не сходится.
            log.warn("отступ живого ({} б.п.) НЕ равен базису симуляции ({} б.п.) — "
                    + "сверять со ступенью лестницы {} б.п. (док. 113 §5)",
                    offset * 10_000, cfg.simOffset() * 10_000, offset * 10_000);
            journal.event("offset", "живое " + offset * 10_000 + " б.п., базис симуляции "
                    + cfg.simOffset() * 10_000 + " б.п.");
        }

        Thread loopThread = new Thread(loop, "revx-quote-loop");
        loopThread.setDaemon(false);

        // Паника обязана работать и из бота, и из хука выключения: заявки на
        // бирже переживают наш процесс, и оставить их там нельзя.
        Runnable panicAction = () -> {
            journal.event("panic", "аварийная остановка");
            loop.shutdown();
            panic.run();
            System.exit(0);
        };
        ExecBot bot = ExecBot.fromEnvironment(loop, journal, panicAction);
        // Предохранители должны докрикиваться до человека, а не только до журнала.
        if (bot != null) {
            loop.alertTo(bot::send);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("выключение: снимаю заявки");
            loop.shutdown();
        }, "revx-exec-shutdown"));

        loopThread.start();
        if (bot != null) {
            Thread botThread = new Thread(bot, "revx-exec-bot");
            botThread.setDaemon(true);
            botThread.start();
        }
        try {
            loopThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stand.close();
        journal.close();
    }

    /**
     * Политика котирования этого бота.
     *
     * Бот A — голый {@link Quoter}: цель 0, пола нет, шаг постоянный. Бот B — он же
     * под двумя надстройками, каждая измерена отдельно:
     *
     * <ul>
     *   <li>{@link CostFloorPolicy} — не продавать ниже средней цены входа
     *       (док. 116: на падении убирает −439 реализованного убытка);</li>
     *   <li>{@link WideningBidPolicy} — шаг покупок растёт с набранным
     *       (док. 117: ёмкость падения с 3% до десятков процентов).</li>
     * </ul>
     *
     * Порядок обёрток важен: пол ближе к котировщику, растущий шаг снаружи. Пол
     * трогает только аск, шаг — только бид, поэтому они не конфликтуют, но
     * менять их местами всё равно не надо: внешняя обёртка видит уже исправленный
     * аск, а не сырой.
     */
    private QuotePolicy buildPolicy(Quoter.Params params) {
        return buildPolicy(params, costFloorMargin, anchorLeash, anchorWidening,
                widening, wideningMaxStep, size, inventoryCap, quoteStep());
    }

    /**
     * ⚠️ Все ручки — аргументами, а не полями. Повтор собирает политику из
     * настроек, записанных в журнале, и брать их из окружения он не имеет права:
     * именно так сверка однажды сравнила не логику бота, а две разные настройки.
     */
    public static QuotePolicy buildPolicy(Quoter.Params params, double costFloorMargin,
                                   double anchorLeash, double anchorWidening,
                                   double widening, double wideningMaxStep,
                                   double size, double inventoryCap, double quoteStep) {
        QuotePolicy policy = new Quoter(params);
        if (costFloorMargin >= 0) {
            policy = new CostFloorPolicy(policy, costFloorMargin, quoteStep);
        }
        // Поводок и растущий шаг решают ОДНУ задачу — пережить падение, — и
        // ставить их вместе бессмысленно: оба двигают бид вниз, и разложить
        // результат потом будет нельзя. Поводок измерен лучше по всем колонкам
        // (док. 119 §5), поэтому при заданном поводке растущий шаг не ставится.
        if (anchorLeash >= 0) {
            policy = new AnchoredBidPolicy(policy, params.offset(), anchorWidening,
                    params.offset(), anchorLeash, size, inventoryCap, quoteStep);
        } else if (widening > 0) {
            policy = new WideningBidPolicy(policy, params.offset(), widening,
                    wideningMaxStep, size, inventoryCap, quoteStep);
        }
        return policy;
    }

    /**
     * Шаг цены пары. Берётся из спецификации, а не угадывается: округление не по
     * шагу — прямой путь к отказу постановки (ТЗ §4.6 п.6).
     */
    /**
     * Спецификация торгуемой пары, прочитанная у площадки при запуске.
     *
     * До 04.09.2026 шаги были зашиты под BTC/USDC константами прямо здесь, и
     * запустить бота на другой паре было нельзя: у SOL шаг цены 0.001 против
     * 0.01, шаг количества 1e-6 против 1e-8.
     */
    private StandReader.PairSpec spec;

    private double quoteStep() {
        return spec.quoteStep();
    }

    /**
     * Минимальный номинал заявки. Связывает не {@code min_order_size} (1e-8 BTC,
     * пренебрежимо), а {@code min_order_size_quote} — 0.1 USDC. Наш лот примерно
     * вдесятеро больше, но остаток от частичного исполнения бывает мельче, и
     * стучаться с ним в площадку значит тратить суточный лимит постановок на
     * гарантированные отказы.
     */
    private double minNotional() {
        return spec.minNotional();
    }
}
