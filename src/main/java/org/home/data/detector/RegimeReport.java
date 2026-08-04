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

        try {
            String tpl = new String(new ClassPathResource("regime-report.template.html")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String html = tpl.replace("__DATA__", mapper.writeValueAsString(data));
            Path out = Path.of(outPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, html);
            log.info("report: {} точек -> {}", points.size(), out.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать отчёт: " + outPath, e);
        }
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

    private void writeReport(String template, Map<String, Object> data, String outPath, int nPoints) {
        try {
            String tpl = new String(new ClassPathResource(template)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String html = tpl.replace("__DATA__", mapper.writeValueAsString(data));
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
