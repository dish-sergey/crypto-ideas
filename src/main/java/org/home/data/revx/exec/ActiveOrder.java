package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Одна открытая заявка из ответа {@code GET /api/1.0/orders/active}.
 *
 * Разбирается Jackson'ом, а не регулярным выражением. Список заявок — массив
 * объектов, и вытаскивание полей одним шаблоном по всему телу склеивает поля
 * РАЗНЫХ заявок: цена одной с идентификатором другой. На одной заявке это
 * незаметно, на двух — уже нет.
 *
 * ⚠️ Идентификатор здесь называется {@code id}, а постановка и замена отдают
 * его же под именем {@code venue_order_id}. Оба имени читаются (см. {@link Panic}
 * — там на этом однажды сломалась уборка).
 *
 * ⚠️ Символ в ответах пишется через дробь ({@code BTC/USDC}), а в теле
 * постановки — через дефис ({@code BTC-USDC}). Сравнивать их можно только
 * после приведения.
 */
public record ActiveOrder(String id, String symbol, Side side, double price,
                          double size, long createdMs) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<ActiveOrder> parse(String json) {
        List<ActiveOrder> orders = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return orders;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return orders;                // мусор вместо ответа — считаем, что не знаем
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            data = root;
        }
        if (data.isObject()) {
            ActiveOrder one = one(data);
            if (one != null) {
                orders.add(one);
            }
            return orders;
        }
        for (JsonNode node : data) {
            ActiveOrder order = one(node);
            if (order != null) {
                orders.add(order);
            }
        }
        return orders;
    }

    private static ActiveOrder one(JsonNode node) {
        String id = text(node, "id", "venue_order_id");
        String side = text(node, "side");
        if (id == null || side == null) {
            return null;
        }
        // leaves_quantity — то, что ещё стоит в книге; на частично исполненной
        // заявке это не то же самое, что quantity, и резерв держится именно им.
        double size = number(node, "leaves_quantity", "quantity", "base_size");
        return new ActiveOrder(id,
                normalize(text(node, "symbol")),
                "sell".equalsIgnoreCase(side) ? Side.SELL : Side.BUY,
                number(node, "price"),
                size,
                (long) number(node, "created_date", "created_at"));
    }

    /** {@code BTC-USDC} и {@code BTC/USDC} — одна и та же пара. */
    public static String normalize(String symbol) {
        return symbol == null ? null : symbol.replace('-', '/').toUpperCase();
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static double number(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            // Площадка отдаёт числа строками; asDouble на строке даёт 0.
            try {
                return Double.parseDouble(value.asText());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
