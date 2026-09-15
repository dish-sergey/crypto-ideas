package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * ПАРНЫЙ ОПЫТ ПО СОВПАДАЮЩИМ ОТРЕЗКАМ ВРЕМЕНИ. Команда
 * {@code --revx-paired --a=журнал --b=журнал --segment=минуты}.
 *
 * <h2>Зачем (док. 154 §VII, блок 5)</h2>
 *
 * Две ветки опыта торгуют ОДНУ И ТУ ЖЕ пару на одном и том же рынке. Значит
 * рыночный ход — общий член, и при сравнении СОВПАДАЮЩИХ отрезков времени он
 * сокращается. Это тот же член, который мерка из блока 1 вычитает безусловным
 * контролем, только здесь он уходит сам.
 *
 * Выигрыш в скорости решения — примерно вчетверо:
 *
 * <pre>
 *   различие на круг | несопряжённо | парно по отрезкам
 *   2 б.п.           | 109 суток    | 27 суток
 *   5 б.п.           | 17.5 суток   | 4.4 суток
 *  10 б.п.           | 4.4 суток    | 1.1 суток
 * </pre>
 *
 * <h2>Что считается</h2>
 *
 * Окно режется на отрезки. В каждом отрезке по каждой ветке считается сумма
 * результатов кругов и их среднее; отрезок идёт в счёт, только если в нём есть
 * круги у ОБЕИХ веток. Дальше усредняются РАЗНОСТИ, а не сравниваются суммы, и
 * печатается доверительный интервал на разность.
 *
 * <h2>⚠️ Круг принадлежит отрезку, только если ОБА конца внутри</h2>
 *
 * Разметка по моменту закрытия приписывает убыток моменту ФИКСАЦИИ, а не
 * создания: у бота A знак «тихого» режима от этого переворачивался (док. 153
 * §VIII.2). Круги, пережившие границу отрезка, не считаются ни там, ни там.
 *
 * <h2>⚠️ Две величины, и они отвечают на разные вопросы</h2>
 *
 * <ul>
 *   <li><b>на круг</b> — качество сделки. Ветка с более широким отступом
 *       выигрывает здесь почти всегда, и это ничего не значит: она просто
 *       реже торгует;</li>
 *   <li><b>за отрезок</b> — сумма, то есть качество × число сделок. Это и есть
 *       ответ на вопрос «какая настройка лучше», и решать надо по нему.</li>
 * </ul>
 */
public final class PairedDiff {

    private static final Logger log = LoggerFactory.getLogger(PairedDiff.class);

    private PairedDiff() {
    }

    /** Закрытый круг: время закрытия, время открытия, результат в б.п. */
    record Round(long openedMs, long closedMs, double bp) {
    }

    public static void run(String pathA, String pathB, String nameA, String nameB,
                           String fromIso, String toIso, int segmentMin, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0 : Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE : Instant.parse(toIso).toEpochMilli();
        List<Round> a = rounds(pathA, from, to);
        List<Round> b = rounds(pathB, from, to);
        StringBuilder sb = new StringBuilder("# Парный опыт по совпадающим отрезкам\n\n");
        sb.append("| ветка | журнал | кругов |\n|---|---|---:|\n");
        sb.append(String.format(Locale.ROOT, "| %s | `%s` | %d |%n", nameA, pathA, a.size()));
        sb.append(String.format(Locale.ROOT, "| %s | `%s` | %d |%n", nameB, pathB, b.size()));
        if (a.isEmpty() || b.isEmpty()) {
            sb.append("\nу одной из веток кругов нет — сравнивать нечего\n");
            write(out, sb.toString());
            return;
        }
        long lo = Math.max(Math.min(a.get(0).openedMs(), b.get(0).openedMs()), from);
        long hi = Math.min(Math.max(a.get(a.size() - 1).closedMs(), b.get(b.size() - 1).closedMs()),
                to == Long.MAX_VALUE ? Long.MAX_VALUE : to);
        long step = segmentMin * 60_000L;

        List<Double> diffSum = new ArrayList<>();
        List<Double> diffMean = new ArrayList<>();
        double sumA = 0;
        double sumB = 0;
        int roundsA = 0;
        int roundsB = 0;
        int paired = 0;
        for (long t = lo; t + step <= hi; t += step) {
            List<Double> ra = inSegment(a, t, t + step);
            List<Double> rb = inSegment(b, t, t + step);
            if (ra.isEmpty() || rb.isEmpty()) {
                continue;
            }
            paired++;
            roundsA += ra.size();
            roundsB += rb.size();
            double sa = ra.stream().mapToDouble(Double::doubleValue).sum();
            double sbm = rb.stream().mapToDouble(Double::doubleValue).sum();
            sumA += sa;
            sumB += sbm;
            diffSum.add(sa - sbm);
            diffMean.add(sa / ra.size() - sbm / rb.size());
        }
        sb.append(String.format(Locale.ROOT,
                "%nокно: %s .. %s, отрезок %d мин, отрезков с кругами у ОБЕИХ веток: %d%n",
                Instant.ofEpochMilli(lo), Instant.ofEpochMilli(hi), segmentMin, paired));
        // ⚠️ Круг, который длиннее отрезка, не помещается целиком ни в один и
        // выпадает из счёта. Это не мелочь: именно длинные круги — худшие, и
        // если их доля велика, парная разность меряет только быструю торговлю.
        sb.append(String.format(Locale.ROOT,
                "круги длиннее отрезка (в счёт не идут): %s %d из %d (медиана T %.0f мин),"
                        + " %s %d из %d (медиана T %.0f мин)%n",
                nameA, longer(a, step), a.size(), medianMinutes(a),
                nameB, longer(b, step), b.size(), medianMinutes(b)));
        if (paired < 5) {
            sb.append("\n⚠️ Совпадающих отрезков меньше пяти — на таком счёте разность не\n")
                    .append("отличима ни от чего. Либо окно короткое, либо отрезок мелкий.\n");
            write(out, sb.toString());
            log.info("\n{}", sb);
            return;
        }
        sb.append(String.format(Locale.ROOT,
                "кругов в парных отрезках: %s %d, %s %d%n", nameA, roundsA, nameB, roundsB));
        sb.append("\n| величина | ").append(nameA).append(" | ").append(nameB)
                .append(" | разность | ± ошибка | t |\n|---|---:|---:|---:|---:|---:|\n");
        sb.append(row("б.п. за отрезок (сумма)", sumA / paired, sumB / paired, diffSum));
        sb.append(row("б.п. на круг (среднее)",
                sumA / Math.max(1, roundsA), sumB / Math.max(1, roundsB), diffMean));
        double[] s = stats(diffSum);
        sb.append(String.format(Locale.ROOT,
                "%n**Решение по сумме за отрезок:** %s%n",
                Math.abs(s[2]) < 2
                        ? "различие НЕ установлено (|t| < 2) — ждать дальше"
                        : s[0] > 0 ? "лучше " + nameA + " (|t| ≥ 2)" : "лучше " + nameB + " (|t| ≥ 2)"));
        sb.append(String.format(Locale.ROOT,
                "При нынешнем разбросе для уверенного ответа нужно около %.0f отрезков"
                        + " (%.1f суток).%n",
                needed(s, paired), needed(s, paired) * segmentMin / 1440.0));
        sb.append("\n⚠️ Рыночный ход в разности сокращается только потому, что отрезки\n")
                .append("СОВПАДАЮТ по времени. Сравнение сумм за всё окно этого свойства не\n")
                .append("имеет и требует вчетверо больше данных.\n");
        sb.append("⚠️ Круги, пережившие границу отрезка, выброшены у ОБЕИХ веток. Чем мельче\n")
                .append("отрезок, тем больше доля выброшенного: при отрезке в час теряются все\n")
                .append("круги длиннее часа, а это худшие круги.\n");
        write(out, sb.toString());
        log.info("\n{}", sb);
    }

    private static String row(String name, double meanA, double meanB, List<Double> diff) {
        double[] s = stats(diff);
        return String.format(Locale.ROOT, "| %s | %+.2f | %+.2f | **%+.2f** | %.2f | %.2f |%n",
                name, meanA, meanB, s[0], s[1], s[2]);
    }

    /** {@code {среднее, ошибка среднего, t}}. */
    static double[] stats(List<Double> v) {
        if (v.size() < 2) {
            return new double[]{0, 0, 0};
        }
        double m = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        double sd = Math.sqrt(s / (v.size() - 1));
        double se = sd / Math.sqrt(v.size());
        return new double[]{m, se, se > 0 ? m / se : 0};
    }

    /**
     * Сколько отрезков нужно, чтобы НЫНЕШНЯЯ разность дала {@code |t| = 2}.
     *
     * Ошибка среднего падает как {@code 1/√n}, поэтому {@code t} растёт как
     * {@code √n}: {@code n_нужно = n_сейчас · (2/t_сейчас)²}. Это оценка
     * «сколько ещё ждать», а не обещание: если разности на самом деле нет,
     * {@code t} не вырастет никогда.
     */
    static double needed(double[] s, int now) {
        return Math.abs(s[2]) < 1e-9 ? Double.NaN : now * Math.pow(2 / Math.abs(s[2]), 2);
    }

    /** Сколько кругов длиннее отрезка — они не помещаются в него целиком. */
    private static int longer(List<Round> rounds, long step) {
        int n = 0;
        for (Round r : rounds) {
            if (r.closedMs() - r.openedMs() >= step) {
                n++;
            }
        }
        return n;
    }

    private static double medianMinutes(List<Round> rounds) {
        if (rounds.isEmpty()) {
            return 0;
        }
        List<Double> v = new ArrayList<>();
        for (Round r : rounds) {
            v.add((r.closedMs() - r.openedMs()) / 60_000.0);
        }
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    static List<Double> inSegment(List<Round> rounds, long from, long to) {
        List<Double> out = new ArrayList<>();
        for (Round r : rounds) {
            if (r.openedMs() >= from && r.closedMs() < to) {
                out.add(r.bp());
            }
        }
        return out;
    }

    /**
     * Круги журнала: FIFO-сопоставление ног, передачи в книгу входят, но пары с
     * их участием выброшены — то же правило, что в {@code --revx-hold-check}.
     */
    private static List<Round> rounds(String path, long from, long to) {
        List<ExecJournal.FillRow> fills;
        try (ExecJournal journal = ExecJournal.readOnly(path)) {
            fills = journal.fills();
        } catch (Exception e) {
            log.warn("журнал {}: {}", path, e.toString());
            return List.of();
        }
        List<Round> out = new ArrayList<>();
        Deque<double[]> lots = new ArrayDeque<>();     // {ts, qty, price, передача}
        for (ExecJournal.FillRow f : fills) {
            if (f.qty() <= 0 || f.price() <= 0) {
                continue;
            }
            boolean handover = f.handover();
            if (f.buy()) {
                lots.addLast(new double[]{f.tsMs(), f.qty(), f.price(), handover ? 1 : 0});
                continue;
            }
            double left = f.qty();
            while (left > 1e-15 && !lots.isEmpty()) {
                double[] lot = lots.peekFirst();
                double take = Math.min(left, lot[1]);
                lot[1] -= take;
                left -= take;
                if (lot[1] <= 1e-15) {
                    lots.pollFirst();
                }
                if (lot[3] == 0 && !handover && lot[2] > 0) {
                    long opened = (long) lot[0];
                    if (f.tsMs() >= from && f.tsMs() < to) {
                        out.add(new Round(opened, f.tsMs(),
                                (f.price() - lot[2]) / lot[2] * 10_000));
                    }
                }
            }
        }
        out.sort((x, y) -> Long.compare(x.closedMs(), y.closedMs()));
        return out;
    }

    private static void write(String out, String text) {
        if (out == null || out.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(out);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, text, StandardCharsets.UTF_8);
            log.warn("парный опыт записан в {}", p.toAbsolutePath());
        } catch (Exception e) {
            log.warn("не записалось в {}: {}", out, e.toString());
        }
    }
}
