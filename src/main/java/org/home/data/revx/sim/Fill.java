package org.home.data.revx.sim;

/**
 * Исполнение нашей заявки. {@code fairAtFill} — справедливая цена (из опорной
 * USD-книги) в момент исполнения: без неё нельзя ни разложить P&L, ни посчитать
 * markout, а оценивать по последней сделке в тонкой книге ТЗ §4.6 п.10 запрещает.
 */
public record Fill(long tsMs, Side side, double price, double qty, double fairAtFill,
                   Book book) {

    /**
     * Состояние книги в момент исполнения — для задания Z9 (док. 109 §III).
     *
     * Вопрос, на который эти поля отвечают: меняется ли пошлина от того, КТО
     * стоит внутри нас. Две взаимоисключающие гипотезы дают противоположные
     * рекомендации — узкий котировщик либо ФИЛЬТРУЕТ мелкие касания и оставляет
     * нам настоящие свипы, либо СНИМАЕТ СЛИВКИ, успевая убрать заявку перед
     * информированным потоком и оставляя токсичное нам.
     *
     * ⚠️ {@code qtyInside} почти наверняка входит в модель исполнения, поэтому
     * ЧИСЛО исполнений по корзинам сравнивать нельзя — это будет тавтология.
     * {@code markout} входом модели не является, и его сравнивать корректно.
     *
     * @param weAreBest    наша цена не хуже лучшей на нашей стороне
     * @param levelsInside ценовых уровней строго между нами и серединой
     * @param qtyInside    номинал на этих уровнях, USDC
     * @param spreadBp     спред книги на этом тике
     */
    public record Book(boolean weAreBest, int levelsInside, double qtyInside, double spreadBp) {
    }

    /** Без контекста книги — для синтетики и старых вызовов. */
    public Fill(long tsMs, Side side, double price, double qty, double fairAtFill) {
        this(tsMs, side, price, qty, fairAtFill, null);
    }

    /** Захват спреда на этом исполнении: купили ниже справедливой / продали выше. */
    public double spreadCapture() {
        return side.sign() * (fairAtFill - price) * qty;
    }

    public double notional() {
        return price * qty;
    }

    /**
     * Контекст книги для нашей заявки: сколько стоит ВНУТРИ нас, ближе к середине.
     *
     * Считается на снимке момента исполнения. Уровни строго внутри — те, что
     * лучше нашей цены на нашей же стороне: для бида это более высокие цены, для
     * аска — более низкие.
     */
    public static Book context(BookView view, Side side, double price) {
        if (view == null || view.empty()) {
            return null;
        }
        double bestBid = view.bestBid();
        double bestAsk = view.bestAsk();
        double mid = (bestBid + bestAsk) / 2;
        double spreadBp = mid > 0 ? (bestAsk - bestBid) / mid * 10_000 : Double.NaN;

        int levels = 0;
        double qty = 0;
        for (BookView.Level level : side == Side.BUY ? view.bids() : view.asks()) {
            boolean inside = side == Side.BUY ? level.price() > price : level.price() < price;
            if (inside) {
                levels++;
                qty += level.price() * level.qty();
            }
        }
        boolean best = side == Side.BUY ? price >= bestBid : price <= bestAsk;
        return new Book(best, levels, qty, spreadBp);
    }
}
