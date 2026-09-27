package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-budget-facts}: ФАКТЫ О БЮДЖЕТЕ ПОСТАНОВОК ПО ЖУРНАЛАМ — пункты Л2 и
 * Л4 из 192. Только чтение журналов, в сеть не ходит.
 *
 * <h2>Л2: доля темноты</h2>
 *
 * Время каждого бот-дня раскладывается на состояния по событиям журнала:
 * <ul>
 *   <li><b>котирует</b> — котирование включено и тик с {@code quotable = 1};</li>
 *   <li><b>пауза</b> — включено, но тик с {@code quotable = 0} (гейт, отвод на
 *       затыке, заморозка резерва): бот сам ничего не ставит;</li>
 *   <li><b>темно по бюджету</b> — от остановки, перед которой было
 *       {@code limit_blocked}, до следующего {@code start}. Тиков в это время нет
 *       вовсе (CLAUDE.md, A88), поэтому считается по событиям, а не по тикам;</li>
 *   <li><b>перезапуск</b> — от {@code boot} до {@code start};</li>
 *   <li><b>выключен</b> — остальные остановки (ручной {@code /stop}, стоп по
 *       убытку и т.п.).</li>
 * </ul>
 * Запас в момент бюджетной остановки — последний тик до неё, в лотах.
 *
 * <h2>Л4: на что уходят постановки</h2>
 *
 * Постановка ({@code POST /orders}) нужна боту только тогда, когда слот ПУСТ:
 * перестановка за ценой и сдвиг скосом идут заменой ({@code PUT}), у которой
 * суточного лимита нет. Поэтому постановка раскладывается по тому, почему слот
 * опустел: исполнение, отмена (с причиной из события), старт, повтор после
 * отказа. 429 — отдельно: такие счётчик бота не считает (A91).
 */
@Component
@Lazy
public class BudgetFacts {

    private static final Logger log = LoggerFactory.getLogger(BudgetFacts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY = 86_400_000L;
    private static final Pattern SIDE = Pattern.compile("\"side\"\\s*:\\s*\"(buy|sell)\"");
    private static final Pattern REASON = Pattern.compile("\\(([^)]*)\\)");

    private record Ev(long ts, String kind, String detail) {
    }

    public void run(String journals, String fromIso, String toIso, String out) throws Exception {
        long from = Instant.parse(fromIso).toEpochMilli();
        long to = Instant.parse(toIso).toEpochMilli();
        StringBuilder sb = new StringBuilder();
        sb.append("# Л2 и Л4: темнота и расход постановок по журналам\n\n")
                .append("окно ").append(fromIso).append(" → ").append(toIso)
                .append("; журналы: ").append(journals).append("\n\n");
        StringBuilder l2 = new StringBuilder();
        StringBuilder l2days = new StringBuilder();
        StringBuilder l4 = new StringBuilder();
        StringBuilder stops = new StringBuilder();
        StringBuilder cfg = new StringBuilder();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            analyse(kv[0].trim(), kv[1].trim(), from, to, l2, l2days, l4, stops, cfg);
        }
        sb.append("## Настройки ботов (из последнего `boot` в окне)\n\n")
                .append("| бот | пара | лот | потолок, лотов | отступ, б.п. | уровней | шаг | скос k | цель | предел постановок |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n").append(cfg);
        sb.append("\n## Л2. Доля времени по состояниям (всё окно)\n\n")
                .append("| бот | суток | котирует | пауза | **темно по бюджету** | перезапуск | выключен | бюджетных остановок | медиана темноты |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n").append(l2);
        sb.append("\n### Л2 по бот-суткам (доля темноты по бюджету; пусто — бот в эти сутки не существовал)\n\n")
                .append("| бот | сутки | котирует | пауза | темно | перезапуск | выключен | постановок |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|\n").append(l2days);
        sb.append("\n### Бюджетные остановки: запас в момент остановки\n\n")
                .append("| бот | остановка | до следующего старта | запас, лотов |\n|---|---|---:|---:|\n")
                .append(stops.isEmpty() ? "| — | нет | | |\n" : stops);
        sb.append("\n## Л4. На что уходят постановки (`POST`)\n\n")
                .append("Перестановка за ценой и сдвиг скосом идут заменой (`PUT`) — суточного "
                        + "лимита у неё нет; постановка нужна, только когда слот пуст.\n\n")
                .append("| бот | постановок (без 429) | после исполнения | старт | после отмены | повтор после отказа | прочее | 429 | замен (`PUT`) в сутки |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n").append(l4);
        String text = sb.toString();
        if (out == null || out.isBlank()) {
            log.info("\n{}", text);
            return;
        }
        Path p = Path.of(out);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, text, StandardCharsets.UTF_8);
        log.info("отчёт: {}", p.toAbsolutePath());
    }

    private void analyse(String bot, String path, long from, long to, StringBuilder l2,
                         StringBuilder l2days, StringBuilder l4, StringBuilder stops,
                         StringBuilder cfg) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro")) {
            List<Ev> ev = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, kind, detail FROM exec_event WHERE ts_ms < ? ORDER BY ts_ms")) {
                ps.setLong(1, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ev.add(new Ev(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            // Настройки — из машинной части последнего boot в окне.
            JsonNode boot = null;
            String symbol = "?";
            for (Ev e : ev) {
                if ("boot".equals(e.kind()) && e.ts() < to && e.detail() != null
                        && e.detail().contains("|")) {
                    try {
                        boot = MAPPER.readTree(e.detail().substring(e.detail().indexOf('|') + 1));
                    } catch (Exception ignore) {
                        // старые записи без машинной части
                    }
                }
            }
            double lot = boot == null ? 0 : boot.path("size").asDouble();
            if (boot != null) {
                symbol = boot.path("symbol").asText();
                double cap = boot.path("inventoryCap").asDouble();
                cfg.append(String.format(Locale.ROOT,
                        "| %s | %s | %s | %.2f | %.1f | %d | %.1f | %s | %.2f | %s |%n", bot, symbol,
                        boot.path("size").asText(), lot > 0 ? cap / lot : 0,
                        boot.path("offset").asDouble() * 1e4, boot.path("levels").asInt(1),
                        boot.path("levelStep").asDouble() * 1e4, boot.path("skewK").asText(),
                        boot.path("skewTarget").asDouble(), placementCap(ev)));
            }

            // --- Л2: интервалы состояний по событиям и тикам.
            long start = Math.max(from, firstTs(c));
            if (start >= to) {
                return;
            }
            // Состояние «включено» и причина последнего выключения на момент start.
            boolean on = false;
            String offKind = "выключен";
            long lastBlocked = Long.MIN_VALUE;
            int ei = 0;
            for (; ei < ev.size() && ev.get(ei).ts() < start; ei++) {
                Ev e = ev.get(ei);
                if (placementBlocked(e)) {
                    lastBlocked = e.ts();
                }
                if ("start".equals(e.kind())) {
                    on = true;
                } else if ("stop".equals(e.kind()) || "boot".equals(e.kind())) {
                    on = false;
                    offKind = offReason(e, lastBlocked);
                }
            }
            // Тики: quotable по времени (для паузы внутри «включено»).
            TreeMap<Long, Boolean> quotable = new TreeMap<>();
            TreeMap<Long, Double> inventory = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, quotable, inventory FROM exec_quote WHERE ts_ms BETWEEN ? AND ? "
                            + "ORDER BY ts_ms")) {
                ps.setLong(1, start - 600_000L);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        quotable.put(rs.getLong(1), rs.getInt(2) == 1);
                        inventory.put(rs.getLong(1), rs.getDouble(3));
                    }
                }
            }
            // Проходим окно шагом в секунду с событиями — дешевле, чем интервальная
            // арифметика, и не ошибается на пересечениях.
            Map<LocalDate, double[]> byDay = new TreeMap<>();   // {котир, пауза, темно, рестарт, выкл}
            double[] total = new double[5];
            List<Long> darkLen = new ArrayList<>();
            long darkSince = !on && "темно".equals(offKind) ? start : -1;
            int budgetStops = 0;
            long step = 1000;
            for (long t = start; t < to; t += step) {
                while (ei < ev.size() && ev.get(ei).ts() <= t) {
                    Ev e = ev.get(ei++);
                    if (placementBlocked(e)) {
                        lastBlocked = e.ts();
                    }
                    if ("start".equals(e.kind())) {
                        if (!on && darkSince >= 0) {
                            darkLen.add(e.ts() - darkSince);
                            darkSince = -1;
                        }
                        on = true;
                    } else if (("stop".equals(e.kind()) || "boot".equals(e.kind())) && on) {
                        on = false;
                        offKind = offReason(e, lastBlocked);
                        if ("темно".equals(offKind)) {
                            budgetStops++;
                            darkSince = e.ts();
                            Map.Entry<Long, Double> inv = inventory.floorEntry(e.ts());
                            stops.append(String.format(Locale.ROOT, "| %s | %s | %%s | %.2f |%n",
                                    bot, Instant.ofEpochMilli(e.ts()).toString().substring(0, 16),
                                    inv == null || lot <= 0 ? Double.NaN : inv.getValue() / lot));
                        }
                    }
                }
                int state;
                if (on) {
                    Map.Entry<Long, Boolean> q = quotable.floorEntry(t);
                    boolean fresh = q != null && t - q.getKey() < 60_000L;
                    state = fresh && q.getValue() ? 0 : 1;
                } else {
                    state = switch (offKind) {
                        case "темно" -> 2;
                        case "перезапуск" -> 3;
                        default -> 4;
                    };
                }
                total[state] += step;
                LocalDate d = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
                byDay.computeIfAbsent(d, k -> new double[5])[state] += step;
            }
            if (darkSince >= 0) {
                darkLen.add(to - darkSince);
            }
            // Длительности подставляем в строки остановок по порядку.
            fillDurations(stops, bot, darkLen);

            double sum = total[0] + total[1] + total[2] + total[3] + total[4];
            Collections.sort(darkLen);
            l2.append(String.format(Locale.ROOT,
                    "| %s | %.1f | %.1f%% | %.1f%% | **%.1f%%** | %.1f%% | %.1f%% | %d | %s |%n",
                    bot, sum / DAY, pct(total[0], sum), pct(total[1], sum), pct(total[2], sum),
                    pct(total[3], sum), pct(total[4], sum), budgetStops,
                    darkLen.isEmpty() ? "—" : hours(darkLen.get(darkLen.size() / 2))));

            // Постановки по суткам.
            Map<LocalDate, Integer> postsDay = new TreeMap<>();
            // --- Л4.
            List<long[]> fills = new ArrayList<>();   // {ts, +1 buy / −1 sell}
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, side FROM exec_fill WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms")) {
                ps.setLong(1, from - 120_000L);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fills.add(new long[]{rs.getLong(1),
                                "SELL".equalsIgnoreCase(rs.getString(2)) ? -1 : 1});
                    }
                }
            }
            int nPost = 0;
            int afterFill = 0;
            int atStart = 0;
            int retry = 0;
            int other = 0;
            int throttled = 0;
            Map<String, Integer> afterCancel = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, body, status FROM exec_request WHERE method = 'POST' "
                            + "AND path LIKE '%/orders' AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong(1);
                        Integer status = (Integer) rs.getObject(3);
                        if (status != null && status == 429) {
                            throttled++;
                            continue;
                        }
                        nPost++;
                        postsDay.merge(Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate(),
                                1, Integer::sum);
                        String body = rs.getString(2);
                        Matcher m = body == null ? null : SIDE.matcher(body);
                        int side = m != null && m.find() ? ("sell".equals(m.group(1)) ? -1 : 1) : 0;
                        String cause = cause(t, side, ev, fills);
                        switch (cause) {
                            case "fill" -> afterFill++;
                            case "start" -> atStart++;
                            case "retry" -> retry++;
                            case "other" -> other++;
                            default -> afterCancel.merge(cause, 1, Integer::sum);
                        }
                    }
                }
            }
            long puts = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM exec_request WHERE method = 'PUT' AND ts_ms BETWEEN ? AND ?")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    puts = rs.next() ? rs.getLong(1) : 0;
                }
            }
            int cancels = afterCancel.values().stream().mapToInt(Integer::intValue).sum();
            double onDays = (total[0] + total[1]) / DAY;
            l4.append(String.format(Locale.ROOT,
                    "| %s | %d | %d (%.0f%%) | %d (%.0f%%) | %d (%.0f%%) | %d (%.0f%%) | %d (%.0f%%) | %d | %.0f |%n",
                    bot, nPost, afterFill, pct(afterFill, nPost), atStart, pct(atStart, nPost),
                    cancels, pct(cancels, nPost), retry, pct(retry, nPost), other, pct(other, nPost),
                    throttled, onDays > 0 ? puts / onDays : 0));
            if (!afterCancel.isEmpty()) {
                StringBuilder why = new StringBuilder();
                afterCancel.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .forEach(e -> why.append(e.getValue()).append(" — ").append(e.getKey())
                                .append("; "));
                l4.append("|  | ↳ отмены: ").append(why).append(" | | | | | | | |\n");
            }
            for (Map.Entry<LocalDate, double[]> d : byDay.entrySet()) {
                double[] v = d.getValue();
                double s = v[0] + v[1] + v[2] + v[3] + v[4];
                l2days.append(String.format(Locale.ROOT,
                        "| %s | %s | %.0f%% | %.0f%% | %s | %.0f%% | %.0f%% | %d |%n",
                        bot, d.getKey(), pct(v[0], s), pct(v[1], s),
                        v[2] > 0 ? String.format(Locale.ROOT, "**%.0f%%**", pct(v[2], s)) : "0%",
                        pct(v[3], s), pct(v[4], s), postsDay.getOrDefault(d.getKey(), 0)));
            }
        }
    }

    /**
     * Почему опустел слот, в который пошла постановка. Порядок проверок важен:
     * старт первым (после него слоты пусты все сразу), потом исполнение той же
     * стороны, потом отмена той же стороны, потом отказ.
     */
    private static String cause(long t, int side, List<Ev> ev, List<long[]> fills) {
        String sideWord = side < 0 ? "SELL" : "BUY";
        String cancelReason = null;
        boolean failed = false;
        boolean started = false;
        for (int i = ev.size() - 1; i >= 0; i--) {
            Ev e = ev.get(i);
            if (e.ts() > t) {
                continue;
            }
            if (t - e.ts() > 180_000L) {
                break;
            }
            switch (e.kind()) {
                case "start", "boot" -> {
                    if (t - e.ts() <= 120_000L) {
                        started = true;
                    }
                }
                case "cancel", "stray_cancel" -> {
                    if (cancelReason == null && e.detail() != null
                            && e.detail().startsWith(sideWord)) {
                        Matcher m = REASON.matcher(e.detail());
                        cancelReason = m.find() ? bucket(m.group(1)) : "без причины";
                    }
                }
                case "place_failed" -> {
                    if (t - e.ts() <= 60_000L) {
                        failed = true;
                    }
                }
                default -> {
                }
            }
        }
        if (started) {
            return "start";
        }
        for (int i = fills.size() - 1; i >= 0; i--) {
            long[] f = fills.get(i);
            if (f[0] > t) {
                continue;
            }
            if (t - f[0] > 60_000L) {
                break;
            }
            if (side == 0 || f[1] == side) {
                return "fill";
            }
        }
        if (cancelReason != null) {
            return cancelReason;
        }
        return failed ? "retry" : "other";
    }

    /** Причины отмен — к нескольким корзинам, иначе таблица утонет в числах. */
    private static String bucket(String reason) {
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("тормозит")) {
            return "затык площадки";
        }
        if (r.contains("курс")) {
            return "курс ненадёжен";
        }
        if (r.contains("нечем")) {
            return "нечем котировать";
        }
        if (r.contains("сверк")) {
            return "плановая сверка";
        }
        if (r.contains("отказ")) {
            return "отказ замены/постановки";
        }
        if (r.contains("останов")) {
            return "остановка";
        }
        return reason.length() > 40 ? reason.substring(0, 40) : reason;
    }

    /**
     * Почему выключился: {@code boot} — перезапуск процесса; {@code stop} сразу
     * после {@code limit_blocked} (±5 с) — темнота по бюджету; иначе — выключен.
     */
    private static String offReason(Ev e, long lastBlocked) {
        if ("boot".equals(e.kind())) {
            return "перезапуск";
        }
        return Math.abs(e.ts() - lastBlocked) <= 5_000L ? "темно" : "выключен";
    }

    private static void fillDurations(StringBuilder stops, String bot, List<Long> darkLen) {
        String s = stops.toString();
        StringBuilder outSb = new StringBuilder();
        int k = 0;
        for (String line : s.split("\n", -1)) {
            if (line.startsWith("| " + bot + " |") && line.contains("%s")) {
                line = line.replace("%s", k < darkLen.size() ? hours(darkLen.get(k)) : "?");
                k++;
            }
            outSb.append(line).append('\n');
        }
        stops.setLength(0);
        stops.append(outSb.toString().replaceAll("\n+$", "\n"));
    }

    /**
     * Отказ ПО ПОСТАНОВКАМ. ⚠️ {@code limit_blocked} пишется и по денежным пределам
     * («нотионал», «экспозиция»); остановка рядом с ними — не темнота по бюджету.
     */
    private static boolean placementBlocked(Ev e) {
        return "limit_blocked".equals(e.kind()) && e.detail() != null
                && e.detail().contains("постановк");
    }

    /** Предел постановок бота — из текста limit_blocked («… 100 из 100»). */
    private static String placementCap(List<Ev> ev) {
        Pattern p = Pattern.compile("из (\\d+)");
        for (int i = ev.size() - 1; i >= 0; i--) {
            Ev e = ev.get(i);
            if ("limit_blocked".equals(e.kind()) && e.detail() != null) {
                Matcher m = p.matcher(e.detail());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return "?";
    }

    private static long firstTs(Connection c) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT MIN(ts_ms) FROM exec_event")) {
            return rs.next() ? rs.getLong(1) : Long.MAX_VALUE;
        }
    }

    private static double pct(double a, double b) {
        return b > 0 ? 100.0 * a / b : 0;
    }

    private static String hours(long ms) {
        return String.format(Locale.ROOT, "%.1f ч", ms / 3_600_000.0);
    }
}
