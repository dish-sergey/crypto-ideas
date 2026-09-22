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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ПОВЕРХНОСТЬ ПРИБЫЛИ ПО ОТСТУПУ ВНУТРИ ОДНОРОДНЫХ ЧАСОВ. Команда
 * {@code --revx-surface}, пункт П3.0 из док. 165 — ворота блока 3.
 *
 * <h2>Вопрос</h2>
 *
 * Поверхность «прибыль против отступа» на всём окне — плато 8.6–13.1 б.п.
 * (док. 151), то есть кривизна около нуля. Условная формула отступа имеет смысл
 * ровно тогда, когда это плато — АРТЕФАКТ УСРЕДНЕНИЯ: внутри каждого часа
 * поверхность острая, но её пик ездит, и среднее по часам выглядит плоским.
 * Отличить одно от другого можно на уже собранных данных, и это делается здесь.
 *
 * <h2>Как считается</h2>
 *
 * Часы делятся на классы по волатильности ПРЕДЫДУЩЕГО часа (по ленте — не по
 * своим сделкам и не по текущему часу: иначе это заглядывание вперёд). Внутри
 * класса строится кривая по ленте:
 *
 * <pre>
 *   λ(δ)      = событий ленты в час, дотянувшихся до δ от опоры
 *   c(δ)      = средний ход опоры ПРОТИВ нас за горизонт после таких событий
 *   прибыль(δ) = λ(δ) × (δ − c(δ)) × лот / 10000,  $/час
 * </pre>
 *
 * ⚠️ Захват равен δ, а не расстоянию принта: наша заявка стоит на δ, и всё, что
 * дальше, достаётся не нам.
 *
 * ⚠️ Пачки схлопываются: несколько принтов одной миллисекунды и одной стороны —
 * это ОДНА рыночная заявка, разметающая книгу, и наш единственный лот берёт
 * только первый (CLAUDE.md, задача про мёртвый слот).
 *
 * <h2>Чего эта кривая не знает</h2>
 *
 * Инвентаря. Она считает, что заявка стоит всегда с обеих сторон, — то есть это
 * ВЕРХНЯЯ граница, одинаково завышенная для всех δ. Для вопроса «острая ли
 * поверхность» этого достаточно, для вопроса «сколько денег» — нет.
 */
@Component
@Lazy
public class OffsetSurface {

    private static final Logger log = LoggerFactory.getLogger(OffsetSurface.class);

    /** Ступени отступа, на которых строится кривая, б.п. */
    private static final double[] GRID = {4, 6, 8, 10, 12, 14, 16, 18, 20, 24};

    /** Ширина плато из док. 151: пики классов обязаны разойтись сильнее. */
    private static final double PLATEAU_BP = 4.5;

    /**
     * Горизонты отбора для П2.8 (док. 169 часть III).
     *
     * 🔑 Зачем лестница: «ядро» измерено маркой на СЛЕДУЮЩЕМ тике, а хедж с
     * полосой выравнивает раз в десятки минут. Между этими двумя горизонтами
     * отбор успевает вырасти, и разница — это то, что потолок конструкции
     * теряет по дороге. Если {@code c(δ, h)} выходит на плато раньше типичного
     * времени до перевешивания, потолок близок к захвату; если растёт на всём
     * диапазоне — потолок существенно ниже, и полосу надо сужать до предела шага.
     */
    private static final long[] HORIZONS = {1_000, 60_000, 300_000, 900_000, 1_800_000, 7_200_000};

    /** Подписи горизонтов для таблицы. */
    private static final String[] HORIZON_NAMES = {"1 с", "60 с", "5 мин", "15 мин", "30 мин", "2 ч"};

    /** Сдвиг плацебо-контроля: связь с принтом рвём, снос рынка оставляем. */
    private static final long PLACEBO_LAG_MS = 3 * 3_600_000L;

    /** Один час: класс по волатильности предыдущего часа и его события. */
    private record Hour(long hour, double prevVolBp, List<double[]> events) {
    }

    /** Часовой ряд волатильности по ленте — выгружается рядом с отчётом. */
    private final StringBuilder volOut = new StringBuilder("пара;час;волатильность_бп\n");

    public void run(String standDb, String journals, String fromIso, String toIso,
                    long horizonMs, double lot, int classes, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0
                : java.time.Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE
                : java.time.Instant.parse(toIso).toEpochMilli();

        StringBuilder sb = new StringBuilder("# П3.0: поверхность прибыли по отступу"
                + " внутри однородных часов\n\n");
        sb.append("Классов по волатильности ПРЕДЫДУЩЕГО часа: ").append(classes)
                .append(", горизонт отбора ").append(horizonMs / 1000).append(" с, лот $")
                .append(round(lot, 2)).append(".\n");

        boolean any = false;
        List<String> verdicts = new ArrayList<>();
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
            if (prints.size() < 200) {
                log.warn("{}: принтов мало ({})", base, prints.size());
                continue;
            }
            any = true;
            verdicts.add(section(sb, base, prints, fair, horizonMs, lot, classes));
        }
        if (!any) {
            log.warn("считать нечего");
            return;
        }

        sb.append("\n# Вердикт П3.0\n\n");
        for (String v : verdicts) {
            sb.append("- ").append(v).append('\n');
        }
        sb.append("\nКритерий: подтверждает, если внутри классов есть измеримая кривизна")
                .append(" И пики классов разнесены больше ширины плато (")
                .append(round(PLATEAU_BP, 1)).append(" б.п.).\n");

        if (out != null && !out.isBlank()) {
            try {
                Files.writeString(Path.of(out + ".vol.csv"), volOut.toString(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("не записать ряд волатильности: {}", e.toString());
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

    /** Раздел по одной паре; возвращает строку вердикта. */
    private String section(StringBuilder sb, String base, List<TapeData.Print> prints,
                           TapeData.Fair fair, long horizonMs, double lot, int classes) {
        // 1. События: пачка одной миллисекунды и одной стороны — одно событие.
        Map<Long, List<double[]>> byHour = new TreeMap<>();
        Map<Long, List<Double>> retByHour = new TreeMap<>();
        long prevMs = Long.MIN_VALUE;
        int prevSide = 0;
        double prevPrice = 0;
        for (TapeData.Print p : prints) {
            double f0 = fair.at(p.tsMs());
            double f1 = fair.after(p.tsMs() + horizonMs);
            if (prevPrice > 0) {
                // Доходность принт-к-принту — из неё часовая волатильность.
                retByHour.computeIfAbsent(p.tsMs() / 3_600_000L, k -> new ArrayList<>())
                        .add(Math.log(p.price() / prevPrice));
            }
            prevPrice = p.price();
            if (f0 <= 0 || f1 <= 0) {
                continue;
            }
            boolean sameBurst = p.tsMs() == prevMs && p.aggressor() == prevSide;
            prevMs = p.tsMs();
            prevSide = p.aggressor();
            if (sameBurst) {
                continue;
            }
            double dist = p.aggressor() * (p.price() - f0) / f0 * 1e4;
            double markout = p.aggressor() * (f1 - f0) / f0 * 1e4;
            if (dist <= 0) {
                continue;
            }
            // П2.8: тот же отбор на лестнице горизонтов. Ноль в клетке значит
            // «опоры на таком удалении нет» — такие клетки в среднее не идут.
            double[] row = new double[3 + 2 * HORIZONS.length];
            row[2 + 2 * HORIZONS.length] = p.aggressor() < 0 ? 1 : 0;   // 1 — наш бид
            row[0] = dist;
            row[1] = markout;
            // ⚠️ Контроль сноса обязателен, иначе дальние горизонты меряют рынок,
            // а не отбор: за два часа снос доходил до −27.65 б.п. при отборе в 8
            // (задача A29). Плацебо — тот же знак агрессора, но ход, взятый за
            // ТРИ ЧАСА ДО принта: связь с принтом разорвана, снос остался.
            double fPlacebo = fair.at(p.tsMs() - PLACEBO_LAG_MS);
            for (int h = 0; h < HORIZONS.length; h++) {
                double fh = fair.after(p.tsMs() + HORIZONS[h]);
                row[2 + h] = fh <= 0 ? Double.NaN : p.aggressor() * (fh - f0) / f0 * 1e4;
                double fph = fair.after(p.tsMs() - PLACEBO_LAG_MS + HORIZONS[h]);
                row[2 + HORIZONS.length + h] = fPlacebo <= 0 || fph <= 0 ? Double.NaN
                        : p.aggressor() * (fph - fPlacebo) / fPlacebo * 1e4;
            }
            byHour.computeIfAbsent(p.tsMs() / 3_600_000L, k -> new ArrayList<>()).add(row);
        }

        // 2. Волатильность часа по ленте и класс по ПРЕДЫДУЩЕМУ часу.
        Map<Long, Double> vol = new TreeMap<>();
        for (Map.Entry<Long, List<Double>> e : retByHour.entrySet()) {
            if (e.getValue().size() < 5) {
                continue;
            }
            double ss = 0;
            for (double r : e.getValue()) {
                ss += r * r;
            }
            vol.put(e.getKey(), Math.sqrt(ss) * 1e4);
        }
        volOut.append(base).append(" hours=").append(vol.size()).append('\n');
        for (Map.Entry<Long, Double> e : vol.entrySet()) {
            volOut.append(base).append(';').append(e.getKey()).append(';')
                    .append(round(e.getValue(), 3)).append('\n');
        }
        List<Hour> hours = new ArrayList<>();
        for (Map.Entry<Long, List<double[]>> e : byHour.entrySet()) {
            Double pv = vol.get(e.getKey() - 1);
            if (pv != null) {
                hours.add(new Hour(e.getKey(), pv, e.getValue()));
            }
        }
        if (hours.size() < 4 * classes) {
            return base + ": часов мало (" + hours.size() + ") — класс не построить";
        }
        double[] cuts = quantiles(hours.stream().mapToDouble(Hour::prevVolBp).toArray(), classes);

        sb.append("\n## ").append(base).append("\n\n");
        sb.append("Часов с известной волатильностью предыдущего часа: ").append(hours.size())
                .append(", событий ленты ")
                .append(hours.stream().mapToInt(h -> h.events().size()).sum()).append(".\n\n");

        Map<Integer, List<Hour>> byClass = new TreeMap<>();
        for (Hour h : hours) {
            int k = 0;
            while (k < cuts.length && h.prevVolBp() > cuts[k]) {
                k++;
            }
            byClass.computeIfAbsent(k, x -> new ArrayList<>()).add(h);
        }

        sb.append("| класс | волатильность предыдущего часа, б.п. | часов | событий/час |");
        for (double d : GRID) {
            sb.append(" δ=").append((int) d).append(" |");
        }
        sb.append(" **пик δ** | прибыль в пике, $/ч | ширина плато |\n");
        sb.append("|---|---|---:|---:|");
        for (int i = 0; i < GRID.length; i++) {
            sb.append("---:|");
        }
        sb.append("---:|---:|---:|\n");

        List<Double> peaks = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        for (Map.Entry<Integer, List<Hour>> e : byClass.entrySet()) {
            List<double[]> ev = new ArrayList<>();
            for (Hour h : e.getValue()) {
                ev.addAll(h.events());
            }
            int nHours = e.getValue().size();
            double[] profit = new double[GRID.length];
            double[] se = new double[GRID.length];
            for (int i = 0; i < GRID.length; i++) {
                double delta = GRID[i];
                int n = 0;
                double sum = 0;
                double ss = 0;
                for (double[] x : ev) {
                    if (x[0] >= delta) {
                        n++;
                        sum += x[1];
                        ss += x[1] * x[1];
                    }
                }
                if (n < 5) {
                    profit[i] = Double.NaN;
                    continue;
                }
                double c = sum / n;
                double varC = Math.max(ss / n - c * c, 0) / n;
                double lambda = n / (double) nHours;
                profit[i] = lambda * (delta - c) * lot / 1e4;
                // Ошибка: пуассоновская по числу событий плюс ошибка среднего отбора.
                double varLambda = n / (double) (nHours * nHours);
                se[i] = Math.sqrt(Math.max((delta - c) * (delta - c) * varLambda
                        + lambda * lambda * varC, 0)) * lot / 1e4;
            }
            int best = -1;
            for (int i = 0; i < GRID.length; i++) {
                if (!Double.isNaN(profit[i]) && (best < 0 || profit[i] > profit[best])) {
                    best = i;
                }
            }
            // 🔑 Пиком считается только ВНУТРЕННИЙ максимум с прибылью, отличимой
            // от нуля. Иначе «пик» получается там, где кривая всюду отрицательна и
            // просто растёт к краю сетки: у ETH так вышло в двух классах из
            // четырёх, и это дало бы ложный разброс пиков в 14 б.п.
            boolean realPeak = best > 0 && best < GRID.length - 1
                    && !Double.isNaN(profit[best + 1])
                    && profit[best] > 2 * se[best];
            double width = best < 0 ? 0 : plateauWidth(profit, best);
            double lo = e.getKey() == 0 ? 0 : cuts[e.getKey() - 1];
            double hi = e.getKey() < cuts.length ? cuts[e.getKey()] : Double.POSITIVE_INFINITY;
            sb.append("| ").append(e.getKey() + 1).append(" | ")
                    .append(round(lo, 1)).append("–")
                    .append(Double.isInfinite(hi) ? "∞" : round(hi, 1))
                    .append(" | ").append(nHours)
                    .append(" | ").append(round(ev.size() / (double) nHours, 1)).append(" |");
            for (int i = 0; i < GRID.length; i++) {
                sb.append(' ').append(Double.isNaN(profit[i]) ? "—" : money(profit[i])).append(" |");
            }
            if (realPeak) {
                peaks.add(GRID[best]);
                widths.add(width);
                sb.append(" **").append((int) GRID[best]).append("** | ")
                        .append(money(profit[best])).append(" ± ").append(money(se[best]))
                        .append(" | ").append(round(width, 1)).append(" б.п. |\n");
            } else if (best >= 0) {
                sb.append(" ⚠️ нет пика (").append((int) GRID[best]).append(") | ")
                        .append(money(profit[best])).append(" ± ").append(money(se[best]))
                        .append(" | — |\n");
            } else {
                sb.append(" — | — | — |\n");
            }
        }

        List<double[]> all = new ArrayList<>();
        for (Hour h : hours) {
            all.addAll(h.events());
        }
        structure(sb, all, hours.size(), lot);
        watch(sb, hours, lot);
        horizons(sb, all, hours.size());
        sidesBySign(sb, hours, fair);

        double spread = peaks.isEmpty() ? 0
                : peaks.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - peaks.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double meanWidth = widths.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        sb.append("\nКлассов с настоящим пиком: **").append(peaks.size()).append(" из ")
                .append(byClass.size()).append("**, разброс пиков **").append(round(spread, 1))
                .append(" б.п.** при ширине плато ").append(round(PLATEAU_BP, 1))
                .append("; средняя ширина плато ВНУТРИ класса ")
                .append(round(meanWidth, 1)).append(" б.п.\n");
        if (peaks.size() < 3) {
            return base + ": настоящих пиков " + peaks.size() + " из " + byClass.size()
                    + " — **опровергает** (сравнивать нечего)";
        }
        return base + ": пиков " + peaks.size() + " из " + byClass.size() + ", разброс "
                + round(spread, 1) + " б.п., плато внутри класса " + round(meanWidth, 1)
                + " б.п. — " + (spread > PLATEAU_BP && meanWidth < PLATEAU_BP
                ? "**подтверждает**" : "**опровергает**");
    }

    /**
     * СТРУКТУРА КРИВОЙ: множители порознь (док. 169 часть IV).
     *
     * 🔑 Зачем. Кривая прибыли — это произведение {@code λ(δ) × (δ − c(δ))}, и по
     * произведению нельзя понять, ПОЧЕМУ поверхность плоская. Условие оптимума
     * при {@code λ = A·e^(−κδ)}:
     *
     * <pre>δ* − c(δ*) = (1 − c'(δ*)) / κ</pre>
     *
     * Если {@code c'(δ) → 1} — каждый лишний базисный пункт отступа съедается
     * лишним пунктом отбора, числитель обращается в ноль, и плоская поверхность
     * оказывается свойством РЫНКА, а не усреднения по нашим часам. Это и есть
     * разница между «плато измерено» и «плато объяснено».
     */
    /**
     * МОНИТОР ПЛАТО: `κ`, `c` и положение оптимума ПО СУТКАМ (док. 186, пункт 1).
     *
     * <h2>Зачем следить за причинами, а не за следствием</h2>
     *
     * Перебор отступов — дорогой способ узнать, где оптимум: деньги меряются
     * плохо, каждая ступень стоит дней, и ответ приходит задним числом. А обе
     * величины, которые оптимум ЗАДАЮТ, ленточные и считаются на тысячах событий
     * за сутки:
     * <ul>
     *   <li>{@code κ} — как быстро поток уходит с расстоянием;</li>
     *   <li>{@code c(δ)} — как быстро растёт отбор.</li>
     * </ul>
     *
     * Условие оптимума: {@code δ* − c(δ*) = (1 − c′)/κ}, ширина плато примерно
     * {@code 0.9/κ}. Если {@code κ} или {@code c} поползли — это видно на ленте
     * за сутки, задолго до того, как проявится в деньгах.
     *
     * <h2>Что уже сбылось</h2>
     *
     * Док. 151 записал заранее: «полка 8.6–13.1 держится на {@code κ} = 0.203 со
     * стенда; если пересчитанная {@code κ} уйдёт за 0.3, полка сдвинется к 8–9».
     * В 170 {@code κ} перемерили трижды (0.385, 0.406, 0.315), и лента поставила
     * оптимум на 8–10. Предсказание по двум числам сбылось.
     *
     * ⚠️ Но честно: тот сдвиг — ИСПРАВЛЕНИЕ плохого замера {@code κ}, а не
     * изменение рынка. Теория говорит, за чем следить; сдвигаться за эти недели
     * было особенно нечему.
     *
     * ⚠️ И второе ограничение: формула считает стороны независимыми, а у бота,
     * который только держит лонг, они связаны через запас (док. 185). Это первое
     * приближение, а не последнее слово.
     */
    private void watch(StringBuilder sb, List<Hour> hours, double lot) {
        // ⚠️ Hour.hour() — НОМЕР часа от эпохи (ts_ms / 3 600 000), а не

        // миллисекунды. Первая версия делила на сутки по нему как по

        // миллисекундам и сложила всё окно в 1970-01-01.

        Map<String, List<Hour>> byDay = new java.util.TreeMap<>();
        for (Hour h : hours) {
            byDay.computeIfAbsent(java.time.Instant.ofEpochMilli(h.hour() * 3_600_000L)
                    .toString().substring(0, 10), k -> new ArrayList<>()).add(h);
        }
        sb.append("\n### Монитор плато по суткам: `κ`, `c` и оптимум\n\n");
        sb.append("| сутки | часов | событий | `κ` | `c(12)`, б.п. | `δ*` | ширина плато |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<Hour>> e : byDay.entrySet()) {
            List<double[]> ev = new ArrayList<>();
            for (Hour h : e.getValue()) {
                ev.addAll(h.events());
            }
            int nh = e.getValue().size();
            if (nh < 6 || ev.size() < 50) {
                continue;                       // неполные сутки на краю окна
            }
            double[] lam = new double[GRID.length];
            double[] c = new double[GRID.length];
            for (int i = 0; i < GRID.length; i++) {
                int n = 0;
                double sum = 0;
                for (double[] x : ev) {
                    if (x[0] >= GRID[i]) {
                        n++;
                        sum += x[1];
                    }
                }
                lam[i] = n / (double) nh;
                c[i] = n < 5 ? Double.NaN : sum / n;
            }
            double kappa = kappaOf(lam);
            // Оптимум — вершина ленточной прибыли λ(δ)·(δ − c(δ)).
            double best = Double.NaN;
            double bestProfit = Double.NEGATIVE_INFINITY;
            double c12 = Double.NaN;
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] == 12) {
                    c12 = c[i];
                }
                if (Double.isNaN(c[i])) {
                    continue;
                }
                double p = lam[i] * (GRID[i] - c[i]) * lot / 1e4;
                if (p > bestProfit) {
                    bestProfit = p;
                    best = GRID[i];
                }
            }
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(nh)
                    .append(" | ").append(ev.size())
                    .append(" | ").append(kappa > 0 ? round(kappa, 3) : "—")
                    .append(" | ").append(Double.isNaN(c12) ? "—" : round(c12, 2))
                    .append(" | ").append(Double.isNaN(best) ? "—" : (int) best)
                    .append(" | ").append(kappa > 0 ? round(0.9 / kappa, 1) : "—")
                    .append(" |\n");
        }
        sb.append("\n⚠️ Столбец `δ*` — вершина ЛЕНТОЧНОЙ прибыли, а не денег бота:")
                .append(" в ней нет ни цены запаса, ни связи сторон через него.")
                .append(" Следить надо за ДВИЖЕНИЕМ `κ` и `c`, а не за самим `δ*`:")
                .append(" при ширине плато в 2–4 б.п. соседние ступени неразличимы,")
                .append(" и вершина будет прыгать от шума.\n");
    }

    /** `κ` по наклону `ln λ` на рабочем участке 6–16 б.п.; 0 — не построилась. */
    private static double kappaOf(double[] lambda) {
        double sx = 0;
        double sy = 0;
        double sxy = 0;
        double sxx = 0;
        int n = 0;
        for (int i = 0; i < GRID.length; i++) {
            if (GRID[i] >= 6 && GRID[i] <= 16 && lambda[i] > 0) {
                double x = GRID[i];
                double y = Math.log(lambda[i]);
                sx += x;
                sy += y;
                sxy += x * y;
                sxx += x * x;
                n++;
            }
        }
        return n > 1 ? -(n * sxy - sx * sy) / (n * sxx - sx * sx) : 0;
    }

    private void structure(StringBuilder sb, List<double[]> ev, int nHours, double lot) {
        sb.append("\n### Структура кривой: `λ(δ)` и `c(δ)` порознь\n\n");
        sb.append("| δ | λ(δ), событий/ч | из них бид / аск | `c(δ)`, б.п. | `δ − c` | `c'(δ)` | прибыль, $/ч |\n");
        sb.append("|---:|---:|---|---:|---:|---:|---:|\n");
        double[] lambda = new double[GRID.length];
        double[] lambdaBid = new double[GRID.length];
        double[] lambdaAsk = new double[GRID.length];
        double[] c = new double[GRID.length];
        for (int i = 0; i < GRID.length; i++) {
            int n = 0;
            int nb = 0;
            double sum = 0;
            for (double[] x : ev) {
                if (x[0] >= GRID[i]) {
                    n++;
                    // 🔑 Сторона нужна для сверки конвенций (док. 171 часть IV):
                    // λ считает ОБЕ стороны, и сравнивать её надо с обеими же
                    // живыми сделками, а не с одной.
                    if (x[2 + 2 * HORIZONS.length] > 0) {
                        nb++;
                    }
                    sum += x[1];
                }
            }
            lambda[i] = n / (double) nHours;
            lambdaBid[i] = nb / (double) nHours;
            lambdaAsk[i] = (n - nb) / (double) nHours;
            c[i] = n < 5 ? Double.NaN : sum / n;
        }
        for (int i = 0; i < GRID.length; i++) {
            double deriv = Double.NaN;
            if (i > 0 && i < GRID.length - 1 && !Double.isNaN(c[i - 1]) && !Double.isNaN(c[i + 1])) {
                deriv = (c[i + 1] - c[i - 1]) / (GRID[i + 1] - GRID[i - 1]);
            }
            sb.append("| ").append((int) GRID[i])
                    .append(" | ").append(round(lambda[i], 2))
                    .append(" | ").append(round(lambdaBid[i], 2)).append(" / ")
                    .append(round(lambdaAsk[i], 2))
                    .append(" | ").append(Double.isNaN(c[i]) ? "—" : round(c[i], 2))
                    .append(" | ").append(Double.isNaN(c[i]) ? "—" : round(GRID[i] - c[i], 2))
                    .append(" | ").append(Double.isNaN(deriv) ? "—" : round(deriv, 2))
                    .append(" | ").append(Double.isNaN(c[i]) ? "—"
                            : money(lambda[i] * (GRID[i] - c[i]) * lot / 1e4))
                    .append(" |\n");
        }
        // κ по наклону ln λ на рабочем участке 6–16 б.п.
        double sx = 0;
        double sy = 0;
        double sxy = 0;
        double sxx = 0;
        int n = 0;
        for (int i = 0; i < GRID.length; i++) {
            if (GRID[i] >= 6 && GRID[i] <= 16 && lambda[i] > 0) {
                double x = GRID[i];
                double y = Math.log(lambda[i]);
                sx += x;
                sy += y;
                sxy += x * y;
                sxx += x * x;
                n++;
            }
        }
        double kappa = n > 1 ? -(n * sxy - sx * sy) / (n * sxx - sx * sx) : 0;
        sb.append("\n`κ` по наклону `ln λ` на участке 6–16 б.п.: **").append(round(kappa, 3))
                .append("** на базисный пункт.\n");
        sides(sb, ev, nHours, lot);
    }

    /**
     * АСИММЕТРИЯ СТОРОН ОТДЕЛЬНО В РАСТУЩИХ И ПАДАЮЩИХ ЧАСАХ (док. 175 часть II).
     *
     * 🔑 Зачем. «Бид платит хуже аска» снято на ПАДАЮЩЕМ окне, а на падающем
     * рынке всякий, кто продаёт нам, прав задним числом. Асимметрия тогда
     * возникает из направления окна, даже если никакой осведомлённости нет.
     * Решающая клетка одна: РАСТУЩИЕ часы внутри того же окна. Если и там бид
     * хуже — асимметрия структурная, свойство площадки. Если картина
     * переворачивается — это тренд, и пользоваться нечем.
     *
     * ⚠️ Отбор берётся ЗА ВЫЧЕТОМ ПЛАЦЕБО (тот же знак, ход за три часа до
     * принта): без этого в растущих часах у аска окажется «выигрыш», который на
     * деле просто рост.
     *
     * ⚠️ Ошибка кластерная по часу: события внутри часа несут один рынок.
     */
    private void sidesBySign(StringBuilder sb, List<Hour> hours, TapeData.Fair fair) {
        sb.append("\n### Асимметрия сторон по знаку часа (с плацебо)\n\n");
        sb.append("| часы | δ | `c` бид | `c` аск | **разность** | `SE` | **`t`** | событий бид / аск |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (int up = 1; up >= 0; up--) {
            for (double delta : new double[]{8, 10, 12}) {
                List<Double> perHour = new ArrayList<>();
                double sBid = 0;
                double sAsk = 0;
                int nBid = 0;
                int nAsk = 0;
                for (Hour h : hours) {
                    double f0 = fair.at(h.hour() * 3_600_000L);
                    double f1 = fair.at((h.hour() + 1) * 3_600_000L);
                    if (f0 <= 0 || f1 <= 0 || (f1 > f0) != (up == 1)) {
                        continue;
                    }
                    double hb = 0;
                    double ha = 0;
                    int cb = 0;
                    int ca = 0;
                    for (double[] x : h.events()) {
                        if (x[0] < delta) {
                            continue;
                        }
                        // Чистый отбор на 60 с: маркаут минус плацебо того же знака.
                        double net = x[2 + 1] - x[2 + HORIZONS.length + 1];
                        if (Double.isNaN(net)) {
                            continue;
                        }
                        if (x[2 + 2 * HORIZONS.length] > 0) {
                            hb += net;
                            cb++;
                        } else {
                            ha += net;
                            ca++;
                        }
                    }
                    sBid += hb;
                    nBid += cb;
                    sAsk += ha;
                    nAsk += ca;
                    if (cb > 0 && ca > 0) {
                        perHour.add(hb / cb - ha / ca);
                    }
                }
                if (perHour.size() < 10) {
                    continue;
                }
                double mean = 0;
                for (double v : perHour) {
                    mean += v;
                }
                mean /= perHour.size();
                double ss = 0;
                for (double v : perHour) {
                    ss += (v - mean) * (v - mean);
                }
                double se = Math.sqrt(ss / (perHour.size() - 1.0) / perHour.size());
                sb.append("| ").append(up == 1 ? "растущие" : "падающие")
                        .append(" | ").append((int) delta)
                        .append(" | ").append(nBid > 0 ? round(sBid / nBid, 2) : "—")
                        .append(" | ").append(nAsk > 0 ? round(sAsk / nAsk, 2) : "—")
                        .append(" | **").append(round(mean, 2))
                        .append("** | ").append(round(se, 2))
                        .append(" | **").append(round(se > 0 ? mean / se : 0, 2))
                        .append("** | ").append(nBid).append(" / ").append(nAsk).append(" |\n");
            }
        }
        sb.append("\nКритерий 175: асимметрия структурна, если в РАСТУЩИХ часах")
                .append(" `c` бида выше `c` аска при `t` ≥ 2.\n");
    }

    /**
     * ТА ЖЕ КРИВАЯ, НО ОТДЕЛЬНО ПО СТОРОНАМ (док. 173 часть V).
     *
     * 🔑 Зачем. Живой бот стоит НЕ симметрично: скос держит бид на эффективных
     * 8.9 б.п., а аск на 13.0 (замер A46). Значит «перейти на δ = 10» — это не
     * одно действие, а два разных: бид уже почти на оптимуме, а аск широк. Но
     * прочитать это можно только из кривой, посчитанной по сторонам порознь:
     * поток тейкеров-покупателей толще, и у сторон разные и {@code λ}, и
     * {@code c}.
     *
     * ⚠️ «Бид» здесь — события, в которых агрессор ПРОДАЁТ (значит исполнился бы
     * наш бид), «аск» — наоборот.
     */
    private void sides(StringBuilder sb, List<double[]> ev, int nHours, double lot) {
        sb.append("\n### Кривая по сторонам\n\n");
        sb.append("| δ | бид: λ / `c` / прибыль | аск: λ / `c` / прибыль |\n");
        sb.append("|---:|---|---|\n");
        int bidBest = -1;
        int askBest = -1;
        double bidTop = 0;
        double askTop = 0;
        for (int i = 0; i < GRID.length; i++) {
            double[] out = new double[6];   // {λ, c, прибыль} × 2 стороны
            for (int side = 0; side < 2; side++) {
                int n = 0;
                double sum = 0;
                for (double[] x : ev) {
                    boolean isBid = x[2 + 2 * HORIZONS.length] > 0;
                    if (x[0] >= GRID[i] && (isBid == (side == 0))) {
                        n++;
                        sum += x[1];
                    }
                }
                double lambda = n / (double) nHours;
                double c = n < 5 ? Double.NaN : sum / n;
                out[side * 3] = lambda;
                out[side * 3 + 1] = c;
                out[side * 3 + 2] = Double.isNaN(c) ? Double.NaN
                        : lambda * (GRID[i] - c) * lot / 1e4;
            }
            if (!Double.isNaN(out[2]) && (bidBest < 0 || out[2] > bidTop)) {
                bidTop = out[2];
                bidBest = i;
            }
            if (!Double.isNaN(out[5]) && (askBest < 0 || out[5] > askTop)) {
                askTop = out[5];
                askBest = i;
            }
            sb.append("| ").append((int) GRID[i]).append(" | ")
                    .append(round(out[0], 2)).append(" / ")
                    .append(Double.isNaN(out[1]) ? "—" : round(out[1], 2)).append(" / ")
                    .append(Double.isNaN(out[2]) ? "—" : money(out[2])).append(" | ")
                    .append(round(out[3], 2)).append(" / ")
                    .append(Double.isNaN(out[4]) ? "—" : round(out[4], 2)).append(" / ")
                    .append(Double.isNaN(out[5]) ? "—" : money(out[5])).append(" |\n");
        }
        if (bidBest >= 0 && askBest >= 0) {
            sb.append("\nОптимум по ленте: **бид ").append((int) GRID[bidBest])
                    .append(" б.п.** (").append(money(bidTop)).append(" $/ч), **аск ")
                    .append((int) GRID[askBest]).append(" б.п.** (").append(money(askTop))
                    .append(" $/ч). Живые ЭФФЕКТИВНЫЕ отступы — 8.9 и 13.0 (A46).\n");
        }
    }

    /**
     * П2.8: КАК ОТБОР РАСТЁТ С ГОРИЗОНТОМ. Потолок конструкции равен захвату
     * минус отбор на ТОМ горизонте, на котором позиция реально выравнивается.
     */
    private void horizons(StringBuilder sb, List<double[]> ev, int nHours) {
        sb.append("\n### П2.8: `c(δ, горизонт)` — на чём стоит потолок\n\n");
        sb.append("Клетка — `отбор минус плацебо` (плацебо: тот же знак, ход за три часа")
                .append(" до принта; без него дальние горизонты меряют снос рынка).\n\n");
        sb.append("| δ | событий/ч |");
        for (String h : HORIZON_NAMES) {
            sb.append(' ').append(h).append(" |");
        }
        sb.append(" рост 1 с → 30 мин | плацебо на 2 ч |\n|---:|---:|");
        for (int i = 0; i < HORIZON_NAMES.length; i++) {
            sb.append("---:|");
        }
        sb.append("---:|---:|\n");
        for (double delta : new double[]{6, 8, 10, 12, 16}) {
            int cnt = 0;
            double[] sum = new double[HORIZONS.length];
            double[] plac = new double[HORIZONS.length];
            int[] n = new int[HORIZONS.length];
            int[] np = new int[HORIZONS.length];
            for (double[] x : ev) {
                if (x[0] < delta) {
                    continue;
                }
                cnt++;
                for (int h = 0; h < HORIZONS.length; h++) {
                    if (!Double.isNaN(x[2 + h])) {
                        sum[h] += x[2 + h];
                        n[h]++;
                    }
                    if (!Double.isNaN(x[2 + HORIZONS.length + h])) {
                        plac[h] += x[2 + HORIZONS.length + h];
                        np[h]++;
                    }
                }
            }
            if (cnt < 20) {
                continue;
            }
            double[] net = new double[HORIZONS.length];
            for (int h = 0; h < HORIZONS.length; h++) {
                net[h] = n[h] < 5 ? Double.NaN
                        : sum[h] / n[h] - (np[h] < 5 ? 0 : plac[h] / np[h]);
            }
            sb.append("| ").append((int) delta).append(" | ")
                    .append(round(cnt / (double) nHours, 2)).append(" |");
            for (int h = 0; h < HORIZONS.length; h++) {
                sb.append(' ').append(Double.isNaN(net[h]) ? "—" : round(net[h], 2)).append(" |");
            }
            sb.append(' ').append(Double.isNaN(net[0]) || Double.isNaN(net[4])
                            ? "—" : round(net[4] - net[0], 2))
                    .append(" | ").append(np[5] < 5 ? "—"
                            : round(plac[5] / np[5], 2)).append(" |\n");
        }
        sb.append("\n⚠️ Уровень `c` по ленте и по своим исполнениям различается (лента не знает")
                .append(" очереди и инвентаря) — читать надо ФОРМУ роста, а не уровень.\n");
    }

    /** Ширина полосы вокруг пика, где прибыль не ниже 90% пиковой, б.п. */
    private static double plateauWidth(double[] profit, int best) {
        double target = profit[best] * 0.9;
        int lo = best;
        int hi = best;
        while (lo > 0 && !Double.isNaN(profit[lo - 1]) && profit[lo - 1] >= target) {
            lo--;
        }
        while (hi < profit.length - 1 && !Double.isNaN(profit[hi + 1]) && profit[hi + 1] >= target) {
            hi++;
        }
        return GRID[hi] - GRID[lo];
    }

    /** Границы классов: равнонаполненные по числу часов. */
    private static double[] quantiles(double[] values, int classes) {
        double[] v = values.clone();
        Arrays.sort(v);
        double[] cuts = new double[classes - 1];
        for (int i = 1; i < classes; i++) {
            cuts[i - 1] = v[(int) Math.round(i * v.length / (double) classes) - 1];
        }
        return cuts;
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.4f", v);
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
