package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
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
    private final double skewTarget;
    private final double costFloorMargin;
    private final double widening;
    private final double wideningMaxStep;
    private final double anchorLeash;
    private final double anchorWidening;
    private final boolean ownPosition;
    private final double positionSeed;
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
                    @Value("${revx.exec.skew-target}") double skewTarget,
                    @Value("${revx.exec.cost-floor-margin}") double costFloorMargin,
                    @Value("${revx.exec.widening}") double widening,
                    @Value("${revx.exec.widening-max-step}") double wideningMaxStep,
                    @Value("${revx.exec.anchor-leash}") double anchorLeash,
                    @Value("${revx.exec.anchor-widening}") double anchorWidening,
                    @Value("${revx.exec.own-position}") boolean ownPosition,
                    @Value("${revx.exec.position-seed}") double positionSeed,
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
        this.skewTarget = skewTarget;
        this.costFloorMargin = costFloorMargin;
        this.widening = widening;
        this.wideningMaxStep = wideningMaxStep;
        this.anchorLeash = anchorLeash;
        this.anchorWidening = anchorWidening;
        this.ownPosition = ownPosition;
        this.positionSeed = positionSeed;
        this.panic = panic;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        ExecJournal journal = new ExecJournal(journalPath);
        StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                        cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs());
        TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);

        // Скос, порог и всё остальное — из конфига симуляции, чтобы живое и
        // посчитанное отличались ровно одним: реальностью исполнения.
        //
        // Отступ — единственное исключение. `revx.sim.offset` остаётся историческим
        // базисом доков 74-113, а живое стоит на рабочей точке, измеренной ВНЕ
        // ВЫБОРКИ (док. 113 §5). Сверять живое надо со ступенью лестницы, равной
        // `revx.exec.offset`, а не с базовым прогоном симуляции.
        Quoter.Params params = new Quoter.Params(offset, size, inventoryCap,
                cfg.simSkewK(), skewTarget, cfg.simDriftBeta(), cfg.simBuySizeRatio(),
                cfg.simDriftWindowMs(), cfg.simSizeShapeEta(), cfg.simDriftGateEr(),
                cfg.simErWindowMs(), cfg.simErSampleMs(), cfg.simStopDrawdownPct(),
                Quoter.Sticky.OFF, Quoter.Frozen.OFF, cfg.simStopCoolOffMs(),
                cfg.simRequoteThreshold(), quoteStep());
        QuotePolicy policy = buildPolicy(params);
        QuoteLoop loop = new QuoteLoop(client, stand, journal, params, symbol, periodMs,
                minNotional(), tag, policy, ownPosition, positionSeed);

        log.warn("""

                === МИКРО-LIVE, РЕАЛЬНЫЕ ОРДЕРА ===
                пара {}, размер {} {}, период {} мс
                отступ {} б.п. (базис симуляции {} б.п.), скос {}%
                котирование ВЫКЛЮЧЕНО до команды /start
                {}""", symbol, size, symbol.substring(0, symbol.indexOf('/')), periodMs,
                offset * 10_000, cfg.simOffset() * 10_000, cfg.simSkewK() * 100,
                ExecLimits.describe());
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
        QuotePolicy policy = new Quoter(params);
        if (costFloorMargin >= 0) {
            policy = new CostFloorPolicy(policy, costFloorMargin, quoteStep());
        }
        // Поводок и растущий шаг решают ОДНУ задачу — пережить падение, — и
        // ставить их вместе бессмысленно: оба двигают бид вниз, и разложить
        // результат потом будет нельзя. Поводок измерен лучше по всем колонкам
        // (док. 119 §5), поэтому при заданном поводке растущий шаг не ставится.
        if (anchorLeash >= 0) {
            policy = new AnchoredBidPolicy(policy, params.offset(), anchorWidening,
                    params.offset(), anchorLeash, size, inventoryCap, quoteStep());
        } else if (widening > 0) {
            policy = new WideningBidPolicy(policy, params.offset(), widening,
                    wideningMaxStep, size, inventoryCap, quoteStep());
        }
        return policy;
    }

    /**
     * Шаг цены пары. Берётся из спецификации, а не угадывается: округление не по
     * шагу — прямой путь к отказу постановки (ТЗ §4.6 п.6).
     */
    private double quoteStep() {
        return 0.01;                     // BTC/USDC: проверено в revx_pair
    }

    /**
     * Минимальный номинал заявки. Связывает не {@code min_order_size} (1e-8 BTC,
     * пренебрежимо), а {@code min_order_size_quote} — 0.1 USDC. Наш лот примерно
     * вдесятеро больше, но остаток от частичного исполнения бывает мельче, и
     * стучаться с ним в площадку значит тратить суточный лимит постановок на
     * гарантированные отказы.
     */
    private double minNotional() {
        return 0.1;                      // BTC/USDC: проверено в revx_pair
    }
}
