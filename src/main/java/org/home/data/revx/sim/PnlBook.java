package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Учёт P&L с обязательным разложением (ТЗ §4.4):
 *
 * <pre>total = spread_capture + inventory_pnl − fees</pre>
 *
 * Это главный результат стенда: он отвечает, заработала стратегия на спреде или
 * на том, что рынок случайно вырос, пока она держала лонг.
 *
 * Разложение сходится не по договорённости, а тождественно. Пусть исполнение i
 * имеет знак s (+1 покупка), объём q, цену p и справедливую цену f_i:
 *
 * <pre>
 * total = Σ s·q·(f_T − p)                        (касса + переоценка инвентаря)
 *       = Σ s·q·(f_i − p) + Σ s·q·(f_T − f_i)
 *       = spread_capture   + inventory_pnl
 * </pre>
 *
 * Инвентарь оценивается по СПРАВЕДЛИВОЙ цене из опорной книги, а не по последней
 * сделке в тонкой книге: та даёт шум, который легко принять за результат
 * (ТЗ §4.4, §4.6 п.10).
 */
public final class PnlBook {

    private final double makerFeeRate;
    private final List<Fill> fills = new ArrayList<>();

    private double inventory;
    private double cash;
    private double fees;

    public PnlBook(double makerFeeRate) {
        this.makerFeeRate = makerFeeRate;
    }

    public void add(Fill fill) {
        fills.add(fill);
        inventory += fill.side().sign() * fill.qty();
        cash -= fill.side().sign() * fill.notional();
        fees += makerFeeRate * fill.notional();
    }

    public record Decomposition(
            double total,
            double spreadCapture,
            double inventoryPnl,
            double fees,
            double inventory,
            int fillCount) {

        /** Разложение обязано сходиться с общим результатом — это проверяется. */
        public boolean reconciles(double tolerance) {
            return Math.abs(total - (spreadCapture + inventoryPnl - fees)) <= tolerance;
        }
    }

    public Decomposition decompose(double fairNow) {
        double spreadCapture = 0;
        double inventoryPnl = 0;
        for (Fill fill : fills) {
            spreadCapture += fill.spreadCapture();
            inventoryPnl += fill.side().sign() * fill.qty() * (fairNow - fill.fairAtFill());
        }
        double total = cash + inventory * fairNow - fees;
        return new Decomposition(total, spreadCapture, inventoryPnl, fees, inventory, fills.size());
    }

    /** Текущая переоценка: касса + инвентарь по справедливой цене − комиссии. */
    public double mark(double fairNow) {
        return cash + inventory * fairNow - fees;
    }

    public double inventory() {
        return inventory;
    }

    public List<Fill> fills() {
        return List.copyOf(fills);
    }
}
