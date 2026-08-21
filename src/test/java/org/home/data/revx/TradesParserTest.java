package org.home.data.revx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Лента сделок: пагинация и границы страниц (ТЗ §7, раздел «Данные»). */
class TradesParserTest {

    /** Реальный ответ BTC/USDC 19.08.2026 (сокращён до трёх сделок). */
    private static final String PAGE = """
            {"data":[
              {"id":"d1e45c8c","symbol":"BTC/USDC","price":"68536.98","quantity":"0.00018851",
               "timestamp":1787157574957,"side":"sell"},
              {"id":"076bac5e","symbol":"BTC/USDC","price":"68557.78","quantity":"0.05337019",
               "timestamp":1787157500000,"side":"buy"},
              {"id":"69bd9dda","symbol":"BTC/USDC","price":"68534.18","quantity":"0.09459887",
               "timestamp":1787157400000,"side":"sell"}],
             "metadata":{"timestamp":1787157605857,
                         "next_cursor":"ZGF0ZT0xNzg3MTU3NTc0OTU3O2lkPTY5YmQ5ZGRh"}}
            """;

    @Test
    void parsesTradesAndCursor() {
        TradesParser.Page page = TradesParser.parse(PAGE);

        assertEquals(3, page.trades().size());
        TradesParser.Trade first = page.trades().get(0);
        assertEquals("d1e45c8c", first.id());
        assertEquals(68536.98, first.price(), 1e-9);
        assertEquals(0.00018851, first.qty(), 1e-12);
        assertEquals("sell", first.side());
        assertEquals("ZGF0ZT0xNzg3MTU3NTc0OTU3O2lkPTY5YmQ5ZGRh", page.nextCursor());
    }

    @Test
    void exposesPageBoundariesForPagination() {
        TradesParser.Page page = TradesParser.parse(PAGE);

        // граница «докуда докачали» — самая старая сделка страницы, по ней решается,
        // догнали ли мы уже записанное; перепутать с новейшей = потерять окно
        assertEquals(1787157400000L, page.oldestTsMs());
        assertEquals(1787157574957L, page.newestTsMs());
    }

    @Test
    void lastPageHasNoCursor() {
        TradesParser.Page page = TradesParser.parse(
                "{\"data\":[],\"metadata\":{\"timestamp\":1,\"next_cursor\":\"\"}}");

        assertTrue(page.empty());
        assertNull(page.nextCursor(), "пустой курсор = конец ленты, а не строка \"\"");
    }

    /** Авторизованная схема: tid/tdt/p/q/s вместо id/timestamp/price/quantity/side. */
    @Test
    void parsesAuthenticatedCompactSchema() {
        TradesParser.Page page = TradesParser.parse("""
                {"data":[{"tdt":1787176380337,"aid":"ETH","p":"2282.97","q":"3.30868333",
                          "tid":"da76aa0a45773523841fcde002d35c7d","s":"sell"}],
                 "metadata":{"timestamp":1787176546459,"next_cursor":"ZGF0ZT0x"}}
                """);

        assertEquals(1, page.trades().size());
        TradesParser.Trade t = page.trades().get(0);
        assertEquals(1787176380337L, t.tsMs());
        assertEquals(2282.97, t.price(), 1e-9);
        assertEquals(3.30868333, t.qty(), 1e-12);
        assertEquals("sell", t.side());
        assertEquals("ZGF0ZT0x", page.nextCursor());
    }

    /**
     * Одна и та же сделка приходит как UUID с дефисами (публично) и без них
     * (с ключом). Без нормализации переход на ключ удвоил бы поток в данных.
     */
    @Test
    void sameTradeGetsSameIdInBothSchemas() {
        TradesParser.Page pub = TradesParser.parse("""
                {"data":[{"id":"D307F978-621B-3066-A45D-AB58519AECA6","symbol":"ETH/USDC",
                          "price":"1","quantity":"1","timestamp":1,"side":"buy"}],"metadata":{}}
                """);
        TradesParser.Page auth = TradesParser.parse("""
                {"data":[{"tid":"d307f978621b3066a45dab58519aeca6","p":"1","q":"1",
                          "tdt":1,"s":"buy"}],"metadata":{}}
                """);

        assertEquals(pub.trades().get(0).id(), auth.trades().get(0).id());
    }

    @Test
    void skipsRecordsWithoutId() {
        TradesParser.Page page = TradesParser.parse("""
                {"data":[{"symbol":"BTC/USDC","price":"1","quantity":"1","timestamp":1}],
                 "metadata":{}}
                """);

        // без id запись неидемпотентна: повторный заход создал бы дубль
        assertTrue(page.empty());
    }
}
