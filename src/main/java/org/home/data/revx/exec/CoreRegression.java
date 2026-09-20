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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ЯДРО КОНСТРУКЦИИ: {@code m = a + b·Δp + g·|Δp|}. Команда {@code --revx-core},
 * пункт П2.4 протокола (док. 165 часть III).
 *
 * <h2>Что за вопрос</h2>
 *
 * Тождество переноски раскладывает результат на {@code захват + бета среднего
 * запаса + время под позицией}. Хедж убирает ковариационный член целиком, значит
 * потолок идеального хеджа равен захвату. Это число уже известно вычитанием
 * (+$0.058 на бота в сутки), но у вычитания нет ошибки: непонятно, отличимо ли
 * оно от нуля. Регрессия даёт то же число С ОШИБКОЙ:
 *
 * <ul>
 *   <li>{@code a} — деньги в час при нулевом ходе И нулевом модуле хода. Это и
 *       есть ядро, то есть потолок хеджа;</li>
 *   <li>{@code b} — бета: линейный отклик на ход рынка. Хедж убирает её целиком;</li>
 *   <li>{@code g} — вогнутость: отклик на МОДУЛЬ хода, ожидается отрицательным.
 *       Хедж убирает её частично.</li>
 * </ul>
 *
 * <h2>Почему только на объединённых окнах</h2>
 *
 * ⚠️ На падающем окне почти все часы имеют {@code Δp < 0}, там {@code |Δp| = −Δp},
 * регрессоры вырождены и {@code a} не определяется вовсе. Вторую ветвь даёт
 * растущее окно. Прибор печатает VIF: если он выше 5, разделения нет и читать
 * {@code a} нельзя.
 *
 * <h2>Наблюдение — БОТО-ЧАС, а ошибка кластерная</h2>
 *
 * 🔑 Строка наблюдения — один бот за один час, и {@code Δp} берётся по ЕГО паре:
 * боты торгуют BTC, ETH и SOL, и усреднять их ход в одну колонку значит смешивать
 * три разных рынка. Но внутри часа боты не независимы (общий рынок, общая
 * инфраструктура), поэтому ошибка считается КЛАСТЕРНОЙ по часу — иначе она
 * занижена примерно в корень из числа ботов.
 *
 * <h2>Вход</h2>
 *
 * Часовые строки из {@code --revx-hedge --hours-out=} (одна выгрузка на окно;
 * здесь они объединяются). Колонки: {@code бот;пара;час;без_хеджа;с_хеджем;
 * опора0;опора1}.
 */
@Component
@Lazy
public class CoreRegression {

    private static final Logger log = LoggerFactory.getLogger(CoreRegression.class);

    /** Одна строка наблюдения: бот-час. */
    private record Row(String window, String bot, String base, long hour,
                       double plain, double hedged, double movePct,
                       double capture, double fills) {
    }

    /** Оценка одной регрессии. */
    private record Fit(double[] beta, double[] se, double[] seCluster, int n, int clusters,
                       double vifMove, double vifAbs, double meanAbsMove) {
    }

    public void run(String hourFiles, String out) {
        List<Row> rows = new ArrayList<>();
        for (String spec : hourFiles.split(",")) {
            String[] kv = spec.split("=", 2);
            String window = kv.length == 2 ? kv[0].trim() : "окно" + (rows.isEmpty() ? "A" : "B");
            String path = (kv.length == 2 ? kv[1] : kv[0]).trim();
            int before = rows.size();
            try {
                for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
                    String[] p = line.split(";");
                    if (p.length < 7 || p[0].startsWith("бот")) {
                        continue;
                    }
                    double f0 = Double.parseDouble(p[5]);
                    double f1 = Double.parseDouble(p[6]);
                    if (f0 <= 0) {
                        continue;
                    }
                    rows.add(new Row(window, p[0], p[1], Long.parseLong(p[2]),
                            Double.parseDouble(p[3]), Double.parseDouble(p[4]),
                            100.0 * (f1 - f0) / f0,
                            p.length > 7 ? Double.parseDouble(p[7]) : Double.NaN,
                            p.length > 8 ? Double.parseDouble(p[8]) : Double.NaN));
                }
            } catch (IOException e) {
                log.warn("не прочитать {}: {}", path, e.toString());
            }
            log.info("{}: строк {}", window, rows.size() - before);
        }
        if (rows.size() < 30) {
            log.warn("строк мало ({}) — регрессия не считается", rows.size());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# П2.4: ядро конструкции регрессией\n\n");
        sb.append("`m = a + b·Δp + g·|Δp|`, наблюдение — бот-час, `Δp` в процентах")
                .append(" по СВОЕЙ паре, ошибка кластерная по часу.\n\n");

        // Состав выборки — читать до коэффициентов.
        sb.append("## Из чего считается\n\n| окно | бот-часов | часов | ботов | средний |Δp|, % | доля часов с Δp > 0 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        Map<String, List<Row>> byWindow = new LinkedHashMap<>();
        for (Row r : rows) {
            byWindow.computeIfAbsent(r.window(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Row>> e : byWindow.entrySet()) {
            List<Row> rs = e.getValue();
            double up = rs.stream().filter(r -> r.movePct() > 0).count() / (double) rs.size();
            double absMove = rs.stream().mapToDouble(r -> Math.abs(r.movePct())).average().orElse(0);
            sb.append("| ").append(e.getKey()).append(" | ").append(rs.size())
                    .append(" | ").append(rs.stream().map(Row::hour).distinct().count())
                    .append(" | ").append(rs.stream().map(Row::bot).distinct().count())
                    .append(" | ").append(round(absMove, 4))
                    .append(" | ").append(round(up, 3)).append(" |\n");
        }

        sb.append("\n## Коэффициенты\n\n");
        sb.append("| выборка | ряд | `a`, $/бот/ч | `SE(a)` кластерная | **`t(a)`** | **`a` × 24, $/бот/сут**")
                .append(" | `b` | `t(b)` | `g` | `t(g)` | VIF |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");

        List<String> samples = new ArrayList<>(byWindow.keySet());
        samples.add("ОБЪЕДИНЁННО");
        Fit joint = null;
        for (String s : samples) {
            List<Row> rs = "ОБЪЕДИНЁННО".equals(s) ? rows : byWindow.get(s);
            for (boolean hedged : new boolean[]{false, true}) {
                Fit f = fit(rs, hedged);
                if (f == null) {
                    continue;
                }
                if ("ОБЪЕДИНЁННО".equals(s) && !hedged) {
                    joint = f;
                }
                sb.append("| ").append(s).append(" | ").append(hedged ? "с хеджем" : "без хеджа")
                        .append(" | ").append(money(f.beta()[0]))
                        .append(" | ").append(money(f.seCluster()[0]))
                        .append(" | **").append(round(t(f, 0), 2))
                        .append("** | ").append(money(f.beta()[0] * 24))
                        .append(" | ").append(money(f.beta()[1]))
                        .append(" | ").append(round(t(f, 1), 2))
                        .append(" | ").append(money(f.beta()[2]))
                        .append(" | ").append(round(t(f, 2), 2))
                        .append(" | ").append(round(Math.max(f.vifMove(), f.vifAbs()), 1))
                        .append(" |\n");
            }
        }

        sb.append(captureSection(rows, byWindow));

        if (joint != null) {
            sb.append("\n## Как это читать\n\n");
            sb.append("- ядро `a` = **").append(money(joint.beta()[0] * 24))
                    .append(" $/бот/сут**, `t` = ").append(round(t(joint, 0), 2))
                    .append(", кластерная ошибка ").append(money(joint.seCluster()[0] * 24))
                    .append(" $/бот/сут;\n");
            sb.append("- вогнутость `g` = ").append(money(joint.beta()[2]))
                    .append(" $ на процент модуля хода, `t` = ").append(round(t(joint, 2), 2))
                    .append(";\n");
            sb.append("- VIF ").append(round(Math.max(joint.vifMove(), joint.vifAbs()), 1))
                    .append(" (выше 5 — ветви не разделены, `a` читать нельзя);\n");
            sb.append("- обычная ошибка без кластеров ").append(money(joint.se()[0]))
                    .append(" против кластерной ").append(money(joint.seCluster()[0]))
                    .append(" — отношение ")
                    .append(round(joint.seCluster()[0] / Math.max(joint.se()[0], 1e-12), 2))
                    .append(".\n");
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

    /**
     * ЗАХВАТ НАПРЯМУЮ и проверка того, законна ли экстраполяция регрессии.
     *
     * 🔑 Потолок идеального хеджа — это захват по следующему тику, и его не надо
     * ни экстраполировать, ни моделировать: он просто измеряется. Средний захват
     * за бот-час с кластерной ошибкой — самая прямая оценка ядра, какая вообще
     * возможна.
     *
     * ⚠️ И тут же проверка свободного члена: {@code захват = c₀ + c₁·|Δp|}. Если
     * {@code c₀} близок к нулю, значит захват целиком берётся из движения (нет
     * хода — нет исполнений), и свободный член ПОЛНОЙ регрессии описывает режим,
     * которого не бывает. Тогда читать надо среднее, а не пересечение.
     */
    private String captureSection(List<Row> rows, Map<String, List<Row>> byWindow) {
        if (rows.stream().anyMatch(r -> Double.isNaN(r.capture()))) {
            return "\n⚠️ в выгрузке нет колонок захвата — раздел пропущен"
                    + " (перевыгрузить `--revx-hedge --hours-out=`).\n";
        }
        StringBuilder sb = new StringBuilder("\n## Захват напрямую — потолок идеального хеджа\n\n");
        sb.append("| выборка | бот-часов | захват, $/бот/сут | кластерная `SE` | **`t`**")
                .append(" | сделок в час | захват при |Δp| = 0 (`c₀`), $/бот/сут | `c₁` |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        List<String> samples = new ArrayList<>(byWindow.keySet());
        samples.add("ОБЪЕДИНЁННО");
        for (String s : samples) {
            List<Row> rs = "ОБЪЕДИНЁННО".equals(s) ? rows : byWindow.get(s);
            // Среднее с кластерной по часу ошибкой: часы — независимые единицы.
            TreeMap<Long, double[]> byHour = new TreeMap<>();
            for (Row r : rs) {
                double[] acc = byHour.computeIfAbsent(r.hour(), k -> new double[2]);
                acc[0] += r.capture();
                acc[1]++;
            }
            double[] perHour = byHour.values().stream()
                    .mapToDouble(v -> v[0] / v[1]).toArray();
            double mean = 0;
            for (double v : perHour) {
                mean += v;
            }
            mean /= perHour.length;
            double ss = 0;
            for (double v : perHour) {
                ss += (v - mean) * (v - mean);
            }
            double se = Math.sqrt(ss / (perHour.length - 1.0) / perHour.length);
            // Регрессия захвата на модуль хода — две колонки, считаю вручную.
            double sx = 0;
            double sy = 0;
            for (Row r : rs) {
                sx += Math.abs(r.movePct());
                sy += r.capture();
            }
            double mx = sx / rs.size();
            double my = sy / rs.size();
            double sxy = 0;
            double sxx = 0;
            for (Row r : rs) {
                sxy += (Math.abs(r.movePct()) - mx) * (r.capture() - my);
                sxx += (Math.abs(r.movePct()) - mx) * (Math.abs(r.movePct()) - mx);
            }
            double c1 = sxx > 0 ? sxy / sxx : 0;
            double c0 = my - c1 * mx;
            sb.append("| ").append(s).append(" | ").append(rs.size())
                    .append(" | **").append(money(mean * 24)).append("**")
                    .append(" | ").append(money(se * 24))
                    .append(" | **").append(round(se > 0 ? mean / se : 0, 2)).append("**")
                    .append(" | ").append(round(rs.stream().mapToDouble(Row::fills).average().orElse(0), 2))
                    .append(" | ").append(money(c0 * 24))
                    .append(" | ").append(money(c1))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static double t(Fit f, int j) {
        double se = f.seCluster()[j];
        return se <= 0 ? 0 : f.beta()[j] / se;
    }

    /** МНК с кластерной по часу ошибкой и VIF. */
    private Fit fit(List<Row> rows, boolean hedged) {
        int n = rows.size();
        if (n < 20) {
            return null;
        }
        double[][] x = new double[n][3];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            x[i] = new double[]{1, r.movePct(), Math.abs(r.movePct())};
            y[i] = hedged ? r.hedged() : r.plain();
        }
        double[][] xtx = new double[3][3];
        double[] xty = new double[3];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 3; j++) {
                xty[j] += x[i][j] * y[i];
                for (int k = 0; k < 3; k++) {
                    xtx[j][k] += x[i][j] * x[i][k];
                }
            }
        }
        double[][] inv = invert3(xtx);
        if (inv == null) {
            // ⚠️ Полное вырождение (все часы одного знака): свободный член не
            // определяется вовсе. Прибор обязан это ПОКАЗАТЬ, а не промолчать.
            double[] nan = {Double.NaN, Double.NaN, Double.NaN};
            return new Fit(nan, nan, nan, n, 0, 1e6, 1e6,
                    rows.stream().mapToDouble(r -> Math.abs(r.movePct())).average().orElse(0));
        }
        double[] beta = mul(inv, xty);
        double[] u = new double[n];
        double ss = 0;
        for (int i = 0; i < n; i++) {
            u[i] = y[i] - (beta[0] * x[i][0] + beta[1] * x[i][1] + beta[2] * x[i][2]);
            ss += u[i] * u[i];
        }
        double sigma2 = ss / (n - 3.0);
        double[] se = new double[3];
        for (int j = 0; j < 3; j++) {
            se[j] = Math.sqrt(Math.max(sigma2 * inv[j][j], 0));
        }
        // Кластерная ошибка: сэндвич по часам.
        TreeMap<Long, double[]> sums = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            double[] acc = sums.computeIfAbsent(rows.get(i).hour(), k -> new double[3]);
            for (int j = 0; j < 3; j++) {
                acc[j] += x[i][j] * u[i];
            }
        }
        double[][] meat = new double[3][3];
        for (double[] acc : sums.values()) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    meat[j][k] += acc[j] * acc[k];
                }
            }
        }
        int g = sums.size();
        double adj = g <= 1 ? 1 : (g / (g - 1.0)) * ((n - 1.0) / (n - 3.0));
        double[][] vc = mul(mul(inv, meat), inv);
        double[] seC = new double[3];
        for (int j = 0; j < 3; j++) {
            seC[j] = Math.sqrt(Math.max(adj * vc[j][j], 0));
        }
        return new Fit(beta, se, seC, n, g, vif(x, 1), vif(x, 2),
                rows.stream().mapToDouble(r -> Math.abs(r.movePct())).average().orElse(0));
    }

    /**
     * VIF колонки {@code j}: регрессия этой колонки на константу и вторую.
     *
     * ⚠️ Ровно то, что ломает замер на одном падающем окне: там {@code |Δp|} и
     * {@code Δp} — одна и та же переменная с точностью до знака.
     */
    private static double vif(double[][] x, int j) {
        int other = j == 1 ? 2 : 1;
        int n = x.length;
        double sx = 0;
        double sy = 0;
        for (double[] r : x) {
            sx += r[other];
            sy += r[j];
        }
        double mx = sx / n;
        double my = sy / n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (double[] r : x) {
            sxy += (r[other] - mx) * (r[j] - my);
            sxx += (r[other] - mx) * (r[other] - mx);
            syy += (r[j] - my) * (r[j] - my);
        }
        if (sxx <= 0 || syy <= 0) {
            return 1;
        }
        double r2 = sxy * sxy / (sxx * syy);
        return r2 >= 0.999999 ? 1e6 : 1 / (1 - r2);
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    r[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return r;
    }

    private static double[] mul(double[][] a, double[] v) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                r[i] += a[i][k] * v[k];
            }
        }
        return r;
    }

    /** Обращение 3×3 методом Гаусса — Жордана; при вырождении {@code null}. */
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

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.4f", v);
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
