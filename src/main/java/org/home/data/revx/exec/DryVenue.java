package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ПЛОЩАДКА БЕЗ ЗАЯВОК — для сухого прогона нового бота на живых ценах ({@code --revx-dry-bot}).
 *
 * Ни одного запроса к Revolut X: постановка, замена и отмена отвечают так, как ответила бы
 * площадка (те же формы JSON, что у {@link org.home.data.revx.replay.SimVenue}), заявки
 * живут только здесь и никогда не исполняются. Остатки — выдуманные: касса {@code cash}
 * в валюте котировки, монеты ноль. Проверяется механика: как часто бот будит тик,
 * сколько замен и отмен шлёт, как уходит на рывке и при молчании Бинанса.
 */
public final class DryVenue implements Venue {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Order(String id, String clientId, String symbol, boolean buy, double price, double size,
                         long createdMs) {
    }

    private final String base;
    private final String quote;
    private final double cash;
    private final double coins;
    private final Map<String, Order> live = new LinkedHashMap<>();
    private final Map<String, String> gone = new LinkedHashMap<>();
    private long places;
    private long replaces;
    private long cancels;

    public DryVenue(String symbol, double cash) {
        this(symbol, cash, 0);
    }

    /** @param coins монеты на выдуманном счёте (тест продажи). */
    public DryVenue(String symbol, double cash, double coins) {
        this.base = symbol.substring(0, symbol.indexOf('/'));
        this.quote = symbol.substring(symbol.indexOf('/') + 1);
        this.cash = cash;
        this.coins = coins;
    }

    /** Стоящие заявки: «buy@цена» / «sell@цена» (тест). */
    public synchronized java.util.List<String> live() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Order o : live.values()) {
            out.add((o.buy ? "buy@" : "sell@") + plain(o.price));
        }
        return out;
    }

    public synchronized String stats() {
        return String.format(Locale.ROOT, "постановок %d, замен %d, отмен %d, стоит %d", places, replaces, cancels,
                live.size());
    }

    @Override
    public synchronized Response activeOrders() {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        boolean first = true;
        for (Order o : live.values()) {
            sb.append(first ? "" : ",");
            first = false;
            sb.append(String.format(Locale.ROOT,
                    "{\"id\":\"%s\",\"client_order_id\":\"%s\",\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"limit\","
                            + "\"quantity\":\"%s\",\"filled_quantity\":\"0\",\"leaves_quantity\":\"%s\","
                            + "\"price\":\"%s\",\"status\":\"new\",\"created_date\":%d}",
                    o.id, o.clientId, o.symbol, o.buy ? "buy" : "sell", plain(o.size), plain(o.size),
                    plain(o.price), o.createdMs));
        }
        return new Response(200, sb.append("]}").toString(), 0);
    }

    @Override
    public synchronized Response balances() {
        double reservedQuote = 0;
        double reservedBase = 0;
        for (Order o : live.values()) {
            if (o.buy) {
                reservedQuote += o.size * o.price;
            } else {
                reservedBase += o.size;
            }
        }
        String body = "{\"data\":[" + balance(base, coins, reservedBase) + "," + balance(quote, cash, reservedQuote) + "]}";
        return new Response(200, body, 0);
    }

    private static String balance(String currency, double total, double reserved) {
        return String.format(Locale.ROOT,
                "{\"currency\":\"%s\",\"available\":\"%s\",\"reserved\":\"%s\",\"total\":\"%s\"}",
                currency, plain(Math.max(0, total - reserved)), plain(reserved), plain(total));
    }

    @Override
    public synchronized Response order(String id) {
        Order o = live.get(id);
        String status = o != null ? "new" : gone.get(id);
        if (status == null) {
            return new Response(404, "{\"message\":\"Order not found\"}", 0);
        }
        return new Response(200, String.format(Locale.ROOT,
                "{\"data\":{\"id\":\"%s\",\"status\":\"%s\",\"filled_quantity\":\"0\",\"average_fill_price\":\"0\","
                        + "\"total_fee\":\"0\",\"fee_currency\":\"%s\",\"price\":\"%s\",\"quantity\":\"%s\"}}",
                id, status, quote, plain(o != null ? o.price : 0), plain(o != null ? o.size : 0)), 0);
    }

    @Override
    public synchronized Response place(String json) {
        JsonNode n = read(json);
        if (n == null) {
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        JsonNode limit = n.path("order_configuration").path("limit");
        Order o = new Order(UUID.randomUUID().toString(), n.path("client_order_id").asText(null),
                n.path("symbol").asText(null), "buy".equalsIgnoreCase(n.path("side").asText("")),
                limit.path("price").asDouble(), limit.path("base_size").asDouble(), System.currentTimeMillis());
        live.put(o.id, o);
        places++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public synchronized Response replace(String id, String json) {
        Order old = live.remove(id);
        JsonNode n = read(json);
        if (old == null || n == null) {
            return new Response(404, "{\"message\":\"Order not found\"}", 0);
        }
        gone.put(id, "cancelled");
        JsonNode limit = n.path("order_configuration").path("limit");
        double price = limit.path("price").asDouble(old.price);
        double size = limit.path("base_size").asDouble(old.size);
        Order o = new Order(UUID.randomUUID().toString(), n.path("client_order_id").asText(old.clientId),
                old.symbol, old.buy, price > 0 ? price : old.price, size > 0 ? size : old.size,
                System.currentTimeMillis());
        live.put(o.id, o);
        replaces++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public synchronized Response cancel(String id) {
        Order o = live.remove(id);
        cancels++;
        if (o == null) {
            return new Response(404, "{\"message\":\"Order not found\"}", 0);
        }
        gone.put(id, "cancelled");
        return new Response(204, null, 0);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
