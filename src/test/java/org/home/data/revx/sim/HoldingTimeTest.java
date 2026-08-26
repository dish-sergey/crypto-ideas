package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Время удержания инвентаря (ТЗ §5.3, док. 75 §3).
 *
 * Проверяется не «больше нуля»: сценарии подобраны так, что правильный ответ
 * известен заранее и отличается от неправильного. Главные из них — частичное
 * закрытие лота, взвешивание процентиля по объёму (лоты различаются в разы)
 * и незакрытый остаток, который обязан считаться отдельно, а не как ноль.
 */
class HoldingTimeTest {

    private static Fill buy(long tsMs, double qty) {
        return new Fill(tsMs, Side.BUY, 100.0, qty, 100.0);
    }

    private static Fill sell(long tsMs, double qty) {
        return new Fill(tsMs, Side.SELL, 100.0, qty, 100.0);
    }

    @Test
    void безИсполненийСтатистикиНет() {
        HoldingTime.Stats stats = HoldingTime.compute(List.of());
        assertEquals(0, stats.lots());
        assertEquals(0.0, stats.unclosedQty());
    }

    @Test
    void простойЛотДаётСвоёВремяУдержания() {
        HoldingTime.Stats stats = HoldingTime.compute(List.of(buy(0, 1.0), sell(60_000, 1.0)));
        assertEquals(1, stats.lots());
        assertEquals(60_000.0, stats.medianMs());
        assertEquals(0.0, stats.unclosedQty(), 1e-12);
    }

    /** Продажа больше первого лота закрывает его целиком и часть следующего. */
    @Test
    void частичноеЗакрытиеДелитЛот() {
        HoldingTime.Stats stats = HoldingTime.compute(
                List.of(buy(0, 1.0), buy(10_000, 1.0), sell(60_000, 1.5)));
        assertEquals(2, stats.lots());
        assertEquals(1.5, stats.matchedQty(), 1e-12);
        assertEquals(0.5, stats.unclosedQty(), 1e-12);
        // 60 000 на объём 1.0 и 50 000 на объём 0.5 → (60000*1 + 50000*0.5) / 1.5
        assertEquals(56_666.7, stats.meanMs(), 0.1);
    }

    /**
     * FIFO — не бухгалтерская условность, а выбор в невыгодную сторону: он даёт
     * САМОЕ ДЛИННОЕ время удержания. Если бы сопоставление шло LIFO, тот же набор
     * исполнений дал бы 10 с вместо 60 с, и порог по комиссии оказался бы мягче.
     */
    @Test
    void fifoДаётДлинноеУдержаниеАНеКороткое() {
        HoldingTime.Stats stats = HoldingTime.compute(
                List.of(buy(0, 1.0), buy(50_000, 1.0), sell(60_000, 1.0)));
        assertEquals(60_000.0, stats.medianMs());
        assertTrue(stats.medianMs() > 10_000, "LIFO дал бы 10 с — сопоставление не FIFO");
    }

    /**
     * Процентиль взвешен по объёму: один большой лот весит больше десяти мелких.
     * По числу лотов медиана была бы 1 с, по объёму — 100 с.
     */
    @Test
    void процентильВзвешенПоОбъёмуАНеПоЧислуЛотов() {
        List<Fill> fills = List.of(
                buy(0, 0.001), buy(0, 0.001), buy(0, 0.001), buy(0, 0.001), buy(0, 1.0),
                sell(1_000, 0.004), sell(100_000, 1.0));
        HoldingTime.Stats stats = HoldingTime.compute(fills);
        assertEquals(100_000.0, stats.medianMs(),
                "медиана по объёму обязана попасть в крупный лот");
    }

    /** Незакрытый инвентарь — отдельное число: у него нет времени удержания вовсе. */
    @Test
    void незакрытыйОстатокВидимОтдельно() {
        HoldingTime.Stats stats = HoldingTime.compute(List.of(buy(0, 1.0), sell(60_000, 0.25)));
        assertEquals(0.25, stats.matchedQty(), 1e-12);
        assertEquals(0.75, stats.unclosedQty(), 1e-12);
        assertEquals(0.75, stats.unclosedShare(), 1e-12);
    }

    /** Спот-онли: продажи без инвентаря быть не может, молчать о ней нельзя. */
    @Test
    void продажаБезИнвентаряПадает() {
        assertThrows(IllegalStateException.class,
                () -> HoldingTime.compute(List.of(sell(0, 1.0))));
    }
}
