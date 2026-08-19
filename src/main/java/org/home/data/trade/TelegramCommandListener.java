package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Входящая сторона Telegram: long-poll getUpdates → команды с телефона и кнопки подтверждения.
 * Команды: {@code /status}, {@code /positions}, {@code /help}. Кнопки уведомления «нужно подтверждение»
 * шлют callback {@code approve:<id>} / {@code reject:<id>} → {@link S5Orchestrator#approve}/{@code reject}.
 *
 * <p>Разбор ({@link #handleUpdates}) отделён от цикла ({@link #start}) — разбор тестируется на готовом
 * JSON без сети. Сообщения не из настроенного чата игнорируются (единственный доверенный оператор).
 */
public class TelegramCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandListener.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final TelegramTransport transport;
    private final long chatId;
    private final S5Orchestrator orch;
    private long offset = 0;
    private volatile boolean running = false;

    public TelegramCommandListener(TelegramTransport transport, long chatId, S5Orchestrator orch) {
        this.transport = transport;
        this.chatId = chatId;
        this.orch = orch;
    }

    /** Демон-поток: бесконечный long-poll. Сбой сети логируется и не роняет поток. */
    public void start() {
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    ObjectNode req = M.createObjectNode();
                    req.put("offset", offset);
                    req.put("timeout", 50);
                    handleUpdates(transport.call("getUpdates", M.writeValueAsString(req)));
                } catch (Exception e) {
                    log.warn("Telegram getUpdates: {}", e.toString());
                    sleep(5000);                           // транзиентные 429/502/timeout — подождать и повторить
                }
            }
        }, "s5-telegram-listener");
        t.setDaemon(true);
        t.start();
    }

    public void stop() { running = false; }

    /** Разбор ответа getUpdates: обновить offset, отработать команды и callback'и. Возвращает число апдейтов. */
    int handleUpdates(String json) throws Exception {
        JsonNode root = M.readTree(json);
        JsonNode result = root.path("result");
        if (!result.isArray()) return 0;
        int n = 0;
        for (JsonNode upd : result) {
            offset = Math.max(offset, upd.path("update_id").asLong() + 1);   // не переспросить обработанное
            if (upd.has("callback_query")) handleCallback(upd.get("callback_query"));
            else if (upd.has("message")) handleMessage(upd.get("message"));
            n++;
        }
        return n;
    }

    private void handleMessage(JsonNode msg) throws Exception {
        if (msg.path("chat").path("id").asLong() != chatId) return;          // чужой чат — игнор
        String text = msg.path("text").asText("").trim();
        switch (text.split("\\s+")[0]) {
            case "/status" -> send(orch.status().render());
            case "/positions" -> send(renderPositions());
            case "/balance" -> send(orch.balanceBreakdown());
            case "/unlocks", "/next" -> send(orch.upcomingText(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()));
            case "/help", "/start" -> send("Команды: /status — состояние, /balance — баланс счёта, /positions — позиции, /unlocks — ближайшие разлоки");
            default -> { /* не команда — молчим */ }
        }
    }

    private void handleCallback(JsonNode cb) throws Exception {
        String data = cb.path("data").asText("");
        long fromChat = cb.path("message").path("chat").path("id").asLong();
        String cbId = cb.path("id").asText("");
        if (fromChat != chatId) { answerCallback(cbId, "не разрешено"); return; }

        if (data.startsWith("approve:")) {
            try {
                S5Orchestrator.ApproveOutcome out = orch.approve(data.substring("approve:".length()));
                answerCallback(cbId, out.approved() ? "✅ принято" : "❌ не принято");
                send(out.message());
            } catch (Exception e) {                    // биржа недоступна при проверке средств
                answerCallback(cbId, "ошибка");
                send("⚠️ Не удалось проверить баланс — попробуй подтвердить ещё раз через минуту.");
            }
        } else if (data.startsWith("reject:")) {
            boolean ok = orch.reject(data.substring("reject:".length()));
            answerCallback(cbId, ok ? "✖️ отклонено" : "уже неактуально");
            send((ok ? "✖️ Отклонено" : "уже неактуально") + ": " + data.substring("reject:".length()));
        } else {
            answerCallback(cbId, "неизвестная кнопка");
        }
    }

    private String renderPositions() throws Exception {
        var ps = orch.positions();
        if (ps.isEmpty()) return "открытых позиций нет";
        StringBuilder sb = new StringBuilder("Открытые позиции:");
        for (Position p : ps) sb.append("\n").append(p.symbol()).append(" ")
                .append(p.side()).append(" qty=").append(p.qty()).append(" @ ").append(p.entryPx());
        return sb.toString();
    }

    private void send(String text) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        transport.call("sendMessage", M.writeValueAsString(body));
    }

    private void answerCallback(String cbId, String text) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("callback_query_id", cbId);
        body.put("text", text);
        transport.call("answerCallbackQuery", M.writeValueAsString(body));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
