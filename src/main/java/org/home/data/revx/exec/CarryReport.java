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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ТРОЙКА ВЕЛИЧИН: захват, бета среднего запаса, время под позицией.
 * Команда {@code --revx-carry}.
 *
 * <h2>Зачем прибор появился</h2>
 *
 * Док. 155 §VIII сформулировал требование: «правильная тройка величин —
 * отношение + остаток + занятость инвентаря, и если они расходятся, решать
 * нельзя ни по одной». Док. 157 §V предложил способ получить её без всякого
 * произвольного параметра: сравнивать бота не с держателем какого-то запаса, а
 * с самим собой, несущим СВОЮ ЖЕ позицию пассивно. Предложение верное, и здесь
 * оно доведено до тождества, которое сходится точно:
 *
 * <pre>
 * капитал(t) = касса(t) + запас(t) · опора(t)
 * Δкапитал   = Σ запас·Δопора        +  Σ (опора − цена сделки)·Δзапас
 *              ПЕРЕНОСКА                 ЗАХВАТ
 * </pre>
 *
 * Переноска делится ещё раз, и вторая часть и есть то, ради чего всё считается:
 * <ul>
 *   <li><b>бета среднего запаса</b> — что унёс бы рынок у держателя ТОЙ ЖЕ
 *       средней позиции: {@code средний запас × ход окна};</li>
 *   <li><b>время под позицией</b> — весь остаток переноски, то есть плата за
 *       то, что запас оказывался большим именно в неудачные минуты. На живых
 *       ботах за 08–15.09.2026 в нём сидело 92% всей переноски: бета среднего
 *       запаса объяснила −$0.159 из −$1.955.</li>
 * </ul>
 *
 * <h2>Почему это не то же, что круг по ленте</h2>
 *
 * ⚠️ Захват здесь — {@code (опора − цена сделки)} в момент сделки, то есть
 * 2δ без пошлины, а круг из док. 154 §I считает {@code 2δ − c}. Значит
 * портфельное тождество кладёт ВЕСЬ отбор в переноску, а круг — в торговлю.
 * Требовать от двух приборов одного числа (док. 157 §V) нельзя: они обязаны
 * различаться ровно на {@code c}. Прибор печатает захват и в базисных пунктах
 * на доллар оборота, чтобы эту разницу было видно прямо.
 *
 * <h2>Грабля, без которой тождество не сходится</h2>
 *
 * ⚠️ **Маркой сделки обязан быть СЛЕДУЮЩИЙ тик котировщика, а не предыдущий.**
 * Инвентарь в {@code exec_quote} меняется на том тике, где бот узнал об
 * исполнении; если брать опору предыдущего тика, остаток тождества на трёх
 * живых ботах выходит −$0.22 при результате −$0.65 — треть всего, и не
 * случайная: сделка случается ровно тогда, когда цена пришла к нам. С маркой
 * следующего тика остаток равен нулю до четвёртого знака.
 *
 * <h2>Что прибор НЕ умеет</h2>
 *
 * Он не отвечает на вопрос «хорошо ли бот торгует»: захват в тождестве
 * положителен по построению (иначе заявка не стояла бы там, где стоит). Он
 * отвечает на другой вопрос — во что обходится время под позицией, и сравним
 * ли захват с этой платой.
 */
@Component
@Lazy
public class CarryReport {

    private static final Logger log = LoggerFactory.getLogger(CarryReport.class);

    /** Разложение одного бота. */
    private record Split(String name, double capture, double beta, double timing,
                         double actual, double meanUsd, int fills, double hours,
                         double movePct, double turnover, long firstMs, long lastMs) {
        double sum() {
            return capture + beta + timing;
        }

        /**
         * Захват на ДОЛЛАР оборота, б.п.
         *
         * ⚠️ Делить на «лот × число сделок» нельзя: лот меняли посреди окна
         * (у бота a 08.09 он был $1, с 11.09 — $2.9), и одна медиана на всё
         * окно завысила бы ранние сделки втрое. Взвешивание по обороту от
         * размера лота не зависит вовсе.
         */
        double captureBp() {
            return !(turnover > 0) ? 0 : 1e4 * capture / turnover;
        }

        double timingBp() {
            return !(turnover > 0) ? 0 : 1e4 * timing / turnover;
        }
    }

    /**
     * @param journals {@code имя=путь,имя=путь} — журналы живых ботов. Журнал
     *                 одного бота можно склеить из нескольких файлов через
     *                 {@code +}: суточные выгрузки лежат порознь, а тождество
     *                 обязано считаться сквозь границу суток — касса и запас
     *                 переходят через неё непрерывно
     * @param fromMs   начало окна; за его пределами записи не читаются вовсе,
     *                 поэтому стартовый капитал берётся по первому тику ВНУТРИ
     *                 окна, а не по началу журнала
     */
    public void run(String journals, long fromMs, long toMs, String out) {
        List<Split> splits = new ArrayList<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            String name = kv.length == 2 ? kv[0].trim() : Path.of(part.trim()).getFileName().toString();
            String path = kv.length == 2 ? kv[1].trim() : part.trim();
            Split s = split(name, path.split("\\+"), fromMs, toMs);
            if (s != null) {
                splits.add(s);
            }
        }
        if (splits.isEmpty()) {
            log.warn("ни одного журнала не прочитано: {}", journals);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Тройка величин: захват, бета среднего запаса, время под позицией\n\n");
        sb.append("окно: ").append(Instant.ofEpochMilli(
                        splits.stream().mapToLong(Split::firstMs).min().orElse(fromMs)))
                .append(" .. ").append(Instant.ofEpochMilli(
                        splits.stream().mapToLong(Split::lastMs).max().orElse(toMs)))
                .append("\n\n");
        sb.append("| бот | захват | бета среднего запаса | время под позицией | сумма |")
                .append(" факт | остаток | сделок | средний запас | ход |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        double tc = 0;
        double tb = 0;
        double tt = 0;
        double ta = 0;
        for (Split s : splits) {
            sb.append("| ").append(s.name())
                    .append(" | ").append(money(s.capture()))
                    .append(" | ").append(money(s.beta()))
                    .append(" | ").append(money(s.timing()))
                    .append(" | ").append(money(s.sum()))
                    .append(" | ").append(money(s.actual()))
                    .append(" | ").append(money(s.actual() - s.sum()))
                    .append(" | ").append(s.fills())
                    .append(" | $").append(String.format(Locale.ROOT, "%.2f", s.meanUsd()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f%%", s.movePct()))
                    .append(" |\n");
            tc += s.capture();
            tb += s.beta();
            tt += s.timing();
            ta += s.actual();
        }
        sb.append("| **все** | **").append(money(tc)).append("** | **").append(money(tb))
                .append("** | **").append(money(tt)).append("** | **").append(money(tc + tb + tt))
                .append("** | **").append(money(ta)).append("** | **")
                .append(money(ta - tc - tb - tt)).append("** | | | |\n\n");

        double hours = splits.stream().mapToDouble(Split::hours).sum();
        if (hours > 0) {
            double days = hours / 24.0;
            sb.append("На бота в сутки: захват ").append(money(tc / days))
                    .append(", бета запаса ").append(money(tb / days))
                    .append(", время под позицией ").append(money(tt / days))
                    .append(", итог ").append(money(ta / days)).append(".\n\n");
        }

        sb.append("⚠️ Остаток тождества обязан быть нулём. Если он не ноль — в журнале\n")
                .append("есть исполнения без тика котировщика рядом (бот стоял), и тогда\n")
                .append("разложение неполно ровно на эту величину.\n\n");

        sb.append("## На доллар оборота, б.п.\n\n");
        sb.append("| бот | захват | время под позицией | разность |\n");
        sb.append("|---|---:|---:|---:|\n");
        for (Split s : splits) {
            sb.append("| ").append(s.name())
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f", s.captureBp()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f", s.timingBp()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f",
                            s.captureBp() + s.timingBp()))
                    .append(" |\n");
        }
        sb.append("\n⚠️ Базисные пункты — на ДОЛЛАР ОБОРОТА, а не на сделку: лот меняли\n")
                .append("посреди окна (у бота a 08.09 он был $1, с 11.09 — $2.9), и деление\n")
                .append("на число сделок завысило бы ранние втрое. Разность двух колонок и\n")
                .append("есть то, что бот зарабатывает или теряет на обороте.\n");

        String text = sb.toString();
        if (out == null || out.isBlank()) {
            log.info("\n{}", text);
        } else {
            try {
                Path p = Path.of(out);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, text, StandardCharsets.UTF_8);
                log.info("отчёт записан: {}", p.toAbsolutePath());
            } catch (IOException e) {
                log.warn("не записать {}: {}", out, e.toString());
                log.info("\n{}", text);
            }
        }
    }

    /**
     * Разложение одного журнала.
     *
     * Касса считается только по сделкам ВНУТРИ окна, а стартовый капитал — по
     * первому тику окна, поэтому окно можно двигать как угодно: обе части
     * тождества обрезаются по одной границе.
     */
    private Split split(String name, String[] paths, long fromMs, long toMs) {
        TreeMap<Long, Double> marks = new TreeMap<>();
        TreeMap<Long, Double> inv = new TreeMap<>();
        for (String path : paths) {
            if (!readQuotes(path.trim(), fromMs, toMs, marks, inv)) {
                return null;
            }
        }
        if (marks.size() < 2) {
            log.warn("{}: тиков в окне {}", name, marks.size());
            return null;
        }

        Trades trades = new Trades();
        for (String path : paths) {
            if (!readFills(path.trim(), fromMs, toMs, marks, trades)) {
                return null;
            }
        }

        double carry = 0;
        double area = 0;
        double areaUsd = 0;
        boolean first = true;
        double prevP = 0;
        double prevQ = 0;
        for (Map.Entry<Long, Double> e : marks.entrySet()) {
            double px = e.getValue();
            double q = inv.get(e.getKey());
            if (!first) {
                carry += prevQ * (px - prevP);
            }
            first = false;
            prevP = px;
            prevQ = q;
            area += q;
            areaUsd += q * px;
        }
        double meanQ = area / marks.size();
        double p0 = marks.firstEntry().getValue();
        double p1 = marks.lastEntry().getValue();
        double beta = meanQ * (p1 - p0);
        double actual = trades.cash + prevQ * p1 - inv.firstEntry().getValue() * p0;
        double hours = (marks.lastKey() - marks.firstKey()) / 3_600_000.0;
        return new Split(name, trades.capture, beta, carry - beta, actual,
                areaUsd / marks.size(), trades.fills, hours,
                100.0 * (p1 - p0) / p0, trades.turnover, marks.firstKey(), marks.lastKey());
    }

    /** Касса, захват и оборот бота за окно. */
    private static final class Trades {
        double cash;
        double capture;
        double turnover;
        int fills;
    }

    private boolean readQuotes(String path, long fromMs, long toMs,
                               TreeMap<Long, Double> marks, TreeMap<Long, Double> inv) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(path).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, fair, inventory FROM exec_quote"
                             + " WHERE ts_ms >= ? AND ts_ms < ? AND fair > 0 ORDER BY ts_ms")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marks.put(rs.getLong(1), rs.getDouble(2));
                    inv.put(rs.getLong(1), rs.getDouble(3));
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("журнал {} не прочитан: {}", path, e.toString());
            return false;
        }
    }

    private boolean readFills(String path, long fromMs, long toMs,
                              TreeMap<Long, Double> marks, Trades trades) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(path).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, side, qty, price FROM exec_fill"
                             + " WHERE ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double qty = rs.getDouble(3);
                    double price = rs.getDouble(4);
                    double dq = "BUY".equalsIgnoreCase(rs.getString(2)) ? qty : -qty;
                    // 🔑 марка — СЛЕДУЮЩИЙ тик: на нём меняется инвентарь.
                    Map.Entry<Long, Double> mark = marks.ceilingEntry(rs.getLong(1));
                    double fair = mark == null ? price : mark.getValue();
                    trades.cash -= dq * price;
                    trades.capture += (fair - price) * dq;
                    trades.turnover += qty * price;
                    trades.fills++;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("журнал {}: исполнения не прочитаны: {}", path, e.toString());
            return false;
        }
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.4f", v);
    }
}
