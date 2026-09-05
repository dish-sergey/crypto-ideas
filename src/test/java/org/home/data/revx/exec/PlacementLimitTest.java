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
    void tightestOffsetGetsTheBiggestBudget() {
        // ⚠️ Раскладка перевёрнута 05.09.2026. Прежде больше всех получал A как
        // контроль; теперь самый узкий отступ у C (7 б.п. против 10 у A), а
        // расход постановок идёт за исполнениями: каждое съедает заявку и
        // требует новой. По лестнице отступов на 7 б.п. ожидается около
        // 100 постановок в сутки против 25 у A — значит бюджет нужен C.
        assertTrue(ExecLimits.maxPlacementsPerDay("c") > ExecLimits.maxPlacementsPerDay("a"),
                "у C отступ уже, исполнений больше — ему и бюджет");
        // B на SOL: поток там тоньше всего, 12 постановок за 17 часов.
        assertTrue(ExecLimits.maxPlacementsPerDay("a") > ExecLimits.maxPlacementsPerDay("b"),
                "B на SOL расходует меньше всех");
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
