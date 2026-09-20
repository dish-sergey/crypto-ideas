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
        StringBuilder rows = new StringBuilder(
                "бот;пара;час;без_хеджа;с_хеджем;опора0;опора1;захват;сделок\n");
        List<Object[]> legs = new ArrayList<>();   // {метка, пара, Legs} — для П2.6
        StringBuilder rateNoise = new StringBuilder();

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
            // Сырые USD-марки — для П2.6: нога Kraken живёт в USD, и разница с
            // USDC-пересчётом и есть базис, которым возражал док. 150.
            NavigableMap<Long, Double> markUsd = marks.marks(PerpMarkSource.perpFor(base),
                    s.ts[0] - 120_000, s.ts[s.ts.length - 1] + 120_000);
            Result plain = evaluate(s, null, 0, 0, 0);
            Result hedged = evaluate(s, mark, markUsd, bandLots * lot, step, feeBp / 1e4);
            legs.add(new Object[]{b.id(), base, hedged.legs()});
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
            sb.append(pairedSection(hourPlain, hourHedged));
            sb.append(legsSection(legs));
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
                          Legs legs) {
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
                fi++;
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
                    hedgeCarry += prevPerp * (m - prevMark);
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
            prevMarkUsd = mu;
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
                slopes[0], slopes[1], (int) slopes[2]), trades,
                exBetaByHour(hourly, meanPos), hourly, capHour,
                new Legs(capture + spotCarry, hedgeCarry - fees,
                        hedgeCarryUsd - fees, mirror, fees));
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
    private String pairedSection(TreeMap<Long, Double> plain, TreeMap<Long, Double> hedged) {
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
}
