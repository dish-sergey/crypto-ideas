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

    /**
     * Один БОТ-ЧАС: цена запаса, исполнения и волатильность.
     *
     * @param carry   {@code Σ q·Δp} за час — во что обошёлся запас целиком
     * @param timing  тот же час за вычетом беты: ковариационная часть
     * @param volBp   реализованная волатильность часа, б.п. за минуту
     * @param prevVol волатильность ПРЕДЫДУЩЕГО часа — по ней и класс: текущую
     *                бот знать не может
     */
    private record Hour(String bot, long hourMs, double carry, double beta, double timing,
                        int fills, double turnover, double meanQ, double volBp,
                        double prevVol, double movePct, int ticks, int quotingTicks,
                        double meanQUsd) {

        /** Цена запаса на одно исполнение, б.п. от оборота часа. */
        double priceBp(boolean useTiming) {
            double v = useTiming ? timing : carry;
            return !(turnover > 0) ? 0 : 1e4 * v / turnover;
        }

        /**
         * Доля часа, которую бот ПРОСТОЯЛ, не котируя.
         *
         * 🔑 ⚠️ Это не мелочь учёта, а смещение в ту самую сторону, которую мы
         * меряем. Бот выключается, упершись в суточный предел постановок
         * ({@code limit_blocked: 100 из 100}), а предел он выбирает быстрее в
         * БУРНЫЕ часы — там больше перестановок. После выключения тики в журнал
         * не пишутся вовсе, значит:
         * <ul>
         *   <li>бурный час обрезается, и его цена запаса считается по началу;</li>
         *   <li>стоимость запаса, который бот держит ВО ВРЕМЯ простоя, в замер
         *       не попадает — а это ровно то, что мы ищем.</li>
         * </ul>
         * Поэтому час с неполным покрытием в сравнение классов не идёт.
         */
        double idleShare() {
            return 1 - quotingTicks / 3600.0;
        }
    }

    /** Часовые строки; {@code null} — разрез не просили. */
    private List<Hour> hourly;

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
        run(journals, fromMs, toMs, out, "");
    }

    /**
     * То же плюс ЧАСОВОЙ разрез в CSV ({@code --hours-out}).
     *
     * <h2>Зачем часы</h2>
     *
     * Блок 3 закрывали по ЛЕНТОЧНОЙ кривой края сделки на минутном горизонте, а
     * в ней нет цены запаса вовсе: она считает каждое событие само по себе и не
     * знает, что сделка потом лежит в запасе около сорока минут (док. 187).
     * Чтобы спросить «дороже ли запас в бурный час», нужен часовой
     * ковариационный член и число исполнений того же часа.
     *
     * ⚠️ Класс часа берётся по волатильности ПРЕДЫДУЩЕГО часа: текущую бот знать
     * не может, а предыдущая её предсказывает ({@code r} = 0.29, док. 166).
     */
    public void run(String journals, long fromMs, long toMs, String out, String hoursOut) {
        this.hourly = hoursOut == null || hoursOut.isBlank() ? null : new ArrayList<>();
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
            // 🔑 ГЛАВНАЯ СТРОКА: ИТОГ БЕЗ БЕТЫ ЗАПАСА (док. 159 §I, док. 160 ч. VI).
            //
            // Ровно один член тройки несёт дрейф попавшегося окна — бета среднего
            // запаса (`средний запас × ход окна`). На рынке, который никуда не
            // идёт, она обнуляется, а ковариационный член («время под позицией»)
            // остаётся: он и есть систематическая склонность держать больше,
            // когда цена падает. Выкинув бету, получаем вердикт, не зависящий от
            // траектории, — то, что искали с док. 153.
            //
            // Сходится с независимым путём через регрессию: на 08–15.09 у трёх
            // ботов тождество даёт −$0.022 на бота в сутки, а модель с изломом
            // (α + β·ход − γ·|ход| при нулевом дрейфе) — −$0.021.
            //
            // ⚠️ Регрессией этот вердикт не измерить: ДИ ±$0.057 при величине
            // 0.022, то есть нужно 214 суток. Тождеством он считается на любом
            // окне сразу, потому что это не оценка, а разложение.
            sb.append("🔑 ИТОГ БЕЗ БЕТЫ ЗАПАСА (вердикт на рынке без дрейфа): ")
                    .append(money((tc + tt) / days)).append(" на бота в сутки.\n")
                    .append("Это строка для сравнения настроек: траектория окна сидит\n")
                    .append("только в бете запаса, и здесь её нет.\n\n");
            sb.append("⚠️ Оговорка: вердикт верен как «на нейтральном рынке ПРИ ТАКОМ ЖЕ\n")
                    .append("поведении бота». На сильно трендовом окне бот набирает больше,\n")
                    .append("и ковариационный член сам зависит от режима.\n\n");
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

        if (hourly != null && !hourly.isEmpty()) {
            sb.append('\n').append(classSection());
            dumpHours(hoursOut);
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
        // Тики, на которых бот ДЕЙСТВИТЕЛЬНО котировал: час, где он полчаса
        // стоял по лимиту, мерит длину простоя, а не цену запаса.
        java.util.Set<Long> quoting = new java.util.HashSet<>();
        for (String path : paths) {
            if (!readQuotes(path.trim(), fromMs, toMs, marks, inv, quoting)) {
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
        if (hourly != null) {

            collectHours(name, marks, inv, trades, quoting);

        }

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
        /** Исполнения и оборот по часам — для часового разреза. */
        final TreeMap<Long, int[]> fillsByHour = new TreeMap<>();
        final TreeMap<Long, double[]> turnoverByHour = new TreeMap<>();
    }

    private static long hourOf(long ms) {
        return ms - Math.floorMod(ms, 3_600_000L);
    }

    /**
     * Часовой разрез одного бота: цена запаса, исполнения, волатильность.
     *
     * ⚠️ Час берётся ЦЕЛИКОМ или не берётся: неполный час на краю окна даёт
     * заниженную и цену запаса, и число исполнений сразу, а отношение одного к
     * другому при этом смещается непредсказуемо. Отбрасываются первый и
     * последний.
     */
    private void collectHours(String bot, TreeMap<Long, Double> marks,
                              TreeMap<Long, Double> inv, Trades trades,
                              java.util.Set<Long> quoting) {
        // Ход и волатильность часа считаются по тем же тикам, что и запас.
        TreeMap<Long, double[]> agg = new TreeMap<>();          // час → {carry, Σq, n, p0, p1, Σ(Δp/p)²}
        Long prevKey = null;
        for (Map.Entry<Long, Double> e : marks.entrySet()) {
            long h = hourOf(e.getKey());
            double px = e.getValue();
            double[] a = agg.computeIfAbsent(h, k -> new double[]{0, 0, 0, px, px, 0, 0});
            if (prevKey != null && hourOf(prevKey) == h) {
                double prevP = marks.get(prevKey);
                a[0] += inv.get(prevKey) * (px - prevP);
                double r = (px - prevP) / prevP;
                a[5] += r * r;
            }
            a[1] += inv.get(e.getKey());

            a[6] += inv.get(e.getKey()) * px;   // запас в ДОЛЛАРАХ: вес второй мерки
            a[2] += 1;
            a[4] = px;
            prevKey = e.getKey();
        }
        if (agg.size() < 3) {
            return;
        }
        long first = agg.firstKey();
        long last = agg.lastKey();
        Double prevVol = null;
        for (Map.Entry<Long, double[]> e : agg.entrySet()) {
            double[] a = e.getValue();
            // б.п. за минуту: СКО тикового хода, приведённое к минуте
            double secs = Math.max(1, a[2]);
            double vol = Math.sqrt(a[5] / secs) * 1e4 * Math.sqrt(60);
            long h = e.getKey();
            if (h != first && h != last && prevVol != null) {
                double meanQ = a[1] / a[2];
                double beta = meanQ * (a[4] - a[3]);
                int[] f = trades.fillsByHour.get(h);
                double[] t = trades.turnoverByHour.get(h);
                int qt = 0;
                        for (long ts : quoting) {
                            if (hourOf(ts) == h) {
                                qt++;
                            }
                        }
                        hourly.add(new Hour(bot, h, a[0], beta, a[0] - beta,
                                f == null ? 0 : f[0], t == null ? 0 : t[0], meanQ, vol, prevVol,
                                100.0 * (a[4] - a[3]) / a[3], (int) a[2], qt, a[6] / a[2]));
            }
            prevVol = vol;
        }
    }

    private boolean readQuotes(String path, long fromMs, long toMs,
                               TreeMap<Long, Double> marks, TreeMap<Long, Double> inv,
                               java.util.Set<Long> quoting) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(path).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, fair, inventory, quotable FROM exec_quote"
                             + " WHERE ts_ms >= ? AND ts_ms < ? AND fair > 0 ORDER BY ts_ms")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marks.put(rs.getLong(1), rs.getDouble(2));
                    inv.put(rs.getLong(1), rs.getDouble(3));
                    // 🔑 Котировал ли бот на этом тике. Нужно не для тождества, а
                    // для часового разреза: час, в котором бот полчаса стоял,
                    // мерит не цену запаса, а длину простоя.
                    if (rs.getInt(4) != 0) {
                        quoting.add(rs.getLong(1));
                    }
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
                    long h = hourOf(rs.getLong(1));
                    trades.fillsByHour.computeIfAbsent(h, k -> new int[1])[0]++;
                    trades.turnoverByHour.computeIfAbsent(h, k -> new double[1])[0] += qty * price;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("журнал {}: исполнения не прочитаны: {}", path, e.toString());
            return false;
        }
    }

    /**
     * ЦЕНА ЗАПАСА ПО КЛАССАМ ВОЛАТИЛЬНОСТИ — шаг 1 блока П3.0-бис (док. 187).
     *
     * <h2>Что решается</h2>
     *
     * Подтверждает: в бурном классе цена запаса НА ОДНО ИСПОЛНЕНИЕ заметно выше,
     * чем в тихом, — тогда оптимум отступа обязан ехать вправо в бурные часы, и
     * шаг 2 имеет смысл. Опровергает: примерно одинакова во всех классах — тогда
     * запас оптимум не двигает, и блок 3 закрыт окончательно, уже на полной
     * задаче.
     *
     * <h2>Две вещи, без которых число врёт</h2>
     *
     * ⚠️ Класс берётся по волатильности ПРЕДЫДУЩЕГО часа. По текущей вышел бы
     * идеальный, но недоступный боту прогноз: он бы «узнавал» бурный час в его
     * начале.
     *
     * ⚠️ Часы БЕЗ ИСПОЛНЕНИЙ в среднее не идут: цена запаса на исполнение там
     * деление на ноль, а молча считать их нулём значило бы разбавлять бурный
     * класс тишиной.
     */
    private String classSection() {
        List<Hour> all = hourly.stream().filter(h -> h.fills() > 0
                && h.turnover() > 0 && h.prevVol() > 0).toList();
        // ⚠️ Часы с простоем выбрасываются: бот выключается по суточному пределу
        // постановок, а выбирает его быстрее в БУРНЫЕ часы — то есть отбор
        // срезает ровно тот класс, который мы меряем.
        List<Hour> withFills = all.stream().filter(h -> h.idleShare() < 0.10).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("## Цена запаса по классам волатильности (шаг 1, док. 187)\n\n");
        if (withFills.size() < 20) {
            return sb.append("бот-часов с исполнениями всего ").append(withFills.size())
                    .append(" — на классы делить нечего\n").toString();
        }
        double[] vols = withFills.stream().mapToDouble(Hour::prevVol).sorted().toArray();
        double[] edge = {vols[vols.length / 4], vols[vols.length / 2],
                vols[vols.length * 3 / 4]};

        sb.append("класс — по волатильности ПРЕДЫДУЩЕГО часа, границы ")
                .append(String.format(Locale.ROOT, "%.2f / %.2f / %.2f б.п./мин",
                        edge[0], edge[1], edge[2]))
                .append("\n\n| класс | бот-часов | исполнений | ")
                .append("цена запаса, $/час | **на исполнение, б.п.** | ")
                .append("только ковариация, б.п. | захват−? |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");

        String[] names = {"тихий", "ниже среднего", "выше среднего", "бурный"};
        double[][] acc = new double[4][5];   // часы, исполнения, Σcarry, Σturnover, Σtiming
        for (Hour h : withFills) {
            int k = h.prevVol() <= edge[0] ? 0 : h.prevVol() <= edge[1] ? 1
                    : h.prevVol() <= edge[2] ? 2 : 3;
            acc[k][0]++;
            acc[k][1] += h.fills();
            acc[k][2] += h.carry();
            acc[k][3] += h.turnover();
            acc[k][4] += h.timing();
        }
        for (int k = 0; k < 4; k++) {
            double[] a = acc[k];
            sb.append("| ").append(names[k])
                    .append(" | ").append((long) a[0])
                    .append(" | ").append((long) a[1])
                    .append(" | ").append(String.format(Locale.ROOT, "%+.4f", a[2] / a[0]))
                    .append(" | ").append(String.format(Locale.ROOT, "**%+.2f**",
                            a[3] > 0 ? 1e4 * a[2] / a[3] : 0))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f",
                            a[3] > 0 ? 1e4 * a[4] / a[3] : 0))
                    .append(" |\n");
        }

        // Тихий против бурного — с ошибкой бутстрапом по часам, иначе это два
        // числа без права на сравнение.
        sb.append('\n').append(diff(withFills, edge));
        sb.append('\n').append(slope(withFills));
        // Вторая мерка — по НОСИМОМУ ЗАПАСУ. Ей не нужны исполнения в часе,
        // поэтому данных у неё втрое больше: у стендового бота при δ = 12 и
        // лоте $1 из 650 бот-часов с исполнениями годны 173, а с запасом — почти
        // все.
        List<Hour> withStock = hourly.stream().filter(h -> h.idleShare() < 0.10).toList();
        sb.append('\n').append(inventorySlope(withStock, false));
        sb.append('\n').append(inventorySlope(withStock, true));
        return sb.toString();
    }

    /**
     * ЦЕНА ЗАПАСА КАК ФУНКЦИЯ ВОЛАТИЛЬНОСТИ — по ВСЕМ часам, а не по крайним.
     *
     * <h2>Почему не хватило квартилей</h2>
     *
     * ⚠️ Сравнение «бурный минус тихий» выбрасывает половину наблюдений: средние
     * два класса в него не входят вовсе. На живых данных это дало точечную
     * оценку −6.2 б.п. при ошибке 7.2, то есть прибор не различал эффект и ноль.
     *
     * <h2>Что считается</h2>
     *
     * Взвешенная по обороту прямая {@code carry = a·T + b·T·V}, где {@code T} —
     * оборот часа, {@code V} — волатильность ПРЕДЫДУЩЕГО часа. Тогда цена
     * доллара запаса при волатильности {@code V} равна {@code a + b·V}, и весь
     * вопрос в знаке {@code b}: отрицательный означает «в бурный час запас
     * дороже».
     *
     * ⚠️ Ошибка — бутстрапом по ЧАСАМ целиком: шесть ботов внутри часа торгуют
     * один рынок.
     */
    private static String slope(List<Hour> hours) {
        return slope(hours, false) + "\n" + slope(hours, true);
    }

    /**
     * ЦЕНА ДОЛЛАРА ЗАПАСА В ЧАС — тот же вопрос, но на порядок больше данных.
     *
     * <h2>Почему понадобилась вторая мерка</h2>
     *
     * Счёт «на доллар ОБОРОТА» требует, чтобы в часе были исполнения, а их
     * часто нет: у стендового бота при δ = 12 и лоте $1 выходит полсотни сделок
     * в сутки, и из 650 бот-часов годными остаются 173. Здесь вес — НОСИМЫЙ
     * ЗАПАС, и годится каждый час, где бот что-то держал.
     *
     * <h2>Что считается</h2>
     *
     * {@code carry = a·Q + b·Q·V}, где {@code Q} — средний запас часа в долларах,
     * {@code V} — волатильность предыдущего часа. Тогда {@code a + b·V} — во что
     * обходится доллар запаса за час при такой волатильности.
     *
     * 🔑 Это прямая проверка того, что говорит теория (Авелланеда–Стойков): член
     * за риск запаса растёт с волатильностью, значит {@code b} обязан быть
     * отрицательным и заметным. Если он ноль — запас в бурный час не дороже, и
     * подстраивать отступ под волатильность не на чем.
     */
    private static String inventorySlope(List<Hour> hours, boolean useTiming) {
        TreeMap<Long, List<Hour>> byClock = new TreeMap<>();
        for (Hour h : hours) {
            if (Math.abs(h.meanQUsd()) > 0 && h.prevVol() > 0) {
                byClock.computeIfAbsent(h.hourMs(), k -> new ArrayList<>()).add(h);
            }
        }
        List<List<Hour>> units = new ArrayList<>(byClock.values());
        int n = units.stream().mapToInt(List::size).sum();
        if (n < 50) {
            return "часов с запасом мало (" + n + ")\n";
        }
        double[] point = fitInv(units, useTiming);
        if (point == null) {
            return "прямую по запасу не построить\n";
        }
        java.util.Random rnd = new java.util.Random(20260922L);
        List<Double> bs = new ArrayList<>();
        for (int r = 0; r < 2000; r++) {
            List<List<Hour>> sample = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                sample.add(units.get(rnd.nextInt(units.size())));
            }
            double[] f = fitInv(sample, useTiming);
            if (f != null) {
                bs.add(f[1]);
            }
        }
        double mean = bs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(bs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum()
                / Math.max(1, bs.size() - 1));
        java.util.Collections.sort(bs);
        double[] v = hours.stream().mapToDouble(Hour::prevVol).sorted().toArray();
        double lo = v[v.length / 10];
        double hi = v[v.length * 9 / 10];
        return String.format(Locale.ROOT,
                "### Цена ДОЛЛАРА ЗАПАСА в час — %s (%d бот-часов с запасом)%n%n"
                        + "`б.п. за час на доллар запаса = %+.3f %+.3f × волатильность`%n%n"
                        + "наклон: ошибка %.3f, **t = %+.2f**, 95%%%% [%+.3f, %+.3f]%n%n"
                        + "| волатильность | цена доллара запаса за час, б.п. |%n|---|---:|%n"
                        + "| 10%%%% (%.2f б.п./мин, тихо) | %+.3f |%n"
                        + "| 90%%%% (%.2f б.п./мин, бурно) | %+.3f |%n",
                useTiming ? "ТОЛЬКО КОВАРИАЦИЯ" : "весь перенос", n,
                point[0], point[1], sd, sd > 0 ? point[1] / sd : 0,
                bs.get((int) (0.025 * bs.size())), bs.get((int) (0.975 * bs.size())),
                lo, point[0] + point[1] * lo, hi, point[0] + point[1] * hi);
    }

    /** Взвешенная прямая {@code carry = a·Q + b·Q·V}, вес — носимый запас в долларах. */
    private static double[] fitInv(List<List<Hour>> units, boolean useTiming) {
        double s11 = 0;
        double s12 = 0;
        double s22 = 0;
        double y1 = 0;
        double y2 = 0;
        for (List<Hour> unit : units) {
            for (Hour h : unit) {
                double q = Math.abs(h.meanQUsd());
                double x1 = q;
                double x2 = q * h.prevVol();
                double y = useTiming ? h.timing() : h.carry();
                s11 += x1 * x1;
                s12 += x1 * x2;
                s22 += x2 * x2;
                y1 += x1 * y;
                y2 += x2 * y;
            }
        }
        double det = s11 * s22 - s12 * s12;
        return Math.abs(det) > 0
                ? new double[]{1e4 * (s22 * y1 - s12 * y2) / det,
                        1e4 * (s11 * y2 - s12 * y1) / det}
                : null;
    }

    /**
     * @param useTiming брать ТОЛЬКО ковариационную часть переноски.
     *
     * 🔑 Этот выбор решает не вкус, а мощность, и я сначала выбрал неверно.
     * Час раскладывается как
     * {@code Σq·Δp = средний_запас·(p1−p0) + Σ(q−средний)·Δp}. Первое слагаемое
     * — средний запас, умноженный на ход часа: ход случаен, знак его случаен, и
     * всё это чистый шум с нулевым средним. Второе — то, ради чего считается:
     * «запас оказывался выше своего среднего ровно тогда, когда цена падала».
     *
     * Ex ante боту доступно только второе: дрейф он предсказать не может, и в
     * ожидании тот ноль. Значит и цена запаса для решения — это ковариационный
     * член, и он же имеет меньший разброс. Полный перенос печатается рядом как
     * контроль.
     */
    private static String slope(List<Hour> hours, boolean useTiming) {
        TreeMap<Long, List<Hour>> byClock = new TreeMap<>();
        for (Hour h : hours) {
            byClock.computeIfAbsent(h.hourMs(), k -> new ArrayList<>()).add(h);
        }
        List<List<Hour>> units = new ArrayList<>(byClock.values());
        double[] point = fit(units, useTiming);
        if (point == null) {
            return "прямую не построить\n";
        }
        java.util.Random rnd = new java.util.Random(20260922L);
        List<Double> bs = new ArrayList<>();
        for (int r = 0; r < 2000; r++) {
            List<List<Hour>> sample = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                sample.add(units.get(rnd.nextInt(units.size())));
            }
            double[] f = fit(sample, useTiming);
            if (f != null) {
                bs.add(f[1]);
            }
        }
        if (bs.size() < 1000) {
            return "бутстрап прямой не собрался\n";
        }
        double mean = bs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(bs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum()
                / (bs.size() - 1));
        java.util.Collections.sort(bs);
        double[] v = hours.stream().mapToDouble(Hour::prevVol).sorted().toArray();
        double lo = v[v.length / 10];
        double hi = v[v.length * 9 / 10];
        return String.format(Locale.ROOT,
                "### Цена запаса как прямая по волатильности — %s (все %d бот-часов)%n%n"
                        + "`цена доллара запаса, б.п. = %+.2f %+.2f × волатильность`%n%n"
                        + "наклон: ошибка %.2f, **t = %+.2f**, 95%%%% [%+.2f, %+.2f]%n%n"
                        + "| волатильность | цена доллара запаса, б.п. |%n|---|---:|%n"
                        + "| 10%%%% (%.2f б.п./мин, тихо) | %+.2f |%n"
                        + "| 90%%%% (%.2f б.п./мин, бурно) | %+.2f |%n",
                useTiming ? "ТОЛЬКО КОВАРИАЦИЯ (решающая)" : "весь перенос (контроль)", hours.size(), point[0], point[1], sd, sd > 0 ? point[1] / sd : 0,
                bs.get((int) (0.025 * bs.size())), bs.get((int) (0.975 * bs.size())),
                lo, point[0] + point[1] * lo, hi, point[0] + point[1] * hi);
    }

    /** Взвешенная прямая {@code carry = a·T + b·T·V}; {@code null} — вырождено. */
    private static double[] fit(List<List<Hour>> units, boolean useTiming) {
        double s11 = 0;
        double s12 = 0;
        double s22 = 0;
        double y1 = 0;
        double y2 = 0;
        for (List<Hour> unit : units) {
            for (Hour h : unit) {
                double t = h.turnover();
                double x1 = t;
                double x2 = t * h.prevVol();
                s11 += x1 * x1;
                s12 += x1 * x2;
                s22 += x2 * x2;
                double y = useTiming ? h.timing() : h.carry();
                y1 += x1 * y;
                y2 += x2 * y;
            }
        }
        double det = s11 * s22 - s12 * s12;
        if (!(Math.abs(det) > 0)) {
            return null;
        }
        return new double[]{1e4 * (s22 * y1 - s12 * y2) / det,
                1e4 * (s11 * y2 - s12 * y1) / det};
    }

    /**
     * Разность «бурный минус тихий» — БУТСТРАПОМ ПО ЧАСАМ.
     *
     * <h2>Почему не среднее по бот-часам</h2>
     *
     * ⚠️ Нужная величина — {@code Σcarry / Σoborot} внутри класса, то есть
     * ВЗВЕШЕННАЯ по обороту. Простое среднее отношений даёт другое число и
     * чудовищный разброс: в часе с тремя сделками отношение скачет как угодно,
     * и на живых данных ошибка такого среднего вышла 5.72 б.п. при эффекте
     * 6 б.п. — то есть прибор не мерил ничего.
     *
     * <h2>Почему бутстрап, а не формула</h2>
     *
     * Оценка — ОТНОШЕНИЕ двух сумм, и её ошибку пришлось бы линеаризовать. Плюс
     * ⚠️ шесть ботов внутри одного часа торгуют один рынок и независимыми не
     * являются. Бутстрап по ЧАСАМ (час целиком, со всеми ботами) закрывает и то
     * и другое: пересобираем часы с возвращением и смотрим разброс разности.
     */
    private static String diff(List<Hour> hours, double[] edge) {
        // Час целиком — одна единица пересборки, вместе со всеми ботами в нём.
        TreeMap<Long, List<Hour>> byClock = new TreeMap<>();
        for (Hour h : hours) {
            byClock.computeIfAbsent(h.hourMs(), k -> new ArrayList<>()).add(h);
        }
        List<List<Hour>> units = new ArrayList<>(byClock.values());
        double point = ratioDiff(units, edge);
        if (Double.isNaN(point)) {
            return "клеток в крайних классах мало — сравнивать нельзя\n";
        }
        int reps = 2000;
        java.util.Random rnd = new java.util.Random(20260922L);
        double[] draws = new double[reps];
        int ok = 0;
        for (int r = 0; r < reps; r++) {
            List<List<Hour>> sample = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                sample.add(units.get(rnd.nextInt(units.size())));
            }
            double d = ratioDiff(sample, edge);
            if (!Double.isNaN(d)) {
                draws[ok++] = d;
            }
        }
        if (ok < reps / 2) {
            return "бутстрап не собрался\n";
        }
        double[] v = java.util.Arrays.copyOf(draws, ok);
        java.util.Arrays.sort(v);
        double mean = java.util.Arrays.stream(v).average().orElse(0);
        double sd = Math.sqrt(java.util.Arrays.stream(v)
                .map(x -> (x - mean) * (x - mean)).sum() / (ok - 1));
        return String.format(Locale.ROOT,
                "**БУРНЫЙ МИНУС ТИХИЙ: %+.2f б.п. на доллар оборота**%n"
                        + "бутстрап по часам (%d часов, %d пересборок): ошибка %.2f, "
                        + "t = %+.2f, 95%%%% промежуток [%+.2f, %+.2f]%n"
                        + "(часов в крайних классах считается по каждой пересборке)%n",
                point, units.size(), ok, sd, sd > 0 ? point / sd : 0,
                v[(int) (0.025 * ok)], v[(int) (0.975 * ok)]);
    }

    /** {@code Σcarry/Σoborot} бурного класса минус то же тихого; NaN — класс пуст. */
    private static double ratioDiff(List<List<Hour>> units, double[] edge) {
        double cc = 0;
        double ct = 0;
        double wc = 0;
        double wt = 0;
        for (List<Hour> unit : units) {
            for (Hour h : unit) {
                if (h.prevVol() <= edge[0]) {
                    cc += h.carry();
                    ct += h.turnover();
                } else if (h.prevVol() > edge[2]) {
                    wc += h.carry();
                    wt += h.turnover();
                }
            }
        }
        return ct > 0 && wt > 0 ? 1e4 * wc / wt - 1e4 * cc / ct : Double.NaN;
    }

    private void dumpHours(String path) {
        StringBuilder csv = new StringBuilder(
                "bot,hour_utc,carry,beta,timing,fills,turnover,mean_q,vol_bp_min,"
                        + "prev_vol_bp_min,move_pct,carry_bp_per_fill\n");
        for (Hour h : hourly) {
            csv.append(h.bot()).append(',').append(Instant.ofEpochMilli(h.hourMs()))
                    .append(String.format(Locale.ROOT,
                            ",%.8f,%.8f,%.8f,%d,%.6f,%.8f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                            h.carry(), h.beta(), h.timing(), h.fills(), h.turnover(),
                            h.meanQ(), h.volBp(), h.prevVol(), h.movePct(),
                            h.priceBp(false), h.idleShare()));
        }
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, csv.toString(), StandardCharsets.UTF_8);
            log.warn("часовой разрез: {} строк → {}", hourly.size(), p.toAbsolutePath());
        } catch (IOException e) {
            log.warn("не записать {}: {}", path, e.toString());
        }
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.4f", v);
    }
}
