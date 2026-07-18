package org.home.data.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP GET с пер-хостовым rate limit и ретраями.
 * Все источники док. 09 — публичные без ключей, поэтому клиент простой.
 */
@Component
public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);

    private static final int MAX_RETRIES = 3;
    private static final long DEFAULT_MIN_INTERVAL_MS = 350;

    /** Более строгие интервалы для источников с жёсткими лимитами. */
    private static final Map<String, Long> HOST_MIN_INTERVAL_MS = Map.of(
            "community-api.coinmetrics.io", 700L, // ~10 req / 6 c
            "api.coinpaprika.com", 1200L,
            "www.okx.com", 250L                   // 20 req / 2 c на эндпоинт
    );

    private static final String DEFAULT_USER_AGENT = "crypto-ideas-collector/1.0";

    /**
     * Akamai перед FRED молча вешает запросы с незнакомым или браузерным UA
     * (проверено 2026-07-17: collector-UA и Chrome-UA — таймаут, curl-UA — 200).
     */
    private static final Map<String, String> HOST_USER_AGENT = Map.of(
            "fred.stlouisfed.org", "curl/8.9.1"
    );

    // HTTP/1.1 принудительно: FRED (Akamai) обрывает HTTP/2-запросы Java HttpClient
    // с RST_STREAM; выигрыша от HTTP/2 при последовательных GET с троттлингом нет.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ConcurrentHashMap<String, Long> lastCallByHost = new ConcurrentHashMap<>();

    public String get(String url) {
        URI uri = URI.create(url);
        throttle(uri.getHost());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", HOST_USER_AGENT.getOrDefault(uri.getHost(), DEFAULT_USER_AGENT))
                .header("Accept", "application/json, text/csv, application/xml, text/xml, */*")
                .GET()
                .build();

        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    return response.body();
                }
                if (code == 429 || code >= 500) {
                    log.warn("HTTP {} от {} (попытка {}/{})", code, uri.getHost(), attempt, MAX_RETRIES);
                    sleep(backoffMs(attempt, code));
                    continue;
                }
                throw new ApiException("HTTP " + code + " для " + url + ": " + truncate(response.body()));
            } catch (IOException e) {
                lastIo = e;
                log.warn("IO-ошибка к {} (попытка {}/{}): {}", uri.getHost(), attempt, MAX_RETRIES, e.getMessage());
                sleep(backoffMs(attempt, 0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("Прервано: " + url, e);
            }
        }
        throw new ApiException("Не удалось получить " + url + " за " + MAX_RETRIES + " попыток", lastIo);
    }

    private void throttle(String host) {
        long minInterval = HOST_MIN_INTERVAL_MS.getOrDefault(host, DEFAULT_MIN_INTERVAL_MS);
        while (true) {
            long now = System.currentTimeMillis();
            Long last = lastCallByHost.get(host);
            if (last == null || now - last >= minInterval) {
                if (last == null
                        ? lastCallByHost.putIfAbsent(host, now) == null
                        : lastCallByHost.replace(host, last, now)) {
                    return;
                }
            } else {
                sleep(minInterval - (now - last));
            }
        }
    }

    private static long backoffMs(int attempt, int code) {
        long base = code == 429 ? 5000 : 1000;
        return base * (1L << (attempt - 1));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
