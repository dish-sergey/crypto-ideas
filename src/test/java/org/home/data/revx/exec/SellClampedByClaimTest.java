package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Продавать можно только СВОЁ — по реестру, а не по внутреннему счётчику.
 *
 * <h2>Зачем этот тест</h2>
 *
 * ⚠️ 11.09.2026 бот C продал лот остановленного соседа по той же паре. Потолок
 * продажи стоял только по {@code inventory} — производной величине, которая
 * расходится с реальностью как минимум тремя найденными в тот же день путями
 * (потерянное исполнение на третьей 422, невыясненная судьба заявки при
 * неудачном GET, перезапуск).
 *
 * Бот при этом САМ написал в журнал «РАСХОЖДЕНИЕ: своя позиция 0.00120795
 * больше остатка аккаунта 0.0008053» — и через 38 секунд продал 0.0008053 при
 * собственном захвате 0.00040265.
 *
 * Реестр знает, что чьё, и обновляется на каждом исполнении. Покупка по нему
 * ограничивалась и раньше; продажа — нет.
 */
class SellClampedByClaimTest {

    private static final String ETH = "ETH";
    private static final long T0 = 1_000_000_000L;

    /** Та же арифметика потолка, что в {@code QuoteLoop.sizeFor} для продажи. */
    private static double sellCap(double want, double inventory, double claim) {
        double ownPositionCap = Math.max(0, inventory);
        double ownClaimCap = Math.max(0, claim);
        return Math.min(Math.min(want, ownClaimCap), ownPositionCap);
    }

    @Test
    void driftedCounterCannotSellTheNeighboursLot(@TempDir Path dir) {
        try (AllocRegistry reg = new AllocRegistry(dir.resolve("alloc.db").toString())) {
            // На счёте два лота: один захвачен ботом c, другой — остановленным d.
            assertTrue(reg.claim("c", ETH, 0.00040265, 0.00080530, 2500, T0));
            assertTrue(reg.claim("d", ETH, 0.00040265, 0.00080530, 2500, T0));

            // Счётчик бота c разошёлся и показывает целый лот — втрое больше.
            double drifted = 0.00120795;
            double sell = sellCap(drifted, drifted, reg.own("c", ETH));

            assertEquals(0.00040265, sell, 1e-12,
                    "продать можно только свой захват, а не то, что насчитал счётчик");
        }
    }

    @Test
    void honestCounterIsNotPunished(@TempDir Path dir) {
        try (AllocRegistry reg = new AllocRegistry(dir.resolve("alloc.db").toString())) {
            assertTrue(reg.claim("c", ETH, 0.00120795, 0.00120795, 2500, T0));
            // Счётчик и захват согласны — потолок не мешает.
            assertEquals(0.00120795, sellCap(0.00120795, 0.00120795, reg.own("c", ETH)), 1e-12);
        }
    }

    @Test
    void counterBelowClaimStillLimits(@TempDir Path dir) {
        // Обратный случай: захват больше, чем счётчик. Продаём по счётчику —
        // иначе отдали бы то, чего у бота фактически нет.
        try (AllocRegistry reg = new AllocRegistry(dir.resolve("alloc.db").toString())) {
            assertTrue(reg.claim("c", ETH, 0.00120795, 0.00120795, 2500, T0));
            assertEquals(0.00040265, sellCap(0.00120795, 0.00040265, reg.own("c", ETH)), 1e-12);
        }
    }
}
