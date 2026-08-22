package org.home.data.trade;

/**
 * Аудит торговли S5 в базу: входы, выходы, все Telegram-сообщения. Реальная реализация — {@link S5Db}
 * (SQLite); в тестах/без БД — {@link #NONE}. Все записи best-effort: сбой аудита не влияет на торговлю.
 */
public interface TradeRecorder {

    void recordOpen(String eventId, String symbol, long unlockDay, String category,
                    double qty, double entryPx, double notionalUsd);

    void recordClose(String symbol, String category, double entryPx, double exitPx, double qty,
                     double pnlPct, String note);

    /** direction: "out" (бот→оператор) / "in" (оператор→бот); kind: approval/reminder/open/exit/command/callback/… */
    void recordTelegram(String direction, String kind, String text);

    /** Текст последних N закрытых сделок с результатами (для команды /history). По умолчанию — пусто. */
    default String recentHistory(int n) { return "история недоступна"; }

    TradeRecorder NONE = new TradeRecorder() {
        public void recordOpen(String e, String s, long u, String c, double q, double px, double n) { }
        public void recordClose(String s, String c, double e, double x, double q, double p, String n) { }
        public void recordTelegram(String d, String k, String t) { }
    };
}
