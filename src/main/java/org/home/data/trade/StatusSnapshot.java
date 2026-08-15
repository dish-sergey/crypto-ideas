package org.home.data.trade;

/**
 * Моментальный снимок состояния бота для запроса «/status» с телефона: сколько позиций открыто,
 * сколько кандидатов ждёт ручного подтверждения, стоит ли пауза монитора деградации, сколько ног
 * уже закрыто за сессию. Форматируется Telegram-слоем в текст сообщения.
 */
public record StatusSnapshot(int openPositions, int pendingApprovals, boolean paused, int legsClosed) {

    /** Человекочитаемая строка для Telegram. */
    public String render() {
        return "S5 бот\n"
                + "открыто позиций: " + openPositions + "\n"
                + "ждут подтверждения: " + pendingApprovals + "\n"
                + "закрыто ног за сессию: " + legsClosed + "\n"
                + "монитор деградации: " + (paused ? "⏸ ПАУЗА" : "ok");
    }
}
