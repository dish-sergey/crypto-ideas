package org.home.data.theory.band;

import java.util.Arrays;
import java.util.Random;

/**
 * Граница разорения (ТЗ 68 §5): вероятность дойти до барьера.
 *
 * <p>Две независимые реализации, обе обязательны: аналитическая (классическая
 * задача о разорении игрока и её диффузионный предел, предпосылка —
 * независимые приращения) и Монте-Карло на <b>эмпирическом</b> распределении с
 * блочным бутстрапом.
 *
 * <p><b>Расхождение оценок — результат, а не проблема:</b> оно измеряет цену
 * предпосылки о независимости. В отчёт идут обе.
 */
public final class RuinMath {

    private RuinMath() {
    }

    /**
     * @param pRuinAnalytic аналитическая оценка (независимые приращения)
     * @param pRuinMc       Монте-Карло на эмпирическом распределении
     * @param ciLow         нижняя граница 95% интервала МК-оценки
     * @param ciHigh        верхняя граница
     * @param medianSteps   медианное число шагов до барьера среди дошедших
     */
    public record RuinResult(double pRuinAnalytic, double pRuinMc, double ciLow, double ciHigh,
                             double medianSteps) {
    }

    /**
     * Классическая задача о разорении игрока: симметричное случайное блуждание с
     * барьерами на расстоянии {@code a} вниз и {@code b} вверх достигает нижнего
     * барьера с вероятностью {@code b/(a+b)} — точная сверка (§7.1).
     */
    public static double gamblersRuinSymmetric(double a, double b) {
        return b / (a + b);
    }

    /**
     * Диффузионный предел: вероятность достичь нижнего барьера {@code −a} раньше
     * верхнего {@code +b} при дрейфе {@code mu} и волатильности {@code sigma}
     * (на шаг). При {@code mu = 0} сводится к {@code b/(a+b)}.
     */
    public static double diffusionRuin(double mu, double sigma, double a, double b) {
        if (sigma <= 0) {
            return mu < 0 ? 1 : 0;
        }
        if (Math.abs(mu) < 1e-12) {
            return gamblersRuinSymmetric(a, b);
        }
        // Шкальная функция s(x) = e^{−2μx/σ²}; P(достичь −a раньше +b) =
        // (s(b) − s(0))/(s(b) − s(−a)). При μ = 0 сводится к b/(a+b), при μ > 0 → 0.
        double k = 2 * mu / (sigma * sigma);
        double numerator = Math.exp(-k * b) - 1;
        double denominator = Math.exp(-k * b) - Math.exp(k * a);
        return numerator / denominator;
    }

    /**
     * Монте-Карло на эмпирических приращениях с <b>блочным</b> бутстрапом (§5.3).
     * Обычный ресэмплинг разрушает кластеризацию волатильности и занижает
     * вероятность разорения — поэтому длина блока задаётся конфигом, а
     * чувствительность к ней прогоняется отдельно.
     *
     * @param returns   эмпирические доходности за шаг
     * @param steps     горизонт в шагах
     * @param drawdown  порог просадки, считающийся разорением (0.5 = −50%)
     * @param blockSize длина блока; 1 = обычный бутстрап
     */
    public static RuinResult monteCarloDrawdown(double[] returns, int steps, double drawdown,
                                                int blockSize, int paths, long seed) {
        Random rnd = new Random(seed);
        int hits = 0;
        int[] hitSteps = new int[paths];
        int hitCount = 0;
        for (int p = 0; p < paths; p++) {
            double wealth = 1;
            double peak = 1;
            int block = 0;
            int index = 0;
            boolean ruined = false;
            for (int t = 0; t < steps; t++) {
                if (block == 0) {
                    index = rnd.nextInt(returns.length);
                    block = Math.max(1, blockSize);
                }
                wealth *= 1 + returns[index];
                index = (index + 1) % returns.length;
                block--;
                peak = Math.max(peak, wealth);
                if (wealth / peak - 1 <= -drawdown) {
                    ruined = true;
                    hitSteps[hitCount++] = t + 1;
                    break;
                }
            }
            if (ruined) {
                hits++;
            }
        }
        double p = (double) hits / paths;
        double se = Math.sqrt(Math.max(p * (1 - p), 1e-12) / paths);
        double median = Double.NaN;
        if (hitCount > 0) {
            int[] sorted = Arrays.copyOf(hitSteps, hitCount);
            Arrays.sort(sorted);
            median = sorted[hitCount / 2];
        }
        return new RuinResult(Double.NaN, p, Math.max(0, p - 1.96 * se), Math.min(1, p + 1.96 * se), median);
    }

    /**
     * Лестница ступеней вниз (применение §5.2 к S6): распределение числа
     * пройденных ступеней и вероятность пройти всю лестницу.
     *
     * @param returns  эмпирические дневные доходности
     * @param stepDrop шаг ступени вниз от точки активации
     * @param steps    сколько ступеней в лестнице
     * @param horizon  горизонт наблюдения в днях
     * @return массив долей траекторий, прошедших ровно k ступеней (индекс = k)
     */
    public static double[] ladderDistribution(double[] returns, double stepDrop, int steps,
                                              int horizon, int blockSize, int paths, long seed) {
        Random rnd = new Random(seed);
        double[] counts = new double[steps + 1];
        for (int p = 0; p < paths; p++) {
            double price = 1;
            double lowest = 1;
            int block = 0;
            int index = 0;
            for (int t = 0; t < horizon; t++) {
                if (block == 0) {
                    index = rnd.nextInt(returns.length);
                    block = Math.max(1, blockSize);
                }
                price *= 1 + returns[index];
                index = (index + 1) % returns.length;
                block--;
                lowest = Math.min(lowest, price);
            }
            int passed = 0;
            for (int k = 1; k <= steps; k++) {
                if (lowest <= 1 - stepDrop * k) {
                    passed = k;
                }
            }
            counts[passed]++;
        }
        for (int i = 0; i <= steps; i++) {
            counts[i] /= paths;
        }
        return counts;
    }

    /**
     * Потолок инвентаря маркет-мейкера (применение §5.2): инвентарь как
     * случайное блуждание с перекосом потока. Модель, а не измерение: поток
     * задаётся параметрами, потому что данных книги у проекта пока нет.
     *
     * @param imbalance перекос потока (доля сделок в одну сторону сверх половины)
     * @param ceiling   потолок инвентаря в единицах сделки
     * @return {@code {доля траекторий, упершихся в потолок, средняя доля времени у потолка}}
     */
    public static double[] inventoryCeiling(double imbalance, int ceiling, int steps, int paths, long seed) {
        Random rnd = new Random(seed);
        int hits = 0;
        double timeAtCeiling = 0;
        for (int p = 0; p < paths; p++) {
            int inventory = 0;
            boolean hit = false;
            int atCeiling = 0;
            for (int t = 0; t < steps; t++) {
                inventory += rnd.nextDouble() < 0.5 + imbalance ? 1 : -1;
                if (Math.abs(inventory) >= ceiling) {
                    hit = true;
                    atCeiling++;
                    inventory = (int) (Math.signum(inventory) * ceiling);
                }
            }
            if (hit) {
                hits++;
            }
            timeAtCeiling += (double) atCeiling / steps;
        }
        return new double[]{(double) hits / paths, timeAtCeiling / paths};
    }
}
