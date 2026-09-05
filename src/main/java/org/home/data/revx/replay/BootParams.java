package org.home.data.revx.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Настройки бота, записанные в событии {@code boot} того же журнала.
 *
 * <h2>Зачем</h2>
 *
 * Повтор брал параметры из {@code application.properties}, а живому боту
 * половина приходит из systemd-юнита: {@code skew-target} 0.3 против 0.0,
 * {@code park-distance} 0.10 против −1. Из-за нулевого скоса повтор котировал
 * чистый отступ 10 б.п. там, где живой стоял на 5.7, и «сверка один в один»
 * сравнивала не логику бота, а две разные настройки — ровно та беда, ради
 * которой всё это и затевалось.
 *
 * Теперь настройки едут ВМЕСТЕ с записью. Подобрать их к журналу задним числом
 * больше нельзя и не нужно.
 *
 * ⚠️ Записи старше 05.09.2026 машинной части не содержат. Для них
 * {@link #parse} возвращает {@code null}, и повтор обязан об этом громко
 * сказать, а не тихо взять окружение: молчаливая подстановка — это и есть
 * исходная ошибка.
 */
public record BootParams(String symbol, String botId, double size, double inventoryCap,
                         double offset, double skewK, double skewTarget, long periodMs,
                         double minNotional, double baseStep, double quoteStep,
                         double parkDistance, double costFloorMargin, double anchorLeash,
                         double widening, double wideningMaxStep, double anchorWidening,
                         boolean ownPosition) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Разобрать {@code detail} события boot. Машинная часть идёт после {@code |}.
     *
     * @return {@code null}, если машинной части нет (старая запись)
     */
    public static BootParams parse(String detail) {
        if (detail == null) {
            return null;
        }
        int bar = detail.indexOf('{');
        if (bar < 0) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(detail.substring(bar));
            if (!n.has("symbol") || !n.has("skewTarget")) {
                return null;
            }
            return new BootParams(
                    n.path("symbol").asText(),
                    n.path("botId").asText("a"),
                    n.path("size").asDouble(),
                    n.path("inventoryCap").asDouble(),
                    n.path("offset").asDouble(),
                    n.path("skewK").asDouble(),
                    n.path("skewTarget").asDouble(),
                    n.path("periodMs").asLong(1000),
                    n.path("minNotional").asDouble(),
                    n.path("baseStep").asDouble(),
                    n.path("quoteStep").asDouble(),
                    n.path("parkDistance").asDouble(-1),
                    n.path("costFloorMargin").asDouble(-1),
                    n.path("anchorLeash").asDouble(-1),
                    n.path("widening").asDouble(),
                    n.path("wideningMaxStep").asDouble(),
                    n.path("anchorWidening").asDouble(),
                    n.path("ownPosition").asBoolean(true));
        } catch (Exception e) {
            return null;
        }
    }

    /** Человеческая часть: то, что печатает {@code /pnl}. */
    public static String human(String detail) {
        if (detail == null) {
            return "";
        }
        int bar = detail.indexOf('|');
        return (bar < 0 ? detail : detail.substring(0, bar)).trim();
    }
}
