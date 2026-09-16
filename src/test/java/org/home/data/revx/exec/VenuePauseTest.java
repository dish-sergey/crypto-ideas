package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ДВЕ ПАУЗЫ ПО ВИНЕ ПЛОЩАДКИ: когда бот обязан отойти в сторону.
 *
 * <h2>Зачем</h2>
 *
 * 14–16.09.2026 счёт дважды дошёл до состояния, в котором бот НЕ МОЖЕТ продать:
 * площадка держала монету в {@code reserved} под заявку, которой не показывала
 * (задача A48). Родились эти заявки на медленных заменах — площадка успевала
 * отменить исходную и создать наследника, а потом упиралась в свой
 * трёхсекундный таймаут и возвращала 422 без идентификатора наследника.
 *
 * Отсюда две паузы, и они разной природы:
 *
 * <ul>
 *   <li><b>затык</b> — профилактика. Замена дольше секунды означает, что мы в
 *       той самой полосе, где рождаются призраки; минуту не котируем;</li>
 *   <li><b>заморозка</b> — сдерживание. Продать нельзя, значит покупать тем
 *       более незачем: иначе бот копит инвентарь сверх потолка на паре, где
 *       выход закрыт.</li>
 * </ul>
 */
class VenuePauseTest {

    private static final long NOW = 1_789_457_912_573L;

    @Test
    void quietVenueDoesNotPause() {
        assertNull(QuoteLoop.venuePause(false, NOW, 0));
    }

    @Test
    void stallPausesUntilItsWindowEnds() {
        long until = NOW + QuoteLoop.STALL_STAND_ASIDE_MS;
        assertNotNull(QuoteLoop.venuePause(false, NOW, until));
        assertNotNull(QuoteLoop.venuePause(false, until - 1, until));
        assertNull(QuoteLoop.venuePause(false, until, until),
                "минута прошла — возвращаемся сами, без человека");
    }

    @Test
    void frozenOutranksStall() {
        // Обе причины сразу: затык проходит за минуту, заморозка держится
        // часами. Назвать надо ту, из-за которой человек пойдёт в --revx-audit.
        String reason = QuoteLoop.venuePause(true, NOW, NOW + QuoteLoop.STALL_STAND_ASIDE_MS);
        assertTrue(reason.contains("заперта"), reason);
    }

    @Test
    void frozenPauseHasNoDeadline() {
        // Заморозку снимает только сама площадка, и задержка наблюдалась от 2 до
        // 32 часов. Поэтому никакого таймаута у этой паузы нет: время в аргументе
        // на ответ не влияет.
        assertEquals(QuoteLoop.venuePause(true, NOW, 0),
                QuoteLoop.venuePause(true, NOW + 32 * 3_600_000L, 0));
    }

    /**
     * Порог медленной замены отделяет два РЕЖИМА, а не два соседних значения.
     *
     * Медиана нормальной замены — 170 мс на 291 729 запросах; у сорока замен,
     * оставивших неснимаемый резерв, медиана 3004 мс. Между ними пусто, и порог
     * обязан лежать в этой пустоте с запасом в обе стороны.
     */
    @Test
    void thresholdSeparatesTheTwoModes() {
        assertTrue(QuoteLoop.SLOW_REPLACE_MS > 170 * 3,
                "нормальная замена не должна попадать в затык даже с тройным запасом");
        assertTrue(QuoteLoop.SLOW_REPLACE_MS < 2500,
                "самая быстрая из призрачных замен — 2.5 с, порог обязан быть ниже");
    }
}
