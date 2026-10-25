package org.home.data.revx.replay;

import org.home.data.revx.exec.ActiveOrder;
import org.home.data.revx.exec.Venue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эффект {@code lag} (док. 193 шаг 2): в окне затыка замена вступает в силу в конце окна,
 * а до того заявка стоит по СТАРОЙ цене и исполняется — бот «ловит первый удар».
 */
class SimVenueLagTest {

    @TempDir
    Path dir;

    /** Принт продавца по 100.5 с момента {@code at}: берёт любую покупку по 100.5 и выше. */
    private static final class SellPrint implements FillModel {
        private final long at;

        SellPrint(long at) {
            this.at = at;
        }

        @Override
        public String describe() {
            return "принт 100.5";
        }

        @Override
        public List<Filled> advance(long nowMs, List<Resting> resting) {
            List<Filled> out = new ArrayList<>();
            if (nowMs >= at) {
                for (Resting r : resting) {
                    if (r.buy() && r.price() >= 100.5) {
                        out.add(new Filled(r.id(), r.size(), r.price()));
                    }
                }
            }
            return out;
        }
    }

    private static String id(Venue.Response r) {
        return r.body().replaceAll(".*\"venue_order_id\"\s*:\s*\"([^\"]+)\".*", "$1");
    }

    private SimVenue venue(SimClock clock, long printAt, String effects) throws Exception {
        Path sched = dir.resolve("stalls.txt");
        Files.writeString(sched, "1001000 1005000\n");
        System.setProperty("revx.sim.stall-schedule", sched.toString());
        System.setProperty("revx.sim.stall-effects", effects);
        System.setProperty("revx.sim.ghost-at-sec", "-1");
        try {
            return new SimVenue(clock, new SellPrint(printAt), "SOL/USDC", 0, 100, 0.1);
        } finally {
            System.clearProperty("revx.sim.stall-schedule");
            System.clearProperty("revx.sim.stall-effects");
            System.clearProperty("revx.sim.ghost-at-sec");
        }
    }

    private static String placeBid(SimVenue v) {
        Venue.Response r = v.place("{\"client_order_id\":\"c1\",\"symbol\":\"SOL-USDC\",\"side\":\"buy\","
                + "\"order_configuration\":{\"limit\":{\"base_size\":\"0.1\",\"price\":\"101\"}}}");
        assertTrue(r.ok(), r.body());
        return id(r);
    }

    @Test
    void replaceInStallLeavesOldPriceExposed() throws Exception {
        SimClock clock = new SimClock(1_000_000);
        SimVenue v = venue(clock, 1_003_000, "lag");
        String first = placeBid(v);
        clock.sleep(2_000);                                   // 1 002 000 — внутри окна затыка
        Venue.Response r = v.replace(first, "{\"client_order_id\":\"c2\",\"base_size\":\"0.1\",\"price\":\"99\"}");
        assertTrue(r.ok(), r.body());
        assertEquals(3_000, r.latencyMs(), "замена отвечает в конце окна");
        String next = id(r);
        // заявка в книге — по старой цене, но под новым идентификатором
        ActiveOrder shown = ActiveOrder.parse(v.activeOrders().body()).getFirst();
        assertEquals(next, shown.id());
        assertEquals(101, shown.price(), 1e-9);
        clock.sleep(1_000);                                   // принт 100.5: старый бид 101 исполняется
        assertTrue(v.order(next).body().contains("\"status\":\"filled\""), v.order(next).body());
        assertTrue(v.order(next).body().contains("\"filled_quantity\":\"0.1\""), v.order(next).body());
        clock.sleep(3_000);                                   // конец окна: ставить нечего
        assertTrue(ActiveOrder.parse(v.activeOrders().body()).isEmpty());
        assertTrue(v.stallDiag().contains("поздних замен 1 (исполнено по старой цене 1)"), v.stallDiag());
    }

    @Test
    void withoutLagReplaceMovesAtOnce() throws Exception {
        SimClock clock = new SimClock(1_000_000);
        SimVenue v = venue(clock, 1_003_000, "none");
        String first = placeBid(v);
        clock.sleep(2_000);
        Venue.Response r = v.replace(first, "{\"client_order_id\":\"c2\",\"base_size\":\"0.1\",\"price\":\"99\"}");
        String next = id(r);
        clock.sleep(1_000);
        assertTrue(v.order(next).body().contains("\"status\":\"new\""), "бид уже на 99, принт 100.5 его не берёт");
    }

    @Test
    void laggedReplaceAppliesAtWindowEnd() throws Exception {
        SimClock clock = new SimClock(1_000_000);
        SimVenue v = venue(clock, Long.MAX_VALUE, "lag");
        String first = placeBid(v);
        clock.sleep(2_000);
        String next = id(v.replace(first, "{\"client_order_id\":\"c2\",\"base_size\":\"0.1\",\"price\":\"99\"}"));
        clock.sleep(3_500);                                   // после конца окна
        ActiveOrder shown = ActiveOrder.parse(v.activeOrders().body()).getFirst();
        assertEquals(next, shown.id());
        assertEquals(99, shown.price(), 1e-9);
    }
}
