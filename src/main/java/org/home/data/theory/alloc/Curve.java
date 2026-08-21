package org.home.data.theory.alloc;

/**
 * Кривая стратегии в едином формате ТЗ 65 §3.1: дневной шаг, доходность на
 * <b>задействованный</b> капитал, маска доступности, собственный оборот.
 *
 * <p>Ключевое (§3.1): {@code ret} определена на капитал, отданный этой стратегии
 * в этот день, а не на общий. Стратегия, активная 17% времени, при определении
 * «на общий капитал» выглядела бы как почти безрисковая с околонулевой
 * доходностью, и аллокатор дал бы ей большой вес.
 *
 * <p>{@code available[t] == false} означает: капитал, назначенный стратегии,
 * лежит в кэше (§3.2), а не что стратегия исключена из нормировки.
 *
 * @param id        идентификатор пула: S1, S5, BH_BTC, …
 * @param kind      честность источника кривой (§3.3) — попадает в отчёт
 * @param ret       доходность на задействованный капитал, по дням календаря
 * @param available могла ли стратегия принять капитал в этот день
 * @param turnover  собственный оборот стратегии за день, доля от её капитала
 *                  (справочно: издержки оборота уже вычтены из {@code ret}, §4.2)
 * @param note      как построена кривая и чем ограничена — идёт в отчёт дословно
 * @param survivorship вселенная кривой построена по СЕГОДНЯШНЕМУ составу (нет
 *                  PIT-снимков) — отдельная ось честности рядом с {@code kind}:
 *                  такая кривая завышена независимо от того, walk-forward она
 *                  или in-sample (правка ТЗ 65 §6.1 по док. 71)
 */
public record Curve(String id, CurveKind kind, double[] ret, boolean[] available,
                    double[] turnover, String note, boolean survivorship) {

    public Curve {
        if (ret.length != available.length || ret.length != turnover.length) {
            throw new IllegalArgumentException("длины рядов кривой " + id + " не совпадают");
        }
    }

    public int length() {
        return ret.length;
    }

    /** Доля дней с available = true — обязательное поле отчёта (§6.1). */
    public double availabilityShare() {
        int n = 0;
        for (boolean a : available) {
            if (a) {
                n++;
            }
        }
        return available.length == 0 ? 0 : (double) n / available.length;
    }
}
