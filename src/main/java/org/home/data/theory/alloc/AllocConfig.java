package org.home.data.theory.alloc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Конфигурация стенда аллокации. ТЗ 65 §2: <b>все</b> параметры стратегий,
 * издержек и порогов — только из конфига, никаких констант в коде; конфиг
 * целиком пишется в запись прогона.
 *
 * <p>Значения — в {@code application.properties}, префикс {@code theory.alloc.*};
 * любое переопределяется аргументом запуска
 * ({@code --theory.alloc.switch-cost=0.002}) и попадает в запись прогона.
 */
@Component
@Lazy
public class AllocConfig {

    private final String from;
    private final List<String> pool;
    private final Map<String, String> excluded;
    private final double cost;
    private final double switchCost;
    private final double s1GatePer8h;
    private final double s1SwitchCost;
    private final double s1RebalanceK;
    private final int s2s9Lookback;
    private final int s2s9Hold;
    private final int s2s9Top;
    private final int s2s9MinHistory;
    private final int s2s9MinUniverse;
    private final int s3Window;
    private final double s3Entry;
    private final String s4YieldsRaw;
    private final TreeMap<Integer, Double> s4Yields = new TreeMap<>();
    private final double s5MinPct;
    private final int s5Lead;
    private final double s5Stop;
    private final double s5MaxFundingCost;
    private final double s5TradeCost;
    private final double s5Slippage;
    private final int s6Steps;
    private final double s6StepDrop;
    private final double s7CrashThreshold;
    private final double hedgeEtaMultiplier;
    private final int hedgeWindow;
    private final double lazyThreshold;
    private final double egEta;
    private final double onsBeta;
    private final double onsEpsilon;
    private final long seed;
    private final int randomRedrawDays;
    private final int bestFixedIterations;
    private final double bestFixedStep;
    private final int randomFixedDraws;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AllocConfig(
            @Value("${theory.alloc.from}") String from,
            @Value("${theory.alloc.pool}") String pool,
            @Value("${theory.alloc.excluded}") String excluded,
            @Value("${theory.alloc.cost}") double cost,
            @Value("${theory.alloc.switch-cost}") double switchCost,
            @Value("${theory.alloc.s1-gate-per-8h}") double s1GatePer8h,
            @Value("${theory.alloc.s1-switch-cost}") double s1SwitchCost,
            @Value("${theory.alloc.s1-rebalance-k}") double s1RebalanceK,
            @Value("${theory.alloc.s2s9-lookback}") int s2s9Lookback,
            @Value("${theory.alloc.s2s9-hold}") int s2s9Hold,
            @Value("${theory.alloc.s2s9-top}") int s2s9Top,
            @Value("${theory.alloc.s2s9-min-history}") int s2s9MinHistory,
            @Value("${theory.alloc.s2s9-min-universe}") int s2s9MinUniverse,
            @Value("${theory.alloc.s3-window}") int s3Window,
            @Value("${theory.alloc.s3-entry}") double s3Entry,
            @Value("${theory.alloc.s4-yields}") String s4Yields,
            @Value("${theory.alloc.s5-min-pct}") double s5MinPct,
            @Value("${theory.alloc.s5-lead}") int s5Lead,
            @Value("${theory.alloc.s5-stop}") double s5Stop,
            @Value("${theory.alloc.s5-max-funding-cost}") double s5MaxFundingCost,
            @Value("${theory.alloc.s5-trade-cost}") double s5TradeCost,
            @Value("${theory.alloc.s5-slippage}") double s5Slippage,
            @Value("${theory.alloc.s6-steps}") int s6Steps,
            @Value("${theory.alloc.s6-step-drop}") double s6StepDrop,
            @Value("${theory.alloc.s7-crash-threshold}") double s7CrashThreshold,
            @Value("${theory.alloc.hedge-eta-multiplier}") double hedgeEtaMultiplier,
            @Value("${theory.alloc.hedge-window}") int hedgeWindow,
            @Value("${theory.alloc.lazy-threshold}") double lazyThreshold,
            @Value("${theory.alloc.eg-eta}") double egEta,
            @Value("${theory.alloc.ons-beta}") double onsBeta,
            @Value("${theory.alloc.ons-epsilon}") double onsEpsilon,
            @Value("${theory.alloc.seed}") long seed,
            @Value("${theory.alloc.random-redraw-days}") int randomRedrawDays,
            @Value("${theory.alloc.best-fixed-iterations}") int bestFixedIterations,
            @Value("${theory.alloc.best-fixed-step}") double bestFixedStep,
            @Value("${theory.alloc.random-fixed-draws}") int randomFixedDraws) {
        this.from = from;
        this.pool = Arrays.stream(pool.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.excluded = parseExcluded(excluded);
        this.cost = cost;
        this.switchCost = switchCost;
        this.s1GatePer8h = s1GatePer8h;
        this.s1SwitchCost = s1SwitchCost;
        this.s1RebalanceK = s1RebalanceK;
        this.s2s9Lookback = s2s9Lookback;
        this.s2s9Hold = s2s9Hold;
        this.s2s9Top = s2s9Top;
        this.s2s9MinHistory = s2s9MinHistory;
        this.s2s9MinUniverse = s2s9MinUniverse;
        this.s3Window = s3Window;
        this.s3Entry = s3Entry;
        this.s4YieldsRaw = s4Yields;
        for (String part : s4Yields.split(",")) {
            String[] kv = part.split(":");
            this.s4Yields.put(Integer.parseInt(kv[0].trim()), Double.parseDouble(kv[1].trim()));
        }
        this.s5MinPct = s5MinPct;
        this.s5Lead = s5Lead;
        this.s5Stop = s5Stop;
        this.s5MaxFundingCost = s5MaxFundingCost;
        this.s5TradeCost = s5TradeCost;
        this.s5Slippage = s5Slippage;
        this.s6Steps = s6Steps;
        this.s6StepDrop = s6StepDrop;
        this.s7CrashThreshold = s7CrashThreshold;
        this.hedgeEtaMultiplier = hedgeEtaMultiplier;
        this.hedgeWindow = hedgeWindow;
        this.lazyThreshold = lazyThreshold;
        this.egEta = egEta;
        this.onsBeta = onsBeta;
        this.onsEpsilon = onsEpsilon;
        this.seed = seed;
        this.randomRedrawDays = randomRedrawDays;
        this.bestFixedIterations = bestFixedIterations;
        this.bestFixedStep = bestFixedStep;
        this.randomFixedDraws = randomFixedDraws;
    }

    private static Map<String, String> parseExcluded(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                out.put(trimmed, "причина не указана");
            } else {
                out.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
            }
        }
        return out;
    }

    /** Конфиг целиком — в запись прогона (§2 «Воспроизводимость»). */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("pool", pool);
        m.put("excluded", excluded);
        m.put("cost", cost);
        m.put("switch_cost", switchCost);
        m.put("s1_gate_per_8h", s1GatePer8h);
        m.put("s1_switch_cost", s1SwitchCost);
        m.put("s1_rebalance_k", s1RebalanceK);
        m.put("s2s9_lookback", s2s9Lookback);
        m.put("s2s9_hold", s2s9Hold);
        m.put("s2s9_top", s2s9Top);
        m.put("s2s9_min_history", s2s9MinHistory);
        m.put("s2s9_min_universe", s2s9MinUniverse);
        m.put("s3_window", s3Window);
        m.put("s3_entry", s3Entry);
        m.put("s4_yields", s4YieldsRaw);
        m.put("s5_min_pct", s5MinPct);
        m.put("s5_lead", s5Lead);
        m.put("s5_stop", s5Stop);
        m.put("s5_max_funding_cost", s5MaxFundingCost);
        m.put("s5_trade_cost", s5TradeCost);
        m.put("s5_slippage", s5Slippage);
        m.put("s6_steps", s6Steps);
        m.put("s6_step_drop", s6StepDrop);
        m.put("s7_crash_threshold", s7CrashThreshold);
        m.put("hedge_eta_multiplier", hedgeEtaMultiplier);
        m.put("hedge_window", hedgeWindow);
        m.put("lazy_threshold", lazyThreshold);
        m.put("eg_eta", egEta);
        m.put("ons_beta", onsBeta);
        m.put("ons_epsilon", onsEpsilon);
        m.put("seed", seed);
        m.put("random_redraw_days", randomRedrawDays);
        m.put("best_fixed_iterations", bestFixedIterations);
        m.put("best_fixed_step", bestFixedStep);
        m.put("random_fixed_draws", randomFixedDraws);
        return m;
    }

    public double s4Yield(int year) {
        Map.Entry<Integer, Double> e = s4Yields.floorEntry(year);
        return e == null ? s4Yields.firstEntry().getValue() : e.getValue();
    }

    public String from() {
        return from;
    }

    public List<String> pool() {
        return pool;
    }

    public Map<String, String> excluded() {
        return excluded;
    }

    public double cost() {
        return cost;
    }

    public double switchCost() {
        return switchCost;
    }

    public double s1GatePer8h() {
        return s1GatePer8h;
    }

    public double s1SwitchCost() {
        return s1SwitchCost;
    }

    public double s1RebalanceK() {
        return s1RebalanceK;
    }

    public int s2s9Lookback() {
        return s2s9Lookback;
    }

    public int s2s9Hold() {
        return s2s9Hold;
    }

    public int s2s9Top() {
        return s2s9Top;
    }

    public int s2s9MinHistory() {
        return s2s9MinHistory;
    }

    public int s2s9MinUniverse() {
        return s2s9MinUniverse;
    }

    public int s3Window() {
        return s3Window;
    }

    public double s3Entry() {
        return s3Entry;
    }

    public String s4YieldsRaw() {
        return s4YieldsRaw;
    }

    public double s5MinPct() {
        return s5MinPct;
    }

    public int s5Lead() {
        return s5Lead;
    }

    public double s5Stop() {
        return s5Stop;
    }

    public double s5MaxFundingCost() {
        return s5MaxFundingCost;
    }

    public double s5TradeCost() {
        return s5TradeCost;
    }

    public double s5Slippage() {
        return s5Slippage;
    }

    public int s6Steps() {
        return s6Steps;
    }

    public double s6StepDrop() {
        return s6StepDrop;
    }

    public double s7CrashThreshold() {
        return s7CrashThreshold;
    }

    public double hedgeEtaMultiplier() {
        return hedgeEtaMultiplier;
    }

    public int hedgeWindow() {
        return hedgeWindow;
    }

    public double lazyThreshold() {
        return lazyThreshold;
    }

    public double egEta() {
        return egEta;
    }

    public double onsBeta() {
        return onsBeta;
    }

    public double onsEpsilon() {
        return onsEpsilon;
    }

    public long seed() {
        return seed;
    }

    public int randomRedrawDays() {
        return randomRedrawDays;
    }

    public int bestFixedIterations() {
        return bestFixedIterations;
    }

    public double bestFixedStep() {
        return bestFixedStep;
    }

    public int randomFixedDraws() {
        return randomFixedDraws;
    }
}
