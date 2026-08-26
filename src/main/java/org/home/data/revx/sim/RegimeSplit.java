package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Чистый край в разрезе режима рынка ВНУТРИ окна.
 *
 * Зачем, если есть разрез по суткам. Окно 20–25.08.2026 поймало рост BTC на 14%,
 * из них +5.4% и +7.1% в первые двое суток; пяти дневных чисел мало, чтобы отделить
 * край от направления — падающих суток в выборке всего одни. Разрез по режиму
 * работает с теми же 2964 исполнениями, а не с пятью точками, и потому отвечает
 * на тот же вопрос с несопоставимо большей статистикой.
 *
 * Ключевое требование к признаку режима — он обязан быть известен ДО исполнения.
 * Дрейф считается за час ПЕРЕД исполнением: если брать окно, пересекающееся с
 * горизонтом markout, корреляция появится механически, и раздел покажет не режим,
 * а сам же markout.
 */
public final class RegimeSplit {

    public record Bucket(String label, int fills, double turnover,
                         double netEdgeBp, double buyEdgeBp, double sellEdgeBp) {
    }

    private RegimeSplit() {
    }

    /**
     * @param thresholdPct граница «плоского» режима в процентах дрейфа за lookback
     */
    public static List<Bucket> compute(List<Fill> fills, NavigableMap<Long, Double> fair,
                                       long lookbackMs, long horizonMs, double thresholdPct) {
        Map<String, List<Fill>> byRegime = new java.util.LinkedHashMap<>();
        byRegime.put("рынок падал", new ArrayList<>());
        byRegime.put("рынок стоял", new ArrayList<>());
        byRegime.put("рынок рос", new ArrayList<>());

        for (Fill fill : fills) {
            var before = fair.floorEntry(fill.tsMs() - lookbackMs);
            var at = fair.floorEntry(fill.tsMs());
            if (before == null || at == null || before.getValue() <= 0) {
                continue;               // нет часа истории — исполнение без метки режима
            }
            double driftPct = 100.0 * (at.getValue() / before.getValue() - 1);
            String label = driftPct < -thresholdPct ? "рынок падал"
                    : driftPct > thresholdPct ? "рынок рос" : "рынок стоял";
            byRegime.get(label).add(fill);
        }

        List<Bucket> out = new ArrayList<>();
        for (var entry : byRegime.entrySet()) {
            List<Fill> group = entry.getValue();
            out.add(new Bucket(entry.getKey(), group.size(), turnover(group),
                    netEdgeBp(group, fair, horizonMs),
                    netEdgeBp(side(group, Side.BUY), fair, horizonMs),
                    netEdgeBp(side(group, Side.SELL), fair, horizonMs)));
        }
        return out;
    }

    private static List<Fill> side(List<Fill> fills, Side side) {
        return fills.stream().filter(f -> f.side() == side).toList();
    }

    private static double turnover(List<Fill> fills) {
        return fills.stream().mapToDouble(Fill::notional).sum();
    }

    private static double netEdgeBp(List<Fill> fills, NavigableMap<Long, Double> fair, long horizonMs) {
        List<Fill> withHorizon = Markout.withHorizon(fills, fair, horizonMs);
        double turnover = turnover(withHorizon);
        if (turnover <= 0) {
            return Double.NaN;
        }
        Markout.Stats atFill = Markout.compute(withHorizon, fair, 0);
        Markout.Stats later = Markout.compute(withHorizon, fair, horizonMs);
        return (atFill.mean() * atFill.fills() + later.mean() * later.fills()) / turnover * 10_000;
    }
}
