package org.home.data.revx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор страницы ленты сделок.
 *
 * Формат ответа: {@code {"data":[...],"metadata":{"next_cursor":"..."}}}.
 * limit упирается в 100, поэтому без пагинации по cursor поток занижается —
 * в док. 62 так и вышло (100 сделок за 9 часов вместо реальных за 0.7 часа).
 *
 * Поле {@code side} биржа отдаёт сама ('buy'/'sell'), но что оно означает —
 * сторону агрессора или сторону мейкера — не документировано. Здесь оно
 * сохраняется как есть; сверка с книгой и вывод агрессора — забота симулятора
 * (ТЗ §4.3), иначе непроверенная трактовка попадёт прямо в данные.
 */
public final class TradesParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Trade(String id, String symbol, long tsMs, double price, double qty, String side) {
    }

    public record Page(List<Trade> trades, String nextCursor) {

        public boolean empty() {
            return trades.isEmpty();
        }

        public long oldestTsMs() {
            return trades.stream().mapToLong(Trade::tsMs).min().orElse(Long.MAX_VALUE);
        }

        public long newestTsMs() {
            return trades.stream().mapToLong(Trade::tsMs).max().orElse(Long.MIN_VALUE);
        }
    }

    private TradesParser() {
    }

    public static Page parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("некорректный JSON ленты сделок", e);
        }
        List<Trade> trades = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            // Публичная схема: id/timestamp/price/quantity/side.
            // Авторизованная: tid/tdt/p/q/s (плюс метаданные актива).
            JsonNode id = first(n, "id", "tid");
            if (id == null || id.asText().isBlank()) {
                continue;
            }
            JsonNode ts = first(n, "timestamp", "tdt");
            JsonNode side = first(n, "side", "s");
            trades.add(new Trade(normalizeId(id.asText()),
                    n.path("symbol").asText(""),
                    ts == null ? 0 : ts.asLong(),
                    asDouble(first(n, "price", "p")),
                    asDouble(first(n, "quantity", "q")),
                    side == null ? null : side.asText().toLowerCase()));
        }
        JsonNode cursor = root.path("metadata").path("next_cursor");
        String next = cursor.isTextual() && !cursor.asText().isBlank() ? cursor.asText() : null;
        return new Page(trades, next);
    }

    /**
     * Один и тот же идентификатор сделки приходит в двух видах: публично —
     * UUID с дефисами (`d307f978-621b-3066-a45d-ab58519aeca6`), с ключом — он же
     * без дефисов (`d307f978621b3066a45dab58519aeca6`). Без нормализации одна
     * сделка легла бы в таблицу дважды и поток удвоился бы на границе перехода.
     */
    static String normalizeId(String id) {
        return id.replace("-", "").toLowerCase();
    }

    /** Первое непустое поле из списка имён — публичная схема или авторизованная. */
    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return Double.NaN;
        }
        try {
            return node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
