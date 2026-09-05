package org.home.data.revx.replay;

import org.home.data.revx.exec.ActiveOrder;
import org.home.data.revx.exec.Venue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Учёт заявок на стенде и ФОРМА ответов.
 *
 * Форма важна не меньше содержания: стенд обязан отвечать так, чтобы её разбирал
 * ТОТ ЖЕ код, что и живую площадку. Самые дорогие ошибки проекта жили именно в
 * разборе — имя поля с идентификатором различается между ответами, замена
 * возвращает новый идентификатор, а на исчезнувшую заявку площадка обязана
 * сообщить {@code filled_quantity}, иначе бот навсегда остаётся с нулевым
 * инвентарём (так первый прогон и разошёлся с живым на 383-м тике).
 */
class SimVenueTest {

    /** Ровно та регулярка, которой {@code QuoteLoop} читает остатки. */
    private static final Pattern BALANCE = Pattern.compile(
            "\\{\"currency\":\"([A-Z0-9]+)\",\"available\":\"([0-9.]+)\","
                    + "\"reserved\":\"([0-9.]+)\",\"total\":\"([0-9.]+)\"");

    /** Модель, которая не исполняет ничего: проверяем чистый учёт. */
    private static final class Inert implements FillModel {
        @Override
        public List<Filled> advance(long nowMs, List<Resting> resting) {
            return List.of();
        }

        @Override
        public String describe() {
            return "ничего не исполняет";
        }
    }

    private static String buy(String price, String size) {
        return ("{\"client_order_id\":\"c-1\",\"symbol\":\"BTC-USDC\",\"side\":\"buy\","
                + "\"order_configuration\":{\"limit\":{\"base_size\":\"%s\",\"price\":\"%s\"}}}")
                .formatted(size, price);
    }

    private static SimVenue venue() {
        return new SimVenue(new SimClock(0), new Inert(), "BTC/USDC", 0.001, 100);
    }

    @Test
    void placementAnswersWithVenueOrderId() {
        Venue.Response r = venue().place(buy("50000", "0.0001"));
        assertEquals(200, r.status());
        assertTrue(r.body().contains("\"venue_order_id\""),
                "постановка обязана отдать venue_order_id: " + r.body());
    }

    @Test
    void activeListIsParsedByTheLiveParser() {
        SimVenue v = venue();
        v.place(buy("50000", "0.0001"));
        List<ActiveOrder> orders = ActiveOrder.parse(v.activeOrders().body());

        assertEquals(1, orders.size(), "живой разбор не увидел нашей заявки");
        assertEquals("BTC/USDC", orders.get(0).symbol());
        assertEquals(50000, orders.get(0).price(), 1e-9);
        assertEquals(0.0001, orders.get(0).size(), 1e-12);
        assertEquals("c-1", orders.get(0).clientId(), "метка бота обязана дожить до списка");
    }

    @Test
    void replaceGivesANewIdAndTheOldOneIsGone() {
        SimVenue v = venue();
        String first = id(v.place(buy("50000", "0.0001")));
        Venue.Response replaced = v.replace(first,
                "{\"client_order_id\":\"c-2\",\"base_size\":\"0.0002\",\"price\":\"50100\"}");

        String second = id(replaced);
        assertFalse(second.equals(first), "замена обязана вернуть ДРУГОЙ идентификатор");

        List<ActiveOrder> orders = ActiveOrder.parse(v.activeOrders().body());
        assertEquals(1, orders.size(), "в книге должна остаться одна заявка");
        assertEquals(second, orders.get(0).id());
        // Живой заявкой 05.09.2026 проверено: замена меняет и объём тоже.
        assertEquals(0.0002, orders.get(0).size(), 1e-12, "замена обязана менять объём");
        assertEquals(50100, orders.get(0).price(), 1e-9);
    }

    @Test
    void replacingADeadIdGives422AndCreatesNothing() {
        // Док. 111 и зонд 04.09.2026: наследника площадка при этом НЕ создаёт.
        SimVenue v = venue();
        String first = id(v.place(buy("50000", "0.0001")));
        v.replace(first, "{\"client_order_id\":\"c-2\",\"base_size\":\"0.0001\",\"price\":\"50100\"}");

        int before = ActiveOrder.parse(v.activeOrders().body()).size();
        Venue.Response stale = v.replace(first,
                "{\"client_order_id\":\"c-3\",\"base_size\":\"0.0001\",\"price\":\"50200\"}");

        assertEquals(422, stale.status());
        assertEquals(before, ActiveOrder.parse(v.activeOrders().body()).size(),
                "книга после отказа расти не должна");
        assertEquals(1, v.replaceRejects());
    }

    @Test
    void cancelAnswers204AndEmptiesTheBook() {
        SimVenue v = venue();
        String only = id(v.place(buy("50000", "0.0001")));
        assertEquals(204, v.cancel(only).status());
        assertTrue(ActiveOrder.parse(v.activeOrders().body()).isEmpty());
        assertEquals(404, v.cancel(only).status(), "второй раз отменять нечего");
    }

    @Test
    void balancesMatchTheLiveRegexAndReserveUnderOrders() {
        SimVenue v = venue();
        v.place(buy("50000", "0.0001"));           // резерв 5 USDC под бидом

        Matcher m = BALANCE.matcher(v.balances().body());
        List<String> seen = new ArrayList<>();
        double usdcAvailable = -1;
        double usdcTotal = -1;
        while (m.find()) {
            seen.add(m.group(1));
            if ("USDC".equals(m.group(1))) {
                usdcAvailable = Double.parseDouble(m.group(2));
                usdcTotal = Double.parseDouble(m.group(4));
            }
        }
        assertEquals(List.of("BTC", "USDC"), seen, "живая регулярка не разобрала остатки");
        assertEquals(100, usdcTotal, 1e-9);
        // ⚠️ Проверять надо available, а не total: под стоящей заявкой деньги
        // видны в total, но поставить на них нельзя.
        assertEquals(95, usdcAvailable, 1e-9, "резерв под бидом не вычтен из available");
    }

    @Test
    void numbersNeverComeOutInScientificNotation() {
        // Регулярка остатков принимает только [0-9.]+ — экспонента ей не по зубам,
        // и бот решит, что остатка нет вовсе. Лот BTC как раз 1.25E-5.
        SimVenue v = new SimVenue(new SimClock(0), new Inert(), "BTC/USDC", 0.0000125, 0.0001);
        String body = v.balances().body();
        assertFalse(body.contains("E-"), "экспонента в остатках: " + body);
        assertTrue(BALANCE.matcher(body).find(), "живая регулярка не разобрала: " + body);
    }

    private static String id(Venue.Response r) {
        Matcher m = Pattern.compile("\"venue_order_id\":\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find(), "нет venue_order_id в " + r.body());
        return m.group(1);
    }
}
