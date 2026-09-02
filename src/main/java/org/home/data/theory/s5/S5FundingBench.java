package org.home.data.theory.s5;

import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Фильтр «дорогого шорта» S5 на данных Kraken (док. 130 §III п.6).
 *
 * <b>Что проверяется.</b> Бот пропускает событие, если ожидаемая стоимость
 * фондирования за срок удержания хуже порога:
 *
 * <pre>
 * оценка = относительная ЧАСОВАЯ ставка сейчас × 120 ч   (KrakenFundingSource)
 * если оценка &lt; −1.5% — событие пропускается            (S5Config)
 * </pre>
 *
 * И оценка, и порог перенесены с Binance, где ставка ВОСЬМИЧАСОВАЯ, а
 * персистентность измерена как {@code corr = 0.812}. У Kraken ставка часовая, и
 * ни персистентность, ни уровень отсечки на ней никто не мерил: коллектор с
 * самого начала собирал фондирование по двум символам. Теперь собрана вся
 * вселенная (274 перпа), и вопрос решается прямо.
 *
 * <b>Устройство проверки.</b> Для каждого часа {@code t}, у которого есть
 * 120 часов вперёд, считаются две величины:
 *
 * <ul>
 *   <li><b>оценка</b> — то, что видит бот: {@code rel_rate(t) × 120};</li>
 *   <li><b>факт</b> — то, что случится: сумма {@code rel_rate} по {@code t..t+120}.</li>
 * </ul>
 *
 * Корреляция между ними и есть аналог {@code 0.812}. Если она низка, фильтр
 * отбирает по шуму, и вопрос об уровне порога теряет смысл раньше, чем
 * начинается: сначала должно быть что предсказывать.
 *
 * <b>Смысл знака.</b> Шорт ПОЛУЧАЕТ фондирование при положительной ставке,
 * поэтому дорогой шорт — это отрицательная сумма. Порог тоже отрицательный.
 */
@Component
@Lazy
public class S5FundingBench {

    private static final Logger log = LoggerFactory.getLogger(S5FundingBench.class);

    /** Срок удержания S5 в часах: пять суток лида (см. {@code theory.kelly.lead}). */
    private static final int HORIZON_HOURS = 120;
    /** Порог из {@code S5Config.protocol()} — тот самый, перенесённый с Binance. */
    private static final double THRESHOLD = -0.015;
    /** Сглаживания оценки, которые проверяются как альтернатива мгновенной ставке. */
    private static final int[] SMOOTH_HOURS = {1, 8, 24, 72, 168};

    private final Db db;
    private final int lead;

    public S5FundingBench(Db db, @Value("${theory.kelly.lead}") int lead) {
        this.db = db;
        this.lead = lead;
    }

    /** Пара «что оценил бот» → «что случилось на самом деле». */
    private record Point(double estimate, double actual) {
    }

    public void run(String outDir) {
        Map<String, List<double[]>> bySymbol = load();
        if (bySymbol.isEmpty()) {
            log.error("kraken_funding пуста — сначала --backfill=kraken-funding");
            return;
        }
        int horizon = lead * 24;
        StringBuilder md = new StringBuilder();
        md.append("# S5: фильтр дорогого шорта на данных Kraken\n\n");
        md.append("Пункт 6 очереди док. 130 (он же 9 в очереди 134). Бот пропускает "
                + "событие, если `относительная часовая ставка × ").append(horizon)
                .append(" ч` хуже **").append(pct(THRESHOLD)).append("**. И оценка, и "
                        + "порог перенесены с Binance, где ставка восьмичасовая, а "
                        + "персистентность измерена как `corr = 0.812`. На Kraken это не "
                        + "проверялось: коллектор собирал два символа из 274.\n\n");

        int symbols = bySymbol.size();
        long rows = bySymbol.values().stream().mapToLong(List::size).sum();
        md.append("| Данные | |\n|---|---|\n");
        md.append("| Символов | ").append(symbols).append(" |\n");
        md.append("| Часовых ставок | ").append(rows).append(" |\n");
        md.append("| Горизонт удержания | ").append(horizon).append(" ч |\n\n");

        // --- 1. Персистентность ------------------------------------------------
        md.append("## 1. Предсказывает ли текущая ставка стоимость удержания\n\n");
        md.append("Аналог `corr 0.812` с Binance. Для каждого часа с полным горизонтом "
                + "впереди: **оценка** — то, что видит бот, **факт** — сумма ставок за "
                + "следующие ").append(horizon).append(" часов.\n\n");
        md.append("| Оценка | Точек | corr(оценка, факт) | R² |\n|---|---|---|---|\n");
        Map<Integer, List<Point>> byWindow = new LinkedHashMap<>();
        for (int w : SMOOTH_HOURS) {
            List<Point> pts = points(bySymbol, w, horizon);
            byWindow.put(w, pts);
            double r = corr(pts);
            md.append(String.format(Locale.ROOT, "| %s | %d | **%.3f** | %.3f |%n",
                    w == 1 ? "мгновенная ставка (как сейчас)" : "среднее за " + w + " ч",
                    pts.size(), r, r * r));
        }
        md.append("\n**Как читать.** Первая строка — то, на чём фильтр стоит сегодня. "
                + "Остальные отвечают, стало бы лучше от сглаживания: часовая ставка "
                + "шумнее восьмичасовой по построению, и если усреднение поднимает "
                + "корреляцию, то чинится фильтр одной строкой кода.\n\n");

        // --- 2. Что фильтр делает на самом деле ---------------------------------
        List<Point> live = byWindow.get(1);
        md.append("## 2. Что фильтр отсекает и что этим экономит\n\n");
        md.append(decision(live, horizon));

        // --- 3. Распределение факта и где стоит порог ---------------------------
        md.append("\n## 3. Распределение фактической стоимости удержания\n\n");
        double[] actual = live.stream().mapToDouble(Point::actual).sorted().toArray();
        md.append("| Квантиль | Стоимость за ").append(horizon).append(" ч |\n|---|---|\n");
        for (double q : new double[]{0.01, 0.05, 0.25, 0.50, 0.75, 0.95, 0.99}) {
            md.append(String.format(Locale.ROOT, "| %.0f%% | %s |%n",
                    q * 100, pct(quantile(actual, q))));
        }
        double share = 100.0 * Arrays.stream(actual).filter(v -> v < THRESHOLD).count() / actual.length;
        md.append(String.format(Locale.ROOT,
                "%n**Ниже порога %s оказывается %.2f%% всех окон.** Это доля случаев, когда "
                        + "удержание действительно стоило дороже порога — а не доля, "
                        + "которую отсекает фильтр (она в §2).%n%n", pct(THRESHOLD), share));

        // --- 4. Где порог был бы осмысленным ------------------------------------
        md.append("## 4. Уровень отсечки\n\n");
        md.append("| Порог по оценке | Отсекается окон | Ср. факт у отсечённых | "
                + "Ср. факт у пропущенных | Разница |\n|---|---|---|---|---|\n");
        for (double th : new double[]{-0.005, -0.01, -0.015, -0.02, -0.03, -0.05}) {
            md.append(cutoffRow(live, th));
        }
        md.append("\nПоследняя колонка — вся польза фильтра: насколько дешевле окна, "
                + "которые он пропускает, по сравнению с теми, что отсекает. Если "
                + "разница близка к нулю, фильтр не различает дорогие окна от дешёвых "
                + "независимо от уровня порога.\n\n");

        // --- 5. Решающая величина: итог на КАНДИДАТА ----------------------------
        //
        // Разница между отсечёнными и пропущенными сама по себе решения не
        // принимает: ужесточая порог, мы экономим на фондировании, но выбрасываем
        // события вместе с их премией. Считать надо ожидание на КАНДИДАТА —
        // событие, которое ещё только предстоит отфильтровать.
        md.append("## 5. Итог на кандидата: порог платит премией\n\n");
        md.append("Ужесточая порог, мы экономим на фондировании — но выбрасываем события "
                + "вместе с их премией. Поэтому сравнивать надо ожидание на КАНДИДАТА:\n\n");
        md.append("```\nитог = доля пропущенных × (премия события + фондирование у пропущенных)\n```\n\n");
        md.append("| Порог | Пропускается | Фондирование у пропущенных | **Итог на кандидата** |\n");
        md.append("|---|---|---|---|\n");
        md.append(candidateRow(live, Double.NEGATIVE_INFINITY, "без фильтра"));
        for (double th : new double[]{-0.005, -0.01, -0.015, -0.02, -0.03, -0.05}) {
            md.append(candidateRow(live, th, pct(th) + (th == THRESHOLD ? " (сейчас)" : "")));
        }
        md.append(String.format(Locale.ROOT,
                "%n⚠️ **Премия взята равной %s** — это оценка док. 131 §4 по вселенной "
                        + "Kraken, и она статистически незначима (t = 1.44). Складывать "
                        + "измеренное фондирование с неуверенной премией можно только "
                        + "как ИЛЛЮСТРАЦИЮ формы зависимости, а не как оптимизацию: "
                        + "сдвинется премия — сдвинется и оптимум.%n%n", pct(PREMIUM)));
        md.append("Что от премии НЕ зависит и потому надёжно: слишком тесный порог "
                + "выбрасывает столько событий, что проигрывает отсутствию фильтра "
                + "вовсе. Это верно при любой положительной премии.\n");

        write(outDir, md.toString());
    }

    // --- расчёты --------------------------------------------------------------

    /** Ряды относительных часовых ставок по символам, упорядоченные по времени. */
    private Map<String, List<double[]>> load() {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        db.query("SELECT symbol, funding_time, rel_rate FROM kraken_funding "
                + "WHERE rel_rate IS NOT NULL ORDER BY symbol, funding_time", rs -> {
            out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new double[]{rs.getLong(2), rs.getDouble(3)});
            return null;
        });
        return out;
    }

    /**
     * Точки «оценка → факт».
     *
     * ⚠️ Ряды рвутся: у делистнутых перпов и у новых листингов в середине бывают
     * дыры. Горизонт считается по ФАКТИЧЕСКОМУ числу часов между метками, и
     * окно, внутри которого пропущено больше десятой части часов, выбрасывается
     * — иначе «сумма за 120 часов» окажется суммой за 40, и корреляция вырастет
     * из ниоткуда.
     */
    private static List<Point> points(Map<String, List<double[]>> bySymbol, int smoothHours,
                                      int horizon) {
        List<Point> out = new ArrayList<>();
        for (List<double[]> series : bySymbol.values()) {
            int n = series.size();
            if (n < smoothHours + horizon + 1) {
                continue;
            }
            double[] prefix = new double[n + 1];
            for (int i = 0; i < n; i++) {
                prefix[i + 1] = prefix[i] + series.get(i)[1];
            }
            for (int i = smoothHours - 1; i + horizon < n; i++) {
                long from = (long) series.get(i)[0];
                long to = (long) series.get(i + horizon)[0];
                long gapHours = (to - from) / 3_600_000L;
                if (gapHours > horizon * 1.1 || gapHours < horizon * 0.9) {
                    continue;                       // дыра в ряду — окно негодно
                }
                double estimate = (prefix[i + 1] - prefix[i + 1 - smoothHours])
                        / smoothHours * horizon;
                double actual = prefix[i + 1 + horizon] - prefix[i + 1];
                out.add(new Point(estimate, actual));
            }
        }
        return out;
    }

    private String decision(List<Point> pts, int horizon) {
        long blocked = pts.stream().filter(p -> p.estimate() < THRESHOLD).count();
        double blockedActual = pts.stream().filter(p -> p.estimate() < THRESHOLD)
                .mapToDouble(Point::actual).average().orElse(Double.NaN);
        double passedActual = pts.stream().filter(p -> p.estimate() >= THRESHOLD)
                .mapToDouble(Point::actual).average().orElse(Double.NaN);
        double all = pts.stream().mapToDouble(Point::actual).average().orElse(Double.NaN);

        StringBuilder sb = new StringBuilder();
        sb.append("| Величина | Значение |\n|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| Окон всего | %d |%n", pts.size()));
        sb.append(String.format(Locale.ROOT, "| **Отсекается фильтром** | **%d (%.2f%%)** |%n",
                blocked, 100.0 * blocked / pts.size()));
        sb.append(String.format(Locale.ROOT, "| Средняя стоимость у ОТСЕЧЁННЫХ | %s |%n",
                pct(blockedActual)));
        sb.append(String.format(Locale.ROOT, "| Средняя стоимость у ПРОПУЩЕННЫХ | %s |%n",
                pct(passedActual)));
        sb.append(String.format(Locale.ROOT, "| Средняя стоимость без фильтра | %s |%n",
                pct(all)));
        sb.append(String.format(Locale.ROOT, "| **Экономия на событие** | **%s** |%n%n",
                pct(passedActual - all)));
        sb.append("Экономия — это то, ради чего фильтр существует: насколько дешевле "
                + "оказывается удержание, если брать только пропущенные события. Её надо "
                + "сравнивать с премией события (+1.00% на вселенной Kraken, док. 131 §4): "
                + "фильтр, экономящий сотые доли процента, не окупает того, что вместе с "
                + "дорогими окнами он выбрасывает и часть прибыльных событий.\n\n");
        return sb.toString();
    }

    /**
     * Премия события на вселенной Kraken (док. 131 §4). Незначима (t = 1.44) и
     * взята только для иллюстрации формы зависимости — см. оговорку в отчёте.
     */
    private static final double PREMIUM = 0.010;

    private String candidateRow(List<Point> pts, double threshold, String label) {
        List<Point> passed = pts.stream().filter(p -> p.estimate() >= threshold).toList();
        double keep = (double) passed.size() / pts.size();
        double funding = passed.stream().mapToDouble(Point::actual).average().orElse(Double.NaN);
        return String.format(Locale.ROOT, "| %s | %.1f%% | %s | **%s** |%n",
                label, keep * 100, pct(funding), pct(keep * (PREMIUM + funding)));
    }

    private String cutoffRow(List<Point> pts, double threshold) {
        long blocked = pts.stream().filter(p -> p.estimate() < threshold).count();
        double blockedActual = pts.stream().filter(p -> p.estimate() < threshold)
                .mapToDouble(Point::actual).average().orElse(Double.NaN);
        double passedActual = pts.stream().filter(p -> p.estimate() >= threshold)
                .mapToDouble(Point::actual).average().orElse(Double.NaN);
        return String.format(Locale.ROOT, "| %s%s | %d (%.2f%%) | %s | %s | %s |%n",
                pct(threshold), threshold == THRESHOLD ? " (сейчас)" : "",
                blocked, 100.0 * blocked / pts.size(),
                pct(blockedActual), pct(passedActual), pct(passedActual - blockedActual));
    }

    private static double corr(List<Point> pts) {
        if (pts.size() < 3) {
            return Double.NaN;
        }
        double mx = pts.stream().mapToDouble(Point::estimate).average().orElse(0);
        double my = pts.stream().mapToDouble(Point::actual).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (Point p : pts) {
            double dx = p.estimate() - mx;
            double dy = p.actual() - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : Double.NaN;
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        return sorted[Math.min(sorted.length - 1,
                Math.max(0, (int) Math.round(q * (sorted.length - 1))))];
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%+.3f%%", v * 100);
    }

    private void write(String outDir, String md) {
        try {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("s5_funding.md");
            Files.writeString(file, md, StandardCharsets.UTF_8);
            log.info("фильтр фондирования: отчёт → {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("не записать отчёт", e);
        }
    }
}
