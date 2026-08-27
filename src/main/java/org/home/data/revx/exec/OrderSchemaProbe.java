package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code --revx-order-schema}: выяснить формат тела постановки, НЕ создавая заявку.
 *
 * Зачем отдельная команда. Адреса эндпоинтов мы нашли, но имена полей в теле
 * {@code POST /orders} нигде у нас не записаны. Угадать их и сразу послать
 * настоящую заявку — способ создать ордер не той стороной, не того размера или
 * не по той цене. Площадка сама называет недостающие поля в ошибках валидации,
 * и это самый дешёвый способ их узнать.
 *
 * Почему это безопасно: тела заведомо неполные. Заявку нельзя создать, не указав
 * пару, сторону, цену и объём — а мы их и не указываем. Худшее, что может
 * случиться, — 400 с текстом ошибки, ради которого всё и делается.
 *
 * Отдельно: команду стоит выполнять, ПОКА СЧЁТ ПУСТ. Тогда даже принятая по
 * недоразумению заявка не исполнится — на неё просто не хватит средств. Это
 * второй, независимый предохранитель поверх неполноты тела.
 */
@Component
@Lazy
public class OrderSchemaProbe {

    private static final Logger log = LoggerFactory.getLogger(OrderSchemaProbe.class);

    /**
     * Тела от полностью пустого к чуть более полному. Каждый следующий шаг делается
     * ровно настолько, насколько предыдущий ответ подсказал имя поля — дальше
     * поднимаемся по лестнице ошибок, а не по догадкам.
     */
    private static final List<String> BODIES = List.of(
            "{}",
            "{\"symbol\":\"BTC-USDC\"}",
            "{\"symbol\":\"BTC-USDC\",\"side\":\"buy\"}",
            "{\"symbol\":\"BTC-USDC\",\"side\":\"buy\",\"type\":\"limit\"}");

    private final RevxConfig cfg;

    public OrderSchemaProbe(RevxConfig cfg) {
        this.cfg = cfg;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal("state/exec.db")) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);

            String balances = client.balances().body();
            boolean empty = balances == null || !balances.matches(".*\"total\":\"(?!0[.0]*\")[^\"]+\".*");
            log.info("счёт {} — {}", empty ? "пуст" : "НЕ ПУСТ",
                    empty ? "безопасный момент для разведки формата"
                          : "разведку лучше делать на пустом счёте, но тела заведомо неполные");

            journal.event("order_schema_probe", "начало разведки формата постановки");
            StringBuilder report = new StringBuilder("\n=== Формат тела POST /api/1.0/orders ===\n");
            for (String body : BODIES) {
                TradeClient.Response response = client.place(body);
                report.append(String.format("%-58s → %d  %s%n", body, response.status(),
                        response.body() == null ? "(нет ответа)"
                                : response.body().replaceAll("\\s+", " ").trim()));
                if (response.ok()) {
                    // Этого не должно случиться: тело неполное. Если случилось —
                    // немедленно снимаем всё, что могло появиться.
                    report.append("!!! ЗАЯВКА СОЗДАНА неполным телом — отменяю всё\n");
                    log.error(report.toString());
                    cancelEverything(client, journal);
                    return;
                }
                Thread.sleep(300);
            }
            report.append("""

                    Читать по последней строке: площадка перечисляет недостающие поля,
                    и следующее тело собирается из них. Ни одна из этих заявок не создана.
                    """);
            log.info(report.toString());
            journal.event("order_schema_probe", "разведка завершена, заявок не создано");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Аварийная уборка: снять всё, что видно в активных заявках. */
    private void cancelEverything(TradeClient client, ExecJournal journal) {
        TradeClient.Response active = client.activeOrders();
        journal.event("panic_cancel", "ответ активных заявок: " + active.body());
        if (active.body() == null) {
            return;
        }
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(active.body());
        while (matcher.find()) {
            TradeClient.Response cancelled = client.cancel(matcher.group(1));
            log.error("отмена заявки {}: {} {}", matcher.group(1), cancelled.status(),
                    cancelled.body());
        }
    }
}
