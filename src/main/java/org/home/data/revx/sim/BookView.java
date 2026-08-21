package org.home.data.revx.sim;

import java.util.List;

/**
 * Видимая часть книги на момент снимка: не более пяти уровней на сторону —
 * это потолок API, а не наше решение (ТЗ §3.1).
 *
 * Именно из-за этого потолка заявка вне видимых уровней считается неисполнимой:
 * мы не знаем ни объёма перед ней, ни того, дошла ли до неё торговля
 * (ТЗ §4.3 п.1, §4.6 п.7).
 */
public record BookView(List<Level> bids, List<Level> asks) {

    public record Level(double price, double qty) {
    }

    public boolean empty() {
        return bids.isEmpty() || asks.isEmpty();
    }

    public double bestBid() {
        return bids.isEmpty() ? Double.NaN : bids.get(0).price();
    }

    public double bestAsk() {
        return asks.isEmpty() ? Double.NaN : asks.get(0).price();
    }

    /** Худшая видимая цена на стороне — граница, за которой заявка «слепая». */
    public double deepestVisible(Side side) {
        List<Level> levels = side == Side.BUY ? bids : asks;
        return levels.isEmpty() ? Double.NaN : levels.get(levels.size() - 1).price();
    }

    /** Объём на конкретной цене; 0, если такого уровня в видимой части нет. */
    public double qtyAt(Side side, double price, double tolerance) {
        for (Level level : side == Side.BUY ? bids : asks) {
            if (Math.abs(level.price() - price) <= tolerance) {
                return level.qty();
            }
        }
        return 0;
    }

    public static BookView of(double[][] bids, double[][] asks) {
        return new BookView(levels(bids), levels(asks));
    }

    private static List<Level> levels(double[][] rows) {
        return java.util.Arrays.stream(rows).map(r -> new Level(r[0], r[1])).toList();
    }
}
