package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Экономкалендарь ForexFactory (док. 09 §2.6, проверен живым запросом) для модуля M1
 * (док. 05): окна запрета входов вокруг HIGH/MED событий. Файлы кэшируются на их
 * стороне — опрашивать не чаще раза в час.
 * available_at: НЕ обновляется при повторных загрузках (INSERT OR IGNORE) — событие
 * «известно» с момента первого появления в календаре, forecast/previous могут
 * уточняться, но для M1 важен только факт и время события.
 */
@Component
public class CalendarCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(CalendarCollector.class);

    // Только thisweek: ff_calendar_nextweek.json удалён на их стороне (404,
    // проверено 2026-07-17). События появляются при смене недели у FF; запас
    // по времени до ближайшего события — от нескольких часов до недели, для
    // окон запрета M1 этого достаточно.
    private static final List<String> URLS = List.of(
            "https://nfs.faireconomy.media/ff_calendar_thisweek.json");

    private static final String INSERT = """
            INSERT OR IGNORE INTO calendar_events(source, event_ts, country, title,
                impact, forecast, previous, available_at)
            VALUES('forexfactory',?,?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public CalendarCollector(Db db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    @Override
    public String name() {
        return "calendar";
    }

    @Override
    public void collect() {
        long now = System.currentTimeMillis();
        int total = 0;
        for (String url : URLS) {
            JsonNode arr = readTree(url);
            if (!arr.isArray()) {
                continue;
            }
            total += db.batch(INSERT, parseEvents(arr, now));
        }
        log.info("calendar: +{} новых событий", total);
    }

    static List<Object[]> parseEvents(JsonNode arr, long fetchedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode e : arr) {
            String date = e.path("date").asText(null);
            if (date == null) {
                continue;
            }
            long ts;
            try {
                ts = OffsetDateTime.parse(date).toInstant().toEpochMilli();
            } catch (java.time.format.DateTimeParseException ex) {
                continue;
            }
            rows.add(new Object[]{
                    ts, e.path("country").asText(""), e.path("title").asText(""),
                    e.path("impact").asText(null), e.path("forecast").asText(null),
                    e.path("previous").asText(null), fetchedAt
            });
        }
        return rows;
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
