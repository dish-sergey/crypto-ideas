package org.home.data.revx;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подпись запросов к Revolut X. Схема из документации: подписывается склейка
 * timestamp + МЕТОД + путь (начиная с /api) + query + тело, без разделителей;
 * алгоритм Ed25519, результат в Base64.
 */
class RevxAuthTest {

    @Test
    void buildsMessageAsDocumented() {
        URI uri = URI.create("https://revx.revolut.com/api/1.0/order-book/ETH-USDC?limit=5&region=EEA");

        String message = RevxAuth.signatureMessage("1765360896219", "GET", uri, "");

        assertEquals("1765360896219GET/api/1.0/order-book/ETH-USDC" + "limit=5&region=EEA", message);
    }

    @Test
    void queryAbsentGivesNoTrailingSeparator() {
        URI uri = URI.create("https://revx.revolut.com/api/1.0/tickers");

        assertEquals("1700000000000GET/api/1.0/tickers",
                RevxAuth.signatureMessage("1700000000000", "GET", uri, ""));
    }

    @Test
    void pathIsTakenFromApiPrefixNotFromHost() {
        // подписывается путь, а не полный URL: хост в подпись входить не должен
        String message = RevxAuth.signatureMessage("1", "GET",
                URI.create("https://revx.revolut.com/api/2.0/public/order-book/BTC-USD?limit=5"), "");

        assertTrue(message.startsWith("1GET/api/2.0/public/order-book/BTC-USD"), message);
        assertTrue(!message.contains("revx.revolut.com"), "хост попал в подпись: " + message);
    }

    /** Подпись обязана проверяться публичным ключом — иначе площадка её отвергнет. */
    @Test
    void signatureVerifiesWithPublicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String message = RevxAuth.signatureMessage("1765360896219", "GET",
                URI.create("https://revx.revolut.com/api/1.0/order-book/ETH-USDC?limit=5"), "");

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String base64 = Base64.getEncoder().encodeToString(signer.sign());

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(base64)));
    }

    @Test
    void withoutKeysAuthStaysDisabledInsteadOfFailing() {
        // Нет ключа — стенд обязан продолжать работать по публичному пути,
        // а не падать при старте: сбор важнее скорости.
        RevxAuth auth = new RevxAuth("keys/нет-такого-файла.txt", "keys/нет-такого.pem");

        assertTrue(!auth.enabled());
        assertTrue(auth.headers("GET", URI.create("https://revx.revolut.com/api/1.0/tickers")).isEmpty());
    }
}
