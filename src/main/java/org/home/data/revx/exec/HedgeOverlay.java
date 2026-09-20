package org.home.data.revx.exec;

import org.home.data.revx.sim.PerpMarkSource;
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
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * ХЕДЖ, НАЛОЖЕННЫЙ НА ФАКТИЧЕСКУЮ ТРАЕКТОРИЮ БОТА. Команда {@code --revx-hedge}.
 * Пункт П2.3 протокола 160.
 *
 * <h2>Почему наложение, а не хедж внутри стенда</h2>
 *
 * Хедж НЕ МЕНЯЕТ поведения котировщика: спотовый бот о перпе не знает, отступ и
 * скос считаются как раньше. Значит траекторию инвентаря можно взять из ЖИВЫХ
 * журналов, а не воспроизводить прогоном, — и тогда в ответе нет ошибки
 * симуляции вообще. Прогон стенда добавил бы к замеру свой собственный недобор
 * исполнений (CLAUDE.md: обход не предсказывает число сделок живого бота и не
 * может), а здесь его нет по построению.
 *
 * ⚠️ Оговорка к этому: наложение верно ровно потому, что хедж ничего не меняет в
 * котировании. Если однажды захочется поднять потолок инвентаря ПОТОМУ ЧТО есть
 * хедж — это уже другая конструкция, и её так не посчитать.
 *
 * <h2>Правило перевешивания</h2>
 *
 * Полоса ±{@code B} лотов по ЧИСТОЙ экспозиции {@code спот + перп}. Вышли за
 * полосу — возвращаем чистую экспозицию к нулю, округляя размер сделки ВНИЗ до
 * шага контракта (CLAUDE.md: округление «к ближайшему» на грубом шаге
 * переворачивает позицию — измерено, нетто-шорт 20% потолка в обратную сторону).
 *
 * <h2>Марка перпа — настоящая, и в USDC</h2>
 *
 * Берётся {@link PerpMarkSource#marksInQuote}: минутные марки Kraken,
 * пересчитанные в USDC по курсу из книги стенда. Считать хедж по споту значит
 * объявить базис тождественно нулевым (док. 146), а считать по USD-марке против
 * USDC-спота — подмешать в базис отклонение стейблкойна, которое на порядок
 * крупнее самого базиса.
 *
 * <h2>Что печатается</h2>
 *
 * Два критерия протокола, оба рядом «без хеджа / с хеджем»:
 * <ul>
 *   <li><b>итог без беты запаса</b> (159 §I) — должен стать положительным на
 *       ОБОИХ окнах противоположного направления;</li>
 *   <li><b>разность наклонов</b> по ЧАСОВОМУ ряду — должна упасть минимум вдвое
 *       с нынешних 0.0395. На суточных клетках излом не определяется вовсе
 *       (158 §VII.2), поэтому только часы.</li>
 * </ul>
 */
@Component
@Lazy
public class HedgeOverlay {

    private static final Logger log = LoggerFactory.getLogger(HedgeOverlay.class);

    /**
     * Шаг контракта Kraken в единицах базовой валюты.
     *
     * ⚠️ Замерено по {@code contractValueTradePrecision} из публичного API
     * 18.09.2026 (задача A69), а не взято со страницы: BTC 4 знака, ETH 3,
     * SOL 2. В наших лотах по $2.90 это 2.7, 0.85 и 0.35 лота — то есть на BTC
     * полоса меньше трёх лотов физически неисполнима.
     */
    private static final Map<String, Double> STEP =
            Map.of("BTC", 1e-4, "ETH", 1e-3, "SOL", 1e-2);

    /** Один бот: метка, база, путь(и) к журналу. */
    private record Bot(String id, String base, String[] paths) {
    }

    /** Итог по боту в одном варианте (с хеджем или без). */
    private record Split(double capture, double beta, double timing, double fees,
                         double slopeDown, double slopeUp, int hours) {
        double total() {
            return capture + beta + timing - fees;
        }

        /** 🔑 Критерий протокола: итог без беты запаса (159 §I). */
        double exBeta() {
            return capture + timing - fees;
        }

        double slopeGap() {
            return slopeDown - slopeUp;
        }
    }

    private final PerpMarkSource marks;

    public HedgeOverlay(PerpMarkSource marks) {
        this.marks = marks;
    }

    /**
     *  stepUsd подмена шага контракта, в долларах. Ноль — настоящий шаг
     *        площадки из { #STEP}.
     *
     *        ⚠️ Зачем подмена. На BTC шаг 0.0001 BTC = 2.7 наших лота, и хедж
     *        просто не включается: инвентарь до такого редко доходит. Это
     *        МЕХАНИЧЕСКОЕ препятствие, а не свойство хеджа, и их надо различать —
     *        иначе «на BTC не работает» читается как приговор механизму, хотя
     *        означает «лот втрое мельче, чем нужно площадке». Подменив шаг на
     *        доллар, получаем ответ на вопрос «заработал бы механизм, будь
     *        гранулярность как у SOL» — и, значит, стоит ли переходить на лот
     *        около $9, где шаг BTC сам станет меньше лота.
     */
    public void run(String journals, double bandLots, double feeBp, double stepUsd,
                    String fromIso, String toIso, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0 : Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE : Instant.parse(toIso).toEpochMilli();

        List<Bot> bots = new ArrayList<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String id = kv[0].trim();
            String base = id.contains(":") ? id.substring(id.indexOf(':') + 1) : null;
            bots.add(new Bot(id.contains(":") ? id.substring(0, id.indexOf(':')) : id,
                    base, kv[1].split("\\+")));
        }
        if (bots.isEmpty()) {
            log.warn("ботов не задано");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# П2.3: хедж, наложенный на фактическую траекторию\n\n");
        sb.append("полоса ±").append(round(bandLots, 1)).append(" лота, комиссия перпа ")
                .append(round(feeBp, 1)).append(" б.п.\n\n");
        sb.append("| бот | пара | часов | без хеджа: итог / без беты / разность наклонов")
                .append(" | с хеджем: итог / без беты / разность | перевешиваний | пошлина |\n");
        sb.append("|---|---|---:|---|---|---:|---:|\n");

        double exBetaPlain = 0;
        double exBetaHedged = 0;
        double gapPlainSum = 0;
        double gapHedgedSum = 0;
        int counted = 0;

        for (Bot b : bots) {
            Series s = readSeries(b, from, to);
            if (s == null || s.ts.length < 100) {
                log.warn("{}: тиков мало", b.id());
                continue;
            }
            String base = b.base() != null ? b.base() : guessBase(s.fair[0]);
            NavigableMap<Long, Double> mark = marks.marksInQuote(
                    PerpMarkSource.perpFor(base), base, s.ts[0] - 120_000,
                    s.ts[s.ts.length - 1] + 120_000);
            if (mark.isEmpty()) {
                log.warn("{}: марок перпа нет — пропускаю", b.id());
                continue;
            }
            double lot = s.lot;
            double price = s.fair[0];
            double step = stepUsd > 0 ? stepUsd / price : STEP.getOrDefault(base, 1e-4);
            Result plain = evaluate(s, null, 0, 0, 0);
            Result hedged = evaluate(s, mark, bandLots * lot, step, feeBp / 1e4);
            sb.append("| ").append(b.id()).append(" | ").append(base)
                    .append(" | ").append(plain.split.hours())
                    .append(" | ").append(money(plain.split.total()))
                    .append(" / ").append(money(plain.split.exBeta()))
                    .append(" / ").append(round(plain.split.slopeGap(), 4))
                    .append(" | ").append(money(hedged.split.total()))
                    .append(" / **").append(money(hedged.split.exBeta()))
                    .append("** / **").append(round(hedged.split.slopeGap(), 4))
                    .append("** | ").append(hedged.trades)
                    .append(" | ").append(money(hedged.split.fees()))
                    .append(" |\n");
            exBetaPlain += plain.split.exBeta();
            exBetaHedged += hedged.split.exBeta();
            gapPlainSum += plain.split.slopeGap();
            gapHedgedSum += hedged.split.slopeGap();
            counted++;
        }

        if (counted > 0) {
            sb.append("\n## Критерии протокола\n\n");
            sb.append("| | без хеджа | с хеджем | что требует протокол |\n|---|---:|---:|---|\n");
            sb.append("| итог без беты запаса, сумма | ").append(money(exBetaPlain))
                    .append(" | **").append(money(exBetaHedged))
                    .append("** | > 0 на ОБОИХ окнах |\n");
            sb.append("| разность наклонов, среднее | ").append(round(gapPlainSum / counted, 4))
                    .append(" | **").append(round(gapHedgedSum / counted, 4))
                    .append("** | падение минимум ВДВОЕ |\n");
            double ratio = gapHedgedSum == 0 ? 0 : gapPlainSum / gapHedgedSum;
            sb.append("\nРазность наклонов упала в ").append(round(ratio, 2)).append(" раза.\n");
        }

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

    /** Ряд бота: тики котировщика и его сделки. */
    private static final class Series {
        long[] ts;
        double[] fair;
        double[] inv;
        long[] fillTs;
        double[] fillDq;
        double[] fillPx;
        double lot;
    }

    private record Result(Split split, int trades) {
    }

    /**
     * Считает разложение по одной траектории. {@code mark == null} — вариант без
     * хеджа.
     *
     * Тождество то же, что в {@link CarryReport}: капитал = касса + запас ×
     * опора, и маркой сделки служит СЛЕДУЮЩИЙ тик. С хеджем к капиталу
     * добавляется переоценка перпа и вычитается пошлина перевешиваний.
     */
    private Result evaluate(Series s, NavigableMap<Long, Double> mark,
                            double band, double step, double fee) {
        double cash = 0;
        double capture = 0;
        double turnover = 0;
        int fi = 0;
        double perp = 0;
        double hedgeCarry = 0;
        double fees = 0;
        int trades = 0;
        double spotCarry = 0;
        double area = 0;
        double areaNet = 0;
        double prevFair = 0;
        double prevInv = 0;
        double prevPerp = 0;
        double prevMark = 0;
        boolean first = true;
        // Часовой ряд: капитал на границе каждого часа и ход опоры за час.
        TreeMap<Long, double[]> hourly = new TreeMap<>();   // час -> {капитал, опора}

        for (int i = 0; i < s.ts.length; i++) {
            long t = s.ts[i];
            double f = s.fair[i];
            double inv = s.inv[i];
            // Сделки, случившиеся до этого тика: марка — ТЕКУЩИЙ тик.
            while (fi < s.fillTs.length && s.fillTs[fi] <= t) {
                cash -= s.fillDq[fi] * s.fillPx[fi];
                capture += (f - s.fillPx[fi]) * s.fillDq[fi];
                turnover += Math.abs(s.fillDq[fi]) * s.fillPx[fi];
                fi++;
            }
            double m = 0;
            if (mark != null) {
                Map.Entry<Long, Double> e = mark.floorEntry(t);
                m = e == null ? 0 : e.getValue();
            }
            if (!first) {
                spotCarry += prevInv * (f - prevFair);
                if (mark != null && prevMark > 0 && m > 0) {
                    hedgeCarry += prevPerp * (m - prevMark);
                }
            }
            // Перевешивание ПОСЛЕ переоценки: решение принимается по состоянию,
            // которое уже отмечено в капитале.
            if (mark != null && m > 0 && band > 0) {
                double net = inv + perp;
                if (Math.abs(net) > band) {
                    double want = -inv;
                    double delta = want - perp;
                    // ⚠️ ВНИЗ по модулю: округление к ближайшему на грубом шаге
                    // переворачивает позицию (CLAUDE.md, измерено).
                    double rounded = Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step;
                    if (Math.abs(rounded) >= step) {
                        perp += rounded;
                        fees += Math.abs(rounded) * m * fee;
                        trades++;
                    }
                }
            }
            first = false;
            prevFair = f;
            prevInv = inv;
            prevPerp = perp;
            prevMark = m;
            area += inv;
            areaNet += inv + perp;
            double equity = cash + inv * f + (mark != null ? hedgeCarry - fees : 0);
            hourly.put(t / 3_600_000L, new double[]{equity, f});
        }

        int n = s.ts.length;
        double p0 = s.fair[0];
        double p1 = s.fair[n - 1];
        double meanPos = (mark != null ? areaNet : area) / n;
        double beta = meanPos * (p1 - p0);
        double carry = spotCarry + (mark != null ? hedgeCarry : 0);
        double[] slopes = hourlySlopes(hourly);
        return new Result(new Split(capture, beta, carry - beta, fees,
                slopes[0], slopes[1], (int) slopes[2]), trades);
    }

    /**
     * Наклоны по ЧАСОВОМУ ряду: {@code доход = α + b₁·min(ход,0) + b₂·max(ход,0)}.
     *
     * ⚠️ Почему часы, а не сутки: на 24 суточных клетках излом не определяется —
     * наклон «на росте» выходит отрицательным, чего у long-only быть не может
     * (158 §VII.2). На часах у живых ботов излом дал t = 9.44.
     */
    private double[] hourlySlopes(TreeMap<Long, double[]> hourly) {
        List<double[]> rows = new ArrayList<>();            // {ход %, доход}
        Long prevHour = null;
        double[] prev = null;
        for (Map.Entry<Long, double[]> e : hourly.entrySet()) {
            if (prev != null && e.getKey() - prevHour == 1) {
                double move = 100.0 * (e.getValue()[1] - prev[1]) / prev[1];
                rows.add(new double[]{move, e.getValue()[0] - prev[0]});
            }
            prevHour = e.getKey();
            prev = e.getValue();
        }
        if (rows.size() < 10) {
            return new double[]{0, 0, rows.size()};
        }
        int n = rows.size();
        double[][] xtx = new double[3][3];
        double[] xty = new double[3];
        for (double[] r : rows) {
            double[] x = {1, Math.min(r[0], 0), Math.max(r[0], 0)};
            for (int j = 0; j < 3; j++) {
                xty[j] += x[j] * r[1];
                for (int k = 0; k < 3; k++) {
                    xtx[j][k] += x[j] * x[k];
                }
            }
        }
        double[] b = solve3(xtx, xty);
        return new double[]{b[1], b[2], n};
    }

    /** Гаусс на матрице 3×3; при вырождении возвращает нули. */
    private static double[] solve3(double[][] a, double[] y) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3] = y[i];
        }
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int i = c; i < 3; i++) {
                if (Math.abs(m[i][c]) > Math.abs(m[piv][c])) {
                    piv = i;
                }
            }
            if (Math.abs(m[piv][c]) < 1e-12) {
                return new double[]{0, 0, 0};
            }
            double[] t = m[c];
            m[c] = m[piv];
            m[piv] = t;
            double d = m[c][c];
            for (int j = 0; j < 4; j++) {
                m[c][j] /= d;
            }
            for (int i = 0; i < 3; i++) {
                if (i != c) {
                    double f = m[i][c];
                    for (int j = 0; j < 4; j++) {
                        m[i][j] -= f * m[c][j];
                    }
                }
            }
        }
        return new double[]{m[0][3], m[1][3], m[2][3]};
    }

    /** Тики и сделки бота из журнала (или нескольких через {@code +}). */
    private Series readSeries(Bot b, long from, long to) {
        List<long[]> ticks = new ArrayList<>();
        List<double[]> tickVals = new ArrayList<>();
        List<long[]> fills = new ArrayList<>();
        List<double[]> fillVals = new ArrayList<>();
        List<Double> qty = new ArrayList<>();
        for (String path : b.paths()) {
            String url = "jdbc:sqlite:file:" + Path.of(path.trim()).toAbsolutePath() + "?mode=ro";
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, fair, inventory FROM exec_quote WHERE ts_ms >= ?"
                                 + " AND ts_ms < ? AND fair > 0 ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ticks.add(new long[]{rs.getLong(1)});
                        tickVals.add(new double[]{rs.getDouble(2), rs.getDouble(3)});
                    }
                }
            } catch (Exception e) {
                log.warn("{}: тики не прочитаны ({})", path, e.toString());
                return null;
            }
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, side, qty, price FROM exec_fill WHERE ts_ms >= ?"
                                 + " AND ts_ms < ? ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double q = rs.getDouble(3);
                        fills.add(new long[]{rs.getLong(1)});
                        fillVals.add(new double[]{
                                "BUY".equalsIgnoreCase(rs.getString(2)) ? q : -q, rs.getDouble(4)});
                        qty.add(q);
                    }
                }
            } catch (Exception e) {
                log.warn("{}: сделки не прочитаны ({})", path, e.toString());
            }
        }
        if (ticks.isEmpty()) {
            return null;
        }
        Series s = new Series();
        s.ts = new long[ticks.size()];
        s.fair = new double[ticks.size()];
        s.inv = new double[ticks.size()];
        for (int i = 0; i < ticks.size(); i++) {
            s.ts[i] = ticks.get(i)[0];
            s.fair[i] = tickVals.get(i)[0];
            s.inv[i] = tickVals.get(i)[1];
        }
        s.fillTs = new long[fills.size()];
        s.fillDq = new double[fills.size()];
        s.fillPx = new double[fills.size()];
        for (int i = 0; i < fills.size(); i++) {
            s.fillTs[i] = fills.get(i)[0];
            s.fillDq[i] = fillVals.get(i)[0];
            s.fillPx[i] = fillVals.get(i)[1];
        }
        java.util.Collections.sort(qty);
        s.lot = qty.isEmpty() ? 0 : qty.get(qty.size() / 2);
        return s;
    }

    /** Грубое определение пары по цене, когда метка её не назвала. */
    private static String guessBase(double price) {
        if (price > 10_000) {
            return "BTC";
        }
        return price > 300 ? "ETH" : "SOL";
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.4f", v);
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
