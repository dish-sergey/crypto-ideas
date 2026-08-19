package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Сборка sendMessage: тихий INFO, звук WARN/CRITICAL, inline-кнопки для подтверждения. */
class TelegramNotifierTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test void infoIsSilent() {
        ObjectNode p = new TelegramNotifier(new FakeTelegramTransport(), 42L).payload(Alert.info("t", "b"));
        assertTrue(p.get("disable_notification").asBoolean(), "INFO — без звука");
        assertEquals(42L, p.get("chat_id").asLong());
        assertTrue(p.get("text").asText().contains("t"));
        assertFalse(p.has("reply_markup"), "у обычного INFO кнопок нет");
    }

    @Test void warnIsLoud() {
        ObjectNode p = new TelegramNotifier(new FakeTelegramTransport(), 42L).payload(Alert.warn("Стоп", "b"));
        assertFalse(p.has("disable_notification"), "WARN — со звуком");
        assertTrue(p.get("text").asText().contains("⚠️"));
    }

    @Test void plainTextNoMarkdownForUnderscoreSymbols() {
        // тикеры вида PF_KAITOUSD содержат '_' — с parse_mode=Markdown Telegram роняет сообщение (400).
        ObjectNode p = new TelegramNotifier(new FakeTelegramTransport(), 42L)
                .payload(Alert.info("Открыт шорт", "PF_KAITOUSD qty=133 @ 0.3384"));
        assertFalse(p.has("parse_mode"), "без parse_mode — плоский текст");
        assertTrue(p.get("text").asText().contains("PF_KAITOUSD"), "подчёркивание в тикере сохранено как есть");
    }

    @Test void approvalCarriesButtons() {
        ObjectNode p = new TelegramNotifier(new FakeTelegramTransport(), 42L)
                .payload(Alert.approval("Нужно подтверждение", "APT", "PF_APTUSD@20005"));
        JsonNode kb = p.path("reply_markup").path("inline_keyboard");
        assertEquals(1, kb.size(), "один ряд");
        assertEquals(2, kb.get(0).size(), "две кнопки");
        assertEquals("approve:PF_APTUSD@20005", kb.get(0).get(0).get("callback_data").asText());
        assertEquals("reject:PF_APTUSD@20005", kb.get(0).get(1).get("callback_data").asText());
    }

    @Test void pushCallsSendMessage() throws Exception {
        FakeTelegramTransport tx = new FakeTelegramTransport();
        new TelegramNotifier(tx, 42L).push(Alert.warn("x", "y"));
        assertEquals("sendMessage", tx.last().method());
        assertEquals(42L, M.readTree(tx.last().body()).get("chat_id").asLong());
    }

    @Test void pushSwallowsTransportFailure() {
        // сбой отправки не должен пробрасываться в торговый цикл (принцип 3)
        TelegramTransport boom = (method, body) -> { throw new RuntimeException("network down"); };
        assertDoesNotThrow(() -> new TelegramNotifier(boom, 42L).push(Alert.critical("x", "y")));
    }
}
