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
    private final Notifier notifier;

    /** eventId -> событие, поданное в Approval Gate и ждущее подтверждения. */
    private final Map<String, UnlockEvent> pending = new LinkedHashMap<>();
    /** Отклонённые оператором eventId — чтобы дневной цикл не переотправлял их снова. */
    private final java.util.Set<String> dismissed = new java.util.HashSet<>();
    private int journalCursor = 0;
    private boolean pauseNotified = false;

    /** Без канала уведомлений (уведомления не шлём). */
    public S5Orchestrator(ExchangeAdapter ex, EventFeed feed, FundingSource funding, ApprovalGate gate,
                          StopEngine engine, ScheduleTracker tracker, DegradationMonitor monitor,
                          TradeJournal journal, S5Config cfg) {
        this(ex, feed, funding, gate, engine, tracker, monitor, journal, cfg, Notifier.NONE);
    }

    public S5Orchestrator(ExchangeAdapter ex, EventFeed feed, FundingSource funding, ApprovalGate gate,
                          StopEngine engine, ScheduleTracker tracker, DegradationMonitor monitor,
                          TradeJournal journal, S5Config cfg, Notifier notifier) {
        this.ex = ex; this.feed = feed; this.funding = funding; this.gate = gate; this.engine = engine;
        this.tracker = tracker; this.monitor = monitor; this.journal = journal; this.cfg = cfg;
        this.notifier = notifier;
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
        // окно (today, today+lead]: обычный вход за 5 дней + ускоренные разлоки (doc 59 §4)
        for (UnlockEvent e : feed.enterableWithin(today, cfg.entryLead())) {
            String id = eventId(e);
            if (engine.openSymbols().contains(e.krakenSymbol()) || pending.containsKey(id) || dismissed.contains(id)) continue;
            if (funding.estimate5dFunding(e.base()) < cfg.expensiveFundingThreshold()) continue; // дорогой шорт
            if (wouldBreachExposure()) continue;
            gate.submit(id);
            pending.put(id, e);
            submitted.add(e);
            notifier.push(Alert.approval("Нужно подтверждение",
                    e.krakenSymbol() + ": разлок " + e.category() + " "
                            + String.format(java.util.Locale.ROOT, "%.1f%%", e.pctCirculating() * 100)
                            + " circ — вход по протоколу сегодня", id));
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
                tracker.onEntry(e.krakenSymbol(), e.unlockDay(), e.category());
                notifier.push(Alert.info("Открыт шорт",
                        e.krakenSymbol() + " qty=" + num(qty) + " @ " + num(px)));
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

    /** Записать премию каждой закрытой ноги в монитор + пуш о закрытии; пуш при уходе монитора в паузу. */
    private void flushClosedToMonitor() {
        List<TradeJournal.Entry> es = journal.entries();
        for (int i = journalCursor; i < es.size(); i++) {
            TradeJournal.Entry e = es.get(i);
            monitor.record(e.pnlPct());
            notifier.push(alertFor(e));
        }
        journalCursor = es.size();
        if (monitor.pauseSignalled() && !pauseNotified) {
            pauseNotified = true;
            notifier.push(Alert.warn("Монитор деградации: ПАУЗА",
                    "Премия ушла в минус (rolling-40<0 или два подряд rolling-20<0). Новые входы остановить, механизм пересмотреть."));
        } else if (!monitor.pauseSignalled()) {
            pauseNotified = false;
        }
    }

    /** Уведомление по закрытой ноге — по категории журнала. */
    private static Alert alertFor(TradeJournal.Entry e) {
        String pnl = pct(e.pnlPct());
        return switch (e.category()) {
            case PLANNED_EXIT     -> Alert.info("Плановый выход", e.symbol() + " " + pnl);
            case STOP             -> Alert.warn("Стоп сработал", e.symbol() + " " + pnl);
            case STOP_GAP         -> Alert.warn("Стоп с гэпом", e.symbol() + " " + pnl + " (проскальзывание за порог)");
            case UNLOCK_CANCELLED -> Alert.warn("Разлок перенесён/отменён", e.symbol() + " закрыто " + pnl);
            case RECONNECT_CLOSE  -> Alert.warn("Докрыто после реконнекта", e.symbol() + " " + pnl);
            case ALREADY_CLOSED   -> Alert.info("Позиция уже закрыта вне системы", e.symbol());
            case CLOSE_FAILED     -> Alert.critical("ЗАКРЫТИЕ НЕ УДАЛОСЬ", e.symbol() + " — требуется ручное вмешательство");
        };
    }

    private boolean wouldBreachExposure() {
        return (engine.openSymbols().size() + 1) * cfg.positionFraction() > cfg.maxExposure();
    }

    private static String pct(double frac) { return String.format(java.util.Locale.ROOT, "%+.1f%%", frac * 100); }
    private static String num(double v)    { return String.format(java.util.Locale.ROOT, "%.4f", v); }

    // --- наблюдаемое состояние ---
    public boolean pauseSignalled() { return monitor.pauseSignalled(); }
    public int openPositions() { return engine.openCount(); }
    public int pendingApprovals() { return pending.size(); }

    /** Снимок для запроса «/status» с телефона. */
    public StatusSnapshot status() {
        return new StatusSnapshot(engine.openCount(), pending.size(), monitor.pauseSignalled(), journalCursor);
    }

    /** Открытые позиции (для «/positions» с телефона) — источник истины биржа. */
    public List<Position> positions() throws Exception { return ex.positions(); }

    // --- ручное подтверждение с телефона (кнопки Telegram) ---

    /** Кнопка «Подтвердить»: одобрить кандидата. true — если он ждал подтверждения. */
    public boolean approve(String eventId) { return gate.approve(eventId); }

    /** Кнопка «Отклонить»: снять кандидата — не откроем и в следующем discover не переотправим. */
    public boolean reject(String eventId) {
        gate.reject(eventId);
        dismissed.add(eventId);
        return pending.remove(eventId) != null;
    }
}
