package org.home.data.revx.replay;

import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Защита стенда от прогона без секундного Бинанса (15.11.2026, док. 248): прогоны 14.11 шли без файлов
 * за 14.11, опора отставала от падения на 30–65 б.п., и по этим числам чуть не выбрали функцию для живых.
 */
class HybridFairCoverageTest {

    static final long MIN = 60_000L, GAP = 15 * MIN;

    static TreeMap<Long, Double> every(long from, long to, long step) {
        TreeMap<Long, Double> m = new TreeMap<>();
        for (long t = from; t <= to; t += step) {
            m.put(t, 100.0);
        }
        return m;
    }

    @Test
    void полныйРядПроходит() {
        assertNull(HybridFair.coverageProblem(every(0, 600 * MIN, 1000), 60 * MIN, 600 * MIN, GAP));
    }

    @Test
    void короткиеДырыНорма() {
        assertNull(HybridFair.coverageProblem(every(0, 600 * MIN, 5 * MIN), 60 * MIN, 600 * MIN, GAP),
                "секунды без сделок — свечей нет, пять минут тишины не ошибка");
    }

    @Test
    void нетПоследнихСутокОстанавливает() {
        // случай 14.11: файлы есть до 13.11, окно до конца 14.11
        assertNotNull(HybridFair.coverageProblem(every(0, 300 * MIN, 1000), 60 * MIN, 600 * MIN, GAP));
    }

    @Test
    void нетНачалаОстанавливает() {
        assertNotNull(HybridFair.coverageProblem(every(300 * MIN, 600 * MIN, 1000), 60 * MIN, 600 * MIN, GAP));
    }

    @Test
    void дыраВнутриОстанавливает() {
        TreeMap<Long, Double> m = every(0, 600 * MIN, 1000);
        m.subMap(200 * MIN, 260 * MIN).clear();
        assertNotNull(HybridFair.coverageProblem(m, 60 * MIN, 600 * MIN, GAP));
    }

    @Test
    void пустойРядОстанавливает() {
        assertNotNull(HybridFair.coverageProblem(new TreeMap<>(), 0, 600 * MIN, GAP));
        assertThrows(IllegalStateException.class, () -> HybridFair.requireCoverage("SOL", new TreeMap<>(), 0, 600 * MIN));
    }
}
