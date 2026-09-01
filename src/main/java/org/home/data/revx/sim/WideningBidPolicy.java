package org.home.data.revx.sim;

/**
 * Растущий шаг покупок: чем больше набрано, тем ниже следующий бид.
 *
 * <pre>шаг(q) = шаг₀ × (1 + η · лотов)</pre>
 *
 * Надстройка над обычным котировщиком, трогающая ТОЛЬКО бид. Аск остаётся тем,
 * что вернула внутренняя политика, — так эту правку можно комбинировать с
 * {@link CostFloorPolicy}, не смешивая два вопроса в одном классе.
 *
 * <h2>Зачем</h2>
 *
 * Ёмкость падения — сколько процентов снижения конструкция способна отработать,
 * прежде чем упрётся в потолок инвентаря — равна сумме расстояний до всех
 * покупок:
 *
 * <pre>ёмкость = шаг₀ × (N + η·N(N−1)/2)</pre>
 *
 * У нынешнего котировщика шаг растёт только за счёт скоса, и этого мало:
 * измерено 1.05% на стенде (5 лотов) и ≈2.95% по геометрии на живом (20 лотов) —
 * док. 116 §2.1. При падении глубже конструкция перестаёт котировать на покупку
 * и просто держит позицию.
 *
 * <h2>Почему именно растущий шаг, а не широкий отступ</h2>
 *
 * Ту же ёмкость даёт постоянный отступ 45 б.п. при 20 лотах — но на 30 б.п. до
 * нас уже не доходит ни одной сделки в сутки (док. 113 §4, замер ленты). Растущий
 * шаг оставляет ближний конец лестницы там же, где он сейчас, и раздвигает
 * только дальний, до которого дело доходит редко.
 */
public final class WideningBidPolicy implements QuotePolicy {

    private final QuotePolicy inner;
    private final double baseStep;
    private final double widening;        // η
    private final double maxStep;
    private final double lotSize;
    private final double inventoryCap;
    private final double quoteStep;

    public WideningBidPolicy(QuotePolicy inner, double baseStep, double widening,
                             double maxStep, double lotSize, double inventoryCap,
                             double quoteStep) {
        this.inner = inner;
        this.baseStep = baseStep;
        this.widening = widening;
        this.maxStep = maxStep > 0 ? maxStep : Double.MAX_VALUE;
        this.lotSize = lotSize;
        this.inventoryCap = inventoryCap;
        this.quoteStep = quoteStep;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (!(fair > 0) || quotes.bid() == null) {
            return quotes;                // бида и так нет — потолок или гейт
        }
        if (inventory >= inventoryCap) {
            return new Quoter.Quotes(null, quotes.ask());
        }
        return new Quoter.Quotes(round(fair * (1 - step(inventory))), quotes.ask());
    }

    /** Расстояние до следующей покупки при текущем наборе. */
    public double step(double inventory) {
        double held = lotSize > 0 ? Math.max(0, inventory) / lotSize : 0;
        return Math.min(maxStep, baseStep * (1 + widening * held));
    }

    /** Ёмкость падения по геометрии: сумма расстояний до всех N покупок. */
    public static double capacityPct(double baseStep, double widening, int lots) {
        return 100.0 * baseStep * (lots + widening * lots * (lots - 1) / 2.0);
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
    }

    private Double round(double price) {
        if (quoteStep <= 0) {
            return price;
        }
        return Math.floor(price / quoteStep + 1e-9) * quoteStep;   // бид — вниз, от рынка
    }
}
