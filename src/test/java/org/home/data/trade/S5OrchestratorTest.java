package org.home.data.trade;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Каркас оркестратора на моке: полный дневной цикл — обнаружение → Approval Gate → открытие → выход. */
class S5OrchestratorTest {

    /** Изменяемый фид в памяти. */
    static final class FakeFeed implements EventFeed {
        final List<UnlockEvent> events = new ArrayList<>();
        public List<UnlockEvent> upcoming(long today) {
            List<UnlockEvent> o = new ArrayList<>();
            for (UnlockEvent e : events) if (e.unlockDay() > today) o.add(e);
            return o;
        }
        public List<UnlockEvent> dueForEntry(long today, int lead) {
            List<UnlockEvent> o = new ArrayList<>();
            for (UnlockEvent e : events) if (e.unlockDay() == today + lead) o.add(e);
            return o;
        }
    }

    private record Ctx(MockExchange ex, FakeFeed feed, ApprovalGate gate, StopEngine engine,
                       ScheduleTracker tracker, DegradationMonitor monitor, TradeJournal journal,
                       S5Orchestrator orch) {}

    private static Ctx ctx(FundingSource funding) {
        MockExchange ex = new MockExchange(1000);
        FakeFeed feed = new FakeFeed();
        ApprovalGate gate = new ApprovalGate();
        TradeJournal j = new TradeJournal();
        StopEngine engine = new StopEngine(ex, j, 0.30, 3);
        ScheduleTracker tracker = new ScheduleTracker();
        DegradationMonitor monitor = new DegradationMonitor();
        S5Orchestrator orch = new S5Orchestrator(ex, feed, funding, gate, engine, tracker, monitor, j, S5Config.protocol());
        return new Ctx(ex, feed, gate, engine, tracker, monitor, j, orch);
    }

    private static final FundingSource CHEAP = base -> 0.0;   // funding 0 → дешёвый шорт
    private static UnlockEvent ev(String base, long day) {
        return new UnlockEvent(base, "PF_" + base + "USD", day, 0.05, "investors");
    }

    @Test void fullCycleDiscoverApproveOpenExit() throws Exception {
        Ctx c = ctx(CHEAP);
        long today = 20000;
        c.ex.tick("PF_APTUSD", 10.0);
        c.feed.events.add(ev("APT", today + 5));                // разлок через 5 дней → вход сегодня

        List<UnlockEvent> submitted = c.orch.discover(today);
        assertEquals(1, submitted.size());
        assertEquals(1, c.orch.pendingApprovals());
        assertEquals(0, c.orch.openPositions(), "без подтверждения не открываем");

        assertEquals(0, c.orch.executeApproved(), "не подтверждено → 0");
        assertTrue(c.gate.approve("PF_APTUSD@" + (today + 5)));
        assertEquals(1, c.orch.executeApproved(), "подтверждено → открыто");
        assertEquals(1, c.orch.openPositions());
        assertEquals(0, c.orch.pendingApprovals());
        // сайзинг: 4.5% от $1000 / $10 = 4.5 контракта
        assertEquals(4.5, c.ex.positions().get(0).qty(), 1e-9);

        // в день разлока — плановый выход, монитор получает премию
        c.ex.tick("PF_APTUSD", 9.5);                            // цена упала → шорт в плюсе +5%
        c.orch.maintain(today + 5);
        assertEquals(0, c.orch.openPositions());
        assertEquals(1, c.journal.count(TradeJournal.Category.PLANNED_EXIT));
        assertEquals(1, c.monitor.count(), "премия закрытия записана в монитор");
    }

    @Test void expensiveShortFilteredOut() throws Exception {
        Ctx c = ctx(base -> -0.03);                            // funding −3%/5д → дорогой шорт (порог −1.5%)
        long today = 20000; c.ex.tick("PF_APTUSD", 10.0);
        c.feed.events.add(ev("APT", today + 5));
        assertTrue(c.orch.discover(today).isEmpty(), "дорогой шорт отфильтрован");
        assertEquals(0, c.orch.pendingApprovals());
    }

    @Test void exposureLimitBlocksExtraPositions() throws Exception {
        Ctx c = ctx(CHEAP);
        long today = 20000;
        // 0.045 * 18 = 0.81 (лимит). 19-я позиция превышает.
        for (int i = 0; i < 20; i++) { String b = "T" + i; c.ex.tick("PF_" + b + "USD", 10.0); c.feed.events.add(ev(b, today + 5)); }
        c.orch.discover(today);
        // подтвердить все и исполнять — откроется не больше лимита
        for (int i = 0; i < 20; i++) c.gate.approve("PF_T" + i + "USD@" + (today + 5));
        c.orch.executeApproved();
        assertEquals(18, c.orch.openPositions(), "18×4.5% = 81% ровно лимит; 19-я блокируется");
    }

    @Test void scheduleChangeClosesImmediately() throws Exception {
        Ctx c = ctx(CHEAP);
        long today = 20000;
        c.ex.tick("PF_APTUSD", 10.0);
        c.feed.events.add(ev("APT", today + 5));
        c.orch.discover(today);
        c.gate.approve("PF_APTUSD@" + (today + 5));
        c.orch.executeApproved();
        assertEquals(1, c.orch.openPositions());

        // за 2 дня до разлока команда перенесла дату
        c.feed.events.clear();
        c.feed.events.add(ev("APT", today + 40));              // новая дата
        c.orch.maintain(today + 3);
        assertEquals(0, c.orch.openPositions(), "перенос → закрыто немедленно");
        assertEquals(1, c.journal.count(TradeJournal.Category.UNLOCK_CANCELLED));
        assertEquals(1, c.tracker.revisions().size());
    }

    @Test void recoverAdoptsPositionsFromExchange() throws Exception {
        // на бирже уже есть шорт (как после перезапуска процесса)
        MockExchange ex = new MockExchange(1000);
        ex.tick("PF_APTUSD", 10.0);
        ex.openShort("PF_APTUSD", 4.5);
        TradeJournal j = new TradeJournal();
        StopEngine engine = new StopEngine(ex, j, 0.30, 3);
        var orch = new S5Orchestrator(ex, new FakeFeed(), CHEAP, new ApprovalGate(), engine,
                new ScheduleTracker(), new DegradationMonitor(), j, S5Config.protocol());
        assertEquals(0, orch.openPositions(), "в памяти пусто");
        orch.recover();
        assertEquals(1, orch.openPositions(), "усыновлено с биржи");
        // и стоп теперь работает по восстановленной позиции
        ex.tick("PF_APTUSD", 13.0);
        orch.pollStops();
        assertEquals(1, j.count(TradeJournal.Category.STOP));
    }

    @Test void stopPollFeedsMonitor() throws Exception {
        Ctx c = ctx(CHEAP);
        long today = 20000; c.ex.tick("PF_APTUSD", 10.0);
        c.feed.events.add(ev("APT", today + 5));
        c.orch.discover(today); c.gate.approve("PF_APTUSD@" + (today + 5)); c.orch.executeApproved();
        c.ex.tick("PF_APTUSD", 13.0);                          // +30% → стоп
        c.orch.pollStops();
        assertEquals(1, c.journal.count(TradeJournal.Category.STOP));
        assertEquals(1, c.monitor.count());
        assertEquals(-0.30, c.monitor.rolling(1) == null ? 0 : c.journal.entries().get(0).pnlPct(), 1e-9);
    }
}
