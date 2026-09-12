package org.home.data.revx.replay;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ЗАМОРОЖЕННАЯ ОПОРА: не двигается N секунд после принта.
 *
 * Опыт разделяющий: он даёт невосприимчивость к собственному потоку пары и НЕ
 * даёт межрыночной информации. Поэтому важна ровно одна деталь реализации —
 * новый принт внутри окна ПРОДЛЕВАЕТ заморозку, но НЕ меняет удерживаемое
 * значение. Иначе пачка принтов одного свипа протащила бы опору за собой по
 * шагу, то есть вернула бы ту самую обратную связь, которую опыт убирает.
 */
class FrozenAnchorTest {

    /** {@code Slice} и {@code freeze} приватны: дёргаем отражением, API не расширяем. */
    private static Object slice(long ts, double mid) throws Exception {
        Class<?> c = Class.forName("org.home.data.revx.replay.StandFair$Slice");
        Constructor<?> ctor = c.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(ts, mid, mid, 0.0, 0.0, mid, mid, 0.0, 0.0, 0.0);
    }

    private static List<Double> freeze(List<Object> slices, List<Long> trades, long holdMs)
            throws Exception {
        Class<?> sliceClass = Class.forName("org.home.data.revx.replay.StandFair$Slice");
        Method m = StandFair.class.getDeclaredMethod("freeze", List.class, List.class, long.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) m.invoke(null, slices, trades, holdMs);
        Method smooth = sliceClass.getDeclaredMethod("smooth");
        smooth.setAccessible(true);
        List<Double> values = new ArrayList<>();
        for (Object o : out) {
            values.add((double) smooth.invoke(o));
        }
        return values;
    }

    /** Без сделок опора равна середине на каждом снимке. */
    @Test
    void noTradesMeansPlainMid() throws Exception {
        List<Object> in = List.of(slice(0, 100), slice(1000, 101), slice(2000, 102));
        assertEquals(List.of(100.0, 101.0, 102.0), freeze(in, List.of(), 5000));
    }

    /**
     * Принт в 1500 при удержании 5 с: снимки 2000 и 3000 держат значение,
     * бывшее ДО принта (101 на снимке 1000), а 7000 уже свободен.
     */
    @Test
    void tradeHoldsPreTradeMid() throws Exception {
        List<Object> in = List.of(slice(0, 100), slice(1000, 101), slice(2000, 90),
                slice(3000, 80), slice(7000, 70));
        assertEquals(List.of(100.0, 101.0, 101.0, 101.0, 70.0),
                freeze(in, List.of(1500L), 5000));
    }

    /**
     * 🔑 ПАЧКА ПРИНТОВ ОДНОГО СВИПА НЕ ТАЩИТ ОПОРУ ЗА СОБОЙ. Три принта подряд
     * продлевают окно, но удерживаемое значение остаётся тем, что было до
     * ПЕРВОГО из них.
     */
    @Test
    void burstExtendsWindowButKeepsValue() throws Exception {
        List<Object> in = List.of(slice(0, 100), slice(1000, 90), slice(2000, 80),
                slice(3000, 70), slice(20_000, 60));
        assertEquals(List.of(100.0, 100.0, 100.0, 100.0, 60.0),
                freeze(in, List.of(500L, 1500L, 2500L), 5000));
    }

    /** После окончания окна новая сделка замораживает уже НОВОЕ значение. */
    @Test
    void secondFreezeTakesFreshValue() throws Exception {
        List<Object> in = List.of(slice(0, 100), slice(1000, 90), slice(20_000, 80),
                slice(21_000, 70), slice(40_000, 60));
        assertEquals(List.of(100.0, 100.0, 80.0, 80.0, 60.0),
                freeze(in, List.of(500L, 20_500L), 5000));
    }
}
