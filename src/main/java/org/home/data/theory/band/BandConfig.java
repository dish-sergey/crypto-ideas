package org.home.data.theory.band;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация модулей полосы и разорения (ТЗ 68 §2): все параметры — только
 * отсюда, конфиг целиком пишется в запись прогона.
 */
@Component
@Lazy
public class BandConfig {

    private final List<Double> epsilonGrid;
    private final List<Double> sigmaGrid;
    private final List<Double> piStarGrid;
    private final List<Double> gammaGrid;
    private final int gridPoints;
    private final int bandSteps;
    private final int impulseSteps;
    private final int impulsePaths;
    private final double impulseDt;
    private final int impulseGridSteps;
    private final List<Double> fixedCostShares;
    private final long seed;
    private final int mcPaths;
    private final int blockSize;
    private final List<Integer> blockGrid;
    private final double ruinThreshold;
    private final int ruinHorizonDays;
    private final double ladderStepDrop;
    private final int ladderSteps;
    private final int ladderHorizonDays;
    private final List<Integer> ladderHorizonGrid;
    private final double inventoryImbalance;
    private final int inventoryCeiling;
    private final int inventorySteps;
    private final double s5Size;
    private final int s5Concurrent;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public BandConfig(
            @Value("${theory.band.epsilon-grid}") String epsilonGrid,
            @Value("${theory.band.sigma-grid}") String sigmaGrid,
            @Value("${theory.band.pi-star-grid}") String piStarGrid,
            @Value("${theory.band.gamma-grid}") String gammaGrid,
            @Value("${theory.band.grid-points}") int gridPoints,
            @Value("${theory.band.band-steps}") int bandSteps,
            @Value("${theory.band.impulse-steps}") int impulseSteps,
            @Value("${theory.band.impulse-paths}") int impulsePaths,
            @Value("${theory.band.impulse-dt}") double impulseDt,
            @Value("${theory.band.impulse-grid-steps}") int impulseGridSteps,
            @Value("${theory.band.fixed-cost-shares}") String fixedCostShares,
            @Value("${theory.band.seed}") long seed,
            @Value("${theory.band.mc-paths}") int mcPaths,
            @Value("${theory.band.block-size}") int blockSize,
            @Value("${theory.band.block-grid}") String blockGrid,
            @Value("${theory.band.ruin-threshold}") double ruinThreshold,
            @Value("${theory.band.ruin-horizon-days}") int ruinHorizonDays,
            @Value("${theory.band.ladder-step-drop}") double ladderStepDrop,
            @Value("${theory.band.ladder-steps}") int ladderSteps,
            @Value("${theory.band.ladder-horizon-days}") int ladderHorizonDays,
            @Value("${theory.band.ladder-horizon-grid}") String ladderHorizonGrid,
            @Value("${theory.band.inventory-imbalance}") double inventoryImbalance,
            @Value("${theory.band.inventory-ceiling}") int inventoryCeiling,
            @Value("${theory.band.inventory-steps}") int inventorySteps,
            @Value("${theory.band.s5-size}") double s5Size,
            @Value("${theory.band.s5-concurrent}") int s5Concurrent) {
        this.epsilonGrid = parseDoubles(epsilonGrid);
        this.sigmaGrid = parseDoubles(sigmaGrid);
        this.piStarGrid = parseDoubles(piStarGrid);
        this.gammaGrid = parseDoubles(gammaGrid);
        this.gridPoints = gridPoints;
        this.bandSteps = bandSteps;
        this.impulseSteps = impulseSteps;
        this.impulsePaths = impulsePaths;
        this.impulseDt = impulseDt;
        this.impulseGridSteps = impulseGridSteps;
        this.fixedCostShares = parseDoubles(fixedCostShares);
        this.seed = seed;
        this.mcPaths = mcPaths;
        this.blockSize = blockSize;
        this.blockGrid = parseDoubles(blockGrid).stream().map(Double::intValue).toList();
        this.ruinThreshold = ruinThreshold;
        this.ruinHorizonDays = ruinHorizonDays;
        this.ladderStepDrop = ladderStepDrop;
        this.ladderSteps = ladderSteps;
        this.ladderHorizonDays = ladderHorizonDays;
        this.ladderHorizonGrid = parseDoubles(ladderHorizonGrid).stream().map(Double::intValue).toList();
        this.inventoryImbalance = inventoryImbalance;
        this.inventoryCeiling = inventoryCeiling;
        this.inventorySteps = inventorySteps;
        this.s5Size = s5Size;
        this.s5Concurrent = s5Concurrent;
    }

    private static List<Double> parseDoubles(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epsilon_grid", epsilonGrid);
        m.put("sigma_grid", sigmaGrid);
        m.put("pi_star_grid", piStarGrid);
        m.put("gamma_grid", gammaGrid);
        m.put("grid_points", gridPoints);
        m.put("band_steps", bandSteps);
        m.put("impulse_steps", impulseSteps);
        m.put("impulse_paths", impulsePaths);
        m.put("impulse_dt", impulseDt);
        m.put("impulse_grid_steps", impulseGridSteps);
        m.put("fixed_cost_shares", fixedCostShares);
        m.put("seed", seed);
        m.put("mc_paths", mcPaths);
        m.put("block_size", blockSize);
        m.put("block_grid", blockGrid);
        m.put("ruin_threshold", ruinThreshold);
        m.put("ruin_horizon_days", ruinHorizonDays);
        m.put("ladder_step_drop", ladderStepDrop);
        m.put("ladder_steps", ladderSteps);
        m.put("ladder_horizon_days", ladderHorizonDays);
        m.put("ladder_horizon_grid", ladderHorizonGrid);
        m.put("inventory_imbalance", inventoryImbalance);
        m.put("inventory_ceiling", inventoryCeiling);
        m.put("inventory_steps", inventorySteps);
        m.put("s5_size", s5Size);
        m.put("s5_concurrent", s5Concurrent);
        return m;
    }

    public List<Double> epsilonGrid() {
        return epsilonGrid;
    }

    public List<Double> sigmaGrid() {
        return sigmaGrid;
    }

    public List<Double> piStarGrid() {
        return piStarGrid;
    }

    public List<Double> gammaGrid() {
        return gammaGrid;
    }

    public int gridPoints() {
        return gridPoints;
    }

    public int bandSteps() {
        return bandSteps;
    }

    public int impulseSteps() {
        return impulseSteps;
    }

    public int impulsePaths() {
        return impulsePaths;
    }

    public double impulseDt() {
        return impulseDt;
    }

    public int impulseGridSteps() {
        return impulseGridSteps;
    }

    public List<Double> fixedCostShares() {
        return fixedCostShares;
    }

    public long seed() {
        return seed;
    }

    public int mcPaths() {
        return mcPaths;
    }

    public int blockSize() {
        return blockSize;
    }

    public List<Integer> blockGrid() {
        return blockGrid;
    }

    public double ruinThreshold() {
        return ruinThreshold;
    }

    public int ruinHorizonDays() {
        return ruinHorizonDays;
    }

    public double ladderStepDrop() {
        return ladderStepDrop;
    }

    public int ladderSteps() {
        return ladderSteps;
    }

    public int ladderHorizonDays() {
        return ladderHorizonDays;
    }

    /** Горизонты лестницы для прогона чувствительности (П6 док. 71). */
    public List<Integer> ladderHorizonGrid() {
        return ladderHorizonGrid;
    }

    public double inventoryImbalance() {
        return inventoryImbalance;
    }

    public int inventoryCeiling() {
        return inventoryCeiling;
    }

    public int inventorySteps() {
        return inventorySteps;
    }

    public double s5Size() {
        return s5Size;
    }

    public int s5Concurrent() {
        return s5Concurrent;
    }
}
