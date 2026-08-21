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
            double requoteThreshold,  // порог перевыставления, доля цены
            double quoteStep) {       // шаг цены пары
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
        double skew = params.inventoryCap() > 0 ? inventory / params.inventoryCap() : 0;
        skew = Math.max(-1, Math.min(1, skew));

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
