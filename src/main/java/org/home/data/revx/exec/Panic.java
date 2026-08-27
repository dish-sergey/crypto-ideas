package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-panic}: снять ВСЕ открытые заявки и выйти. Требование ТЗ §6
 * («аварийная остановка одной командой с отменой всех заявок»).
 *
 * Отдельной командой, а не только кнопкой в боте: если процесс исполнителя завис
 * или не запускается, кнопка недоступна, а заявки на бирже остались. Эта команда
 * не зависит ни от чего, кроме ключа.
 *
 * ⚠️ Имя поля с идентификатором у площадки РАЗНОЕ в разных ответах, и это найдено
 * на живой заявке 27.08.2026:
 *
 * <pre>
 * POST /orders          → "venue_order_id"
 * PUT  /orders/{id}     → "venue_order_id" (причём НОВЫЙ — замена создаёт новую заявку)
 * GET  /orders/active   → "id"
 * </pre>
 *
 * Первая версия уборки искала {@code venue_order_id} в ответе активных заявок,
 * не находила его и радостно сообщала «отменять нечего», оставляя заявку в книге.
 * Поэтому здесь ищутся оба имени, и отдельно проверяется, что после отмены список
 * действительно пуст — доверять собственному разбору ответа тут нельзя.
 */
@Component
@Lazy
public class Panic {

    private static final Logger log = LoggerFactory.getLogger(Panic.class);

    /** Оба имени: и то, что отдаёт постановка, и то, что отдаёт список активных. */
    private static final Pattern ID = Pattern.compile("\"(?:venue_order_)?id\"\\s*:\\s*\"([^\"]+)\"");

    private final RevxConfig cfg;

    public Panic(RevxConfig cfg) {
        this.cfg = cfg;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal("state/exec.db")) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);
            journal.event("panic", "аварийная отмена всех заявок");

            List<String> ids = openOrderIds(client);
            if (ids.isEmpty()) {
                log.info("открытых заявок нет");
                return;
            }
            log.warn("открытых заявок: {} — снимаю все", ids.size());
            for (String id : ids) {
                TradeClient.Response cancelled = client.cancel(id);
                log.warn("DELETE {} → {}", id, cancelled.status());
                journal.event("panic_cancel", id + " → " + cancelled.status());
            }

            // Проверяем ФАКТ, а не собственный разбор ответа: именно на этом шаге
            // и вскрылась ошибка с именем поля.
            List<String> left = openOrderIds(client);
            if (left.isEmpty()) {
                log.info("подтверждено: открытых заявок не осталось");
            } else {
                log.error("ОСТАЛИСЬ заявки после отмены: {} — снимать вручную", left);
            }
            journal.event("panic", "осталось заявок: " + left.size());
        }
    }

    private static List<String> openOrderIds(TradeClient client) {
        TradeClient.Response active = client.activeOrders();
        List<String> ids = new ArrayList<>();
        if (active.body() == null) {
            return ids;
        }
        Matcher matcher = ID.matcher(active.body());
        while (matcher.find()) {
            if (!ids.contains(matcher.group(1))) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }
}
