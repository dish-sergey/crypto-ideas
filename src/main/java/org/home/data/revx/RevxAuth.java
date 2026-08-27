package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Подпись запросов к Revolut X (Ed25519).
 *
 * Зачем вообще ключ на стенде, который не торгует: публичные эндпоинты дают
 * 1 токен/с, авторизованные — 100/с и 1000/мин. Это разница между «23 пары раз
 * в минуту» и требованием ТЗ §3.4 «раз в 5 секунд», и заодно возвращает
 * одновременность ног (ТЗ §3.3): параллельные запросы перестают ловить 429.
 *
 * Ключ создаётся с правом ТОЛЬКО НА ЧТЕНИЕ — отправить ордер им физически
 * нельзя, обещание ТЗ §0 «приложение не хранит торговых ключей» нарушается
 * минимально и осознанно.
 *
 * Подписывается строка: timestamp + МЕТОД + путь (начиная с /api) + query + тело.
 * Приватный ключ — PKCS#8 PEM, в git не попадает (см. .gitignore).
 */
@Component
@Lazy
public class RevxAuth {

    private static final Logger log = LoggerFactory.getLogger(RevxAuth.class);

    public static final String HEADER_KEY = "X-Revx-API-Key";
    public static final String HEADER_TIMESTAMP = "X-Revx-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Revx-Signature";

    private final String apiKey;
    private final PrivateKey privateKey;

    public RevxAuth(@Value("${revx.auth.api-key-file}") String apiKeyFile,
                    @Value("${revx.auth.private-key-file}") String privateKeyFile) {
        this.apiKey = readApiKey(apiKeyFile);
        this.privateKey = readPrivateKey(privateKeyFile);
        if (enabled()) {
            log.info("Revolut X: ключ загружен, идём по авторизованным эндпоинтам (100 req/s, 1000/мин)");
        } else {
            log.info("Revolut X: ключа нет, работаем по публичным эндпоинтам (1 req/s)");
        }
    }

    /** Без ключа стенд не ломается, а падает на публичный путь с его 1 req/s. */
    public boolean enabled() {
        return apiKey != null && privateKey != null;
    }

    /** Заголовки для запроса. Тело у нас всегда пустое: стенд только читает. */
    public Map<String, String> headers(String method, URI uri) {
        if (!enabled()) {
            return Map.of();
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String message = signatureMessage(timestamp, method, uri, "");
        return Map.of(
                HEADER_KEY, apiKey,
                HEADER_TIMESTAMP, timestamp,
                HEADER_SIGNATURE, sign(message));
    }

    /**
     * Склейка без разделителей: timestamp + МЕТОД + путь + query + тело.
     * Путь берётся из URI как есть — он уже начинается с /api, как требует
     * документация; query — без вопросительного знака.
     */
    public static String signatureMessage(String timestamp, String method, URI uri, String body) {
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        return timestamp + method.toUpperCase() + uri.getRawPath() + query + body;
    }

    String sign(String message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("не удалось подписать запрос к Revolut X", e);
        }
    }

    /** Ключ можно дать файлом или переменной окружения (как у S5 с Kraken). */
    private static String readApiKey(String file) {
        String fromEnv = System.getenv("REVX_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Path path = Path.of(file);
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            log.warn("не прочитался файл API-ключа {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static PrivateKey readPrivateKey(String file) {
        Path path = Path.of(file);
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            String pem = Files.readString(path, StandardCharsets.UTF_8);
            String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("приватный ключ {} не читается: {}", file, e.getMessage());
            return null;
        }
    }
}
