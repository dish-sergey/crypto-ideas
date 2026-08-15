package org.home.data.trade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Стоп-логика S5 — единственная защита хвоста (doc 57 §4.2, главное). В BULL хвост толще
 * (s5_diagnostics: худшие 10% −31% против −20.6%), поэтому стоп обязан работать до реального капитала.
 *
 * <p>Цепочка: обнаружение достижения порога по внутридневному максимуму → закрывающий ордер →
 * подтверждение → журнал. Обрабатывает сбойные сценарии: отклонение закрытия (ретраи→эскалация),
 * гэп через порог (фиксирует фактическое проскальзывание), уже-закрытую позицию (без двойного
 * закрытия), разрыв соединения (докрытие после реконнекта). Состояние — из {@link ExchangeAdapter#positions()}.
 */
public class StopEngine {

    private static final double GAP_EPS = 0.005; // классифицируем как STOP_GAP, если убыток > порог + 0.5пп

    private final ExchangeAdapter ex;
    private final TradeJournal journal;
    private final double stopFrac;   // 0.30
    private final int maxRetries;    // ретраи закрывающего ордера

    private static final class Managed {
        final double entryPx; final double qty; double runningMax;
        Managed(double entryPx, double qty) { this.entryPx = entryPx; this.qty = qty; this.runningMax = entryPx; }
    }
    private final Map<String, Managed> managed = new LinkedHashMap<>();

    public StopEngine(ExchangeAdapter ex, TradeJournal journal, double stopFrac, int maxRetries) {
        this.ex = ex; this.journal = journal; this.stopFrac = stopFrac; this.maxRetries = maxRetries;
    }

    /** Открыть шорт ТОЛЬКО при подтверждении события через Approval Gate; иначе ордер не отправляется. */
    public boolean openShort(String eventId, ApprovalGate gate, String symbol, double qty)
            throws ExchangeDisconnectedException {
        if (!gate.isApproved(eventId)) return false;         // ни одного ордера в обход (§4.5)
        OrderResult r = ex.openShort(symbol, qty);
        if (!r.isFilled()) return false;
        managed.put(symbol, new Managed(r.fillPx(), qty));
        return true;
    }

    /** Один опрос цен: обновить внутридневной максимум и сработать стопом при пробое порога. */
    public void poll() {
        for (String sym : List.copyOf(managed.keySet())) {
            Managed m = managed.get(sym);
            if (m == null) continue;
            double mark;
            try { mark = ex.mark(sym); }
            catch (ExchangeDisconnectedException e) { return; } // реконнект-докрытие сделает reconnect()
            if (mark > m.runningMax) m.runningMax = mark;
            // для шорта неблагоприятно движение ВВЕРХ
            if ((m.runningMax / m.entryPx - 1.0) >= stopFrac) {
                attemptClose(sym, TradeJournal.Category.STOP);
            }
        }
    }

    /** Плановый выход в день разлока. */
    public void closePlanned(String symbol) { attemptClose(symbol, TradeJournal.Category.PLANNED_EXIT); }

    /** Разлок перенесён/отменён после входа (doc 55 §2.3): закрыть немедленно. */
    public void cancelUnlock(String symbol) { attemptClose(symbol, TradeJournal.Category.UNLOCK_CANCELLED); }

    /**
     * После восстановления соединения: перепроверить стоп-условие по текущей цене и докрыть только
     * те позиции, что пробили порог (закрытие могло не дойти в момент разрыва). Непробившие — оставить.
     */
    public void reconnect() {
        for (String sym : List.copyOf(managed.keySet())) {
            Managed m = managed.get(sym);
            if (m == null) continue;
            double mark;
            try { mark = ex.mark(sym); }
            catch (ExchangeDisconnectedException e) { return; } // всё ещё нет связи
            if (mark > m.runningMax) m.runningMax = mark;
            if ((m.runningMax / m.entryPx - 1.0) >= stopFrac) {
                attemptClose(sym, TradeJournal.Category.RECONNECT_CLOSE);
            }
        }
    }

    private void attemptClose(String symbol, TradeJournal.Category baseCat) {
        Managed m = managed.get(symbol);
        if (m == null) return;
        // источник истины: если позиции уже нет — она закрыта вне системы, не закрываем повторно
        try {
            if (!hasPosition(symbol)) {
                journal.record(symbol, TradeJournal.Category.ALREADY_CLOSED, m.entryPx, m.entryPx,
                        "позиция закрыта вне системы");
                managed.remove(symbol);
                return;
            }
        } catch (ExchangeDisconnectedException e) { return; } // разрыв — попробуем на reconnect()

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            OrderResult r;
            try { r = ex.closeShort(symbol, m.qty); }
            catch (ExchangeDisconnectedException e) { return; } // висит; docrytie на reconnect()
            if (r.isFilled()) {
                double exit = r.fillPx();
                double adverse = exit / m.entryPx - 1.0; // >0 = убыток шорта
                TradeJournal.Category cat = adverse > stopFrac + GAP_EPS ? TradeJournal.Category.STOP_GAP : baseCat;
                String note = cat == TradeJournal.Category.STOP_GAP
                        ? String.format("гэп: факт %.1f%% против порога %.0f%%", adverse * 100, stopFrac * 100)
                        : "закрыто";
                journal.record(symbol, cat, m.entryPx, exit, note);
                managed.remove(symbol);
                return;
            }
            // отклонено: возможно, позиция исчезла между проверкой и ордером
            try {
                if (!hasPosition(symbol)) {
                    journal.record(symbol, TradeJournal.Category.ALREADY_CLOSED, m.entryPx, m.entryPx,
                            "позиция исчезла при закрытии");
                    managed.remove(symbol);
                    return;
                }
            } catch (ExchangeDisconnectedException e) { return; }
            // иначе — транзиентное отклонение, повторяем
        }
        // ретраи исчерпаны — эскалация, позицию не снимаем (докрытие на следующем poll/reconnect)
        journal.record(symbol, TradeJournal.Category.CLOSE_FAILED, m.entryPx, 0,
                "закрытие отклонено " + (maxRetries + 1) + " раз — АЛЕРТ");
    }

    private boolean hasPosition(String symbol) throws ExchangeDisconnectedException {
        for (Position p : ex.positions()) if (p.symbol().equals(symbol)) return true;
        return false;
    }

    public int openCount() { return managed.size(); }
}
