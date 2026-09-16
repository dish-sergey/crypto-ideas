package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * НИЧЕЙНОЕ: сторож обязан сообщать НОВОСТЬ, а не состояние.
 *
 * <h2>Зачем</h2>
 *
 * Тревога про ничейную монету заведена не зря: ею никто не торгует, ни один
 * счётчик её не показывает, и владелец узнаёт о ней, случайно заглянув в
 * приложение. Так было 10.09.2026 (лот пролежал ничейным восемь часов после
 * потерянного исполнения) и 13.09.2026 (по лоту на трёх парах после
 * {@code /release} без обратного захвата).
 *
 * Но 16.09.2026 владелец освободил претензии НАРОЧНО, чтобы начать с нуля, — и
 * получил шесть тревог в час про состояние, которое сам и сделал. Отличить
 * нарочное от потерянного сторож не может, зато может отличить НОВОЕ от уже
 * названного: новая беда приходит лотом, а известный остаток не меняется.
 *
 * ⚠️ Цена ошибки в обратную сторону выше, чем кажется: тревога, которую видно
 * каждый час без повода, приучает не читать тревоги, а следующая будет про
 * потерянное исполнение.
 */
class UnownedNewsTest {

    private static final double LOT = 0.00944;

    @Test
    void firstTimeIsAlwaysNews() {
        assertTrue(QuoteLoop.unownedIsNews(5 * LOT, 0, LOT));
    }

    @Test
    void sameAmountIsNotNewsAgain() {
        // Ровно случай 16.09.2026: 0.0472 SOL лежат ничейными и лежать будут.
        assertFalse(QuoteLoop.unownedIsNews(5 * LOT, 5 * LOT, LOT));
    }

    @Test
    void driftBelowALotIsNotNews() {
        // Пыль и запаздывание снимка остатков не должны считаться новой бедой.
        assertFalse(QuoteLoop.unownedIsNews(5 * LOT + LOT * 0.9, 5 * LOT, LOT));
    }

    @Test
    void anotherLotGoneMissingIsNews() {
        // Вот это и есть новая беда: пришёл лот и хозяина не нашёл.
        assertTrue(QuoteLoop.unownedIsNews(6 * LOT, 5 * LOT, LOT));
    }

    @Test
    void shrinkingIsNotNews() {
        // Забрали часть — сообщать не о чем; сторож не считает уменьшение бедой.
        assertFalse(QuoteLoop.unownedIsNews(2 * LOT, 5 * LOT, LOT));
    }
}
