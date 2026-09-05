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
 * Площадка стенда: вторая реализация {@link Venue}.
 *
 * <h2>Что здесь и чего здесь нет</h2>
 *
 * Здесь — весь учёт: заявки, остатки, резервы под стоящими заявками, семантика
 * замены (новый идентификатор), 422 на мёртвый идентификатор, {@code
 * filled_quantity} по исполнившейся заявке. Всё это проверено повтором живого
 * журнала: при верных исполнениях котировки сходятся с живым на 99.92%
 * (61 542 тика из 61 592, бот A, 17 часов).
 *
 * Здесь НЕТ решения, исполнилась ли заявка. Это единственная неизвестная, и она
 * вынесена в {@link FillModel} — чтобы любое расхождение стенда с реальностью
 * относилось к ней одной, а не размазывалось по десятку подозреваемых.
 *
 * <h2>Почему обмен строками JSON</h2>
 *
 * Чтобы стенд гонял ТОТ ЖЕ разбор, что и живой бот. Самые дорогие ошибки жили
 * именно в разборе: имя поля с идентификатором различается между ответами
 * ({@code venue_order_id} против {@code id}), замена возвращает новый
 * идентификатор, 422 не означает, что замены не было. Отдай мы типизированные
 * объекты — проверяли бы не бота, а свою модель бота.
 */
public final class SimVenue implements Venue {

    // ⚠️ Все точки входа synchronized: котировщиков в прогнозе несколько, каждый
    // в своём потоке, а книга заявок и остатки здесь ОДНИ. Без этого гонка за
    // объёмом сделки делала прогон невоспроизводимым (см. SimClock: очередь хода).

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private final FillModel model;
    private final String base;
    private final String quote;

    private final Map<String, Order> live = new LinkedHashMap<>();
    /** Что по какой заявке исполнилось: {объём, оборот}. Источник filled_quantity. */
    private final Map<String, double[]> done = new LinkedHashMap<>();
    private double baseTotal;
    private double quoteTotal;

    private long placements;
    private long replaces;
    private long cancels;
    private long applied;
    private long replaceRejects;
    /** Сколько раз площадку спросили и сколько из них заявка на стороне СТОЯЛА. */
    private long probes;
    private long bidPresent;
    private long askPresent;

    public String presence() {
        return probes == 0 ? "нет данных"
                : String.format(java.util.Locale.ROOT, "бид в книге %.1f%%, аск %.1f%%",
                        100.0 * bidPresent / probes, 100.0 * askPresent / probes);
    }

    public SimVenue(Clock clock, FillModel model, String symbol,
                    double baseStart, double quoteStart) {
        this.clock = clock;
        this.model = model;
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

    public FillModel model() {
        return model;
    }

    /**
     * Догнать исполнения до текущего момента часов.
     *
     * Вызывается перед КАЖДЫМ ответом: бот узнаёт об исполнении из списка
     * активных и из {@code GET /orders/{id}}, и порядок «сначала событие, потом
     * ответ» обязан совпадать с живым.
     */
    private void advance() {
        probes++;
        for (Order o : live.values()) {
            if (o.buy) {
                bidPresent++;
            } else {
                askPresent++;
            }
        }
        List<FillModel.Resting> resting = new ArrayList<>();
        for (Order o : live.values()) {
            resting.add(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        }
        for (FillModel.Filled f : model.advance(clock.now(), resting)) {
            apply(f);
        }
    }

    private void apply(FillModel.Filled f) {
        Order hit = live.get(f.orderId());
        if (hit == null) {
            return;
        }
        applied++;
        double qty = Math.min(f.qty(), hit.size);
        if (hit.buy) {
            baseTotal += qty;
            quoteTotal -= qty * f.price();
        } else {
            baseTotal -= qty;
            quoteTotal += qty * f.price();
        }
        // ⚠️ Исполнение надо ЗАПОМНИТЬ за заявкой. Бот узнаёт о нём не из
        // остатков, а из GET /orders/{id} по полю filled_quantity: при трёх
        // ботах на счёте остатки содержат чужие сделки, и других источников у
        // него нет. Площадка, отвечающая на исчезнувшую заявку нулём, оставляет
        // бота с нулевым инвентарём навсегда — первый прогон разошёлся с живым
        // ровно здесь, на 383-м тике.
        double[] acc = done.computeIfAbsent(hit.id, k -> new double[2]);
        acc[0] += qty;
        acc[1] += qty * f.price();
        hit.size -= qty;
        if (hit.size <= 1e-12) {
            live.remove(hit.id);
            model.cancelled(hit.id);
        }
    }

    @Override
    public synchronized Response activeOrders() {
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
    public synchronized Response balances() {
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
    public synchronized Response order(String id) {
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
    public synchronized Response place(String json) {
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
        model.placed(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        placements++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public synchronized Response replace(String id, String json) {
        advance();
        Order old = live.remove(id);
        if (old == null) {
            // Та самая 422 из док. 111. Проверено зондом 04.09.2026: наследника
            // площадка при этом НЕ создаёт, книга не растёт.
            replaceRejects++;
            return new Response(422,
                    "{\"message\":\"Cannot replace an order that is not in the 'NEW' state\"}", 0);
        }
        model.cancelled(id);
        JsonNode n = read(json);
        if (n == null) {
            live.put(id, old);
            return new Response(400, "{\"message\":\"bad body\"}", 0);
        }
        Order o = new Order();
        // ⚠️ Замена создаёт ДРУГУЮ заявку с новым идентификатором — именно это
        // поведение площадки ломало учёт, и стенд обязан его повторять. Для
        // модели очереди это тоже принципиально: наследник встаёт в КОНЕЦ
        // очереди, приоритета предшественника он не наследует.
        o.id = UUID.randomUUID().toString();
        o.clientId = n.path("client_order_id").asText(old.clientId);
        o.symbol = old.symbol;
        o.buy = old.buy;
        o.price = n.path("price").asDouble(old.price);
        o.size = n.path("base_size").asDouble(old.size);
        o.createdMs = clock.now();
        live.put(o.id, o);
        model.placed(new FillModel.Resting(o.id, o.buy, o.price, o.size, o.createdMs));
        replaces++;
        return new Response(200, String.format(
                "{\"data\":{\"venue_order_id\":\"%s\",\"client_order_id\":\"%s\",\"state\":\"new\"}}",
                o.id, o.clientId), 0);
    }

    @Override
    public synchronized Response cancel(String id) {
        advance();
        cancels++;
        if (live.remove(id) == null) {
            return new Response(404, "{\"message\":\"not found\"}", 0);
        }
        model.cancelled(id);
        return new Response(204, "", 0);
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
