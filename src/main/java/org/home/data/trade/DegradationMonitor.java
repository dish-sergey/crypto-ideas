package org.home.data.trade;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Монитор деградации (doc 54 §3): механизм S5 — предвосхищение разлока рынком, значит арбитрируем,
 * поэтому монитор — условие эксплуатации, не формальность. Ведётся в НАБЛЮДЕНИИ (сигнал, без авто-паузы).
 *
 * <p>Порог зафиксирован из ПРИНЦИПА (устойчивый уход премии в минус), НЕ подобран по данным
 * (правило `rolling-20 < 0` было опровергнуто бэктестом 73x vs 2242x — паузило на нормальной дисперсии):
 * <pre>пауза при: rolling-40 &lt; 0  ЛИБО  два подряд отрицательных rolling-20.</pre>
 * Окончательный порог подтверждается/отвергается в микро-live, но не подбирается (запрет фиттинга).
 */
public class DegradationMonitor {

    private final Deque<Double> prem = new ArrayDeque<>(); // все реализованные премии по порядку
    private int consecutiveNeg20 = 0;
    private boolean pauseSignalled = false;

    /** Зафиксировать реализованную премию сделки и пересчитать сигнал. */
    public void record(double premium) {
        prem.addLast(premium);
        Double r20 = rolling(20);
        if (r20 != null) {
            if (r20 < 0) consecutiveNeg20++;
            else consecutiveNeg20 = 0;
        }
        Double r40 = rolling(40);
        boolean byR40 = r40 != null && r40 < 0;
        boolean byR20 = consecutiveNeg20 >= 2;
        if (byR40 || byR20) pauseSignalled = true;
    }

    /** Среднее последних n премий, или null если событий меньше n. */
    public Double rolling(int n) {
        if (prem.size() < n) return null;
        double s = 0; int i = 0;
        // итерируем с конца
        var it = prem.descendingIterator();
        while (it.hasNext() && i < n) { s += it.next(); i++; }
        return s / n;
    }

    /** Сигнал паузы взведён (наблюдение — оператор/Approval Gate решает). */
    public boolean pauseSignalled() { return pauseSignalled; }

    /** Сброс сигнала после ручного разбора и перезапуска (doc 54: перезапуск — отдельная процедура). */
    public void clearPauseSignal() { pauseSignalled = false; consecutiveNeg20 = 0; }

    public int count() { return prem.size(); }
}
