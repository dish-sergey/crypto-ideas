package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-order-probe}: проверка полного жизненного цикла заявки на
 * ЗАВЕДОМО НЕИСПОЛНИМОЙ цене — постановка, замена, отмена.
 *
 * Формат тела взят из документации площадки (developer.revolut.com/docs/x-api),
 * а не подобран перебором:
 *
 * <pre>
 * POST   /api/1.0/orders                 {client_order_id, symbol, side,
 *                                         order_configuration:{limit:{base_size,
 *                                         price, execution_instructions}}}
 * PUT    /api/1.0/orders/{venue_order_id} {client_order_id, base_size, price,
 *                                          execution_instructions}
 * DELETE /api/1.0/orders/{venue_order_id} без тела, ответ 204 без содержимого
 * </pre>
 *
 * Оттуда же — ответ на вопрос, который в ТЗ §6 стоял открытым: **`post_only`
 * существует** и передаётся как {@code execution_instructions: ["post_only"]}.
 * Значит ценовой предохранитель в исполнителе не нужен, достаточно флага. Но
 * документация говорит, что флаг есть; ведёт ли он себя как обещано, показывает
 * только живая заявка — за этим зонд и нужен.
 *
 * Две вещи, которые видно только здесь и которые важны для цикла котирования:
 * замена требует **нового** {@code client_order_id} каждый раз, и она же
 * возвращает новый {@code venue_order_id} — то есть состояние заявки надо
 * перечитывать из ответа, а не помнить.
 *
 * Безопасность: цена покупки 1 USDC за BTC неисполнима при любом рынке —
 * лимитная покупка сводится по цене не выше указанной. Размер минимальный.
 * Отмена стоит в {@code finally} и срабатывает даже при падении посередине.
 */
@Component
@Lazy
public class OrderProbe {

    private static final Logger log = LoggerFactory.getLogger(OrderProbe.class);

    private static final Pattern VENUE_ID =
            Pattern.compile("\"venue_order_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ANY_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** Цена, по которой биткойн не продаст никто. Это и есть предохранитель. */
    private static final String UNFILLABLE_PRICE = "1.00";
    private static final String NEXT_PRICE = "1.10";
    private static final String SIZE = "0.1";

    private final RevxConfig cfg;

    public OrderProbe(RevxConfig cfg) {
        this.cfg = cfg;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal("state/exec.db")) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);
            journal.event("order_probe", "начало: покупка по " + UNFILLABLE_PRICE
                    + " USDC за BTC, размер " + SIZE + ", post_only");

            StringBuilder report = new StringBuilder(
                    "\n=== Жизненный цикл заявки (цена неисполнима) ===\n");
            String venueId = null;
            try {
                String placeBody = """
                        {"client_order_id":"%s","symbol":"BTC-USDC","side":"buy",
                         "order_configuration":{"limit":{"base_size":"%s","price":"%s",
                         "execution_instructions":["post_only"]}}}"""
                        .formatted(UUID.randomUUID(), SIZE, UNFILLABLE_PRICE)
                        .replaceAll("\\s*\\n\\s*", "");

                TradeClient.Response placed = client.place(placeBody);
                report.append(line("POST /orders", placed));
                if (!placed.ok()) {
                    report.append("\nПостановка не прошла — дальше идти незачем.\n");
                    log.info(report.toString());
                    return;
                }
                venueId = extract(placed.body());
                report.append("    venue_order_id = ").append(venueId).append('\n');

                Thread.sleep(500);
                report.append(line("GET /orders/active", client.activeOrders()));

                Thread.sleep(500);
                String replaceBody = """
                        {"client_order_id":"%s","base_size":"%s","price":"%s",
                         "execution_instructions":["post_only"]}"""
                        .formatted(UUID.randomUUID(), SIZE, NEXT_PRICE)
                        .replaceAll("\\s*\\n\\s*", "");
                TradeClient.Response replaced = client.replace(venueId, replaceBody);
                report.append(line("PUT /orders/{id} (цена → " + NEXT_PRICE + ")", replaced));
                if (replaced.ok()) {
                    String newId = extract(replaced.body());
                    if (newId != null && !newId.equals(venueId)) {
                        report.append("    ВНИМАНИЕ: замена вернула НОВЫЙ id ").append(newId)
                                .append(" — состояние заявки надо перечитывать из ответа\n");
                        venueId = newId;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                report.append(cleanup(client, journal));
            }
            log.info(report.toString());
            journal.event("order_probe", "завершено");
        }
    }

    private static String line(String what, TradeClient.Response response) {
        String body = response.body() == null || response.body().isBlank()
                ? "(без тела)" : response.body().replaceAll("\\s+", " ").trim();
        return String.format("%-36s → %d за %d мс  %s%n", what, response.status(),
                response.latencyMs(), body.length() > 300 ? body.substring(0, 300) + "…" : body);
    }

    private static String extract(String body) {
        Matcher venue = VENUE_ID.matcher(body == null ? "" : body);
        if (venue.find()) {
            return venue.group(1);
        }
        Matcher any = ANY_ID.matcher(body == null ? "" : body);
        return any.find() ? any.group(1) : null;
    }

    /** Снимает всё активное. Выполняется всегда — в том числе при падении зонда. */
    private String cleanup(TradeClient client, ExecJournal journal) {
        TradeClient.Response active = client.activeOrders();
        if (active.body() == null || !active.body().contains("order_id")) {
            return "\nАктивных заявок не осталось.\n";
        }
        StringBuilder out = new StringBuilder("\nУборка:\n");
        Matcher matcher = VENUE_ID.matcher(active.body());
        boolean any = false;
        while (matcher.find()) {
            any = true;
            TradeClient.Response cancelled = client.cancel(matcher.group(1));
            out.append("  DELETE ").append(matcher.group(1)).append(" → ")
                    .append(cancelled.status()).append('\n');
            journal.event("probe_cancel", matcher.group(1) + " → " + cancelled.status());
        }
        return any ? out.toString() : "\nАктивных заявок не осталось.\n";
    }
}
