package org.home.data.revx.replay;

import org.home.data.revx.exec.ActiveOrder;
import org.home.data.revx.exec.Venue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Частично исполненная заявка на стенде ведёт себя как на площадке: не
 * заменяется и называет своё состояние.
 *
 * <h2>Зачем этот тест</h2>
 *
 * До 09.09.2026 стенд отдавал всем стоящим заявкам {@code "status":"new"}, не
 * отдавал {@code filled_quantity} вовсе и замену пропускал. То есть он
 * приписывал боту возможность переставить цену там, где живая площадка отвечает
 * 422, — и тем занижал стоимость крупного лота: чем крупнее лот, тем чаще принт
 * берёт заявку не целиком.
 *
 * Проверяется вся цепочка, потому что сломаться она может в трёх местах:
 * ответ списка активных, ответ по одной заявке и решение о замене.
 */
class SimVenuePartialTest {

    /** Модель, которая один раз откусывает от заявки заданную долю. */
    private static final class BiteOnce implements FillModel {
        private final double qty;
        private boolean done;

        BiteOnce(double qty) {
            this.qty = qty;
        }

        @Override
        public String describe() {
            return "откусывает " + qty + " один раз";
        }

        @Override
        public List<Filled> advance(long nowMs, List<Resting> resting) {
            List<Filled> out = new ArrayList<>();
            if (!done && !resting.isEmpty()) {
                done = true;
                out.add(new Filled(resting.get(0).id(), qty, resting.get(0).price()));
            }
            return out;
        }
    }

    private static String place(SimVenue venue, double size, double price) {
        Venue.Response r = venue.place(String.format(java.util.Locale.ROOT,
                "{\"client_order_id\":\"c1\",\"symbol\":\"BTC-USDC\",\"side\":\"buy\","
                        + "\"order_configuration\":{\"limit\":{\"base_size\":\"%s\","
                        + "\"price\":\"%s\"}}}", size, price));
        assertTrue(r.ok(), "постановка должна пройти: " + r.status());
        return r.body().replaceAll(".*\"venue_order_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    /**
     * Откусили треть — заявка осталась в книге, назвалась частично исполненной,
     * и замена по ней отвергнута тем же текстом, что шлёт площадка.
     */
    @Test
    void partiallyFilledOrderCannotBeReplaced() {
        SimClock clock = new SimClock(1_000_000);
        // Лот 0.00003765 (боевой BTC), откусываем 0.00001023 — ровно тот случай,
        // что случился живьём 09.09.2026 с заявкой c02363a9.
        SimVenue venue = new SimVenue(clock, new BiteOnce(0.00001023), "BTC/USDC",
                0, 100, 0.1);
        String id = place(venue, 0.00003765, 79505.53);

        // Первый же ответ прогоняет модель: заявку кусают.
        Venue.Response active = venue.activeOrders();
        List<ActiveOrder> orders = ActiveOrder.parse(active.body());
        assertEquals(1, orders.size(), "заявка обязана ОСТАТЬСЯ в книге");
        ActiveOrder o = orders.getFirst();
        assertTrue(o.partiallyFilled(), "список активных обязан назвать состояние: " + active.body());
        assertEquals(0.00001023, o.filled(), 1e-12);
        assertEquals(0.00002742, o.size(), 1e-12);   // leaves_quantity, не весь лот

        // Ответ по одной заявке говорит то же самое.
        assertTrue(venue.order(id).body().contains("\"status\":\"partially_filled\""),
                venue.order(id).body());

        // И замена по ней отвергается — ровно тем текстом, по которому живой бот
        // отличает этот отказ от временного.
        Venue.Response replace = venue.replace(id, "{\"client_order_id\":\"c2\","
                + "\"base_size\":\"0.00003765\",\"price\":\"79600\"}");
        assertEquals(422, replace.status());
        assertTrue(replace.body().contains("'NEW' state"), replace.body());

        // ⚠️ И заявка от отказа НЕ ПРОПАЛА: отвергнутая замена не должна съедать
        // заявку, иначе бот остался бы без неё и без исполнения.
        assertEquals(1, ActiveOrder.parse(venue.activeOrders().body()).size());
    }

    /** Нетронутая заявка заменяется как прежде — правило не должно ловить лишних. */
    @Test
    void untouchedOrderStillReplaces() {
        SimClock clock = new SimClock(1_000_000);
        SimVenue venue = new SimVenue(clock, new BiteOnce(0), "BTC/USDC", 0, 100, 0.1);
        String id = place(venue, 0.00003765, 79505.53);

        assertFalse(ActiveOrder.parse(venue.activeOrders().body()).getFirst().partiallyFilled());
        Venue.Response replace = venue.replace(id, "{\"client_order_id\":\"c2\","
                + "\"base_size\":\"0.00003765\",\"price\":\"79600\"}");
        assertTrue(replace.ok(), "нетронутую заявку менять можно: " + replace.status());
    }

    /**
     * ⚠️ Отменить частично исполненную заявку МОЖНО, и это единственный выход
     * из слота: замены ей закрыты навсегда. Запрети мы и отмену — слот
     * заморозился бы до конца прогона.
     */
    @Test
    void partiallyFilledOrderCanStillBeCancelled() {
        SimClock clock = new SimClock(1_000_000);
        SimVenue venue = new SimVenue(clock, new BiteOnce(0.00001023), "BTC/USDC",
                0, 100, 0.1);
        String id = place(venue, 0.00003765, 79505.53);
        assertTrue(ActiveOrder.parse(venue.activeOrders().body()).getFirst().partiallyFilled());

        assertTrue(venue.cancel(id).ok());
        assertTrue(ActiveOrder.parse(venue.activeOrders().body()).isEmpty());
    }
}
