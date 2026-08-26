package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разрез края по режиму рынка.
 *
 * Проверяется главное свойство конструкции: признак режима берётся ИЗ ПРОШЛОГО.
 * Если бы окно дрейфа пересекалось с горизонтом markout, раздел показывал бы
 * корреляцию markout с самим собой, и «край на падении» получался бы бесплатно.
 */
class RegimeSplitTest {

    private static final long HOUR = 3_600_000L;

    /** Опорный ряд: два часа роста, затем два часа падения, шаг минута. */
    private static TreeMap<Long, Double> fairSeries() {
        TreeMap<Long, Double> fair = new TreeMap<>();
        for (long minute = 0; minute <= 240; minute++) {
            double price = minute <= 120 ? 100.0 + minute * 0.05 : 106.0 - (minute - 120) * 0.05;
            fair.put(minute * 60_000L, price);
        }
        return fair;
    }

    @Test
    void исполнениеБезЧасаИсторииМеткиНеПолучает() {
        // Исполнение на 30-й минуте: часа перед ним в ряду нет.
        List<RegimeSplit.Bucket> buckets = RegimeSplit.compute(
                List.of(new Fill(30 * 60_000L, Side.BUY, 100.0, 1.0, 101.5)),
                fairSeries(), HOUR, 60_000L, 0.2);
        assertEquals(0, buckets.stream().mapToInt(RegimeSplit.Bucket::fills).sum());
    }

    @Test
    void растущийИПадающийУчасткиПопадаютВРазныеКорзины() {
        Fill onRise = new Fill(120 * 60_000L, Side.BUY, 105.0, 1.0, 106.0);
        Fill onFall = new Fill(240 * 60_000L, Side.BUY, 99.0, 1.0, 100.0);
        List<RegimeSplit.Bucket> buckets = RegimeSplit.compute(
                List.of(onRise, onFall), fairSeries(), HOUR, 60_000L, 0.2);

        RegimeSplit.Bucket up = buckets.stream().filter(b -> b.label().equals("рынок рос"))
                .findFirst().orElseThrow();
        RegimeSplit.Bucket down = buckets.stream().filter(b -> b.label().equals("рынок падал"))
                .findFirst().orElseThrow();
        assertEquals(1, up.fills());
        assertEquals(1, down.fills());
    }

    /**
     * Метка режима не должна зависеть от того, что произошло ПОСЛЕ исполнения.
     * Вершина ряда — минута 120: до неё час роста, после неё час падения. Метка
     * обязана быть «рос»: иначе признак смотрит в будущее.
     */
    @Test
    void меткаБерётсяИзПрошлогоАНеИзБудущего() {
        List<RegimeSplit.Bucket> buckets = RegimeSplit.compute(
                List.of(new Fill(120 * 60_000L, Side.BUY, 105.0, 1.0, 106.0)),
                fairSeries(), HOUR, 60_000L, 0.2);
        RegimeSplit.Bucket up = buckets.stream().filter(b -> b.label().equals("рынок рос"))
                .findFirst().orElseThrow();
        assertEquals(1, up.fills(), "на вершине ряда метка обязана быть по прошлому часу");
    }

    @Test
    void плоскийУчастокПопадаетВСреднююКорзину() {
        TreeMap<Long, Double> flat = new TreeMap<>();
        for (long minute = 0; minute <= 120; minute++) {
            flat.put(minute * 60_000L, 100.0 + (minute % 2) * 0.01);   // ±0.01% шум
        }
        // Исполнение не на самой границе ряда: горизонт markout обязан в него влезть.
        List<RegimeSplit.Bucket> buckets = RegimeSplit.compute(
                List.of(new Fill(100 * 60_000L, Side.BUY, 99.9, 1.0, 100.0)),
                flat, HOUR, 60_000L, 0.2);
        RegimeSplit.Bucket flatBucket = buckets.stream().filter(b -> b.label().equals("рынок стоял"))
                .findFirst().orElseThrow();
        assertEquals(1, flatBucket.fills());
        assertTrue(Double.isFinite(flatBucket.netEdgeBp()));
    }
}
