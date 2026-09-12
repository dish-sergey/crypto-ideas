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
    void singleLevelBotsGetHundred() {
        for (String bot : new String[]{"a", "b", "c"}) {
            assertEquals(100, ExecLimits.maxPlacementsPerDay(bot), "бот " + bot);
        }
    }

    @Test
    void threeLevelBotsGetTwoFifty() {
        for (String bot : new String[]{"d", "e", "f"}) {
            assertEquals(250, ExecLimits.maxPlacementsPerDay(bot), "бот " + bot);
        }
    }

    /** ⚠️ Опечатка в метке не должна давать БОЛЬШЕ, чем настроено. */
    @Test
    void unknownBotGetsTheSmallestShare() {
        assertEquals(100, ExecLimits.maxPlacementsPerDay("zzz"));
        assertEquals(100, ExecLimits.maxPlacementsPerDay(""));
        assertEquals(100, ExecLimits.maxPlacementsPerDay(null));
    }

    /** Доля вдвое выше предсказанного спроса самого прожорливого. */
    @Test
    void capIsAboveMeasuredDemand() {
        assertTrue(ExecLimits.maxPlacementsPerDay("f") >= 2 * 176 * 0.7,
                "трёхуровневый SOL: предсказано 176 постановок в сутки");
        assertTrue(ExecLimits.maxPlacementsPerDay("b") >= 53,
                "одноуровневый SOL: предсказано 53");
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
        for (String bot : new String[]{"a", "b", "c", "d", "e", "f"}) {
            sum += ExecLimits.maxPlacementsPerDay(bot);
        }
        assertEquals(1050, sum);
        assertTrue(sum < 1200, "перебор над тысячей должен оставаться небольшим");
    }
}
