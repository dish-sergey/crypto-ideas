package org.home.data.revx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты данных из ТЗ §7. Первый — главный: массив asks на живом API приходит
 * по УБЫВАНИЮ цены, и наивное чтение asks[0] завышает спред в разы.
 */
class BookParserTest {

    /** Реальный ответ ETH/USDC 19.08.2026: asks по убыванию, bids по убыванию. */
    private static final String REAL = """
            {"data":{
              "asks":[{"price":"2101.00","quantity":"0.02379819","count":1},
                      {"price":"2100.00","quantity":"5.15351904","count":3},
                      {"price":"2099.42","quantity":"0.005","count":1},
                      {"price":"2099.00","quantity":"0.80313663","count":1},
                      {"price":"2097.13","quantity":"4","count":1}],
              "bids":[{"price":"2090.13","quantity":"2.33801954","count":1},
                      {"price":"2089.96","quantity":"0.01196195","count":1},
                      {"price":"2089.63","quantity":"4.37108","count":1},
                      {"price":"2089.59","quantity":"7.82728279","count":1},
                      {"price":"2089.49","quantity":"203.30604657","count":1}]},
             "metadata":{"region":"EEA","timestamp":1787157572533}}
            """;

    @Test
    void sortsDescendingAsksAndFlagsIt() {
        BookParser.Book book = BookParser.parse(REAL, 5);

        assertEquals(2097.13, book.bestAsk(), 1e-9, "лучший аск — минимальный, а не asks[0]");
        assertEquals(2090.13, book.bestBid(), 1e-9);
        assertTrue(BookFlags.has(book.flags(), BookFlags.ASKS_REORDERED),
                "пересортировку asks надо фиксировать флагом, а не молча чинить");
        assertFalse(BookFlags.has(book.flags(), BookFlags.BIDS_REORDERED));
        assertTrue(book.usable());
    }

    @Test
    void naiveFirstElementWouldOverstateSpread() {
        BookParser.Book book = BookParser.parse(REAL, 5);

        double correct = book.relativeSpread();                       // (2097.13-2090.13)/mid
        double naive = (2101.00 - 2090.13) / ((2101.00 + 2090.13) / 2);
        assertTrue(naive > correct * 1.5,
                "именно эта ошибка и завышала спред: наивно " + naive + " против " + correct);
        assertEquals(0.00334, correct, 1e-5);
    }

    @Test
    void keepsServerTimestamp() {
        assertEquals(1787157572533L, BookParser.parse(REAL, 5).serverTsMs());
    }

    @Test
    void crossedBookIsRejectedNotFixed() {
        String crossed = """
                {"data":{"asks":[{"price":"100.0","quantity":"1"}],
                         "bids":[{"price":"101.0","quantity":"1"}]},
                 "metadata":{"timestamp":1}}
                """;
        BookParser.Book book = BookParser.parse(crossed, 1);

        assertTrue(BookFlags.has(book.flags(), BookFlags.CROSSED));
        assertFalse(book.usable(), "перекрещенный снимок не должен попадать в данные");
    }

    @Test
    void emptySideIsRejected() {
        String oneSided = """
                {"data":{"asks":[],"bids":[{"price":"101.0","quantity":"1"}]},"metadata":{"timestamp":1}}
                """;
        BookParser.Book book = BookParser.parse(oneSided, 5);

        assertTrue(BookFlags.has(book.flags(), BookFlags.EMPTY_SIDE));
        assertFalse(book.usable());
    }

    @Test
    void shallowBookIsFlaggedButUsable() {
        String shallow = """
                {"data":{"asks":[{"price":"101.0","quantity":"1"}],
                         "bids":[{"price":"100.0","quantity":"1"}]},
                 "metadata":{"timestamp":1}}
                """;
        BookParser.Book book = BookParser.parse(shallow, 5);

        assertTrue(BookFlags.has(book.flags(), BookFlags.PARTIAL_DEPTH));
        assertTrue(book.usable(), "неполная глубина — не повод выбрасывать снимок");
    }

    /**
     * Авторизованные эндпоинты отдают ТУ ЖЕ книгу в другой схеме: p/q/no вместо
     * price/quantity/count. Пока парсер этого не знал, снимки молча выходили
     * пустыми и в базу не попадали вообще (поймано на ARM 19.08.2026).
     */
    @Test
    void parsesAuthenticatedCompactSchema() {
        String auth = """
                {"data":{
                  "asks":[{"aid":"ETH","s":"SELL","p":"2299.56","q":"2.33801954","no":"1","pdt":1787176527174},
                          {"aid":"ETH","s":"SELL","p":"2290.00","q":"0.1","no":"2","pdt":1787176527174}],
                  "bids":[{"aid":"ETH","s":"BUYI","p":"2282.65","q":"20.02564559","no":"1","pdt":1787176546292},
                          {"aid":"ETH","s":"BUYI","p":"2282.50","q":"284.6284652","no":"1","pdt":1787176546292}]},
                 "metadata":{"timestamp":1787176546292}}
                """;
        BookParser.Book book = BookParser.parse(auth, 2);

        assertTrue(book.usable());
        assertEquals(2290.00, book.bestAsk(), 1e-9, "asks и здесь по убыванию");
        assertEquals(2282.65, book.bestBid(), 1e-9);
        // после сортировки лучший аск — 2290.00, у него no=2
        assertEquals(2, book.asks().get(0).count(), "count берётся из поля no");
        assertEquals(1787176546292L, book.serverTsMs());
    }

    @Test
    void ignoresMalformedLevels() {
        String broken = """
                {"data":{"asks":[{"price":"abc","quantity":"1"},{"price":"101.0","quantity":"2"}],
                         "bids":[{"price":"100.0","quantity":"1"},{"quantity":"5"}]},
                 "metadata":{"timestamp":1}}
                """;
        BookParser.Book book = BookParser.parse(broken, 2);

        assertEquals(1, book.asks().size());
        assertEquals(1, book.bids().size());
        assertEquals(101.0, book.bestAsk(), 1e-9);
    }
}
