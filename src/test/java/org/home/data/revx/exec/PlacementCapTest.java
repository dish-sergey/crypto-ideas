package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СУТОЧНЫЕ ПОТОЛКИ ПОСТАНОВОК — предохранитель от сбоя одного бота.
 *
 * ⚠️ Тест сторожит два свойства, каждое из которых уже ломалось.
 *
 * Первое: незнакомая метка бота обязана получать САМУЮ МАЛУЮ долю. Опечатка в
 * {@code --revx.exec.bot-id} иначе молча выдаёт боту бюджет больше настроенного.
 *
 * Второе: доли должны быть выше предсказанного спроса, но не настолько, чтобы
 * один сбойный бот успел выбрать всё ведро. Предсказание обхода на 12 б.п.
 * (август, верхняя оценка): одноуровневые 37/47/53, трёхуровневые 81/129/176.
 */
class PlacementCapTest {

    @Test
    void singleLevelBotsGetNinetyFive() {
        // 15.10.2026: 100 → 95, пять отдано пилоту g (решение владельца).
        for (String bot : new String[]{"a", "b", "c"}) {
            assertEquals(95, ExecLimits.maxPlacementsPerDay(bot), "бот " + bot);
        }
    }

    @Test
    void threeLevelBotsGetTwoThirtyFive() {
        for (String bot : new String[]{"d", "e", "f"}) {
            assertEquals(235, ExecLimits.maxPlacementsPerDay(bot), "бот " + bot);
        }
    }

    /** ⚠️ Опечатка в метке не должна давать БОЛЬШЕ, чем настроено. */
    @Test
    void unknownBotGetsTheSmallestShare() {
        assertEquals(60, ExecLimits.maxPlacementsPerDay("zzz"));
        assertEquals(60, ExecLimits.maxPlacementsPerDay(""));
        assertEquals(60, ExecLimits.maxPlacementsPerDay(null));
    }

    /** Пилот ADA (бот g): ~15 на укусы и исполнения + ~18 на возврат после рывков. */
    @Test
    void pilotGetsSixty() {
        assertEquals(60, ExecLimits.maxPlacementsPerDay("g"));
    }

    /**
     * Доля вдвое выше СРЕДНЕГО живого спроса самого прожорливого (f, SOL на трёх уровнях:
     * 110 в сутки за 08–14.10). ⚠️ Пиковые скользящие сутки выше (f 255, c 103, a 101) —
     * в такие дни бот упирается в долю, и это осознанная плата за пилот.
     */
    @Test
    void capIsAboveMeasuredDemand() {
        assertTrue(ExecLimits.maxPlacementsPerDay("f") >= 2 * 110,
                "трёхуровневый SOL: в среднем 110 постановок в сутки");
        assertTrue(ExecLimits.maxPlacementsPerDay("b") >= 53,
                "одноуровневый SOL: 52 в сутки");
    }

    /**
     * ⚠️ Сумма НАМЕРЕННО выше тысячи площадки: все шестеро на потолке
     * одновременно не бывают, а поровну поделённое ведро гарантированно
     * недоиспользуется. Тест фиксирует, что это осознанный выбор, а не описка, —
     * и что перебор небольшой.
     */
    @Test
    void sumExceedsVenueLimitButOnlySlightly() {
        int sum = 0;
        for (String bot : new String[]{"a", "b", "c", "d", "e", "f", "g"}) {
            sum += ExecLimits.maxPlacementsPerDay(bot);
        }
        assertEquals(1050, sum);
        assertTrue(sum < 1200, "перебор над тысячей должен оставаться небольшим");
    }
}
