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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * СКОЛЬКО КРУГОВ ДОБАВИЛ БЫ ЗАСЕВ. Команда {@code --revx-seed-gain}, пункт П4.0
 * из док. 165 — ворота блока 4.
 *
 * <h2>Вопрос</h2>
 *
 * Спотовый бот не может продать то, чего у него нет, поэтому половину времени и
 * больше аска в книге нет вовсе (BTC 49.9%, ETH 55.9%, SOL 68.0%). Засев —
 * стартовый запас — убрал бы этот пол. Вопрос один: сколько сделок это реально
 * добавит. Оценка «1/(1 − доля пустого)» заведомо завышена, потому что поток
 * покупателей ограничен сам по себе.
 *
 * <h2>Как считается — с самокалибровкой</h2>
 *
 * По ленте и журналу разом, чтобы не считать в разных единицах:
 * <ol>
 *   <li>для каждого принта ищем тик котировщика и смотрим, СТОЯЛА ли тогда наша
 *       заявка на этой стороне и доставал ли принт до её цены. Цена берётся из
 *       журнала — та, что реально стояла в книге;</li>
 *   <li>если заявки не было (пустой запас — нет аска; потолок — нет бида),
 *       подставляем цену, по которой бот выставил бы её при НУЛЕВОМ отклонении
 *       запаса от цели: {@code опора × (1 ± δ)}. Именно так стоял бы засеянный
 *       бот — у него отклонение около нуля, а значит и скоса нет;</li>
 *   <li>🔑 доля реализации считается ИЗ ДАННЫХ: сколько наших живых исполнений
 *       приходится на событие ленты, дотянувшееся до стоящей заявки. Этой долей
 *       и умножаются события «заявки не было» — то есть завышение модели
 *       сокращается само.</li>
 * </ol>
 *
 * ⚠️ Чего оценка не знает: засеянный бот торгует ДРУГУЮ траекторию (его скос
 * другой, значит и бид стоит иначе), и оценка верна только «в первом порядке» —
 * пока засев не меняет поведение рынка вокруг нас.
 */
@Component
@Lazy
public class SeedGain {

    private static final Logger log = LoggerFactory.getLogger(SeedGain.class);

    /** Тики котировщика: время, опора, бид, аск (ноль — заявки не было). */
    private record Ticks(long[] ts, double[] fair, double[] bid, double[] ask) {
        int index(long t) {
            int lo = 0;
            int hi = ts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ts[mid] <= t) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo - 1;
        }
    }

    public void run(String standDb, String journals, String fromIso, String toIso,
                    double deltaBp, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0
                : java.time.Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE
                : java.time.Instant.parse(toIso).toEpochMilli();

        StringBuilder sb = new StringBuilder("# П4.0: сколько кругов добавил бы засев\n\n");
        sb.append("Цена отсутствующей заявки — опора ± ").append(round(deltaBp, 1))
                .append(" б.п. (засеянный бот стоит без скоса).\n\n");
        sb.append("| бот | пара | часов | нет аска | нет бида | событий к аску: стоял / нет")
                .append(" | доля реализации | продаж/ч сейчас | наивно (только аск)")
                .append(" | **продаж/ч с засевом** | покупок/ч сейчас | **с засевом**")
                .append(" | кругов/ч сейчас | **с засевом** | **множитель** |\n");
        sb.append("|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");

        List<String> verdicts = new ArrayList<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String id = kv[0].trim();
            String base = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String bot = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
            Ticks t = ticks(kv[1].trim(), from, to);
            if (t == null || t.ts().length < 100) {
                log.warn("{}: тиков мало", id);
                continue;
            }
            int[] fills = fills(kv[1].trim(), from, to);
            List<TapeData.Print> prints = TapeData.prints(standDb, base + "/USDC",
                    t.ts()[0], t.ts()[t.ts().length - 1]);
            if (prints.size() < 100) {
                log.warn("{}: принтов мало ({})", id, prints.size());
                continue;
            }
            verdicts.add(row(sb, bot, base, t, fills, prints, deltaBp));
        }

        sb.append("\n# Вердикт П4.0\n\n");
        for (String v : verdicts) {
            sb.append("- ").append(v).append('\n');
        }
        sb.append("\nКритерий 165: подтверждает при множителе кругов ≥ 1.5 хотя бы на двух")
                .append(" парах; опровергает при < 1.2 — тогда отсутствие аска не было узким местом.\n");

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

    private String row(StringBuilder sb, String bot, String base, Ticks t, int[] fills,
                       List<TapeData.Print> prints, double deltaBp) {
        int noAsk = 0;
        int noBid = 0;
        for (int i = 0; i < t.ts().length; i++) {
            if (t.ask()[i] <= 0) {
                noAsk++;
            }
            if (t.bid()[i] <= 0) {
                noBid++;
            }
        }
        double hours = (t.ts()[t.ts().length - 1] - t.ts()[0]) / 3_600_000.0;

        int askPresent = 0;
        int askAbsent = 0;
        int bidPresent = 0;
        int bidAbsent = 0;
        int askSeed = 0;
        int bidSeed = 0;
        long prevMs = Long.MIN_VALUE;
        int prevSide = 0;
        for (TapeData.Print p : prints) {
            boolean sameBurst = p.tsMs() == prevMs && p.aggressor() == prevSide;
            prevMs = p.tsMs();
            prevSide = p.aggressor();
            if (sameBurst) {
                continue;
            }
            int i = t.index(p.tsMs());
            if (i < 0 || t.fair()[i] <= 0) {
                continue;
            }
            if (p.aggressor() > 0) {
                // Покупатель забрал — это наш АСК.
                double ask = t.ask()[i];
                if (ask > 0) {
                    if (p.price() >= ask) {
                        askPresent++;
                    }
                } else if (p.price() >= t.fair()[i] * (1 + deltaBp / 1e4)) {
                    askAbsent++;
                }
                // 🔑 Контрфакт засева: заявка стоит ВСЕГДА и РОВНО на δ — скоса
                // нет, потому что отклонение запаса от цели около нуля.
                if (p.price() >= t.fair()[i] * (1 + deltaBp / 1e4)) {
                    askSeed++;
                }
            } else {
                double bid = t.bid()[i];
                if (bid > 0) {
                    if (p.price() <= bid) {
                        bidPresent++;
                    }
                } else if (p.price() <= t.fair()[i] * (1 - deltaBp / 1e4)) {
                    bidAbsent++;
                }
                // ⚠️ И та же симметрия бьёт по покупкам: сейчас пустой бот
                // подтягивает бид скосом (7.7 б.п. вместо 12), а засеянный — нет.
                if (p.price() <= t.fair()[i] * (1 - deltaBp / 1e4)) {
                    bidSeed++;
                }
            }
        }

        // 🔑 Доля реализации: сколько живых исполнений на одно событие, дотянувшееся
        // до СТОЯВШЕЙ заявки. Ею же умножаются события «заявки не было».
        double realSell = askPresent > 0 ? fills[1] / (double) askPresent : 0;
        double realBuy = bidPresent > 0 ? fills[0] / (double) bidPresent : 0;
        double sellsNow = fills[1] / hours;
        double buysNow = fills[0] / hours;
        // Наивная оценка «аск появился, остальное как было» — она завышена.
        double sellsNaive = (fills[1] + realSell * askAbsent) / hours;
        // Честная: обе стороны стоят на δ всегда, скоса нет.
        double sellsSeed = realSell * askSeed / hours;
        double buysSeed = realBuy * bidSeed / hours;
        double circlesNow = Math.min(buysNow, sellsNow);
        double circlesSeed = Math.min(buysSeed, sellsSeed);
        double mult = circlesNow > 0 ? circlesSeed / circlesNow : 0;

        sb.append("| ").append(bot).append(" | ").append(base)
                .append(" | ").append(round(hours, 0))
                .append(" | ").append(round(100.0 * noAsk / t.ts().length, 1)).append("% |")
                .append(' ').append(round(100.0 * noBid / t.ts().length, 1)).append("% | ")
                .append(askPresent).append(" / ").append(askAbsent)
                .append(" | ").append(round(realSell, 2))
                .append(" | ").append(round(sellsNow, 2))
                .append(" | ").append(round(sellsNaive, 2))
                .append(" | **").append(round(sellsSeed, 2))
                .append("** | ").append(round(buysNow, 2))
                .append(" | **").append(round(buysSeed, 2)).append("**")
                .append(" | ").append(round(circlesNow, 2))
                .append(" | **").append(round(circlesSeed, 2))
                .append("** | **×").append(round(mult, 2)).append("** |\n");
        return bot + " " + base + ": множитель кругов ×" + round(mult, 2)
                + (mult >= 1.5 ? " — **подтверждает**"
                : mult < 1.2 ? " — **опровергает**" : " — серая зона");
    }

    /** Тики котировщика; заявки нет — ноль. */
    private Ticks ticks(String journals, long from, long to) {
        List<long[]> ts = new ArrayList<>();
        List<double[]> vals = new ArrayList<>();
        for (String path : journals.split("\\+")) {
            String url = "jdbc:sqlite:file:" + Path.of(path.trim()).toAbsolutePath() + "?mode=ro";
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT ts_ms, fair, bid, ask FROM exec_quote WHERE fair > 0"
                                 + " AND ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ts.add(new long[]{rs.getLong(1)});
                        vals.add(new double[]{rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)});
                    }
                }
            } catch (Exception e) {
                log.warn("тики из {}: {}", path, e.toString());
                return null;
            }
        }
        if (ts.isEmpty()) {
            return null;
        }
        long[] t = new long[ts.size()];
        double[] f = new double[ts.size()];
        double[] b = new double[ts.size()];
        double[] a = new double[ts.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ts.get(i)[0];
            f[i] = vals.get(i)[0];
            b[i] = vals.get(i)[1];
            a[i] = vals.get(i)[2];
        }
        return new Ticks(t, f, b, a);
    }

    /** Живые исполнения: {покупок, продаж}. */
    private int[] fills(String journals, long from, long to) {
        int[] out = new int[2];
        for (String path : journals.split("\\+")) {
            String url = "jdbc:sqlite:file:" + Path.of(path.trim()).toAbsolutePath() + "?mode=ro";
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT side, count(*) FROM exec_fill WHERE ts_ms >= ? AND ts_ms < ?"
                                 + " GROUP BY side")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String side = rs.getString(1);
                        if (side != null && side.toUpperCase().startsWith("B")) {
                            out[0] += rs.getInt(2);
                        } else {
                            out[1] += rs.getInt(2);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("сделки из {}: {}", path, e.toString());
            }
        }
        return out;
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
