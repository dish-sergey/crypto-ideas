package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
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
    /** Третья цена — чтобы удар по устаревшему id отличался от предыдущей замены. */
    private static final String ORPHAN_PRICE = "1.20";

    /**
     * Первый символ {@code client_order_id}, которым не помечен ни один бот.
     *
     * ⚠️ {@link BotTag#owns} считает заявку своей по ПЕРВОМУ символу
     * идентификатора, а боты зовутся {@code a}, {@code b}, {@code c}. Случайный
     * UUID начинается с {@code a}, {@code b} или {@code c} с вероятностью 3/16 —
     * и тогда живой бот принял бы заявку зонда за свою: снял бы её как
     * бесхозную либо усыновил как собственную котировку, после чего перевыставил
     * бы её к рынку. Поэтому первый символ прибивается к «ничьему».
     */
    private static final char FREE_PREFIX = 'f';
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
            // ⚠️ Счёт на площадке ОДИН, и на нём котируют живые боты. Зонд обязан
            // отменять ТОЛЬКО свои заявки: прежняя уборка снимала всё активное и
            // на нынешнем счёте снесла бы котировки трёх ботов, заставив их
            // перевыставляться за счёт суточного лимита постановок.
            Set<String> mine = new LinkedHashSet<>();
            String venueId = null;
            try {
                String placeBody = """
                        {"client_order_id":"%s","symbol":"BTC-USDC","side":"buy",
                         "order_configuration":{"limit":{"base_size":"%s","price":"%s",
                         "execution_instructions":["post_only"]}}}"""
                        .formatted(probeClientId(), SIZE, UNFILLABLE_PRICE)
                        .replaceAll("\\s*\\n\\s*", "");

                TradeClient.Response placed = client.place(placeBody);
                report.append(line("POST /orders", placed));
                if (!placed.ok()) {
                    report.append("\nПостановка не прошла — дальше идти незачем.\n");
                    log.info(report.toString());
                    return;
                }
                venueId = extract(placed.body());
                mine.add(venueId);
                report.append("    venue_order_id = ").append(venueId).append('\n');

                Thread.sleep(500);
                report.append(line("GET /orders/active", client.activeOrders()));

                Thread.sleep(500);
                String replaceBody = """
                        {"client_order_id":"%s","base_size":"%s","price":"%s",
                         "execution_instructions":["post_only"]}"""
                        .formatted(probeClientId(), SIZE, NEXT_PRICE)
                        .replaceAll("\\s*\\n\\s*", "");
                TradeClient.Response replaced = client.replace(venueId, replaceBody);
                report.append(line("PUT /orders/{id} (цена → " + NEXT_PRICE + ")", replaced));
                String staleId = venueId;
                if (replaced.ok()) {
                    String newId = extract(replaced.body());
                    if (newId != null && !newId.equals(venueId)) {
                        report.append("    ВНИМАНИЕ: замена вернула НОВЫЙ id ").append(newId)
                                .append(" — состояние заявки надо перечитывать из ответа\n");
                        venueId = newId;
                        mine.add(newId);
                    }
                }

                Thread.sleep(500);
                report.append(orphanStage(client, staleId, venueId, mine));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                report.append(cleanup(client, journal, mine));
            }
            log.info(report.toString());
            journal.event("order_probe", "завершено");
        }
    }

    /**
     * Сцена «сирота»: замена по УСТАРЕВШЕМУ идентификатору.
     *
     * Живой исполнитель 30.08.2026 (док. 111) получил на замену
     * {@code 422 Cannot replace an order that is not in the 'NEW' state}, а
     * заявка при этом уже была {@code cancelled / reject_reason: replaced} — то
     * есть наследник создан, а его идентификатор не пришёл никуда. Заявка
     * осталась в книге без хозяина, её резерв сделал инвентарь неотчуждаемым, и
     * 766 попыток продать подряд выбрали весь суточный лимит постановок.
     *
     * Здесь та же ситуация воспроизводится намеренно и без риска: цена
     * неисполнима, размер минимальный. Мы уже заменили заявку один раз, значит
     * {@code staleId} больше не в состоянии {@code NEW}. Бьём по нему второй раз
     * и смотрим не на код ответа, а на КНИГУ — единственный источник правды.
     */
    private static String orphanStage(TradeClient client, String staleId, String liveId,
                                      Set<String> mine) throws InterruptedException {
        StringBuilder out = new StringBuilder(
                "\n=== Сцена «сирота»: замена по устаревшему id ===\n");
        Set<String> before = activeIds(client.activeOrders().body());
        out.append("До удара активных заявок: ").append(before.size())
                .append(' ').append(before).append('\n');

        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(probeClientId(), SIZE, ORPHAN_PRICE)
                .replaceAll("\\s*\\n\\s*", "");
        TradeClient.Response stale = client.replace(staleId, body);
        out.append(line("PUT /orders/{УСТАРЕВШИЙ id}", stale));

        Thread.sleep(700);
        out.append(line("GET /orders/{УСТАРЕВШИЙ id}", client.order(staleId)));

        Set<String> after = activeIds(client.activeOrders().body());
        out.append("После удара активных заявок: ").append(after.size())
                .append(' ').append(after).append('\n');

        Set<String> born = new LinkedHashSet<>(after);
        born.removeAll(before);
        born.remove(liveId);

        // Родившееся в это окно — наше, даже если идентификатор не пришёл ни в
        // одном ответе. Именно так сирота и попадает в уборку.
        mine.addAll(born);

        if (!stale.ok() && !born.isEmpty()) {
            out.append("\n❗ БАГ ВОСПРОИЗВЁЛСЯ: ответ ").append(stale.status())
                    .append(", а в книге появилась заявка ").append(born)
                    .append(",\n   идентификатор которой НЕ пришёл ни в одном ответе.\n")
                    .append("   Значит судьбу заявки нельзя выводить из кода ответа —\n")
                    .append("   только из GET /orders/active.\n");
        } else if (!stale.ok()) {
            out.append("\n✔ Отказ ").append(stale.status())
                    .append(" оказался честным: новых заявок в книге не появилось.\n")
                    .append("   Значит на ЭТОМ пути сирота не родилась. Живой случай\n")
                    .append("   док. 111 шёл гонкой двух замен подряд, а не по\n")
                    .append("   заведомо мёртвому id — воспроизводить надо её.\n");
        } else {
            out.append("\n⚠️ Замена по устаревшему id ПРОШЛА (").append(stale.status())
                    .append("). Площадка ведёт себя не так, как в док. 111.\n");
        }
        return out.toString();
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

    /**
     * Идентификаторы заявок из ответа {@code GET /orders/active}.
     *
     * ⚠️ Имя поля РАЗНОЕ в разных ответах площадки: постановка и замена отдают
     * {@code venue_order_id}, а список активных — {@code id}. Прежняя уборка
     * искала только {@code venue_order_id}, своей же заявки в списке не находила
     * и бодро печатала «Активных заявок не осталось», пока та висела в книге.
     * Поэтому собираем оба имени.
     *
     * Регулярка {@code "id"} не цепляет {@code "client_order_id"}: там перед
     * {@code id} стоит подчёркивание, а не кавычка.
     */
    /** Идентификатор заявки зонда: валидный UUID, который не присвоит ни один бот. */
    static String probeClientId() {
        String uuid = UUID.randomUUID().toString();
        return FREE_PREFIX + uuid.substring(1);
    }

    static Set<String> activeIds(String body) {
        Set<String> ids = new LinkedHashSet<>();
        if (body == null) {
            return ids;
        }
        for (Pattern p : new Pattern[]{VENUE_ID, ANY_ID}) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /**
     * Снимает СВОИ заявки. Выполняется всегда — в том числе при падении зонда.
     *
     * ⚠️ Чужое не трогает. Счёт на площадке один, на нём котируют живые боты, и
     * прежняя уборка «отменить всё активное» снесла бы их котировки.
     */
    private String cleanup(TradeClient client, ExecJournal journal, Set<String> mine) {
        TradeClient.Response active = client.activeOrders();
        Set<String> inBook = activeIds(active.body());
        String body = active.body() == null ? "" : active.body().trim();
        boolean parsed = inBook.isEmpty()
                && !(body.isEmpty() || body.equals("[]") || body.equals("{}"));

        StringBuilder out = new StringBuilder("\nУборка:\n");
        if (parsed) {
            // Различаем «пусто» и «не разобрали»: молчаливое «всё чисто» на
            // неразобранном теле — это ровно тот отказ, который в прошлый раз
            // оставил заявку в книге.
            out.append("  ⚠️ ОТВЕТ НЕ РАЗОБРАН, свои заявки могли остаться:\n  ")
                    .append(body.length() > 400 ? body.substring(0, 400) + "…" : body)
                    .append('\n');
        }

        Set<String> toCancel = new LinkedHashSet<>(mine);
        toCancel.retainAll(inBook);
        Set<String> foreign = new LinkedHashSet<>(inBook);
        foreign.removeAll(mine);

        if (toCancel.isEmpty()) {
            out.append("  своих заявок в книге нет\n");
        }
        for (String id : toCancel) {
            TradeClient.Response cancelled = client.cancel(id);
            out.append("  DELETE ").append(id).append(" → ")
                    .append(cancelled.status()).append('\n');
            journal.event("probe_cancel", id + " → " + cancelled.status());
        }
        if (!foreign.isEmpty()) {
            out.append("  чужих заявок в книге ").append(foreign.size())
                    .append(" — не трогаю (это котировки живых ботов)\n");
        }

        // Отмена могла не пройти — проверяем результат, а не намерение.
        Set<String> left = activeIds(client.activeOrders().body());
        left.retainAll(mine);
        out.append(left.isEmpty() ? "  своё убрано полностью\n"
                : "  ⚠️ СВОЁ ОСТАЛОСЬ В КНИГЕ: " + left + '\n');
        return out.toString();
    }
}
