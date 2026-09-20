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
            byHour.computeIfAbsent(p.tsMs() / 3_600_000L, k -> new ArrayList<>())
                    .add(new double[]{dist, markout});
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
