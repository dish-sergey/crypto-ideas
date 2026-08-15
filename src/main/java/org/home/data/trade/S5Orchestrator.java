package org.home.data.trade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Каркас оркестратора S5 (микро-live по протоколу 54) — против {@link ExchangeAdapter}, так что
 * тестируется на моке без ключей. Склеивает: фид разлоков → фильтр дорогого шорта → Approval Gate →
 * открытие шорта; poll стопа; ежедневную сверку расписаний (перенос/отмена → закрытие); монитор
 * деградации (запись премии на каждом закрытии). Состояние восстанавливается из биржи ({@link #recover}).
 *
 * <p>Реальных вызовов Kraken нет — {@link ExchangeAdapter} инжектируется (мок сейчас, прод-адаптер позже,
 * смена одной реализации). Approval Gate управляется вручную (оператор подтверждает между вызовами).
 */
public class S5Orchestrator {

    private final ExchangeAdapter ex;
    private final EventFeed feed;
    private final FundingSource funding;
    private final ApprovalGate gate;
    private final StopEngine engine;
    private final ScheduleTracker tracker;
    private final DegradationMonitor monitor;
    private final TradeJournal journal;
    private final S5Config cfg;

    /** eventId -> событие, поданное в Approval Gate и ждущее подтверждения. */
    private final Map<String, UnlockEvent> pending = new LinkedHashMap<>();
    private int journalCursor = 0;

    public S5Orchestrator(ExchangeAdapter ex, EventFeed feed, FundingSource funding, ApprovalGate gate,
                          StopEngine engine, ScheduleTracker tracker, DegradationMonitor monitor,
                          TradeJournal journal, S5Config cfg) {
        this.ex = ex; this.feed = feed; this.funding = funding; this.gate = gate; this.engine = engine;
        this.tracker = tracker; this.monitor = monitor; this.journal = journal; this.cfg = cfg;
    }

    /** Перезапуск: усыновить открытые позиции с биржи (источник истины), не из памяти. */
    public void recover() throws Exception { engine.adopt(ex.positions()); }

    private static String eventId(UnlockEvent e) { return e.krakenSymbol() + "@" + e.unlockDay(); }

    /**
     * Обнаружение: события с днём входа сегодня → фильтры (дубликат / дорогой шорт / лимит экспозиции) →
     * подача в Approval Gate (ожидание ручного подтверждения). Возвращает поданные события.
     */
    public List<UnlockEvent> discover(long today) throws Exception {
        List<UnlockEvent> submitted = new java.util.ArrayList<>();
        for (UnlockEvent e : feed.dueForEntry(today, cfg.entryLead())) {
            String id = eventId(e);
            if (engine.openSymbols().contains(e.krakenSymbol()) || pending.containsKey(id)) continue;
            if (funding.estimate5dFunding(e.base()) < cfg.expensiveFundingThreshold()) continue; // дорогой шорт
            if (wouldBreachExposure()) continue;
            gate.submit(id);
            pending.put(id, e);
            submitted.add(e);
        }
        return submitted;
    }

    /** Исполнение подтверждённых событий: открыть шорт, зафиксировать дату разлока. */
    public int executeApproved() throws Exception {
        int opened = 0;
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            String id = en.getKey(); UnlockEvent e = en.getValue();
            if (!gate.isApproved(id)) continue;
            if (wouldBreachExposure()) continue;
            double px = ex.mark(e.krakenSymbol());
            if (px <= 0) continue;
            double qty = ex.balance() * cfg.positionFraction() / px;
            if (engine.openShort(id, gate, e.krakenSymbol(), qty)) {
                tracker.onEntry(e.krakenSymbol(), e.unlockDay());
                it.remove();
                opened++;
            }
        }
        return opened;
    }

    /**
     * Ежедневная сверка: плановый выход в день разлока; перенос/отмена расписания → немедленное закрытие
     * (doc 55 §2.3). Требует перечитывания расписаний по открытым позициям (§2.5).
     */
    public void maintain(long today) throws Exception {
        Map<String, Long> current = new java.util.HashMap<>();
        for (UnlockEvent e : feed.upcoming(today)) current.putIfAbsent(e.krakenSymbol(), e.unlockDay());
        for (String sym : engine.openSymbols()) {
            Long expected = tracker.expectedDay(sym);
            if (expected != null && expected <= today) {          // день разлока (или просрочка) → плановый выход
                engine.closePlanned(sym); tracker.onExit(sym); continue;
            }
            Long cur = current.get(sym);
            if (tracker.scheduleChanged(sym, cur, today)) {        // перенос/отмена → закрыть немедленно
                engine.cancelUnlock(sym); tracker.onExit(sym);
            }
        }
        flushClosedToMonitor();
    }

    /** Частый опрос стопов (внутридневной). */
    public void pollStops() { engine.poll(); flushClosedToMonitor(); }

    /** Записать премию каждой закрытой ноги в монитор деградации (наблюдение). */
    private void flushClosedToMonitor() {
        List<TradeJournal.Entry> es = journal.entries();
        for (int i = journalCursor; i < es.size(); i++) monitor.record(es.get(i).pnlPct());
        journalCursor = es.size();
    }

    private boolean wouldBreachExposure() {
        return (engine.openSymbols().size() + 1) * cfg.positionFraction() > cfg.maxExposure();
    }

    // --- наблюдаемое состояние ---
    public boolean pauseSignalled() { return monitor.pauseSignalled(); }
    public int openPositions() { return engine.openCount(); }
    public int pendingApprovals() { return pending.size(); }
}
