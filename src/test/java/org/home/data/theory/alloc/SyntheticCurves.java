package org.home.data.theory.alloc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Сборка синтетических пулов для тестов §7 ТЗ 65. */
final class SyntheticCurves {

    private SyntheticCurves() {
    }

    /** Пул из готовых рядов доходности; доступность — везде true, ставка кэша — 0. */
    static CurveSet of(double[]... rets) {
        int t = rets[0].length;
        boolean[][] avail = new boolean[rets.length][t];
        for (boolean[] a : avail) {
            Arrays.fill(a, true);
        }
        return of(rets, avail, new double[t]);
    }

    static CurveSet of(double[][] rets, boolean[][] avail, double[] cash) {
        int t = rets[0].length;
        String[] days = new String[t];
        long[] dayMs = new long[t];
        LocalDate start = LocalDate.parse("2020-01-01");
        for (int i = 0; i < t; i++) {
            days[i] = start.plusDays(i).toString();
            dayMs[i] = start.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        List<Curve> pool = new ArrayList<>();
        for (int i = 0; i < rets.length; i++) {
            pool.add(new Curve("S" + (i + 1), CurveKind.IN_SAMPLE, rets[i], avail[i], new double[t], "синтетика"));
        }
        return new CurveSet(days, dayMs, pool, new LinkedHashMap<>(), cash,
                new String[t], new String[t], Map.of());
    }

    static boolean[] all(int t, boolean value) {
        boolean[] a = new boolean[t];
        Arrays.fill(a, value);
        return a;
    }

    static double[] constant(int t, double value) {
        double[] r = new double[t];
        Arrays.fill(r, value);
        return r;
    }
}
