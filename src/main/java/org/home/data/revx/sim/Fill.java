package org.home.data.revx.sim;

/**
 * Исполнение нашей заявки. {@code fairAtFill} — справедливая цена (из опорной
 * USD-книги) в момент исполнения: без неё нельзя ни разложить P&L, ни посчитать
 * markout, а оценивать по последней сделке в тонкой книге ТЗ §4.6 п.10 запрещает.
 */
public record Fill(long tsMs, Side side, double price, double qty, double fairAtFill) {

    /** Захват спреда на этом исполнении: купили ниже справедливой / продали выше. */
    public double spreadCapture() {
        return side.sign() * (fairAtFill - price) * qty;
    }

    public double notional() {
        return price * qty;
    }
}
