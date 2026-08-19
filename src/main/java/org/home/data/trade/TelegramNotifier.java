package org.home.data.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Notifier} поверх Telegram Bot API. Каждый {@link Alert} → sendMessage в личный чат оператора.
 * INFO шлётся тихо (disable_notification), WARN/CRITICAL — со звуком. Уведомление «нужно подтверждение»
 * несёт inline-кнопки Подтвердить/Отклонить (callback_data = eventId), которые ловит {@link TelegramCommandListener}.
 *
 * <p>Сбой отправки не роняет торговый цикл (принцип 3): исключение логируется и проглатывается —
 * лучше пропущенный пуш, чем упавший бот.
 */
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final TelegramTransport transport;
    private final long chatId;

    public TelegramNotifier(TelegramTransport transport, long chatId) {
        this.transport = transport;
        this.chatId = chatId;
    }

    @Override
    public void push(Alert alert) {
        try {
            transport.call("sendMessage", M.writeValueAsString(payload(alert)));
        } catch (Exception e) {
            log.warn("Telegram push не отправлен ({}): {}", alert.title(), e.toString());
        }
    }

    /**
     * Сборка тела sendMessage: обычный текст (БЕЗ parse_mode) — тикеры вида PF_KAITOUSD содержат '_',
     * который Markdown ловит как курсив и роняет всё сообщение (HTTP 400 can't parse entities). Плоский
     * текст надёжен для любых символов. Эмодзи-метка уровня, тихий режим для INFO, кнопки при наличии.
     */
    ObjectNode payload(Alert alert) {
        ObjectNode body = M.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", mark(alert.level()) + " " + alert.title() + "\n" + alert.body());
        if (alert.level() == Alert.Level.INFO) body.put("disable_notification", true);
        if (!alert.actions().isEmpty()) {
            ArrayNode row = M.createArrayNode();
            for (Alert.Action a : alert.actions()) {
                ObjectNode btn = M.createObjectNode();
                btn.put("text", a.label());
                btn.put("callback_data", a.data());
                row.add(btn);
            }
            ArrayNode keyboard = M.createArrayNode();
            keyboard.add(row);
            ObjectNode markup = M.createObjectNode();
            markup.set("inline_keyboard", keyboard);
            body.set("reply_markup", markup);
        }
        return body;
    }

    private static String mark(Alert.Level level) {
        return switch (level) {
            case INFO -> "ℹ️";
            case WARN -> "⚠️";
            case CRITICAL -> "🚨";
        };
    }
}
