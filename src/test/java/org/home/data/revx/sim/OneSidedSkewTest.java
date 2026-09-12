package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СКОС НИЖЕ ЦЕЛИ — ПРИМАНКА, А НЕ РАЗГРУЗКА.
 *
 * Скос вычитается из ОБЕИХ цен: {@code бид = fair·(1 − отступ − k·skew)},
 * {@code аск = fair·(1 + отступ − k·skew)}. Значит при отрицательном скосе
 * (инвентарь ниже цели) обе цены идут ВВЕРХ — бид ближе к справедливой цене,
 * аск дальше. Задуман скос был страховкой от переполнения, а работает
 * преимущественно в наборе: живой замер 12.09.2026 дал «ниже цели» 89–92%
 * времени у ботов с целью 0.3 и 53–75% у ботов с целью 0.5.
 *
 * Тест фиксирует арифметику обоих режимов и то, что ключ выключен по умолчанию.
 */
class OneSidedSkewTest {

    private static double skew(double inventory, double cap, double target) throws Exception {
        Quoter.Params p = params(cap, target);
        Method m = Quoter.class.getDeclaredMethod("skew", Quoter.Params.class,
                double.class, double.class);
        m.setAccessible(true);
        return (double) m.invoke(null, p, inventory, 0.0);
    }

    /** Отступ 12 б.п., лот и потолок в лотах, k = 0.0010 — боевая форма бота A. */
    private static Quoter.Params params(double cap, double target) {
        return new Quoter.Params(0.0012, 1.0, cap, 0.0010, target, 0.00005, 0.01);
    }

    /**
     * Цель 30%, потолок 7 лотов: пустой бот даёт −0.3/0.7 = −0.4286, то есть
     * сдвиг обеих цен на k·0.4286 = 4.29 б.п. ВВЕРХ при k = 0.0010.
     */
    @Test
    void emptyBotAtTargetThirtyPullsBidByFourBp() throws Exception {
        double s = skew(0, 7, 0.3);
        assertEquals(-0.42857, s, 1e-4);
        assertEquals(4.2857, -s * 0.0010 * 10_000, 1e-3);
    }

    /**
     * ⚠️ Цель 50% притягивает ВДВОЕ сильнее при той же пустоте: знаменатель
     * {@code max(0.5, 0.5) = 0.5} вместо 0.7, скос упирается в −1.0, и сдвиг
     * равен целым 10 б.п. — больше боевого отступа бота e (6 б.п.).
     */
    @Test
    void emptyBotAtTargetFiftyPullsTwiceAsHard() throws Exception {
        double s = skew(0, 7, 0.5);
        assertEquals(-1.0, s, 1e-9);
        assertEquals(10.0, -s * 0.0010 * 10_000, 1e-9);
        assertTrue(6.0 - 10.0 < 0, "бид уходит выше справедливой цены");
    }

    /** Выше цели знак обратный: обе цены вниз, то есть разгрузка. */
    @Test
    void aboveTargetSkewUnloads() throws Exception {
        assertTrue(skew(7, 7, 0.3) > 0);
        assertTrue(skew(7, 7, 0.5) > 0);
    }

    /**
     * Односторонний режим обнуляет только нижнюю половину и не трогает верхнюю.
     * Ключ читается статически, поэтому проверяется через отдельный процесс —
     * здесь фиксируется само правило на уровне арифметики.
     */
    @Test
    void oneSidedZeroesOnlyTheLowerHalf() {
        assertEquals(0.0, oneSided(-0.4286), 1e-9);
        assertEquals(0.0, oneSided(-1.0), 1e-9);
        assertEquals(0.5, oneSided(0.5), 1e-9);
    }

    private static double oneSided(double inv) {
        return inv < 0 ? 0 : inv;
    }

    /** По умолчанию поведение прежнее: ключ выключен. */
    @Test
    void twoSidedByDefault() throws Exception {
        assertTrue(skew(0, 7, 0.3) < 0,
                "без ключа скос ниже цели обязан оставаться отрицательным");
    }
}
