package org.home.data.revx.sim;

/**
 * Бид, привязанный к ЦЕНЕ ВХОДА, а не к справедливой цене, с поводком.
 *
 * <h2>Зачем понадобилась третья политика бида</h2>
 *
 * Обычный котировщик держит бид на {@code справедливая × (1 − шаг)} и
 * пересчитывает его каждый тик. Значит заявка никогда не отстаёт — но и
 * исполниться может только от ВЫНОСА такой глубины за один принт. По замеру
 * ленты (док. 113 §4) на 20 б.п. до нас доходит 1.5 сделки в сутки, на 30 —
 * ноль. Отсюда следствие, замеченное на живом 01.09.2026: растущий шаг покупок
 * не «покупает глубже по мере падения», он **перестаёт покупать**, потому что
 * его заявка уходит из досягаемости.
 *
 * Сетка ведёт себя иначе: заявка стоит на своём уровне и ждёт, пока рынок к ней
 * ПРИДЁТ. Для «пережить падение, докупая вниз» это правильная семантика — и
 * именно её молча предполагала формула ёмкости из док. 117 §1.
 *
 * У сетки свой провал, зеркальный: если цена ушла вверх, заявка остаётся внизу и
 * не исполнится никогда — на растущем рынке бот не наберёт ничего.
 *
 * <h2>Поводок объединяет обе</h2>
 *
 * <pre>
 * кандидат = якорь × (1 − шаг(инвентарь))
 * бид      = min(кандидат, справедливая × (1 − minDistance))   не покупать выше рынка
 * бид      = max(бид,      справедливая × (1 − leash))         поводок: не отставать
 * </pre>
 *
 * Поводок {@code leash} — единственный параметр, и он задаёт всё семейство:
 *
 * <ul>
 *   <li>{@code leash = minDistance} — заявка не может отстать вовсе, то есть в
 *       точности прежняя привязка к справедливой цене;</li>
 *   <li>{@code leash = ∞} — чистая сетка, заявка стоит где поставлена;</li>
 *   <li>между ними — сетка, которая ждёт рынок, но подтягивается, если он ушёл
 *       слишком далеко вверх.</li>
 * </ul>
 *
 * Верхний зажим {@code minDistance} обязателен и не является настройкой. Купили
 * по 100, цена упала до 95 — якорная цена 99.9 оказывается ВЫШЕ рынка, и заявка
 * стала бы покупкой по любой цене. Это тот самый «опасный снос», который в
 * док. 110 §6 срезал край вчетверо, только в предельной форме.
 */
public final class AnchoredBidPolicy implements QuotePolicy {

    private final QuotePolicy inner;
    private final double baseStep;
    private final double widening;        // η: во сколько раз шаг растёт на лот
    private final double minDistance;     // ближе этого к справедливой не подходим
    private final double leash;           // дальше этого от справедливой не отстаём
    private final double lotSize;
    private final double inventoryCap;
    private final double quoteStep;

    /** Цена последней покупки. NaN = покупок ещё не было, якорь — рынок. */
    private double anchor = Double.NaN;

    public AnchoredBidPolicy(QuotePolicy inner, double baseStep, double widening,
                             double minDistance, double leash, double lotSize,
                             double inventoryCap, double quoteStep) {
        this.inner = inner;
        this.baseStep = baseStep;
        this.widening = widening;
        this.minDistance = minDistance;
        this.leash = leash >= 0 ? leash : Double.MAX_VALUE;   // 0 = поводок в ноль, а НЕ «поводка нет»
        this.lotSize = lotSize;
        this.inventoryCap = inventoryCap;
        this.quoteStep = quoteStep;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (!(fair > 0) || quotes.bid() == null) {
            return quotes;
        }
        if (inventory >= inventoryCap) {
            return new Quoter.Quotes(null, quotes.ask());
        }
        // Пока не купили ничего, якоря нет: ведём себя как обычный котировщик,
        // иначе первую покупку было бы не с чего отсчитывать.
        resetIfFlat(inventory);
        double base = Double.isNaN(anchor) ? fair : anchor;
        double candidate = base * (1 - step(inventory));
        // ⚠️ Зажимы берутся ОТ БИДА ВНУТРЕННЕЙ политики, а не от голого отступа.
        // Иначе обёртка затирает вклад скоса и цели инвентаря — ровно та ошибка,
        // что была в WideningBidPolicy и стоила половины механизма живому боту B.
        // При поводке, равном расстоянию внутренней, бид обязан совпасть с ней
        // дословно: это приёмочное условие лестницы.
        double ceiling = quotes.bid();                  // ближе, чем хочет котировщик, не подходим
        // Поводок отмеряется ОТ БИДА КОТИРОВЩИКА, а не от справедливой цены.
        // Иначе он перебивает скос: при поводке, равном отступу, заявка
        // подтягивалась бы вверх даже там, где котировщик сознательно увёл её
        // вниз по инвентарю, и нижняя ступень лестницы не совпадала бы с базой.
        double floor = quotes.bid() * (1 - Math.min(leash, 1));
        double bid = Math.max(Math.min(candidate, ceiling), floor);
        return new Quoter.Quotes(round(bid), quotes.ask());
    }

    /** Расстояние до следующей покупки ОТ ЯКОРЯ при текущем наборе. */
    public double step(double inventory) {
        double held = lotSize > 0 ? Math.max(0, inventory) / lotSize : 0;
        return baseStep * (1 + widening * held);
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
        if (fill.side() == Side.BUY) {
            anchor = fill.price();
        }
    }

    /**
     * Инвентарь опустел — якорь сбрасывается: цена входа, от которой мы
     * отсчитывали лестницу, больше не относится ни к чему.
     */
    public void resetIfFlat(double inventory) {
        if (inventory <= 1e-15) {
            anchor = Double.NaN;
        }
    }

    private double round(double price) {
        if (quoteStep <= 0) {
            return price;
        }
        return Math.floor(price / quoteStep + 1e-9) * quoteStep;   // бид — вниз, от рынка
    }
}
