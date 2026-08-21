package org.home.data.revx.sim;

/**
 * Сделка из ленты. {@code aggressor} — сторона тейкера (документация Revolut X
 * называет поле `side` именно так); {@code null} = сторона не определена.
 *
 * Неопределённая сторона исполнения НЕ вызывает: при неоднозначности модель
 * обязана выбирать вариант против нас (ТЗ §4.3).
 */
public record MarketTrade(long tsMs, double price, double qty, Side aggressor) {

    public static MarketTrade buy(long tsMs, double price, double qty) {
        return new MarketTrade(tsMs, price, qty, Side.BUY);
    }

    public static MarketTrade sell(long tsMs, double price, double qty) {
        return new MarketTrade(tsMs, price, qty, Side.SELL);
    }

    public static MarketTrade unknown(long tsMs, double price, double qty) {
        return new MarketTrade(tsMs, price, qty, null);
    }
}
