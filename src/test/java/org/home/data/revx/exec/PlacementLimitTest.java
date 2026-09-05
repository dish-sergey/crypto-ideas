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
    void gridBotsGetTheBudgetAndTheStoppedOneGetsLeast() {
        // ⚠️ Раскладка менялась дважды за 05.09.2026 и теперь стоит под СЕТКИ:
        // A ведёт сетку на BTC, B — на SOL, C остановлен.
        //
        // Сетке измерено 278 постановок в сутки против 233 у одиночной
        // котировки, поэтому A нужно 400: при 300 она встала бы впритык, а
        // упереться нельзя — при исчерпании лимита бот не пропускает
        // постановку, а ВЫКЛЮЧАЕТСЯ совсем.
        assertTrue(ExecLimits.maxPlacementsPerDay("a") > ExecLimits.maxPlacementsPerDay("b"),
                "сетка на BTC расходует больше всех: поток там втрое гуще, чем на SOL");
        assertTrue(ExecLimits.maxPlacementsPerDay("b") > ExecLimits.maxPlacementsPerDay("c"),
                "C остановлен — ему нужен наименьший бюджет");
        assertTrue(ExecLimits.maxPlacementsPerDay("a") >= 400,
                "сетке нужно 278 в сутки, 300 было бы впритык");
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
