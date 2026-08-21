package org.home.data.revx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор каталога пар. Фрагмент — реальный ответ API от 19.08.2026:
 * это СЛОВАРЬ с ключом 'ETH/USDC', числа приходят строками, а в пути книги
 * тот же символ пишется через дефис.
 */
class PairsCatalogTest {

    private static final String CATALOG = """
            {
              "ETH/USDC": {"base":"ETH","quote":"USDC","base_step":"0.00000001","quote_step":"0.01",
                           "min_order_size":"0.00000001","max_order_size":"5000",
                           "min_order_size_quote":"0.1","max_order_size_quote":"1000000",
                           "status":"active","slippage":5},
              "ETH/USD":  {"base":"ETH","quote":"USD","base_step":"0.00000001","quote_step":"0.01",
                           "min_order_size":"0.00000001","max_order_size":"5000",
                           "min_order_size_quote":"0.1","max_order_size_quote":"10000000",
                           "status":"active","slippage":5},
              "DOGE/USDC":{"base":"DOGE","quote":"USDC","base_step":"1","quote_step":"0.00001",
                           "min_order_size":"1","status":"active","slippage":5},
              "DOGE/USD": {"base":"DOGE","quote":"USD","base_step":"1","quote_step":"0.00001",
                           "min_order_size":"1","status":"active","slippage":5},
              "JUP/USDC": {"base":"JUP","quote":"USDC","base_step":"0.001","quote_step":"0.00001",
                           "status":"active","slippage":5},
              "JUP/USD":  {"base":"JUP","quote":"USD","base_step":"0.001","quote_step":"0.00001",
                           "status":"suspended","slippage":5},
              "USDC/EUR": {"base":"USDC","quote":"EUR","base_step":"0.01","quote_step":"0.0001",
                           "status":"active","slippage":5}
            }
            """;

    @Test
    void parsesDictionaryFormatWithStringNumbers() {
        Map<String, PairSpec> specs = PairsCatalog.parse(CATALOG);

        assertEquals(7, specs.size());
        PairSpec eth = specs.get("ETH/USDC");
        assertEquals("ETH", eth.base());
        assertEquals("USDC", eth.quote());
        assertEquals(1e-8, eth.baseStep(), 1e-12);
        assertEquals(0.01, eth.quoteStep(), 1e-12);
        assertEquals(0.1, eth.minOrderSizeQuote(), 1e-12);
        assertTrue(eth.active());
    }

    @Test
    void buildsPathSymbolWithDash() {
        assertEquals("ETH-USDC", PairsCatalog.parse(CATALOG).get("ETH/USDC").pathSymbol());
    }

    @Test
    void missingOptionalFieldsBecomeNullNotZero() {
        PairSpec jup = PairsCatalog.parse(CATALOG).get("JUP/USDC");
        assertEquals(null, jup.minOrderSize());
        assertEquals(null, jup.minOrderSizeQuote());
    }

    @Test
    void universeKeepsOnlyUsdcPairsWithActiveUsdReference() {
        List<String> missing = new ArrayList<>();
        List<PairsCatalog.Leg> universe = PairsCatalog.selectUniverse(
                PairsCatalog.parse(CATALOG), "USDC", "USD",
                List.of("DOGE"), List.of("ETH"),
                (symbol, detail) -> missing.add(symbol));

        // JUP/USDC выброшен: опорная JUP/USD не active — справедливую цену взять неоткуда.
        assertEquals(List.of("ETH", "DOGE"), universe.stream().map(PairsCatalog.Leg::base).toList());
        assertEquals(List.of("JUP/USDC"), missing);
        // USDC/EUR — не USDC-котируемая пара, в стенд не попадает.
        assertTrue(universe.stream().noneMatch(l -> l.quoted().symbol().equals("USDC/EUR")));
    }

    @Test
    void memecoinsAreFlaggedButNotDropped() {
        List<PairsCatalog.Leg> universe = PairsCatalog.selectUniverse(
                PairsCatalog.parse(CATALOG), "USDC", "USD",
                List.of("DOGE"), List.of("ETH"), (s, d) -> { });

        PairsCatalog.Leg doge = universe.stream().filter(l -> l.base().equals("DOGE")).findFirst().orElseThrow();
        assertTrue(doge.memecoin(), "мемкоин должен быть помечен: он исключается из расчёта курса USDC/USD");
        PairsCatalog.Leg eth = universe.stream().filter(l -> l.base().equals("ETH")).findFirst().orElseThrow();
        assertFalse(eth.memecoin());
    }

    @Test
    void priorityDrivesPollOrder() {
        List<PairsCatalog.Leg> universe = PairsCatalog.selectUniverse(
                PairsCatalog.parse(CATALOG), "USDC", "USD",
                List.of(), List.of("DOGE", "ETH"), (s, d) -> { });

        assertEquals(List.of("DOGE", "ETH"), universe.stream().map(PairsCatalog.Leg::base).toList());
    }
}
