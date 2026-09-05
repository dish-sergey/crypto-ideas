package org.home.data.revx.replay;

import org.home.data.revx.exec.Clock;
import org.home.data.revx.exec.FairSource;
import org.home.data.revx.exec.StandReader;

import java.util.List;

/**
 * Справедливая цена из записи живого бота.
 *
 * Живой бот на каждом тике писал в {@code exec_quote} то число, по которому
 * принял решение, вместе с признаком {@code quotable} и причиной паузы. Повтор
 * отдаёт ровно их — не пересчитывая ничего из книги.
 *
 * ⚠️ Соблазн пересчитать справедливую цену заново из снимков стенда велик и
 * вреден: даже более точное число сделает сверку невозможной. Разойдутся
 * котировки, и причину придётся искать между двумя вычислениями цены, а не в
 * логике бота — то есть ровно там, где её нет.
 */
public final class ReplayFair implements FairSource {

    /** Один записанный тик. */
    public record Tick(long tsMs, double fair, Double bid, Double ask, double inventory,
                       boolean quotable, String reason) {
    }

    private final List<Tick> ticks;
    private final Clock clock;
    private int cursor;

    public ReplayFair(List<Tick> ticks, Clock clock) {
        this.ticks = ticks;
        this.clock = clock;
    }

    public List<Tick> ticks() {
        return ticks;
    }

    /** Тик, действующий на текущий момент часов. */
    public synchronized Tick current() {
        long now = clock.now();
        while (cursor + 1 < ticks.size() && ticks.get(cursor + 1).tsMs() <= now) {
            cursor++;
        }
        return ticks.isEmpty() ? null : ticks.get(cursor);
    }

    @Override
    public StandReader.Fair latest(String base, long lookbackMs) {
        Tick t = current();
        if (t == null) {
            return new StandReader.Fair(0, false, "запись кончилась", 0, 0);
        }
        // pairsUsed=8 — любое число, проходящее гейт: сам гейт уже отработал в
        // живом боте, и его вердикт записан в quotable.
        return new StandReader.Fair(t.fair(), t.quotable(), t.reason(), t.tsMs(), 8,
                0, 0);
    }
}
