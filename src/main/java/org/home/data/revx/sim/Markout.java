package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;

/**
 * Неблагоприятный отбор — решающее измерение (ТЗ §4.5).
 *
 * <pre>markout(Δ) = (fair(t_fill + Δ) − fill_price) · side</pre>
 *
 * Как читать (ТЗ §4.5):
 *  - markout(0) > 0 и markout(60с) ≈ markout(0) — исполняет неинформированный
 *    поток, гипотеза подтверждается;
 *  - markout(0) > 0, но markout(60с) заметно ниже — классический неблагоприятный
 *    отбор: спред реален, но забирает его не тот, кто котирует;
 *  - markout(60с) < 0 — стратегия мертва, что бы ни показывала эквити.
 */
public final class Markout {

    public record Stats(long horizonMs, int fills, double mean, double median, double q25, double q75) {
    }

    private Markout() {
    }

    /**
     * Справедливая цена берётся по последнему известному значению НЕ ПОЗЖЕ
     * момента t+Δ: заглядывать в будущее дальше горизонта нельзя (ТЗ §4.6 п.4).
     * Если данных за горизонтом нет вовсе, исполнение в статистику не входит —
     * иначе хвост выборки молча смещал бы результат.
     */
    /**
     * То же по одной стороне. Нужно как предохранитель против беты: на растущем
     * рынке markout покупок положителен, а продаж отрицателен просто потому, что
     * цена шла вверх, и агрегат по обеим сторонам может показать «край» там, где
     * работало направление. Если положительный markout держится ТОЛЬКО на покупках,
     * это не преимущество котирования, а незакрытая длинная позиция.
     */
    public static Stats compute(List<Fill> fills, NavigableMap<Long, Double> fairSeries,
                                long horizonMs, Side side) {
        return compute(fills.stream().filter(f -> f.side() == side).toList(), fairSeries, horizonMs);
    }

    /**
     * Исполнения, для которых горизонт целиком лежит внутри данных. Нужно, чтобы
     * числитель и знаменатель чистого края считались по ОДНОМУ множеству: иначе
     * захват берётся по всем исполнениям, markout — по тем, что успели дожить до
     * горизонта, и их разность — не край, а разность двух разных выборок.
     */
    public static List<Fill> withHorizon(List<Fill> fills, NavigableMap<Long, Double> fairSeries,
                                         long horizonMs) {
        if (fairSeries.isEmpty()) {
            return List.of();
        }
        long lastFairMs = fairSeries.lastKey();
        return fills.stream()
                .filter(f -> f.tsMs() + horizonMs <= lastFairMs)
                .filter(f -> fairSeries.floorEntry(f.tsMs() + horizonMs) != null)
                .toList();
    }

    public static Stats compute(List<Fill> fills, NavigableMap<Long, Double> fairSeries, long horizonMs) {
        List<Double> values = new ArrayList<>();
        long lastFairMs = fairSeries.isEmpty() ? Long.MIN_VALUE : fairSeries.lastKey();
        for (Fill fill : fills) {
            long at = fill.tsMs() + horizonMs;
            if (at > lastFairMs) {
                continue;
            }
            var entry = fairSeries.floorEntry(at);
            if (entry == null) {
                continue;
            }
            values.add(fill.side().sign() * (entry.getValue() - fill.price()) * fill.qty());
        }
        values.sort(Comparator.naturalOrder());
        return new Stats(horizonMs, values.size(), mean(values),
                percentile(values, 0.50), percentile(values, 0.25), percentile(values, 0.75));
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? Double.NaN
                : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int idx = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(idx);
    }
}
