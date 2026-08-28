package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.function.Predicate;

/**
 * Задание Z9 (док. 109 §III): зависит ли пошлина от того, КТО стоит внутри нас.
 *
 * Две взаимоисключающие гипотезы с противоположными рекомендациями:
 *
 * <ul>
 *   <li><b>Фильтр</b> — узкий котировщик собирает мелкие касания, до нас доходят
 *       только настоящие свипы. Тогда наш markout на таких исполнениях ЛУЧШЕ,
 *       конкуренция внутри безвредна, менять ничего не надо.</li>
 *   <li><b>Снятие сливок</b> — быстрый котировщик успевает убрать заявку перед
 *       информированным потоком (у него миллисекунды, у нас секунда) и оставляет
 *       токсичное нам. Тогда markout ХУЖЕ, а измеренная лестница по отступу
 *       загрязнена: узкий конец ещё отрицательнее, чем показано.</li>
 * </ul>
 *
 * Третий исход — разницы нет — не хуже первых двух: он закрывает переменную и
 * говорит, что {@code c(d)} есть полное описание пошлины.
 *
 * ⚠️ Сравнивать между корзинами можно ТОЛЬКО markout. Объём внутри нас входит в
 * модель исполнения, поэтому число исполнений по корзинам различается по
 * построению, и вывод из него был бы тавтологией.
 */
public final class QueueCost {

    /**
     * @param label корзина
     * @param fills сколько исполнений (справочно — сравнивать НЕЛЬЗЯ)
     * @param markoutBp средний markout на единицу оборота
     * @param standardErrorBp стандартная ошибка среднего
     * @param captureBp средний захват на единицу оборота
     */
    public record Group(String label, int fills, double markoutBp, double standardErrorBp,
                        double captureBp) {

        /** Чистый край корзины: захват плюс то, что от него осталось к горизонту. */
        public double netEdgeBp() {
            return captureBp + markoutBp;
        }
    }

    private QueueCost() {
    }

    /**
     * Разбиение исполнений на корзины по состоянию книги.
     *
     * @param horizonMs горизонт markout
     */
    public static List<Group> byPresenceInside(List<Fill> fills,
                                               NavigableMap<Long, Double> fairSeries,
                                               long horizonMs) {
        List<Group> out = new ArrayList<>();
        out.add(group("мы лучшая цена", fills, fairSeries, horizonMs,
                f -> f.book() != null && f.book().weAreBest()));
        out.add(group("внутри нас кто-то есть", fills, fairSeries, horizonMs,
                f -> f.book() != null && !f.book().weAreBest()));
        return out;
    }

    /** Корзины по номиналу, стоящему внутри нас. */
    public static List<Group> byQtyInside(List<Fill> fills,
                                          NavigableMap<Long, Double> fairSeries,
                                          long horizonMs) {
        double[] edges = {0, 1_000, 5_000, 20_000};
        String[] labels = {"0", "(0, 1к]", "(1к, 5к]", "(5к, 20к]", "> 20к"};
        List<Group> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            out.add(group(labels[i], fills, fairSeries, horizonMs, f -> {
                if (f.book() == null) {
                    return false;
                }
                double q = f.book().qtyInside();
                if (idx == 0) {
                    return q <= 0;
                }
                double lo = edges[idx - 1];
                return q > lo && (idx == labels.length - 1 || q <= edges[idx]);
            }));
        }
        return out;
    }

    /** Корзины по числу ценовых уровней внутри нас. */
    public static List<Group> byLevelsInside(List<Fill> fills,
                                             NavigableMap<Long, Double> fairSeries,
                                             long horizonMs) {
        List<Group> out = new ArrayList<>();
        for (int n = 0; n <= 2; n++) {
            final int level = n;
            out.add(group(String.valueOf(n), fills, fairSeries, horizonMs,
                    f -> f.book() != null && f.book().levelsInside() == level));
        }
        out.add(group("3+", fills, fairSeries, horizonMs,
                f -> f.book() != null && f.book().levelsInside() >= 3));
        return out;
    }

    private static Group group(String label, List<Fill> all,
                               NavigableMap<Long, Double> fairSeries, long horizonMs,
                               Predicate<Fill> filter) {
        List<Fill> selected = Markout.withHorizon(all.stream().filter(filter).toList(),
                fairSeries, horizonMs);
        double turnover = selected.stream().mapToDouble(Fill::notional).sum();
        if (selected.isEmpty() || !(turnover > 0)) {
            return new Group(label, selected.size(), Double.NaN, Double.NaN, Double.NaN);
        }
        Markout.Stats stats = Markout.compute(selected, fairSeries, horizonMs);
        double markoutBp = stats.mean() * stats.fills() / turnover * 10_000;
        double captureBp = selected.stream().mapToDouble(Fill::spreadCapture).sum()
                / turnover * 10_000;
        double se = Markout.standardErrorBp(selected, fairSeries, horizonMs);
        return new Group(label, selected.size(), markoutBp, se, captureBp);
    }
}
