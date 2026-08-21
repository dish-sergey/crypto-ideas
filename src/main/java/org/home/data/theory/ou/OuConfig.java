package org.home.data.theory.ou;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфигурация предтеста OU (ТЗ 67 §3): все пороги — только отсюда, конфиг
 * целиком пишется в запись прогона. Пороги допуска фиксируются <b>до</b>
 * прогона и не смягчаются, если ничего не прошло (§8).
 */
@Component
@Lazy
public class OuConfig {

    private final double level;
    private final int lags;
    private final int bootstrap;
    private final long seed;
    private final int kappaWindow;
    private final double kappaCvThreshold;
    private final int minEpisodes;
    private final double breakToSigma;
    private final double holdingHorizonDays;
    private final double olsMleDivergence;
    private final double basisThreshold;
    private final double basisSkewToleranceMs;
    private final double depegThreshold;
    private final double depegMinHours;
    private final double fundDiffThreshold;
    private final double pairZThreshold;
    private final int pairWindow;
    private final String from;
    private final String basisFrom;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OuConfig(
            @Value("${theory.ou.level}") double level,
            @Value("${theory.ou.lags}") int lags,
            @Value("${theory.ou.bootstrap}") int bootstrap,
            @Value("${theory.ou.seed}") long seed,
            @Value("${theory.ou.kappa-window}") int kappaWindow,
            @Value("${theory.ou.kappa-cv-threshold}") double kappaCvThreshold,
            @Value("${theory.ou.min-episodes}") int minEpisodes,
            @Value("${theory.ou.break-to-sigma}") double breakToSigma,
            @Value("${theory.ou.holding-horizon-days}") double holdingHorizonDays,
            @Value("${theory.ou.ols-mle-divergence}") double olsMleDivergence,
            @Value("${theory.ou.basis-threshold}") double basisThreshold,
            @Value("${theory.ou.basis-skew-tolerance-ms}") double basisSkewToleranceMs,
            @Value("${theory.ou.depeg-threshold}") double depegThreshold,
            @Value("${theory.ou.depeg-min-hours}") double depegMinHours,
            @Value("${theory.ou.funddiff-threshold}") double fundDiffThreshold,
            @Value("${theory.ou.pair-z-threshold}") double pairZThreshold,
            @Value("${theory.ou.pair-window}") int pairWindow,
            @Value("${theory.ou.from}") String from,
            @Value("${theory.ou.basis-from}") String basisFrom) {
        this.level = level;
        this.lags = lags;
        this.bootstrap = bootstrap;
        this.seed = seed;
        this.kappaWindow = kappaWindow;
        this.kappaCvThreshold = kappaCvThreshold;
        this.minEpisodes = minEpisodes;
        this.breakToSigma = breakToSigma;
        this.holdingHorizonDays = holdingHorizonDays;
        this.olsMleDivergence = olsMleDivergence;
        this.basisThreshold = basisThreshold;
        this.basisSkewToleranceMs = basisSkewToleranceMs;
        this.depegThreshold = depegThreshold;
        this.depegMinHours = depegMinHours;
        this.fundDiffThreshold = fundDiffThreshold;
        this.pairZThreshold = pairZThreshold;
        this.pairWindow = pairWindow;
        this.from = from;
        this.basisFrom = basisFrom;
    }

    /** Конфиг целиком — в запись прогона. */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("lags", lags);
        m.put("bootstrap", bootstrap);
        m.put("seed", seed);
        m.put("kappa_window", kappaWindow);
        m.put("kappa_cv_threshold", kappaCvThreshold);
        m.put("min_episodes", minEpisodes);
        m.put("break_to_sigma", breakToSigma);
        m.put("holding_horizon_days", holdingHorizonDays);
        m.put("ols_mle_divergence", olsMleDivergence);
        m.put("basis_threshold", basisThreshold);
        m.put("basis_skew_tolerance_ms", basisSkewToleranceMs);
        m.put("depeg_threshold", depegThreshold);
        m.put("depeg_min_hours", depegMinHours);
        m.put("funddiff_threshold", fundDiffThreshold);
        m.put("pair_z_threshold", pairZThreshold);
        m.put("pair_window", pairWindow);
        m.put("from", from);
        m.put("basis_from", basisFrom);
        return m;
    }

    public double level() {
        return level;
    }

    public int lags() {
        return lags;
    }

    public int bootstrap() {
        return bootstrap;
    }

    public long seed() {
        return seed;
    }

    public int kappaWindow() {
        return kappaWindow;
    }

    public double kappaCvThreshold() {
        return kappaCvThreshold;
    }

    public int minEpisodes() {
        return minEpisodes;
    }

    public double breakToSigma() {
        return breakToSigma;
    }

    public double holdingHorizonDays() {
        return holdingHorizonDays;
    }

    public double olsMleDivergence() {
        return olsMleDivergence;
    }

    public double basisThreshold() {
        return basisThreshold;
    }

    public double basisSkewToleranceMs() {
        return basisSkewToleranceMs;
    }

    public double depegThreshold() {
        return depegThreshold;
    }

    public double depegMinHours() {
        return depegMinHours;
    }

    public double fundDiffThreshold() {
        return fundDiffThreshold;
    }

    public double pairZThreshold() {
        return pairZThreshold;
    }

    public int pairWindow() {
        return pairWindow;
    }

    public String from() {
        return from;
    }

    /** Начало НЕПРЕРЫВНОГО минутного окна базиса; до него лежит стресс-выборка. */
    public String basisFrom() {
        return basisFrom;
    }
}
