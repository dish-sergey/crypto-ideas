package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Фид разлоков на фикстурах — без сети. Проверяет фильтры ≥3%, доступность Kraken, будущие даты, категорию. */
class UnlockFeedTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Расписание: circulating растёт до 1000 к дню разлока; один клифф 50 токенов (5%) для investors. */
    private static String emission(String gecko, long unlockTs, String amt, String desc, double circAtUnlock) {
        // documentedData: одна категория с двумя точками — до и на дату разлока
        return """
            {"metadata":{"token":"coingecko:%s","events":[
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[%s],"category":"","description":"%s"}
            ]},
             "documentedData":{"data":[{"label":"All","data":[
               {"timestamp":%d,"unlocked":%s}]}]}}
            """.formatted(gecko, unlockTs, amt, desc, unlockTs, circAtUnlock);
    }

    private UnlockFeed feed(EmissionSource src) {
        return new UnlockFeed(src, Map.of("aptos", "APT", "arbitrum", "ARB", "bitcoin", "BTC"),
                Set.of("APT", "BTC")); // ARB намеренно НЕ на Kraken
    }

    private static EmissionSource fake(Map<String, String> slugToJson) {
        return new EmissionSource() {
            public List<String> protocols() { return List.copyOf(slugToJson.keySet()); }
            public JsonNode emissions(String slug) throws Exception { return M.readTree(slugToJson.get(slug)); }
        };
    }

    @Test void surfacesFutureCliffAbove3pctOnKraken() throws Exception {
        long today = 20000;
        long unlockDay = today + 5;
        long ts = unlockDay * 86400;
        var src = fake(Map.of(
                "aptos", emission("aptos", ts, "60", "A cliff from Investors on X", 1000),   // 6% ✓
                "bitcoin", emission("bitcoin", ts, "20", "A cliff from Team on X", 1000)));   // 2% ✗ (<3%)
        List<UnlockEvent> up = feed(src).upcoming(today);
        assertEquals(1, up.size(), "только APT (6%); BTC 2% отфильтрован");
        UnlockEvent e = up.get(0);
        assertEquals("APT", e.base());
        assertEquals("PF_APTUSD", e.krakenSymbol());
        assertEquals(unlockDay, e.unlockDay());
        assertEquals(0.06, e.pctCirculating(), 1e-9);
        assertEquals("investors", e.category());
    }

    @Test void excludesNonKrakenAndPastEvents() throws Exception {
        long today = 20000;
        var src = fake(Map.of(
                "arbitrum", emission("arbitrum", (today + 5) * 86400, "100", "from Investors on X", 1000), // не на Kraken
                "aptos", emission("aptos", (today - 3) * 86400, "100", "from Team on X", 1000)));           // в прошлом
        assertTrue(feed(src).upcoming(today).isEmpty(), "ARB нет на Kraken, APT в прошлом");
    }

    @Test void dueForEntryPicksExactLead() throws Exception {
        long today = 20000;
        var src = fake(Map.of(
                "aptos", emission("aptos", (today + 5) * 86400, "60", "from Investors on X", 1000),
                "bitcoin", emission("bitcoin", (today + 8) * 86400, "60", "from Team on X", 1000)));
        List<UnlockEvent> due = feed(src).dueForEntry(today, 5);
        assertEquals(1, due.size());
        assertEquals("APT", due.get(0).base(), "вход сегодня только для разлока через 5 дней");
    }

    @Test void mergesSameDayTranchesIntoOne() throws Exception {
        long today = 20000, unlockDay = today + 5, ts = unlockDay * 86400;
        // два клиффа в один день: 40/1000=4% ecosystem + 320/1000=32% team → одно событие 36%, категория team
        String twoTranche = """
            {"metadata":{"token":"coingecko:aptos","events":[
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[40],"category":"","description":"cliff from Ecosystem on X"},
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[320],"category":"","description":"cliff from Team on X"}
            ]},"documentedData":{"data":[{"label":"All","data":[{"timestamp":%d,"unlocked":1000}]}]}}
            """.formatted(ts, ts, ts);
        List<UnlockEvent> up = feed(fake(Map.of("aptos", twoTranche))).upcoming(today);
        assertEquals(1, up.size(), "два транша одного дня → одно событие");
        assertEquals(0.36, up.get(0).pctCirculating(), 1e-9, "проценты суммируются");
        assertEquals("team", up.get(0).category(), "категория крупнейшего транша");
    }

    @Test void oneBadProtocolDoesNotBreakFeed() throws Exception {
        long today = 20000;
        var src = fake(Map.of(
                "aptos", emission("aptos", (today + 5) * 86400, "60", "from Investors on X", 1000),
                "broken", "{ not json"));
        assertEquals(1, feed(src).upcoming(today).size(), "битый протокол пропущен, фид жив");
    }
}
