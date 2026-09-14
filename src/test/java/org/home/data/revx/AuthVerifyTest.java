package org.home.data.revx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ПРОВЕРКА, ЧТО КЛЮЧ ДЕЙСТВИТЕЛЬНО РАБОТАЕТ.
 *
 * ⚠️ Самая опасная поломка здесь — тихая. Нет файлов ключа — приложение молча
 * уходит на публичный путь: данные идут, таблицы растут, никто не кричит. Так у
 * нас уже терялись 12 пар хвоста на micro2: публичный путь давал перекос ног
 * 1250 мс при пороге 250, и КАЖДЫЙ их снимок молча отбрасывался расчётом курса.
 *
 * Статус 200 доказательством не служит — рыночные адреса отвечают и без ключа.
 * Единственный надёжный признак авторизованного пути — ГЛУБИНА: публичный путь
 * игнорирует {@code limit} и отдаёт пять уровней при любом запросе (проверено
 * 10.09.2026), с ключом приходит столько, сколько просили.
 */
class AuthVerifyTest {

    /** Ключа нет вовсе — это и есть первый случай, ради которого всё затевалось. */
    @Test
    void missingKeyIsReported() {
        RevxAuth auth = new RevxAuth("нет-такого-файла.txt", "нет-такого-файла.pem");
        String problem = auth.verify(symbol -> "неважно");
        assertNotNull(problem, "отсутствие ключа обязано быть названо");
        assertTrue(problem.contains("ключа нет"), problem);
    }

    /** Пять уровней в ответе — это публичный путь, сколько бы мы ни просили. */
    @Test
    void shallowAnswerMeansPublicPath() {
        String shallow = body(5);
        String problem = verifyWith(shallow);
        assertNotNull(problem, "мелкая книга обязана быть распознана: " + shallow);
        assertTrue(problem.contains("ПУБЛИЧНЫЙ"), problem);
    }

    /** Глубокая книга приходит только с принятым ключом. */
    @Test
    void deepAnswerMeansKeyWorks() {
        assertNull(verifyWith(body(50)));
    }

    /** Отказ подписи площадка называет своими словами. */
    @Test
    void rejectedSignatureIsReported() {
        String problem = verifyWith("{\"message\":\"Unauthenticated access\",\"code\":2000}");
        assertNotNull(problem);
        assertTrue(problem.contains("отвергла подпись"), problem);
    }

    /** Молчание площадки — тоже беда, и молчать о нём нельзя. */
    @Test
    void emptyAnswerIsReported() {
        assertNotNull(verifyWith(""));
        assertNotNull(verifyWith(null));
    }

    /**
     * Проверка ходит за книгой, а НЕ за остатками: ключ может быть чужого счёта,
     * и тянуть в свой журнал чужие остатки незачем.
     */
    @Test
    void verifyAsksForTheBookAndNothingElse() {
        StringBuilder asked = new StringBuilder();
        auth().verify(symbol -> {
            asked.append(symbol);
            return body(50);
        });
        assertTrue(asked.toString().contains("BTC"), "спрашиваем книгу пары: " + asked);
    }

    /** Тело ответа книги с заданным числом ценовых уровней. */
    private static String body(int levels) {
        StringBuilder sb = new StringBuilder("{\"bids\":[");
        for (int i = 0; i < levels; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"p\":\"").append(70000 - i)
                    .append("\",\"q\":\"1\"}");
        }
        return sb.append("]}").toString();
    }

    private static String verifyWith(String answer) {
        return auth().verify(symbol -> answer);
    }

    /**
     * Ключ, который «есть»: проверке достаточно, чтобы оба файла прочитались, —
     * дальше она судит по ответу площадки. Пара сгенерирована для теста.
     */
    private static RevxAuth auth() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("revx-key");
            java.security.KeyPair pair = java.security.KeyPairGenerator
                    .getInstance("Ed25519").generateKeyPair();
            java.nio.file.Path pem = dir.resolve("private.pem");
            java.nio.file.Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n");
            java.nio.file.Path key = dir.resolve("api_key.txt");
            java.nio.file.Files.writeString(key, "0".repeat(64));
            return new RevxAuth(key.toString(), pem.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
