package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ПЕРЕВЕС ТЕЙКЕРОВ: ОПИСЫВАЕТ ИЛИ ПРЕДСКАЗЫВАЕТ? Команда
 * {@code --revx-flow-signal}, пункт П5.1 из док. 177.
 *
 * <h2>Вопрос владельца</h2>
 *
 * «Разве мы не знаем, растущий это час или падающий, по отношению тейкеров на
 * покупку и продажу?» Знаем — но когда час уже прошёл: их покупки цену и
 * двигают. Торговать можно только тем, что известно ДО движения, поэтому
 * меряется ровно одно: связан ли перевес за последние N минут с ходом опоры за
 * СЛЕДУЮЩИЕ N минут.
 *
 * <h2>Два сигнала, и второй может быть полезнее</h2>
 *
 * <ul>
 *   <li><b>S1 — уровень</b>: {@code (покупки − продажи) / (покупки + продажи)}
 *       за последние N минут. Ловит давление, которое УЖЕ идёт;</li>
 *   <li><b>S2 — изменение</b>: тот же перевес минус перевес за предыдущие N
 *       минут. Ловит РАЗВОРОТ потока. К моменту, когда уровень стал сильно
 *       отрицательным, падение уже исполнило наш бид, — значит опережение, если
 *       оно есть, скорее в S2.</li>
 * </ul>
 *
 * <h2>Три защиты от ложной находки, все обязательны</h2>
 *
 * 🔑 <b>Перекрытие окон.</b> Скользящий сигнал на 15-минутном окне даёт соседние
 * точки, делящие 93% данных, и наивное {@code t} завышено примерно в 3.9 раза.
 * Поэтому печатаются ДВЕ ошибки: по непересекающимся окнам (честная, но теряет
 * данные) и Ньюи–Уэста с лагом N (сохраняет данные). Расходятся — верить
 * непересекающимся.
 *
 * 🔑 <b>Тихие окна.</b> Перевес в минуте с одной сделкой равен ±1.0 ровно как в
 * минуте со ста. Окна беднее {@code minTrades} сделок выбрасываются, и порог
 * записан ДО прогона.
 *
 * 🔑 <b>Число проверок.</b> Два сигнала × три окна = шесть вариантов; порог для
 * общей ошибки 5% — не 1.96, а <b>2.64</b>. Набор фиксирован и не расширяется.
 *
 * ⚠️ Нормировка сигнала намеренно СЫРАЯ (доля, а не z-оценка): приведение к
 * единому масштабу требует среднего и разброса, а взятые по всему окну они
 * подмешивают будущее.
 */
@Component
@Lazy
public class FlowSignal {

    private static final Logger log = LoggerFactory.getLogger(FlowSignal.class);

    /** Окна сигнала, минуты. Набор ФИКСИРОВАН (см. поправку на число проверок). */
    private static final int[] WINDOWS = {1, 5, 15};

    /** Порог `t` с поправкой на шесть проверок (Бонферрони, общая ошибка 5%). */
    private static final double T_THRESHOLD = 2.64;

    public void run(String standDb, String journals, String fromIso, String toIso,
                    int minTrades, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0
                : java.time.Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE
                : java.time.Instant.parse(toIso).toEpochMilli();

        StringBuilder sb = new StringBuilder("# П5.1: предсказывает ли перевес тейкеров ход вперёд\n\n");
        sb.append("Минимум сделок в окне ").append(minTrades)
                .append(", порог `t` с поправкой на шесть проверок — **")
                .append(round(T_THRESHOLD, 2)).append("**.\n\n");
        sb.append("| пара | сигнал | окно | точек | наклон, б.п. на единицу")
                .append(" | `t` непересекающиеся | `t` Ньюи–Уэст | вердикт |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---|\n");

        boolean any = false;
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String base = kv[0].trim();
            TapeData.Fair fair = TapeData.fair(kv[1].trim(), from, to);
            if (fair.isEmpty()) {
                log.warn("{}: опоры нет", base);
                continue;
            }
            List<TapeData.Print> prints = TapeData.prints(standDb, base + "/USDC",
                    fair.ts()[0], fair.ts()[fair.ts().length - 1]);
            if (prints.size() < 500) {
                log.warn("{}: принтов мало ({})", base, prints.size());
                continue;
            }
            any = true;
            section(sb, base, prints, fair, minTrades);
        }
        if (!any) {
            log.warn("считать нечего");
            return;
        }
        sb.append("\n⚠️ Наклон — в базисных пунктах хода опоры за следующие N минут")
                .append(" на единицу сигнала (сигнал лежит в [−1, +1]).\n");
        sb.append("⚠️ Если две ошибки расходятся — верить непересекающимся:")
                .append(" Ньюи–Уэст сохраняет данные, но опирается на выбор лага.\n");

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

    private void section(StringBuilder sb, String base, List<TapeData.Print> prints,
                         TapeData.Fair fair, int minTrades) {
        // Поминутные корзины тейкеров: сколько покупок и продаж пришло в минуту.
        TreeMap<Long, int[]> byMinute = new TreeMap<>();
        for (TapeData.Print p : prints) {
            int[] c = byMinute.computeIfAbsent(p.tsMs() / 60_000L, k -> new int[2]);
            if (p.aggressor() > 0) {
                c[0]++;
            } else {
                c[1]++;
            }
        }
        if (byMinute.size() < 100) {
            return;
        }
        long first = byMinute.firstKey();
        long last = byMinute.lastKey();

        for (int n : WINDOWS) {
            List<double[]> rows = new ArrayList<>();   // {S1, S2, y, минута}
            for (long m = first + 2L * n; m + n <= last; m++) {
                double s1 = imbalance(byMinute, m - n, m, minTrades);
                double s0 = imbalance(byMinute, m - 2L * n, m - n, minTrades);
                if (Double.isNaN(s1)) {
                    continue;
                }
                double f0 = fair.at(m * 60_000L);
                double f1 = fair.at((m + n) * 60_000L);
                if (f0 <= 0 || f1 <= 0) {
                    continue;
                }
                double y = (f1 - f0) / f0 * 1e4;
                rows.add(new double[]{s1, Double.isNaN(s0) ? Double.NaN : s1 - s0, y, m});
            }
            if (rows.size() < 50) {
                continue;
            }
            for (int sig = 0; sig < 2; sig++) {
                List<double[]> use = new ArrayList<>();
                for (double[] r : rows) {
                    if (!Double.isNaN(r[sig])) {
                        use.add(new double[]{r[sig], r[2], r[3]});
                    }
                }
                if (use.size() < 50) {
                    continue;
                }
                double[] all = fit(use);
                // Непересекающиеся окна: каждая n-я точка.
                List<double[]> sparse = new ArrayList<>();
                long lastTaken = Long.MIN_VALUE;
                for (double[] r : use) {
                    if (lastTaken == Long.MIN_VALUE || (long) r[2] - lastTaken >= n) {
                        sparse.add(r);
                        lastTaken = (long) r[2];
                    }
                }
                double[] sp = sparse.size() >= 30 ? fit(sparse) : new double[]{0, 0};
                double tNw = neweyWest(use, all[0], n);
                double best = Math.min(Math.abs(sp[1]), Math.abs(tNw));
                sb.append("| ").append(base)
                        .append(" | ").append(sig == 0 ? "S1 уровень" : "S2 изменение")
                        .append(" | ").append(n)
                        .append(" | ").append(use.size()).append(" / ").append(sparse.size())
                        .append(" | ").append(round(all[0], 2))
                        .append(" | **").append(round(sp[1], 2))
                        .append("** | ").append(round(tNw, 2))
                        .append(" | ").append(best >= T_THRESHOLD ? "**берёт порог**" : "нет")
                        .append(" |\n");
            }
        }
    }

    /** Перевес тейкеров на полуинтервале минут {@code [a, b)}; NaN — окно бедное. */
    private static double imbalance(TreeMap<Long, int[]> byMinute, long a, long b, int minTrades) {
        int buys = 0;
        int sells = 0;
        for (Map.Entry<Long, int[]> e : byMinute.subMap(a, b).entrySet()) {
            buys += e.getValue()[0];
            sells += e.getValue()[1];
        }
        int total = buys + sells;
        return total < minTrades ? Double.NaN : (buys - sells) / (double) total;
    }

    /** МНК с одним предиктором: возвращает {наклон, обычное t}. */
    private static double[] fit(List<double[]> rows) {
        int n = rows.size();
        double sx = 0;
        double sy = 0;
        for (double[] r : rows) {
            sx += r[0];
            sy += r[1];
        }
        double mx = sx / n;
        double my = sy / n;
        double sxx = 0;
        double sxy = 0;
        for (double[] r : rows) {
            sxx += (r[0] - mx) * (r[0] - mx);
            sxy += (r[0] - mx) * (r[1] - my);
        }
        if (sxx <= 0) {
            return new double[]{0, 0};
        }
        double b = sxy / sxx;
        double ss = 0;
        for (double[] r : rows) {
            double u = r[1] - my - b * (r[0] - mx);
            ss += u * u;
        }
        double se = Math.sqrt(ss / (n - 2.0) / sxx);
        return new double[]{b, se == 0 ? 0 : b / se};
    }

    /**
     * Ошибка Ньюи–Уэста с лагом {@code lag} и весами Бартлетта.
     *
     * ⚠️ Нужна именно потому, что скользящие окна перекрываются: соседние точки
     * на 15-минутном окне делят 93% данных, и обычная ошибка занижена втрое с
     * лишним.
     */
    private static double neweyWest(List<double[]> rows, double slope, int lag) {
        int n = rows.size();
        double sx = 0;
        double sy = 0;
        for (double[] r : rows) {
            sx += r[0];
            sy += r[1];
        }
        double mx = sx / n;
        double my = sy / n;
        double sxx = 0;
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            double xc = rows.get(i)[0] - mx;
            sxx += xc * xc;
            g[i] = xc * (rows.get(i)[1] - my - slope * xc);
        }
        if (sxx <= 0) {
            return 0;
        }
        double s = 0;
        for (double v : g) {
            s += v * v;
        }
        s /= n;
        for (int j = 1; j <= lag; j++) {
            double gamma = 0;
            for (int i = j; i < n; i++) {
                gamma += g[i] * g[i - j];
            }
            gamma /= n;
            s += 2.0 * (1.0 - j / (lag + 1.0)) * gamma;
        }
        double var = n * Math.max(s, 0) / (sxx * sxx);
        double se = Math.sqrt(var);
        return se == 0 ? 0 : slope / se;
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
