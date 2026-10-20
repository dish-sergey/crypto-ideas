package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живой общий буфер в реестре (20.10.2026): резерв из ничейного, доклады, часовой шаг,
 * передачи между ботом и буфером. Деньги не появляются и не исчезают.
 */
class AllocPoolTest {

    private static final String U = AllocRegistry.POOL_CURRENCY;
    private static final long H = AllocRegistry.HOUR_MS;
    private static final long T0 = 1_000 * H;

    private AllocRegistry open(Path dir) {
        return new AllocRegistry(dir.resolve("alloc.db").toString());
    }

    @Test
    void addReservesBufferFromUnownedAndKeepsItAlive(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            assertTrue(r.claim("a", U, 30, 200, 1, T0));
            assertNull(r.poolCreate("mm", 5, 0.5, 3, true, T0));
            assertNull(r.poolAdd("mm", "a", 30, 200, T0));
            assertNull(r.poolAdd("mm", "b", 30, 200, T0));
            assertEquals(3.0, r.own("pool:mm", U), 1e-12, "5% от двух капиталов по 30");
            assertEquals(3.0, r.pool("mm").buffer(), 1e-12);
            // Через сутки без сердцебиения претензия a истекла, а буфер жив: ничейным он не стал.
            assertEquals(200 - 3, r.free(U, 200, T0 + 24 * H).free(), 1e-9);
            assertNotNull(r.poolAdd("mm", "a", 10, 200, T0), "второй раз в пул — отказ");
            assertNotNull(r.poolAdd("mm", "c", 10_000, 200, T0), "буфер больше ничейного — отказ");
        }
    }

    @Test
    void stepSplitsExcessEquallyAndTransfersMatchBuffer(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", U, 30, 200, 1, T0);
            r.claim("b", U, 30, 200, 1, T0);
            r.poolCreate("mm", 5, 0.5, 3, true, T0);
            r.poolAdd("mm", "a", 30, 200, T0);
            r.poolAdd("mm", "b", 30, 200, T0);
            assertNull(r.poolStep("mm", T0 + 10), "в том же часе шага нет");

            // a заработал 2.10, b потерял 0.10: прибыль a оседает в его кассе.
            r.applyFill("a", U, 2.10, T0 + 1000);
            r.applyFill("b", U, -0.10, T0 + 1000);
            r.poolReport("a", 2.10, T0 + 1000);
            r.poolReport("b", -0.10, T0 + 1000);
            assertNotNull(r.poolStep("mm", T0 + H + 5), "излишек 2.0 — капиталы сдвигаются");
            AllocRegistry.PoolMember a = r.poolOf("a");
            AllocRegistry.PoolMember b = r.poolOf("b");
            assertEquals(a.capital() - 30, b.capital() - 30, 1e-12, "поровну");
            double gained = a.capital() - 30 + b.capital() - 30;
            double buffer = r.pool("mm").buffer();
            assertEquals(3 + 2.0, gained + buffer, 1e-12, "буфер + прирост = резерв + принесённое");
            assertEquals(0.05 * (a.capital() + b.capital()), buffer, 1e-9, "буфер встал в цель");
            assertEquals(0, a.pending(), 1e-12);

            // Боты сводят кассу: цель = капитал (запаса нет).
            double moveA = r.poolTransfer("a", U, a.capital() - r.own("a", U), T0 + H + 60_000);
            double moveB = r.poolTransfer("b", U, b.capital() - r.own("b", U), T0 + H + 60_000);
            assertTrue(moveA < 0, "a отдаёт в буфер больше, чем получил");
            assertTrue(moveB > 0, "b получает свою долю и покрытие убытка");
            assertEquals(a.capital(), r.own("a", U), 1e-9);
            assertEquals(b.capital(), r.own("b", U), 1e-9);
            assertEquals(buffer, r.own("pool:mm", U), 1e-9, "на счёте буфера ровно учётный буфер");
        }
    }

    @Test
    void lossBeyondBufferCutsCapitalsAndTransfersAreBounded(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", U, 30, 200, 1, T0);
            r.claim("b", U, 30, 200, 1, T0);
            r.poolCreate("mm", 5, 0.5, 3, true, T0);
            r.poolAdd("mm", "a", 30, 200, T0);
            r.poolAdd("mm", "b", 30, 200, T0);
            r.applyFill("a", U, -5, T0 + 1000);
            r.poolReport("a", -5, T0 + 1000);
            r.poolStep("mm", T0 + H);
            AllocRegistry.PoolMember a = r.poolOf("a");
            assertEquals(30 - 1, a.capital(), 1e-9, "недостача 2 — поровну по 1");
            assertEquals(0, r.pool("mm").buffer(), 1e-9);
            // a просит покрыть 4 (капитал 29 при кассе 25), а в буфере 3 — берёт 3.
            assertEquals(3, r.poolTransfer("a", U, 29 - 25, T0 + H), 1e-9);
            assertEquals(0, r.own("pool:mm", U), 1e-12);
            // b отдаёт 1 срезанного.
            assertEquals(-1, r.poolTransfer("b", U, 29 - 30, T0 + H), 1e-9);
            assertEquals(1, r.own("pool:mm", U), 1e-9);
        }
    }

    @Test
    void setAndRemove(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.poolCreate("mm", 5, 0.5, 3, true, T0);
            r.poolAdd("mm", "a", 30, 200, T0);
            r.poolFunded("a");
            assertNull(r.poolSet("a", 40, T0));
            assertEquals(40, r.poolOf("a").capital0(), 1e-12);
            assertTrue(!r.poolOf("a").funded(), "после /pool set касса сводится с ничейным заново");
            assertEquals(10, r.poolFundFree("a", U, 10, 200, T0), 1e-12);
            assertNull(r.poolRemove("a", T0));
            assertNull(r.poolOf("a"));
            assertEquals(1.5, r.own("pool:mm", U), 1e-12, "буфер остался пулу");
        }
    }
}
