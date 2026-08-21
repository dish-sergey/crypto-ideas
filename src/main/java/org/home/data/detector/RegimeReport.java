package org.home.data.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Генератор самодостаточной HTML-страницы визуализации детектора режима:
 * цена BTC (лог) + полосы режима + score/компоненты. Данные встроены в файл,
 * внешних зависимостей нет — открывается локально и заливается на любой сервер.
 * CLI: --report=regime [--out=path]
 */
@Component
public class RegimeReport {

    private static final Logger log = LoggerFactory.getLogger(RegimeReport.class);

    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegimeReport(Db db) {
        this.db = db;
    }

    private record DayClose(String day, double close) {}
    private record Reg(String day, String state, Double score, Double c1, Double c2, Double c3, Double c4, Double c5) {}
    private record RegCmp(String day, String v1, String v2, Double d, Double t, Double s, String phase) {}
    private record RegV3(String day, String state, Double d, Double t, String phase, Double stress, Double breadth, Double conf) {}
    private record RegV5(String day, String state, Double distAtr, String phase, Double breadth, Double conf) {}
    private record RegAll(String day, String v1, String v3, String v5) {}

    public void generate(String outPath) {
        Map<String, Double> price = new HashMap<>();
        for (DayClose d : db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d'",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)))) {
            price.put(d.day(), d.close());
        }

        List<Reg> rows = db.query(
                "SELECT day, state, score, c1, c2, c3, c4, c5 FROM regime_daily ORDER BY day",
                rs -> new Reg(rs.getString("day"), rs.getString("state"), d(rs, "score"),
                        d(rs, "c1"), d(rs, "c2"), d(rs, "c3"), d(rs, "c4"), d(rs, "c5")));
        if (rows.isEmpty()) {
            log.warn("report: regime_daily пуст — сначала --backfill=regime");
            return;
        }

        List<Object[]> points = new ArrayList<>();
        for (Reg r : rows) {
            Double p = price.get(r.day());
            if (p == null) {
                continue;
            }
            points.add(new Object[]{r.day(), p, stateIdx(r.state()), r.score(),
                    r.c1(), r.c2(), r.c3(), r.c4(), r.c5()});
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", points.get(0)[0]);
        data.put("to", points.get(points.size() - 1)[0]);
        data.put("points", points);

        writeReport("regime-report.template.html", data, outPath, points.size());
    }

    /**
     * Отчёт детектора v3: цена BTC (лог) + полосы состояния {BULL,RANGE,BEAR,TRANSITION},
     * нижняя панель — оси D и T с порогами (T=0.40, |D|=0.20). Аналог отчёта v1,
     * но вместо скаляра score — две оси. Читает regime_daily_v3. CLI: --report=regime-v3.
     */
    public void generateV3(String outPath) {
        Map<String, Double> price = new HashMap<>();
        for (DayClose d : db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d'",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)))) {
            price.put(d.day(), d.close());
        }

        List<RegV3> rows = db.query(
                "SELECT day, state, d, t, cycle_phase, stress_level, breadth, confidence "
                        + "FROM regime_daily_v3 ORDER BY day",
                rs -> new RegV3(rs.getString("day"), rs.getString("state"), d(rs, "d"), d(rs, "t"),
                        rs.getString("cycle_phase"), d(rs, "stress_level"), d(rs, "breadth"), d(rs, "confidence")));
        if (rows.isEmpty()) {
            log.warn("report: regime_daily_v3 пуст — сначала --backfill=regime-v3");
            return;
        }

        List<Object[]> points = new ArrayList<>();
        for (RegV3 r : rows) {
            Double p = price.get(r.day());
            if (p == null) {
                continue;
            }
            points.add(new Object[]{r.day(), p, stateIdx(r.state()), r.d(), r.t(),
                    phaseShort(r.phase()), r.stress(), r.breadth(), r.conf()});
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", points.get(0)[0]);
        data.put("to", points.get(points.size() - 1)[0]);
        data.put("points", points);

        writeReport("regime-v3-report.template.html", data, outPath, points.size());
    }

    /**
     * Отчёт детектора v5 (прод): цена BTC (лог) + полосы {BULL,BEAR}, нижняя панель —
     * dist_atr = (close−SMA200)/ATR90. Читает regime_daily_v5. CLI: --report=regime-v5.
     */
    public void generateV5(String outPath) {
        Map<String, Double> price = new HashMap<>();
        for (DayClose d : db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d'",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)))) {
            price.put(d.day(), d.close());
        }
        List<RegV5> rows = db.query(
                "SELECT day, state, dist_atr, cycle_phase, breadth, confidence FROM regime_daily_v5 ORDER BY day",
                rs -> new RegV5(rs.getString("day"), rs.getString("state"), d(rs, "dist_atr"),
                        rs.getString("cycle_phase"), d(rs, "breadth"), d(rs, "confidence")));
        if (rows.isEmpty()) {
            log.warn("report: regime_daily_v5 пуст — сначала --backfill=regime-v5");
            return;
        }
        List<Object[]> points = new ArrayList<>();
        for (RegV5 r : rows) {
            Double p = price.get(r.day());
            if (p == null) {
                continue;
            }
            points.add(new Object[]{r.day(), p, stateIdx(r.state()), r.distAtr(),
                    phaseShort(r.phase()), r.breadth(), r.conf()});
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", points.get(0)[0]);
        data.put("to", points.get(points.size() - 1)[0]);
        data.put("points", points);
        writeReport("regime-v5-report.template.html", data, outPath, points.size());
    }

    /**
     * Общий отчёт: цена BTC (лог) + три ленты состояний v1 / v3 / v5. Джойн regime_daily,
     * regime_daily_v3, regime_daily_v5 по дню. CLI: --report=regime-all.
     */
    public void generateAll(String outPath) {
        Map<String, Double> price = new HashMap<>();
        for (DayClose d : db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d'",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)))) {
            price.put(d.day(), d.close());
        }
        List<RegAll> rows = db.query(
                "SELECT a.day, a.state v1, b.state v3, c.state v5 FROM regime_daily a "
                        + "JOIN regime_daily_v3 b USING(day) JOIN regime_daily_v5 c USING(day) ORDER BY a.day",
                rs -> new RegAll(rs.getString("day"), rs.getString("v1"), rs.getString("v3"), rs.getString("v5")));
        if (rows.isEmpty()) {
            log.warn("report: нет пересечения regime_daily/v3/v5 — прогони все три backfill");
            return;
        }
        List<Object[]> points = new ArrayList<>();
        for (RegAll r : rows) {
            Double p = price.get(r.day());
            if (p == null) {
                continue;
            }
            points.add(new Object[]{r.day(), p, stateIdx(r.v1()), stateIdx(r.v3()), stateIdx(r.v5())});
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", points.get(0)[0]);
        data.put("to", points.get(points.size() - 1)[0]);
        data.put("points", points);
        writeReport("regime-all.template.html", data, outPath, points.size());
    }

    /**
     * Сравнительный отчёт v1 vs v2: цена BTC (лог), фон — состояние v2, лента снизу —
     * v1, нижняя панель — оси D/T/S. Читает regime_daily (v1) и regime_daily_v2,
     * джойнит по дню. CLI: --report=regime-compare [--out=path].
     */
    public void generateCompare(String outPath) {
        Map<String, Double> price = new HashMap<>();
        for (DayClose d : db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d'",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)))) {
            price.put(d.day(), d.close());
        }

        List<RegCmp> rows = db.query(
                "SELECT a.day, a.state v1, b.state v2, b.d, b.t, b.s, b.cycle_phase "
                        + "FROM regime_daily a JOIN regime_daily_v2 b USING(day) ORDER BY a.day",
                rs -> new RegCmp(rs.getString("day"), rs.getString("v1"), rs.getString("v2"),
                        d(rs, "d"), d(rs, "t"), d(rs, "s"), rs.getString("cycle_phase")));
        if (rows.isEmpty()) {
            log.warn("report: нет пересечения regime_daily и regime_daily_v2 — прогони оба backfill");
            return;
        }

        List<Object[]> points = new ArrayList<>();
        for (RegCmp r : rows) {
            Double p = price.get(r.day());
            if (p == null) {
                continue;
            }
            points.add(new Object[]{r.day(), p, stateIdx(r.v1()), stateIdx(r.v2()),
                    r.d(), r.t(), r.s(), phaseShort(r.phase())});
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", points.get(0)[0]);
        data.put("to", points.get(points.size() - 1)[0]);
        data.put("points", points);

        writeReport("regime-compare.template.html", data, outPath, points.size());
    }

    /**
     * Главная страница дашборда: меню со всеми графиками режима, текущее состояние
     * каждой версии детектора и лента состояний за последний год. Ссылки ведут на
     * соседние файлы (regime-v5.html и т.д.) с якорем окна (#w=1m/3m/1y/all).
     * CLI: --report=regime-index [--out=path].
     */
    public void generateIndex(String outPath) {
        List<Map<String, Object>> cards = new ArrayList<>();
        addCard(cards, "regime_daily_v5", "regime-v5.html", "v5 — SMA200", "ПРОД",
                "close &gt; SMA200 → BULL, иначе BEAR. Ноль параметров, без гистерезиса. Нижняя панель — dist_atr.");
        addCard(cards, "regime_daily_v3", "regime-v3.html", "v3 — оси D/T", null,
                "Направление D и трендовость T с порогами (T=0.40, |D|=0.20), dwell 15 дней. Без CRASH.");
        addCard(cards, "regime_daily", "regime-v1.html", "v1 — композит C1–C5", null,
                "Скалярный score из пяти компонент + гистерезис FSM (вход ±0.30, выход ±0.10).");
        if (hasRows("regime_daily") && hasRows("regime_daily_v3") && hasRows("regime_daily_v5")) {
            cards.add(plainCard("regime-all.html", "Все версии — v1 / v3 / v5", null,
                    "Цена и три ленты состояний рядом: где версии расходятся."));
        }
        if (hasRows("regime_daily") && hasRows("regime_daily_v2")) {
            cards.add(plainCard("regime-compare.html", "Сравнение v1 vs v2", null,
                    "Фон — состояние v2, лента снизу — v1, нижняя панель — оси D/T/S."));
        }
        if (cards.isEmpty()) {
            log.warn("report: нет ни одной заполненной таблицы regime_daily* — сначала прогони --backfill=regime-v5");
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generated", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")));
        data.put("cards", cards);
        List<DayClose> tail = db.query(
                "SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time DESC LIMIT 31",
                rs -> new DayClose(rs.getString(1), rs.getDouble(2)));
        if (!tail.isEmpty()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("day", tail.get(0).day());
            p.put("close", tail.get(0).close());
            p.put("chg30", tail.size() > 30 ? tail.get(0).close() / tail.get(30).close() - 1 : null);
            data.put("price", p);
        }
        writeReport("regime-index.template.html", data, outPath, cards.size());
    }

    /**
     * Собирает весь дашборд в один каталог под каноническими именами:
     * index.html (меню) + regime-v5/v3/v1/all/compare.html. Пустые таблицы пропускаются.
     * CLI: --report=regime-dash [--out=каталог].
     */
    public void generateDash(String outDir) {
        Path dir = Path.of(outDir);
        generateV5(dir.resolve("regime-v5.html").toString());
        generateV3(dir.resolve("regime-v3.html").toString());
        generate(dir.resolve("regime-v1.html").toString());
        generateAll(dir.resolve("regime-all.html").toString());
        generateCompare(dir.resolve("regime-compare.html").toString());
        generateIndex(dir.resolve("index.html").toString());
        log.info("dash: готово -> {}", dir.toAbsolutePath());
    }

    private static final int SPARK_DAYS = 365;

    private void addCard(List<Map<String, Object>> cards, String table, String file,
                         String title, String tag, String desc) {
        if (!hasRows(table)) {
            return;
        }
        Map<String, Object> c = plainCard(file, title, tag, desc);
        List<String[]> tail = db.query(
                "SELECT day, state FROM " + table + " ORDER BY day DESC LIMIT " + SPARK_DAYS,
                rs -> new String[]{rs.getString(1), rs.getString(2)});
        java.util.Collections.reverse(tail);
        String state = tail.get(tail.size() - 1)[1];
        int since = 1;
        for (int i = tail.size() - 2; i >= 0; i--) {
            if (!java.util.Objects.equals(tail.get(i)[1], state)) {
                break;
            }
            since++;
        }
        c.put("state", state);
        c.put("stIdx", stateIdx(state));
        c.put("day", tail.get(tail.size() - 1)[0]);
        c.put("since", tail.get(Math.max(0, tail.size() - since))[0]);
        c.put("sinceDays", since);
        c.put("sinceCapped", since >= tail.size());
        c.put("from", db.queryStrings("SELECT min(day) FROM " + table).stream().findFirst().orElse(null));
        c.put("n", db.queryLong("SELECT count(*) FROM " + table));
        List<Integer> spark = new ArrayList<>(tail.size());
        for (String[] r : tail) {
            spark.add(stateIdx(r[1]));
        }
        c.put("spark", spark);
        cards.add(c);
    }

    private static Map<String, Object> plainCard(String file, String title, String tag, String desc) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("file", file);
        c.put("title", title);
        c.put("tag", tag);
        c.put("desc", desc);
        return c;
    }

    private boolean hasRows(String table) {
        Long t = db.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", table);
        if (t == null || t == 0) {
            return false;
        }
        Long n = db.queryLong("SELECT count(*) FROM " + table);
        return n != null && n > 0;
    }

    private void writeReport(String template, Map<String, Object> data, String outPath, int nPoints) {
        try {
            String tpl = new String(new ClassPathResource(template)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String html = tpl.replace("__DATA__", mapper.writeValueAsString(data))
                    .replace("__WINDOW_JS__", windowJs());
            Path out = Path.of(outPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, html);
            log.info("report: {} точек -> {}", nPoints, out.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать отчёт: " + outPath, e);
        }
    }

    /** Общий JS выбора окна времени (regime-window.js), встраивается в каждый отчёт. */
    private String windowJs() {
        if (windowJs == null) {
            try {
                windowJs = new String(new ClassPathResource("regime-window.js")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("не найден regime-window.js", e);
            }
        }
        return windowJs;
    }

    private String windowJs;

    private static String phaseShort(String phase) {
        return switch (phase == null ? "" : phase) {
            case "ACCUMULATION" -> "ACC";
            case "EARLY" -> "EARLY";
            case "MID" -> "MID";
            case "LATE" -> "LATE";
            case "EUPHORIA" -> "EUPH";
            default -> null;
        };
    }

    private static int stateIdx(String s) {
        return switch (s == null ? "" : s) {
            case "BULL" -> 0;
            case "RANGE" -> 1;
            case "BEAR" -> 2;
            case "CRASH" -> 4;
            default -> 3;
        };
    }

    private static Double d(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
