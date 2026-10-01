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

    /** Сдвиг плацебо-контроля для П2.10: связь со сделкой рвём, снос оставляем. */
    private static final long PLACEBO_LAG_MS = 3 * 3_600_000L;

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
        run(journals, bandLots, feeBp, stepUsd, fromIso, toIso, out, null);
    }

    /**
     *  hoursOut куда выгрузить ЧАСОВЫЕ строки (бот; пара; час; итог без беты без
     *        хеджа; он же с хеджем; опора в начале часа; опора в конце). Нужен
     *        пункту П2.4 из 165: регрессия ядра гоняется на ОБЪЕДИНЁННЫХ окнах, а
     *        один запуск прибора считает одно окно.
     */
    public void run(String journals, double bandLots, double feeBp, double stepUsd,
                    String fromIso, String toIso, String out, String hoursOut) {
        run(journals, bandLots, feeBp, stepUsd, fromIso, toIso, out, hoursOut, 0, false);
    }

    /**
     *  periodMin перевешивать по ЧАСАМ, а не по полосе (П2.8): ноль — по полосе.
     *  perpUsd   считать ногу перпа в сырых USD-марках (П2.7).
     */
    public void run(String journals, double bandLots, double feeBp, double stepUsd,
                    String fromIso, String toIso, String out, String hoursOut,
                    double periodMin, boolean perpUsd) {
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
        TreeMap<Long, Double> hourPlain = new TreeMap<>();
        TreeMap<Long, Double> hourHedged = new TreeMap<>();
        // Ход опоры за час, сложенный по ботам: {Σ|Δp|, число ботов} — веса П2.7.
        TreeMap<Long, double[]> hourMove = new TreeMap<>();
        // П2.9: по каждому боту-часу {|Δp| %, переноска спота, нога перпа}.
        List<double[]> cover = new ArrayList<>();
        StringBuilder rows = new StringBuilder(
                "бот;пара;час;без_хеджа;с_хеджем;опора0;опора1;захват;сделок\n");
        List<Object[]> legs = new ArrayList<>();   // {метка, пара, Legs} — для П2.6
        StringBuilder rateNoise = new StringBuilder();
        List<Object[]> hedgeFills = new ArrayList<>();   // {метка, марки, сделки} — П2.10

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
            // Шаг в ДОЛЯХ ЛОТА (30.09.2026): одна мерка на все пары, чтобы отличать
            // «идея не работает» от «лот мельче шага контракта» (BTC: шаг 0.0001 ≈ $11).
            double stepLots = Double.parseDouble(System.getProperty("revx.hedge.step-lots", "0"));
            if (stepLots > 0 && lot > 0) {
                step = lot * stepLots;
            }
            // Сырые USD-марки — для П2.6: нога Kraken живёт в USD, и разница с
            // USDC-пересчётом и есть базис, которым возражал док. 150.
            NavigableMap<Long, Double> markUsd = marks.marks(PerpMarkSource.perpFor(base),
                    s.ts[0] - 120_000, s.ts[s.ts.length - 1] + 120_000);
            Result plain = evaluate(s, null, 0, 0, 0);
            Result hedged = evaluate(s, mark, markUsd, bandLots * lot, step, feeBp / 1e4,
                    (long) (periodMin * 60_000), perpUsd);
            legs.add(new Object[]{b.id(), base, hedged.legs()});
            hedgeFills.add(new Object[]{b.id(), perpUsd && !markUsd.isEmpty() ? markUsd : mark,
                    hedged.hedgeFills()});
            rateNoise.append(rateStats(b.id(), mark, markUsd));
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
            // Отрезок — ЧАС, и складываются в нём все боты: внутри часа они
            // несут один и тот же рынок, и считать их независимыми нельзя.
            plain.exBetaHour().forEach((h, v) -> hourPlain.merge(h, v, Double::sum));
            hedged.exBetaHour().forEach((h, v) -> hourHedged.merge(h, v, Double::sum));
            for (Map.Entry<Long, Double> e : plain.exBetaHour().entrySet()) {
                long h = e.getKey();
                double[] cur = plain.hourly().get(h);
                double[] prev = plain.hourly().get(h - 1);
                Double hv = hedged.exBetaHour().get(h);
                if (cur == null || prev == null || hv == null) {
                    continue;
                }
                double[] mv = hourMove.computeIfAbsent(h, k -> new double[2]);
                mv[0] += Math.abs(100.0 * (cur[1] - prev[1]) / prev[1]);
                mv[1]++;
                // П2.9: приращения переносок за час — из накопленных на границах.
                double[] c0 = hedged.carryHour().get(h - 1);
                double[] c1 = hedged.carryHour().get(h);
                if (c0 != null && c1 != null) {
                    cover.add(new double[]{Math.abs(100.0 * (cur[1] - prev[1]) / prev[1]),
                            c1[0] - c0[0], c1[1] - c0[1]});
                }
                double[] cap = plain.capHour().getOrDefault(h, new double[2]);
                rows.append(b.id()).append(';').append(base).append(';').append(h)
                        .append(';').append(round(e.getValue(), 6))
                        .append(';').append(round(hv, 6))
                        .append(';').append(round(prev[1], 6))
                        .append(';').append(round(cur[1], 6))
                        .append(';').append(round(cap[0], 6))
                        .append(';').append((int) cap[1]).append('\n');
            }
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
            // ⚠️ Та же величина, но с ногой перпа в ЕГО валюте: перп рассчитывается
            // в USD, а поминутный пересчёт в USDC подмешивает дрожание нашей оценки
            // курса (см. раздел про курс ниже).
            double usdShift = 0;
            for (Object[] row : legs) {
                Legs l = (Legs) row[2];
                usdShift += l.krakenUsd() - l.krakenUsdc();
            }
            sb.append("\n⚠️ Тот же итог без беты, но с ногой перпа в USD (без поминутного")
                    .append(" пересчёта в USDC): **").append(money(exBetaHedged + usdShift))
                    .append("** против ").append(money(exBetaHedged))
                    .append(" — разница ").append(money(usdShift)).append(".\n");
            sb.append(pairedSection(hourPlain, hourHedged, hourMove));
            sb.append(legsSection(legs));
            sb.append(coverageSection(cover));
            sb.append(hedgeMarkoutSection(hedgeFills));
            sb.append("\n### Курс USDC/USD, которым пересчитана нога\n\n")
                    .append("| бот | средний курс | СКО поминутного изменения, б.п. | минут |\n")
                    .append("|---|---:|---:|---:|\n").append(rateNoise)
                    .append("\n⚠️ Колонка «курс USDC/USD» в таблице выше — это НЕ базис,")
                    .append(" а в основном ШУМ нашей оценки курса: он входит в переоценку")
                    .append(" ноги каждую минуту (CLAUDE.md: медиана implied гуляет 7 б.п.")
                    .append(" за 43 секунды, пока прямая книга стоит).\n");
        }

        if (hoursOut != null && !hoursOut.isBlank()) {
            try {
                Path p = Path.of(hoursOut);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, rows.toString(), StandardCharsets.UTF_8);
                log.info("часовые строки: {}", p.toAbsolutePath());
            } catch (IOException e) {
                log.warn("не записать {}: {}", hoursOut, e.toString());
            }
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

    /**
     * Итог по одной траектории плюс ЧАСОВОЙ ряд «итога без беты».
     *
     * 🔑 Ряд нужен для парного счёта отрезками — того самого, который протокол
     * 160 (часть V) требует заранее: «итог без беты больше нуля на двух окнах
     * противоположного направления, с {@code t} ≥ 2 по парному счёту отрезками».
     * Одна сумма за окно этого вопроса не решает: у неё нет ошибки.
     *
     * Ключ — номер часа (эпоха / 3 600 000), значение — приращение итога без
     * беты за этот час. Сумма ряда равна {@link Split#exBeta()} с точностью до
     * разрывов в тиках (часы без предшественника в ряд не входят).
     */
    private record Result(Split split, int trades, TreeMap<Long, Double> exBetaHour,
                          TreeMap<Long, double[]> hourly, TreeMap<Long, double[]> capHour,
                          Legs legs, TreeMap<Long, double[]> carryHour,
                          List<double[]> hedgeFills) {
    }

    /**
     * СЫРОЙ РЕЗУЛЬТАТ ПО НОГАМ (П2.6 из 165 v2) — то, что видят два счёта, а не
     * мерка «без беты».
     *
     * 🔑 Зачем отдельно от всего остального: «итог без беты» — конструкция для
     * сравнения вариантов, в ней вычтена бета среднего запаса. Налоговая её не
     * знает. На растущем окне мерка показывала по хеджу плюс, хотя сырой шорт был
     * в убытке: бету просто вычли.
     *
     * @param revolut   результат спотового счёта в USDC (капитал по опоре).
     *                  ⚠️ От хеджа не зависит ВООБЩЕ: бот о перпе не знает;
     * @param krakenUsdc нога перпа, пересчитанная в USDC (пошлина уже вычтена);
     * @param krakenUsd  она же по СЫРЫМ USD-маркам: разница с предыдущей — это
     *                   курс USDC/USD, то есть базис, которым 150 и возражал;
     * @param mirror     зеркальная часть ноги перпа: минус изменение стоимости
     *                   спотового запаса за те же интервалы. Если хедж полный,
     *                   вся нога состоит из неё;
     * @param fees       пошлина перевешиваний.
     */
    private record Legs(double revolut, double krakenUsdc, double krakenUsd,
                        double mirror, double fees) {
        /** Остаток ноги перпа сверх зеркала: полоса, базис и округление. */
        double residual() {
            return krakenUsdc + fees - mirror;
        }
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
        return evaluate(s, mark, null, band, step, fee);
    }

    private Result evaluate(Series s, NavigableMap<Long, Double> mark,
                            NavigableMap<Long, Double> markUsd,
                            double band, double step, double fee) {
        return evaluate(s, mark, markUsd, band, step, fee, 0, false);
    }

    private Result evaluate(Series s, NavigableMap<Long, Double> mark,
                            NavigableMap<Long, Double> markUsd,
                            double band, double step, double fee,
                            long periodMs, boolean perpUsd) {
        // ⚠️ НЕ Long.MIN_VALUE: разность t − lastHedge переполняется, и первое
        // перевешивание не наступает никогда. Ноль означает «пора сразу».
        long lastHedge = 0;
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
        double prevMarkUsd = 0;
        double hedgeCarryUsd = 0;
        double mirror = 0;
        boolean first = true;
        // Часовой ряд: капитал на границе каждого часа и ход опоры за час.
        TreeMap<Long, double[]> hourly = new TreeMap<>();   // час -> {капитал, опора}
        // 🔑 Отдельно — ЗАХВАТ по часам и число исполнений: без них нельзя
        // проверить, законна ли экстраполяция регрессии П2.4 к нулевому ходу.
        // Захват идёт со сделками, а сделок в тихий час мало — если он падает
        // вместе с |Δp|, свободный член регрессии меряет несуществующий режим.
        TreeMap<Long, double[]> capHour = new TreeMap<>();  // час -> {захват, сделок}
        // Накопленные переноски на конец каждого часа: {спот, перп}.
        TreeMap<Long, double[]> carryHour = new TreeMap<>();
        // П2.10: собственные исполнения хеджа {время, сторона, марка}.
        List<double[]> hedgeFills = new ArrayList<>();
        // 🔑 ХЕДЖ НА РЕЗКИЙ НАБОР (30.09.2026, идея владельца): шорт открывается,
        // когда за BURST_MIN минут куплено не меньше BURST_LOTS лотов, держится
        // равным запасу и закрывается, когда запас опустился до одного лота.
        long burstMs = (long) (Double.parseDouble(System.getProperty("revx.hedge.burst-min", "0"))
                * 60_000);
        double burstLots = Double.parseDouble(System.getProperty("revx.hedge.burst-lots", "2"));
        double lot = medianBuy(s);
        java.util.ArrayDeque<double[]> recentBuys = new java.util.ArrayDeque<>();
        boolean burstOn = false;
        int burstStarts = 0;

        for (int i = 0; i < s.ts.length; i++) {
            long t = s.ts[i];
            double f = s.fair[i];
            double inv = s.inv[i];
            // Сделки, случившиеся до этого тика: марка — ТЕКУЩИЙ тик.
            while (fi < s.fillTs.length && s.fillTs[fi] <= t) {
                cash -= s.fillDq[fi] * s.fillPx[fi];
                double got = (f - s.fillPx[fi]) * s.fillDq[fi];
                capture += got;
                double[] ch = capHour.computeIfAbsent(t / 3_600_000L, k -> new double[2]);
                ch[0] += got;
                ch[1]++;
                turnover += Math.abs(s.fillDq[fi]) * s.fillPx[fi];
                if (burstMs > 0 && s.fillDq[fi] > 0) {
                    recentBuys.addLast(new double[]{s.fillTs[fi], s.fillDq[fi]});
                }
                fi++;
            }
            if (burstMs > 0) {
                while (!recentBuys.isEmpty() && recentBuys.peekFirst()[0] < t - burstMs) {
                    recentBuys.pollFirst();
                }
            }
            double m = 0;
            if (mark != null) {
                Map.Entry<Long, Double> e = mark.floorEntry(t);
                m = e == null ? 0 : e.getValue();
            }
            double mu = 0;
            if (markUsd != null) {
                Map.Entry<Long, Double> e = markUsd.floorEntry(t);
                mu = e == null ? 0 : e.getValue();
            }
            if (!first) {
                spotCarry += prevInv * (f - prevFair);
                if (mark != null && prevMark > 0 && m > 0) {
                    // 🔑 П2.7: нога перпа может считаться в СЫРЫХ USD-марках.
                    // Поминутный пересчёт в USDC подмешивает дрожание нашей
                    // оценки курса (3.3–5.8 б.п. в минуту, задача A73), и этот
                    // шум сидит в парной разности целиком: плечо «без хеджа»
                    // курса не требует вовсе.
                    hedgeCarry += perpUsd && markUsd != null && prevMarkUsd > 0 && mu > 0
                            ? prevPerp * (mu - prevMarkUsd)
                            : prevPerp * (m - prevMark);
                    // Зеркало: то же движение, но по спотовой опоре и на спотовом
                    // запасе. Разница с ногой перпа — полоса, базис, округление.
                    mirror -= prevInv * (f - prevFair);
                }
                if (markUsd != null && prevMarkUsd > 0 && mu > 0) {
                    hedgeCarryUsd += prevPerp * (mu - prevMarkUsd);
                }
            }
            // Перевешивание ПОСЛЕ переоценки: решение принимается по состоянию,
            // которое уже отмечено в капитале.
            if (burstMs > 0 && mark != null && m > 0 && lot > 0) {
                double bought = recentBuys.stream().mapToDouble(x -> x[1]).sum();
                if (!burstOn && bought >= burstLots * lot * 0.99) {
                    burstOn = true;
                    burstStarts++;
                }
                if (burstOn && inv <= lot * 1.01) {
                    burstOn = false;
                }
                double want = burstOn ? -inv : 0;
                double delta = want - perp;
                double rounded = Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step;
                // закрытие шорта — до нуля целиком, чтобы остаток шага не висел
                if (!burstOn && perp != 0) {
                    rounded = -perp;
                }
                if (Math.abs(rounded) >= step || (!burstOn && rounded != 0)) {
                    perp += rounded;
                    fees += Math.abs(rounded) * m * fee;
                    trades++;
                    hedgeFills.add(new double[]{t, Math.signum(rounded), m});
                }
            } else if (mark != null && m > 0 && (band > 0 || periodMs > 0)) {
                double net = inv + perp;
                // 🔑 Два правила перевешивания, и второе нужно для П2.8: полоса
                // отвечает на вопрос «насколько далеко пускаем», период — «как
                // часто выравниваем». Потолок конструкции задаёт ВТОРОЕ: отбор
                // растёт с горизонтом удержания, а не с величиной отклонения.
                boolean due = periodMs > 0 && t - lastHedge >= periodMs && Math.abs(net) > 0;
                if (due) {
                    lastHedge = t;
                }
                if (due || (band > 0 && Math.abs(net) > band)) {
                    double want = -inv;
                    double delta = want - perp;
                    // ⚠️ ВНИЗ по модулю: округление к ближайшему на грубом шаге
                    // переворачивает позицию (CLAUDE.md, измерено).
                    double rounded = Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step;
                    if (Math.abs(rounded) >= step) {
                        perp += rounded;
                        fees += Math.abs(rounded) * m * fee;
                        trades++;
                        // П2.10: перевешивание — это тоже ИСПОЛНЕНИЕ, и оно тоже
                        // отбирается. Запоминаем время, сторону и марку, чтобы
                        // потом посмотреть, куда ушла цена перпа после нас.
                        hedgeFills.add(new double[]{t, Math.signum(rounded), m});
                    }
                }
            }
            first = false;
            prevFair = f;
            prevInv = inv;
            prevPerp = perp;
            prevMark = m;
            prevMarkUsd = mu;
            area += inv;
            areaNet += inv + perp;
            double equity = cash + inv * f + (mark != null ? hedgeCarry - fees : 0);
            hourly.put(t / 3_600_000L, new double[]{equity, f});
            // П2.9: переноска спота и ноги перпа ПО ЧАСАМ — чтобы увидеть, где
            // именно хедж покрывает, а где молчит.
            double[] cr = carryHour.computeIfAbsent(t / 3_600_000L, k -> new double[2]);
            cr[0] = spotCarry;
            cr[1] = hedgeCarry;
        }

        int n = s.ts.length;
        double p0 = s.fair[0];
        double p1 = s.fair[n - 1];
        double meanPos = (mark != null ? areaNet : area) / n;
        double beta = meanPos * (p1 - p0);
        double carry = spotCarry + (mark != null ? hedgeCarry : 0);
        double[] slopes = hourlySlopes(hourly);
        return new Result(new Split(capture, beta, carry - beta, fees,
                slopes[0], slopes[1], (int) slopes[2]), trades,
                exBetaByHour(hourly, meanPos), hourly, capHour,
                new Legs(capture + spotCarry, hedgeCarry - fees,
                        hedgeCarryUsd - fees, mirror, fees), carryHour, hedgeFills);
    }

    /** Типичный лот бота — медиана покупок. */
    private static double medianBuy(Series s) {
        double[] buys = java.util.Arrays.stream(s.fillDq).filter(x -> x > 0).sorted().toArray();
        return buys.length == 0 ? 0 : buys[buys.length / 2];
    }

    /**
     * Курс, которым пересчитана нога перпа: он восстанавливается делением
     * USDC-марки на USD-марку.
     *
     * 🔑 Зачем печатать. Переоценка ноги идёт КАЖДУЮ минуту, поэтому в неё
     * попадает не отклонение курса от единицы, а его поминутное ДРОЖАНИЕ,
     * умноженное на номинал и накопленное за окно. Если СКО изменения курса
     * велико, разница «нога в USD против ноги в USDC» — это шум оценки, а не
     * базис, и вычитать его как базис нельзя.
     */
    private String rateStats(String bot, NavigableMap<Long, Double> quote,
                             NavigableMap<Long, Double> usd) {
        List<Double> rate = new ArrayList<>();
        for (Map.Entry<Long, Double> e : usd.entrySet()) {
            Double q = quote.get(e.getKey());
            if (q != null && e.getValue() > 0) {
                rate.add(q / e.getValue());
            }
        }
        if (rate.size() < 10) {
            return "";
        }
        double mean = 0;
        for (double v : rate) {
            mean += v;
        }
        mean /= rate.size();
        double ss = 0;
        for (int i = 1; i < rate.size(); i++) {
            double d = (rate.get(i) - rate.get(i - 1)) * 1e4;
            ss += d * d;
        }
        double sd = Math.sqrt(ss / (rate.size() - 1));
        return "| " + bot + " | " + round(mean, 6) + " | " + round(sd, 2)
                + " | " + rate.size() + " |\n";
    }

    /**
     * П2.6 — СЫРОЙ РЕЗУЛЬТАТ ПО НОГАМ, без единого вычитания.
     *
     * 🔑 Печатается ровно потому, что все остальные числа прибора — это мерка
     * «без беты», а налоговая её не знает. Здесь два счёта как они есть: USDC на
     * Revolut и USD на Kraken.
     *
     * Проверка, которую надо смотреть первой: сумма ног обязана совпасть с
     * колонкой «итог с хеджем» из верхней таблицы — это тождество, а не
     * совпадение. Если не совпало, сломан прибор.
     */
    private String legsSection(List<Object[]> legs) {
        if (legs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## П2.6. Сырой результат по ногам"
                + " (то, что видят счета, а не мерка)\n\n");
        sb.append("| бот | пара | Revolut, USDC | Kraken в USDC | Kraken в USD | курс USDC/USD")
                .append(" | из ноги: зеркало | остаток | пошлина | **сумма ног** |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        double rev = 0;
        double kr = 0;
        double krUsd = 0;
        double mir = 0;
        double fee = 0;
        for (Object[] row : legs) {
            Legs l = (Legs) row[2];
            sb.append("| ").append(row[0]).append(" | ").append(row[1])
                    .append(" | ").append(money(l.revolut()))
                    .append(" | ").append(money(l.krakenUsdc()))
                    .append(" | ").append(money(l.krakenUsd()))
                    .append(" | ").append(money(l.krakenUsd() - l.krakenUsdc()))
                    .append(" | ").append(money(l.mirror()))
                    .append(" | ").append(money(l.residual()))
                    .append(" | ").append(money(l.fees()))
                    .append(" | **").append(money(l.revolut() + l.krakenUsdc())).append("** |\n");
            rev += l.revolut();
            kr += l.krakenUsdc();
            krUsd += l.krakenUsd();
            mir += l.mirror();
            fee += l.fees();
        }
        sb.append("| **всего** | | **").append(money(rev)).append("** | **").append(money(kr))
                .append("** | ").append(money(krUsd))
                .append(" | ").append(money(krUsd - kr))
                .append(" | ").append(money(mir))
                .append(" | ").append(money(kr + fee - mir))
                .append(" | ").append(money(fee))
                .append(" | **").append(money(rev + kr)).append("** |\n");
        sb.append("\nНалог 19% с положительной ноги Kraken: ")
                .append(money(kr > 0 ? -0.19 * kr : 0))
                .append(", на руки ").append(money(rev + kr - (kr > 0 ? 0.19 * kr : 0)))
                .append(".\n");
        sb.append("⚠️ Нога Revolut от хеджа НЕ зависит — бот о перпе не знает;")
                .append(" весь эффект хеджа сидит в ноге Kraken.\n");
        return sb.toString();
    }

    /**
     * ПАРНЫЙ СЧЁТ ОТРЕЗКАМИ — критерий части V протокола 160.
     *
     * Считаются три величины, и путать их нельзя:
     * <ul>
     *   <li><b>разность</b> (с хеджем − без хеджа) на ОДНИХ И ТЕХ ЖЕ отрезках —
     *       работает ли механизм. Рынок в паре сокращается, поэтому ошибка здесь
     *       мала;</li>
     *   <li><b>уровень с хеджем</b> против нуля — то самое «итог без беты > 0»,
     *       которым протокол решает. Тут рынок не сокращается, и ошибка большая;</li>
     *   <li><b>уровень без хеджа</b> против нуля — контроль.</li>
     * </ul>
     *
     * ⚠️ Отрезки укрупняются до 6 и 24 часов намеренно: соседние часы одного
     * бота не независимы (позиция переходит через границу часа), и на часовом
     * отрезке {@code t} завышен. Если знак держится и на суточных блоках — он не
     * артефакт нарезки.
     */
    private String pairedSection(TreeMap<Long, Double> plain, TreeMap<Long, Double> hedged,
                                 TreeMap<Long, double[]> move) {
        List<Long> hours = new ArrayList<>(hedged.keySet());
        hours.retainAll(plain.keySet());
        if (hours.size() < 10) {
            return "\n⚠️ отрезков меньше десяти — парный счёт не считается.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Парный счёт отрезками (критерий части V протокола)\n\n");
        sb.append("| отрезок | отрезков | разность с хеджем − без, $/отрезок | `t` разности")
                .append(" | уровень С хеджем, $/отрезок | `t` уровня | уровень БЕЗ хеджа | `t` |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int block : new int[]{1, 6, 24}) {
            TreeMap<Long, double[]> agg = new TreeMap<>();
            for (long h : hours) {
                double[] v = agg.computeIfAbsent(Math.floorDiv(h, block), k -> new double[2]);
                v[0] += plain.get(h);
                v[1] += hedged.get(h);
            }
            if (agg.size() < 5) {
                continue;
            }
            double[] diff = new double[agg.size()];
            double[] lvlH = new double[agg.size()];
            double[] lvlP = new double[agg.size()];
            int i = 0;
            for (double[] v : agg.values()) {
                diff[i] = v[1] - v[0];
                lvlH[i] = v[1];
                lvlP[i] = v[0];
                i++;
            }
            double[] d = stat(diff);
            double[] h = stat(lvlH);
            double[] p = stat(lvlP);
            sb.append("| ").append(block == 1 ? "час" : block + " ч")
                    .append(" | ").append(agg.size())
                    .append(" | ").append(money(d[0])).append(" | **").append(round(d[1], 2))
                    .append("** | ").append(money(h[0])).append(" | **").append(round(h[1], 2))
                    .append("** | ").append(money(p[0])).append(" | ").append(round(p[1], 2))
                    .append(" |\n");
        }
        sb.append("\nПорог протокола: `t` ≥ 2 при положительном уровне с хеджем.\n");
        sb.append(weighted(hours, plain, hedged, move));
        return sb.toString();
    }

    /**
     * ТО ЖЕ, НО С ВЕСАМИ ПО ОБРАТНОЙ ДИСПЕРСИИ (П2.7 из док. 169).
     *
     * 🔑 Разброс часовой разности растёт вместе с ходом часа, а ход по квинтилям
     * различается в 26 раз (166). При равных весах бурные часы дают почти всю
     * дисперсию, и оценка теряет мощность ни за что. Вес {@code 1/|Δp|²} это
     * лечит.
     *
     * ⚠️ И почему это НЕ подгонка: вес считается из хода часа, который ОДИНАКОВ в
     * обоих плечах пары. Он не знает, включён хедж или нет, поэтому не может
     * сдвинуть саму оценку — только её ошибку.
     *
     * ⚠️ Но это приём ИЗМЕРЕНИЯ, а не правило торговли: ход часа известен только
     * после часа.
     */
    private String weighted(List<Long> hours, TreeMap<Long, Double> plain,
                            TreeMap<Long, Double> hedged, TreeMap<Long, double[]> move) {
        List<double[]> rows = new ArrayList<>();   // {разность, уровень, |Δp|}
        List<Double> moves = new ArrayList<>();
        for (long h : hours) {
            double[] mv = move.get(h);
            if (mv == null || mv[1] <= 0) {
                continue;
            }
            double abs = Math.abs(mv[0] / mv[1]);
            rows.add(new double[]{hedged.get(h) - plain.get(h), hedged.get(h), abs});
            moves.add(abs);
        }
        if (rows.size() < 10) {
            return "";
        }
        // ⚠️ Пол на веса: без него один сверхтихий час получает вес в тысячи и
        // становится единственным наблюдением.
        List<Double> sorted = new ArrayList<>(moves);
        sorted.sort(Double::compareTo);
        double floor = sorted.get(Math.max(0, (int) (0.1 * sorted.size())));
        StringBuilder sb = new StringBuilder("\n### Те же часы, но с весами `1/|Δp|²` (П2.7)\n\n");
        sb.append("| величина | оценка, $/ч | `SE` | **`t`** | было при равных весах |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        for (int col = 0; col < 2; col++) {
            double sw = 0;
            double swx = 0;
            for (double[] r : rows) {
                double w = 1.0 / Math.pow(Math.max(r[2], floor), 2);
                sw += w;
                swx += w * r[col];
            }
            double mean = swx / sw;
            double ss = 0;
            for (double[] r : rows) {
                double w = 1.0 / Math.pow(Math.max(r[2], floor), 2);
                ss += w * (r[col] - mean) * (r[col] - mean);
            }
            double se = Math.sqrt(ss / (rows.size() - 1.0) / sw);
            final int c = col;
            double[] eq = stat(rows.stream().mapToDouble(r -> r[c]).toArray());
            sb.append("| ").append(col == 0 ? "разность с хеджем − без" : "уровень с хеджем")
                    .append(" | ").append(money(mean)).append(" | ").append(money(se))
                    .append(" | **").append(round(se > 0 ? mean / se : 0, 2))
                    .append("** | ").append(round(eq[1], 2)).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * П2.10: ОТБОР НА СОБСТВЕННОЙ НОГЕ ХЕДЖА (док. 171 часть III).
     *
     * 🔑 Перевешивание — это тоже исполнение, и мейкер у касания по определению
     * исполняется тогда, когда цена собирается пройти сквозь его уровень. Значит
     * у ноги перпа есть свой отбор, и до сих пор его никто не мерил: цена
     * перевешивания считалась равной одной пошлине в 2 б.п.
     *
     * ⚠️ Плацебо обязателен: на трендовом окне ход марки за час после сделки
     * меряет снос рынка, а не отбор. Берётся тот же знак стороны, но ход за три
     * часа ДО сделки.
     *
     * ⚠️ Марки МИНУТНЫЕ, поэтому горизонт в минуту стоит на грани разрешения:
     * читать надо 5–60 минут.
     */
    private String hedgeMarkoutSection(List<Object[]> fills) {
        long[] hs = {60_000, 300_000, 900_000, 3_600_000};
        String[] names = {"1 мин", "5 мин", "15 мин", "60 мин"};
        int total = 0;
        for (Object[] f : fills) {
            total += ((List<?>) f[2]).size();
        }
        if (total < 30) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n### П2.10. Отбор на собственных"
                + " перевешиваниях (маркаут ноги перпа)\n\n");
        sb.append("Плюс — против нас. Клетка: `отбор − плацебо`, ошибка по сделкам.\n\n");
        sb.append("| горизонт | сделок | отбор, б.п. | плацебо | **чистый** | `SE` | **`t`** |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (int h = 0; h < hs.length; h++) {
            List<Double> net = new ArrayList<>();
            double rawSum = 0;
            double placSum = 0;
            for (Object[] f : fills) {
                @SuppressWarnings("unchecked")
                NavigableMap<Long, Double> mark = (NavigableMap<Long, Double>) f[1];
                for (Object o : (List<?>) f[2]) {
                    double[] t = (double[]) o;
                    long ts = (long) t[0];
                    double side = t[1];
                    double m0 = t[2];
                    Map.Entry<Long, Double> e = mark.ceilingEntry(ts + hs[h]);
                    Map.Entry<Long, Double> p0 = mark.floorEntry(ts - PLACEBO_LAG_MS);
                    Map.Entry<Long, Double> p1 = mark.ceilingEntry(ts - PLACEBO_LAG_MS + hs[h]);
                    if (e == null || m0 <= 0 || p0 == null || p1 == null || p0.getValue() <= 0) {
                        continue;
                    }
                    // Купили перп — против нас падение; продали — рост.
                    double raw = -side * (e.getValue() - m0) / m0 * 1e4;
                    double plac = -side * (p1.getValue() - p0.getValue()) / p0.getValue() * 1e4;
                    rawSum += raw;
                    placSum += plac;
                    net.add(raw - plac);
                }
            }
            if (net.size() < 20) {
                continue;
            }
            double[] arr = net.stream().mapToDouble(Double::doubleValue).toArray();
            // stat отдаёт {среднее, t}; ошибка восстанавливается делением.
            double[] st = stat(arr);
            double se = st[1] == 0 ? 0 : st[0] / st[1];
            sb.append("| ").append(names[h]).append(" | ").append(net.size())
                    .append(" | ").append(round(rawSum / net.size(), 2))
                    .append(" | ").append(round(placSum / net.size(), 2))
                    .append(" | **").append(round(st[0], 2))
                    .append("** | ").append(round(se, 2))
                    .append(" | **").append(round(st[1], 2)).append("** |\n");
        }
        return sb.toString();
    }

    /**
     * П2.9: ГДЕ ИМЕННО ХЕДЖ ПОКРЫВАЕТ (док. 169 часть VI).
     *
     * 🔑 Среднее покрытие в 34% ничего не решает, пока не известно, ГДЕ эти 34%.
     * Если хедж покрывает половину переноски в тихих часах и десятую часть в
     * бурных, он работает наоборот: платит пошлину там, где терять нечего, и
     * молчит там, где теряется всё. Лечится это тогда не полосой, а темпом.
     *
     * Покрытие считается отношением СУММ по квинтилю (нога перпа к зеркалу
     * спотовой переноски), а не средним отношений: у частного с малым
     * знаменателем нет матожидания.
     */
    private String coverageSection(List<double[]> cover) {
        if (cover.size() < 20) {
            return "";
        }
        List<double[]> rows = new ArrayList<>(cover);
        rows.sort((x, y) -> Double.compare(x[0], y[0]));
        StringBuilder sb = new StringBuilder("\n### П2.9. Покрытие по квинтилям хода часа\n\n");
        sb.append("| квинтиль | \\|Δp\\| часа | бот-часов | зеркало (−переноска спота)")
                .append(" | нога перпа | **покрытие** |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        int q = rows.size() / 5;
        for (int b = 0; b < 5; b++) {
            double mv = 0;
            double mirror = 0;
            double perp = 0;
            int n = 0;
            for (int i = b * q; i < Math.min((b + 1) * q, rows.size()); i++) {
                mv += rows.get(i)[0];
                mirror -= rows.get(i)[1];
                perp += rows.get(i)[2];
                n++;
            }
            sb.append("| ").append(b + 1).append(" | ").append(round(mv / n, 3)).append("%")
                    .append(" | ").append(n)
                    .append(" | ").append(money(mirror))
                    .append(" | ").append(money(perp))
                    .append(" | **").append(Math.abs(mirror) < 1e-9 ? "—"
                            : round(100 * perp / mirror, 0) + "%").append("** |\n");
        }
        return sb.toString();
    }

    /** Среднее и {@code t = среднее / ошибка среднего} по ряду отрезков. */
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

    /**
     * Часовой ряд «итога без беты»: {@code Δкапитал − средний запас × Δопоры}.
     *
     * Бета запаса за окно равна {@code средний запас × (конец − начало)}, а это
     * телескопическая сумма часовых кусков — значит вычитать её можно по часам,
     * и сумма ряда совпадёт с итогом за окно. Считаются только СОСЕДНИЕ часы,
     * как и в {@link #hourlySlopes}: через разрыв приращения нет.
     */
    private TreeMap<Long, Double> exBetaByHour(TreeMap<Long, double[]> hourly, double meanPos) {
        TreeMap<Long, Double> out = new TreeMap<>();
        Long prevHour = null;
        double[] prev = null;
        for (Map.Entry<Long, double[]> e : hourly.entrySet()) {
            if (prev != null && e.getKey() - prevHour == 1) {
                double dEquity = e.getValue()[0] - prev[0];
                double dFair = e.getValue()[1] - prev[1];
                out.put(e.getKey(), dEquity - meanPos * dFair);
            }
            prevHour = e.getKey();
            prev = e.getValue();
        }
        return out;
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

    // ------------------------------------------------ хедж по сделкам (30.09.2026)

    /** Набор условий: тип (A — пик, B — спуск), N, порог плюса P, доля минуса L, порог минуса X, выход E. */
    record RoundRule(char type, int n, double p, double l, double x, double e) {
        String code() {
            return type == 'A'
                    ? String.format(Locale.ROOT, "A%d-%.0f-%.0f-%.0f", n, p, l * 100, e)
                    : String.format(Locale.ROOT, "B%d-%.0f-%.0f", n, x, e);
        }

        String name() {
            return type == 'A'
                    ? String.format(Locale.ROOT, "A пик: %d плюс, сумма ≥%.0f п., минус ≥%.0f%% суммы, выход ≥%.0f п.",
                    n, p, l * 100, e)
                    : String.format(Locale.ROOT, "B спуск: %d минус, сумма ≥%.0f п., выход ≥%.0f п.", n, x, e);
        }
    }

    /** Закрытая сделка: момент продажи и её результат в б.п. к цене входа (FIFO). */
    private record Round(long ts, double usd) {
    }

    private static List<Round> rounds(Series s) {
        java.util.ArrayDeque<double[]> lots = new java.util.ArrayDeque<>();   // {qty, px}
        List<Round> out = new ArrayList<>();
        for (int i = 0; i < s.fillTs.length; i++) {
            double dq = s.fillDq[i];
            double px = s.fillPx[i];
            if (dq > 0) {
                lots.addLast(new double[]{dq, px});
                continue;
            }
            double left = -dq;
            double cost = 0;
            double matched = 0;
            while (left > 1e-15 && !lots.isEmpty()) {
                double[] l = lots.peekFirst();
                double take = Math.min(left, l[0]);
                cost += take * l[1];
                matched += take;
                l[0] -= take;
                left -= take;
                if (l[0] <= 1e-15) {
                    lots.pollFirst();
                }
            }
            if (matched > 0) {
                double avg = cost / matched;
                out.add(new Round(s.fillTs[i], (px - avg) * matched));
            }
        }
        return out;
    }

    /**
     * {@code --revx-hedge-rounds}: хедж, включаемый по РЕЗУЛЬТАТАМ СДЕЛОК, на всей сетке
     * условий сразу. Вход A — «пик»: N плюсовых сделок (сумма ≥ P б.п.), затем минус
     * не меньше L от этой суммы. Вход B — «спуск»: N минусовых подряд, каждая ≤ −X.
     * Хедж — шорт на весь запас, следует за запасом; выход — плюсовые сделки с
     * начала режима набрали ≥ E б.п., шорт закрывается целиком.
     */
    public void runRoundGrid(String journals, String fromIso, String toIso, double stepLots,
                             double feeBp, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0 : Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE : Instant.parse(toIso).toEpochMilli();
        List<RoundRule> rules = new ArrayList<>();
        double[] exits = {0, 1, 2, 5, 10};
        for (int n : new int[]{2, 3}) {
            for (double p : new double[]{0, 10, 20, 30, 40}) {
                for (double l : new double[]{0, 0.2, 0.3, 0.4, 0.5}) {
                    for (double e : exits) {
                        rules.add(new RoundRule('A', n, p, l, 0, e));
                    }
                }
            }
            for (double x : new double[]{0, 5, 10, 15, 20}) {
                for (double e : exits) {
                    rules.add(new RoundRule('B', n, 0, 0, x, e));
                }
            }
        }
        double[][] acc = new double[rules.size()][5];   // {нога перпа, пошлина, режимов, минут в режиме, сделок}
        List<PriceRule> prules = new ArrayList<>();
        for (double h : new double[]{1, 2, 3}) {
            for (double r : new double[]{0.7, 1, 1.5, 2}) {
                for (double f : new double[]{0.25, 0.33, 0.5}) {
                    for (double y : new double[]{0.3, 0.5, 0.8, 99}) {
                        for (double tt : new double[]{1, 2, 4, 99}) {
                            prules.add(new PriceRule(h, r, f, y, tt));
                        }
                    }
                }
            }
        }
        double[][] pacc = new double[prules.size()][5];
        double[] invN = {1, 2, 3, 4, 5, 6};
        double[][] iacc = new double[invN.length][5];
        double spot = 0;
        int minutes = 0;
        StringBuilder perBot = new StringBuilder();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String id = kv[0].trim();
            String base = id.contains(":") ? id.substring(id.indexOf(':') + 1) : null;
            Bot b = new Bot(id.contains(":") ? id.substring(0, id.indexOf(':')) : id, base,
                    kv[1].split("\\+"));
            Series s = readSeries(b, from, to);
            if (s == null || s.ts.length < 100) {
                continue;
            }
            String cur = b.base() != null ? b.base() : guessBase(s.fair[0]);
            NavigableMap<Long, Double> mark = marks.marksInQuote(PerpMarkSource.perpFor(cur), cur,
                    s.ts[0] - 120_000, s.ts[s.ts.length - 1] + 120_000);
            if (mark.isEmpty()) {
                continue;
            }
            double lot = s.lot > 0 ? s.lot : medianBuy(s);
            double step = stepLots > 0 && lot > 0 ? lot * stepLots : STEP.getOrDefault(cur, 1e-4);
            // Пункт — доля ДЕНЕЖНОГО ПОТОЛКА бота (владелец 30.09.2026): 10 п. от $7 = 0.7 цента.
            double capLots = Double.parseDouble(System.getProperty("revx.hedge.cap-lots",
                    lot * s.fair[0] > 4 ? "2.33" : "7"));
            double unit = capLots * lot * s.fair[s.ts.length / 2] / 1e4;
            List<Round> rs = rounds(s);
            double cash = 0;
            int fi = 0;
            for (int i = 0; i < s.ts.length; i++) {
                while (fi < s.fillTs.length && s.fillTs[fi] <= s.ts[i]) {
                    cash -= s.fillDq[fi] * s.fillPx[fi];
                    fi++;
                }
            }
            double botSpot = cash + s.inv[s.ts.length - 1] * s.fair[s.ts.length - 1];
            spot += botSpot;
            minutes = Math.max(minutes, s.ts.length);
            perBot.append(String.format(Locale.ROOT, "%s %s: сделок %d, спот %+.4f, шаг %.2f лота%n",
                    b.id(), cur, rs.size(), botSpot, lot > 0 ? step / lot : 0));
            if (!TRACE.isBlank()) {
                log.info("--- бот {} {}: режимы набора «{}»", b.id(), cur, TRACE);
            }
            for (int r = 0; r < rules.size(); r++) {
                simulateRule(rules.get(r), s, rs, mark, step, feeBp / 1e4, acc[r], unit);
            }
            MinuteSeries ms = minutes(s, mark);
            for (int r = 0; r < prules.size(); r++) {
                simulatePrice(prules.get(r), ms, step, feeBp / 1e4, pacc[r]);
            }
            for (int r = 0; r < invN.length; r++) {
                simulateInv(invN[r], lot, ms, step, feeBp / 1e4, iacc[r]);
            }
        }
        Integer[] order = new Integer[rules.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(acc[y][0] - acc[y][1], acc[x][0] - acc[x][1]));
        StringBuilder sb = new StringBuilder("# Хедж по сделкам\n\n").append(perBot)
                .append(String.format(Locale.ROOT, "%nспот всех ботов без хеджа: %+.4f $%n%n", spot))
                .append("| условия | режимов | часов в хедже | перевешиваний | нога перпа | пошлина | итог хеджа | спот + хедж |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int k = 0; k < order.length; k++) {
            int r = order[k];
            if (k >= 25 && k < order.length - 5) {
                if (k == 25) {
                    sb.append("| … | | | | | | | |\n");
                }
                continue;
            }
            double net = acc[r][0] - acc[r][1];
            sb.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f | %.0f | %+.4f | %.4f | **%+.4f** | %+.4f |%n",
                    rules.get(r).name(), acc[r][2], acc[r][3] / 60.0, acc[r][4], acc[r][0], acc[r][1],
                    net, spot + net));
        }
        log.info("\n{}", sb);
        if (out != null && !out.isBlank()) {
            try {
                StringBuilder csv = new StringBuilder("условия;режимов;часов;перевешиваний;нога;пошлина;итог\n");
                for (int r = 0; r < rules.size(); r++) {
                    csv.append(String.format(Locale.ROOT, "%s;%.0f;%.1f;%.0f;%.4f;%.4f;%.4f%n",
                            rules.get(r).name(), acc[r][2], acc[r][3] / 60.0, acc[r][4],
                            acc[r][0], acc[r][1], acc[r][0] - acc[r][1]));
                }
                for (int r = 0; r < prules.size(); r++) {
                    csv.append(String.format(Locale.ROOT, "%s;%.0f;%.1f;%.0f;%.4f;%.4f;%.4f%n",
                            prules.get(r).name(), pacc[r][2], pacc[r][3] / 60.0, pacc[r][4],
                            pacc[r][0], pacc[r][1], pacc[r][0] - pacc[r][1]));
                }
                for (int r = 0; r < invN.length; r++) {
                    csv.append(String.format(Locale.ROOT, "I запас: хедж с %.0f лотов до распродажи;%.0f;%.1f;%.0f;%.4f;%.4f;%.4f%n",
                            invN[r], iacc[r][2], iacc[r][3] / 60.0, iacc[r][4],
                            iacc[r][0], iacc[r][1], iacc[r][0] - iacc[r][1]));
                }
                Files.writeString(Path.of(out + ".csv"), csv.toString(), StandardCharsets.UTF_8);
                Files.writeString(Path.of(out), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("не записать {}: {}", out, e.getMessage());
            }
        }
    }

    /**
     * ВХОД И ВЫХОД ПО ЦЕНЕ (30.09.2026): рост за H ч от минимума к максимуму ≥ R%,
     * затем откат от максимума ≥ F от этого роста — шорт на весь запас. Выход:
     * отскок от минимума за время хеджа ≥ Y% (99 — без условия) или прошло T ч
     * (99 — без условия). Повторный вход — только на новом пике. Считается по минутам.
     */
    record PriceRule(double h, double r, double f, double y, double t) {
        String name() {
            return String.format(Locale.ROOT, "P цена: рост ≥%.1f%% за %.0f ч, откат ≥%.0f%%, выход: отскок %s, время %s",
                    r, h, f * 100, y >= 99 ? "—" : String.format(Locale.ROOT, "%.1f%%", y),
                    t >= 99 ? "—" : String.format(Locale.ROOT, "%.0f ч", t));
        }

        String code() {
            return String.format(Locale.ROOT, "P%.1f-%.0f-%.0f-%.1f-%.0f", r, h, f * 100, y, t);
        }
    }

    /** Поминутный ряд бота и заготовки «максимум / минимум до максимума» за H часов. */
    private static final class MinuteSeries {
        long[] ts;
        double[] p;
        double[] inv;
        double[] mark;
        Map<Integer, int[][]> hiLo = new java.util.HashMap<>();   // H → {индекс максимума, индекс минимума до него}
    }

    private static MinuteSeries minutes(Series s, NavigableMap<Long, Double> mark) {
        List<Integer> idx = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (int i = 0; i < s.ts.length; i++) {
            if (s.ts[i] / 60_000 != last) {
                idx.add(i);
                last = s.ts[i] / 60_000;
            }
        }
        MinuteSeries m = new MinuteSeries();
        int n = idx.size();
        m.ts = new long[n];
        m.p = new double[n];
        m.inv = new double[n];
        m.mark = new double[n];
        for (int k = 0; k < n; k++) {
            int i = idx.get(k);
            m.ts[k] = s.ts[i];
            m.p[k] = s.fair[i];
            m.inv[k] = s.inv[i];
            Map.Entry<Long, Double> e = mark.floorEntry(s.ts[i]);
            m.mark[k] = e == null ? 0 : e.getValue();
        }
        for (int hours : new int[]{1, 2, 3}) {
            int[][] hl = new int[n][2];
            long win = hours * 3_600_000L;
            for (int k = 0; k < n; k++) {
                int hi = k;
                for (int j = k; j >= 0 && m.ts[k] - m.ts[j] <= win; j--) {
                    if (m.p[j] > m.p[hi]) {
                        hi = j;
                    }
                }
                int lo = hi;
                for (int j = hi; j >= 0 && m.ts[k] - m.ts[j] <= win; j--) {
                    if (m.p[j] < m.p[lo]) {
                        lo = j;
                    }
                }
                hl[k][0] = hi;
                hl[k][1] = lo;
            }
            m.hiLo.put(hours, hl);
        }
        return m;
    }

    private static void simulatePrice(PriceRule rule, MinuteSeries m, double step, double fee,
                                      double[] acc) {
        int[][] hl = m.hiLo.get((int) rule.h());
        boolean on = false;
        double perp = 0;
        double minSince = 0;
        long since = 0;
        int lastPeak = -1;
        for (int k = 0; k < m.ts.length; k++) {
            if (k > 0 && m.mark[k] > 0 && m.mark[k - 1] > 0) {
                acc[0] += perp * (m.mark[k] - m.mark[k - 1]);
            }
            double p = m.p[k];
            int hi = hl[k][0];
            int lo = hl[k][1];
            if (!on) {
                double rise = m.p[hi] / m.p[lo] - 1;
                double back = m.p[hi] - m.p[lo] > 0 ? (m.p[hi] - p) / (m.p[hi] - m.p[lo]) : 0;
                if (hi != lastPeak && rise * 100 >= rule.r() && back >= rule.f()) {
                    on = true;
                    lastPeak = hi;
                    minSince = p;
                    since = m.ts[k];
                    acc[2]++;
                    if (rule.code().equals(TRACE)) {
                        traceLeg = acc[0] - acc[1];
                        tracePx = p;
                        log.info("ВХОД {}: пик {} в {}, рост {}%, цена {}", Instant.ofEpochMilli(m.ts[k]),
                                round(m.p[hi], 4), Instant.ofEpochMilli(m.ts[hi]), round(rise * 100, 2), round(p, 4));
                    }
                }
            } else {
                minSince = Math.min(minSince, p);
                boolean bounce = rule.y() < 99 && p >= minSince * (1 + rule.y() / 100);
                boolean timeUp = rule.t() < 99 && m.ts[k] - since >= rule.t() * 3_600_000L;
                if (bounce || timeUp) {
                    on = false;
                    if (rule.code().equals(TRACE)) {
                        log.info("ВЫХОД {} ({}): цена {} → {} ({}%), нога {}", Instant.ofEpochMilli(m.ts[k]),
                                bounce ? "отскок" : "время", round(tracePx, 4), round(p, 4),
                                round((p / tracePx - 1) * 100, 2), money(acc[0] - acc[1] - traceLeg));
                    }
                }
            }
            if (m.mark[k] > 0) {
                double want = on ? -m.inv[k] : 0;
                double delta = want - perp;
                double rounded = on ? Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step : -perp;
                if (Math.abs(rounded) > 1e-15 && (Math.abs(rounded) >= step || !on)) {
                    perp += rounded;
                    acc[1] += Math.abs(rounded) * m.mark[k] * fee;
                    acc[4]++;
                }
            }
            if (on && k > 0) {
                acc[3] += (m.ts[k] - m.ts[k - 1]) / 60_000.0;
            }
        }
    }

    /**
     * ХЕДЖ ПО ЗАПАСУ ДО РАСПРОДАЖИ (30.09.2026, идея владельца для раздельного лота):
     * запас дошёл до N лотов — шорт на весь запас, следует за ним; запас упал ниже
     * половины лота (бот продал всё) — шорт закрывается целиком.
     */
    private static void simulateInv(double nLots, double lot, MinuteSeries m, double step,
                                    double fee, double[] acc) {
        boolean on = false;
        double perp = 0;
        for (int k = 0; k < m.ts.length; k++) {
            if (k > 0 && m.mark[k] > 0 && m.mark[k - 1] > 0) {
                acc[0] += perp * (m.mark[k] - m.mark[k - 1]);
            }
            if (!on && m.inv[k] >= nLots * lot * 0.99) {
                on = true;
                acc[2]++;
            } else if (on && m.inv[k] < 0.5 * lot) {
                on = false;
            }
            if (m.mark[k] > 0) {
                double want = on ? -m.inv[k] : 0;
                double delta = want - perp;
                double rounded = on ? Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step : -perp;
                if (Math.abs(rounded) > 1e-15 && (Math.abs(rounded) >= step || !on)) {
                    perp += rounded;
                    acc[1] += Math.abs(rounded) * m.mark[k] * fee;
                    acc[4]++;
                }
            }
            if (on && k > 0) {
                acc[3] += (m.ts[k] - m.ts[k - 1]) / 60_000.0;
            }
        }
    }

    /** Набор условий, чьи режимы хеджа печатаются поимённо ({@code revx.hedge.trace}). */
    static final String TRACE = System.getProperty("revx.hedge.trace", "");
    private static long traceFrom;
    private static double traceLeg;
    private static double tracePx;

    private static void simulateRule(RoundRule rule, Series s, List<Round> rs,
                                     NavigableMap<Long, Double> mark, double step, double fee,
                                     double[] acc, double unit) {
        boolean on = false;
        double winSum = 0;
        double perp = 0;
        double prevM = 0;
        int ri = 0;
        List<Double> hist = new ArrayList<>();
        long prevT = 0;
        for (int i = 0; i < s.ts.length; i++) {
            long t = s.ts[i];
            Map.Entry<Long, Double> e = mark.floorEntry(t);
            double m = e == null ? 0 : e.getValue();
            if (prevM > 0 && m > 0) {
                acc[0] += perp * (m - prevM);
            }
            while (ri < rs.size() && rs.get(ri).ts() <= t) {
                double bp = rs.get(ri++).usd();
                if (!on) {
                    boolean enter = false;
                    int n = rule.n();
                    if (rule.type() == 'A' && bp < 0 && hist.size() >= n) {
                        double sum = 0;
                        boolean allPlus = true;
                        for (int k = hist.size() - n; k < hist.size(); k++) {
                            allPlus &= hist.get(k) > 0;
                            sum += hist.get(k);
                        }
                        enter = allPlus && sum >= rule.p() * unit && -bp >= rule.l() * sum;
                    } else if (rule.type() == 'B' && bp < 0 && hist.size() >= n - 1) {
                        boolean all = true;
                        double lossSum = -bp;
                        for (int k = hist.size() - (n - 1); k < hist.size(); k++) {
                            double v = hist.get(k);
                            all &= v < 0;
                            lossSum -= v;
                        }
                        enter = all && lossSum >= rule.x() * unit;
                    }
                    if (enter) {
                        on = true;
                        winSum = 0;
                        acc[2]++;
                        if (rule.code().equals(TRACE)) {
                            traceFrom = t;
                            traceLeg = acc[0] - acc[1];
                            tracePx = s.fair[i];
                        }
                    }
                } else if (bp > 0) {
                    winSum += bp;
                    if (winSum >= rule.e() * unit) {
                        on = false;
                        if (rule.code().equals(TRACE)) {
                            log.info("ХЕДЖ {} → {}: цена {} → {} ({}%), нога {}",
                                    Instant.ofEpochMilli(traceFrom), Instant.ofEpochMilli(t),
                                    round(tracePx, 4), round(s.fair[i], 4),
                                    round((s.fair[i] / tracePx - 1) * 100, 2),
                                    money(acc[0] - acc[1] - traceLeg));
                        }
                    }
                }
                hist.add(bp);
            }
            if (m > 0) {
                double want = on ? -s.inv[i] : 0;
                double delta = want - perp;
                double rounded = on ? Math.signum(delta) * Math.floor(Math.abs(delta) / step) * step
                        : -perp;
                if (Math.abs(rounded) > 1e-15 && (Math.abs(rounded) >= step || !on)) {
                    perp += rounded;
                    acc[1] += Math.abs(rounded) * m * fee;
                    acc[4]++;
                }
            }
            if (on && prevT > 0) {
                acc[3] += (t - prevT) / 60_000.0;
            }
            prevT = t;
            prevM = m;
        }
    }
}
