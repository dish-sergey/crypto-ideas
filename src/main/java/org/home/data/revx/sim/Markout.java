package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;

/**
 * Неблагоприятный отбор — решающее измерение (ТЗ §4.5).
 *
 * <pre>markout(Δ) = (fair(t_fill + Δ) − fair(t_fill)) · side</pre>
 *
 * База — справедливая цена в момент исполнения, а НЕ цена заявки. Разница
 * принципиальна: при базе «цена заявки» markout содержит в себе захват спреда,
 * и величина «захват + markout» считает захват дважды — при нулевом дрейфе она
 * даёт 2·d вместо d. С базой «справедливая цена» markout измеряет ровно то, ради
 * чего он нужен: что рынок сделал с нами ПОСЛЕ исполнения.
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
            // База — справедливая цена В МОМЕНТ ИСПОЛНЕНИЯ, а не цена заявки.
            // Иначе markout(Δ) содержит в себе захват спреда целиком, и сумма
            // «захват + markout» считает захват ДВАЖДЫ: при нулевом дрейфе она
            // даёт 2·d вместо d. Здесь markout — только то, что рынок сделал с нами
            // ПОСЛЕ исполнения, и тогда край = захват + markout честно.
            values.add(fill.side().sign() * (entry.getValue() - fill.fairAtFill()) * fill.qty());
        }
        values.sort(Comparator.naturalOrder());
        return new Stats(horizonMs, values.size(), mean(values),
                percentile(values, 0.50), percentile(values, 0.25), percentile(values, 0.75));
    }

    /**
     * Стандартная ошибка среднего markout, в базисных пунктах оборота.
     *
     * Нужна там, где по markout принимаются решения. Неблагоприятный отбор на
     * боковом окне измерен как 0.63 б.п. по 28 исполнениям — и без этой величины
     * непонятно, отличается ли он от 2.71 на ралли или это одна и та же цифра,
     * увиденная дважды. От ответа зависит вывод «оптимальный отступ гуляет по
     * режимам» (док. 103 §4), поэтому ошибка обязана стоять рядом с оценкой.
     */
    /**
     * Ð¡ÑÐ°Ð½Ð´Ð°ÑÑÐ½Ð°Ñ Ð¾ÑÐ¸Ð±ÐºÐ° Ð§ÐÐ¡Ð¢ÐÐÐ ÐÐ ÐÐ¯ Ð½Ð° Ð¸ÑÐ¿Ð¾Ð»Ð½ÐµÐ½Ð¸Ðµ, Ð±.Ð¿.
     *
     * ÐÑÐ¶Ð½Ð° Ð¾ÑÐ´ÐµÐ»ÑÐ½Ð¾ Ð¾Ñ {@link #standardErrorBp}, Ð¸ ÑÑÐ¾ Ð½Ðµ Ð¿ÐµÐ´Ð°Ð½ÑÐ¸Ð·Ð¼. ÐÑÐ°Ð¹
     * Ð¾Ð¿ÑÐµÐ´ÐµÐ»ÑÐ½ ÐºÐ°Ðº {@code Ð·Ð°ÑÐ²Ð°Ñ + markout}, Ð¿Ð¾ÑÑÐ¾Ð¼Ñ Ð¿Ð¾ Ð»ÑÐ±Ð¾Ð¼Ñ ÑÐ°Ð·Ð±Ð¸ÐµÐ½Ð¸Ñ
     * {@code ÎÐºÑÐ°Ð¹ = ÎÐ·Ð°ÑÐ²Ð°Ñ + Îmarkout} â ÑÐ¾Ð¶Ð´ÐµÑÑÐ²ÐµÐ½Ð½Ð¾. ÐÐ½Ð°ÑÐ¸Ñ Â«markout ÑÑÐ¶Ðµ, Ð°
     * Ð·Ð°ÑÐ²Ð°Ñ ÑÐ¾Ð²Ð½Ð¾ Ð½Ð° ÑÑÐ¾Ð»ÑÐºÐ¾ Ð¶Ðµ Ð²ÑÑÐµÂ» ÐµÑÑÑ ÐÐÐÐ ÑÐ°ÐºÑ, Ð·Ð°Ð¿Ð¸ÑÐ°Ð½Ð½ÑÐ¹ Ð´Ð²Ð°Ð¶Ð´Ñ, Ð°
     * ÐµÐ´Ð¸Ð½ÑÑÐ²ÐµÐ½Ð½Ð°Ñ Ð½ÐµÐ·Ð°Ð²Ð¸ÑÐ¸Ð¼Ð°Ñ Ð²ÐµÐ»Ð¸ÑÐ¸Ð½Ð° â ÑÐ°Ð¼ ÐºÑÐ°Ð¹ (Ð´Ð¾Ðº. 123 Â§2).
     *
     * Ð ÐºÑÐ°Ð¹ Ð¨Ð£ÐÐÐÐ markout, Ð° Ð½Ðµ ÑÐ¸ÑÐµ: Ð² Ð½ÐµÐ³Ð¾ Ð²ÑÐ¾Ð´Ð¸Ñ Ð¸ Ð´Ð¸ÑÐ¿ÐµÑÑÐ¸Ñ Ð·Ð°ÑÐ²Ð°ÑÐ°.
     * ÐÐ¾ÑÑÐ¾Ð¼Ñ Ð²ÑÐ²Ð¾Ð´ Â«ÐºÑÐ°Ð¹ Ð½Ðµ Ð¾ÑÐ»Ð¸ÑÐ°ÐµÑÑÑÂ» Ð±ÐµÐ· ÑÑÐ¾Ð¹ Ð¾ÑÐ¸Ð±ÐºÐ¸ Ð½Ðµ Ð¿Ð¾Ð´ÑÐ²ÐµÑÐ¶Ð´ÑÐ½ â Ð¾Ð½ Ð»Ð¸ÑÑ
     * Ð½Ðµ Ð¾Ð¿ÑÐ¾Ð²ÐµÑÐ³Ð½ÑÑ.
     */
    public static double netEdgeStandardErrorBp(List<Fill> fills,
                                                NavigableMap<Long, Double> fairSeries,
                                                long horizonMs) {
        List<Double> perFill = new ArrayList<>();
        long lastFairMs = fairSeries.isEmpty() ? Long.MIN_VALUE : fairSeries.lastKey();
        for (Fill fill : fills) {
            long at = fill.tsMs() + horizonMs;
            if (at > lastFairMs) {
                continue;
            }
            var entry = fairSeries.floorEntry(at);
            double notional = fill.notional();
            if (entry == null || !(notional > 0)) {
                continue;
            }
            double markout = fill.side().sign() * (entry.getValue() - fill.fairAtFill()) * fill.qty();
            perFill.add((fill.spreadCapture() + markout) / notional * 10_000);
        }
        if (perFill.size() < 2) {
            return Double.NaN;
        }
        double mean = mean(perFill);
        double sumSq = 0;
        for (double v : perFill) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (perFill.size() - 1) / perFill.size());
    }


    public static double standardErrorBp(List<Fill> fills, NavigableMap<Long, Double> fairSeries,
                                         long horizonMs) {
        List<Double> perFill = new ArrayList<>();
        long lastFairMs = fairSeries.isEmpty() ? Long.MIN_VALUE : fairSeries.lastKey();
        for (Fill fill : fills) {
            long at = fill.tsMs() + horizonMs;
            if (at > lastFairMs) {
                continue;
            }
            var entry = fairSeries.floorEntry(at);
            double notional = fill.notional();
            if (entry == null || !(notional > 0)) {
                continue;
            }
            double value = fill.side().sign() * (entry.getValue() - fill.fairAtFill()) * fill.qty();
            perFill.add(value / notional * 10_000);
        }
        if (perFill.size() < 2) {
            return Double.NaN;
        }
        double mean = mean(perFill);
        double sumSq = 0;
        for (double v : perFill) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (perFill.size() - 1)) / Math.sqrt(perFill.size());
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
