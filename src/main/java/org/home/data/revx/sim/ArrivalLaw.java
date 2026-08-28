package org.home.data.revx.sim;

import java.util.List;

/**
 * Закон прихода заявок {@code λ(δ) = A·e^{−κδ}} — допущение, на котором стоит
 * вся литература по маркет-мейкингу (Avellaneda–Stoikov, Guéant et al.).
 *
 * Оно эмпирическое и на большинстве площадок проверяется плохо. Наша лестница
 * отступа — это прямое его измерение: каждая ступень даёт пару «дистанция →
 * интенсивность», и подгонка отвечает, держится ли экспонента вообще.
 *
 * Практический смысл — не в проверке чужой теории, а в том, что подтверждённый
 * закон ПРЕДСКАЗЫВАЕТ число исполнений на непроверенных отступах. Лестницу на
 * каждую новую настройку гонять больше не нужно (док. 100 §3, §8 п.5).
 *
 * Подгонка идёт и по числу исполнений, и по обороту, и это разные ответы:
 * глубокие исполнения приходят от крупных выносов, поэтому средний филл РАСТЁТ
 * с дистанцией (док. 100 §4). Оптимизировать надо по обороту, и {@code κ} для
 * оборота заметно меньше — то есть оптимальный отступ шире, чем предсказал бы
 * учебник.
 */
public final class ArrivalLaw {

    /**
     * @param a        интенсивность при нулевой дистанции
     * @param kappa    скорость затухания, 1/доля цены
     * @param rSquared качество подгонки в логарифмах
     * @param points   сколько ступеней вошло
     */
    public record Fit(double a, double kappa, double rSquared, int points) {

        /** Предсказание интенсивности на произвольном отступе. */
        public double predict(double offset) {
            return a * Math.exp(-kappa * offset);
        }

        /**
         * Оптимальный отступ замкнутой формулой: {@code δ* = c + 1/κ}.
         *
         * {@code 1/κ} — учебничный ответ при нулевых издержках. Слагаемое {@code c}
         * — наша пошлина на исполнение (устаревание плюс неблагоприятный отбор),
         * которой в моделях нет вовсе: там прибыль с филла равна {@code δ}, а у нас
         * {@code δ − c}. Без неё формула даёт отступ, на котором наш измеренный край
         * отрицателен (док. 100 §4).
         *
         * @param costFraction пошлина {@code c} в долях цены
         */
        public double optimalOffset(double costFraction) {
            return kappa > 0 ? costFraction + 1.0 / kappa : Double.NaN;
        }

        /** Держится ли экспонента: ниже этого подгонку читать нельзя. */
        public boolean holds() {
            return points >= 4 && rSquared >= 0.9 && kappa > 0;
        }
    }

    /** Одна ступень: отступ в долях цены и наблюдённая интенсивность. */
    public record Rung(double offset, double intensity) {
    }

    private ArrivalLaw() {
    }

    /**
     * Подгонка методом наименьших квадратов В ЛОГАРИФМАХ: {@code ln λ = ln A − κδ}.
     *
     * Логарифмическая шкала здесь не удобство, а необходимость: интенсивности
     * различаются на порядок между краями лестницы, и обычный МНК подогнал бы
     * почти исключительно самую левую ступень.
     */
    public static Fit fit(List<Rung> rungs) {
        List<Rung> usable = rungs.stream().filter(r -> r.intensity() > 0).toList();
        if (usable.size() < 3) {
            return new Fit(0, 0, 0, usable.size());
        }
        double sx = 0, sy = 0;
        for (Rung r : usable) {
            sx += r.offset();
            sy += Math.log(r.intensity());
        }
        double mx = sx / usable.size();
        double my = sy / usable.size();
        double sxx = 0, sxy = 0, syy = 0;
        for (Rung r : usable) {
            double dx = r.offset() - mx;
            double dy = Math.log(r.intensity()) - my;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (!(sxx > 0)) {
            return new Fit(0, 0, 0, usable.size());
        }
        double slope = sxy / sxx;                    // = −κ
        double intercept = my - slope * mx;          // = ln A
        double r2 = syy > 0 ? (sxy * sxy) / (sxx * syy) : 0;
        return new Fit(Math.exp(intercept), -slope, r2, usable.size());
    }
}
