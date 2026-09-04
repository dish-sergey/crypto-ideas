package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Реестр владения инвентарём. Числа живые: лот 0.0000125 BTC, остаток счёта
 * 0.00031074 при сумме претензий трёх ботов 0.00030000 (замер 04.09.2026).
 */
class AllocRegistryTest {

    private static final String BTC = "BTC";
    private static final double LOT = 0.0000125;
    private static final double TOTAL = 20 * LOT;
    private static final long T0 = 1_000_000_000L;

    private AllocRegistry open(Path dir) {
        return new AllocRegistry(dir.resolve("alloc.db").toString());
    }

    @Test
    void claimTakesFromTheFreePool(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            assertEquals(TOTAL, r.free(BTC, TOTAL, T0).free(), 1e-15, "сначала свободно всё");
            assertTrue(r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0));
            assertEquals(8 * LOT, r.own("a", BTC), 1e-15);
            assertEquals(12 * LOT, r.free(BTC, TOTAL, T0).free(), 1e-15);
        }
    }

    @Test
    void cannotClaimMoreThanFree(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            assertTrue(r.claim("a", BTC, 15 * LOT, TOTAL, 80_000, T0));
            assertFalse(r.claim("b", BTC, 10 * LOT, TOTAL, 80_000, T0),
                    "свободно только пять лотов — десять брать нельзя");
            assertEquals(0.0, r.own("b", BTC), 1e-15);
        }
    }

    @Test
    void liveClaimOfAnotherBotIsNotFree(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            // Минуту спустя аренда ещё жива.
            AllocRegistry.Free f = r.free(BTC, TOTAL, T0 + 60_000);
            assertEquals(8 * LOT, f.claimedLive(), 1e-15);
            assertEquals(0.0, f.claimedExpired(), 1e-15);
            assertEquals(12 * LOT, f.free(), 1e-15);
        }
    }

    @Test
    void expiredClaimBecomesFreeButIsNotErasedUntilSomeoneComes(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            long later = T0 + AllocRegistry.LEASE_MS + 1;
            AllocRegistry.Free f = r.free(BTC, TOTAL, later);
            assertEquals(0.0, f.claimedLive(), 1e-15, "аренда истекла");
            assertEquals(8 * LOT, f.claimedExpired(), 1e-15);
            assertEquals(TOTAL, f.free(), 1e-15, "инвентарь мёртвого бота свободен");
            // Но строка ЖИВА: за инвентарём никто не пришёл, и хозяин может вернуться.
            assertEquals(8 * LOT, r.own("a", BTC), 1e-15,
                    "истечение аренды не стирает претензию само по себе");
            assertNull(r.seizedPrice("a", BTC, 0), "изъятия не было");
        }
    }

    @Test
    void claimingDissolvesExpiredClaimsAndRecordsThePriceOfSEIZURE(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            long later = T0 + AllocRegistry.LEASE_MS + 1;
            // Бот B приходит за инвентарём, когда рынок уже на 81 000.
            assertTrue(r.claim("b", BTC, 8 * LOT, TOTAL, 81_000, later));
            assertEquals(0.0, r.own("a", BTC), 1e-15, "претензия A распущена");
            assertEquals(8 * LOT, r.own("b", BTC), 1e-15);
            Double seized = r.seizedPrice("a", BTC, 0);
            assertEquals(81_000.0, seized, 1e-9,
                    "списывать надо по цене МОМЕНТА ИЗЪЯТИЯ, а не текущей");
        }
    }

    @Test
    void heartbeatKeepsTheClaimAlive(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            long later = T0 + AllocRegistry.LEASE_MS + 1;
            r.heartbeat("a", BTC, later);
            assertEquals(8 * LOT, r.free(BTC, TOTAL, later + 1000).claimedLive(), 1e-15,
                    "продление спасает претензию");
            assertEquals(12 * LOT, r.free(BTC, TOTAL, later + 1000).free(), 1e-15);
        }
    }

    @Test
    void ownFillsMoveThePositionDuringOperation(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            r.applyFill("a", BTC, LOT, T0 + 1000);         // купил лот
            assertEquals(9 * LOT, r.own("a", BTC), 1e-15);
            r.applyFill("a", BTC, -2 * LOT, T0 + 2000);    // продал два
            assertEquals(7 * LOT, r.own("a", BTC), 1e-15);
        }
    }

    @Test
    void releaseGivesEverythingBack(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
            r.release("a", BTC, 80_500, T0 + 1000);
            assertEquals(0.0, r.own("a", BTC), 1e-15);
            assertEquals(TOTAL, r.free(BTC, TOTAL, T0 + 1000).free(), 1e-15);
        }
    }

    @Test
    void invariantCatchesClaimsExceedingTheAccount(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 10 * LOT, TOTAL, 80_000, T0);
            assertNull(r.checkInvariant(BTC, TOTAL, T0), "пока всё сходится");
            // Остаток счёта упал (вывели монеты) — претензии стали больше него.
            String problem = r.checkInvariant(BTC, 5 * LOT, T0);
            assertTrue(problem != null && problem.contains("расхождение"),
                    "нарушение инварианта обязано быть названо: " + problem);
        }
    }

    @Test
    void survivesReopening(@TempDir Path dir) {
        try (AllocRegistry r = open(dir)) {
            r.claim("a", BTC, 8 * LOT, TOTAL, 80_000, T0);
        }
        try (AllocRegistry again = open(dir)) {
            assertEquals(8 * LOT, again.own("a", BTC), 1e-15,
                    "реестр обязан пережить перезапуск — иначе он бесполезен");
        }
    }
}
