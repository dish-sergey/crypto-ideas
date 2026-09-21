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

    /**
     * ПЕРЕВЕС ТЕЙКЕРОВ БИНАНСА из минутных свечей: {@code 2 × покупки / объём − 1}.
     *
     * 🔑 Зачем второй источник. На ленте Revolut минута с пятью сделками —
     * редкость (2.6% минут), и сигнал там держится на горстке принтов. У Бинанса
     * та же величина считается по ОБЪЁМУ десятков тысяч сделок и лежит в архиве
     * готовой колонкой {@code taker_buy_volume}. Плюс Бинанс идёт впереди нашей
     * площадки на 1–2 секунды (A54), то есть у его потока есть фора по
     * построению.
     *
     * ⚠️ Величина не та же самая: там доля ОБЪЁМА, здесь доля ЧИСЛА сделок.
     * Сравнивать наклоны между источниками нельзя, сравнивать значимость — можно.
     */
    private static TreeMap<Long, Double> binanceImbalance(String cryptoDb, String symbol,
                                                          long from, long to) {
        TreeMap<Long, Double> out = new TreeMap<>();
        String url = "jdbc:sqlite:file:" + Path.of(cryptoDb).toAbsolutePath() + "?mode=ro";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT open_time, volume, taker_buy_volume FROM candles"
                             + " WHERE symbol = ? AND interval = '1m' AND open_time >= ?"
                             + " AND open_time < ? AND volume > 0 AND taker_buy_volume IS NOT NULL")) {
            ps.setString(1, symbol);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1) / 60_000L, 2 * rs.getDouble(3) / rs.getDouble(2) - 1);
                }
            }
        } catch (Exception e) {
            log.warn("свечи {} из {}: {}", symbol, cryptoDb, e.toString());
        }
        return out;
    }

    public void run(String standDb, String journals, String fromIso, String toIso,
                    int minTrades, String out) {
        run(standDb, journals, fromIso, toIso, minTrades, out, "");
    }

    /**
     *  cryptoDb база со свечами Бинанса; пусто — считать только по ленте Revolut.
     */
    public void run(String standDb, String journals, String fromIso, String toIso,
                    int minTrades, String out, String cryptoDb) {
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
            if (cryptoDb != null && !cryptoDb.isBlank()) {
                TreeMap<Long, Double> bnb = binanceImbalance(cryptoDb, base + "USDT",
                        fair.ts()[0], fair.ts()[fair.ts().length - 1]);
                if (bnb.size() > 500) {
                    binance(sb, base, bnb, fair);
                    sweeps(sb, base, prints, fair, bnb);
                } else {
                    log.warn("{}: свечей Бинанса мало ({})", base, bnb.size());
                }
            }
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

    /**
     * Тот же счёт, но сигнал — перевес тейкеров БИНАНСА, а цель по-прежнему ход
     * НАШЕЙ опоры. Тихие окна отбрасывать не нужно: на Бинансе их не бывает.
     */
    private void binance(StringBuilder sb, String base, TreeMap<Long, Double> imb,
                         TapeData.Fair fair) {
        long first = imb.firstKey();
        long last = imb.lastKey();
        for (int n : WINDOWS) {
            List<double[]> rows = new ArrayList<>();
            for (long m = first + 2L * n; m + n <= last; m++) {
                double s1 = mean(imb, m - n, m);
                double s0 = mean(imb, m - 2L * n, m - n);
                if (Double.isNaN(s1)) {
                    continue;
                }
                double f0 = fair.at(m * 60_000L);
                double f1 = fair.at((m + n) * 60_000L);
                if (f0 <= 0 || f1 <= 0) {
                    continue;
                }
                rows.add(new double[]{s1, Double.isNaN(s0) ? Double.NaN : s1 - s0,
                        (f1 - f0) / f0 * 1e4, m});
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
                sb.append("| ").append(base).append(" **Бинанс**")
                        .append(" | ").append(sig == 0 ? "S1 уровень" : "S2 изменение")
                        .append(" | ").append(n)
                        .append(" | ").append(use.size()).append(" / ").append(sparse.size())
                        .append(" | ").append(round(all[0], 2))
                        .append(" | **").append(round(sp[1], 2))
                        .append("** | ").append(round(tNw, 2))
                        .append(" | ").append(Math.abs(sp[1]) >= T_THRESHOLD
                                ? "**берёт порог**" : "нет")
                        .append(" |\n");
            }
        }
    }

    /**
     * СВИПЫ: несут ли они что-то СВЕРХ перевеса (док. 179 часть IV).
     *
     * Три вопроса по порядку:
     * <ol>
     *   <li>связаны ли размер перевеса и размер свипов — вопрос владельца
     *       буквально;</li>
     *   <li>предсказывает ли свип ход сам по себе — разбором ПО СОБЫТИЯМ, а не
     *       регрессией по минутам: свип редок, и в регрессии по всем минутам он
     *       утонет в пустых;</li>
     *   <li>🔑 решающий: остаётся ли свип значимым, когда перевес уже учтён.</li>
     * </ol>
     *
     * ⚠️ Знак допускается ЛЮБОЙ: продолжение (свип осведомлён) и возврат (свип
     * переплатил за немедленность) — разные гипотезы, и выбирать между ними
     * должен замер, а не ожидание.
     *
     * ⚠️ Плацебо обязателен — после урока 176: на трендовом окне «продолжение»
     * получается у любого события, направленного по тренду.
     */
    private void sweeps(StringBuilder sb, String base, List<TapeData.Print> prints,
                        TapeData.Fair fair, TreeMap<Long, Double> bnb) {
        // Свип — цепочка принтов одной стороны в пределах 100 мс.
        // 🔑 У свипа запоминается и НАЧАЛО, и КОНЕЦ. Путь считается от опоры
        // ПОСЛЕ последнего принта: иначе в «ход после свипа» попадает его
        // собственный удар — те уровни книги, которые он съел, — а его бот
        // избежать не может по определению (док. 181 часть II).
        List<double[]> bursts = new ArrayList<>();   // {начало, знак, номинал, конец}
        long start = 0;
        int side = 0;
        double notional = 0;
        long prevTs = Long.MIN_VALUE;
        for (TapeData.Print p : prints) {
            boolean same = p.aggressor() == side && p.tsMs() - prevTs <= 100;
            if (!same) {
                if (notional > 0) {
                    bursts.add(new double[]{start, side, notional, prevTs});
                }
                start = p.tsMs();
                side = p.aggressor();
                notional = 0;
            }
            notional += p.price() * p.qty();
            prevTs = p.tsMs();
        }
        if (notional > 0) {
            bursts.add(new double[]{start, side, notional, prevTs});
        }
        if (bursts.size() < 200) {
            return;
        }
        double[] sizes = bursts.stream().mapToDouble(b -> b[2]).sorted().toArray();
        double big = sizes[(int) (sizes.length * 0.9)];

        sb.append("\n### Свипы ").append(base)
                .append(": всего ").append(bursts.size())
                .append(", крупных (верхние 10% по номиналу, от $")
                .append(round(big, 0)).append(") — ")
                .append(bursts.stream().filter(b -> b[2] >= big).count()).append("\n\n");

        // Вопрос 2 и П5.3: разбор по событиям, с плацебо, от КОНЦА свипа.
        sb.append("| горизонт от конца свипа | событий | путь по свипу, б.п. | плацебо")
                .append(" | **чистый** | **`t`** |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        long[] hs = {1_000, 5_000, 15_000, 30_000, 60_000, 300_000, 900_000};
        String[] names = {"1 с", "5 с", "15 с", "30 с", "60 с", "5 мин", "15 мин"};
        for (int h = 0; h < hs.length; h++) {
            List<Double> net = new ArrayList<>();
            double raw = 0;
            double plac = 0;
            for (double[] b : bursts) {
                if (b[2] < big) {
                    continue;
                }
                long t = (long) b[3];
                double f0 = fair.after(t);
                double f1 = fair.after(t + hs[h]);
                double p0 = fair.at(t - PLACEBO_LAG_MS);
                double p1 = fair.after(t - PLACEBO_LAG_MS + hs[h]);
                if (f0 <= 0 || f1 <= 0 || p0 <= 0 || p1 <= 0) {
                    continue;
                }
                double r = b[1] * (f1 - f0) / f0 * 1e4;
                double pl = b[1] * (p1 - p0) / p0 * 1e4;
                raw += r;
                plac += pl;
                net.add(r - pl);
            }
            if (net.size() < 30) {
                continue;
            }
            double[] arr = net.stream().mapToDouble(Double::doubleValue).toArray();
            double[] st = stat(arr);
            sb.append("| ").append(names[h]).append(" | ").append(net.size())
                    .append(" | ").append(round(raw / net.size(), 2))
                    .append(" | ").append(round(plac / net.size(), 2))
                    .append(" | **").append(round(st[0], 2))
                    .append("** | **").append(round(st[1], 2)).append("** |\n");
        }

        // Вопросы 1 и 3: пятиминутные окна, свип рядом с перевесом.
        TreeMap<Long, Double> sweepByMinute = new TreeMap<>();
        for (double[] b : bursts) {
            if (b[2] >= big) {
                sweepByMinute.merge((long) b[0] / 60_000L, b[1] * b[2], Double::sum);
            }
        }
        List<double[]> rows = new ArrayList<>();   // {перевес, свип, ход, минута}
        for (long m : bnb.keySet()) {
            double imb = mean(bnb, m - 5, m);
            if (Double.isNaN(imb)) {
                continue;
            }
            double sw = 0;
            for (long k = m - 5; k < m; k++) {
                sw += sweepByMinute.getOrDefault(k, 0.0);
            }
            double f0 = fair.at(m * 60_000L);
            double f1 = fair.at((m + 5) * 60_000L);
            if (f0 <= 0 || f1 <= 0) {
                continue;
            }
            // Номинал приводится к тысячам долларов, чтобы наклон читался.
            rows.add(new double[]{imb, sw / 1000.0, (f1 - f0) / f0 * 1e4, m});
        }
        if (rows.size() < 200) {
            return;
        }
        sb.append("\nСвязь перевеса Бинанса и размера свипов (вопрос 1): **r = ")
                .append(round(correlation(rows), 3)).append("**");
        sb.append(", то есть ").append(Math.abs(correlation(rows)) > 0.5
                        ? "это во многом одно и то же" : "это РАЗНАЯ информация")
                .append(".\n\n");

        double[][] joint = jointFit(rows, 5);
        sb.append("| в одной регрессии (вопрос 3) | наклон | **`t` непересек.** |\n|---|---:|---:|\n");
        sb.append("| перевес Бинанса | ").append(round(joint[0][0], 2))
                .append(" | **").append(round(joint[0][1], 2)).append("** |\n");
        sb.append("| размер свипов, на $1000 | ").append(round(joint[1][0], 4))
                .append(" | **").append(round(joint[1][1], 2)).append("** |\n");
    }

    /** Корреляция первых двух колонок. */
    private static double correlation(List<double[]> rows) {
        int n = rows.size();
        double sx = 0;
        double sy = 0;
        for (double[] r : rows) {
            sx += r[0];
            sy += r[1];
        }
        double mx = sx / n;
        double my = sy / n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (double[] r : rows) {
            sxy += (r[0] - mx) * (r[1] - my);
            sxx += (r[0] - mx) * (r[0] - mx);
            syy += (r[1] - my) * (r[1] - my);
        }
        return sxx <= 0 || syy <= 0 ? 0 : sxy / Math.sqrt(sxx * syy);
    }

    /**
     * Регрессия хода на ДВА предиктора сразу, на непересекающихся точках.
     *
     * 🔑 Это и есть решающий вопрос про свипы: значим ли свип, когда перевес уже
     * в модели. Если нет — он был частью перевеса, а не отдельной информацией.
     */
    private static double[][] jointFit(List<double[]> rows, int step) {
        List<double[]> use = new ArrayList<>();
        long lastTaken = Long.MIN_VALUE;
        for (double[] r : rows) {
            if (lastTaken == Long.MIN_VALUE || (long) r[3] - lastTaken >= step) {
                use.add(r);
                lastTaken = (long) r[3];
            }
        }
        int n = use.size();
        double[][] xtx = new double[3][3];
        double[] xty = new double[3];
        for (double[] r : use) {
            double[] x = {1, r[0], r[1]};
            for (int i = 0; i < 3; i++) {
                xty[i] += x[i] * r[2];
                for (int j = 0; j < 3; j++) {
                    xtx[i][j] += x[i] * x[j];
                }
            }
        }
        double[][] inv = invert3(xtx);
        if (inv == null) {
            return new double[][]{{0, 0}, {0, 0}};
        }
        double[] b = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                b[i] += inv[i][k] * xty[k];
            }
        }
        double ss = 0;
        for (double[] r : use) {
            double u = r[2] - b[0] - b[1] * r[0] - b[2] * r[1];
            ss += u * u;
        }
        double s2 = ss / (n - 3.0);
        double se1 = Math.sqrt(Math.max(s2 * inv[1][1], 0));
        double se2 = Math.sqrt(Math.max(s2 * inv[2][2], 0));
        return new double[][]{
                {b[1], se1 == 0 ? 0 : b[1] / se1},
                {b[2], se2 == 0 ? 0 : b[2] / se2}};
    }

    /** Обращение 3×3; при вырождении {@code null}. */
    private static double[][] invert3(double[][] a) {
        double[][] m = new double[3][6];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3 + i] = 1;
        }
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int i = c; i < 3; i++) {
                if (Math.abs(m[i][c]) > Math.abs(m[piv][c])) {
                    piv = i;
                }
            }
            if (Math.abs(m[piv][c]) < 1e-15) {
                return null;
            }
            double[] t = m[c];
            m[c] = m[piv];
            m[piv] = t;
            double d = m[c][c];
            for (int j = 0; j < 6; j++) {
                m[c][j] /= d;
            }
            for (int i = 0; i < 3; i++) {
                if (i != c) {
                    double f = m[i][c];
                    for (int j = 0; j < 6; j++) {
                        m[i][j] -= f * m[c][j];
                    }
                }
            }
        }
        double[][] inv = new double[3][3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 3, inv[i], 0, 3);
        }
        return inv;
    }

    /** Среднее и {@code t} по ряду. */
    private static double[] stat(double[] x) {
        int n = x.length;
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= n;
        double ss = 0;
        for (double v : x) {
            ss += (v - mean) * (v - mean);
        }
        double se = n < 2 ? 0 : Math.sqrt(ss / (n - 1.0) / n);
        return new double[]{mean, se == 0 ? 0 : mean / se};
    }

    /** Сдвиг плацебо: связь с событием рвём, снос рынка оставляем. */
    private static final long PLACEBO_LAG_MS = 3 * 3_600_000L;

    /** Среднее поминутного перевеса на полуинтервале {@code [a, b)}. */
    private static double mean(TreeMap<Long, Double> imb, long a, long b) {
        double s = 0;
        int n = 0;
        for (Double v : imb.subMap(a, b).values()) {
            s += v;
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
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
