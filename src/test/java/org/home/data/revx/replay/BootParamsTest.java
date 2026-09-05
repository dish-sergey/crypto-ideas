package org.home.data.revx.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Настройки повтора берутся ИЗ ЖУРНАЛА, а не из окружения.
 *
 * 05.09.2026 повтор брал их из application.properties, а живому боту половина
 * приходит из systemd-юнита: skew-target 0.3 против 0.0, park-distance 0.10
 * против −1. Из-за нулевого скоса повтор котировал чистый отступ 10 б.п. там,
 * где живой стоял на 5.7, и «сверка один в один» сравнивала не логику бота, а
 * две разные настройки.
 */
class BootParamsTest {

    private static final String REAL = "BTC/USDC, лот 0.0000125, отступ 10.0 б.п., "
            + "скос k=0.0010, цель 30% потолка | "
            + "{\"symbol\":\"BTC/USDC\",\"botId\":\"a\",\"size\":0.0000125,"
            + "\"inventoryCap\":0.00025,\"offset\":0.001,\"skewK\":0.001,"
            + "\"skewTarget\":0.3,\"periodMs\":1000,\"minNotional\":0.1,"
            + "\"baseStep\":0.00000001,\"quoteStep\":0.01,\"parkDistance\":0.1,"
            + "\"costFloorMargin\":-1,\"anchorLeash\":-1,\"widening\":0,"
            + "\"wideningMaxStep\":0.02,\"anchorWidening\":0.5,\"ownPosition\":true}";

    @Test
    void readsTheSettingsThatDifferFromDefaults() {
        BootParams p = BootParams.parse(REAL);
        // Ровно те два, из-за которых сверка врала.
        assertEquals(0.3, p.skewTarget(), 1e-12, "цель скоса взята не из журнала");
        assertEquals(0.1, p.parkDistance(), 1e-12, "отвод заявок взят не из журнала");
        assertEquals(0.001, p.offset(), 1e-12);
        assertEquals(0.0000125, p.size(), 1e-18);
        assertEquals("a", p.botId());
        assertEquals(1000, p.periodMs());
        assertTrue(p.ownPosition());
    }

    @Test
    void oldRecordsGiveNothingRatherThanDefaults() {
        // Записи до 05.09.2026 машинной части не содержат. Молчаливая подстановка
        // окружения — это и есть исходная ошибка, поэтому здесь обязан быть null.
        assertNull(BootParams.parse("BTC/USDC, лот 0.0000125, отступ 10.0 б.п."));
        assertNull(BootParams.parse(null));
        assertNull(BootParams.parse(""));
    }

    @Test
    void brokenJsonIsNotHalfRead() {
        assertNull(BootParams.parse("что-то | {\"symbol\":\"BTC/USDC\","));
        // Есть символ, но нет цели скоса — неполной записи верить нельзя.
        assertNull(BootParams.parse("x | {\"symbol\":\"BTC/USDC\",\"offset\":0.001}"));
    }

    @Test
    void humanPartIsWhatTheChatShows() {
        assertEquals("BTC/USDC, лот 0.0000125, отступ 10.0 б.п., скос k=0.0010, цель 30% потолка",
                BootParams.human(REAL));
        // Старая запись без машинной части остаётся собой целиком.
        assertEquals("BTC/USDC, отступ 10.0 б.п.", BootParams.human("BTC/USDC, отступ 10.0 б.п."));
        assertEquals("", BootParams.human(null));
    }
}
