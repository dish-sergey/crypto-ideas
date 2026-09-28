package org.home.data.revx.replay;

import org.home.data.revx.exec.ActiveOrder;
import org.home.data.revx.exec.Venue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Призрак замены на стенде: так, как 28.09.2026 в 09:38:29 его получили все шесть
 * живых ботов — 422 через ~3 с, предка нет, наследника нет, резерв заперт.
 */
class SimVenueGhostTest {

    /** 2026-09-28T09:38:29.500Z — внутри окна HH:38:29 шириной 3 с. */
    private static final long IN_WINDOW = 1_790_588_309_500L;

    @AfterEach
    void clear() {
        System.clearProperty("revx.sim.ghost-at-sec");
        System.clearProperty("revx.sim.ghost-hold-min");
    }

    private static final class Never implements FillModel {
        @Override
        public String describe() {
            return "не исполняет";
        }

        @Override
        public List<Filled> advance(long nowMs, List<Resting> resting) {
            return List.of();
        }
    }

    private static String placeBuy(SimVenue venue) {
        Venue.Response r = venue.place("{\"client_order_id\":\"c1\",\"symbol\":\"SOL-USDC\","
                + "\"side\":\"buy\",\"order_configuration\":{\"limit\":{\"base_size\":\"0.05\","
                + "\"price\":\"120\"}}}");
        return r.body().replaceAll(".*\"venue_order_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private static double reservedUsdc(SimVenue venue) {
        String body = venue.balances().body();
        return Double.parseDouble(body.replaceAll(
                ".*\"currency\":\"USDC\",\"available\":\"[0-9.]+\",\"reserved\":\"([0-9.]+)\".*", "$1"));
    }

    @Test
    void заменаВОкнеСтановитсяПризраком() {
        System.setProperty("revx.sim.ghost-at-sec", String.valueOf(38 * 60 + 29));
        System.setProperty("revx.sim.ghost-hold-min", "120");
        SimClock clock = new SimClock(IN_WINDOW - 10_000);   // за 10 с до окна
        SimVenue venue = new SimVenue(clock, new Never(), "SOL/USDC", 0, 100, 0.1);
        String id = placeBuy(venue);
        assertEquals(6.0, reservedUsdc(venue), 1e-9);        // 0.05 × 120 под видимой заявкой

        clock.sleep(10_000);                                   // в окне
        Venue.Response r = venue.replace(id, "{\"client_order_id\":\"c2\",\"base_size\":\"0.05\","
                + "\"price\":\"121\"}");
        assertEquals(422, r.status());
        assertTrue(r.latencyMs() >= 3_000, "затык: ответ думает ~3 с");
        assertTrue(r.body().contains("'NEW' state"), r.body());

        // Ни предка, ни наследника в книге — а резерв на месте.
        List<ActiveOrder> active = ActiveOrder.parse(venue.activeOrders().body());
        assertTrue(active.isEmpty(), "наследника площадка не создаёт");
        assertEquals(6.0, reservedUsdc(venue), 1e-9);
        assertTrue(venue.order(id).body().contains("\"status\":\"cancelled\""));
        assertEquals(1, venue.ghostsMade());

        // Через два часа площадка отпускает.
        clock.sleep(120 * 60_000L + 1_000);
        assertEquals(0.0, reservedUsdc(venue), 1e-9);
    }

    /** Модель, исполняющая первую стоящую заявку, когда её включили. */
    private static final class FillOnDemand implements FillModel {
        boolean fire;

        @Override
        public String describe() {
            return "по команде";
        }

        @Override
        public List<Filled> advance(long nowMs, List<Resting> resting) {
            if (!fire || resting.isEmpty()) {
                return List.of();
            }
            fire = false;
            return List.of(new Filled(resting.get(0).id(), resting.get(0).size(), resting.get(0).price()));
        }
    }

    /** 204 на отмену в затык, а через секунды заявка исполняется (бот b, 22.09 20:02). */
    @Test
    void отменаСНепрошедшейЖизнью() {
        System.setProperty("revx.sim.ghost-at-sec", String.valueOf(38 * 60 + 29));
        System.setProperty("revx.sim.stall-effects", "late204,stale");
        try {
            SimClock clock = new SimClock(IN_WINDOW);
            FillOnDemand model = new FillOnDemand();
            SimVenue venue = new SimVenue(clock, model, "SOL/USDC", 0, 100, 0.1);
            String id = placeBuy(venue);

            Venue.Response c = venue.cancel(id);
            assertEquals(204, c.status());
            assertTrue(ActiveOrder.parse(venue.activeOrders().body()).isEmpty(),
                    "в списке активных её уже нет");
            assertEquals(404, venue.order(id).status(), "в затык судьба не видна");

            clock.sleep(1_500);                  // ещё в окне, срок жизни 4 с не вышел
            model.fire = true;
            venue.activeOrders();                // модель исполняет «отменённую»
            clock.sleep(10_000);                 // окно прошло
            assertTrue(venue.order(id).body().contains("\"status\":\"filled\""),
                    venue.order(id).body());
            assertTrue(venue.stallDiag().contains("исполнено после «отмены» 1"), venue.stallDiag());
        } finally {
            System.clearProperty("revx.sim.stall-effects");
        }
    }

    /** 404 на отмену живой заявки: она живёт срок и снимается сама. */
    @Test
    void четыреСтаЧетыреНаЖивой() {
        System.setProperty("revx.sim.ghost-at-sec", String.valueOf(38 * 60 + 29));
        System.setProperty("revx.sim.stall-effects", "live404");
        try {
            SimClock clock = new SimClock(IN_WINDOW);
            SimVenue venue = new SimVenue(clock, new Never(), "SOL/USDC", 0, 100, 0.1);
            String id = placeBuy(venue);
            assertEquals(404, venue.cancel(id).status());
            assertEquals(6.0, reservedUsdc(venue), 1e-9);   // резерв держится, пока жива
            clock.sleep(5_000);
            assertEquals(0.0, reservedUsdc(venue), 1e-9);   // срок вышел — снята
            assertTrue(venue.order(id).body().contains("\"status\":\"cancelled\""));
        } finally {
            System.clearProperty("revx.sim.stall-effects");
        }
    }

    @Test
    void внеОкнаЗаменаОбычная() {
        System.setProperty("revx.sim.ghost-at-sec", String.valueOf(38 * 60 + 29));
        SimClock clock = new SimClock(IN_WINDOW - 60_000);
        SimVenue venue = new SimVenue(clock, new Never(), "SOL/USDC", 0, 100, 0.1);
        String id = placeBuy(venue);
        Venue.Response r = venue.replace(id, "{\"client_order_id\":\"c2\",\"base_size\":\"0.05\","
                + "\"price\":\"121\"}");
        assertTrue(r.ok());
        assertEquals(0, venue.ghostsMade());
    }
}
