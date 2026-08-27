package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Арифметика края. Тест существует из-за конкретной ошибки, прожившей в стенде
 * от дока 76 до дока 85: {@code markout} считался от ЦЕНЫ ЗАЯВКИ, а не от
 * справедливой цены, поэтому содержал в себе захват спреда. Величина
 * «захват + markout» складывала захват дважды и при нулевом дрейфе давала
 * 2·d вместо d — то есть завышала край примерно вдвое.
 *
 * Сценарии подобраны так, что правильный ответ известен заранее и отличается от
 * неправильного ровно на эту величину.
 */
class EdgeArithmeticTest {

    private static final double FAIR = 100.0;
    private static final double D = 0.001;                  // отступ 10 б.п.

    private static TreeMap<Long, Double> flatFair() {
        TreeMap<Long, Double> fair = new TreeMap<>();
        for (long minute = 0; minute <= 10; minute++) {
            fair.put(minute * 60_000L, FAIR);
        }
        return fair;
    }

    /** Покупка на d ниже справедливой цены; справедливая цена не двигается. */
    private static Fill buyAtOffset() {
        double price = FAIR * (1 - D);
        return new Fill(60_000L, Side.BUY, price, 1.0, FAIR);
    }

    @Test
    void безДрейфаMarkoutРавенНулю() {
        Markout.Stats stats = Markout.compute(List.of(buyAtOffset()), flatFair(), 60_000);
        assertEquals(0.0, stats.mean(), 1e-9,
                "markout обязан мерить движение ПОСЛЕ исполнения, а не захват");
    }

    @Test
    void безДрейфаКрайРавенОтступуАНеДвумОтступам() {
        Fill fill = buyAtOffset();
        Markout.Stats at60 = Markout.compute(List.of(fill), flatFair(), 60_000);
        double edge = fill.spreadCapture() + at60.mean();

        assertEquals(FAIR * D, edge, 1e-9, "край при нулевом дрейфе равен d");
        assertTrue(Math.abs(edge - 2 * FAIR * D) > 1e-9,
                "если край равен 2·d — захват сложен дважды, ошибка вернулась");
    }

    /** Цена ушла против нас после покупки: край обязан уменьшиться ровно на дрейф. */
    @Test
    void дрейфПротивНасВычитаетсяИзКрая() {
        TreeMap<Long, Double> fair = flatFair();
        fair.put(120_000L, FAIR - 0.05);                    // −5 б.п. через минуту

        Fill fill = buyAtOffset();
        Markout.Stats at60 = Markout.compute(List.of(fill), fair, 60_000);

        assertEquals(-0.05, at60.mean(), 1e-9);
        assertEquals(FAIR * D - 0.05, fill.spreadCapture() + at60.mean(), 1e-9);
    }

    /**
     * Захват считается против справедливой цены В МОМЕНТ ИСПОЛНЕНИЯ. Если цена
     * успела уйти против нас до исполнения, захват обязан стать ОТРИЦАТЕЛЬНЫМ —
     * это и есть «нас разобрали». Пока захват брался от цены котирования, такой
     * сделки в модели не могло существовать в принципе.
     */
    @Test
    void устаревшаяЗаявкаДаётОтрицательныйЗахват() {
        double price = FAIR * (1 - D);                      // котировали по 99.9
        double fairWhenFilled = FAIR * (1 - 3 * D);         // а исполнились, когда стало 99.7
        Fill fill = new Fill(60_000L, Side.BUY, price, 1.0, fairWhenFilled);

        assertTrue(fill.spreadCapture() < 0,
                "покупка выше справедливой цены обязана давать отрицательный захват");
        assertEquals(FAIR * (1 - 3 * D) - price, fill.spreadCapture(), 1e-9);
    }

    /** Для продажи знак зеркальный — перепутанный знак валит тест. */
    @Test
    void дляПродажиЗнакиЗеркальны() {
        double price = FAIR * (1 + D);
        Fill sell = new Fill(60_000L, Side.SELL, price, 1.0, FAIR);
        assertEquals(FAIR * D, sell.spreadCapture(), 1e-9);

        TreeMap<Long, Double> fair = flatFair();
        fair.put(120_000L, FAIR + 0.05);                    // цена выросла — против продавца
        Markout.Stats at60 = Markout.compute(List.of(sell), fair, 60_000);
        assertEquals(-0.05, at60.mean(), 1e-9);
    }
}
