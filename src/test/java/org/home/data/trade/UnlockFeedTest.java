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
        assertEquals("team 32.0 + eco 4.0", up.get(0).breakdown(), "разбивка по траншам, крупнейший первым");
        assertTrue(up.get(0).pctLabel().startsWith("36.0% (team 32.0 + eco 4.0"), up.get(0).pctLabel());
    }

    /**
     * Регрессия док. 130: порог применяется ПОСЛЕ слияния. Два транша по 2% в один день дают событие 4% и
     * обязаны пройти порог 3%; одиночный транш 2% — не обязан. До правки первый случай отбрасывался целиком
     * (живой промах: ZRO 19.09.2026, 1.68% + 2.06%, оба insiders).
     */
    @Test void thresholdAppliesAfterMergeNotPerTranche() throws Exception {
        long today = 20000, unlockDay = today + 5, ts = unlockDay * 86400;
        String twoSmall = """
            {"metadata":{"token":"coingecko:aptos","events":[
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[20],"category":"","description":"cliff from Team on X"},
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[20],"category":"","description":"cliff from Core Contributors on X"}
            ]},"documentedData":{"data":[{"label":"All","data":[{"timestamp":%d,"unlocked":1000}]}]}}
            """.formatted(ts, ts, ts);
        List<UnlockEvent> up = feed(fake(Map.of("aptos", twoSmall))).upcoming(today);
        assertEquals(1, up.size(), "2% + 2% = 4% за день — событие проходит порог 3%");
        assertEquals(0.04, up.get(0).pctCirculating(), 1e-9);

        // контроль: одиночный транш 2% порога не проходит
        var single = fake(Map.of("aptos", emission("aptos", ts, "20", "cliff from Team on X", 1000)));
        assertTrue(feed(single).upcoming(today).isEmpty(), "одиночный транш 2% отсекается");
    }

    /** Транши разных дней не сливаются, даже если каждый по отдельности ниже порога. */
    @Test void doesNotMergeAcrossDays() throws Exception {
        long today = 20000;
        String twoDays = """
            {"metadata":{"token":"coingecko:aptos","events":[
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[20],"category":"","description":"cliff from Team on X"},
               {"unlockType":"cliff","timestamp":%d,"noOfTokens":[20],"category":"","description":"cliff from Team on X"}
            ]},"documentedData":{"data":[{"label":"All","data":[{"timestamp":%d,"unlocked":1000}]}]}}
            """.formatted((today + 5) * 86400, (today + 6) * 86400, (today + 5) * 86400);
        assertTrue(feed(fake(Map.of("aptos", twoDays))).upcoming(today).isEmpty(),
                "2% в один день и 2% в другой — два разных события, оба ниже порога");
    }

    /** Линейные события — это смена скорости [было, стало], а не объём: в разлоки они не попадают. */
    @Test void ignoresLinearRateChanges() throws Exception {
        long today = 20000, ts = (today + 5) * 86400;
        String linear = """
            {"metadata":{"token":"coingecko:aptos","events":[
               {"unlockType":"linear","timestamp":%d,"noOfTokens":[0,900],"category":"","description":"Linear from Team on X","rateDurationDays":30}
            ]},"documentedData":{"data":[{"label":"All","data":[{"timestamp":%d,"unlocked":1000}]}]}}
            """.formatted(ts, ts);
        assertTrue(feed(fake(Map.of("aptos", linear))).upcoming(today).isEmpty(),
                "linear не событие S5: 900 — новая скорость, а не разлочённый объём");
    }

    /** timestamp в корпусе бывает строкой (наблюдалось у Curve) — разбор обязан это переживать. */
    @Test void acceptsStringTimestamps() throws Exception {
        long today = 20000, unlockDay = today + 5, ts = unlockDay * 86400;
        String stringTs = """
            {"metadata":{"token":"coingecko:aptos","events":[
               {"unlockType":"cliff","timestamp":"%d","noOfTokens":[60],"category":"","description":"cliff from Investors on X"}
            ]},"documentedData":{"data":[{"label":"All","data":[{"timestamp":"%d","unlocked":1000}]}]}}
            """.formatted(ts, ts);
        List<UnlockEvent> up = feed(fake(Map.of("aptos", stringTs))).upcoming(today);
        assertEquals(1, up.size(), "строковый timestamp разобран");
        assertEquals(unlockDay, up.get(0).unlockDay());
    }

    @Test void oneBadProtocolDoesNotBreakFeed() throws Exception {
        long today = 20000;
        var src = fake(Map.of(
                "aptos", emission("aptos", (today + 5) * 86400, "60", "from Investors on X", 1000),
                "broken", "{ not json"));
        assertEquals(1, feed(src).upcoming(today).size(), "битый протокол пропущен, фид жив");
    }

    /**
     * Тикер, на который претендуют два проекта, не берётся ВООБЩЕ (док. 130
     * §III п.7). Реальный случай: у `velodrome-finance` тикер VELO, на Kraken
     * есть PF_VELOUSD — но это Velo Labs, другая монета, и её расписание
     * разлоков к Velodrome отношения не имеет.
     */
    @Test void refusesTickerClaimedByTwoProjects() throws Exception {
        long today = 20000;
        long unlock = (today + 5) * 86400;
        Map<String, String> gecko = Map.of(
                "aptos", "APT",
                "velodrome-finance", "VELO",
                "velo", "VELO");                    // два проекта, один тикер
        UnlockFeed feed = new UnlockFeed(
                fake(Map.of("velodrome-finance", emission("velodrome-finance", unlock, "50", "investors", 1000))),
                gecko, Set.of("APT", "VELO"));

        assertTrue(feed.ambiguousTickers().contains("VELO"),
                "конфликт обязан быть виден в списке исключённых");
        assertEquals(0, feed.upcoming(today).size(),
                "чужое расписание не должно доехать до бота");
    }

    /** Однозначный тикер конфликтом не считается — иначе фид опустеет. */
    @Test void unambiguousTickerSurvives() {
        UnlockFeed feed = feed(fake(Map.of()));
        assertTrue(feed.ambiguousTickers().isEmpty(),
                "в обычной карте конфликтов нет: " + feed.ambiguousTickers());
    }

    /** Коллизии среди монет, которыми мы не торгуем, в предупреждение не попадают. */
    @Test void collisionOutsideKrakenIsNotReported() {
        UnlockFeed feed = new UnlockFeed(fake(Map.of()),
                Map.of("a", "APT", "x", "FOO", "y", "FOO"), Set.of("APT"));
        assertTrue(feed.ambiguousTickers().isEmpty(),
                "FOO не торгуется — шуметь про него незачем");
    }
}
