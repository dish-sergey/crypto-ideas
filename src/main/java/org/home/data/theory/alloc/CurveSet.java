package org.home.data.theory.alloc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пул кривых на одном календаре плюс бенчмарки и ставка кэша (ТЗ 65 §3).
 *
 * <p>Пул фиксируется <b>до</b> прогона (§8: исключать убыточные стратегии по
 * результату — look-ahead) и в отчёте перечисляется целиком, вместе со
 * стратегиями, для которых кривой нет, и причиной.
 *
 * @param days      календарь, 'YYYY-MM-DD' UTC
 * @param dayMs     тот же календарь в epoch ms UTC (§2: время — целое, UTC)
 * @param pool      кривые пула в фиксированном порядке
 * @param benchmarks кривые бенчмарков (BH_BTC, SMA200, …)
 * @param cash      дневная ставка кэша по датам (§3.2: не константа 8%)
 * @param regime    состояние детектора по дням (для аллокатора DETECTOR), может быть null
 * @param cyclePhase фаза цикла по дням (модификатор матрицы), может быть null
 * @param missing   стратегии пула без кривой: id → причина (идёт в отчёт)
 */
public record CurveSet(String[] days, long[] dayMs, List<Curve> pool, Map<String, Curve> benchmarks,
                       double[] cash, String[] regime, String[] cyclePhase, Map<String, String> missing) {

    public int length() {
        return days.length;
    }

    public int size() {
        return pool.size();
    }

    public String[] ids() {
        String[] out = new String[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            out[i] = pool.get(i).id();
        }
        return out;
    }

    /** Матрица доходностей [день][стратегия] на задействованный капитал. */
    public double[][] retMatrix() {
        double[][] m = new double[length()][pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            double[] r = pool.get(i).ret();
            for (int t = 0; t < length(); t++) {
                m[t][i] = r[t];
            }
        }
        return m;
    }

    public boolean[][] availMatrix() {
        boolean[][] m = new boolean[length()][pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            boolean[] a = pool.get(i).available();
            for (int t = 0; t < length(); t++) {
                m[t][i] = a[t];
            }
        }
        return m;
    }

    /** Тот же набор без одной стратегии — для leave-one-out (§5). */
    public CurveSet without(String id) {
        List<Curve> reduced = new ArrayList<>();
        for (Curve c : pool) {
            if (!c.id().equals(id)) {
                reduced.add(c);
            }
        }
        Map<String, String> miss = new LinkedHashMap<>(missing);
        miss.put(id, "leave-one-out");
        return new CurveSet(days, dayMs, reduced, benchmarks, cash, regime, cyclePhase, miss);
    }

    /** Есть ли в пуле хоть одна in-sample кривая — шапка отчёта (§3.3, §6.1). */
    public boolean anyInSample() {
        return pool.stream().anyMatch(c -> c.kind() == CurveKind.IN_SAMPLE);
    }
}
