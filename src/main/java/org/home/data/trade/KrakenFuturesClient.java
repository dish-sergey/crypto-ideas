package org.home.data.trade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Реальный {@link KrakenApi}: HTTP к {@code futures.kraken.com/derivatives} + подпись cf-api.
 * Nonce — строго возрастающий (счётчик, засеянный текущим временем: растёт и между рестартами).
 * Ключ/секрет приходят извне (env/файл, {@link KrakenConfig}) и в код/логи не попадают.
 */
public class KrakenFuturesClient implements KrakenApi {

    static final String PROD = "https://futures.kraken.com/derivatives";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    private final KrakenSigner signer = new KrakenSigner();
    private final AtomicLong nonce = new AtomicLong(System.currentTimeMillis());

    private final String baseUrl;
    private final String apiKey;
    private final String apiSecretB64;

    public KrakenFuturesClient(String apiKey, String apiSecretB64) { this(apiKey, apiSecretB64, PROD); }

    public KrakenFuturesClient(String apiKey, String apiSecretB64, String baseUrl) {
        this.apiKey = apiKey; this.apiSecretB64 = apiSecretB64; this.baseUrl = baseUrl;
    }

    @Override public String get(String path, boolean signed) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (signed) authHeaders(b, path, "");
        return send(b.build());
    }

    @Override public String post(String path, String formBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody));
        authHeaders(b, path, formBody);
        return send(b.build());
    }

    private void authHeaders(HttpRequest.Builder b, String path, String postData) {
        String n = Long.toString(nonce.incrementAndGet());
        b.header("APIKey", apiKey);
        b.header("Nonce", n);
        b.header("Authent", signer.sign(apiSecretB64, postData, n, path));
    }

    private String send(HttpRequest req) throws Exception {
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300)
            throw new java.io.IOException("Kraken HTTP " + r.statusCode() + ": " + r.body());
        return r.body();
    }
}
