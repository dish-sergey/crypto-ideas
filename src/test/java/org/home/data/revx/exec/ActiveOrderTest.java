package org.home.data.revx.exec;

import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор списка открытых заявок. Тела взяты из журнала живого исполнителя
 * (`state/exec.db`, 30.08.2026), а не выдуманы: именно расхождения между
 * ответами разных эндпоинтов здесь и ломали уборку.
 */
class ActiveOrderTest {

    /** Ответ {@code GET /orders/{id}} — один объект, идентификатор зовётся {@code id}. */
    private static final String ONE = """
            {"data":{"id":"c9f9b6c4-d62b-481b-9b02-d1187ceee742",
            "previous_order_id":"9bc71bcb-8982-4646-93c4-0fbad1f078f0",
            "client_order_id":"309d0248-3975-4b7a-9fa1-7dd66a682aff","symbol":"BTC/USDC",
            "side":"buy","type":"limit","quantity":"0.0000125","filled_quantity":"0",
            "leaves_quantity":"0.0000125","amount":"0.98148","filled_amount":"0",
            "price":"78518.33","total_fee":"0","fee_currency":"USDC","status":"cancelled",
            "reject_reason":"replaced","time_in_force":"gtc",
            "execution_instructions":["post_only"],
            "created_date":1788093108766,"updated_date":1788093113939}}""";

    /**
     * Тот же список, но идентификатор назван так, как его зовёт постановка.
     * Площадка сегодня отдаёт в списке {@code id}; читаются оба имени, потому что
     * различие между ними уже один раз стоило заявки, оставленной в книге.
     */
    private static final String ALIAS = """
            {"data":[{"venue_order_id":"8192eb0c-a7a7-4e8b-9144-c2d244aa0813",
            "symbol":"BTC-USDC","side":"sell","quantity":"0.0000125",
            "price":"79100.0","created_date":1788110000000}]}""";

    private static final String TWO = """
            {"data":[
            {"id":"aaa","symbol":"BTC/USDC","side":"buy","quantity":"0.0000125",
             "leaves_quantity":"0.0000125","price":"78518.33","created_date":1788093108766},
            {"id":"bbb","symbol":"BTC/USDC","side":"buy","quantity":"0.0000125",
             "leaves_quantity":"0.0000125","price":"78962.71","created_date":1788110000000},
            {"id":"ccc","symbol":"ETH/USDC","side":"sell","quantity":"0.01",
             "leaves_quantity":"0.01","price":"4100.5","created_date":1788110000001}],
            "metadata":{"next_cursor":"","timestamp":1788093114775}}""";

    @Test
    void readsBothNamesOfTheIdentifier() {
        // Площадка называет один и тот же идентификатор по-разному в разных
        // ответах — на этом однажды сломалась уборка (см. Panic).
        assertEquals("c9f9b6c4-d62b-481b-9b02-d1187ceee742", ActiveOrder.parse(ONE).getFirst().id());
        assertEquals("8192eb0c-a7a7-4e8b-9144-c2d244aa0813",
                ActiveOrder.parse(ALIAS).getFirst().id());
    }

    @Test
    void readsFieldsOfOneOrder() {
        ActiveOrder order = ActiveOrder.parse(ONE).getFirst();
        assertEquals("BTC/USDC", order.symbol());
        assertEquals(Side.BUY, order.side());
        assertEquals(78518.33, order.price(), 1e-9);
        assertEquals(0.0000125, order.size(), 1e-12);
        assertEquals(1788093108766L, order.createdMs());
    }

    @Test
    void keepsOrdersApart() {
        // Регулярное выражение по всему телу склеило бы цену одной заявки с
        // идентификатором другой; это и есть причина разбирать список Jackson'ом.
        List<ActiveOrder> orders = ActiveOrder.parse(TWO);
        assertEquals(3, orders.size());
        assertEquals(78518.33, orders.get(0).price(), 1e-9);
        assertEquals(78962.71, orders.get(1).price(), 1e-9);
        assertEquals(Side.SELL, orders.get(2).side());
        assertNotEquals(orders.get(0).id(), orders.get(1).id());
    }

    @Test
    void symbolWrittenBothWaysIsTheSamePair() {
        // В теле постановки пара пишется через дефис, в ответах — через дробь.
        assertEquals(ActiveOrder.normalize("BTC-USDC"), ActiveOrder.normalize("BTC/USDC"));
        assertEquals("BTC/USDC", ActiveOrder.parse(TWO).getFirst().symbol());
    }

    @Test
    void emptyAndBrokenBodiesGiveNothing() {
        // «Не знаю» обязано выглядеть как пустой список, а не как исключение:
        // сверка на непонятном ответе должна ничего не трогать.
        assertTrue(ActiveOrder.parse("{\"data\":[],\"metadata\":{}}").isEmpty());
        assertTrue(ActiveOrder.parse(null).isEmpty());
        assertTrue(ActiveOrder.parse("не json").isEmpty());
    }
}
