package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Клиент торговых эндпоинтов Revolut X. Адреса найдены разведкой 27.08.2026
 * (см. {@link TradeCheck}), лимиты — из документации площадки:
 *
 * <pre>
 * GET    /api/1.0/orders/active   открытые заявки
 * GET    /api/1.0/orders/{id}     одна заявка
 * GET    /api/1.0/balances        остатки
 * POST   /api/1.0/orders          постановка — 10/с и 1000/СУТКИ
 * PUT    /api/1.0/orders/{id}     замена   — 10/с, суточного потолка нет
 * DELETE /api/1.0/orders/{id}     отмена   — 100/с
 * </pre>
 *
 * Суточный потолок постановок — единственный жёсткий ресурс: перевыставлять цену
 * надо через {@code PUT}, иначе тысяча закончится за часы (док. 74).
 *
 * Каждый вызов попадает в журнал вместе с телом запроса и ответом. Это требование
 * ТЗ §6, и оно же — единственный способ потом разобраться, почему заявка повела
 * себя не так, как ожидалось.
 */
public final class TradeClient {

    private static final Logger log = LoggerFactory.getLogger(TradeClient.class);

    public record Response(int status, String body, long latencyMs) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final String baseUrl;
    private final TradeAuth auth;
    private final ExecJournal journal;
    private final HttpClient http;

    public TradeClient(String baseUrl, TradeAuth auth, ExecJournal journal) {
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.journal = journal;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public Response activeOrders() {
        return call("GET", "/api/1.0/orders/active", null);
    }

    public Response balances() {
        return call("GET", "/api/1.0/balances", null);
    }

    public Response order(String id) {
        return call("GET", "/api/1.0/orders/" + id, null);
    }

    /** Постановка. Тратит суточный лимит — 1000 штук, и другого источника нет. */
    public Response place(String json) {
        return call("POST", "/api/1.0/orders", json);
    }

    /** Замена цены. Суточного потолка нет, поэтому перевыставление идёт сюда. */
    public Response replace(String id, String json) {
        return call("PUT", "/api/1.0/orders/" + id, json);
    }

    public Response cancel(String id) {
        return call("DELETE", "/api/1.0/orders/" + id, null);
    }

    private Response call(String method, String path, String body) {
        URI uri = URI.create(baseUrl + path);
        long started = System.currentTimeMillis();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20));
            if (body == null) {
                request = "GET".equals(method)
                        ? request.GET()
                        : request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request = request.method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json");
            }
            auth.headers(method, uri, body == null ? "" : body).forEach(request::header);

            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - started;
            journal.request(method, path, body, response.statusCode(), response.body(), latency, null);
            return new Response(response.statusCode(), response.body(), latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            // Неотправленный запрос тоже пишется: «ответа нет» и «не спрашивали» —
            // разные состояния, и при разборе аварии их надо различать.
            journal.request(method, path, body, null, null, latency, e.toString());
            log.error("{} {} упал за {} мс: {}", method, path, latency, e.toString());
            return new Response(-1, null, latency);
        }
    }
}
