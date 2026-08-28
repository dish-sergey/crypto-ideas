package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.sim.FairPrice;
import org.home.data.revx.sim.Quoter;
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
 * Параметры котирования берутся из ТОГО ЖЕ конфига, что и симуляция: отступ,
 * скос, порог перевыставления. Отличается только размер — он микроскопический
 * и задаётся отдельно ({@code revx.exec.size}), потому что мерить надо
 * попадание в предсказание, а не P&L.
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

    public Executor(RevxConfig cfg,
                    @Value("${revx.exec.stand-db}") String standDbPath,
                    @Value("${revx.exec.symbol}") String symbol,
                    @Value("${revx.exec.size}") double size,
                    @Value("${revx.exec.inventory-cap}") double inventoryCap,
                    @Value("${revx.exec.period-ms}") long periodMs) {
        this.cfg = cfg;
        this.standDbPath = standDbPath;
        this.symbol = symbol;
        this.size = size;
        this.inventoryCap = inventoryCap;
        this.periodMs = periodMs;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        ExecJournal journal = new ExecJournal("state/exec.db");
        StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                        cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs());
        TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);

        // Отступ, скос и порог — из конфига симуляции, чтобы живое и посчитанное
        // отличались ровно одним: реальностью исполнения.
        Quoter.Params params = new Quoter.Params(cfg.simOffset(), size, inventoryCap,
                cfg.simSkewK(), cfg.simSkewTarget(), cfg.simDriftBeta(), cfg.simBuySizeRatio(),
                cfg.simDriftWindowMs(), cfg.simSizeShapeEta(),
                cfg.simRequoteThreshold(), quoteStep());
        QuoteLoop loop = new QuoteLoop(client, stand, journal, params, symbol, periodMs);

        log.warn("""

                === МИКРО-LIVE, РЕАЛЬНЫЕ ОРДЕРА ===
                пара {}, размер {} {}, отступ {}%, скос {}%, период {} мс
                котирование ВЫКЛЮЧЕНО до команды /start
                {}""", symbol, size, symbol.substring(0, symbol.indexOf('/')),
                cfg.simOffset() * 100, cfg.simSkewK() * 100, periodMs, ExecLimits.describe());

        Thread loopThread = new Thread(loop, "revx-quote-loop");
        loopThread.setDaemon(false);

        // Паника обязана работать и из бота, и из хука выключения: заявки на
        // бирже переживают наш процесс, и оставить их там нельзя.
        Runnable panic = () -> {
            journal.event("panic", "аварийная остановка");
            loop.shutdown();
            new Panic(cfg).run();
            System.exit(0);
        };
        ExecBot bot = ExecBot.fromEnvironment(loop, journal, panic);
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
     * Шаг цены пары. Берётся из спецификации, а не угадывается: округление не по
     * шагу — прямой путь к отказу постановки (ТЗ §4.6 п.6).
     */
    private double quoteStep() {
        return 0.01;                     // BTC/USDC: проверено в revx_pair
    }
}
