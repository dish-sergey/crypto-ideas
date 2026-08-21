package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Справедливая цена и курс USDC/USD (ТЗ §4.1).
 *
 * Опорная цена берётся из USD-книги, а не из той, в которой торгуем:
 * {@code fair_usdc(asset) = mid_usd(asset) / rate_usdc_usd}.
 *
 * Курс считается по МНОГИМ парам через медиану, а не по одной и не средним:
 * одна разъехавшаяся книга не должна двигать курс. Мемкоины в расчёт курса не
 * входят (ТЗ §4.1), но котироваться могут.
 *
 * Два предохранителя, и второй — не из ТЗ, а из измерения 19.08.2026:
 *
 *  1. ОБЩИЙ (ТЗ §4.1): если разброс implied по парам велик, курс объявляется
 *     ненадёжным и котирование останавливается целиком.
 *  2. ПОПАРНЫЙ (наш): на движении ETH в 18% опорная книга ETH/USD раздвинулась
 *     до 1.18% (BTC/USD в тот же момент — 0.016%), а implied по ETH ушёл на
 *     −3.5% при том, что остальные 22 пары дали 0.9999 с разбросом в сотые доли.
 *     Медиана в этот момент здорова, общий гейт МОЛЧИТ — и стратегия котировала бы
 *     ETH от цены, промахнувшейся на 3.5%. Поэтому пара выключается отдельно,
 *     если её опорная книга широка или её implied далеко от медианы.
 *
 * Класс чистый (без Spring и БД) — чтобы поведение гейтов проверялось тестами,
 * а не наблюдалось в проде.
 */
public final class FairPrice {

    /** Пороги; все — из конфига, никаких констант в коде (ТЗ §2). */
    public record Limits(
            int minPairs,
            double maxDispersionPct,
            double maxReferenceSpreadPct,
            double maxResidualPct) {
    }

    /** Почему по паре нельзя котировать; null = можно. */
    public record PairState(double fairUsdc, double impliedRate, double residualPct, String pausedReason) {

        public boolean quotable() {
            return pausedReason == null;
        }
    }

    public record Result(
            double rate,
            double dispersionPct,
            int pairsUsed,
            boolean reliable,
            String unreliableReason,
            Map<String, PairState> pairs) {

        public PairState pair(String base) {
            return pairs.get(base);
        }
    }

    private FairPrice() {
    }

    public static Result compute(List<PairQuote> quotes, Limits limits) {
        List<Double> implied = new ArrayList<>();
        for (PairQuote q : quotes) {
            // мемкоины исключены из расчёта курса, но не из котирования (ТЗ §4.1)
            if (q.valid() && !q.memecoin()) {
                implied.add(q.implied());
            }
        }

        double rate = median(implied);
        double dispersionPct = dispersionPct(implied, rate);

        String unreliableReason = null;
        if (implied.size() < limits.minPairs()) {
            unreliableReason = "пар для расчёта курса " + implied.size()
                    + " при минимуме " + limits.minPairs();
        } else if (dispersionPct > limits.maxDispersionPct()) {
            unreliableReason = "разброс implied " + round(dispersionPct, 3)
                    + "% выше порога " + limits.maxDispersionPct() + "%";
        }
        boolean reliable = unreliableReason == null;

        Map<String, PairState> pairs = new LinkedHashMap<>();
        for (PairQuote q : quotes) {
            if (!q.valid()) {
                pairs.put(q.base(), new PairState(Double.NaN, Double.NaN, Double.NaN,
                        "нет корректных середин книги"));
                continue;
            }
            double residualPct = 100.0 * (q.implied() / rate - 1);
            double fair = q.midUsd() / rate;
            String paused = null;
            if (!reliable) {
                paused = "курс ненадёжен: " + unreliableReason;
            } else if (100.0 * q.spreadUsd() > limits.maxReferenceSpreadPct()) {
                // тот самый случай ETH: опора шире, чем книга, в которой торгуем
                paused = "опорная книга широка: " + round(100.0 * q.spreadUsd(), 3)
                        + "% при пороге " + limits.maxReferenceSpreadPct() + "%";
            } else if (Math.abs(residualPct) > limits.maxResidualPct()) {
                paused = "опора разошлась с рынком: остаток " + round(residualPct, 3)
                        + "% при пороге ±" + limits.maxResidualPct() + "%";
            }
            pairs.put(q.base(), new PairState(fair, q.implied(), residualPct, paused));
        }

        return new Result(rate, dispersionPct, implied.size(), reliable, unreliableReason, pairs);
    }

    /**
     * Разброс — медианное абсолютное отклонение в процентах от курса, а не
     * стандартное: дисперсию раздувает один выброс, и порог тогда сработал бы
     * от нормального рынка, а не от поломки.
     */
    static double dispersionPct(List<Double> values, double rate) {
        if (values.isEmpty() || rate <= 0) {
            return Double.NaN;
        }
        List<Double> deviations = new ArrayList<>(values.size());
        for (double v : values) {
            deviations.add(Math.abs(v - rate));
        }
        return 100.0 * median(deviations) / rate;
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double round(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }
}
