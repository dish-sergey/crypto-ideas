package org.home.data.revx.sim;

/**
 * Коэффициент эффективности Кауфмана: {@code ER = |конец − начало| / Σ|шаг|}.
 *
 * Ноль — движение целиком состоит из возвратов, единица — прямая линия. Мера
 * реализованная, а не прогнозная: она говорит, что рынок УЖЕ сделал, и потому
 * годится там, где прогноз не годится.
 *
 * Зачем в этом стенде. База конструкции — сборщик шума: она зарабатывает там, где
 * выбросы возвращаются, и ломается там, где движение продолжается и инвентарь
 * упирается в потолок. Дрейф-скос — страховка от второго режима, и стоит она
 * дорого: −50.6 на боковике против +170.5 на тренде (док. 104 §3). Платить
 * страховку постоянно незачем, если режим наступает 29% времени — отсюда гейт
 * (док. 105 §5).
 *
 * Скользящее вычисление: сумма модулей шагов ведётся инкрементально, поэтому
 * весь ряд обходится один раз.
 */
public final class EfficiencyRatio {

    private final long windowMs;
    private final long sampleMs;
    private final java.util.ArrayDeque<long[]> points = new java.util.ArrayDeque<>();
    private double pathSum;
    private long lastSampleMs = Long.MIN_VALUE;

    /**
     * @param sampleMs шаг ПРОРЕЖИВАНИЯ ряда перед расчётом
     *
     * Прореживание обязательно, и это не оптимизация. ER **зависит от частоты
     * выборки**: длина пути `Σ|шаг|` растёт с числом точек, а числитель нет,
     * поэтому на пятисекундных данных ER в разы меньше, чем на часовых, при том
     * же самом рынке. Порог, откалиброванный по часовому ряду, на секундном
     * никогда не сработает — гейт молча не откроется ни разу.
     *
     * Ровно это и случилось при первом прогоне (док. 106 §2). Фиксированный шаг
     * делает классификатор независимым от разрешения данных, и порог остаётся
     * тем же числом, что измерено офлайн.
     */
    public EfficiencyRatio(long windowMs, long sampleMs) {
        this.windowMs = windowMs;
        this.sampleMs = Math.max(1, sampleMs);
    }

    /**
     * Добавить наблюдение и вернуть ER на текущем окне.
     *
     * Пока окно не заполнилось, возвращается {@code NaN}: считать ER по огрызку
     * нельзя — на коротком отрезке он систематически завышен, и гейт открывался
     * бы в самом начале данных без всякого основания.
     */
    public double accept(long tsMs, double price) {
        // Первая точка принимается всегда: сравнение с Long.MIN_VALUE переполняется,
        // и разность оказывается отрицательной — ряд не начинался бы вовсе.
        if (!(price > 0) || (!points.isEmpty() && tsMs - lastSampleMs < sampleMs)) {
            return current();
        }
        lastSampleMs = tsMs;
        if (!points.isEmpty()) {
            double prev = Double.longBitsToDouble(points.peekLast()[1]);
            pathSum += Math.abs(price - prev);
        }
        points.addLast(new long[]{tsMs, Double.doubleToRawLongBits(price)});

        long since = tsMs - windowMs;
        while (points.size() > 1 && points.peekFirst()[0] < since) {
            double first = Double.longBitsToDouble(points.pollFirst()[1]);
            double second = Double.longBitsToDouble(points.peekFirst()[1]);
            pathSum -= Math.abs(second - first);
        }
        return current();
    }

    /** ER текущего окна или {@code NaN}, пока окно не заполнено. */
    public double current() {
        if (points.size() < 2) {
            return Double.NaN;
        }
        long span = points.peekLast()[0] - points.peekFirst()[0];
        if (span < windowMs * 9 / 10) {
            return Double.NaN;                 // окно ещё не набралось
        }
        double first = Double.longBitsToDouble(points.peekFirst()[1]);
        double last = Double.longBitsToDouble(points.peekLast()[1]);
        return pathSum > 0 ? Math.abs(last - first) / pathSum : 0;
    }

    /**
     * Открыт ли гейт. Порог {@code <= 0} означает «гейта нет» — историческое
     * поведение, страховка включена всегда.
     *
     * При незаполненном окне гейт **закрыт**: неизвестный режим трактуется как
     * тот, в котором страховка не нужна, потому что её цена в этом режиме
     * измерена и велика, а польза — нет.
     */
    public boolean open(double threshold) {
        if (threshold <= 0) {
            return true;
        }
        double er = current();
        return !Double.isNaN(er) && er >= threshold;
    }
}
