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
        // Запас на повторы и ручные проверки. С 05.09.2026 он ровно 100 из 1000:
        // бот B переведён на SOL и поднят до 300. Меньше сотни оставлять нельзя —
        // это единственный буфер на разбор аварии, когда боты уже съели своё.
        assertTrue(VENUE_DAILY - total >= 100,
                "запас на повторы и ручные проверки меньше сотни: " + (VENUE_DAILY - total));
    }

    @Test
    void controlGetsMoreBecauseItTradesMore() {
        // A — контроль, на котором копится статистика: 48 исполнений за 6.6 ч
        // против шести у B. Каждое исполнение съедает заявку.
        assertTrue(ExecLimits.maxPlacementsPerDay("a") > ExecLimits.maxPlacementsPerDay("b"));
        // ⚠️ Прежде здесь стояло «у B и C лимит обязан совпадать»: они отличались
        // только отступом и сравнивались между собой. С 05.09.2026 B переведён на
        // SOL, сравнивать их больше не с чем, и равенство отменено намеренно.
        assertTrue(ExecLimits.maxPlacementsPerDay("b") > ExecLimits.maxPlacementsPerDay("c"),
                "у B первая пара кроме биткойна — ему нужен запас, чтобы измерение "
                        + "не упёрлось в лимит вместо рынка");
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
