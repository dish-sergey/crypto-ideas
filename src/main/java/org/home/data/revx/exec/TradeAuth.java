package org.home.data.revx.exec;

import org.home.data.revx.RevxAuth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Подпись ТОРГОВЫХ запросов. Отдельно от {@link RevxAuth} намеренно.
 *
 * У стенда ключ на чтение и лежит файлами рядом с приложением — этого достаточно
 * для данных, которые и так публичны. Торговый ключ живёт по другим правилам
 * (ТЗ §6): только переменные окружения, никогда в конфиге и репозитории, и
 * читает его systemd из root-ового {@code /etc/revx-exec.env}, а не процесс из
 * своего каталога.
 *
 * Отсюда два отличия от {@link RevxAuth}, которые нельзя «упростить»:
 *
 * 1. **Никаких файлов и никаких значений по умолчанию.** Нет переменных — объект
 *    не создаётся вовсе. Стенд без ключа деградирует на публичный путь и живёт
 *    дальше; исполнитель без ключа обязан не запуститься, а не начать что-то
 *    делать наполовину.
 * 2. **Подпись знает про тело.** У стенда все запросы GET с пустым телом, у
 *    исполнителя POST и PUT с JSON, и тело входит в подписываемую строку.
 */
public final class TradeAuth {

    private static final String ENV_KEY = "REVX_TRADE_KEY";
    private static final String ENV_SECRET = "REVX_TRADE_SECRET_B64";

    private final String apiKey;
    private final PrivateKey privateKey;

    private TradeAuth(String apiKey, PrivateKey privateKey) {
        this.apiKey = apiKey;
        this.privateKey = privateKey;
    }

    /**
     * @throws IllegalStateException если ключа нет или он не читается — исполнитель
     *                               обязан упасть на старте, а не выяснить это
     *                               в момент первой заявки
     */
    public static TradeAuth fromEnvironment() {
        String key = System.getenv(ENV_KEY);
        String secret = System.getenv(ENV_SECRET);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("нет " + ENV_KEY + " — торговый ключ задаётся только"
                    + " переменной окружения (ТЗ §6), из /etc/revx-exec.env через systemd");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("нет " + ENV_SECRET + " — приватный ключ задаётся"
                    + " только переменной окружения, в base64 от PKCS#8 PEM");
        }
        return new TradeAuth(key.trim(), parseKey(secret.trim()));
    }

    /** Ключ приходит как base64 от PEM-файла целиком, вместе со строками BEGIN/END. */
    private static PrivateKey parseKey(String base64Pem) {
        try {
            String pem = new String(Base64.getDecoder().decode(base64Pem), StandardCharsets.UTF_8);
            String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("приватный ключ из " + ENV_SECRET
                    + " не разбирается: ожидается base64 от PKCS#8 PEM", e);
        }
    }

    /** Заголовки запроса. Тело участвует в подписи — у торговых методов оно есть. */
    public Map<String, String> headers(String method, URI uri, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String message = RevxAuth.signatureMessage(timestamp, method, uri, body == null ? "" : body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RevxAuth.HEADER_KEY, apiKey);
        headers.put(RevxAuth.HEADER_TIMESTAMP, timestamp);
        headers.put(RevxAuth.HEADER_SIGNATURE, sign(message));
        return headers;
    }

    private String sign(String message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("не удалось подписать торговый запрос", e);
        }
    }

    /** Хвост ключа для журнала: понять, каким ключом ходили, не раскрывая его. */
    public String keyFingerprint() {
        return apiKey.length() <= 6 ? "***" : "…" + apiKey.substring(apiKey.length() - 6);
    }
}
