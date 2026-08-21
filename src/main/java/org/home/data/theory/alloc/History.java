package org.home.data.theory.alloc;

/**
 * Окно истории, доступное аллокатору: данные <b>до дня t включительно</b>
 * (ТЗ 65 §4). Обращение к дню &gt; t — дефект реализации, и здесь это не
 * соглашение, а исключение: единственный способ не проверять отсутствие
 * look-ahead глазами.
 *
 * <p>Автоматический тест §7.2 подменяет всё после дня t на NaN и требует, чтобы
 * веса на t+1 не изменились; такой тест ловит подглядывание только если оно
 * вообще возможно, поэтому доступ закрыт здесь, а не в каждом аллокаторе.
 */
public final class History {

    private final double[][] ret;
    private final boolean[][] avail;
    private final double[] cash;
    private final String[] regime;
    private final String[] cyclePhase;
    private final String[] ids;
    private int today = -1;

    public History(double[][] ret, boolean[][] avail, double[] cash,
                   String[] regime, String[] cyclePhase, String[] ids) {
        this.ret = ret;
        this.avail = avail;
        this.cash = cash;
        this.regime = regime;
        this.cyclePhase = cyclePhase;
        this.ids = ids;
    }

    void advanceTo(int day) {
        this.today = day;
    }

    /** Последний наблюдённый день; веса запрашиваются на {@code today() + 1}. */
    public int today() {
        return today;
    }

    public int strategies() {
        return ids.length;
    }

    public String[] ids() {
        return ids.clone();
    }

    public double ret(int day, int strategy) {
        check(day);
        return ret[day][strategy];
    }

    public boolean available(int day, int strategy) {
        check(day);
        return avail[day][strategy];
    }

    /**
     * Доступность стратегии на последний наблюдённый день. Веса на t+1 строятся
     * по ней, а не по фактической доступности t+1: заглядывать в завтрашнее
     * расписание аллокатору нельзя, даже когда оно технически известно заранее.
     * Фактическую доступность дня t+1 применяет уже движок — деньги недоступной
     * стратегии уходят в кэш (§3.2).
     */
    public boolean availableToday(int strategy) {
        return today >= 0 && avail[today][strategy];
    }

    public double cash(int day) {
        check(day);
        return cash[day];
    }

    /** Состояние детектора на день t: известно на конец дня (available_at = конец дня). */
    public String regimeToday() {
        return regime == null || today < 0 || today >= regime.length ? null : regime[today];
    }

    public String cyclePhaseToday() {
        return cyclePhase == null || today < 0 || today >= cyclePhase.length ? null : cyclePhase[today];
    }

    private void check(int day) {
        if (day > today) {
            throw new IllegalStateException("look-ahead: запрошен день " + day + " при today=" + today);
        }
    }
}
