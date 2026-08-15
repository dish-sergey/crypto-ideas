package org.home.data.trade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Реальный транспорт Telegram Bot API: POST на {@code https://api.telegram.org/bot<token>/<method>}
 * с JSON-телом. Токен приходит извне (env {@code S5_TELEGRAM_TOKEN}) и в код/логи не попадает.
 * getUpdates при long-poll ждёт долго — таймаут запроса с запасом над серверным timeout.
 */
public class HttpTelegramTransport implements TelegramTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String base;

    public HttpTelegramTransport(String token) {
        this.base = "https://api.telegram.org/bot" + token + "/";
    }

    @Override
    public String call(String method, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + method))
                .timeout(Duration.ofSeconds(70))               // long-poll getUpdates: серверный timeout ≤ 60с
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Telegram " + method + " HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }
}
