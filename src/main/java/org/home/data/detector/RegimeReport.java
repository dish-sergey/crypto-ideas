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

    private static int stateIdx(String s) {
        return switch (s == null ? "" : s) {
            case "BULL" -> 0;
            case "RANGE" -> 1;
            case "BEAR" -> 2;
            default -> 3;
        };
    }

    private static Double d(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
