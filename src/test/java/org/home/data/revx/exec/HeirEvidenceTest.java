package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.home.data.revx.exec.QuoteLoop.HeirVerdict.НЕ_СОВПАЛО;
import static org.home.data.revx.exec.QuoteLoop.HeirVerdict.СЛИШКОМ_МНОГО;
import static org.home.data.revx.exec.QuoteLoop.HeirVerdict.СОВПАЛО;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⚠️ ЕДИНСТВЕННОЕ МЕСТО В БОТЕ, ГДЕ ИСПОЛНЕНИЕ ЗАПИСЫВАЕТСЯ НЕ ПО ОТВЕТУ
 * ПЛОЩАДКИ. Поэтому правило вынесено в чистую функцию и проверяется здесь.
 *
 * <h2>Откуда взялся случай</h2>
 *
 * Замена получает 422: площадка замену ВЫПОЛНИЛА, наследника создала, а его
 * {@code venue_order_id} не вернула. Спросить о нём нечем — зонд 14.09.2026
 * показал, что заявка адресуется только по venue_order_id, а истории заявок у
 * площадки нет. Обычно наследника находит сверка в книге; если он успел
 * исполниться раньше, исполнение теряется навсегда. Так 14.09 в 06:31 у бота A
 * пропала покупка лота BTC: монета пришла на счёт ничейной, деньги ушли.
 *
 * <h2>Правило</h2>
 *
 * Записывать только при ДВУХ независимых уликах: появилась ничейная монета И
 * ровно на её стоимость не хватает кассы. Сравнение полосой, а не равенством:
 * на счёте лежит пыль, цена могла отличаться на тик, касса шевелится сделками
 * соседа по паре. Сверху нехватка кассы ограничена: кратно большее расхождение —
 * это не наш наследник, и подменять догадкой разбор нельзя.
 *
 * Числа в тестах — лот бота A (0.00003765 BTC) по цене 77 802, то есть тот самый
 * случай.
 */
class HeirEvidenceTest {

    private static final double LOT = 0.00003765;
    private static final double PRICE = 77802.81;
    private static final double NOTIONAL = LOT * PRICE;   // ≈ 2.93 USDC

    /** Обе улики на месте — это наш наследник. */
    @Test
    void bothCluesMatch() {
        assertEquals(СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL, LOT, NOTIONAL));
    }

    /** Точных совпадений не бывает: пыль на счёте и тик цены допускаются. */
    @Test
    void smallDriftIsTolerated() {
        assertEquals(СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL,
                LOT * 0.97, NOTIONAL * 0.85), "монета на 3% меньше, касса на 15%");
    }

    /** Монета появилась, а деньги на месте — значит покупка не наша. */
    @Test
    void coinWithoutMissingCashIsNotOurs() {
        assertEquals(НЕ_СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL, LOT, 0));
    }

    /** Денег не хватает, а монеты нет — записывать нечего. */
    @Test
    void missingCashWithoutCoinIsNotEnough() {
        assertEquals(НЕ_СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL, 0, NOTIONAL));
    }

    /** Монеты заметно меньше, чем мы покупали: чужой остаток, не наш лот. */
    @Test
    void tooLittleCoinIsRefused() {
        assertEquals(НЕ_СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL, LOT * 0.5, NOTIONAL));
    }

    /**
     * 🔑 Нехватка кассы кратно больше нашей сделки — это НЕ наш наследник, а
     * расхождение покрупнее. Такое уходит в тревогу, а не в запись: догадкой
     * нельзя подменять разбор.
     */
    @Test
    void hugeCashGapGoesToAlarmNotToBooking() {
        assertEquals(СЛИШКОМ_МНОГО, QuoteLoop.heirVerdict(LOT, NOTIONAL, LOT, NOTIONAL * 5));
    }

    /** Ничейной монеты больше нашего лота — берём свой размер, это допустимо. */
    @Test
    void moreUnownedCoinThanOursStillMatches() {
        assertEquals(СОВПАЛО, QuoteLoop.heirVerdict(LOT, NOTIONAL, LOT * 2, NOTIONAL));
    }
}
