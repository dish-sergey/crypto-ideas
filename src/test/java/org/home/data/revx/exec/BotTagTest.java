package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Метка владельца в идентификаторе заявки. Свойство, ради которого всё это
 * заведено: два бота на одном аккаунте не должны снимать заявки друг друга.
 */
class BotTagTest {

    private static final BotTag A = new BotTag("a");
    private static final BotTag B = new BotTag("b");

    @Test
    void ownIdIsRecognisedAndForeignIsNot() {
        String mine = A.newClientOrderId();
        assertTrue(A.owns(mine), "свою заявку бот обязан узнать");
        assertFalse(B.owns(mine), "чужую — не трогать");

        String theirs = B.newClientOrderId();
        assertTrue(B.owns(theirs));
        assertFalse(A.owns(theirs));
    }

    @Test
    void unknownOwnerIsForeign() {
        // Заявка без клиентского идентификатора могла быть поставлена кем угодно.
        // Присвоить её молча хуже, чем оставить в книге хвост.
        assertFalse(A.owns(null));
        assertFalse(A.owns(""));
        assertFalse(A.owns(UUID.randomUUID().toString()), "чужой UUID — не наш");
    }

    @Test
    void identifierStaysAValidUuid() {
        // Площадка принимает только UUID; метка обязана уместиться внутрь формата,
        // а не пристроиться сбоку.
        for (int i = 0; i < 50; i++) {
            String id = A.newClientOrderId();
            assertEquals(36, id.length());
            assertDoesNotThrow(() -> UUID.fromString(id), "должен разбираться как UUID: " + id);
        }
    }

    @Test
    void identifiersStayUnique() {
        // Метка съедает 8 шестнадцатеричных цифр, но 24 остаются случайными.
        String first = A.newClientOrderId();
        String second = A.newClientOrderId();
        assertNotEquals(first, second);
        assertEquals(first.substring(0, 8), second.substring(0, 8), "префикс общий");
    }

    @Test
    void prefixDiffersBetweenBots() {
        assertNotEquals(A.prefix(), B.prefix());
        assertEquals(8, A.prefix().length());
    }
}
