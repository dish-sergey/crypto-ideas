package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Подпись cf-api на эталонном векторе (сгенерирован независимо через openssl). */
class KrakenSignerTest {

    private static final String SECRET = "MDEyMzQ1Njc4OQ==";   // base64("0123456789")

    @Test void matchesReferenceVector() {
        String authent = new KrakenSigner().sign(
                SECRET, "orderType=mkt&symbol=PF_XBTUSD&side=sell&size=1", "1", "/api/v3/sendorder");
        assertEquals("4X5GHnuepLnn+jW0RKWI0rNbOPcqmVUMqr+X0vq44i0QN96TqwIy6CzafiyoooDlrVfcIz1ItSEu3crPErwjNg==",
                authent);
    }

    @Test void stripsDerivativesPrefix() {
        KrakenSigner s = new KrakenSigner();
        assertEquals(s.sign(SECRET, "", "42", "/api/v3/openpositions"),
                     s.sign(SECRET, "", "42", "/derivatives/api/v3/openpositions"),
                "endpoint с /derivatives и без — одна и та же подпись");
    }

    @Test void nonceChangesSignature() {
        KrakenSigner s = new KrakenSigner();
        assertNotEquals(s.sign(SECRET, "", "1", "/api/v3/accounts"),
                        s.sign(SECRET, "", "2", "/api/v3/accounts"));
    }
}
