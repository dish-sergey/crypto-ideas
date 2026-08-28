package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Персистентность дрейфа: сколько от прошлого движения доживает до будущего.
 *
 * Зачем это нужно. Дрейф-скос (док. 99) вводился как правило риска, а вес
 * {@code β = 1/(2d)} выведен из геометрии котировки. Теорема Guéant et al.
 * (arXiv 1206.4810) предписывает другое: сдвиг центра котировки должен быть
 * равен ОЖИДАЕМОМУ изменению цены за горизонт удержания, {@code E[S(T)] − s}.
 * Эта величина измерима — регрессией будущей доходности на трейлинг-дрейф, — и
 * именно она даёт теоретический вес вместо эвристики (док. 100 §6.1).
 *
 * Побочно — и это важнее самого веса — регрессия отвечает на вопрос о ПРИРОДЕ
 * результата. Если будущее из прошлого дрейфа не предсказывается, дрейф-скос
 * работает исключительно как правило риска, а не как альфа. Это законно, но
 * описывать конструкцию тогда надо иначе, и переносить её на другие площадки
 * нельзя как «сигнал». Плато по β из дока 99 §3 заранее указывает именно на
 * правило риска: настоящая альфа была бы чувствительна к весу.
 *
 * Измерение причинно чистое: обе величины берутся из одного ряда опоры,
 * трейлинг-окно заканчивается в момент {@code t}, будущее начинается там же.
 */
public final class DriftPersistence {

    /**
     * @param slope       коэффициент регрессии будущей доходности на трейлинг-дрейф;
     *                    это и есть {@code β} теоремы, в долях
     * @param correlation IC: корреляция прошлого дрейфа с будущей доходностью
     * @param rSquared    доля объяснённой дисперсии
     * @param points      сколько независимых точек вошло в регрессию
     * @param overlap     во сколько раз выборка перекрывается (окна наложены)
     */
    public record Stats(double slope, double correlation, double rSquared,
                        int points, double overlap) {

        /**
         * Вес для {@code Quoter}: скос сдвигает цену на {@code skewK · β · drift},
         * поэтому чтобы сдвиг равнялся ожидаемому изменению цены {@code slope · drift},
         * нужно {@code β = slope / skewK}.
         */
        public double betaFor(double skewK) {
            return skewK > 0 ? slope / skewK : 0;
        }

        /**
         * Предсказуемость на уровне шума. Порог намеренно мягкий: вопрос не в том,
         * годится ли дрейф как торговый сигнал (не годится), а в том, есть ли в нём
         * вообще направленное содержание.
         */
        public boolean predictive() {
            return Math.abs(correlation) >= 0.05 && points >= 100;
        }
    }

    private DriftPersistence() {
    }

    /**
     * @param fairSeries  ряд справедливой цены
     * @param trailingMs  окно измерения прошлого дрейфа (то же, что у скоса)
     * @param futureMs    горизонт удержания позиции
     * @param strideMs    шаг между точками выборки; окна всё равно перекрываются,
     *                    поэтому шаг влияет только на объём вычислений
     */
    public static Stats compute(TreeMap<Long, Double> fairSeries,
                                long trailingMs, long futureMs, long strideMs) {
        if (fairSeries.size() < 3 || trailingMs <= 0 || futureMs <= 0) {
            return new Stats(0, 0, 0, 0, 0);
        }
        List<double[]> pairs = new ArrayList<>();
        long first = fairSeries.firstKey();
        long last = fairSeries.lastKey();
        long step = Math.max(1, strideMs);

        for (long t = first + trailingMs; t + futureMs <= last; t += step) {
            Double past = valueAt(fairSeries, t - trailingMs);
            Double now = valueAt(fairSeries, t);
            Double future = valueAt(fairSeries, t + futureMs);
            if (past == null || now == null || future == null
                    || !(past > 0) || !(now > 0)) {
                continue;
            }
            double x = (now - past) / past;               // прошлый дрейф
            double y = (future - now) / now;              // будущая доходность
            pairs.add(new double[]{x, y});
        }
        if (pairs.size() < 10) {
            return new Stats(0, 0, 0, pairs.size(), 0);
        }

        double sx = 0, sy = 0;
        for (double[] p : pairs) {
            sx += p[0];
            sy += p[1];
        }
        double mx = sx / pairs.size();
        double my = sy / pairs.size();
        double sxx = 0, syy = 0, sxy = 0;
        for (double[] p : pairs) {
            double dx = p[0] - mx;
            double dy = p[1] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        double slope = sxx > 0 ? sxy / sxx : 0;
        double correlation = sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0;
        // Перекрытие: соседние точки делят почти всё окно, поэтому число НЕЗАВИСИМЫХ
        // наблюдений меньше числа точек во столько раз. Без этой поправки любая
        // значимость будет завышена, а соблазн поверить в слабую корреляцию — велик.
        double overlap = (double) Math.max(trailingMs, futureMs) / step;
        return new Stats(slope, correlation, correlation * correlation,
                pairs.size(), overlap);
    }

    /** Значение ряда на момент или ближайшее предшествующее. */
    private static Double valueAt(TreeMap<Long, Double> series, long tsMs) {
        Map.Entry<Long, Double> entry = series.floorEntry(tsMs);
        return entry == null ? null : entry.getValue();
    }
}
