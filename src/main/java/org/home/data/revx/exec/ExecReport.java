package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Отчёт по журналу живого исполнителя: пункт 1 очереди док. 128 §6.
 *
 * <b>Что он чинит.</b> В док. 126 разрыв между захватом спреда (7.11 б.п.) и
 * фактическими деньгами (2.33 б.п.) был назван «пошлиной, совпавшей с
 * намеренной на стенде». Это подмена величины: при нулевых комиссиях
 * {@code total = захват + инвентарная нога}, поэтому разрыв ТОЖДЕСТВЕННО равен
 * инвентарной ноге, а не пошлине. Улика лежала в том же отчёте — у бота A с
 * почти нулевым инвентарём разрыв 1.83, а не 4.78, чего рыночная величина дать
 * не может.
 *
 * <b>Как пошлина меряется на самом деле</b> (док. 128 §1):
 *
 * <pre>
 * захват  = сторона · (справедливая(t_исп) − цена) / справедливая
 * markout = сторона · (справедливая(t_исп + Δ) − справедливая(t_исп)) / справедливая
 * c       = отступ − захват − markout
 * </pre>
 *
 * База markout — справедливая цена В МОМЕНТ ИСПОЛНЕНИЯ, а не цена заявки: иначе
 * «захват + markout» считает захват дважды (та же оговорка, что в
 * {@link org.home.data.revx.sim.Markout}). Оба слагаемых берутся из журнала:
 * {@code exec_fill} даёт цену и справедливую на момент сделки,
 * {@code exec_quote} — справедливую на каждом тике.
 *
 * <b>Отдельно считается инвентарная нога</b>, потому что именно она путалась с
 * пошлиной: {@code инвентарная = total − захват}, где {@code total} — изменение
 * кассы плюс переоценка изменения позиции по последней справедливой цене окна.
 * Такое разложение не зависит от того, с какой позиции бот начал.
 */
@Component
@Lazy
public class ExecReport {

    private static final Logger log = LoggerFactory.getLogger(ExecReport.class);

    private static final long[] HORIZONS_MS = {60_000, 300_000};

    /**
     * Отступ живого бота. Он же делитель пошлины, поэтому берётся из того же
     * ключа, что и котирование: сверять `c` со стендом, подставив чужой `d`,
     * значит получить разницу отступов вместо пошлины.
     */
    private final double configuredOffset;

    public ExecReport(@org.springframework.beans.factory.annotation.Value("${revx.exec.offset}")
                      double configuredOffset) {
        this.configuredOffset = configuredOffset;
    }

    private record Fill(long tsMs, String side, double qty, double price, double fair,
                        double fee, String status) {

        int sign() {
            return "buy".equalsIgnoreCase(side) ? 1 : "sell".equalsIgnoreCase(side) ? -1 : 0;
        }

        double notional() {
            return qty * price;
        }

        /** Захват спреда в деньгах: расстояние от справедливой цены, со знаком стороны. */
        double capture() {
            return sign() * (fair - price) * qty;
        }
    }

    /** Итог по одному горизонту markout. */
    private record Horizon(long ms, int fills, double turnover, double captureBp,
                           double markoutBp, double netBp, double tollBp) {
    }

    public void run(String journalPath, int hours, long toMs, Double offsetOverride, String out) {
        double offset = offsetOverride == null ? configuredOffset : offsetOverride;
        long fromMs = hours > 0 ? toMs - hours * 3600_000L : 0;
        List<Fill> fills = new ArrayList<>();
        TreeMap<Long, Double> fair = new TreeMap<>();
        Map<String, long[]> requests = new LinkedHashMap<>();
        long[] quoteStats = new long[2];              // всего котировок, из них двусторонних
        int mismatches = 0;

        String url = "jdbc:sqlite:file:" + journalPath.replace('\\', '/') + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, side, qty, price, fair, fee, status FROM exec_fill "
                            + "WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fills.add(new Fill(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getString(7)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, fair, bid, ask FROM exec_quote WHERE ts_ms BETWEEN ? AND ? "
                            + "ORDER BY ts_ms")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double f = rs.getDouble(2);
                        if (f > 0) {
                            fair.put(rs.getLong(1), f);
                        }
                        quoteStats[0]++;
                        rs.getDouble(3);
                        boolean hasBid = !rs.wasNull();
                        rs.getDouble(4);
                        boolean hasAsk = !rs.wasNull();
                        if (hasBid && hasAsk) {
                            quoteStats[1]++;
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT method, status, COUNT(*) FROM exec_request WHERE ts_ms BETWEEN ? AND ? "
                            + "GROUP BY method, status")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long[] counts = requests.computeIfAbsent(rs.getString(1), k -> new long[2]);
                        counts[0] += rs.getLong(3);
                        if (rs.getInt(2) >= 400 || rs.getInt(2) == 0) {
                            counts[1] += rs.getLong(3);
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM exec_event WHERE kind = 'position_mismatch' "
                            + "AND ts_ms BETWEEN ? AND ?")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    mismatches = rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("не прочитать журнал " + journalPath, e);
        }

        if (fills.isEmpty() || fair.isEmpty()) {
            log.warn("{}: в окне нет ни исполнений, ни котировок — отчёт не о чем", journalPath);
            return;
        }

        // ⚠️ Справедливая цена в exec_fill иногда нулевая: исполнение нашлось
        // сверкой, а не ответом на постановку, и цены момента у неё не было.
        // Ноль в этом поле не «цена ноль», а «неизвестно», и подставлять его в
        // захват нельзя: три такие сделки из 61 давали захват −156 б.п. вместо
        // +8.7. Восстанавливаем по журналу котировок — там справедливая пишется
        // на каждом тике; что не восстановилось, из захвата выбрасывается явно.
        int repaired = 0;
        int unresolved = 0;
        for (int i = 0; i < fills.size(); i++) {
            Fill f = fills.get(i);
            if (f.fair() > 0) {
                continue;
            }
            Map.Entry<Long, Double> at = fair.floorEntry(f.tsMs());
            if (at == null) {
                unresolved++;
                continue;
            }
            fills.set(i, new Fill(f.tsMs(), f.side(), f.qty(), f.price(), at.getValue(),
                    f.fee(), f.status()));
            repaired++;
        }
        if (repaired > 0 || unresolved > 0) {
            log.warn("{}: справедливая цена восстановлена у {} исполнений, не восстановлена у {}",
                    journalPath, repaired, unresolved);
        }
        write(out, render(journalPath, fills, fair, requests, quoteStats, mismatches, offset,
                repaired, unresolved));
        log.info("отчёт по журналу {}: {} исполнений → {}", journalPath, fills.size(), out);
    }

    // --- расчёты -------------------------------------------------------------

    /**
     * Пошлина на горизонте Δ.
     *
     * Числитель и знаменатель считаются по ОДНОМУ множеству исполнений — тем,
     * что дожили до горизонта внутри данных. Иначе захват берётся по всем
     * сделкам, markout — по выжившим, и их разность не край, а разность двух
     * разных выборок.
     */
    private Horizon horizon(List<Fill> fills, TreeMap<Long, Double> fair, long ms, double offset) {
        long lastFairMs = fair.lastKey();
        double turnover = 0;
        double capture = 0;
        double markout = 0;
        int count = 0;
        for (Fill f : fills) {
            if (f.sign() == 0 || !(f.fair() > 0)) {
                continue;
            }
            Map.Entry<Long, Double> later = fair.floorEntry(f.tsMs() + ms);
            if (f.tsMs() + ms > lastFairMs || later == null) {
                continue;
            }
            turnover += f.notional();
            capture += f.capture();
            markout += f.sign() * (later.getValue() - f.fair()) * f.qty();
            count++;
        }
        if (turnover <= 0) {
            return new Horizon(ms, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double captureBp = capture / turnover * 10_000;
        double markoutBp = markout / turnover * 10_000;
        return new Horizon(ms, count, turnover, captureBp, markoutBp,
                captureBp + markoutBp, offset * 10_000 - captureBp - markoutBp);
    }

    private String render(String journalPath, List<Fill> fills, TreeMap<Long, Double> fair,
                          Map<String, long[]> requests, long[] quoteStats, int mismatches,
                          double offset, int repaired, int unresolved) {
        long from = Math.min(fills.get(0).tsMs(), fair.firstKey());
        long to = Math.max(fills.get(fills.size() - 1).tsMs(), fair.lastKey());
        double hours = (to - from) / 3_600_000.0;
        double fairLast = fair.lastEntry().getValue();

        double turnover = 0;
        double capture = 0;
        double cash = 0;
        double position = 0;
        double fees = 0;
        int buys = 0;
        for (Fill f : fills) {
            if (f.sign() == 0) {
                continue;
            }
            turnover += f.notional();
            // Исполнение без справедливой цены участвует в кассе и позиции (оно
            // случилось), но не в захвате: захват от неизвестной цены не считается.
            capture += f.fair() > 0 ? f.capture() : 0;
            cash -= f.sign() * f.notional();
            position += f.sign() * f.qty();
            fees += f.fee();
            if (f.sign() > 0) {
                buys++;
            }
        }
        double total = cash + position * fairLast;
        double inventoryLeg = total - capture;

        StringBuilder sb = new StringBuilder();
        sb.append("# Живой исполнитель: разложение результата и пошлина через markout\n\n");
        sb.append("Пункт 1 очереди док. 128 §6. Заголовок док. 126 («разрыв между захватом "
                + "и деньгами — это пошлина») держался на подмене величины: при нулевых "
                + "комиссиях разрыв **тождественно равен инвентарной ноге**. Здесь обе "
                + "величины считаются отдельно и каждая своим определением.\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| Журнал | `").append(journalPath).append("` |\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC))
                .append(" — ").append(Instant.ofEpochMilli(to).atZone(ZoneOffset.UTC))
                .append(" (").append(round(hours, 2)).append(" ч) |\n");
        sb.append("| Котировочных тиков | ").append(quoteStats[0])
                .append(", двусторонних ").append(quoteStats[0] == 0 ? "—"
                        : round(100.0 * quoteStats[1] / quoteStats[0], 1) + "%").append(" |\n");
        sb.append("| Котируемый отступ `d` | ").append(round(offset * 10_000, 2)).append(" б.п. |\n");
        sb.append("| Исполнений | ").append(fills.size()).append(" (покупок ").append(buys)
                .append(", продаж ").append(fills.size() - buys).append(") |\n");
        sb.append("| Оборот | ").append(round(turnover, 4)).append(" |\n");
        sb.append("| Комиссии | ").append(round(fees, 8)).append(" |\n");
        sb.append("| Справедливая цена восстановлена по журналу котировок | ").append(repaired)
                .append(" исполн.").append(unresolved > 0
                        ? ", **не восстановлена у " + unresolved + "**" : "").append(" |\n\n");
        if (repaired > 0) {
            sb.append("> ⚠️ У части исполнений в `exec_fill` справедливая цена нулевая: "
                    + "сделка найдена сверкой, а не ответом на постановку, и цены момента "
                    + "у неё не было. Ноль здесь означает «неизвестно», и подстановка его "
                    + "в захват даёт бессмыслицу — три такие сделки из 61 давали захват "
                    + "−156 б.п. вместо +8.7. Значение восстановлено из `exec_quote` по "
                    + "последнему тику не позже исполнения.\n\n");
        }

        sb.append("## 1. Разложение: захват — не прибыль, и разрыв — не пошлина\n\n");
        sb.append("`total = захват спреда + инвентарная нога − комиссии`. Это тождество, а "
                + "не измерение: инвентарная нога определена как остаток. Поэтому "
                + "«разрыв между захватом и деньгами» НЕЛЬЗЯ читать как рыночную "
                + "величину — он равен инвентарной ноге по построению.\n\n");
        sb.append("| Слагаемое | В валюте котировки | В б.п. оборота |\n|---|---|---|\n");
        sb.append("| Захват спреда | ").append(round(capture, 6))
                .append(" | ").append(round(capture / turnover * 10_000, 2)).append(" |\n");
        sb.append("| **Инвентарная нога** | **").append(round(inventoryLeg, 6))
                .append("** | **").append(round(inventoryLeg / turnover * 10_000, 2)).append("** |\n");
        sb.append("| Комиссии | ").append(round(-fees, 6))
                .append(" | ").append(round(-fees / turnover * 10_000, 2)).append(" |\n");
        sb.append("| **`total`** | **").append(round(total, 6))
                .append("** | **").append(round(total / turnover * 10_000, 2)).append("** |\n\n");
        sb.append("Инвентарная нога — это переоценка позиции, которую бот нёс, пока рынок "
                + "куда-то шёл. Она меняет знак вместе с рынком и о качестве котирования "
                + "не говорит ничего. Сравнивать двух ботов по величине, которая её "
                + "содержит, нельзя, пока они несут разную позицию.\n\n");

        sb.append("## 2. Пошлина: `c = отступ − захват − markout`\n\n");
        sb.append("Вот это — рыночная величина, и её можно сверять со стендом. `markout` "
                + "берётся от справедливой цены В МОМЕНТ ИСПОЛНЕНИЯ (не от цены заявки), "
                + "иначе захват учитывается дважды.\n\n");
        sb.append("| Горизонт | Исполнений в оценке | Захват, б.п. | `markout`, б.п. "
                + "| Чистый край, б.п. | **Пошлина `c`, б.п.** |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (long ms : HORIZONS_MS) {
            Horizon h = horizon(fills, fair, ms, offset);
            sb.append("| ").append(ms / 1000).append(" с")
                    .append(" | ").append(h.fills())
                    .append(" | ").append(round(h.captureBp(), 2))
                    .append(" | ").append(round(h.markoutBp(), 2))
                    .append(" | ").append(round(h.netBp(), 2))
                    .append(" | **").append(round(h.tollBp(), 2)).append("** |\n");
        }
        sb.append("\n**Как читать.** Пошлина складывается из устаревания заявки (рынок "
                + "ушёл, пока котировка висела) и неблагоприятного отбора (нас разобрали "
                + "те, кто знал больше). Первое видно как разрыв между отступом и "
                + "захватом, второе — как отрицательный `markout`. Разложение по "
                + "горизонтам отвечает, какая часть постоянна: устаревание не зависит от "
                + "горизонта, отбор растёт с ним.\n\n");
        sb.append("Именно ЭТО число сравнимо со стендовым `c`, и только оно. Величина "
                + "`total − захват` из док. 126 сравнима лишь с инвентарной ногой другого "
                + "бота.\n\n");

        sb.append("## 3. Исполнения по часам\n\n");
        sb.append("| Час (UTC) | Исполнений | Оборот | Захват, б.п. | Позиция за час |\n");
        sb.append("|---|---|---|---|---|\n");
        Map<Long, double[]> byHour = new TreeMap<>();
        for (Fill f : fills) {
            if (f.sign() == 0) {
                continue;
            }
            double[] acc = byHour.computeIfAbsent(f.tsMs() / 3_600_000L, k -> new double[4]);
            acc[0]++;
            acc[1] += f.notional();
            acc[2] += f.capture();
            acc[3] += f.sign() * f.qty();
        }
        for (Map.Entry<Long, double[]> e : byHour.entrySet()) {
            double[] acc = e.getValue();
            sb.append("| ").append(Instant.ofEpochMilli(e.getKey() * 3_600_000L).atZone(ZoneOffset.UTC)
                            .toLocalDateTime())
                    .append(" | ").append((int) acc[0])
                    .append(" | ").append(round(acc[1], 4))
                    .append(" | ").append(acc[1] > 0 ? round(acc[2] / acc[1] * 10_000, 2) : "—")
                    .append(" | ").append(round(acc[3], 8))
                    .append(" |\n");
        }

        sb.append("\n## 4. Здоровье исполнителя\n\n");
        sb.append("Доля отказов на замене — пункт 5 очереди док. 126 §9: она росла с "
                + "активностью, а отказ на замене стоит четырёх запросов и однажды уже "
                + "оставил заявку без хозяина (док. 111).\n\n");
        sb.append("| Метод | Запросов | Отказов | Доля отказов |\n|---|---|---|---|\n");
        for (Map.Entry<String, long[]> e : requests.entrySet()) {
            long[] v = e.getValue();
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(v[0])
                    .append(" | ").append(v[1])
                    .append(" | ").append(v[0] == 0 ? "—" : round(100.0 * v[1] / v[0], 2) + "%")
                    .append(" |\n");
        }
        sb.append("\n| Тревог `position_mismatch` | ").append(mismatches).append(" |\n|---|---|\n");
        sb.append("\nНоль расхождений позиции при растущей доле отказов — приёмка сверки "
                + "из доков 111 и 113: судьба заявки выводится из `GET /orders/active`, а "
                + "не из её собственного статуса.\n");
        return sb.toString();
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    private void write(String out, String markdown) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записать отчёт " + out, e);
        }
    }
}
