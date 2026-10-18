package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ЗАЩИТА ПО КАЛЕНДАРЮ (владелец 17.10.2026). Живые журналы 11–16.10: в 12:30:02 UTC 14.10 (CPI) и
 * 15.10 (PPI, розничные продажи) цена прыгнула за секунду, и трёхуровневые d/e/f купили 14 раз на
 * −18…−65 б.п. от Бинанса заявками возрастом 1–10 с — двигать было некогда, а парковка по размаху
 * включается ПОСЛЕ скачка. Время выхода известно заранее, поэтому ближние уровни уводятся за
 * {@code before} до события и возвращаются через {@code after} после.
 *
 * ДЛИННЫЕ СОБЫТИЯ ({@code revx.exec.news-long}, по умолчанию {@code Press Conference=4500}):
 * пресс-конференция ФРС — не скачок, а час болтанки. 30 заседаний 2023–2026 по секундным свечам
 * Бинанса: размах за 75 мин BTC 176 б.п. (типично) против 65 в те же часы неделей раньше, самая
 * сильная минута — то в первые 5–10 мин, то на 35–63-й (вопросы), до 285 б.п. за минуту у ETH.
 * Окно такого события — {@code before} до начала и N секунд после.
 *
 * Источник — недельный файл ForexFactory ({@code ff_calendar_thisweek.json}: {@code title},
 * {@code country}, {@code date} со смещением, {@code impact}), который раз в час кладёт в общий
 * каталог крон на сервере ботов. Берутся события заданной важности и стран; выступления
 * («Speaks») не берутся — у них нет точного момента, — праздники тоже. Файл перечитывается при
 * смене времени изменения, не чаще раза в минуту; пропавший или битый файл защиту не включает
 * (бот торгует как без неё), а сводка об этом предупреждает.
 */
public final class NewsGuard {

    /** Событие календаря: момент, подпись и сколько после него держать окно. */
    public record Event(long tsMs, String country, String title, long afterMs) {
        public long endMs() {
            return tsMs + afterMs;
        }
    }

    private final Path file;
    private final long beforeMs;
    private final long afterMs;
    private final Set<String> impacts;
    private final Set<String> countries;
    private final Map<String, Long> longAfterMs;
    private final ObjectMapper mapper = new ObjectMapper();
    private List<Event> events = List.of();
    private long fileMtime = Long.MIN_VALUE;
    private long checkedMs = Long.MIN_VALUE;

    public NewsGuard(Path file, long beforeMs, long afterMs, Set<String> impacts, Set<String> countries,
                     Map<String, Long> longAfterMs) {
        this.file = file;
        this.beforeMs = beforeMs;
        this.afterMs = afterMs;
        this.impacts = impacts;
        this.countries = countries;
        this.longAfterMs = longAfterMs;
    }

    /** Из системных свойств {@code revx.exec.news-*}; {@code null}, если файл не задан. */
    public static NewsGuard fromProperties() {
        String f = System.getProperty("revx.exec.news-file", "");
        if (f.isBlank()) {
            return null;
        }
        return new NewsGuard(Path.of(f),
                Long.getLong("revx.exec.news-before-sec", 30L) * 1000L,
                Long.getLong("revx.exec.news-after-sec", 90L) * 1000L,
                Set.of(System.getProperty("revx.exec.news-impact", "High").split(",")),
                Set.of(System.getProperty("revx.exec.news-countries", "USD").split(",")),
                parseLong(System.getProperty("revx.exec.news-long", "Press Conference=4500")));
    }

    /** {@code "Press Conference=4500,..."} → подстрока заголовка → мс после начала. */
    static Map<String, Long> parseLong(String s) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String part : s.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                        Long.parseLong(part.substring(eq + 1).trim()) * 1000L);
            }
        }
        return m;
    }

    public long beforeMs() {
        return beforeMs;
    }

    /** Возраст файла календаря, мс; {@code -1}, если файла нет. */
    public long fileAgeMs(long now) {
        try {
            return now - Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    /** Событие, окно которого [начало − before, конец] накрывает момент, иначе {@code null}. */
    public synchronized Event active(long now) {
        return within(now, beforeMs);
    }

    /** Событие, у которого {@code now} попадает в [начало − lead, конец]; для «прийти пустым». */
    public synchronized Event within(long now, long leadMs) {
        reload(now);
        for (Event e : events) {
            if (now >= e.tsMs() - leadMs && now <= e.endMs()) {
                return e;
            }
        }
        return null;
    }

    /** Ближайшее событие, окно которого ещё не кончилось; {@code null}, если такого нет. */
    public synchronized Event next(long now) {
        reload(now);
        for (Event e : events) {
            if (e.endMs() >= now) {
                return e;
            }
        }
        return null;
    }

    private void reload(long now) {
        if (checkedMs != Long.MIN_VALUE && now - checkedMs < 60_000L && now >= checkedMs) {
            return;
        }
        checkedMs = now;
        try {
            long m = Files.getLastModifiedTime(file).toMillis();
            if (m == fileMtime) {
                return;
            }
            events = parse(mapper.readTree(Files.readString(file)), impacts, countries, afterMs, longAfterMs);
            fileMtime = m;
        } catch (Exception e) {
            // нет файла или битый — оставляем прежний список (пустой на старте)
        }
    }

    /** Отбор событий из массива ForexFactory, по времени. Одинаковый момент — одно событие. */
    static List<Event> parse(JsonNode arr, Set<String> impacts, Set<String> countries, long afterMs,
                             Map<String, Long> longAfterMs) {
        List<Event> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode e : arr) {
            String title = e.path("title").asText("");
            String country = e.path("country").asText("");
            String lower = title.toLowerCase(Locale.ROOT);
            if (!impacts.contains(e.path("impact").asText("")) || !countries.contains(country)
                    || lower.contains("speaks")) {
                continue;
            }
            long ts;
            try {
                ts = OffsetDateTime.parse(e.path("date").asText("")).toInstant().toEpochMilli();
            } catch (Exception ex) {
                continue;
            }
            long after = afterMs;
            for (var l : longAfterMs.entrySet()) {
                if (lower.contains(l.getKey())) {
                    after = Math.max(after, l.getValue());
                }
            }
            Event prev = out.stream().filter(x -> x.tsMs() == ts).findFirst().orElse(null);
            if (prev == null) {
                out.add(new Event(ts, country, title, after));
            } else {
                // по целым заголовкам: «CPI m/m» — подстрока «Core CPI m/m», но это другая строка
                String t = java.util.Arrays.asList(prev.title().split(", ")).contains(title)
                        ? prev.title() : prev.title() + ", " + title;
                out.set(out.indexOf(prev), new Event(ts, country, t, Math.max(after, prev.afterMs())));
            }
        }
        out.sort(java.util.Comparator.comparingLong(Event::tsMs));
        return List.copyOf(out);
    }
}
