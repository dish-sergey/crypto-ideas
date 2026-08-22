package org.home.data.trade;

import java.util.ArrayList;
import java.util.List;

/**
 * Журнал сделок S5. Каждая закрытая нога — отдельная запись с категорией.
 * Категории требуются протоколом: обычный выход, стоп, стоп с гэпом (проскальзывание),
 * отмена разлока (doc 55 §2.3), докрытие после реконнекта, уже-закрытая позиция, провал закрытия (алерт).
 */
public class TradeJournal {

    public enum Category {
        PLANNED_EXIT,     // выход в день разлока по плану
        STOP,             // стоп сработал в пределах порога
        STOP_GAP,         // стоп исполнен с гэпом — фактический убыток больше порога (§4.2)
        UNLOCK_CANCELLED, // разлок перенесён/отменён после входа — закрыто немедленно (doc 55 §2.3)
        RECONNECT_CLOSE,  // позиция докрыта после восстановления соединения
        ALREADY_CLOSED,   // позиция была закрыта вне системы — двойного закрытия нет
        CLOSE_FAILED      // закрытие не удалось после ретраев — эскалация/алерт
    }

    public record Entry(String symbol, Category category, double entryPx, double exitPx, double qty,
                        double pnlPct, long ts, String note) {
        /** Результат в долларах для шорта: (entryPx − exitPx) × qty. */
        public double pnlUsd() { return (entryPx - exitPx) * qty; }
        /** Сумма входа в долларах. */
        public double entryNotionalUsd() { return entryPx * qty; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> alerts = new ArrayList<>();

    /** pnl для шорта: (entryPx − exitPx)/entryPx; qty — размер позиции для результата в долларах. */
    public void record(String symbol, Category cat, double entryPx, double exitPx, double qty, String note) {
        double pnl = entryPx > 0 ? (entryPx - exitPx) / entryPx : 0;
        entries.add(new Entry(symbol, cat, entryPx, exitPx, qty, pnl, System.currentTimeMillis(), note));
        if (cat == Category.CLOSE_FAILED) alerts.add(symbol + ": " + note);
    }

    public List<Entry> entries() { return List.copyOf(entries); }
    public List<String> alerts() { return List.copyOf(alerts); }
    public long count(Category cat) { return entries.stream().filter(e -> e.category() == cat).count(); }
    public int size() { return entries.size(); }
}
