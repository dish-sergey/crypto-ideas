package org.home.data.revx.sim;

/**
 * Разгрузка при ЗАСТОЕ: набрали сверх цели, продаж давно нет — встаём первыми в
 * книге, но не дешевле минимальной прибыли.
 *
 * <h2>Чем отличается от {@link UnloadPolicy}</h2>
 *
 * Предыдущая версия опускала аск сразу до {@code себестоимость·(1+ε)} и потому
 * отдавала весь спред: захват падал с 10.9 до 1.1 б.п., и правило проигрывало на
 * ОБОИХ окнах (док. 151 §5). Ошибка была в том, что цена привязывалась к нашему
 * входу, а не к рынку: на растущем окне себестоимость отстаёт от книги на всю
 * величину роста, и аск улетал далеко под справедливую цену.
 *
 * Здесь цена привязана к КНИГЕ: аск опускается ровно настолько, чтобы стать
 * лучшим предложением — {@code лучший аск − тик}. Это отдаёт один тик и позицию
 * в очереди, а не спред целиком.
 *
 * <h2>Три условия, и все обязательны</h2>
 *
 * <ol>
 *   <li><b>инвентарь выше цели</b> — иначе разгружать нечего, и правило не имеет
 *       смысла вмешиваться в нормальную работу;</li>
 *   <li><b>продаж не было дольше порога</b> — это и есть признак застоя. Без него
 *       правило срабатывало бы всё время, пока инвентарь высок, и превратилось бы
 *       в «котировать у́же», то есть в лестницу отступа, давно измеренную;</li>
 *   <li><b>получившаяся цена выше себестоимости на ε</b> — «разгрузиться хотя бы
 *       с минимальной прибылью». Ниже входа правило не продаёт: это уже пол по
 *       себестоимости наоборот, и он в док. 147 проиграл.</li>
 * </ol>
 *
 * <h2>Чем за это платят</h2>
 *
 * Тиком и очередью. Встать первым — значит забрать поток раньше конкурентов, но
 * и достаться тому, кто знает больше: неблагоприятный отбор у лучшей цены выше.
 * Поэтому смотреть надо не на число исполнений, а на захват и на колонку «при
 * возврате цены».
 *
 * ⚠️ **Правило одностороннее: аск только опускается.** Поднимать его нельзя —
 * это превратило бы разгрузку в пол по себестоимости, то есть в механизм с
 * противоположным знаком.
 */
public final class StallUnloadPolicy implements QuotePolicy {

    private final QuotePolicy inner;
    private final double targetShare;
    private final double inventoryCap;
    /** Сколько окон без продажи считается застоем. */
    private final int stallWindows;
    /** Минимальная прибыль над средней ценой входа, доля. */
    private final double margin;
    private final double quoteStep;

    private double qty;
    private double cost;
    private int windowsSinceSell;
    private double bestBid;
    private double bestAsk;

    public StallUnloadPolicy(QuotePolicy inner, double targetShare, double inventoryCap,
                             int stallWindows, double margin, double quoteStep) {
        this.inner = inner;
        this.targetShare = targetShare;
        this.inventoryCap = inventoryCap;
        this.stallWindows = stallWindows;
        this.margin = margin;
        this.quoteStep = quoteStep;
    }

    @Override
    public void onWindow(BookView book) {
        inner.onWindow(book);
        windowsSinceSell++;
        if (book != null && !book.empty()) {
            bestBid = book.bestBid();
            bestAsk = book.bestAsk();
        }
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (quotes.ask() == null || !(qty > 0) || !(cost > 0) || !(bestAsk > 0)) {
            return quotes;
        }
        if (inventory <= targetShare * inventoryCap || windowsSinceSell < stallWindows) {
            return quotes;                       // не застой либо разгружать нечего
        }
        double front = bestAsk - quoteStep;      // встать первым в книге
        double floor = cost / qty * (1 + margin);
        // Ниже себестоимости не продаём, и мейкером остаёмся: под лучшим бидом
        // заявка сведётся немедленно и заплатит тейкера, что съест всю маржу.
        double limit = Math.max(floor, bestBid + quoteStep);
        double target = Math.max(front, limit);
        // Только опускаем. Если ограничения оказались выше обычного аска, правило
        // молчит — поднимать цену оно не имеет права.
        return target >= quotes.ask() ? quotes : new Quoter.Quotes(quotes.bid(), round(target));
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
        if (fill.side() == Side.BUY) {
            qty += fill.qty();
            cost += fill.qty() * fill.price();
            return;
        }
        windowsSinceSell = 0;
        double average = qty > 0 ? cost / qty : 0;
        double take = Math.min(fill.qty(), qty);
        qty -= take;
        cost -= take * average;
        if (qty <= 1e-15) {
            qty = 0;
            cost = 0;
        }
    }

    /** Средняя цена входа — объяснение, где стоит граница разгрузки. */
    public double averageCost() {
        return qty > 0 ? cost / qty : 0;
    }

    private double round(double price) {
        if (quoteStep <= 0) {
            return price;
        }
        return Math.ceil(price / quoteStep - 1e-9) * quoteStep;
    }
}
