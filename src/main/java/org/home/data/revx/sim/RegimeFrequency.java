package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Как часто рынок бывает в режиме, ради которого куплена страховка.
 *
 * Постановка родилась из отрицательного результата. Гейт по режиму провалился не
 * по реализации, а по существу: он классифицирует по прошлому то, что
 * определяется будущим — «вернётся ли цена» наблюдаемо только постфактум
 * (док. 106 §5, док. 107 §2). Но если предсказывать режим нельзя, то посчитать
 * ЧАСТОТУ режима — можно, и для этого не нужна книга заявок вовсе.
 *
 * Восемь суток данных стенда не отвечают на вопрос о многодневных просадках ни
 * при каком классификаторе. Дневные свечи с 2019 года — отвечают.
 *
 * Здесь только арифметика по ряду закрытий: ни базы, ни конфига, ни ввода-вывода.
 * Всё, что зависит от источника данных, живёт в вызывающем коде.
 */
public final class RegimeFrequency {

    /**
     * Эпизод просадки: от пика до возврата к нему.
     *
     * @param recoveredDays дни до возврата к пику; {@code -1} — не вернулась до
     *                      конца ряда
     */
    public record Episode(int startIndex, double depthPct, int daysToTrough, int recoveredDays) {

        public boolean recoveredWithin(int days) {
            return recoveredDays >= 0 && recoveredDays <= days;
        }
    }

    /** Доля окон, попавших в корзину, и их число. */
    public record Bucket(String label, int windows, double share, double payoff) {

        public double contribution() {
            return share * payoff;
        }
    }

    private RegimeFrequency() {
    }

    /**
     * Все просадки глубже порога, от скользящего пика до возврата к нему.
     *
     * Эпизод открывается на новом пике и закрывается, когда цена его превысила.
     * Незакрытый последний эпизод возвращается с {@code recoveredDays = -1}: он
     * и есть самое интересное наблюдение, выбрасывать его нельзя.
     */
    public static List<Episode> drawdowns(double[] closes, double minDepthPct) {
        List<Episode> out = new ArrayList<>();
        if (closes.length < 2) {
            return out;
        }
        double peak = closes[0];
        int peakIndex = 0;
        double trough = closes[0];
        int troughIndex = 0;

        for (int i = 1; i < closes.length; i++) {
            if (closes[i] >= peak) {
                double depth = (peak - trough) / peak * 100;
                if (depth >= minDepthPct) {
                    out.add(new Episode(peakIndex, depth, troughIndex - peakIndex, i - peakIndex));
                }
                peak = closes[i];
                peakIndex = i;
                trough = closes[i];
                troughIndex = i;
            } else if (closes[i] < trough) {
                trough = closes[i];
                troughIndex = i;
            }
        }
        double depth = (peak - trough) / peak * 100;
        if (depth >= minDepthPct) {
            out.add(new Episode(peakIndex, depth, troughIndex - peakIndex, -1));
        }
        return out;
    }

    /**
     * Раскладка скользящих окон длины {@code windowDays} по знаку и величине хода.
     *
     * Корзины подобраны так, чтобы соответствовать ТРЁМ ИЗМЕРЕННЫМ окнам стенда:
     * ралли (+16.45% за 5 суток), зеркальное падение (−14.12%) и боковик. Это
     * грубое соответствие — внутри «прочего» лежат и ±9% ходы, ничем не похожие
     * на наш боковик с ER = 0.022, — и потому итог ниже читается как порядок, а
     * не как оценка.
     *
     * @param payoffUp    измеренная выплата на растущем окне
     * @param payoffDown  измеренная выплата на падающем окне
     * @param payoffFlat  измеренная выплата на боковике
     */
    public static List<Bucket> buckets(double[] closes, int windowDays, double edgePct,
                                       double payoffUp, double payoffDown, double payoffFlat) {
        int up = 0;
        int down = 0;
        int flat = 0;
        for (int i = 0; i + windowDays < closes.length; i++) {
            double ret = (closes[i + windowDays] - closes[i]) / closes[i] * 100;
            if (ret >= edgePct) {
                up++;
            } else if (ret <= -edgePct) {
                down++;
            } else {
                flat++;
            }
        }
        int total = up + down + flat;
        if (total == 0) {
            return List.of();
        }
        return List.of(
                new Bucket("рост ≥ +" + edgePct + "%", up, (double) up / total, payoffUp),
                new Bucket("падение ≤ −" + edgePct + "%", down, (double) down / total, payoffDown),
                new Bucket("прочее", flat, (double) flat / total, payoffFlat));
    }

    /** Ожидание на окно: сумма вкладов корзин. */
    public static double expectation(List<Bucket> buckets) {
        return buckets.stream().mapToDouble(Bucket::contribution).sum();
    }

    /**
     * Доля ДНЕЙ, проведённых в просадках, которые не отыгрались за горизонт.
     *
     * Именно эта величина отвечает на вопрос страховки: не «как часто случается
     * падение», а «какую долю времени мы сидим в падении, которое не вернётся
     * достаточно быстро».
     */
    public static double shareOfDaysInUnrecovered(double[] closes, double minDepthPct,
                                                  int horizonDays) {
        int days = 0;
        for (Episode e : drawdowns(closes, minDepthPct)) {
            if (!e.recoveredWithin(horizonDays)) {
                days += e.recoveredDays() < 0
                        ? closes.length - e.startIndex()
                        : e.recoveredDays();
            }
        }
        return closes.length == 0 ? 0 : (double) days / closes.length;
    }
}
