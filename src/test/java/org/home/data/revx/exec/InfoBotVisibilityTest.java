package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Показ ботов в сводках: {@code /hide} и {@code /show}.
 *
 * Сводка — прибор, и у прибора опаснее всего не поломка, а правдоподобное
 * молчание. Поэтому проверяется не только «скрылось», но и три отказа: чужая
 * метка, скрытие всех и то, что скрытие не переживает перезапуск (в памяти).
 */
class InfoBotVisibilityTest {

    private static InfoBot bot() {
        return new InfoBot("токен", 1, List.of(
                new InfoBot.Watched("a", "BTC/USDC", "нет"),
                new InfoBot.Watched("b", "SOL/USDC", "нет"),
                new InfoBot.Watched("c", "ETH/USDC", "нет"),
                new InfoBot.Watched("d", "ADA/USDC", "нет")));
    }

    private static List<String> ids(InfoBot bot) {
        return bot.visible().stream().map(InfoBot.Watched::botId).toList();
    }

    @Test
    void hidesAndRestores() {
        InfoBot bot = bot();
        assertEquals(List.of("a", "b", "c", "d"), ids(bot));

        bot.visibility(new String[]{"d"}, true);
        assertEquals(List.of("a", "b", "c"), ids(bot));

        bot.visibility(new String[]{"b", "c"}, true);
        assertEquals(List.of("a"), ids(bot));

        bot.visibility(new String[]{"c"}, false);
        assertEquals(List.of("a", "c"), ids(bot));

        bot.visibility(new String[]{"all"}, false);
        assertEquals(List.of("a", "b", "c", "d"), ids(bot));
    }

    /** Метка ботом не является — сообщаем и НЕ трогаем показ. */
    @Test
    void unknownLabelChangesNothing() {
        InfoBot bot = bot();
        String answer = bot.visibility(new String[]{"z"}, true);
        assertTrue(answer.contains("Не знаю таких"), answer);
        assertTrue(answer.contains("z"), answer);
        assertEquals(List.of("a", "b", "c", "d"), ids(bot));
    }

    /** Одна верная метка вместе с одной чужой не проходит целиком: полумеры хуже отказа. */
    @Test
    void partiallyUnknownIsRejectedWhole() {
        InfoBot bot = bot();
        bot.visibility(new String[]{"a", "z"}, true);
        assertEquals(List.of("a", "b", "c", "d"), ids(bot));
    }

    /**
     * ⚠️ Скрыть всех нельзя. Пустая сводка выглядит как «всё тихо» при любом
     * происходящем — это не настройка, а сломанный прибор.
     */
    @Test
    void refusesToHideEveryone() {
        InfoBot bot = bot();
        bot.visibility(new String[]{"a", "b", "c"}, true);
        String answer = bot.visibility(new String[]{"d"}, true);
        assertTrue(answer.contains("скроются все"), answer);
        assertEquals(List.of("d"), ids(bot));
    }

    /** Регистр метки не важен: в чате пишут и «D», и «d». */
    @Test
    void labelCaseDoesNotMatter() {
        InfoBot bot = bot();
        bot.visibility(new String[]{"D"}, true);
        assertEquals(List.of("a", "b", "c"), ids(bot));
        bot.visibility(new String[]{"d"}, false);
        assertEquals(List.of("a", "b", "c", "d"), ids(bot));
    }

    /** Расклад показывается и без меток — обеими командами одинаково. */
    @Test
    void listsStateWithoutLabels() {
        InfoBot bot = bot();
        bot.visibility(new String[]{"d"}, true);
        String byHide = bot.visibility(new String[0], true);
        String byShow = bot.visibility(new String[0], false);
        assertEquals(byHide, byShow);
        assertTrue(byHide.contains("D ADA/USDC — скрыт"), byHide);
        assertTrue(byHide.contains("✅ A BTC/USDC"), byHide);
    }
}
