package org.home.data.revx.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Сколько времени купленный инвентарь лежит до продажи (ТЗ §5.3).
 *
 * Зачем это нужно, а не «для полноты» (док. 75 §3). Неблагоприятный отбор растёт
 * с горизонтом: по BTC он съедает 28.7% края за минуту и 53.2% за пять. Какой из
 * этих горизонтов сравнивать с комиссией — определяет не вкус, а факт: за сколько
 * позиция реально разгружается. Если инвентарь живёт минуты, порог безубыточности
 * считается по markout(300 с), и конструкция при maker 0.02% уже мертва; если
 * секунды — по markout(60 с), и запас есть.
 *
 * Сопоставление лотов — FIFO: первый купленный уходит первым. Это не бухгалтерская
 * условность, а консервативный выбор: FIFO даёт САМОЕ ДЛИННОЕ время удержания из
 * возможных сопоставлений, то есть отвечает на вопрос в невыгодную для стратегии
 * сторону. Незакрытый остаток — отдельное число, а не ноль: инвентарь, не проданный
 * до конца окна, не имеет времени удержания вовсе, и прятать его в статистику
 * значило бы занижать горизонт.
 */
public final class HoldingTime {

    /** Времена — в миллисекундах, взвешены по объёму лота. */
    public record Stats(int lots, double matchedQty, double unclosedQty,
                        double meanMs, double medianMs, double p90Ms) {

        public double unclosedShare() {
            double total = matchedQty + unclosedQty;
            return total <= 0 ? 0 : unclosedQty / total;
        }
    }

    private record Lot(long tsMs, double qty) {
    }

    private record Hold(long durationMs, double qty) {
    }

    private HoldingTime() {
    }

    public static Stats compute(List<Fill> fills) {
        Deque<Lot> open = new ArrayDeque<>();
        List<Hold> holds = new ArrayList<>();
        double matched = 0;

        for (Fill fill : fills) {
            if (fill.side() == Side.BUY) {
                open.addLast(new Lot(fill.tsMs(), fill.qty()));
                continue;
            }
            double remaining = fill.qty();
            while (remaining > 0 && !open.isEmpty()) {
                Lot lot = open.pollFirst();
                double take = Math.min(remaining, lot.qty());
                holds.add(new Hold(fill.tsMs() - lot.tsMs(), take));
                matched += take;
                remaining -= take;
                if (lot.qty() > take) {
                    open.addFirst(new Lot(lot.tsMs(), lot.qty() - take));
                }
            }
            // Остаток продажи без покрытия означал бы шорт — спот-онли его не допускает,
            // но если он появится, молчать нельзя: это дефект движка, а не статистики.
            if (remaining > 1e-12) {
                throw new IllegalStateException(
                        "продажа без инвентаря на " + remaining + " — спот-онли нарушен");
            }
        }

        double unclosed = open.stream().mapToDouble(Lot::qty).sum();
        if (holds.isEmpty()) {
            return new Stats(0, 0, unclosed, Double.NaN, Double.NaN, Double.NaN);
        }
        holds.sort(Comparator.comparingLong(Hold::durationMs));
        double weighted = holds.stream().mapToDouble(h -> h.durationMs() * h.qty()).sum();
        return new Stats(holds.size(), matched, unclosed, weighted / matched,
                weightedPercentile(holds, matched, 0.50), weightedPercentile(holds, matched, 0.90));
    }

    /** Процентиль по ОБЪЁМУ, а не по числу лотов: лоты различаются в 18 раз. */
    private static double weightedPercentile(List<Hold> sorted, double totalQty, double q) {
        double target = totalQty * q;
        double running = 0;
        for (Hold hold : sorted) {
            running += hold.qty();
            if (running >= target) {
                return hold.durationMs();
            }
        }
        return sorted.get(sorted.size() - 1).durationMs();
    }
}
