package org.home.data.cli;

import org.home.data.collectors.Collector;
import org.home.data.collectors.FundingCollector;
import org.home.data.collectors.OhlcvCollector;
import org.home.data.collectors.OiArchiveImporter;
import org.home.data.collectors.OnchainCollector;
import org.home.data.detector.RegimeDetector;
import org.home.data.ws.LiquidationWsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final LiquidationWsCollector liquidations;
    private final List<String> okxInstruments;
    private final List<String> defaultSymbols;

    public CliRunner(ConfigurableApplicationContext context, CliMode mode,
                     List<Collector> collectors, OhlcvCollector ohlcv, FundingCollector funding,
                     OnchainCollector onchain, OiArchiveImporter oiArchive,
                     RegimeDetector detector, LiquidationWsCollector liquidations,
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
        this.liquidations = liquidations;
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
            default -> throw new IllegalArgumentException(
                    "Неизвестная цель backfill: " + target
                            + " (ohlcv | funding-okx | onchain | oi-archive | regime)");
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

    private static String firstOr(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }
}
