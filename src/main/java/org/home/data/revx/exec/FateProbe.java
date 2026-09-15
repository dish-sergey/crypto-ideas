package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code --revx-fate-probe}: КАК УЗНАТЬ СУДЬБУ ЗАЯВКИ, У КОТОРОЙ НЕТ НАШЕГО
 * ИДЕНТИФИКАТОРА. Только GET, ни одного ордера.
 *
 * <h2>Зачем</h2>
 *
 * Остался ровно один непокрытый способ потерять исполнение, и 14.09.2026 он
 * сработал на живом боте A. Замена получает 422 «Cannot replace an order that is
 * not in the 'NEW' state»: площадка замену ВЫПОЛНИЛА, наследника создала, но его
 * {@code venue_order_id} не вернула (док. 111). Обычно наследника находит сверка
 * по списку активных — но если он успел исполниться раньше, в списке его уже
 * нет, спросить не о чем, и покупка пропадает из учёта навсегда. У бота A в
 * 06:31:54 так потерялся лот BTC: монета пришла на счёт ничейной, деньги ушли,
 * записи не появилось.
 *
 * 🔑 <b>Но идентификатор наследника мы всё-таки знаем.</b> {@code client_order_id}
 * генерируем МЫ и кладём в тело того самого PUT — он лежит в нашем журнале
 * ({@code exec_request.body}). Не хватает только способа спросить площадку по
 * нему.
 *
 * <h2>Что делает зонд</h2>
 *
 * Перебирает формы адреса «дай заявку по клиентскому идентификатору» и «дай
 * историю заявок» и печатает, что ответила площадка. Дальше решает человек:
 * найденная форма закрывает этот класс потерь целиком, ненайденная — значит
 * остаётся сторож ничейного и ручной {@code /claim}.
 *
 * ⚠️ <b>401 здесь двусмысленна.</b> Площадка отвечает тем же «Unauthenticated
 * access» и на несуществующий адрес, и на неверную подпись — на этом уже
 * обожглись 19.08.2026, когда {@code /trades/all?symbol=} дал 401 не потому, что
 * адреса нет, а потому что символ ждали в ПУТИ. Поэтому первым идёт заведомо
 * рабочий адрес: если он отвечает 200, подпись в порядке, и 401 у остальных
 * означает именно отсутствие адреса.
 */
@Component
@Lazy
public class FateProbe {

    private static final Logger log = LoggerFactory.getLogger(FateProbe.class);

    private final RevxConfig cfg;

    public FateProbe(RevxConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * @param venueId   известный идентификатор площадки — контроль подписи
     * @param clientId  наш {@code client_order_id} той же заявки
     */
    public void run(String venueId, String clientId) {
        if (venueId == null || venueId.isBlank() || clientId == null || clientId.isBlank()) {
            log.error("нужны --order-id (venue_order_id) и --client-id (наш client_order_id) "
                    + "ОДНОЙ И ТОЙ ЖЕ заявки: без контроля подписи ответы не читаются");
            return;
        }
        TradeAuth auth = TradeAuth.fromEnvironment();
        log.info("торговый ключ загружен ({}), зонд судьбы заявки — ТОЛЬКО GET", auth.keyFingerprint());

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String enc = URLEncoder.encode(clientId, StandardCharsets.UTF_8);
        List<String> paths = new ArrayList<>();
        // КОНТРОЛЬ: этот адрес заведомо есть. 200 здесь = подпись верна.
        paths.add("/api/1.0/orders/" + venueId);
        // Заявка по нашему идентификатору — то, ради чего зонд.
        paths.add("/api/1.0/orders?client_order_id=" + enc);
        paths.add("/api/1.0/orders/client/" + enc);
        paths.add("/api/1.0/orders/client_order_id/" + enc);
        paths.add("/api/1.0/orders/by-client-id/" + enc);
        paths.add("/api/1.0/orders/" + enc);
        // История заявок: годится и она — наследника можно найти перебором.
        paths.add("/api/1.0/orders");
        paths.add("/api/1.0/orders/all");
        paths.add("/api/1.0/orders/history");
        paths.add("/api/1.0/orders/closed");
        paths.add("/api/1.0/orders?status=filled");
        paths.add("/api/1.0/orders/inactive");
        // Наши собственные сделки: другой способ увидеть исполнение.
        paths.add("/api/1.0/fills");
        paths.add("/api/1.0/trades/my");
        paths.add("/api/1.0/trades/mine");

        StringBuilder report = new StringBuilder(
                "\n=== Зонд: как узнать судьбу заявки без её venue_order_id ===\n");
        for (String path : paths) {
            URI uri = URI.create(cfg.baseUrl() + path);
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .GET();
                auth.headers("GET", uri, "").forEach(request::header);
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                report.append(String.format("%-52s %d  %s%n",
                        path.length() > 50 ? path.substring(0, 49) + "…" : path,
                        response.statusCode(), summarize(response.body())));
                // Пауза: у площадки ограничен минимальный интервал между
                // запросами, и разведка не должна выглядеть залпом.
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                report.append(String.format("%-52s  —  ошибка: %s%n", path, e.getMessage()));
            }
        }
        report.append("""

                Как читать:
                  первая строка 200 — подпись верна, остальным ответам можно верить;
                  200 у формы с client_order_id — КЛАСС ПОТЕРЬ ЗАКРЫВАЕТСЯ: после
                    422 «replaced» бот спросит наследника по своему же
                    идентификатору, даже если тот уже исполнился;
                  200 у истории заявок — тоже годится, наследник ищется перебором;
                  401/404 везде — способа нет, остаётся сторож ничейного и /claim.
                """);
        log.info(report.toString());
    }

    /**
     * {@code --revx-order-status --order-id=id1,id2,…}: СПРОСИТЬ ПЛОЩАДКУ ПРО
     * КОНКРЕТНЫЕ ЗАЯВКИ и напечатать ответ целиком. Только GET.
     *
     * <h2>Зачем отдельная команда</h2>
     *
     * Судьбу заявки нельзя выводить ни из её собственного статуса в нашем
     * журнале, ни из отсутствия в списке активных: у площадки это РАЗНЫЕ
     * источники, и они расходятся. 15.09.2026 нашлись две заявки, которых нет в
     * {@code /orders/active}, но чей резерв площадка держит — и единственный
     * способ узнать, живы они или нет, это спросить по идентификатору.
     *
     * ⚠️ Печатается ВЕСЬ ответ. Зонд {@link #run} режет тело до 150 символов,
     * чтобы не таскать в журнал остатки счёта, — а здесь важен именно
     * {@code status} и {@code leaves_quantity}, которые стоят в конце.
     */
    public void status(String ids) {
        if (ids == null || ids.isBlank()) {
            log.error("нужен --order-id=<venue_order_id>[,<venue_order_id>…]");
            return;
        }
        TradeAuth auth = TradeAuth.fromEnvironment();
        log.info("торговый ключ загружен ({}), спрашиваю статусы — ТОЛЬКО GET",
                auth.keyFingerprint());
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        StringBuilder report = new StringBuilder("\n=== Статусы заявок по идентификаторам ===\n");
        for (String id : ids.split(",")) {
            String orderId = id.trim();
            if (orderId.isEmpty()) {
                continue;
            }
            URI uri = URI.create(cfg.baseUrl() + "/api/1.0/orders/" + orderId);
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .GET();
                auth.headers("GET", uri, "").forEach(request::header);
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                report.append(orderId).append("  ").append(response.statusCode()).append('\n')
                        .append("  ").append(response.body().replaceAll("\\s+", " ")).append("\n\n");
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                report.append(orderId).append("  — ошибка: ").append(e.getMessage()).append('\n');
            }
        }
        log.info(report.toString());
    }

    /** Тело урезается: в ответе бывают остатки счёта, а в журнале это лишнее. */
    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "(пусто)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 150 ? flat : flat.substring(0, 150) + "…";
    }
}
