package org.home.data.theory.alloc;

/**
 * Метрики прогона аллокатора (ТЗ 65 §6.2).
 *
 * <p>Sharpe считается на избыточной доходности над ставкой кэша по датам: без
 * этого аллокатор, держащий капитал в кэше, получает «бесплатный» Sharpe от
 * самой ставки. Дефлированный Sharpe — по конвенции проекта (док. 15 §6.3):
 * {@code S − SE·√(2 ln N)} при {@code SE = √((1 + S²/2)/лет)}, где N — честное
 * число прогонов этого стенда.
 */
public record Metrics(double cagr, double vol, double sharpe, double sharpeSe, double sharpeDeflated,
                      double maxDrawdown, String maxDrawdownDay, double years) {

    public static Metrics of(double[] ret, double[] equity, double[] cash, String[] days, int trials) {
        int t = ret.length;
        double years = t / 365.0;
        double finalEquity = equity[equity.length - 1];
        double cagr = years > 0 && finalEquity > 0 ? Math.pow(finalEquity, 1 / years) - 1 : Double.NaN;

        double mean = 0;
        for (int i = 0; i < t; i++) {
            mean += ret[i] - cash[i];
        }
        mean = t == 0 ? 0 : mean / t;
        double var = 0;
        for (int i = 0; i < t; i++) {
            double d = (ret[i] - cash[i]) - mean;
            var += d * d;
        }
        double sd = t > 1 ? Math.sqrt(var / (t - 1)) : 0;
        double vol = sd * Math.sqrt(365);
        double sharpe = sd > 0 ? mean / sd * Math.sqrt(365) : 0;
        double se = years > 0 ? Math.sqrt((1 + sharpe * sharpe / 2) / years) : Double.NaN;
        double deflated = sharpe - se * Math.sqrt(2 * Math.log(Math.max(trials, 2)));

        double peak = equity[0];
        double maxDd = 0;
        String maxDdDay = "—";
        for (int i = 1; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            double dd = peak > 0 ? equity[i] / peak - 1 : 0;
            if (dd < maxDd) {
                maxDd = dd;
                maxDdDay = days[Math.min(i - 1, days.length - 1)];
            }
        }
        return new Metrics(cagr, vol, sharpe, se, deflated, maxDd, maxDdDay, years);
    }

    /**
     * Теоретическая граница регрета Hedge: {@code G·√(T·ln N / 2)} при
     * {@code η* = √(8 ln N / T)}, где G — наблюдённый размах дневных
     * доходностей пула (классическая граница выведена для выигрышей в [0,1],
     * масштабирование на размах — прямое следствие).
     *
     * <p>Граница <b>относительная</b>: она гарантирует «не сильно хуже лучшей
     * стратегии пула» и ничего не обещает про прибыль (§0, §10).
     */
    public static double hedgeRegretBound(double[][] ret, int n, int t) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : ret) {
            for (int i = 0; i < n; i++) {
                min = Math.min(min, row[i]);
                max = Math.max(max, row[i]);
            }
        }
        double span = max - min;
        return span * Math.sqrt(t * Math.log(Math.max(n, 2)) / 2.0);
    }

    /** Реализованный регрет: аддитивный (в терминах суммы дневных доходностей). */
    public static double realizedRegret(double[] algorithm, double[] benchmark) {
        double sum = 0;
        for (int i = 0; i < algorithm.length; i++) {
            sum += benchmark[i] - algorithm[i];
        }
        return sum;
    }
}
