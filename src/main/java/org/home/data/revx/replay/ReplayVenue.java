package org.home.data.revx.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.exec.Clock;
import org.home.data.revx.exec.Venue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Площадка, отвечающая по ЗАПИСИ живого бота.
 *
 * <h2>Что это и чем не является</h2>
 *
 * Это вторая реализация {@link Venue}. Первая, {@link
 * org.home.data.revx.exec.TradeClient}, шлёт запросы на Revolut X; эта отвечает
 * на те же запросы теми же формами JSON, а исполнения берёт не из модели, а из
 * журнала: что случилось у живого бота, то случается и здесь.
 *
 * Поэтому сверка получается ЗАМКНУТОЙ. При одинаковых входах — та же цена, те же
 * исполнения — котировщик обязан выдать те же котировки до последнего знака.
 * Любое расхождение здесь означает поломку в самом повторе (часы, разбор,
 * состояние), а не спор моделей. Это и есть первая ступень: убедиться, что стенд
 * умеет воспроизвести живого, прежде чем что-то на нём прогнозировать.
 *
 * ⚠️ Предсказанием исполнений эта площадка НЕ занимается. Модель исполнения
 * ({@code sim.ExecutionModel}) подключается второй ступенью и меряется против
 * этих же записанных исполнений — там стопроцентного совпадения не будет и быть
 * не может.
 *
 * <h2>Почему обмен строками JSON</h2>
 *
 * Чтобы повтор гонял ТОТ ЖЕ разбор, что и живой бот. Самые дорогие ошибки жили
 * именно в разборе: имя поля с идентификатором различается между ответами
 * ({@code venue_order_id} против {@code id}), замена возвращает новый
 * идентификатор, 422 не означает, что замены не было. Отдай мы типизированные
 * объекты — проверяли бы не бота, а свою модель бота.
 */
public final class ReplayVenue implements Venue {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Записанное исполнение: то, что на самом деле произошло у живого бота. */
    public record RecordedFill(long tsMs, boolean buy, double qty, double price) {
    }

    private static final class Order {
        String id;
        String clientId;
        String symbol;
        boolean buy;
        double price;
        double size;
        long createdMs;
    }

    private final Clock clock;
    private final List<RecordedFill> fills;
    private final String base;
    private final String quote;

    private final Map<String, Order> live = new LinkedHashMap<>();
    /** Что по какой заявке исполнилось: {объём, оборот}. Источник filled_quantity. */
    private final Map<String, double[]> done = new LinkedHashMap<>();
    private double baseTotal;
    private double quoteTotal;
    private int fillCursor;

    private long placements;
    private long replaces;
    private long cancels;
    private long applied;
    private long unattributed;
    private long replaceRejects;

    public ReplayVenue(Clock clock, List<RecordedFill> fills, String symbol,
                       double baseStart, double quoteStart) {
        this.clock = clock;
        this.fills = fills;
        this.base = symbol.substring(0, symbol.indexOf('/'));
        this.quote = symbol.substring(symbol.indexOf('/') + 1);
        this.baseTotal = baseStart;
        this.quoteTotal = quoteStart;
    }

    public long placements() {
        return placements;
    }

    public long replaces() {
        return replaces;
    }

    public long cancels() {
        return cancels;
    }

    /** Замены, отклонённые как «заявки уже нет»: у живого их 1.5% (док. 151). */
    public long replaceRejects() {
        return replaceRejects;
    }

    public long appliedFills() {
        return applied;
    }

    /**
     * Записанные исполнения, которые НЕ на что было положить.
     *
     * Каждое такое — расхождение повтора с живым: у бота в этот момент стояла
     * заявка, а у нас нет. Молча пропускать их нельзя, это и есть измеряемая
     * ошибка воспроизведения.
     */
    public long unattributedFills() {
        return unattributed;
    }

    /**
     * Догнать записанные исполнения до текущего момента часов.
     *
     * Вызывается перед КАЖДЫМ ответом: живой бот узнаёт об исполнении из
     * остатков и из списка активных, и порядок «сначала событие, потом ответ»
     * обязан совпадать.
     */
    private void advance() {
        long now = clock.now();
        while (fillCursor < fills.size() && fills.get(fillCursor).tsMs() <= now) {
            apply(fills.get(fillCursor++));
        }
    }

    private void apply(RecordedFill f) {
        Order hit = null;
        for (Order o : live.values()) {
            if (o.buy == f.buy()) {
                hit = o;
                break;
            }
        }
        if (hit == null) {
            unattributed++;
            return;
        }
        applied++;
        if (f.buy()) {
            baseTotal += f.qty();
            quoteTotal -= f.qty() * f.price();
        } else {
            baseTotal -= f.qty();
            quoteTotal += f.qty() * f.price();
        }
        // ⚠️ Исполнение надо ЗАПОМНИТЬ за заявкой. Бот узнаёт о нём не из
        // остатков, а из GET /orders/{id} по полю filled_quantity: при двух
        // ботах на счёте остатки содержат чужие сделки, и других источников у
        // него нет. Площадка, отвечающая на исчезнувшую заявку нулём, оставляет
        // бота с нулевым инвентарём навсегда — первый же прогон разошёлся с
        // живым ровно здесь, на 383-м тике.
        double[] acc = done.computeIfAbsent(hit.id, k -> new double[2]);
        acc[0] += f.qty();
        acc[1] += f.qty() * f.price();
        hit.size -= f.qty();
        if (hit.size <= 1e-12) {
            live.remove(hit.id);
        }
    }

    @Override
    public Response activeOrders() {
        advance();
        StringBuilder sb = new StringBuilder("{\"data\":[");
        boolean first = true;
        for (Order o : live.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(String.format(Locale.ROOT,
                    "{\"id\":\"%s\",\"client_order_id\":\"%s\",\"symbol\":\"%s\","
                            + "\"side\":\"%s\",\"type\":\"limit\",\"quantity\":\"%s\","
                            + "\"leaves_quantity\":\"%s\",\"price\":\"%s\","
                            + "\"status\":\"new\",\"created_date\":%d}",
                    o.id, o.clientId, o.symbol, o.buy ? "buy" : "sell",
                    plain(o.size), plain(o.size), plain(o.price), o.createdMs));
        }
        return new Response(200, sb.append("]}").toString(), 0);
    }

    @Override
    public Response balances() {
        advance();
        // ⚠️ Форма обязана совпадать с живой до символа: QuoteLoop разбирает её
        // жёсткой регуляркой без пробелов. available = total минус то, что
        // зарезервировано под нашими же стоящими заявками.
        double baseReserved = 0;
        double quoteReserved = 0;
        for (Order o : live.values()) {
            if (o.buy) {
                quoteReserved += o.size * o.price;
            } else {
                baseReserved += o.size;
            }
        }
        String body = "{\"data\":["
                + balance(base, baseTotal, baseReserved) + ","
                + balance(quote, quoteTotal, quoteReserved) + "]}";
        return new Response(200, body, 0);
    }

    private static String balance(String currency, double total, double reserved) {
        double avail = Math.max(0, total - reserved);
        return String.format(Locale.ROOT,
                "{\"currency\":\"%s\",\"available\":\"%s\",\"reserved\":\"%s\",\"total\":\"%s\"}",
                currency, plain(avail), plain(Math.max(0, reserved)), plain(Math.max(0, total)));
    }

    @Override
    public Response order(String id) {
        advance();
        double[] acc = done.get(id);
        double filled = acc == null ? 0 : acc[0];
        double avg = filled > 0 ? acc[1] / filled : 0;
        Order o = live.get(id);
        String status = o != null ? "new" : (filled > 0 ? "filled" : "cancelled");
        return new Response(200, String.format(Locale.ROOT,
                "{\"data\":{\"id\":\"%s\",\"status\":\"%s\",\"filled_quantity\":\"%s\","
                        + "\"average_fill_price\":\"%s\",\"total_fee\":\"0\","
                        + "\"fee_currency\":\"%s\",\"price\":\"%s\",\"quantity\":\"%s\"}}",
                id, status, plain(filled), plain(avg), quote,
                plain(o != null ? o.price : avg), plain(o != null ? o.size : filled)), 0);
    }

    @Override
    public Response place(String json) {
        advance();
        JsonNode n = read(json);
        if (n == null) {
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        JsonNode limit = n.path("order_configuration").path("limit");
        Order o = new Order();
        o.id = UUID.randomUUID().toString();
        o.clientId = n.path("client_order_id").asText(null);
        o.symbol = n.path("symbol").asText(null);
        o.buy = "buy".equalsIgnoreCase(n.path("side").asText(""));
        o.price = limit.path("price").asDouble();
        o.size = limit.path("base_size").asDouble();
        o.createdMs = clock.now();
        live.put(o.id, o);
        placements++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public Response replace(String id, String json) {
        advance();
        Order old = live.remove(id);
        if (old == null) {
            // Та самая 422 из док. 111. Проверено зондом 04.09.2026: наследника
            // площадка при этом НЕ создаёт, книга не растёт.
            replaceRejects++;
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}", 0);
        }
        JsonNode n = read(json);
        if (n == null) {
            live.put(id, old);
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        Order o = new Order();
        // ⚠️ Замена создаёт ДРУГУЮ заявку с новым идентификатором — именно это
        // поведение площадки ломало учёт, и повтор обязан его повторять.
        o.id = UUID.randomUUID().toString();
        o.clientId = n.path("client_order_id").asText(old.clientId);
        o.symbol = old.symbol;
        o.buy = old.buy;
        o.price = n.path("price").asDouble(old.price);
        o.size = n.path("base_size").asDouble(old.size);
        o.createdMs = clock.now();
        live.put(o.id, o);
        replaces++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public Response cancel(String id) {
        advance();
        cancels++;
        return live.remove(id) == null
                ? new Response(404, "{\"message\":\"not found\"}", 0)
                : new Response(204, "", 0);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Число без экспоненты и без минуса.
     *
     * Регулярка остатков в {@link org.home.data.revx.exec.QuoteLoop} принимает
     * только {@code [0-9.]+}: {@code 1.25E-5} или отрицательное значение она
     * молча не распознает, и бот решит, что остатка нет вовсе.
     */
    private static String plain(double v) {
        return BigDecimal.valueOf(Math.max(0, v))
                .setScale(12, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Заявки, оставшиеся в книге на конец прогона. */
    public List<String> openIds() {
        return new ArrayList<>(live.keySet());
    }
}
