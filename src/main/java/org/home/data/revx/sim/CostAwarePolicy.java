package org.home.data.revx.sim;

/**
 * Отступ, знающий о СЕБЕСТОИМОСТИ инвентаря: маленькая асимметричная поправка,
 * зависящая от нереализованного результата.
 *
 * <h2>Идея</h2>
 *
 * Нынешний скос знает только УРОВЕНЬ инвентаря и ничего не знает о том, дорого
 * он куплен или дёшево. А это разные положения:
 *
 * <ul>
 *   <li><b>инвентарь выше цели и в ПРИБЫЛИ</b> — разгрузиться до цели можно
 *       прямо сейчас и в деньгах: аск придвигается к рынку;</li>
 *   <li><b>инвентарь выше цели и ПОД ВОДОЙ</b> — продавать в убыток не надо
 *       (это пол по себестоимости, он проигран в док. 147), но и накапливать
 *       дальше незачем: бид отодвигается от рынка, набор замедляется.</li>
 * </ul>
 *
 * То есть правило асимметрично по знаку нереализованного: прибыль трогает
 * АСК, убыток трогает БИД. Обе поправки двигают цену вниз, но на разных
 * сторонах и в разных состояниях.
 *
 * <h2>Почему поправка маленькая</h2>
 *
 * Потолок — единицы базисных пунктов, и это главное отличие от
 * {@link UnloadPolicy}, которая опускала аск до себестоимости и отдавала весь
 * спред: захват падал с 10.9 до 1.1 б.п., правило проиграло на обоих окнах
 * (док. 151 §5). Здесь трогается не цена, а ОТСТУП, и не больше, чем на
 * {@code maxShift}.
 *
 * Малое возмущение отступа — единственная форма, которая в этом проекте
 * работала: сам скос устроен так же. Крупные переприклейки цены к чему-либо,
 * кроме рынка, провалились все до одной (док. 147).
 *
 * <h2>Масштаб</h2>
 *
 * {@code k} подобран так, чтобы поправка выходила на потолок примерно при
 * половине процента нереализованного: {@code k = maxShift / 0.005}. Половина
 * процента — это наблюдённый на живых ботах разброс себестоимости против рынка
 * (03.09.2026: +0.33%, +0.89%, +0.44%; на следующее утро −0.167%).
 *
 * <h2>Чем за это платят и как читать</h2>
 *
 * ⚠️ Это правило меняет экспозицию, а значит его выигрыш обязан сравниваться не
 * с «правило выключено», а с прямой покупкой той же экспозиции (док. 148 §8).
 * На растущем окне «разгрузка в прибыли» продаёт раньше и потому теряет; на
 * падающем «замедление набора под водой» экономит. Ни одно окно по отдельности
 * ничего не решает.
 */
public final class CostAwarePolicy implements QuotePolicy {

    private final QuotePolicy inner;
    private final double targetShare;
    private final double inventoryCap;
    /** Потолок поправки к отступу, доля цены. */
    private final double maxShift;
    /** Нереализованное, при котором поправка выходит на потолок. */
    private static final double SATURATION = 0.005;

    private double qty;
    private double cost;

    public CostAwarePolicy(QuotePolicy inner, double targetShare, double inventoryCap,
                           double maxShift) {
        this.inner = inner;
        this.targetShare = targetShare;
        this.inventoryCap = inventoryCap;
        this.maxShift = maxShift;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        Quoter.Quotes quotes = inner.quotes(fair, inventory, drift);
        if (!(qty > 0) || !(cost > 0) || !(fair > 0) || maxShift <= 0) {
            return quotes;
        }
        if (inventory <= targetShare * inventoryCap) {
            return quotes;                 // ниже цели правило молчит целиком
        }
        double average = cost / qty;
        double unrealised = (fair - average) / average;
        double shift = Math.min(maxShift, Math.abs(unrealised) * (maxShift / SATURATION));
        if (unrealised > 0) {
            // В прибыли: придвигаем АСК к рынку — разгружаемся до цели в деньгах.
            return quotes.ask() == null ? quotes
                    : new Quoter.Quotes(quotes.bid(), quotes.ask() - fair * shift);
        }
        // Под водой: отодвигаем БИД от рынка — набор замедляется. Аск не трогаем:
        // продавать в убыток мы не собираемся, это отдельный и проигранный
        // механизм (пол по себестоимости, док. 147).
        return quotes.bid() == null ? quotes
                : new Quoter.Quotes(quotes.bid() - fair * shift, quotes.ask());
    }

    @Override
    public void onFill(Fill fill) {
        inner.onFill(fill);
        if (fill.side() == Side.BUY) {
            qty += fill.qty();
            cost += fill.qty() * fill.price();
            return;
        }
        // Себестоимость снимается по средней — как в поле и в разгрузке.
        double average = qty > 0 ? cost / qty : 0;
        double take = Math.min(fill.qty(), qty);
        qty -= take;
        cost -= take * average;
        if (qty <= 1e-15) {
            qty = 0;
            cost = 0;
        }
    }

    @Override
    public void onWindow(BookView book) {
        inner.onWindow(book);
    }

    /** Средняя цена входа — объяснение, откуда взялась поправка. */
    public double averageCost() {
        return qty > 0 ? cost / qty : 0;
    }
}
