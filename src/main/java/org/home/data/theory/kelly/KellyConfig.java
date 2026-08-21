package org.home.data.theory.kelly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация стенда сайзинга S5 (ТЗ 66 §2): все параметры события, издержек и
 * порогов — только отсюда, конфиг целиком пишется в запись прогона.
 *
 * <p>Префикс {@code theory.kelly.*} в {@code application.properties}.
 */
@Component
@Lazy
public class KellyConfig {

    private final double minPct;
    private final int lead;
    private final double stop;
    private final double maxFundingCost;
    private final double tradeCost;
    private final double slippage;
    private final String from;
    private final int bootstrap;
    private final long seed;
    private final int monteCarloPaths;
    private final double ruinThreshold;
    private final double currentLimit;
    private final int maxConcurrent;
    private final int testedHypotheses;
    private final List<Double> priorStrengths;
    private final List<Double> sizeGrid;
    private final List<Double> stopGrid;
    private final double portfolioDdLimit;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public KellyConfig(
            @Value("${theory.kelly.min-pct}") double minPct,
            @Value("${theory.kelly.lead}") int lead,
            @Value("${theory.kelly.stop}") double stop,
            @Value("${theory.kelly.max-funding-cost}") double maxFundingCost,
            @Value("${theory.kelly.trade-cost}") double tradeCost,
            @Value("${theory.kelly.slippage}") double slippage,
            @Value("${theory.kelly.from}") String from,
            @Value("${theory.kelly.bootstrap}") int bootstrap,
            @Value("${theory.kelly.seed}") long seed,
            @Value("${theory.kelly.monte-carlo-paths}") int monteCarloPaths,
            @Value("${theory.kelly.ruin-threshold}") double ruinThreshold,
            @Value("${theory.kelly.current-limit}") double currentLimit,
            @Value("${theory.kelly.max-concurrent}") int maxConcurrent,
            @Value("${theory.kelly.tested-hypotheses}") int testedHypotheses,
            @Value("${theory.kelly.prior-strengths}") String priorStrengths,
            @Value("${theory.kelly.size-grid}") String sizeGrid,
            @Value("${theory.kelly.stop-grid}") String stopGrid,
            @Value("${theory.kelly.portfolio-dd-limit}") double portfolioDdLimit) {
        this.minPct = minPct;
        this.lead = lead;
        this.stop = stop;
        this.maxFundingCost = maxFundingCost;
        this.tradeCost = tradeCost;
        this.slippage = slippage;
        this.from = from;
        this.bootstrap = bootstrap;
        this.seed = seed;
        this.monteCarloPaths = monteCarloPaths;
        this.ruinThreshold = ruinThreshold;
        this.currentLimit = currentLimit;
        this.maxConcurrent = maxConcurrent;
        this.testedHypotheses = testedHypotheses;
        this.priorStrengths = parseList(priorStrengths);
        this.sizeGrid = parseList(sizeGrid);
        this.stopGrid = parseList(stopGrid);
        this.portfolioDdLimit = portfolioDdLimit;
    }

    private static List<Double> parseList(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }

    /** Конфиг целиком — в запись прогона (§2 «Воспроизводимость»). */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min_pct", minPct);
        m.put("lead", lead);
        m.put("stop", stop);
        m.put("max_funding_cost", maxFundingCost);
        m.put("trade_cost", tradeCost);
        m.put("slippage", slippage);
        m.put("from", from);
        m.put("bootstrap", bootstrap);
        m.put("seed", seed);
        m.put("monte_carlo_paths", monteCarloPaths);
        m.put("ruin_threshold", ruinThreshold);
        m.put("current_limit", currentLimit);
        m.put("max_concurrent", maxConcurrent);
        m.put("tested_hypotheses", testedHypotheses);
        m.put("prior_strengths", priorStrengths);
        m.put("size_grid", sizeGrid);
        m.put("stop_grid", stopGrid);
        m.put("portfolio_dd_limit", portfolioDdLimit);
        return m;
    }

    public double minPct() {
        return minPct;
    }

    public int lead() {
        return lead;
    }

    public double stop() {
        return stop;
    }

    public double maxFundingCost() {
        return maxFundingCost;
    }

    public double tradeCost() {
        return tradeCost;
    }

    public double slippage() {
        return slippage;
    }

    public String from() {
        return from;
    }

    public int bootstrap() {
        return bootstrap;
    }

    public long seed() {
        return seed;
    }

    public int monteCarloPaths() {
        return monteCarloPaths;
    }

    public double ruinThreshold() {
        return ruinThreshold;
    }

    public double currentLimit() {
        return currentLimit;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int testedHypotheses() {
        return testedHypotheses;
    }

    public List<Double> priorStrengths() {
        return priorStrengths;
    }

    public List<Double> sizeGrid() {
        return sizeGrid;
    }

    public List<Double> stopGrid() {
        return stopGrid;
    }

    public double portfolioDdLimit() {
        return portfolioDdLimit;
    }
}
