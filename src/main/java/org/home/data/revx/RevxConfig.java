package org.home.data.revx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Конфигурация стенда. ТЗ §2: все параметры стратегии и издержек — только из
 * конфига, никаких констант в коде. Ключи живут в application.properties
 * (префикс {@code revx.}) и переопределяются аргументом запуска, например
 * {@code --revx.book-poll-seconds=10}; снимок конфига пишется в запись прогона.
 */
@Component
@Lazy
public class RevxConfig {

    private final String baseUrl;
    private final String region;
    private final int bookDepth;
    private final double maxRequestsPerSecond;
    private final int burstCapacity;
    private final double authMaxRequestsPerSecond;
    private final int authBurstCapacity;
    private final long authSkewThresholdMs;
    private final int workers;
    private final int authWorkers;
    private final int authBookPeriodSeconds;
    private final int authTradesPeriodSeconds;
    private final int maxRetries;
    private final int requestTimeoutSeconds;
    private final long skewThresholdMs;
    private final String quoteCurrency;
    private final String referenceQuote;
    private final List<String> memecoins;
    private final List<String> priority;
    private final int tradesPageLimit;

    private final List<String> tier1;
    private final List<String> tier2;
    private final int bookPeriodTier1Seconds;
    private final int bookPeriodTier2Seconds;
    private final int bookPeriodTier3Seconds;
    private final int tradesPeriodTier1Seconds;
    private final int tradesPeriodOtherSeconds;
    private final int pairsRefreshHours;
    private final int tradesPagesPerRun;
    private final int tradesBackfillMaxHours;
    private final String healthFile;

    private final int fairMinPairs;
    private final double fairMaxDispersionPct;
    private final double fairMaxReferenceSpreadPct;
    private final double fairMaxResidualPct;
    private final long fairMaxSkewMs;

    private final double simOffset;
    private final double simSize;
    private final double simInventoryCap;
    private final double simSkewK;
    private final double simRequoteThreshold;
    private final double simMakerFee;
    private final double simPessimisticFee;
    private final long simRandomSeed;
    private final int simRandomSeeds;
    private final List<Double> simFeeLadder;
    private final List<Double> simOffsetLadder;
    private final List<Double> simSkewLadder;
    private final List<Double> simCapLadder;

    private final double probeStartRps;
    private final double probeStepRps;
    private final double probeMaxRps;
    private final int probeDwellSeconds;
    private final int probeCooldownSeconds;
    private final double probeFailRatio;

    public RevxConfig(
            @Value("${revx.base-url}") String baseUrl,
            @Value("${revx.region}") String region,
            @Value("${revx.book-depth}") int bookDepth,
            @Value("${revx.max-requests-per-second}") double maxRequestsPerSecond,
            @Value("${revx.burst-capacity}") int burstCapacity,
            @Value("${revx.auth.max-requests-per-second}") double authMaxRequestsPerSecond,
            @Value("${revx.auth.burst-capacity}") int authBurstCapacity,
            @Value("${revx.auth.skew-threshold-ms}") long authSkewThresholdMs,
            @Value("${revx.workers}") int workers,
            @Value("${revx.auth.workers}") int authWorkers,
            @Value("${revx.auth.book-period-seconds}") int authBookPeriodSeconds,
            @Value("${revx.auth.trades-period-seconds}") int authTradesPeriodSeconds,
            @Value("${revx.max-retries}") int maxRetries,
            @Value("${revx.request-timeout-seconds}") int requestTimeoutSeconds,
            @Value("${revx.skew-threshold-ms}") long skewThresholdMs,
            @Value("${revx.quote-currency}") String quoteCurrency,
            @Value("${revx.reference-quote}") String referenceQuote,
            @Value("${revx.memecoins}") List<String> memecoins,
            @Value("${revx.priority}") List<String> priority,
            @Value("${revx.trades-page-limit}") int tradesPageLimit,
            @Value("${revx.tier1}") List<String> tier1,
            @Value("${revx.tier2}") List<String> tier2,
            @Value("${revx.book-period-tier1-seconds}") int bookPeriodTier1Seconds,
            @Value("${revx.book-period-tier2-seconds}") int bookPeriodTier2Seconds,
            @Value("${revx.book-period-tier3-seconds}") int bookPeriodTier3Seconds,
            @Value("${revx.trades-period-tier1-seconds}") int tradesPeriodTier1Seconds,
            @Value("${revx.trades-period-other-seconds}") int tradesPeriodOtherSeconds,
            @Value("${revx.pairs-refresh-hours}") int pairsRefreshHours,
            @Value("${revx.trades-pages-per-run}") int tradesPagesPerRun,
            @Value("${revx.trades-backfill-max-hours}") int tradesBackfillMaxHours,
            @Value("${revx.health-file}") String healthFile,
            @Value("${revx.fair.min-pairs}") int fairMinPairs,
            @Value("${revx.fair.max-dispersion-pct}") double fairMaxDispersionPct,
            @Value("${revx.fair.max-reference-spread-pct}") double fairMaxReferenceSpreadPct,
            @Value("${revx.fair.max-residual-pct}") double fairMaxResidualPct,
            @Value("${revx.fair.max-skew-ms}") long fairMaxSkewMs,
            @Value("${revx.sim.offset}") double simOffset,
            @Value("${revx.sim.size}") double simSize,
            @Value("${revx.sim.inventory-cap}") double simInventoryCap,
            @Value("${revx.sim.skew-k}") double simSkewK,
            @Value("${revx.sim.requote-threshold}") double simRequoteThreshold,
            @Value("${revx.sim.maker-fee}") double simMakerFee,
            @Value("${revx.sim.pessimistic-fee}") double simPessimisticFee,
            @Value("${revx.sim.random-seed}") long simRandomSeed,
            @Value("${revx.sim.random-seeds}") int simRandomSeeds,
            @Value("${revx.sim.fee-ladder}") List<Double> simFeeLadder,
            @Value("${revx.sim.offset-ladder}") List<Double> simOffsetLadder,
            @Value("${revx.sim.skew-ladder}") List<Double> simSkewLadder,
            @Value("${revx.sim.cap-ladder}") List<Double> simCapLadder,
            @Value("${revx.probe.start-rps}") double probeStartRps,
            @Value("${revx.probe.step-rps}") double probeStepRps,
            @Value("${revx.probe.max-rps}") double probeMaxRps,
            @Value("${revx.probe.dwell-seconds}") int probeDwellSeconds,
            @Value("${revx.probe.cooldown-seconds}") int probeCooldownSeconds,
            @Value("${revx.probe.fail-ratio}") double probeFailRatio) {
        this.baseUrl = baseUrl;
        this.region = region;
        this.bookDepth = bookDepth;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.burstCapacity = burstCapacity;
        this.authMaxRequestsPerSecond = authMaxRequestsPerSecond;
        this.authBurstCapacity = authBurstCapacity;
        this.authSkewThresholdMs = authSkewThresholdMs;
        this.workers = workers;
        this.authWorkers = authWorkers;
        this.authBookPeriodSeconds = authBookPeriodSeconds;
        this.authTradesPeriodSeconds = authTradesPeriodSeconds;
        this.maxRetries = maxRetries;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.skewThresholdMs = skewThresholdMs;
        this.quoteCurrency = quoteCurrency;
        this.referenceQuote = referenceQuote;
        this.memecoins = memecoins;
        this.priority = priority;
        this.tradesPageLimit = tradesPageLimit;
        this.tier1 = tier1;
        this.tier2 = tier2;
        this.bookPeriodTier1Seconds = bookPeriodTier1Seconds;
        this.bookPeriodTier2Seconds = bookPeriodTier2Seconds;
        this.bookPeriodTier3Seconds = bookPeriodTier3Seconds;
        this.tradesPeriodTier1Seconds = tradesPeriodTier1Seconds;
        this.tradesPeriodOtherSeconds = tradesPeriodOtherSeconds;
        this.pairsRefreshHours = pairsRefreshHours;
        this.tradesPagesPerRun = tradesPagesPerRun;
        this.tradesBackfillMaxHours = tradesBackfillMaxHours;
        this.healthFile = healthFile;
        this.fairMinPairs = fairMinPairs;
        this.fairMaxDispersionPct = fairMaxDispersionPct;
        this.fairMaxReferenceSpreadPct = fairMaxReferenceSpreadPct;
        this.fairMaxResidualPct = fairMaxResidualPct;
        this.fairMaxSkewMs = fairMaxSkewMs;
        this.simOffset = simOffset;
        this.simSize = simSize;
        this.simInventoryCap = simInventoryCap;
        this.simSkewK = simSkewK;
        this.simRequoteThreshold = simRequoteThreshold;
        this.simMakerFee = simMakerFee;
        this.simPessimisticFee = simPessimisticFee;
        this.simRandomSeed = simRandomSeed;
        this.simRandomSeeds = simRandomSeeds;
        this.simFeeLadder = simFeeLadder;
        this.simOffsetLadder = simOffsetLadder;
        this.simSkewLadder = simSkewLadder;
        this.simCapLadder = simCapLadder;
        this.probeStartRps = probeStartRps;
        this.probeStepRps = probeStepRps;
        this.probeMaxRps = probeMaxRps;
        this.probeDwellSeconds = probeDwellSeconds;
        this.probeCooldownSeconds = probeCooldownSeconds;
        this.probeFailRatio = probeFailRatio;
    }

    // --- URL-ы. Символ в пути книги/сделок идёт через дефис (BTC-USDC),
    //     хотя каталог пар отдаёт его через слэш (BTC/USDC).

    public String pairsUrl() {
        return baseUrl + "/api/1.0/public/configuration/pairs?region=" + region;
    }

    public String bookUrl(String pathSymbol) {
        return baseUrl + "/api/2.0/public/order-book/" + pathSymbol
                + "?limit=" + bookDepth + "&region=" + region;
    }

    public String tradesUrl(String pathSymbol, String cursor) {
        String url = baseUrl + "/api/1.0/public/trades/all?symbol=" + pathSymbol
                + "&limit=" + tradesPageLimit + "&region=" + region;
        return cursor == null || cursor.isBlank() ? url : url + "&cursor=" + cursor;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String region() {
        return region;
    }

    public int bookDepth() {
        return bookDepth;
    }

    public double maxRequestsPerSecond() {
        return maxRequestsPerSecond;
    }

    /** Разрешённый залп на публичном пути. Держим 1: площадка не терпит запросов встык. */
    public int burstCapacity() {
        return burstCapacity;
    }

    /** Темп с ключом: лимит площадки 100/с и 1000/мин, берём с большим запасом. */
    public double authMaxRequestsPerSecond() {
        return authMaxRequestsPerSecond;
    }

    /** С ключом залпы разрешены — иначе не будет одновременности ног (ТЗ §3.3). */
    public int authBurstCapacity() {
        return authBurstCapacity;
    }

    public long authSkewThresholdMs() {
        return authSkewThresholdMs;
    }

    public int workers() {
        return workers;
    }

    public int authWorkers() {
        return authWorkers;
    }

    public int authBookPeriodSeconds() {
        return authBookPeriodSeconds;
    }

    public int authTradesPeriodSeconds() {
        return authTradesPeriodSeconds;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public long skewThresholdMs() {
        return skewThresholdMs;
    }

    public String quoteCurrency() {
        return quoteCurrency;
    }

    public String referenceQuote() {
        return referenceQuote;
    }

    public List<String> memecoins() {
        return memecoins;
    }

    public List<String> priority() {
        return priority;
    }

    public int tradesPageLimit() {
        return tradesPageLimit;
    }

    /** Ярус 1 — единственный, по которому симуляция честна (снимки достаточно часты). */
    public List<String> tier1() {
        return tier1;
    }

    public List<String> tier2() {
        return tier2;
    }

    public int bookPeriodTier1Seconds() {
        return bookPeriodTier1Seconds;
    }

    public int bookPeriodTier2Seconds() {
        return bookPeriodTier2Seconds;
    }

    public int bookPeriodTier3Seconds() {
        return bookPeriodTier3Seconds;
    }

    public int tradesPeriodTier1Seconds() {
        return tradesPeriodTier1Seconds;
    }

    public int tradesPeriodOtherSeconds() {
        return tradesPeriodOtherSeconds;
    }

    public int pairsRefreshHours() {
        return pairsRefreshHours;
    }

    public int tradesPagesPerRun() {
        return tradesPagesPerRun;
    }

    public int tradesBackfillMaxHours() {
        return tradesBackfillMaxHours;
    }

    public String healthFile() {
        return healthFile;
    }

    // --- Пороги справедливой цены (ТЗ §4.1) ---

    public int fairMinPairs() {
        return fairMinPairs;
    }

    public double fairMaxDispersionPct() {
        return fairMaxDispersionPct;
    }

    public double fairMaxReferenceSpreadPct() {
        return fairMaxReferenceSpreadPct;
    }

    public double fairMaxResidualPct() {
        return fairMaxResidualPct;
    }

    public long fairMaxSkewMs() {
        return fairMaxSkewMs;
    }

    // --- Параметры симуляции (ТЗ §4.2, §4.7). Задаются ДО прогона. ---

    public double simOffset() {
        return simOffset;
    }

    public double simSize() {
        return simSize;
    }

    public double simInventoryCap() {
        return simInventoryCap;
    }

    public double simSkewK() {
        return simSkewK;
    }

    public double simRequoteThreshold() {
        return simRequoteThreshold;
    }

    public double simMakerFee() {
        return simMakerFee;
    }

    public double simPessimisticFee() {
        return simPessimisticFee;
    }

    public long simRandomSeed() {
        return simRandomSeed;
    }

    /**
     * Сколько независимых seed'ов гонять для контроля «случайные котировки».
     * Один seed даёт одно число и вопрос «побит контроль или нет» решается
     * монеткой: разница по BTC была +4%, по ETH −11% (док. 75 §4). Нулевое
     * распределение из N прогонов заменяет вердикт процентилем.
     */
    public int simRandomSeeds() {
        return simRandomSeeds;
    }

    public double[] simFeeLadder() {
        return simFeeLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simOffsetLadder() {
        return simOffsetLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Лестница скоса. Мерить её надо по КРАЮ, а не по {@code total}: скос меняет
     * не цену исполнения, а момент — при его выключении захват спреда остался
     * прежним (402.2 против 403.4), а весь выигрыш пришёл в markout (док. 79 §7).
     * В {@code total} этот эффект тонет в бете.
     */
    public double[] simSkewLadder() {
        return simSkewLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Множители к потолку инвентаря. Отвечают на вопрос ёмкости: базисные пункты
     * превращает в деньги именно потолок, а лестницы по нему не было.
     *
     * Что этот прогон МОЖЕТ показать: где наступает механическое насыщение, то есть
     * где мы перестаём упираться в потолок и рост потолка больше ничего не добавляет.
     * Чего он показать НЕ может: рыночного влияния. Модель проигрывает исторические
     * сделки против гипотетической котировки и не знает, что при доле рынка в
     * четверть поток стал бы другим. Поэтому результат — верхняя граница ёмкости.
     */
    public double[] simCapLadder() {
        return simCapLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double probeStartRps() {
        return probeStartRps;
    }

    public double probeStepRps() {
        return probeStepRps;
    }

    public double probeMaxRps() {
        return probeMaxRps;
    }

    public int probeDwellSeconds() {
        return probeDwellSeconds;
    }

    public int probeCooldownSeconds() {
        return probeCooldownSeconds;
    }

    public double probeFailRatio() {
        return probeFailRatio;
    }
}
