package org.home.data.trade;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Подпись приватных запросов Kraken Futures (cf-api). Схема (важно — иная, чем у спота):
 * <pre>Authent = base64( HMAC-SHA512( SHA256(postData + nonce + endpoint), base64decode(apiSecret) ) )</pre>
 * где endpoint — путь без домена и без префикса {@code /derivatives} (напр. {@code /api/v3/sendorder}),
 * postData — тело form-urlencoded (для GET — пустая строка). Закреплено юнит-тестом на эталонном векторе.
 */
public final class KrakenSigner {

    /** @return значение заголовка {@code Authent}. */
    public String sign(String apiSecretB64, String postData, String nonce, String endpoint) {
        if (endpoint.startsWith("/derivatives")) endpoint = endpoint.substring("/derivatives".length());
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256")
                    .digest((postData + nonce + endpoint).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(apiSecretB64), "HmacSHA512"));
            return Base64.getEncoder().encodeToString(mac.doFinal(sha256));
        } catch (Exception e) {
            throw new IllegalStateException("Kraken sign: " + e, e);
        }
    }
}
