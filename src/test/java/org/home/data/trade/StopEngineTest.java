package org.home.data.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Фаза 0 на синтетике (doc 57 §4–§5): вместо выведенной песочницы Kraken прогоняем стоп-логику,
 * отмену разлока, Approval Gate и реконнект на {@link MockExchange}. Покрывает критерии выхода §5.
 */
class StopEngineTest {

    private static final double STOP = 0.30;

    private StopEngine engine(MockExchange ex, TradeJournal j) { return new StopEngine(ex, j, STOP, 3); }

    private static ApprovalGate approved(String id) {
        ApprovalGate g = new ApprovalGate(); g.submit(id); g.approve(id); return g;
    }

    // ---- Approval Gate: ни одного ордера в обход (§4.5) ----
    @Test void approvalGateBlocksUnapprovedOrder() throws Exception {
        MockExchange ex = new MockExchange(1000); ex.tick("APTUSD", 10.0);
        TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ApprovalGate gate = new ApprovalGate(); gate.submit("ev1"); // submitted but NOT approved

        assertFalse(e.openShort("ev1", gate, "APTUSD", 4.5), "без подтверждения ордер не идёт");
        assertEquals(0, e.openCount());
        assertTrue(ex.positions().isEmpty(), "позиции быть не должно");

        assertTrue(gate.approve("ev1"));
        assertTrue(e.openShort("ev1", gate, "APTUSD", 4.5));
        assertEquals(1, e.openCount());
    }

    // ---- ≥20 корректных срабатываний стопа (§5) ----
    @Test void stopTriggersTwentyTimes() throws Exception {
        MockExchange ex = new MockExchange(100000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        for (int i = 0; i < 20; i++) {
            String sym = "TOK" + i; double entry = 10 + i;
            ex.tick(sym, entry);
            assertTrue(e.openShort("ev" + i, approved("ev" + i), sym, 4.5));
            ex.tick(sym, entry * 1.30); // +30% против шорта = порог
            e.poll();
        }
        assertEquals(20, j.count(TradeJournal.Category.STOP), "20 корректных стопов");
        assertEquals(0, e.openCount(), "все закрыты");
        assertTrue(ex.positions().isEmpty());
        // pnl шорта на стопе ≈ −30%
        j.entries().forEach(en -> assertEquals(-0.30, en.pnlPct(), 1e-9));
    }

    // ---- Внутридневной максимум удерживает режим «надо закрыть» даже после отката цены ----
    @Test void runningMaxKeepsMustCloseAfterRecovery() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.rejectNextCloses(4);                // первая волна закрытий полностью проваливается
        ex.tick("X", 130); e.poll();           // пробой на 130, закрыть не удалось
        assertEquals(1, j.count(TradeJournal.Category.CLOSE_FAILED));
        assertEquals(1, e.openCount());
        ex.tick("X", 110); e.poll();           // цена откатилась к +10% (<порога), но max=130 → всё равно закрываем
        assertEquals(0, e.openCount(), "позиция закрыта по удержанному максимуму");
        assertEquals(1, j.count(TradeJournal.Category.STOP));
    }

    // ---- Спайк ЗА уровень стопа классифицируется как гэп ----
    @Test void jumpPastStopLevelIsGap() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.tick("X", 135); e.poll();           // цена уже на +35% при опросе — стоп проскочен
        assertEquals(1, j.count(TradeJournal.Category.STOP_GAP));
        assertEquals(0, e.openCount());
    }

    // ---- Сбой 1: гэп через порог фиксирует фактическое проскальзывание (§4.2) ----
    @Test void gapThroughStopRecordsSlippage() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.setCloseSlippage(0.10);            // закрытие исполнится на +10% выше mark
        ex.tick("X", 130); e.poll();          // mark 130 → fill 143 → факт −43%
        assertEquals(1, j.count(TradeJournal.Category.STOP_GAP));
        TradeJournal.Entry en = j.entries().get(0);
        assertTrue(en.pnlPct() < -0.40, "убыток больше порога зафиксирован: " + en.pnlPct());
    }

    // ---- Сбой 2: закрывающий ордер отклонён → ретраи → успех ----
    @Test void closeRejectedThenRetriesSucceed() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.rejectNextCloses(2);               // 2 отказа, третья попытка проходит
        ex.tick("X", 130); e.poll();
        assertEquals(1, j.count(TradeJournal.Category.STOP));
        assertEquals(0, e.openCount());
    }

    // ---- Сбой 3: закрытие отклонено сверх ретраев → эскалация, позиция не снята ----
    @Test void closeFailsBeyondRetriesEscalates() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.rejectNextCloses(4);               // ровно исчерпывает maxRetries+1 попыток
        ex.tick("X", 130); e.poll();
        assertEquals(1, j.count(TradeJournal.Category.CLOSE_FAILED));
        assertEquals(1, j.alerts().size(), "должен быть алерт");
        assertEquals(1, e.openCount(), "позиция остаётся для докрытия");
        // следующий poll докрывает (отказы кончились)
        e.poll();
        assertEquals(1, j.count(TradeJournal.Category.STOP));
        assertEquals(0, e.openCount());
    }

    // ---- Сбой 4: позиция уже закрыта вне системы → без двойного закрытия ----
    @Test void alreadyClosedNoDoubleClose() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.closeExternally("X");              // закрыто вручную/ADL
        ex.tick("X", 130); e.poll();
        assertEquals(1, j.count(TradeJournal.Category.ALREADY_CLOSED));
        assertEquals(0, j.count(TradeJournal.Category.STOP));
        assertEquals(0, e.openCount());
    }

    // ---- Сбой 5: разрыв соединения в момент срабатывания → докрытие после реконнекта ----
    @Test void disconnectAtStopThenReconnectCloses() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.tick("X", 130); ex.disconnect();
        e.poll();                              // no-op под разрывом
        assertEquals(0, j.size(), "под разрывом ничего не закрыто");
        assertEquals(1, e.openCount());
        ex.reconnect(); e.reconnect();         // восстановление → докрытие
        assertEquals(1, j.count(TradeJournal.Category.RECONNECT_CLOSE));
        assertEquals(0, e.openCount());
    }

    // ---- Отмена разлока после входа → немедленное закрытие, отдельная категория (§4.3, ≥3 раза) ----
    @Test void unlockCancelledClosesImmediately() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        for (int i = 0; i < 3; i++) {
            String s = "U" + i; ex.tick(s, 50);
            assertTrue(e.openShort("u" + i, approved("u" + i), s, 1));
            e.cancelUnlock(s);                 // разлок перенесён — закрыть, хотя стоп не сработал
        }
        assertEquals(3, j.count(TradeJournal.Category.UNLOCK_CANCELLED));
        assertEquals(0, e.openCount());
    }

    // ---- Плановый выход ----
    @Test void plannedExitRecorded() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.tick("X", 95); e.closePlanned("X"); // цена упала — шорт в плюсе
        assertEquals(1, j.count(TradeJournal.Category.PLANNED_EXIT));
        assertEquals(0.05, j.entries().get(0).pnlPct(), 1e-9);
    }

    // ---- Позиция без пробоя порога стоп не трогает ----
    @Test void noStopBelowThreshold() throws Exception {
        MockExchange ex = new MockExchange(1000); TradeJournal j = new TradeJournal(); StopEngine e = engine(ex, j);
        ex.tick("X", 100); assertTrue(e.openShort("e", approved("e"), "X", 1));
        ex.tick("X", 125); e.poll();           // +25% < 30%
        assertEquals(0, j.size());
        assertEquals(1, e.openCount());
    }
}
