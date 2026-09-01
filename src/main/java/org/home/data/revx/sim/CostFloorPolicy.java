package org.home.data.revx.sim;

/**
 * Пол по себестоимости: аск не опускается ниже средней цены входа.
 *
 * Это надстройка над обычным котировщиком, а не замена ему. Всё остальное —
 * отступ, скос, гейты — работает как прежде; убирается ровно одно правило,
 * которое заставляет фиксировать убыток.
 *
 * <h2>Зачем</h2>
 *
 * Скос целится в нулевой инвентарь: набрали на падении — аск придвигается к
 * рынку и разгружает. При уходе цены вниз это означает продажу НИЖЕ цены
 * покупки. Измерено (док. 115 §2): на зеркальном падении котировщик теряет
 * −439 **даже при полном возврате цены**, то есть убыток реализованный, а не
 * переоценка.
 *
 * <h2>Чем отличается от сетки</h2>
 *
 * {@link GridQuoter} привязывает аск к цене входа ВМЕСТО рынка и потому
 * систематически продаёт ниже справедливой цены на растущем рынке (захват
 * −8 б.п., док. 115 §5.3). Здесь рыночный якорь сохранён, а цена входа работает
 * только как НИЖНЯЯ ГРАНИЦА: пока рынок выше себестоимости, поведение в
 * точности прежнее, и вмешательство происходит лишь там, где старое правило
 * велело продать в минус.
 *
 * <h2>Чем за это платят</h2>
 *
 * Разгрузка на падении прекращается совсем: инвентарь копится до потолка и
 * стоит. Отложенный убыток остаётся отложенным, и если цена не вернётся, он
 * никуда не денется. Пол по себестоимости не делает падение прибыльным — он
 * лишь перестаёт превращать его в реализованный убыток.
 *
 * Себестоимость считается СРЕДНЕЙ, а не по партиям: продажа снимает долю
 * стоимости пропорционально объёму. FIFO дал бы другую границу на одном и том
 * же инвентаре, и выбор между ними — отдельный вопрос, который эта правка не
 * решает.
 */
public final class CostFloorPolicy implements QuotePolicy {

    private final QuotePolicy inner;
    private final double margin;          // минимальная прибыль над средней ценой входа
    private final double quoteStep;

    private double qty;                   // сколько держим
    private double cost;                  // сколько за это заплачено

    public CostFloorPolicy(QuotePolicy inner, double margin, double quoteStep) {
        this.inner = inner;
        this.margin = margin;
        this.quoteStep = quoteStep;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (quotes.ask() == null || !(qty > 0) || !(cost > 0)) {
            return quotes;
        }
        double floor = round(cost / qty * (1 + margin));
        // Поднимаем аск, но никогда не опускаем: правило односторонее.
        return quotes.ask() >= floor ? quotes : new Quoter.Quotes(quotes.bid(), floor);
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
        if (fill.side() == Side.BUY) {
            qty += fill.qty();
            cost += fill.qty() * fill.price();
            return;
        }
        // Продажа снимает стоимость по средней, а не по цене сделки: иначе
        // прибыльная продажа занижала бы себестоимость остатка и пол уезжал бы вниз.
        double average = qty > 0 ? cost / qty : 0;
        double take = Math.min(fill.qty(), qty);
        qty -= take;
        cost -= take * average;
        if (qty <= 1e-15) {
            qty = 0;
            cost = 0;
        }
    }

    /** Средняя цена входа — попадает в отчёт как объяснение, где стоит пол. */
    public double averageCost() {
        return qty > 0 ? cost / qty : 0;
    }

    private double round(double price) {
        if (quoteStep <= 0) {
            return price;
        }
        return Math.ceil(price / quoteStep - 1e-9) * quoteStep;   // аск — вверх, от рынка
    }
}
