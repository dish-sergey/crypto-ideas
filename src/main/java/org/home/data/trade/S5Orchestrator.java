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
    /** eventId, по которым уже слали «недостаточно средств» — не спамить каждый цикл. */
    private final java.util.Set<String> underfundedNotified = new java.util.HashSet<>();
    private final ApprovalReminders reminders = new ApprovalReminders();
    private int journalCursor = 0;
    private boolean pauseNotified = false;
    /** Последний снимок ближайших разлоков (для команды /unlocks); обновляется в discover. */
    private volatile List<UnlockEvent> upcomingSnapshot = List.of();

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
        List<UnlockEvent> up = feed.upcoming(today);
        upcomingSnapshot = up;                              // снимок для команды /unlocks
        // окно чуть шире дня входа (approvalLeadDays) — кандидат всплывает заранее, чтобы успела эскалация
        // напоминаний; сам вход всё равно на unlockDay−entryLead. Плюс ускоренные разлоки (doc 59 §4).
        long horizon = today + cfg.entryLead() + cfg.approvalLeadDays();
        for (UnlockEvent e : up) {
            if (e.unlockDay() > horizon) continue;          // вне окна входа
            String id = eventId(e);
            if (engine.openSymbols().contains(e.krakenSymbol()) || pending.containsKey(id) || dismissed.contains(id)) continue;
            if (funding.estimate5dFunding(e.base()) < cfg.expensiveFundingThreshold()) continue; // дорогой шорт
            if (wouldBreachExposure()) continue;
            gate.submit(id);
            pending.put(id, e);
            submitted.add(e);
            long daysToEntry = (e.unlockDay() - cfg.entryLead()) - today;
            String when = daysToEntry <= 0 ? "сегодня" : ("через " + daysToEntry + " дн");
            notifier.push(Alert.approval("Нужно подтверждение",
                    e.krakenSymbol() + ": " + e.pctLabel() + " circ — вход " + when + sizingLine(e), id));
        }
        return submitted;
    }

    /** Расчёт размера позиции и минимума ордера — источник истины для сообщений и проверки средств. */
    private record Sizing(double px, double bal, double minSize, double qty,
                          double notional, double minNotional, double minBalance, boolean ok) {}

    private Sizing sizing(UnlockEvent e) throws Exception {
        double px = ex.mark(e.krakenSymbol());
        double bal = ex.balance();
        double minSize = ex.minOrderSize(e.krakenSymbol());
        double raw = px > 0 ? bal * cfg.positionFraction() / px : 0;
        double qty = minSize > 0 ? Math.floor(raw / minSize) * minSize : raw;   // округлить вниз к шагу лота
        double minNotional = minSize * px;
        double minBalance = cfg.positionFraction() > 0 ? minNotional / cfg.positionFraction() : 0;
        boolean ok = px > 0 && qty > 0 && (minSize <= 0 || qty >= minSize);
        return new Sizing(px, bal, minSize, qty, qty * px, minNotional, minBalance, ok);
    }

    /** Строка для сообщения оператору: размер позиции, мин. ордер и сколько нужно на счёте. */
    private String sizingLine(UnlockEvent e) throws Exception {
        Sizing s = sizing(e);
        if (s.px() <= 0) return "";
        String line = "\nразмер 4.5%: " + num(s.qty()) + " (~$" + usd(s.notional()) + ")";
        if (s.minSize() > 0)
            line += "\nмин. ордер: " + num(s.minSize()) + " (~$" + usd(s.minNotional())
                    + "), нужно ≥ $" + usd(s.minBalance()) + " на счёте";
        line += s.ok() ? "\nбаланс $" + usd(s.bal()) + " — хватает ✓"
                       : "\n⚠️ баланс $" + usd(s.bal()) + " — НЕ хватает для входа";
        return line;
    }

    /**
     * Эскалация напоминаний по неподтверждённым кандидатам (вызывать часто, {@code nowSec} — сейчас).
     * Момент входа = день (unlockDay−entryLead), 00:00 UTC. Подтверждённые — молчим.
     */
    public void pollReminders(long nowSec) {
        for (var en : pending.entrySet()) {
            String id = en.getKey(); UnlockEvent e = en.getValue();
            if (gate.isApproved(id)) { reminders.clear(id); continue; }
            long entryInstant = (e.unlockDay() - cfg.entryLead()) * 86400L;
            Integer h = reminders.due(id, entryInstant, nowSec);
            if (h != null) notifier.push(Alert.approval("Напоминание: подтвердить (≈" + h + "ч до входа)",
                    e.krakenSymbol() + ": " + e.pctLabel() + " circ", id));
        }
    }

    /** Немедленное исполнение подтверждённых (ручной/тестовый путь): без гейта по дню входа. */
    public int executeApproved() throws Exception { return exec(0, false); }

    /**
     * Исполнение с гейтом по дню входа (демон): подтверждённое открывается только в день входа
     * (unlockDay−entryLead) или позже; неподтверждённое после дня входа — снимается как пропущенное.
     */
    public int executeApproved(long today) throws Exception { return exec(today, true); }

    private int exec(long today, boolean gated) throws Exception {
        int opened = 0;
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            String id = en.getKey(); UnlockEvent e = en.getValue();
            long entryDay = e.unlockDay() - cfg.entryLead();
            if (gate.isApproved(id)) {
                if (gated && today < entryDay) continue;        // подтверждено рано — ждём дня входа
                if (wouldBreachExposure()) continue;
                Sizing s = sizing(e);
                if (s.px() <= 0) continue;
                if (!s.ok()) {                                  // средств не хватает на мин. ордер
                    if (underfundedNotified.add(id))            // уведомить один раз, не спамить каждый цикл
                        notifier.push(Alert.warn("Недостаточно средств для входа",
                                e.krakenSymbol() + ": на счёте $" + usd(s.bal())
                                        + ", нужно ≥ $" + usd(s.minBalance()) + " (мин. ордер " + num(s.minSize())
                                        + "). Вход отложен — пополни счёт."));
                    continue;                                   // оставляем pending: при пополнении откроется
                }
                if (engine.openShort(id, gate, e.krakenSymbol(), s.qty())) {
                    tracker.onEntry(e.krakenSymbol(), e.unlockDay(), e.category());
                    reminders.clear(id);
                    underfundedNotified.remove(id);
                    notifier.push(Alert.info("Открыт шорт",
                            e.krakenSymbol() + " qty=" + num(s.qty()) + " (~$" + usd(s.notional()) + ") @ " + num(s.px())));
                    it.remove();
                    opened++;
                }
            } else if (gated && today >= e.unlockDay()) {        // разлок наступил без подтверждения — снять
                reminders.clear(id);
                dismissed.add(id);
                notifier.push(Alert.info("Вход пропущен",
                        e.krakenSymbol() + " — не подтверждён до разлока, событие снято"));
                it.remove();
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
    private static String usd(double v)    { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    // --- наблюдаемое состояние ---
    public boolean pauseSignalled() { return monitor.pauseSignalled(); }
    public int openPositions() { return engine.openCount(); }
    public int pendingApprovals() { return pending.size(); }

    /** Снимок для запроса «/status» с телефона. */
    public StatusSnapshot status() {
        return new StatusSnapshot(engine.openCount(), pending.size(), monitor.pauseSignalled(), journalCursor);
    }

    /** Текст ближайших разлоков для команды /unlocks (из снимка последнего скана). */
    public String upcomingText(long today) {
        List<UnlockEvent> up = upcomingSnapshot;
        if (up.isEmpty()) return "Ближайшие разлоки: список ещё не собран (скан раз в день). Загляни позже.";
        StringBuilder sb = new StringBuilder("Ближайшие клифф-разлоки ≥3% на Kraken:");
        int n = 0;
        for (UnlockEvent e : up) {
            if (n++ >= 15) break;
            sb.append("\n").append(e.krakenSymbol()).append("  через ")
              .append(e.unlockDay() - today).append("д  ").append(e.pctLabel());
        }
        int lead = cfg.entryLead();
        long horizon = today + lead + cfg.approvalLeadDays();
        long inWindow = up.stream().filter(e -> e.unlockDay() <= horizon).count();
        sb.append("\n\nвсего впереди: ").append(up.size())
          .append(", в окне входа: ").append(inWindow);
        return sb.toString();
    }

    /** Открытые позиции (для «/positions» с телефона) — источник истины биржа. */
    public List<Position> positions() throws Exception { return ex.positions(); }

    // --- ручное подтверждение с телефона (кнопки Telegram) ---

    /** Результат нажатия «Подтвердить»: принято ли + текст-ответ оператору. */
    public record ApproveOutcome(boolean approved, String message) {}

    /**
     * Кнопка «Подтвердить»: одобряем ТОЛЬКО если на счёте хватает на мин. ордер (иначе подтверждение
     * бессмысленно — вход всё равно отклонится). При нехватке возвращаем понятный отказ, событие
     * остаётся в ожидании — можно подтвердить снова после пополнения.
     */
    public ApproveOutcome approve(String eventId) throws Exception {
        UnlockEvent e = pending.get(eventId);
        if (e == null) return new ApproveOutcome(false, "уже неактуально");
        Sizing s = sizing(e);
        if (s.px() <= 0) return new ApproveOutcome(false, "нет цены по " + e.krakenSymbol() + ", попробуй позже");
        if (!s.ok()) return new ApproveOutcome(false,
                "❌ Не подтверждено: на счёте $" + usd(s.bal()) + ", для " + e.krakenSymbol()
                        + " нужно ≥ $" + usd(s.minBalance()) + " (мин. ордер " + num(s.minSize())
                        + "). Пополни счёт и подтверди снова.");
        gate.approve(eventId);
        return new ApproveOutcome(true, "✅ Подтверждено: " + e.krakenSymbol()
                + ", вход " + num(s.qty()) + " (~$" + usd(s.notional()) + ") в день −5.");
    }

    /** Кнопка «Отклонить»: снять кандидата — не откроем и в следующем discover не переотправим. */
    public boolean reject(String eventId) {
        gate.reject(eventId);
        dismissed.add(eventId);
        reminders.clear(eventId);
        underfundedNotified.remove(eventId);
        return pending.remove(eventId) != null;
    }
}
