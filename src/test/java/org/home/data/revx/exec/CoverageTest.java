package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ПОКРЫТИЕ: хватает ли доступного на то, что за ботами числится.
 *
 * <h2>Почему так, а не «сколько заперто от потолка»</h2>
 *
 * Мерка прошла три версии за сутки, и первые две были неверны:
 *
 * <ol>
 *   <li><b>«заперт хотя бы лот»</b> — 16.09.2026 бот {@code d} встал, имея ТРИ
 *       продаваемых лота, а сосед {@code c} на той же заморозке не встал, потому
 *       что у него крупнее лот. Абсолютная величина ничего не говорит;</li>
 *   <li><b>«заперто больше половины потолка»</b> — лучше, но потолок это
 *       РАЗРЕШЕНИЕ торговать, а не обязательство иметь. Бот с пустым инвентарём
 *       и половиной запертого потолка прекрасно работает;</li>
 *   <li><b>«доступного меньше, чем числится»</b> — решение владельца и
 *       единственное, что описывает беду: бот не может забрать своё.</li>
 * </ol>
 *
 * Считается по ВСЕМ ботам и обеим валютам: счёт общий, и заморозка у соседа
 * отнимает доступное у всех.
 */
class CoverageTest {

    @Test
    void nothingClaimedIsFullyCovered() {
        assertEquals(1.0, QuoteLoop.coverage(0, 0), 1e-12,
                "не числится ничего — покрывать нечего, это не беда");
    }

    @Test
    void plentyAvailableIsFullCoverage() {
        assertTrue(QuoteLoop.coverage(10, 4) >= QuoteLoop.COVERAGE_RESUME);
    }

    @Test
    void shortfallIsProportional() {
        assertEquals(0.5, QuoteLoop.coverage(2, 4), 1e-12);
        assertEquals(0.7, QuoteLoop.coverage(7, 10), 1e-12);
    }

    @Test
    void thirtyPercentShortTripsUnwind() {
        // Ровно граница владельца: «если доступного не хватает на 30%».
        assertFalse(QuoteLoop.coverage(7, 10) < QuoteLoop.COVERAGE_UNWIND,
                "ровно 70% — ещё работаем");
        assertTrue(QuoteLoop.coverage(6.9, 10) < QuoteLoop.COVERAGE_UNWIND,
                "чуть меньше — уходим в распродажу");
    }

    @Test
    void resumeNeedsMoreThanUnwindThreshold() {
        // Гистерезис обязателен: совпади пороги, бот дёргался бы на границе, а
        // каждый заход в распродажу стоит сведённой позиции и оборота.
        assertTrue(QuoteLoop.COVERAGE_RESUME > QuoteLoop.COVERAGE_UNWIND);
        double onEdge = QuoteLoop.coverage(8, 10);
        assertFalse(onEdge < QuoteLoop.COVERAGE_UNWIND, "не уходим");
        assertFalse(onEdge >= QuoteLoop.COVERAGE_RESUME, "но и не возвращаемся");
    }

    @Test
    void negativeAvailableIsZeroCoverage() {
        // Отрицательного доступного быть не должно, но если пришло — это ноль
        // покрытия, а не отрицательное число, которое сравнится как угодно.
        assertEquals(0.0, QuoteLoop.coverage(-1, 10), 1e-12);
    }
}
