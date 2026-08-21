package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CLI-фасад стенда Revolut X. Отдельным классом, чтобы {@link org.home.data.cli.CliRunner}
 * тянул его через ObjectProvider: в режиме планировщика бины стенда не создаются
 * и файл data/revx.db не появляется.
 */
@Component
@Lazy
public class RevxCommands {

    private static final Logger log = LoggerFactory.getLogger(RevxCommands.class);

    private final PairsCatalog catalog;
    private final RateLimitProbe probe;
    private final RevxCollectorDaemon daemon;
    private final org.home.data.revx.sim.BasisReport basisReport;
    private final org.home.data.revx.sim.SimRunner simRunner;
    private final RevxConfig cfg;

    public RevxCommands(PairsCatalog catalog, RateLimitProbe probe, RevxCollectorDaemon daemon,
                        org.home.data.revx.sim.BasisReport basisReport,
                        org.home.data.revx.sim.SimRunner simRunner, RevxConfig cfg) {
        this.catalog = catalog;
        this.probe = probe;
        this.daemon = daemon;
        this.basisReport = basisReport;
        this.simRunner = simRunner;
        this.cfg = cfg;
    }

    /** --revx-collect: демон сбора; --revx-collect=once — разовый обход и выход. */
    public void collect(boolean once) {
        if (once) {
            daemon.collectOnce();
        } else {
            daemon.run();
        }
    }

    /** --revx-pairs: обновить каталог и показать вселенную стенда. */
    public void pairs() {
        Map<String, PairSpec> specs = catalog.refresh();
        List<PairsCatalog.Leg> universe = catalog.universe(specs);
        StringBuilder sb = new StringBuilder(String.format(
                "%n=== Вселенная стенда: %d пар %s (+ %d опорных ног %s) ===%n",
                universe.size(), cfg.quoteCurrency(), universe.size(), cfg.referenceQuote()));
        sb.append(String.format("%-12s %-12s %14s %12s %14s  %s%n",
                "пара", "опора", "base_step", "quote_step", "min ордер, " + cfg.quoteCurrency(), "примечание"));
        for (PairsCatalog.Leg leg : universe) {
            sb.append(String.format("%-12s %-12s %14s %12s %14s  %s%n",
                    leg.quoted().symbol(), leg.reference().symbol(),
                    trim(leg.quoted().baseStep()), trim(leg.quoted().quoteStep()),
                    trim(leg.quoted().minOrderSizeQuote()),
                    leg.memecoin() ? "мемкоин: в расчёт курса USDC/USD не входит" : ""));
        }
        sb.append(String.format("%nЗапросов на полный обход: %d (по две ноги на пару). Каталог записан в revx_pair.%n",
                universe.size() * 2));
        log.info(sb.toString());
    }

    /** --revx-probe-limits: эмпирический потолок запросов (ТЗ §3.2). */
    public void probeLimits(RateLimitProbe.Ladder ladder) {
        probe.run(ladder);
    }

    /** --revx-basis: курс USDC/USD, его устойчивость и пригодность пар (ТЗ §4.1). */
    public void basis(int hours, long bucketMs, String out) {
        basisReport.run(hours, bucketMs, out);
    }

    /** --revx-sim: обязательные прогоны §4.7 и отчёт §5.3 по паре. */
    public void simulate(String symbol, int hours, String out) {
        simRunner.run(symbol, hours, out);
    }

    /** Шаги бывают мельче 1e-8 (мемкоины), поэтому без %f — иначе печатается ноль. */
    private static String trim(Double v) {
        return v == null ? "-" : java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
