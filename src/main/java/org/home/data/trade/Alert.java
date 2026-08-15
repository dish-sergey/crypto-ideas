package org.home.data.trade;

import java.util.List;

/**
 * Уведомление на телефон (Telegram). {@code level} задаёт срочность: INFO — рутина (открытие/плановый
 * выход), WARN — внимание (стоп, перенос разлока, пауза монитора), CRITICAL — ручное вмешательство
 * (закрытие не удалось). Telegram-слой решает по level, шуметь ли звуком.
 *
 * <p>{@code actions} — необязательные кнопки: у уведомления «нужно подтверждение» это Подтвердить/Отклонить,
 * привязанные к eventId (Telegram рисует их inline; {@link MockNotifier} и {@link Notifier#NONE} игнорируют).
 */
public record Alert(Level level, String title, String body, List<Action> actions) {

    public enum Level { INFO, WARN, CRITICAL }

    /** Кнопка: подпись + callback-данные (например «approve:PF_APTUSD@20005»). */
    public record Action(String label, String data) {}

    public static Alert info(String title, String body)     { return new Alert(Level.INFO, title, body, List.of()); }
    public static Alert warn(String title, String body)     { return new Alert(Level.WARN, title, body, List.of()); }
    public static Alert critical(String title, String body) { return new Alert(Level.CRITICAL, title, body, List.of()); }

    /** Уведомление с кнопками Подтвердить/Отклонить для ручного Approval Gate. */
    public static Alert approval(String title, String body, String eventId) {
        return new Alert(Level.INFO, title, body, List.of(
                new Action("✅ Подтвердить", "approve:" + eventId),
                new Action("✖️ Отклонить", "reject:" + eventId)));
    }
}
