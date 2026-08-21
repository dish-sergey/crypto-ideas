package org.home.data.theory.ou;

import java.util.ArrayList;
import java.util.List;

/**
 * Ряд-кандидат блока B (ТЗ 67 §2) с определением эпизода отклонения.
 *
 * @param id            идентификатор: BASIS, DEPEG, FUNDDIFF, PAIRSPREAD, NEG_BTC, …
 * @param description   что это за величина и откуда взята
 * @param times         моменты наблюдений в единицах времени ряда (дни или часы)
 * @param values        значения величины
 * @param inEpisode     маска «идёт эпизод отклонения» — определяется <b>до</b> прогона
 *                      и для каждой величины отдельно (§5.2)
 * @param episodeRule   словесное определение эпизода — идёт в отчёт дословно
 * @param timeUnit      единица времени («дни», «часы») для полупериода
 * @param droppedShare  доля наблюдений, отброшенных по рассинхронизации (для BASIS, §3)
 * @param note          ограничения ряда, переносимые в отчёт
 * @param stitched      ряд СШИТ из несмежных окон (стресс-выборка): статистики
 *                      эпизодов на нём законны, а тесты стационарности и
 *                      структурного сдвига — нет, разрывы между окнами дадут
 *                      ложный «сдвиг уровня» и ложную нестабильность κ
 */
public record OuSeries(String id, String description, double[] times, double[] values,
                       boolean[] inEpisode, String episodeRule, String timeUnit,
                       double droppedShare, String note, boolean stitched) {

    public int length() {
        return values.length;
    }

    /** Шаги между наблюдениями: пропуски и нерегулярность обрабатываются как есть (§7.3). */
    public double[] steps() {
        double[] dt = new double[values.length - 1];
        for (int i = 0; i < dt.length; i++) {
            dt[i] = times[i + 1] - times[i];
        }
        return dt;
    }

    /** Эпизод отклонения: непрерывный отрезок с {@code inEpisode = true}. */
    public record Episode(int from, int to, double duration, double depth, boolean returned) {
    }

    /**
     * Эпизоды отклонения: от момента, когда величина вышла за порог, до
     * <b>возврата к уровню</b> {@code theta}, а не до момента, когда она первый
     * раз нырнула обратно под порог.
     *
     * <p>Так определяется то, что реально живёт: отклонение в 0.9 порога — всё
     * ещё отклонение, и позиция в этот момент не закрыта. При «до порога»
     * длительность эпизода систематически занижается, и T2 превращается в
     * подбрасывание монеты даже на настоящем OU (поймано контролем
     * {@code POS_SYN}).
     *
     * <p>«Вернулся» — эпизод закончился пересечением уровня, а не концом данных.
     */
    public List<Episode> episodes(double theta) {
        List<Episode> out = new ArrayList<>();
        int start = -1;
        int side = 0;
        for (int i = 0; i < inEpisode.length; i++) {
            if (start < 0) {
                if (inEpisode[i]) {
                    start = i;
                    side = values[i] >= theta ? 1 : -1;
                }
                continue;
            }
            boolean crossedLevel = side > 0 ? values[i] <= theta : values[i] >= theta;
            if (crossedLevel) {
                out.add(episode(start, i, theta, true));
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(episode(start, inEpisode.length - 1, theta, false));
        }
        return out;
    }

    private Episode episode(int from, int to, double theta, boolean returned) {
        double depth = 0;
        for (int i = from; i <= to; i++) {
            depth = Math.max(depth, Math.abs(values[i] - theta));
        }
        return new Episode(from, to, times[to] - times[from], depth, returned);
    }

    /** Медианная длительность эпизода — правая часть теста T2 (§5.2). */
    public double medianEpisodeDuration(double theta) {
        List<Episode> eps = episodes(theta);
        if (eps.isEmpty()) {
            return Double.NaN;
        }
        double[] d = eps.stream().mapToDouble(Episode::duration).sorted().toArray();
        return d[d.length / 2];
    }
}
