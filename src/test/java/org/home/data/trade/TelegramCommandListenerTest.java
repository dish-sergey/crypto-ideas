package org.home.data.trade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Входящая сторона: команды с телефона и кнопки Подтвердить/Отклонить (разбор без сети). */
class TelegramCommandListenerTest {

    private static final long CHAT = 42L;
    private static final long TODAY = 20000;
    private static final String EID = "PF_APTUSD@20005";   // event: unlockDay = today+5

    /** Мини-фид с одним событием APT через 5 дней. */
    private static EventFeed feedApt() {
        return new EventFeed() {
            public List<UnlockEvent> upcoming(long today) {
                return List.of(new UnlockEvent("APT", "PF_APTUSD", today + 5, 0.05, "investors"));
            }
            public List<UnlockEvent> dueForEntry(long today, int lead) { return List.of(); }
        };
    }

    private record Rig(MockExchange ex, ApprovalGate gate, S5Orchestrator orch, FakeTelegramTransport tx, TelegramCommandListener lis) {}

    private static Rig rig() throws Exception {
        MockExchange ex = new MockExchange(1000);
        ex.tick("PF_APTUSD", 10.0);
        ApprovalGate gate = new ApprovalGate();
        TradeJournal j = new TradeJournal();
        S5Orchestrator orch = new S5Orchestrator(ex, feedApt(), base -> 0.0, gate,
                new StopEngine(ex, j, 0.30, 3), new ScheduleTracker(), new DegradationMonitor(), j, S5Config.protocol());
        orch.discover(TODAY);                                     // кандидат в pending
        FakeTelegramTransport tx = new FakeTelegramTransport();
        return new Rig(ex, gate, orch, tx, new TelegramCommandListener(tx, CHAT, orch));
    }

    private static String callbackJson(long updId, String data, long chat) {
        return "{\"result\":[{\"update_id\":" + updId + ",\"callback_query\":{\"id\":\"cb1\",\"data\":\""
                + data + "\",\"message\":{\"chat\":{\"id\":" + chat + "}}}}]}";
    }
    private static String messageJson(long updId, String text, long chat) {
        return "{\"result\":[{\"update_id\":" + updId + ",\"message\":{\"chat\":{\"id\":" + chat
                + "},\"text\":\"" + text + "\"}}]}";
    }

    @Test void approveButtonApprovesCandidate() throws Exception {
        Rig r = rig();
        assertFalse(r.gate.isApproved(EID));
        r.lis.handleUpdates(callbackJson(1, "approve:" + EID, CHAT));
        assertTrue(r.gate.isApproved(EID), "кнопка Подтвердить → gate одобрил");
        assertEquals(1, r.tx.countOf("answerCallbackQuery"), "кнопке ответили");
        assertTrue(r.tx.countOf("sendMessage") >= 1, "прислали подтверждение в чат");
    }

    @Test void approveBlockedWhenInsufficientFunds() throws Exception {
        Rig r = rig();
        r.ex.setMinSize("PF_APTUSD", 10.0);                       // мин.лот 10 → нужно $100 при $10 цене; баланс $1000 даёт $45 позицию < мин
        r.lis.handleUpdates(callbackJson(10, "approve:" + EID, CHAT));
        assertFalse(r.gate.isApproved(EID), "подтверждение отклонено — средств не хватает");
        assertEquals(1, r.orch.status().pendingApprovals(), "событие остаётся в ожидании");
        assertTrue(r.tx.lastOf("sendMessage").body().contains("Не подтверждено"), r.tx.lastOf("sendMessage").body());
    }

    @Test void rejectButtonDropsCandidate() throws Exception {
        Rig r = rig();
        assertEquals(1, r.orch.status().pendingApprovals());
        r.lis.handleUpdates(callbackJson(2, "reject:" + EID, CHAT));
        assertEquals(0, r.orch.status().pendingApprovals(), "Отклонить → снят из ожидания");
        assertFalse(r.gate.isApproved(EID));
    }

    @Test void statusCommandRepliesWithSnapshot() throws Exception {
        Rig r = rig();
        r.lis.handleUpdates(messageJson(3, "/status", CHAT));
        assertEquals("sendMessage", r.tx.last().method());
        assertTrue(r.tx.last().body().contains("S5"), "ответ на /status — снимок состояния");
    }

    @Test void unlocksCommandListsUpcoming() throws Exception {
        Rig r = rig();                                            // rig() уже вызвал discover → снимок собран
        r.lis.handleUpdates(messageJson(6, "/unlocks", CHAT));
        assertEquals("sendMessage", r.tx.last().method());
        assertTrue(r.tx.last().body().contains("PF_APTUSD"), "в ответе — ближайший разлок");
    }

    @Test void foreignChatIgnored() throws Exception {
        Rig r = rig();
        r.lis.handleUpdates(messageJson(4, "/status", 999L));     // чужой чат
        assertEquals(0, r.tx.calls.size(), "на чужой чат не отвечаем");
        r.lis.handleUpdates(callbackJson(5, "approve:" + EID, 999L));
        assertFalse(r.gate.isApproved(EID), "чужой callback не подтверждает");
    }

    @Test void offsetAdvancesPastProcessedUpdates() throws Exception {
        Rig r = rig();
        int n = r.lis.handleUpdates(messageJson(100, "/help", CHAT));
        assertEquals(1, n);
    }
}
