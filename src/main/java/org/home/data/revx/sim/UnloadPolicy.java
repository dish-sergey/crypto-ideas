package org.home.data.revx.sim;

/**
 * Разгрузка по себестоимости: набрав СВЕРХ цели, продаём при первой же прибыли,
 * не дожидаясь полного отступа.
 *
 * <h2>Зачем</h2>
 *
 * Идея из наблюдения на живом боте 03.09.2026. Рост остановился, три бота за
 * два часа набрали с 16% до 64% потолка, продажи встали (последняя за час до
 * замера), а инвентарь при этом был **в прибыли**: средняя цена входа 80 810
 * против рынка 81 078, то есть +0.33%. Обычный аск стоял на `fair·(1+d)` и ждал
 * ещё десять базисных пунктов сверху, которых рынок не давал.
 *
 * Правило: пока инвентарь выше цели, аск разрешается опустить до
 * {@code себестоимость·(1+ε)} — то есть отдать часть спреда за то, чтобы
 * выйти в деньги.
 *
 * <h2>Чем это отличается от пола по себестоимости</h2>
 *
 * {@link CostFloorPolicy} — то же число, но с другой стороны и с другим знаком.
 * Пол ЗАПРЕЩАЕТ продавать ниже входа и потому копит инвентарь; здесь наоборот,
 * себестоимость работает потолком для аска и инвентарь СБРАСЫВАЕТ. Правила
 * несовместимы по духу, и включать их вместе бессмысленно: пол вмешивается,
 * когда рынок ниже входа, разгрузка — когда выше.
 *
 * <h2>Чем за это платят</h2>
 *
 * Захватом. Продажа у {@code cost·(1+ε)} вместо {@code fair·(1+d)} — это прямой
 * отказ от края ради оборота и нейтральности. Если рынок продолжит расти, мы
 * продадим дёшево и будем смотреть, как он уходит; выигрыш появляется только
 * если рост действительно кончился.
 *
 * ⚠️ **Это ставка на разворот, и её надо читать именно так.** Механизм
 * зарабатывает ровно тогда, когда цена после остановки идёт вниз, — то есть его
 * выигрыш на растущем окне обязан быть отрицательным, а на падающем
 * положительным. Прогон на одном окне поэтому ничего не решает: нужны оба, и
 * сравнивать надо сумму (док. 148 §8: механизм, выигрыш которого объясняется
 * экспозицией, обязан сравниваться с прямой покупкой той же экспозиции).
 *
 * <h2>Почему аск не опускается ниже верха книги</h2>
 *
 * Опустить аск ниже лучшего бида значит перестать быть мейкером: заявка сведётся
 * немедленно и заплатит тейкера (0.09%), что съест всю маржу ε. Ограничение
 * снаружи не задаётся — на живом боте его ставит предохранитель от пересечения
 * книги, здесь же модель исполнения просто не даст такой заявке стоять.
 */
public final class UnloadPolicy implements QuotePolicy {

    private final QuotePolicy inner;
    /** Доля потолка, выше которой разгрузка включается. */
    private final double targetShare;
    private final double inventoryCap;
    /** Минимальная прибыль над средней ценой входа, доля. */
    private final double margin;
    private final double quoteStep;

    private double qty;
    private double cost;

    public UnloadPolicy(QuotePolicy inner, double targetShare, double inventoryCap,
                        double margin, double quoteStep) {
        this.inner = inner;
        this.targetShare = targetShare;
        this.inventoryCap = inventoryCap;
        this.margin = margin;
        this.quoteStep = quoteStep;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (quotes.ask() == null || !(qty > 0) || !(cost > 0)) {
            return quotes;
        }
        if (inventory <= targetShare * inventoryCap) {
            return quotes;                 // ниже цели правило молчит целиком
        }
        double unload = round(cost / qty * (1 + margin));
        // Опускаем аск, но никогда не поднимаем: правило одностороннее, как и пол.
        // Иначе на дорогом инвентаре оно превратилось бы в пол по себестоимости,
        // то есть в противоположный механизм.
        return quotes.ask() <= unload ? quotes : new Quoter.Quotes(quotes.bid(), unload);
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
        if (fill.side() == Side.BUY) {
            qty += fill.qty();
            cost += fill.qty() * fill.price();
            return;
        }
        // Себестоимость снимается по СРЕДНЕЙ — так же, как в поле по себестоимости.
        // FIFO дал бы другую границу на том же инвентаре, и выбор между ними —
        // отдельный вопрос, который эта правка не решает.
        double average = qty > 0 ? cost / qty : 0;
        double take = Math.min(fill.qty(), qty);
        qty -= take;
        cost -= take * average;
        if (qty <= 1e-15) {
            qty = 0;
            cost = 0;
        }
    }

    /** Средняя цена входа — попадает в отчёт как объяснение, где стоит разгрузка. */
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
