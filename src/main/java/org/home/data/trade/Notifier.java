package org.home.data.trade;

/**
 * Куда S5 шлёт уведомления оператору (Telegram в проде, {@link MockNotifier} в тестах, {@link #NONE}
 * когда канал не подключён). Одна реализация — один канал; оркестратор к нему привязан только через
 * этот интерфейс, поэтому тестируется без сети.
 */
public interface Notifier {

    void push(Alert alert);

    /** No-op: уведомления просто не отправляются (канал не настроен). */
    Notifier NONE = alert -> { };
}
