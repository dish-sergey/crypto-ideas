package org.home.data.revx.exec;

import org.home.data.revx.sim.BookView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Опора без своих заявок (15.11.2026, док. 248): уровень по глубине считается по книге без наших заявок,
 * а ширина книги для гейта — по полной книге. У SOL наши заявки сами задают лучшую цену в 85–92% сделок,
 * и опора по глубине частично считалась от нашей же заявки.
 */
class HybridLevelBookTest {

    static BookView.Level lv(double p, double q) {
        return new BookView.Level(p, q);
    }

    // Полная книга: наш бид 99.0 на 1 лот сверху, дальше чужие 98.0 на 10 лотов; аски чужие.
    static final BookView FULL = new BookView(List.of(lv(99.0, 1), lv(98.0, 10)), List.of(lv(101.0, 10), lv(102.0, 10)));
    static final BookView OTHERS = new BookView(
            QuoteLoop.withoutOwn(FULL.bids(), List.of(new double[]{99.0, 1})), FULL.asks());

    @Test
    void безСвоихУровеньПоЧужим() {
        assertEquals(98.0, OTHERS.bestBid(), 1e-12, "свой бид вычтен, лучший чужой — 98");
        // глубина 2 лота: в полной книге бид набирается на 98 (1 + 10), без своих — тоже на 98; верх книги разный
        HybridCore full = new HybridCore(0, 0, 0, 1000), excl = new HybridCore(0, 0, 0, 1000);
        double a = full.step(0, Double.NaN, true, FULL, 1, 100.0).fair();
        double b = excl.step(0, Double.NaN, true, FULL, OTHERS, 1, 99.5).fair();
        assertTrue(Math.abs(a - 100.0) < 0.06, "полная книга: середина верха (99 + 101) / 2 = 100, получено " + a);
        assertTrue(Math.abs(b - 99.5) < 0.06, "без своих: середина (98 + 101) / 2 = 99.5, получено " + b);
    }

    @Test
    void гейтШириныПоПолнойКниге() {
        // без своих книга шире (98..101 = 304 б.п. при пороге 250), с ними 99..101 = 200 — котировать можно
        HybridCore c = new HybridCore(0, 0, 0, 250);
        HybridCore.Out o = c.step(0, Double.NaN, true, FULL, OTHERS, 1, 99.5);
        assertEquals(200.0, o.spreadBp(), 1.0, "ширина — по полной книге");
    }

    @Test
    void безОтдельнойКнигиКакРаньше() {
        HybridCore a = new HybridCore(0.25, 10, 180, 50), b = new HybridCore(0.25, 10, 180, 50);
        assertEquals(a.step(0, 100.0, true, FULL, 0.1, 100.0).fair(), b.step(0, 100.0, true, FULL, FULL, 0.1, 100.0).fair(), 0);
    }
}
