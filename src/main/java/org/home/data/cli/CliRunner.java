package org.home.data.cli;

import org.home.data.collectors.Collector;
import org.home.data.collectors.FundingCollector;
import org.home.data.collectors.OhlcvCollector;
import org.home.data.collectors.OiArchiveImporter;
import org.home.data.collectors.OnchainCollector;
import org.home.data.detector.RegimeDetector;
import org.home.data.detector.RegimeDetectorV2;
import org.home.data.detector.RegimeDetectorV3;
import org.home.data.detector.RegimeDetectorV5;
import org.home.data.detector.RegimeReport;
import org.home.data.eval.AllocationProxy;
import org.home.data.eval.S1Backtest;
import org.home.data.eval.bench.Bench;
import org.home.data.revx.RevxCommands;
import org.home.data.ws.LiquidationWsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * CLI поверх коллекторов.
 *
 * Примеры:
 *   ./gradlew bootRun                                   — режим планировщика + WS-ликвидации
 *   ./gradlew bootRun --args='--collect=funding,oi'     — разовый сбор и выход
 *   ./gradlew bootRun --args='--collect=all'
 *   ./gradlew bootRun --args='--backfill=ohlcv --symbols=BTCUSDT --interval=1m --from=2023-01-01'
 *   ./gradlew bootRun --args='--backfill=funding-okx'
 *   ./gradlew bootRun --args='--backfill=onchain --from=2015-07-01'
 *   ./gradlew bootRun --args='--revx-pairs'                       — стенд Revolut X: каталог пар и вселенная
 *   ./gradlew bootRun --args='--revx-probe-limits --max-rps=16'   — стенд: эмпирический потолок запросов
 *   ./gradlew bootRun --args='--revx-collect=once'                — стенд: разовый обход книг и сделок
 *   ./gradlew bootRun --args='--revx-collect'                     — стенд: непрерывный сбор (демон)
 *   ./gradlew bootRun --args='--revx-basis --hours=6'             — стенд: курс USDC/USD и пригодность пар
 *   ./gradlew bootRun --args='--revx-sim --symbol=BTC/USDC --hours=123 --to=2026-08-25T03:00:00Z'
 *   ./gradlew bootRun --args='--revx-flow --symbol=BTC/USDC --hours=123'  — кто по ту сторону книги
 *   ./gradlew bootRun --args='--revx-trade-check'  — проверка ТОРГОВОГО ключа, без ордеров
 */
@Component
public class CliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final ConfigurableApplicationContext context;
    private final CliMode mode;
    private final List<Collector> collectors;
    private final OhlcvCollector ohlcv;
    private final FundingCollector funding;
    private final OnchainCollector onchain;
    private final OiArchiveImporter oiArchive;
    private final RegimeDetector detector;
    private final RegimeDetectorV2 detectorV2;
    private final RegimeDetectorV3 detectorV3;
    private final RegimeDetectorV5 detectorV5;
    private final RegimeReport report;
    private final AllocationProxy allocationProxy;
    private final Bench bench;
    private final S1Backtest s1Backtest;
    private final LiquidationWsCollector liquidations;
    /** Стенд Revolut X — через провайдер: в режиме планировщика его бины не создаются. */
    private final ObjectProvider<RevxCommands> revx;
    /** Счётные стенды теории оптимальности (ТЗ 65–68) — тоже через провайдер, data/theory.db лениво. */
    private final ObjectProvider<org.home.data.theory.TheoryCommands> theory;
    /** Проверка торгового ключа — тоже лениво: без переменных окружения бин упадёт. */
    private final ObjectProvider<org.home.data.revx.exec.TradeCheck> tradeCheck;
    private final List<String> okxInstruments;
    private final List<String> defaultSymbols;

    public CliRunner(ConfigurableApplicationContext context, CliMode mode,
                     List<Collector> collectors, OhlcvCollector ohlcv, FundingCollector funding,
                     OnchainCollector onchain, OiArchiveImporter oiArchive,
                     RegimeDetector detector, RegimeDetectorV2 detectorV2, RegimeDetectorV3 detectorV3,
                     RegimeDetectorV5 detectorV5,
                     RegimeReport report, AllocationProxy allocationProxy, Bench bench, S1Backtest s1Backtest,
                     LiquidationWsCollector liquidations,
                     ObjectProvider<RevxCommands> revx,
                     ObjectProvider<org.home.data.theory.TheoryCommands> theory,
                     ObjectProvider<org.home.data.revx.exec.TradeCheck> tradeCheck,
                     @Value("${collectors.okx-instruments}") List<String> okxInstruments,
                     @Value("${collectors.symbols}") List<String> defaultSymbols) {
        this.context = context;
        this.mode = mode;
        this.collectors = collectors;
        this.ohlcv = ohlcv;
        this.funding = funding;
        this.onchain = onchain;
        this.oiArchive = oiArchive;
        this.detector = detector;
        this.detectorV2 = detectorV2;
        this.detectorV3 = detectorV3;
        this.detectorV5 = detectorV5;
        this.report = report;
        this.allocationProxy = allocationProxy;
        this.bench = bench;
        this.s1Backtest = s1Backtest;
        this.liquidations = liquidations;
        this.revx = revx;
        this.theory = theory;
        this.tradeCheck = tradeCheck;
        this.okxInstruments = okxInstruments;
        this.defaultSymbols = defaultSymbols;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mode.isScheduleMode()) {
            liquidations.start();
            log.info("Режим планировщика: {} коллекторов + WS-ликвидации", collectors.size());
            return;
        }
        int exitCode = 0;
        try {
            if (args.containsOption("collect")) {
                exitCode = runCollect(first(args, "collect"));
            }
            if (args.containsOption("backfill")) {
                runBackfill(first(args, "backfill"), args);
            }
            if (args.containsOption("s5-dry-run")) {
                var dry = new org.home.data.trade.S5DryRun("telegram/s5_bot.txt");
                if ("once".equals(firstOr(args, "s5-dry-run", "live"))) dry.digestOnce();
                else dry.runDaemon();                       // блокирует: интерактивный демон
            }
            if (args.containsOption("s5-kraken-check")) {
                org.home.data.trade.S5KrakenCheck.run("kraken/keys.txt");   // read-only, без ордеров
            }
            if (args.containsOption("s5-demo")) {
                org.home.data.trade.S5Demo.run("telegram/s5_bot.txt");      // показ цикла в Telegram, без ордеров
            }
            if (args.containsOption("s5-live")) {
                new org.home.data.trade.S5Live("telegram/s5_bot.txt", "kraken/keys.txt").run();  // РЕАЛЬНЫЕ ордера
            }
            if (args.containsOption("revx-pairs")) {
                revx.getObject().pairs();
            }
            if (args.containsOption("revx-collect")) {
                // без значения — демон (блокирует), =once — разовый обход и выход
                revx.getObject().collect("once".equals(firstOr(args, "revx-collect", "daemon")));
            }
            if (args.containsOption("revx-basis")) {
                revx.getObject().basis(
                        Integer.parseInt(firstOr(args, "hours", "24")),
                        Long.parseLong(firstOr(args, "bucket-seconds", "5")) * 1000L,
                        java.time.Instant.parse(firstOr(args, "to",
                                java.time.Instant.now().toString())).toEpochMilli(),
                        firstOr(args, "out", "reports/revx_basis.md"));
            }
            if (args.containsOption("revx-sim")) {
                revx.getObject().simulate(
                        firstOr(args, "symbol", "ETH/USDC"),
                        Integer.parseInt(firstOr(args, "hours", "24")),
                        // --to=2026-08-25T03:00:00Z — окно, кончающееся не «сейчас»
                        java.time.Instant.parse(firstOr(args, "to",
                                java.time.Instant.now().toString())).toEpochMilli(),
                        firstOr(args, "out", "reports/revx_sim.md"));
            }
            if (args.containsOption("revx-trade-check")) {
                tradeCheck.getObject().run();
            }
            if (args.containsOption("revx-flow")) {
                revx.getObject().flow(
                        firstOr(args, "symbol", "BTC/USDC"),
                        Integer.parseInt(firstOr(args, "hours", "24")),
                        java.time.Instant.parse(firstOr(args, "to",
                                java.time.Instant.now().toString())).toEpochMilli(),
                        firstOr(args, "out", "reports/revx_flow.md"));
            }
            if (args.containsOption("revx-probe-limits")) {
                revx.getObject().probeLimits(new org.home.data.revx.RateLimitProbe.Ladder(
                        doubleOrNull(args, "start-rps"), doubleOrNull(args, "step-rps"),
                        doubleOrNull(args, "max-rps"), intOrNull(args, "dwell")));
            }
            if (args.containsOption("theory")) {
                theory.getObject().run(first(args, "theory"), firstOr(args, "out", "reports/theory"));
            }
            if (args.containsOption("report")) {
                String target = first(args, "report");
                switch (target) {
                    case "regime" -> report.generate(firstOr(args, "out", "regime-report.html"));
                    case "regime-v3" -> report.generateV3(firstOr(args, "out", "regime-v3-report.html"));
                    case "regime-v5" -> report.generateV5(firstOr(args, "out", "regime-v5-report.html"));
                    case "regime-all" -> report.generateAll(firstOr(args, "out", "regime-all.html"));
                    case "regime-compare" ->
                            report.generateCompare(firstOr(args, "out", "regime-compare.html"));
                    case "regime-index" -> report.generateIndex(firstOr(args, "out", "reports/index.html"));
                    case "regime-dash" -> report.generateDash(firstOr(args, "out", "reports"));
                    case "crash-econ" ->
                            allocationProxy.run(firstOr(args, "out", "reports/crash_econ.md"));
                    case "crash-maxdd" ->
                            allocationProxy.maxddCheck(firstOr(args, "out", "reports/maxdd_check.md"));
                    case "regime-econ" ->
                            allocationProxy.econOf(firstOr(args, "table", "regime_daily_v3"));
                    case "crossmarket" ->
                            allocationProxy.crossMarket(firstOr(args, "out", "reports/crossmarket.md"));
                    case "voltarget" ->
                            allocationProxy.voltargetPostmortem(firstOr(args, "out", "reports/voltarget_postmortem.md"));
                    case "bench" -> bench.run(firstOr(args, "out", "reports/bench"));
                    case "ensemble" ->
                            allocationProxy.ensembleRun(firstOr(args, "out", "reports/ensemble.md"));
                    case "slope-gate-a" ->
                            allocationProxy.slopeGateA(firstOr(args, "out", "reports/slope_gate_a.md"));
                    case "slope-gate-b" ->
                            allocationProxy.slopeGateB(firstOr(args, "out", "reports/slope_gate_b.md"));
                    case "s3-viability" ->
                            allocationProxy.s3Viability(firstOr(args, "out", "reports/s3_viability.md"));
                    case "s1" -> s1Backtest.run(firstOr(args, "out", "reports/s1_backtest.md"));
                    case "s1-v2" -> s1Backtest.runV2(firstOr(args, "out", "reports/s1_backtest_v2.md"));
                    case "s1-gate" -> s1Backtest.gateTest(firstOr(args, "out", "reports/s1_gate.md"));
                    case "leverage" -> s1Backtest.leverageStudy(firstOr(args, "out", "reports/leverage_warning_study.md"));
                    case "leverage-v2" -> s1Backtest.leverageStudyV2(firstOr(args, "out", "reports/leverage_warning_study_v2.md"));
                    default -> throw new IllegalArgumentException(
                            "Неизвестный отчёт: " + target
                                    + " (regime | regime-v3 | regime-v5 | regime-all | regime-compare | regime-index"
                                    + " | regime-dash | crash-econ | crash-maxdd"
                                    + " | regime-econ | crossmarket | voltarget | bench | ensemble"
                                    + " | slope-gate-a | slope-gate-b | s3-viability)");
                }
            }
        } catch (Exception e) {
            log.error("Команда завершилась ошибкой", e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /**
     * Изоляция сбоев (док. 01 §3, принцип 3 CLAUDE.md): падение одного
     * источника логируется и не мешает собрать остальные. Возвращает 1,
     * если хотя бы один коллектор упал.
     */
    private int runCollect(String names) {
        int failed = 0;
        for (Collector collector : collectors) {
            if ("all".equals(names) || List.of(names.split(",")).contains(collector.name())) {
                log.info("collect {} ...", collector.name());
                try {
                    collector.collect();
                } catch (Exception e) {
                    failed++;
                    log.error("collect {} упал: {}", collector.name(), e.toString());
                }
            }
        }
        return failed == 0 ? 0 : 1;
    }

    private void runBackfill(String target, ApplicationArguments args) {
        switch (target) {
            case "ohlcv" -> {
                String interval = firstOr(args, "interval", "1d");
                long from = parseDateMs(firstOr(args, "from", "2019-01-01"));
                String symbolsArg = firstOr(args, "symbols", null);
                List<String> list = symbolsArg != null ? List.of(symbolsArg.split(",")) : defaultSymbols;
                for (String symbol : list) {
                    ohlcv.backfill(symbol, interval, from);
                }
            }
            case "funding-okx" -> {
                for (String instId : okxInstruments) {
                    funding.backfillOkx(instId);
                }
            }
            case "onchain" -> onchain.backfill(firstOr(args, "from", "2015-01-01"));
            case "oi-archive" -> {
                String symbolsArg = firstOr(args, "symbols", null);
                List<String> list = symbolsArg != null ? List.of(symbolsArg.split(",")) : defaultSymbols;
                oiArchive.backfill(list, firstOr(args, "from", "2021-01-01"));
            }
            case "regime" -> detector.backfill(firstOr(args, "from", "2020-01-01"));
            case "regime-v2" -> detectorV2.backfill(firstOr(args, "from", "2020-01-01"));
            case "regime-v3" -> detectorV3.backfill(firstOr(args, "from", "2020-01-01"));
            case "regime-v5" -> detectorV5.backfill(firstOr(args, "from", "2020-01-01"));
            default -> throw new IllegalArgumentException(
                    "Неизвестная цель backfill: " + target
                            + " (ohlcv | funding-okx | onchain | oi-archive | regime | regime-v2 | regime-v3 | regime-v5)");
        }
    }

    private static long parseDateMs(String day) {
        return LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static String first(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("--" + name + " требует значение");
        }
        return values.get(0);
    }

    private static Double doubleOrNull(ApplicationArguments args, String name) {
        String v = firstOr(args, name, null);
        return v == null ? null : Double.valueOf(v);
    }

    private static Integer intOrNull(ApplicationArguments args, String name) {
        String v = firstOr(args, name, null);
        return v == null ? null : Integer.valueOf(v);
    }

    private static String firstOr(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }
}
