package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетические круги по ленте — мерка настройки из док. 154 §I.
 *
 * Тест закрепляет три вещи, каждая из которых уже была источником ошибки в
 * соседних приборах:
 * <ul>
 *   <li>круг требует ОБЕИХ ног и строго в правильном порядке: продавец бьёт наш
 *       бид, потом покупатель бьёт наш аск. Двойная покупка подряд второго
 *       круга не открывает — лот у нас один;</li>
 *   <li>значение круга равно {@code 2δ + ход опоры}, то есть тождеству
 *       «круг = вход + снос + выход», а не одному захвату;</li>
 *   <li>безусловный контроль считает ход опоры от КАЖДОЙ минуты окна, а не от
 *       моментов наших сделок, — иначе он повторял бы тот самый отбор, который
 *       должен вычитать.</li>
 * </ul>
 */
class TapeRoundsTest {

    /** Принт: сторона агрессора, расстояние от опоры, цена опоры. */
    private static FlowMarkout.Print print(long ts, int aggressor, double distBp, double fair) {
        return new FlowMarkout.Print(ts, fair, 1.0, aggressor, distBp, fair, -1, 1, Double.NaN);
    }

    @Test
    void roundNeedsBothLegsInOrder() {
        List<FlowMarkout.Print> prints = new ArrayList<>();
        prints.add(print(1_000, -1, 12, 100));      // продавец: купили
        prints.add(print(2_000, -1, 12, 100));      // ещё продавец: лот уже есть
        prints.add(print(3_000, +1, 12, 100));      // покупатель: продали
        List<FlowMarkout.Round> rounds = FlowMarkout.rounds(prints, 10, new TreeMap<>());
        assertEquals(1, rounds.size(), "лот один — второй круг открыться не может");
        assertEquals(1_000, rounds.get(0).openMs());
        assertEquals(3_000, rounds.get(0).closeMs());
    }

    @Test
    void askBeforeBidOpensNothing() {
        List<FlowMarkout.Print> prints = List.of(
                print(1_000, +1, 12, 100),          // покупатель первым: продавать нечего
                print(2_000, +1, 12, 100));
        assertTrue(FlowMarkout.rounds(prints, 10, new TreeMap<>()).isEmpty());
    }

    @Test
    void roundValueIsTwiceOffsetPlusAnchorMove() {
        // Опора ушла вверх на 50 б.п. между ногами: круг обязан это забрать.
        List<FlowMarkout.Print> prints = List.of(
                print(1_000, -1, 12, 100.0),
                print(2_000, +1, 12, 100.5));
        List<FlowMarkout.Round> rounds = FlowMarkout.rounds(prints, 10, new TreeMap<>());
        assertEquals(1, rounds.size());
        assertEquals(2 * 10 + 50, rounds.get(0).valueBp(), 1e-6);
    }

    @Test
    void roundValueIsHurtByAnchorFallingAgainstUs() {
        List<FlowMarkout.Print> prints = List.of(
                print(1_000, -1, 12, 100.0),
                print(2_000, +1, 12, 99.5));        // опора вниз на 50 б.п.
        assertEquals(2 * 10 - 50,
                FlowMarkout.rounds(prints, 10, new TreeMap<>()).get(0).valueBp(), 1e-6);
    }

    @Test
    void eventsFartherThanDeltaOnly() {
        List<FlowMarkout.Print> prints = List.of(
                print(1_000, -1, 4, 100),           // ближе δ — до нашей заявки не дошло
                print(2_000, -1, 12, 100),
                print(3_000, +1, 12, 100));
        List<FlowMarkout.Round> rounds = FlowMarkout.rounds(prints, 10, new TreeMap<>());
        assertEquals(1, rounds.size());
        assertEquals(2_000, rounds.get(0).openMs(), "круг открылся дальним принтом, а не ближним");
    }

    @Test
    void marketControlAveragesOverEveryMinute() {
        // Опора растёт ровно на 1 б.п. в минуту: контроль за 5 минут = 5 б.п.
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        double p = 100;
        for (long m = 0; m < 100; m++) {
            byMinute.put(m, p);
            p *= 1.0001;
        }
        assertEquals(5.0, FlowMarkout.marketDrift(byMinute, 5), 0.02);
        assertEquals(1.0, FlowMarkout.marketDrift(byMinute, 1), 0.01);
    }

    @Test
    void marketControlIsZeroOnFlatAnchor() {
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        for (long m = 0; m < 50; m++) {
            byMinute.put(m, 100.0);
        }
        assertEquals(0.0, FlowMarkout.marketDrift(byMinute, 7), 1e-9);
    }

    @Test
    void gateOnEntrySkipsRoundsEntirely() {
        // Гейт запрещает вход по первому принту: круг не открывается вовсе, а
        // не открывается позже — «не котировать» значит не котировать.
        List<FlowMarkout.Print> prints = List.of(
                print(1_000, -1, 12, 100.0),
                print(2_000, +1, 12, 100.0),
                print(3_000, -1, 12, 100.0),
                print(4_000, +1, 12, 100.0));
        List<FlowMarkout.Round> all = FlowMarkout.rounds(prints, 10, p -> true, p -> true);
        List<FlowMarkout.Round> gated =
                FlowMarkout.rounds(prints, 10, p -> p.tsMs() > 2_500, p -> true);
        assertEquals(2, all.size());
        assertEquals(1, gated.size());
        assertEquals(3_000, gated.get(0).openMs());
    }
}
