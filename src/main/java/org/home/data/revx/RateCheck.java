package org.home.data.revx;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code --revx-rate-check}: НАША МЕДИАНА КУРСА ПРОТИВ ПРЯМОЙ КНИГИ USDC/USD.
 *
 * <h2>Вопрос, который висит с 10.09.2026</h2>
 *
 * Справедливую цену мы считаем как {@code mid_usd / курс}, а курс — медианой
 * подразумеваемых оценок по парам: {@code implied = mid(X/USD) / mid(X/USDC)}.
 * В тот день выяснилось, что у площадки есть ПРЯМАЯ книга {@code USDC/USD} со
 * спредом в один базисный пункт, и мы её ни разу не собирали. Три одновременных
 * замера показали расхождение 3.1, 3.4 и 9.9 б.п. при боевом отступе 12, а наша
 * медиана гуляла на 7 б.п. за 43 секунды, пока прямая книга стояла.
 *
 * ⚠️ Тогда же было записано правило: тремя замерами основание всего котирования
 * не меняют, нужен параллельный сбор хотя бы на сутки. Сбор идёт с 10.09
 * ({@code revx.rate-book-symbol}), и этот прибор его наконец разбирает.
 *
 * <h2>Как считается</h2>
 *
 * Ровно так же, как считает живой бот, иначе сравнение бессмысленно:
 * <ul>
 *   <li>ноги пары сшиваются ПО {@code snap_id}, а не «последняя с последней»:
 *       наивная склейка берёт USDC из цикла N, а USD из N−1;</li>
 *   <li>перекос ног больше {@link #maxSkewMs} — пара выбрасывается;</li>
 *   <li>снимок старше {@link #FRESH_MS} — не годится;</li>
 *   <li>мемкоины в расчёт курса не входят (ТЗ §4.1);</li>
 *   <li>меньше {@code minPairs} пар — курс считается ненадёжным, и такие
 *       моменты считаются отдельно: это не «расхождение», это «мы вообще не
 *       знали курса».</li>
 * </ul>
 *
 * Опорные точки — отметки ПРЯМОЙ книги (раз в 10 с): для каждой берётся наша
 * медиана на тот же момент.
 *
 * <h2>Что прибор НЕ делает</h2>
 *
 * Он ничего не меняет и ничего не советует. Смена опоры курса — это смена
 * основания всего котирования; здесь только измерение, по которому такое решение
 * можно будет принимать.
 */
@Component
@Lazy
public class RateCheck {

    private static final Logger log = LoggerFactory.getLogger(RateCheck.class);

    /** Сколько секунд снимок ноги считается свежим — как {@code stand.latest(base, 30_000)}. */
    private static final long FRESH_MS = 30_000;

    private final RevxConfig cfg;

    public RateCheck(RevxConfig cfg) {
        this.cfg = cfg;
    }

    private long maxSkewMs;

    /** Нога книги: время, перекос и середина. */
    private record Leg(long recvMs, long skewMs, double mid) {
    }

    public void run(String dbPath, long fromMs, long toMs, String out) {
        maxSkewMs = cfg.fairMaxSkewMs();
        List<String> bases = new ArrayList<>();
        java.util.Set<String> memecoins = new java.util.HashSet<>(cfg.memecoins());
        TreeMap<Long, Double> direct = new TreeMap<>();
        Map<String, TreeMap<Long, Leg>> usdcLegs = new HashMap<>();
        Map<String, TreeMap<Long, Leg>> usdLegs = new HashMap<>();

        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:file:" + Path.of(dbPath).toAbsolutePath() + "?mode=ro")) {
            // Прямая книга курса — опорные точки сравнения.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t_recv_ms, bp1, ap1 FROM revx_book WHERE symbol = 'USDC/USD'"
                            + " AND t_recv_ms >= ? AND t_recv_ms < ? ORDER BY t_recv_ms")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double bid = rs.getDouble(2);
                        double ask = rs.getDouble(3);
                        if (bid > 0 && ask > 0) {
                            direct.put(rs.getLong(1), (bid + ask) / 2);
                        }
                    }
                }
            }
            // Ноги остальных пар. Ключ — snap_id: ноги сшиваются по нему.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT symbol, snap_id, t_recv_ms, coalesce(skew_ms, 0), bp1, ap1"
                            + " FROM revx_book WHERE t_recv_ms >= ? AND t_recv_ms < ?"
                            + " AND symbol <> 'USDC/USD' AND bp1 > 0 AND ap1 > 0")) {
                ps.setLong(1, fromMs - FRESH_MS);
                ps.setLong(2, toMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String symbol = rs.getString(1);
                        int slash = symbol.lastIndexOf('/');
                        if (slash < 0) {
                            continue;
                        }
                        String base = symbol.substring(0, slash);
                        String quote = symbol.substring(slash + 1);
                        Leg leg = new Leg(rs.getLong(3), rs.getLong(4),
                                (rs.getDouble(5) + rs.getDouble(6)) / 2);
                        Map<String, TreeMap<Long, Leg>> target =
                                "USDC".equals(quote) ? usdcLegs
                                        : "USD".equals(quote) ? usdLegs : null;
                        if (target == null) {
                            continue;
                        }
                        target.computeIfAbsent(base, k -> new TreeMap<>()).put(rs.getLong(2), leg);
                    }
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась база {}: {}", dbPath, e.getMessage());
            return;
        }
        for (String base : usdcLegs.keySet()) {
            if (usdLegs.containsKey(base) && !memecoins.contains(base)) {
                bases.add(base);
            }
        }
        Collections.sort(bases);
        if (direct.isEmpty() || bases.isEmpty()) {
            log.error("нечего сравнивать: прямых снимков {}, пар с обеими ногами {}",
                    direct.size(), bases.size());
            return;
        }

        // Для каждой опорной точки считаем нашу медиану тем же способом, что бот.
        List<Double> diffs = new ArrayList<>();
        TreeMap<String, List<Double>> byDay = new TreeMap<>();
        TreeMap<Long, Double> ourSeries = new TreeMap<>();
        int unreliable = 0;
        int noPairs = 0;
        for (Map.Entry<Long, Double> point : direct.entrySet()) {
            long at = point.getKey();
            List<Double> implied = new ArrayList<>();
            for (String base : bases) {
                Double value = impliedAt(usdcLegs.get(base), usdLegs.get(base), at);
                if (value != null) {
                    implied.add(value);
                }
            }
            if (implied.isEmpty()) {
                noPairs++;
                continue;
            }
            if (implied.size() < cfg.fairMinPairs()) {
                unreliable++;
                continue;
            }
            double ours = median(implied);
            ourSeries.put(at, ours);
            double diffBp = (ours / point.getValue() - 1) * 10_000;
            diffs.add(diffBp);
            byDay.computeIfAbsent(
                            Instant.ofEpochMilli(at).toString().substring(0, 10),
                            k -> new ArrayList<>())
                    .add(diffBp);
        }

        StringBuilder sb = new StringBuilder("# Курс USDC/USD: наша медиана против прямой книги\n\n");
        sb.append("база: `").append(dbPath).append("`\n\n");
        sb.append("окно: ").append(Instant.ofEpochMilli(fromMs)).append(" .. ")
                .append(Instant.ofEpochMilli(toMs)).append("\n\n");
        sb.append(String.format(Locale.ROOT,
                "пар в корзине: %d (%s)%nточек прямой книги: %d, сравнено: %d%n%n",
                bases.size(), String.join(", ", bases), direct.size(), diffs.size()));

        sb.append("## Расхождение медианы с прямой книгой, б.п.\n\n");
        sb.append("| срез | точек | медиана | 10%% | 90%% | СКО |\n|---|---:|---:|---:|---:|---:|\n"
                .replace("%%", "%"));
        sb.append(row("всё окно", diffs));
        for (Map.Entry<String, List<Double>> day : byDay.entrySet()) {
            sb.append(row(day.getKey(), day.getValue()));
        }

        sb.append(String.format(Locale.ROOT,
                "%nмоментов, когда курс считался НЕНАДЁЖНЫМ (пар меньше %d): %d; "
                        + "совсем без пар: %d%n", cfg.fairMinPairs(), unreliable, noPairs));

        // Шум: насколько дёргается каждая оценка сама по себе. Считается по
        // изменению за минуту — той же мерой, что и всё остальное в проекте.
        sb.append("\n## Кто из двух шумит\n\n");
        sb.append("| ряд | шаг за минуту, медиана |б.п.| | 90%% |б.п.| |\n|---|---:|---:|\n"
                .replace("|б.п.| ", ""));
        sb.append(noise("наша медиана", ourSeries));
        sb.append(noise("прямая книга", direct));

        sb.append("""

                ⚠️ Как читать. Расхождение — это СМЕЩЕНИЕ нашей опоры: на столько
                справедливая цена уезжает от той, что видит площадка в своей же книге
                курса. Смещение двигает ОБЕ котировки: бид уходит от рынка, аск
                придвигается, — и при κ ≈ 0.385 на базисный пункт это заметная доля
                потока (CLAUDE.md).

                ⚠️ Прибор НИЧЕГО не меняет и не советует. Опора курса — основание
                всего котирования; здесь только измерение, по которому решение можно
                принимать.
                """);
        write(out, sb.toString());
        log.info("\n{}", sb);
    }

    /**
     * Подразумеваемый курс пары на момент {@code at}: самый свежий снимок, у
     * которого ОБЕ ноги пришли одним циклом.
     *
     * Сшивка по {@code snap_id} — не тонкость: наивная «последняя с последней»
     * берёт USDC из одного цикла, а USD из соседнего, и расхождение выходит
     * равным периоду опроса.
     */
    private Double impliedAt(TreeMap<Long, Leg> usdc, TreeMap<Long, Leg> usd, long at) {
        if (usdc == null || usd == null) {
            return null;
        }
        // Идём по снимкам сверху вниз: нужен самый свежий, который годится.
        for (Map.Entry<Long, Leg> e : usdc.descendingMap().entrySet()) {
            Leg legUsdc = e.getValue();
            if (legUsdc.recvMs() > at) {
                continue;                 // заглядывать вперёд нельзя
            }
            if (at - legUsdc.recvMs() > FRESH_MS) {
                return null;              // дальше только старее
            }
            Leg legUsd = usd.get(e.getKey());
            if (legUsd == null || legUsd.recvMs() > at
                    || at - legUsd.recvMs() > FRESH_MS) {
                continue;
            }
            if (Math.max(legUsdc.skewMs(), legUsd.skewMs()) > maxSkewMs) {
                continue;                 // ноги разъехались — пара не в счёт
            }
            return legUsdc.mid() > 0 ? legUsd.mid() / legUsdc.mid() : null;
        }
        return null;
    }

    private static String row(String label, List<Double> values) {
        if (values.isEmpty()) {
            return String.format(Locale.ROOT, "| %s | 0 | — | — | — | — |%n", label);
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "| %s | %d | %+.2f | %+.2f | %+.2f | %.2f |%n",
                label, values.size(), quantile(sorted, 0.5),
                quantile(sorted, 0.1), quantile(sorted, 0.9), sd(values));
    }

    /** Шаг ряда за минуту: мера собственной дёрганости оценки. */
    private static String noise(String label, TreeMap<Long, Double> series) {
        List<Double> steps = new ArrayList<>();
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        for (Map.Entry<Long, Double> e : series.entrySet()) {
            byMinute.put(e.getKey() / 60_000, e.getValue());
        }
        Long prevKey = null;
        double prevValue = 0;
        for (Map.Entry<Long, Double> e : byMinute.entrySet()) {
            if (prevKey != null && e.getKey() - prevKey == 1 && prevValue > 0) {
                steps.add(Math.abs(e.getValue() - prevValue) / prevValue * 10_000);
            }
            prevKey = e.getKey();
            prevValue = e.getValue();
        }
        if (steps.isEmpty()) {
            return String.format(Locale.ROOT, "| %s | — | — |%n", label);
        }
        Collections.sort(steps);
        return String.format(Locale.ROOT, "| %s | %.2f | %.2f |%n",
                label, quantile(steps, 0.5), quantile(steps, 0.9));
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double quantile(List<Double> sorted, double p) {
        int i = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
        return sorted.get(i);
    }

    private static double sd(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double v = 0;
        for (double x : values) {
            v += (x - mean) * (x - mean);
        }
        return Math.sqrt(v / (values.size() - 1));
    }

    private void write(String out, String text) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, text, StandardCharsets.UTF_8);
            log.info("отчёт записан: {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.error("не записался отчёт {}: {}", out, e.getMessage());
        }
    }
}
