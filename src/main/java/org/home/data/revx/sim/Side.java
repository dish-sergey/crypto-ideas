package org.home.data.revx.sim;

/** Сторона: наша заявка либо агрессор сделки. */
public enum Side {
    BUY,
    SELL;

    /** +1 для покупки, −1 для продажи — знак в формулах P&L. */
    public int sign() {
        return this == BUY ? 1 : -1;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
