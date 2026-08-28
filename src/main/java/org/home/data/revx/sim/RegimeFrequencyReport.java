package org.home.data.revx.sim;

import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Частота режимов по дневным свечам (док. 107 §4).
 *
 * Отдельная команда, а не раздел отчёта симуляции, по существенной причине:
 * **книга заявок здесь не нужна вовсе**. Восемь суток данных стенда не отвечают
 * на вопрос о многодневных просадках ни при каком классификаторе, а семь лет
 * дневных свечей отвечают — и они уже собраны для более ранних направлений.
 *
 * Выплаты подставляются измеренные (док. 104 §1), поэтому итог — ожидание на
 * реальном распределении режимов, а не на восьми сутках, где доля тренда была
 * оценкой порядка и решала знак вывода.
 */
@Component
public class RegimeFrequencyReport {

    private static final Logger log = LoggerFactory.getLogger(RegimeFrequencyReport.class);

    private static final String SELECT_CLOSES = """
            SELECT open_time, close FROM candles
            WHERE symbol = ? AND interval = '1d'
            ORDER BY open_time
            """;

    private final Db db;

    public RegimeFrequencyReport(Db db) {
        this.db = db;
    }

    /**
     * @param payoffUp   выплата на растущем окне (док. 104 §1, клетка β=178/η=2.8)
     * @param payoffDown выплата на падающем окне
     * @param payoffFlat выплата на боковике
     */
    public void run(String symbol, int windowDays, double edgePct,
                    double payoffUp, double payoffDown, double payoffFlat, String out) {
        record Row(long ts, double close) {
        }
        List<Row> rows = db.query(SELECT_CLOSES,
                rs -> new Row(rs.getLong("open_time"), rs.getDouble("close")), symbol);
        if (rows.size() < windowDays + 2) {
            log.warn("{}: дневных свечей всего {} — считать нечего", symbol, rows.size());
            return;
        }
        double[] closes = rows.stream().mapToDouble(Row::close).toArray();

        StringBuilder sb = new StringBuilder();
        sb.append("# Частота режимов по дневным свечам: ").append(symbol).append("\n\n");
        sb.append("Ответ на вопрос, который **не требует книги заявок** (док. 107 §4). ")
                .append("Классифицировать режим в момент решения нельзя — различающий ")
                .append("признак «вернётся ли цена» наблюдаем только постфактум ")
                .append("(док. 106 §5). Но посчитать, как ЧАСТО он наступает, можно, и ")
                .append("для этого достаточно дневных свечей.\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Свечей | ").append(rows.size()).append(" |\n");
        sb.append("| Период | ").append(day(rows.getFirst().ts()))
                .append(" — ").append(day(rows.getLast().ts())).append(" |\n");
        sb.append("| Окно классификации | ").append(windowDays).append(" сут |\n\n");

        sb.append("## Просадки от скользящего пика\n\n");
        sb.append("| Порог глубины | Эпизодов | Медиана глубины | Медиана дней до дна "
                + "| Медиана дней до возврата | Не вернулись за 5 сут |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (double depth : new double[]{5, 10, 20, 30}) {
            List<RegimeFrequency.Episode> episodes = RegimeFrequency.drawdowns(closes, depth);
            if (episodes.isEmpty()) {
                continue;
            }
            long unrecovered = episodes.stream().filter(e -> !e.recoveredWithin(windowDays)).count();
            sb.append("| ≥ ").append(round(depth, 0)).append("% | ").append(episodes.size())
                    .append(" | ").append(round(median(episodes.stream()
                            .mapToDouble(RegimeFrequency.Episode::depthPct).toArray()), 1)).append("%")
                    .append(" | ").append(round(median(episodes.stream()
                            .mapToDouble(e -> e.daysToTrough()).toArray()), 0))
                    .append(" | ").append(round(median(episodes.stream()
                            .filter(e -> e.recoveredDays() >= 0)
                            .mapToDouble(e -> e.recoveredDays()).toArray()), 0))
                    .append(" | **").append(unrecovered).append("** (")
                    .append(round(100.0 * unrecovered / episodes.size(), 0)).append("%) |\n");
        }
        sb.append("\n**Доля ДНЕЙ в невернувшихся просадках** — то, что и определяет цену ")
                .append("страховки, потому что важно не число эпизодов, а сколько времени ")
                .append("мы в них сидим:\n\n");
        sb.append("| Глубина | Доля дней |\n|---|---|\n");
        for (double depth : new double[]{5, 10, 20}) {
            sb.append("| ≥ ").append(round(depth, 0)).append("% | ")
                    .append(round(100 * RegimeFrequency.shareOfDaysInUnrecovered(
                            closes, depth, windowDays), 1)).append("% |\n");
        }

        sb.append("\n## Ожидание на реальном распределении режимов\n\n");
        sb.append("Выплаты подставлены ИЗМЕРЕННЫЕ (док. 104 §1, конфигурация β=178, η=2.8): ")
                .append("рост ").append(round(payoffUp, 1)).append(", падение ")
                .append(round(payoffDown, 1)).append(", прочее ").append(round(payoffFlat, 1))
                .append(".\n\n");
        sb.append("| Порог | Корзина | Окон | Доля | Выплата | Вклад |\n|---|---|---|---|---|---|\n");
        for (double edge : new double[]{edgePct / 2, edgePct, edgePct * 1.5}) {
            List<RegimeFrequency.Bucket> buckets =
                    RegimeFrequency.buckets(closes, windowDays, edge, payoffUp, payoffDown, payoffFlat);
            for (RegimeFrequency.Bucket b : buckets) {
                sb.append("| ±").append(round(edge, 1)).append("% | ").append(b.label())
                        .append(" | ").append(b.windows())
                        .append(" | ").append(round(100 * b.share(), 1)).append("%")
                        .append(" | ").append(round(b.payoff(), 1))
                        .append(" | ").append(round(b.contribution(), 1)).append(" |\n");
            }
            sb.append("| ±").append(round(edge, 1)).append("% | **ИТОГО** | | | | **")
                    .append(round(RegimeFrequency.expectation(buckets), 1)).append("** |\n");
        }
        sb.append("\n**Соответствие корзин измеренным окнам грубое.** Внутри «прочего» ")
                .append("лежат и ±9% ходы, ничем не похожие на наш боковик с ER = 0.022, ")
                .append("которому и принадлежит выплата ").append(round(payoffFlat, 1))
                .append(". Поэтому итог читается как ПОРЯДОК, а не как оценка: он ")
                .append("отвечает на вопрос «хватает ли частоты трендов в принципе», а не ")
                .append("«сколько именно заработаем».\n");

        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, sb.toString());
            log.info("{}: свечей {}, отчёт {}", symbol, rows.size(), out);
        } catch (Exception e) {
            log.error("не записался отчёт {}: {}", out, e.getMessage());
        }
    }

    private static String day(long tsMs) {
        return Instant.ofEpochMilli(tsMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String round(double v, int digits) {
        if (Double.isNaN(v)) {
            return "—";
        }
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v)
                .setScale(digits, java.math.RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }
}
