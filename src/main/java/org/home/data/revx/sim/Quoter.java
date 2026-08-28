package org.home.data.revx.sim;

/**
 * Логика котирования (ТЗ §4.2). Все параметры — из конфига.
 *
 * Скос двигает ОБЕ котировки в одну сторону: при полном инвентаре бид уходит
 * вниз и перестаёт исполняться, а аск придвигается к справедливой цене и
 * разгружает позицию.
 *
 * Жёсткие правила: при полном инвентаре бида нет вовсе; при нулевом инвентаре
 * нет аска — спот, шортить нечем, и это физика, а не настройка.
 */
public final class Quoter implements QuotePolicy {

    public record Params(
            double offset,            // d — отступ от справедливой цены, доля
            double size,              // номинал заявки в базовой валюте
            double inventoryCap,      // потолок инвентаря в базовой валюте
            double skewK,             // коэффициент скоса
            double skewTarget,        // ЦЕЛЬ скоса, доля потолка (0 = пустой инвентарь)
            double requoteThreshold,  // порог перевыставления, доля цены
            double quoteStep) {       // шаг цены пары

        /** Совместимость: цель по умолчанию — пустой инвентарь. */
        public Params(double offset, double size, double inventoryCap, double skewK,
                      double requoteThreshold, double quoteStep) {
            this(offset, size, inventoryCap, skewK, 0.0, requoteThreshold, quoteStep);
        }
    }

    /** null в цене = сторона не котируется. */
    public record Quotes(Double bid, Double ask) {

        public boolean hasBid() {
            return bid != null;
        }

        public boolean hasAsk() {
            return ask != null;
        }
    }

    private final Params params;

    public Quoter(Params params) {
        this.params = params;
    }

    @Override
    public Quotes quotes(double fair, double inventory) {
        if (!(fair > 0)) {
            return new Quotes(null, null);
        }
        double skew = skew(inventory);

        Double bid = null;
        Double ask = null;
        if (inventory < params.inventoryCap()) {
            bid = round(fair * (1 - params.offset() - params.skewK() * skew), true);
        }
        if (inventory > 0) {
            ask = round(fair * (1 + params.offset() - params.skewK() * skew), false);
        }
        return new Quotes(bid, ask);
    }

    /**
     * Скос относительно ЦЕЛЕВОГО инвентаря, а не относительно пустого счёта.
     *
     * Скос вычитается из обеих цен, поэтому симметричны они ровно там, где он
     * равен нулю: эта точка и есть цель контроллера. При {@code skewTarget = 0}
     * целью оказывается пустой инвентарь — а на споте это угол, в котором аск
     * выставить нечем, и стратегия становится односторонней. Живой прогон провёл
     * там 58% времени (док. 93 §4).
     *
     * Нормировка на {@code max(target, 1−target)} держит скос в [−1, 1] при любой
     * цели, поэтому коэффициент {@code k} сохраняет смысл «сколько б.п. на краю».
     */
    private double skew(double inventory) {
        double cap = params.inventoryCap();
        if (!(cap > 0)) {
            return 0;
        }
        double target = params.skewTarget();
        double span = Math.max(target, 1 - target);
        if (!(span > 0)) {
            return 0;
        }
        double skew = (inventory / cap - target) / span;
        return Math.max(-1, Math.min(1, skew));
    }

    /**
     * Перевыставлять только при отклонении больше порога — иначе счётчик
     * перевыставлений уйдёт в тысячи и упрётся в лимит запросов (ТЗ §4.2).
     */
    public boolean shouldRequote(Double current, Double target) {
        if (current == null || target == null) {
            return current != null || target != null;
        }
        return Math.abs(target - current) / current > params.requoteThreshold();
    }

    /** Цены округляются по шагу пары: бид вниз, аск вверх — всегда в сторону от рынка. */
    private Double round(double price, boolean down) {
        double step = params.quoteStep();
        if (step <= 0) {
            return price;
        }
        double steps = price / step;
        return (down ? Math.floor(steps + 1e-9) : Math.ceil(steps - 1e-9)) * step;
    }
}
