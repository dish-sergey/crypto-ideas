package org.home.data.theory.alloc;

import java.util.Arrays;

/**
 * Движок аллокации (ТЗ 65 §3.2, §4.2): применяет веса аллокатора к пулу кривых
 * с маской доступности, ставкой кэша по датам и издержками переключения.
 *
 * <pre>
 * r(t) = Σ_i w_i(t)·[ available_i(t) ? ret_i(t) : rate_cash(t) ]
 *      + (1 − Σ_i w_i(t))·rate_cash(t)                       — незанятый резерв
 *      − c_switch·Σ_i |w_i(t) − w_i(t−1)| / 2
 * </pre>
 *
 * <p>Веса на день t считаются по истории до дня t−1 включительно
 * ({@link History}); собственный оборот стратегии уже вычтен из {@code ret_i} и
 * повторно не списывается (§4.2) — проверяется тестом.
 */
public final class AllocEngine {

    private final CurveSet set;
    private final double switchCost;

    public AllocEngine(CurveSet set, double switchCost) {
        this.set = set;
        this.switchCost = switchCost;
    }

    /**
     * Результат прогона одного аллокатора.
     *
     * @param allocId       идентификатор
     * @param ret           дневная доходность портфеля (после издержек)
     * @param equity        кривая капитала, equity[0] = 1 до первого дня
     * @param weights       веса по дням [день][стратегия]
     * @param switchCostSum суммарные издержки переключения в долях начального капитала
     * @param meanAbsDw     средний Σ|Δw| в день
     * @param cashShare     доля капитала в кэше по времени
     * @param contribution  вклад каждой стратегии в P&L (в единицах капитала)
     * @param cashPnl       вклад кэша в P&L
     * @param costPnl       суммарные издержки в единицах капитала (отрицательный вклад)
     */
    public record Result(String allocId, double[] ret, double[] equity, double[][] weights,
                         double switchCostSum, double meanAbsDw, double cashShare,
                         double[] contribution, double cashPnl, double costPnl) {

        public double finalEquity() {
            return equity[equity.length - 1];
        }
    }

    public Result run(Allocator allocator) {
        int t = set.length();
        int n = set.size();
        double[][] ret = set.retMatrix();
        boolean[][] avail = set.availMatrix();
        double[] cash = set.cash();

        History history = new History(ret, avail, cash, set.regime(), set.cyclePhase(), set.ids());
        allocator.reset(n);

        double[] portfolio = new double[t];
        double[] equity = new double[t + 1];
        double[][] weights = new double[t][];
        double[] contribution = new double[n];
        equity[0] = 1;
        double[] prev = new double[n];
        double absDwSum = 0;
        double cashTime = 0;
        double cashPnl = 0;
        double costPnl = 0;
        double costSum = 0;

        for (int day = 0; day < t; day++) {
            history.advanceTo(day - 1);
            double[] w = allocator.weights(history);
            if (w.length != n) {
                throw new IllegalStateException(allocator.id() + ": вернул " + w.length + " весов при N=" + n);
            }
            double sum = 0;
            for (double wi : w) {
                if (wi < -1e-12) {
                    throw new IllegalStateException(allocator.id() + ": отрицательный вес " + wi);
                }
                sum += wi;
            }
            if (sum > 1 + 1e-9) {
                throw new IllegalStateException(allocator.id() + ": Σw = " + sum + " > 1");
            }

            double absDw = 0;
            for (int i = 0; i < n; i++) {
                absDw += Math.abs(w[i] - prev[i]);
            }
            double cost = switchCost * absDw / 2;
            absDwSum += absDw;

            double gross = 0;
            double inCash = 1 - sum;
            double wealth = equity[day];
            for (int i = 0; i < n; i++) {
                if (avail[day][i]) {
                    double pnl = w[i] * ret[day][i];
                    gross += pnl;
                    contribution[i] += wealth * pnl;
                } else {
                    inCash += w[i];
                }
            }
            gross += inCash * cash[day];
            cashPnl += wealth * inCash * cash[day];
            costPnl -= wealth * cost;
            costSum += cost;
            cashTime += inCash;

            portfolio[day] = gross - cost;
            equity[day + 1] = wealth * (1 + portfolio[day]);
            weights[day] = w;
            prev = w;
        }

        return new Result(allocator.id(), portfolio, equity, weights, costSum,
                t == 0 ? 0 : absDwSum / t, t == 0 ? 0 : cashTime / t, contribution, cashPnl, costPnl);
    }

    /**
     * Лучший постоянный микс задним числом (§4.1 {@code BEST_FIXED}) —
     * недостижимый верхний ориентир. Максимизируется логарифм итогового
     * капитала по симплексу: задача вогнутая, решается проекционным градиентным
     * подъёмом. Смотрит в будущее <b>намеренно</b>.
     *
     * <p>Веса ищутся с учётом маски доступности и ставки кэша, но <b>без</b>
     * издержек переключения: постоянный микс их и не несёт, кроме ежедневной
     * ребалансировки к постоянным весам, которая на маске доступности мала.
     */
    public double[] bestFixedMix(int iterations, double step) {
        int t = set.length();
        int n = set.size();
        double[][] ret = set.retMatrix();
        boolean[][] avail = set.availMatrix();
        double[] cash = set.cash();

        double[] w = new double[n];
        Arrays.fill(w, 1.0 / n);
        for (int it = 0; it < iterations; it++) {
            double[] grad = new double[n];
            for (int day = 0; day < t; day++) {
                double r = 0;
                double[] eff = new double[n];
                for (int i = 0; i < n; i++) {
                    eff[i] = avail[day][i] ? ret[day][i] : cash[day];
                    r += w[i] * eff[i];
                }
                double denom = 1 + r;
                if (denom <= 1e-9) {
                    denom = 1e-9;
                }
                for (int i = 0; i < n; i++) {
                    grad[i] += eff[i] / denom;
                }
            }
            double[] next = new double[n];
            for (int i = 0; i < n; i++) {
                next[i] = w[i] + step * grad[i] / Math.max(t, 1);
            }
            double[] projected = Allocators.projectToSimplex(next);
            double move = 0;
            for (int i = 0; i < n; i++) {
                move += Math.abs(projected[i] - w[i]);
            }
            w = projected;
            if (move < 1e-12) {
                break;
            }
        }
        return w;
    }

    /** Лучшая одиночная стратегия задним числом (§4.1 {@code BEST_SINGLE}): база регрета. */
    public int bestSingleIndex() {
        int best = 0;
        double bestWealth = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < set.size(); i++) {
            double[] w = new double[set.size()];
            w[i] = 1;
            double wealth = run(new Allocators.Fixed("tmp", w, true)).finalEquity();
            if (wealth > bestWealth) {
                bestWealth = wealth;
                best = i;
            }
        }
        return best;
    }
}
