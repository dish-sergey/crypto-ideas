package org.home.data.theory.kelly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Оценка размера позиции (ТЗ 66 §4).
 *
 * <p>Точечный Kelly здесь — <b>база для сравнения, а не ответ</b>: подстановка
 * точечной оценки {@code μ̂} систематически завышает размер, и завышение тем
 * сильнее, чем меньше N (§0). Решение принимается по нижней границе бутстрапа и
 * по усаженной оценке.
 */
public final class KellySizing {

    private KellySizing() {
    }

    /**
     * Численный Kelly: максимум {@code E[ln(1 + f·X)]} по эмпирическому
     * распределению исходов. Основная версия (§4.1); непрерывная формула
     * {@code μ/σ²} — только для сверки порядка величины.
     *
     * <p>Область поиска ограничена условием {@code 1 + f·x > 0} для всех
     * наблюдённых {@code x}: за ней логарифм не определён, то есть ставка
     * разоряет счёт на одном событии.
     */
    public static double kellyNumeric(double[] outcomes) {
        if (outcomes.length == 0) {
            return 0;
        }
        double worstLoss = 0;
        double bestGain = 0;
        for (double x : outcomes) {
            worstLoss = Math.min(worstLoss, x);
            bestGain = Math.max(bestGain, x);
        }
        double low = bestGain > 0 ? -1 / bestGain + 1e-9 : -10;
        double high = worstLoss < 0 ? -1 / worstLoss - 1e-9 : 10;
        if (!(low < high)) {
            return 0;
        }
        // золотое сечение: g(f) вогнута на допустимом интервале
        double phi = (Math.sqrt(5) - 1) / 2;
        double a = low;
        double b = high;
        double c = b - phi * (b - a);
        double d = a + phi * (b - a);
        for (int i = 0; i < 200; i++) {
            if (growthRate(outcomes, c) < growthRate(outcomes, d)) {
                a = c;
            } else {
                b = d;
            }
            c = b - phi * (b - a);
            d = a + phi * (b - a);
        }
        return (a + b) / 2;
    }

    /** Темп роста {@code E[ln(1 + f·X)]} — главная кривая отчёта (§5.2). */
    public static double growthRate(double[] outcomes, double f) {
        double sum = 0;
        for (double x : outcomes) {
            double v = 1 + f * x;
            if (v <= 1e-12) {
                return Double.NEGATIVE_INFINITY;
            }
            sum += Math.log(v);
        }
        return sum / outcomes.length;
    }

    /** Непрерывное приближение {@code f* = μ̂/σ̂²} — для сверки порядка величины. */
    public static double kellyContinuous(double[] outcomes) {
        double m = mean(outcomes);
        double v = variance(outcomes);
        return v > 0 ? m / v : 0;
    }

    /**
     * Бутстрап-распределение {@code f*} (§4.2): ресэмплинг событий с
     * возвращением. Возвращает <b>отсортированный</b> массив реплик.
     *
     * <p>Решение принимается по нижней границе (5-й процентиль), а не по
     * медиане: переоценка и недооценка {@code f} наказываются по-разному (§0 п.3).
     */
    public static double[] bootstrapKelly(double[] outcomes, int replicas, long seed) {
        Random rnd = new Random(seed);
        double[] out = new double[replicas];
        double[] sample = new double[outcomes.length];
        for (int b = 0; b < replicas; b++) {
            for (int i = 0; i < sample.length; i++) {
                sample[i] = outcomes[rnd.nextInt(outcomes.length)];
            }
            out[b] = kellyNumeric(sample);
        }
        Arrays.sort(out);
        return out;
    }

    /**
     * Байесовская усадка (§4.3): нормально-нормальная модель с априорным средним
     * <b>ноль</b> («эффекта нет, пока не доказано»).
     *
     * @param strength сила приора {@code s}: апостериорное среднее равно
     *                 {@code μ̂/(1+s)}; {@code s = 0} — неинформативный приор,
     *                 {@code s → ∞} — полная усадка к нулю
     */
    public static double shrunkKelly(double[] outcomes, double strength) {
        double m = mean(outcomes) / (1 + strength);
        double v = variance(outcomes);
        return v > 0 ? m / v : 0;
    }

    /**
     * Поправка на смещение отбора (§4.4, вариант «усадка к нулю с силой по числу
     * проверенных гипотез»): из оценки {@code μ̂} вычитается
     * {@code SE(μ̂)·√(2 ln N)} — та же конвенция, по которой в проекте
     * дефлируется Sharpe (док. 15 §6.3).
     *
     * <p>Ограничение поправки: она учитывает множественность проверок, но не
     * учитывает, что сам датасет собран после того, как эффект был найден.
     */
    public static double selectionAdjustedKelly(double[] outcomes, int testedHypotheses) {
        int n = outcomes.length;
        if (n < 2) {
            return 0;
        }
        double se = Math.sqrt(variance(outcomes) / n);
        double adjusted = mean(outcomes) - se * Math.sqrt(2 * Math.log(Math.max(testedHypotheses, 2)));
        double v = variance(outcomes);
        return v > 0 ? adjusted / v : 0;
    }

    /**
     * Группы одновременных событий (§4.5): исходы событий, окна удержания
     * которых пересекаются. Используются как эмпирическая <b>совместная</b>
     * выборка.
     *
     * @param days  дата разлока каждого события в виде номера дня
     * @param values исходы
     * @param window длительность удержания в днях
     * @param size  размер группы
     */
    public static List<double[]> concurrentGroups(long[] days, double[] values, int window, int size) {
        List<double[]> groups = new ArrayList<>();
        for (int i = 0; i < days.length; i++) {
            List<Double> group = new ArrayList<>();
            group.add(values[i]);
            for (int j = 0; j < days.length && group.size() < size; j++) {
                if (j != i && Math.abs(days[j] - days[i]) < window) {
                    group.add(values[j]);
                }
            }
            if (group.size() == size) {
                double[] g = new double[size];
                for (int k = 0; k < size; k++) {
                    g[k] = group.get(k);
                }
                groups.add(g);
            }
        }
        return groups;
    }

    /**
     * Фактическая кластеризация событий (§4.5): сколько ставок в среднем и в
     * максимуме открыто одновременно. Правило «≤ N одновременных» имеет смысл
     * только по отношению к этому числу — если поток даёт 18 пересечений, лимит
     * в 3 события означает, что 15 сделок пропускаются, а не что экспозиция мала.
     *
     * @return {@code {максимум одновременных, среднее одновременных}}
     */
    public static double[] concurrency(long[] days, int window) {
        int max = 0;
        double sum = 0;
        for (long day : days) {
            int c = 0;
            for (long other : days) {
                if (Math.abs(other - day) < window) {
                    c++;
                }
            }
            max = Math.max(max, c);
            sum += c;
        }
        return new double[]{max, days.length == 0 ? 0 : sum / days.length};
    }

    /**
     * Потолок размера позиции из портфельного лимита просадки: если {@code k}
     * позиций могут одновременно упереться в стоп, суммарная потеря не должна
     * превышать {@code ddLimit}. Тот же расчёт, что в док. 52 §3.3.
     */
    public static double drawdownCap(double ddLimit, double concurrent, double worstLoss) {
        return concurrent <= 0 || worstLoss <= 0 ? Double.NaN : ddLimit / (concurrent * worstLoss);
    }

    /**
     * Оптимальный <b>суммарный</b> размер по группе одновременных ставок:
     * максимизируется {@code E[ln(1 + Σ f_i·x_i)]} при равных {@code f_i = F/k}.
     * При положительной корреляции исходов оптимум меньше, чем {@code k·f}.
     */
    public static double jointOptimalTotal(List<double[]> groups, int size) {
        if (groups.isEmpty()) {
            return 0;
        }
        double[] totals = new double[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            double sum = 0;
            for (double x : groups.get(i)) {
                sum += x;
            }
            totals[i] = sum / size;                // средний исход группы
        }
        return kellyNumeric(totals);
    }

    /** Корреляция исходов внутри групп одновременных событий (§4.5). */
    public static double averagePairCorrelation(List<double[]> groups) {
        List<double[]> pairs = new ArrayList<>();
        for (double[] g : groups) {
            for (int i = 0; i < g.length; i++) {
                for (int j = i + 1; j < g.length; j++) {
                    pairs.add(new double[]{g[i], g[j]});
                }
            }
        }
        if (pairs.size() < 3) {
            return Double.NaN;
        }
        double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();
        return correlation(x, y);
    }

    public static double correlation(double[] x, double[] y) {
        double mx = mean(x);
        double my = mean(y);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : Double.NaN;
    }

    public static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? 0 : s / v.length;
    }

    public static double variance(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return s / (v.length - 1);
    }

    public static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** Асимметрия распределения исходов — диагностика профиля проданного опциона (§3.3). */
    public static double skewness(double[] v) {
        int n = v.length;
        if (n < 3) {
            return Double.NaN;
        }
        double m = mean(v);
        double sd = Math.sqrt(variance(v));
        if (sd == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += Math.pow((x - m) / sd, 3);
        }
        return s * n / ((n - 1.0) * (n - 2.0));
    }

    /** Эксцесс (превышение над нормальным). */
    public static double excessKurtosis(double[] v) {
        int n = v.length;
        if (n < 4) {
            return Double.NaN;
        }
        double m = mean(v);
        double sd = Math.sqrt(variance(v));
        if (sd == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += Math.pow((x - m) / sd, 4);
        }
        double g2 = s * n * (n + 1.0) / ((n - 1.0) * (n - 2.0) * (n - 3.0));
        return g2 - 3.0 * (n - 1.0) * (n - 1.0) / ((n - 2.0) * (n - 3.0));
    }
}
