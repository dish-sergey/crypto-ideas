package org.home.data.theory.kelly;

import java.util.Arrays;
import java.util.Random;

/**
 * Симуляция траекторий капитала (ТЗ 66 §5): что даёт размер {@code f} на
 * реальном горизонте, а не в асимптотике.
 *
 * <p>Все выводы привязаны к фактическому потоку событий (§5.3): при ≈8 событиях
 * в год утверждения об «асимптотически оптимальном росте» некорректны, и в
 * отчёте они не используются.
 */
public final class KellySim {

    private KellySim() {
    }

    /**
     * Результат прогона по одному размеру.
     *
     * @param f            размер ставки на событие
     * @param medianWealth медиана конечного капитала
     * @param p5Wealth     5-й процентиль конечного капитала
     * @param p95Wealth    95-й процентиль конечного капитала
     * @param medianMaxDd  медиана максимальной просадки
     * @param p95MaxDd     95-й процентиль максимальной просадки (по модулю)
     * @param pDeep        доля траекторий с просадкой глубже порога
     * @param growth       темп роста {@code E[ln(1+f·X)]} на исходной выборке
     */
    public record SizeResult(double f, double medianWealth, double p5Wealth, double p95Wealth,
                             double medianMaxDd, double p95MaxDd, double pDeep, double growth) {
    }

    /**
     * Монте-Карло по перестановкам порядка событий (§5.2). Ресэмплинг с
     * возвращением сохраняет распределение исходов, но разрушает кластеризацию;
     * это отмечается в отчёте как ограничение.
     */
    public static SizeResult simulate(double[] outcomes, double f, int paths, double deepThreshold, long seed) {
        Random rnd = new Random(seed);
        double[] finals = new double[paths];
        double[] maxDds = new double[paths];
        int deep = 0;
        for (int p = 0; p < paths; p++) {
            double wealth = 1;
            double peak = 1;
            double maxDd = 0;
            for (int i = 0; i < outcomes.length; i++) {
                double x = outcomes[rnd.nextInt(outcomes.length)];
                wealth *= 1 + f * x;
                if (wealth <= 0) {
                    wealth = 1e-12;
                }
                peak = Math.max(peak, wealth);
                maxDd = Math.min(maxDd, wealth / peak - 1);
            }
            finals[p] = wealth;
            maxDds[p] = maxDd;
            if (-maxDd >= deepThreshold) {
                deep++;
            }
        }
        Arrays.sort(finals);
        Arrays.sort(maxDds);
        return new SizeResult(f,
                KellySizing.quantile(finals, 0.5),
                KellySizing.quantile(finals, 0.05),
                KellySizing.quantile(finals, 0.95),
                KellySizing.quantile(maxDds, 0.5),
                KellySizing.quantile(maxDds, 0.05),
                (double) deep / paths,
                KellySizing.growthRate(outcomes, f));
    }

    /**
     * Прогон по фактической последовательности событий (без ресэмплинга):
     * итоговый рост, максимальная просадка и число событий до восстановления
     * после худшей просадки (§5.1).
     *
     * @return {@code {итоговый капитал, максимальная просадка, событий до восстановления}}
     */
    public static double[] walkThrough(double[] outcomes, double f) {
        double wealth = 1;
        double peak = 1;
        double maxDd = 0;
        int troughIndex = -1;
        for (int i = 0; i < outcomes.length; i++) {
            wealth *= 1 + f * outcomes[i];
            if (wealth <= 0) {
                wealth = 1e-12;
            }
            peak = Math.max(peak, wealth);
            double dd = wealth / peak - 1;
            if (dd < maxDd) {
                maxDd = dd;
                troughIndex = i;
            }
        }
        double recovery = Double.NaN;
        if (troughIndex >= 0) {
            // капитал в точке дна и уровень пика до неё
            double w = 1;
            double pk = 1;
            double trough = 1;
            for (int i = 0; i <= troughIndex; i++) {
                w *= 1 + f * outcomes[i];
                pk = Math.max(pk, w);
                trough = w;
            }
            double target = pk;
            double after = trough;
            for (int i = troughIndex + 1; i < outcomes.length; i++) {
                after *= 1 + f * outcomes[i];
                if (after >= target) {
                    recovery = i - troughIndex;
                    break;
                }
            }
        }
        return new double[]{wealth, maxDd, recovery};
    }
}
