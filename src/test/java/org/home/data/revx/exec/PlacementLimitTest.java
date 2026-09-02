package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Суточный лимит постановок — по ботам, а не поровну (док. 74: потолок у
 * площадки общий на ВЕСЬ аккаунт). Тест сторожит два свойства: сумма не
 * превышает того, что даёт площадка, и незнакомый бот получает консервативную
 * долю, а не максимум.
 */
class PlacementLimitTest {

    /** Столько даёт площадка на аккаунт. */
    private static final int VENUE_DAILY = 1000;

    @Test
    void sumOverBotsStaysUnderVenueLimit() {
        int total = ExecLimits.maxPlacementsPerDay("a")
                + ExecLimits.maxPlacementsPerDay("b")
                + ExecLimits.maxPlacementsPerDay("c");
        assertTrue(total <= VENUE_DAILY,
                "сумма по трём ботам " + total + " превышает потолок площадки " + VENUE_DAILY);
        assertTrue(total <= VENUE_DAILY * 0.85,
                "нужен запас на повторы и ручные проверки, а осталось " + (VENUE_DAILY - total));
    }

    @Test
    void controlGetsMoreBecauseItTradesMore() {
        // A — контроль, на котором копится статистика: 48 исполнений за 6.6 ч
        // против шести у B. Каждое исполнение съедает заявку.
        assertTrue(ExecLimits.maxPlacementsPerDay("a") > ExecLimits.maxPlacementsPerDay("b"));
        assertEquals(ExecLimits.maxPlacementsPerDay("b"), ExecLimits.maxPlacementsPerDay("c"),
                "B и C сравниваются между собой, у них лимит обязан совпадать");
    }

    @Test
    void unknownBotGetsConservativeShare() {
        int unknown = ExecLimits.maxPlacementsPerDay("z");
        assertTrue(unknown <= ExecLimits.maxPlacementsPerDay("b"),
                "незнакомый бот не должен получать больше известного");
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(null));
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(""));
    }

    @Test
    void idIsMatchedByFirstLetterAndCase() {
        assertEquals(ExecLimits.maxPlacementsPerDay("a"), ExecLimits.maxPlacementsPerDay("A"));
        assertEquals(ExecLimits.maxPlacementsPerDay("a"), ExecLimits.maxPlacementsPerDay(" a "));
    }
}
