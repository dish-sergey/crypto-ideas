package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⚠️ ПОТЕРЯННОЕ ИСПОЛНЕНИЕ 13.09.2026, 22:06 UTC.
 *
 * С края пришёл залп 429 (тело HTML, а не JSON площадки), и три повтора
 * {@code GET /orders/{id}} подряд получили отказ. Заявка бота e на продажу лота
 * BTC в это время исполнилась, но узнать об этом было НЕ У КОГО: код в таком
 * случае увеличивал счётчик и возвращался, то есть забывал вопрос навсегда.
 * Монета ушла со счёта, в журнале осталась, и через минуту закричал сторож
 * расхождения позиции — единственное, что сработало правильно.
 *
 * Исправление: заявка с невыясненной судьбой попадает в очередь, и вопрос
 * повторяется раз в минуту, пока площадка не ответит.
 *
 * <h2>Что проверяет этот тест</h2>
 *
 * Повтор безопасен только потому, что учёт идёт ПО РАЗНИЦЕ: {@code QuoteLoop}
 * помнит в {@code bookedByOrder}, сколько по заявке уже записано, и записывает
 * лишь приращение. Здесь проверяется именно это правило — если оно сломается,
 * повторный вопрос станет двойным исполнением, то есть лекарство окажется хуже
 * болезни.
 *
 * Проводка (очередь, минутный повтор, тревога через пять минут) проверяется на
 * стенде и на живом журнале: ключи и клиент площадки в {@link QuoteLoop}
 * статические, и подменить их в тесте нельзя, не ломая остальные — та же
 * причина, что описана в {@link AskDecayTest}.
 */
class LostFillRetryTest {

    /** Та же арифметика, что в {@code QuoteLoop.book}: пишем разницу, а не итог. */
    private static double bookable(Map<String, Double> booked, String venueId, double total) {
        double delta = total - booked.getOrDefault(venueId, 0.0);
        if (delta > 1e-12) {
            booked.put(venueId, total);
            return delta;
        }
        return 0;
    }

    /** Один и тот же ответ, прочитанный дважды, двигает позицию ОДИН раз. */
    @Test
    void askingTwiceBooksOnce() {
        Map<String, Double> booked = new HashMap<>();
        double lot = 0.00001255;

        assertEquals(lot, bookable(booked, "b9ed7b89", lot), 1e-15, "первый ответ — записали");
        assertEquals(0.0, bookable(booked, "b9ed7b89", lot), 1e-15,
                "повторный вопрос про ту же заявку исполнением быть не может");
        assertEquals(0.0, bookable(booked, "b9ed7b89", lot), 1e-15, "и третий тоже");
    }

    /**
     * {@code filled_quantity} у площадки НАКОПИТЕЛЬНЫЙ, и частичное исполнение
     * приходит несколькими ответами. Записываться должны приращения, а сумма —
     * сходиться с итогом.
     */
    @Test
    void partialFillsAreBookedByIncrements() {
        Map<String, Double> booked = new HashMap<>();
        double lot = 0.00003765;

        double first = bookable(booked, "c02363a9", 0.00001023);
        double second = bookable(booked, "c02363a9", 0.00002500);
        double third = bookable(booked, "c02363a9", lot);

        assertEquals(0.00001023, first, 1e-15);
        assertEquals(0.00001477, second, 1e-15);
        assertEquals(0.00001265, third, 1e-15);
        assertEquals(lot, first + second + third, 1e-15, "сумма приращений равна лоту");
        assertEquals(0.0, bookable(booked, "c02363a9", lot), 1e-15, "добавить больше нечего");
    }

    /** Разные заявки считаются независимо: у каждой свой накопитель. */
    @Test
    void ordersAreCountedSeparately() {
        Map<String, Double> booked = new HashMap<>();
        double lot = 0.00001255;

        assertEquals(lot, bookable(booked, "первая", lot), 1e-15);
        assertEquals(lot, bookable(booked, "вторая", lot), 1e-15,
                "вторая заявка на тот же объём — это ДРУГОЕ исполнение");
        assertEquals(0.0, bookable(booked, "первая", lot), 1e-15);
    }
}
