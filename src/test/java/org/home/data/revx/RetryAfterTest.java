package org.home.data.revx;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Единица заголовка {@code Retry-After} зависит от того, КТО ответил.
 *
 * <h2>Зачем тест</h2>
 *
 * Раньше единица угадывалась по величине: «значение ≤ 60 — секунды, иначе
 * миллисекунды». Догадка держалась на том, что все наблюдённые значения были
 * больше шестидесяти, и незаметно превращала законные 50 мс в 50 секунд.
 *
 * Правило теперь такое: площадка отвечает JSON и считает в МИЛЛИСЕКУНДАХ (так
 * написано в её документации), защита периметра отвечает HTML-страницей и живёт
 * по обычному HTTP, то есть в СЕКУНДАХ. Из ~694 ответов 429 во всех журналах
 * 564 были именно HTML — то есть более частый случай как раз второй.
 */
class RetryAfterTest {

    /** Ответ с телом и одним заголовком: больше {@link RevxHttp} ничего не читает. */
    private static HttpResponse<String> response(String body, String retryAfter) {
        HttpHeaders headers = HttpHeaders.of(
                retryAfter == null ? Map.of() : Map.of("retry-after", List.of(retryAfter)),
                (a, b) -> true);
        return new HttpResponse<>() {
            @Override public int statusCode() {
                return 429;
            }
            @Override public HttpRequest request() {
                return null;
            }
            @Override public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }
            @Override public HttpHeaders headers() {
                return headers;
            }
            @Override public String body() {
                return body;
            }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }
            @Override public URI uri() {
                return URI.create("https://revx.revolut.com/api/1.0/order-book/BTC-USDC");
            }
            @Override public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test
    void venueAnswersJsonAndCountsMilliseconds() {
        // Наблюдённые живьём значения: 334, 507, 375, 531, 919, 27000.
        assertEquals(334, RevxHttp.retryAfterMs(
                response("{\"message\":\"Rate limit exceeded\"}", "334")));
        assertEquals(27000, RevxHttp.retryAfterMs(
                response("{\"message\":\"Rate limit exceeded\"}", "27000")));
    }

    @Test
    void smallValueFromVenueStaysMillisecondsAndDoesNotBecomeMinutes() {
        // ⚠️ Ровно та величина, на которой ломалась прежняя догадка: ведро
        // пополняется раз в 100 мс, поэтому 50 мс — обычный ответ, а не секунды.
        assertEquals(50, RevxHttp.retryAfterMs(
                response("{\"message\":\"Rate limit exceeded\"}", "50")));
    }

    @Test
    void edgeAnswersHtmlAndCountsSeconds() {
        // 564 из ~694 ответов 429 приходят именно так — без JSON вовсе.
        assertEquals(60_000, RevxHttp.retryAfterMs(
                response("<!doctype html><meta charset=\"utf-8\">", "60")));
    }

    @Test
    void noHeaderOrUnparsableValueMeansOwnBackoff() {
        assertEquals(0, RevxHttp.retryAfterMs(response("{}", null)));
        // Стандарт допускает и HTTP-дату — её мы не разбираем, ждём по своей лестнице.
        assertEquals(0, RevxHttp.retryAfterMs(
                response("{}", "Wed, 10 Sep 2026 20:00:00 GMT")));
        // ⚠️ Ответ БЕЗ ТЕЛА считается ответом периметра, то есть секундами. Это
        // намеренно осторожная сторона: подождать дольше безопасно, а вызывающий
        // всё равно ограничивает паузу тридцатью секундами.
        assertEquals(500_000, RevxHttp.retryAfterMs(response(null, "500")));
    }
}
