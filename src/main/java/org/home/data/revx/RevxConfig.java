package org.home.data.revx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    private final List<String> fastPairs;
    private final long fastBookPeriodMs;
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
    private final double simSkewTarget;
    private final double simDriftBeta;
    private final long simDriftWindowMs;
    private final double simBuySizeRatio;
    private final double simSizeShapeEta;
    private final long simHoldHorizonMs;
    private final double simDriftGateEr;
    private final long simErWindowMs;
    private final long simErSampleMs;
    private final double simStopDrawdownPct;
    private final long simStopCoolOffMs;
    private final List<Double> simStopLadder;
    private final boolean simStickyEnabled;
    private final double simStickyOuter;
    private final double simStickyInner;
    private final long simStickyMaxAgeMs;
    private final boolean simStickyReplaceOnFill;
    private final double simStickySkewDelta;
    private final boolean simStickyResetQueue;
    private final List<Long> simHedgeRebalanceLadder;
    private final double simHedgeStep;
    private final Map<String, Double> simHedgeSteps;
    private final boolean simHedgeRoundDown;
    private final double simHedgeFee;
    private final double simHedgeFundingPerHour;
    private final List<Double> simAnchorLeashLadder;
    private final List<Double> simWideningLadder;
    private final double simWideningMaxStep;
    private final List<Double> simCostFloorLadder;
    private final double simGridMargin;
    private final double simGridBaseStep;
    private final double simGridWidening;
    private final double simGridMaxStep;
    private final List<Double> simGridMarginLadder;
    private final List<Double> simGridWideningLadder;
    private final List<Integer> simGridLotsLadder;
    private final boolean simFrozenEnabled;
    private final long simFrozenCoolOffMs;
    private final long simFrozenMaxAgeMs;
    private final List<Long> simFrozenCoolOffLadder;
    private final List<Long> simFrozenMaxAgeLadder;
    private final List<Double> simSkewTargetLadder;
    private final List<Double> simStickyOuterLadder;
    private final List<Double> simStickyInnerLadder;
    private final double simRequoteThreshold;
    private final double simMakerFee;
    private final double simPessimisticFee;
    private final long simRandomSeed;
    private final int simRandomSeeds;
    private final int simControlAnchorWindows;
    private final List<Integer> simControlAnchorLadder;
    private final List<Double> simFeeLadder;
    private final List<Double> simOffsetLadder;
    private final List<Double> simSkewLadder;
    private final List<Double> simCapLadder;
    private final List<Double> simDriftBetaLadder;
    private final List<Double> simBuyRatioLadder;
    private final List<Double> simShapeLadder;
    private final List<Double> simCrossBeta;
    private final List<Double> simCrossEta;
    private final List<Integer> simLatencyLadder;

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
            @Value("${revx.fast-pairs}") List<String> fastPairs,
            @Value("${revx.fast-book-period-ms}") long fastBookPeriodMs,
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
            @Value("${revx.sim.skew-target}") double simSkewTarget,
            @Value("${revx.sim.drift-beta}") double simDriftBeta,
            @Value("${revx.sim.drift-window-ms}") long simDriftWindowMs,
            @Value("${revx.sim.buy-size-ratio}") double simBuySizeRatio,
            @Value("${revx.sim.size-shape-eta}") double simSizeShapeEta,
            @Value("${revx.sim.hold-horizon-ms}") long simHoldHorizonMs,
            @Value("${revx.sim.drift-gate-er}") double simDriftGateEr,
            @Value("${revx.sim.er-window-ms}") long simErWindowMs,
            @Value("${revx.sim.er-sample-ms}") long simErSampleMs,
            @Value("${revx.sim.stop-drawdown-pct}") double simStopDrawdownPct,
            @Value("${revx.sim.stop-cool-off-ms}") long simStopCoolOffMs,
            @Value("${revx.sim.stop-ladder}") List<Double> simStopLadder,
            @Value("${revx.sim.sticky-enabled}") boolean simStickyEnabled,
            @Value("${revx.sim.sticky-outer}") double simStickyOuter,
            @Value("${revx.sim.sticky-inner}") double simStickyInner,
            @Value("${revx.sim.sticky-max-age-ms}") long simStickyMaxAgeMs,
            @Value("${revx.sim.sticky-replace-on-fill}") boolean simStickyReplaceOnFill,
            @Value("${revx.sim.sticky-skew-delta}") double simStickySkewDelta,
            @Value("${revx.sim.sticky-reset-queue}") boolean simStickyResetQueue,
            @Value("${revx.sim.hedge-rebalance-ladder}") List<Long> simHedgeRebalanceLadder,
            @Value("${revx.sim.hedge-step}") double simHedgeStep,
            @Value("${revx.sim.hedge-steps}") String simHedgeSteps,
            @Value("${revx.sim.hedge-round-down}") boolean simHedgeRoundDown,
            @Value("${revx.sim.hedge-fee}") double simHedgeFee,
            @Value("${revx.sim.hedge-funding-per-hour}") double simHedgeFundingPerHour,
            @Value("${revx.sim.anchor-leash-ladder}") List<Double> simAnchorLeashLadder,
            @Value("${revx.sim.widening-ladder}") List<Double> simWideningLadder,
            @Value("${revx.sim.widening-max-step}") double simWideningMaxStep,
            @Value("${revx.sim.cost-floor-ladder}") List<Double> simCostFloorLadder,
            @Value("${revx.sim.grid-margin}") double simGridMargin,
            @Value("${revx.sim.grid-base-step}") double simGridBaseStep,
            @Value("${revx.sim.grid-widening}") double simGridWidening,
            @Value("${revx.sim.grid-max-step}") double simGridMaxStep,
            @Value("${revx.sim.grid-margin-ladder}") List<Double> simGridMarginLadder,
            @Value("${revx.sim.grid-widening-ladder}") List<Double> simGridWideningLadder,
            @Value("${revx.sim.grid-lots-ladder}") List<Integer> simGridLotsLadder,
            @Value("${revx.sim.frozen-enabled}") boolean simFrozenEnabled,
            @Value("${revx.sim.frozen-cool-off-ms}") long simFrozenCoolOffMs,
            @Value("${revx.sim.frozen-max-age-ms}") long simFrozenMaxAgeMs,
            @Value("${revx.sim.frozen-cool-off-ladder}") List<Long> simFrozenCoolOffLadder,
            @Value("${revx.sim.frozen-max-age-ladder}") List<Long> simFrozenMaxAgeLadder,
            @Value("${revx.sim.skew-target-ladder}") List<Double> simSkewTargetLadder,
            @Value("${revx.sim.sticky-outer-ladder}") List<Double> simStickyOuterLadder,
            @Value("${revx.sim.sticky-inner-ladder}") List<Double> simStickyInnerLadder,
            @Value("${revx.sim.requote-threshold}") double simRequoteThreshold,
            @Value("${revx.sim.maker-fee}") double simMakerFee,
            @Value("${revx.sim.pessimistic-fee}") double simPessimisticFee,
            @Value("${revx.sim.random-seed}") long simRandomSeed,
            @Value("${revx.sim.random-seeds}") int simRandomSeeds,
            @Value("${revx.sim.control-anchor-windows}") int simControlAnchorWindows,
            @Value("${revx.sim.control-anchor-ladder}") List<Integer> simControlAnchorLadder,
            @Value("${revx.sim.fee-ladder}") List<Double> simFeeLadder,
            @Value("${revx.sim.offset-ladder}") List<Double> simOffsetLadder,
            @Value("${revx.sim.skew-ladder}") List<Double> simSkewLadder,
            @Value("${revx.sim.cap-ladder}") List<Double> simCapLadder,
            @Value("${revx.sim.drift-beta-ladder}") List<Double> simDriftBetaLadder,
            @Value("${revx.sim.buy-ratio-ladder}") List<Double> simBuyRatioLadder,
            @Value("${revx.sim.shape-ladder}") List<Double> simShapeLadder,
            @Value("${revx.sim.cross-beta}") List<Double> simCrossBeta,
            @Value("${revx.sim.cross-eta}") List<Double> simCrossEta,
            @Value("${revx.sim.latency-ladder}") List<Integer> simLatencyLadder,
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
        this.fastPairs = fastPairs;
        this.fastBookPeriodMs = fastBookPeriodMs;
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
        this.simSkewTarget = simSkewTarget;
        this.simDriftBeta = simDriftBeta;
        this.simDriftWindowMs = simDriftWindowMs;
        this.simBuySizeRatio = simBuySizeRatio;
        this.simSizeShapeEta = simSizeShapeEta;
        this.simHoldHorizonMs = simHoldHorizonMs;
        this.simDriftGateEr = simDriftGateEr;
        this.simErWindowMs = simErWindowMs;
        this.simErSampleMs = simErSampleMs;
        this.simStopDrawdownPct = simStopDrawdownPct;
        this.simStopCoolOffMs = simStopCoolOffMs;
        this.simStopLadder = simStopLadder;
        this.simStickyEnabled = simStickyEnabled;
        this.simStickyOuter = simStickyOuter;
        this.simStickyInner = simStickyInner;
        this.simStickyMaxAgeMs = simStickyMaxAgeMs;
        this.simStickyReplaceOnFill = simStickyReplaceOnFill;
        this.simStickySkewDelta = simStickySkewDelta;
        this.simStickyResetQueue = simStickyResetQueue;
        this.simHedgeRebalanceLadder = simHedgeRebalanceLadder;
        this.simHedgeStep = simHedgeStep;
        this.simHedgeSteps = parseSteps(simHedgeSteps);
        this.simHedgeRoundDown = simHedgeRoundDown;
        this.simHedgeFee = simHedgeFee;
        this.simHedgeFundingPerHour = simHedgeFundingPerHour;
        this.simAnchorLeashLadder = simAnchorLeashLadder;
        this.simWideningLadder = simWideningLadder;
        this.simWideningMaxStep = simWideningMaxStep;
        this.simCostFloorLadder = simCostFloorLadder;
        this.simGridMargin = simGridMargin;
        this.simGridBaseStep = simGridBaseStep;
        this.simGridWidening = simGridWidening;
        this.simGridMaxStep = simGridMaxStep;
        this.simGridMarginLadder = simGridMarginLadder;
        this.simGridWideningLadder = simGridWideningLadder;
        this.simGridLotsLadder = simGridLotsLadder;
        this.simFrozenEnabled = simFrozenEnabled;
        this.simFrozenCoolOffMs = simFrozenCoolOffMs;
        this.simFrozenMaxAgeMs = simFrozenMaxAgeMs;
        this.simFrozenCoolOffLadder = simFrozenCoolOffLadder;
        this.simFrozenMaxAgeLadder = simFrozenMaxAgeLadder;
        this.simSkewTargetLadder = simSkewTargetLadder;
        this.simStickyOuterLadder = simStickyOuterLadder;
        this.simStickyInnerLadder = simStickyInnerLadder;
        this.simRequoteThreshold = simRequoteThreshold;
        this.simMakerFee = simMakerFee;
        this.simPessimisticFee = simPessimisticFee;
        this.simRandomSeed = simRandomSeed;
        this.simRandomSeeds = simRandomSeeds;
        this.simControlAnchorWindows = simControlAnchorWindows;
        this.simControlAnchorLadder = simControlAnchorLadder;
        this.simFeeLadder = simFeeLadder;
        this.simOffsetLadder = simOffsetLadder;
        this.simSkewLadder = simSkewLadder;
        this.simCapLadder = simCapLadder;
        this.simDriftBetaLadder = simDriftBetaLadder;
        this.simBuyRatioLadder = simBuyRatioLadder;
        this.simShapeLadder = simShapeLadder;
        this.simCrossBeta = simCrossBeta;
        this.simCrossEta = simCrossEta;
        this.simLatencyLadder = simLatencyLadder;
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
    /**
     * Пары быстрого яруса (базовые символы). Пусто = ярус выключен.
     *
     * Задержка котирования — самый дорогой из измеренных параметров: устаревание
     * заявки растёт как корень из периода опроса и на пяти секундах съедает
     * 2.86 б.п. из четырнадцати (док. 88). Левая часть кривой в симуляции
     * недоступна — данные собраны с шагом 5 с, — поэтому её собирают отдельно.
     */
    public List<String> fastPairs() {
        return fastPairs;
    }

    public long fastBookPeriodMs() {
        return fastBookPeriodMs;
    }

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

    public double simSkewTarget() {
        return simSkewTarget;
    }

    public double simDriftBeta() {
        return simDriftBeta;
    }

    public long simDriftWindowMs() {
        return simDriftWindowMs;
    }

    public double simBuySizeRatio() {
        return simBuySizeRatio;
    }

    public double simSizeShapeEta() {
        return simSizeShapeEta;
    }

    public long simHoldHorizonMs() {
        return simHoldHorizonMs;
    }

    public double simDriftGateEr() {
        return simDriftGateEr;
    }

    public long simErWindowMs() {
        return simErWindowMs;
    }

    public long simErSampleMs() {
        return simErSampleMs;
    }

    public double simStopDrawdownPct() {
        return simStopDrawdownPct;
    }

    public long simStopCoolOffMs() {
        return simStopCoolOffMs;
    }

    public double[] simStopLadder() {
        return simStopLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public org.home.data.revx.sim.Quoter.Sticky simSticky() {
        return new org.home.data.revx.sim.Quoter.Sticky(simStickyEnabled, simStickyOuter,
                simStickyInner, simStickyMaxAgeMs, simStickyReplaceOnFill,
                simStickySkewDelta, simStickyResetQueue);
    }

    public long[] simHedgeRebalanceLadder() {
        return simHedgeRebalanceLadder.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Хедж БЕЗ привязки к паре — только для мест, где символ неизвестен.
     * Везде, где он известен, брать {@link #simHedge(String)}.
     */
    public org.home.data.revx.sim.Quoter.Hedge simHedge() {
        return new org.home.data.revx.sim.Quoter.Hedge(true, 0, simHedgeStep, simHedgeFee,
                simHedgeFundingPerHour, simHedgeRoundDown);
    }

    /**
     * Хедж для КОНКРЕТНОЙ пары.
     *
     * ⚠️ Шаг контракта у каждого перпа свой, и подставлять BTC-шаг остальным
     * нельзя. Прогоны ETH и SOL из док. 125 §6 шли с общим {@code hedge-step}
     * = 0.0001, то есть с разрешением хеджа в тысячи раз тоньше настоящего:
     * остаточная нога там выходила 0.00188 SOL при реальном шаге контракта
     * порядка 0.1. Числа «SOL 200.0 против BTC 30.0» посчитаны при этом
     * допущении и без него не проверены.
     */
    public org.home.data.revx.sim.Quoter.Hedge simHedge(String symbol) {
        return simHedge().withStep(simHedgeStep(symbol));
    }

    /** Шаг контракта перпа для пары; при отсутствии в карте — общий. */
    public double simHedgeStep(String symbol) {
        if (symbol == null) {
            return simHedgeStep;
        }
        String base = symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/'))
                : symbol.contains("-") ? symbol.substring(0, symbol.indexOf('-')) : symbol;
        Double v = simHedgeSteps.get(base.toUpperCase(java.util.Locale.ROOT));
        return v == null ? simHedgeStep : v;
    }

    public boolean simHedgeRoundDown() {
        return simHedgeRoundDown;
    }

    /** Формат «BTC:0.0001,ETH:0.01,SOL:0.1»; пустая строка = карты нет. */
    private static Map<String, Double> parseSteps(String raw) {
        Map<String, Double> map = new java.util.LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                map.put(kv[0].trim().toUpperCase(java.util.Locale.ROOT), Double.parseDouble(kv[1].trim()));
            }
        }
        return map;
    }

    public double[] simAnchorLeashLadder() {
        return simAnchorLeashLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simWideningLadder() {
        return simWideningLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double simWideningMaxStep() { return simWideningMaxStep; }

    public double[] simCostFloorLadder() {
        return simCostFloorLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double simGridMargin() { return simGridMargin; }

    public double simGridBaseStep() { return simGridBaseStep; }

    public double simGridWidening() { return simGridWidening; }

    public double simGridMaxStep() { return simGridMaxStep; }

    public double[] simGridMarginLadder() {
        return simGridMarginLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simGridWideningLadder() {
        return simGridWideningLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public int[] simGridLotsLadder() {
        return simGridLotsLadder.stream().mapToInt(Integer::intValue).toArray();
    }

    public org.home.data.revx.sim.Quoter.Frozen simFrozen() {
        return new org.home.data.revx.sim.Quoter.Frozen(simFrozenEnabled, simFrozenCoolOffMs, simFrozenMaxAgeMs);
    }

    public long[] simFrozenCoolOffLadder() {
        return simFrozenCoolOffLadder.stream().mapToLong(Long::longValue).toArray();
    }

    public long[] simFrozenMaxAgeLadder() {
        return simFrozenMaxAgeLadder.stream().mapToLong(Long::longValue).toArray();
    }

    public double[] simSkewTargetLadder() {
        return simSkewTargetLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simStickyOuterLadder() {
        return simStickyOuterLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simStickyInnerLadder() {
        return simStickyInnerLadder.stream().mapToDouble(Double::doubleValue).toArray();
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

    /**
     * Глубина «недавнего» для второго контроля — того, у которого расстояние
     * ±d такое же, как у стратегии, а случаен только центр (док. 127 §9).
     */
    public int simControlAnchorWindows() {
        return simControlAnchorWindows;
    }

    /**
     * Лестница глубины якоря. Нужна, чтобы отличить «слежение за ценой» от
     * «свежести котировки»: если счёт контроля идёт по {@code d − 2.86·√(t/5)},
     * он меряет устаревание и только его (док. 132 §1).
     */
    public int[] simControlAnchorLadder() {
        return simControlAnchorLadder.stream().mapToInt(Integer::intValue).toArray();
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

    public double[] simDriftBetaLadder() {
        return simDriftBetaLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simBuyRatioLadder() {
        return simBuyRatioLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simShapeLadder() {
        return simShapeLadder.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simCrossBeta() {
        return simCrossBeta.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] simCrossEta() {
        return simCrossEta.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Ступени задержки котирования в секундах — цена отсутствия WebSocket.
     * Базовая ступень (период опроса) добавляется отдельно; быстрее неё
     * промоделировать нечего, данные собраны с этим шагом.
     */
    public int[] simLatencyLadder() {
        return simLatencyLadder.stream().mapToInt(Integer::intValue).toArray();
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
