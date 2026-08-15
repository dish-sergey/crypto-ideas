package org.home.data.trade;

/**
 * Транспорт к Telegram Bot API — одна операция «вызвать метод с JSON-телом, получить JSON-ответ».
 * Реальная реализация ({@link HttpTelegramTransport}) шлёт HTTP POST на api.telegram.org; в тестах
 * подставляется фейк, поэтому {@link TelegramNotifier} и слушатель команд проверяются без сети и токена.
 */
public interface TelegramTransport {
    /** method — например "sendMessage" / "getUpdates" / "answerCallbackQuery"; jsonBody — тело запроса. */
    String call(String method, String jsonBody) throws Exception;
}
