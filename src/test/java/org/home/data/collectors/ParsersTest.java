package org.home.data.collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Парсеры проверяются на реальных фрагментах ответов (сняты живыми запросами, док. 09). */
class ParsersTest {

    @Test
    void fredCsv() {
        String csv = """
                observation_date,DFF
                2026-07-01,3.58
                2026-07-02,.
                2026-07-03,3.58
                мусорная строка
                """;
        List<Object[]> rows = MacroCollector.parseCsv("DFF", csv);
        assertEquals(2, rows.size());
        assertEquals("2026-07-01", rows.get(0)[1]);
        assertEquals(3.58, (Double) rows.get(0)[2], 1e-9);
        // available_at = день + 1 сутки
        assertEquals(java.time.Instant.parse("2026-07-02T00:00:00Z").toEpochMilli(), rows.get(0)[3]);
    }

    @Test
    void forexFactoryEvents() throws Exception {
        String json = """
                [{"title":"Core CPI m/m","country":"USD","date":"2026-07-14T08:30:00-04:00",
                  "impact":"High","forecast":"0.2%","previous":"0.2%"},
                 {"title":"без даты","country":"USD"}]
                """;
        List<Object[]> rows = CalendarCollector.parseEvents(new ObjectMapper().readTree(json), 123L);
        assertEquals(1, rows.size());
        assertEquals("Core CPI m/m", rows.get(0)[2]);
        assertEquals(java.time.Instant.parse("2026-07-14T12:30:00Z").toEpochMilli(), rows.get(0)[0]);
        assertEquals("High", rows.get(0)[3]);
    }

    @Test
    void rssItems() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Feed</title>
                <item><title>Новость 1</title><link>https://x/a</link>
                  <guid>guid-1</guid><pubDate>Tue, 14 Jul 2026 08:30:00 GMT</pubDate></item>
                <item><title>Без guid</title><link>https://x/b</link></item>
                </channel></rss>
                """;
        List<Object[]> rows = NewsRssCollector.parseRss("x.com", xml, 42L);
        assertEquals(2, rows.size());
        assertEquals("guid-1", rows.get(0)[1]);
        assertEquals("https://x/b", rows.get(1)[1]); // fallback guid = link
        assertNull(rows.get(1)[2]);                  // pubDate отсутствует
        assertTrue((Long) rows.get(0)[2] > 0);
    }

    @Test
    void bybitSubscribe() {
        assertEquals("{\"op\":\"subscribe\",\"args\":[\"allLiquidation.BTCUSDT\",\"allLiquidation.ETHUSDT\"]}",
                org.home.data.ws.LiquidationWsCollector.bybitSubscribeMessage(List.of("BTCUSDT", "ETHUSDT")));
    }
}
