package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Передачи инвентаря живут ВНУТРИ книги партий, а не мимо неё.
 *
 * 04.09.2026 у бота A торговая статистика считалась по отдельной книге, из
 * которой передачи выбрасывали. Подаренные +0.00026055 BTC в книгу не попали,
 * а их продажи попали — книга встала в шорт ровно на −0.00026055 (20.8 лота).
 * Ни одной честной пары «купил-продал» в ней не осталось, и «реализовано»
 * стало измерять направление рынка: +0.2587 за три часа были падением BTC на
 * 2.48% против фантомного шорта, а не захватом спреда.
 */
class FifoHandoverTest {

    private static final double EPS = 1e-9;

    @Test
    void handoverKeepsTheBookFlat() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, 10, 100, 0, true);      // подарили 10 штук
        l.add(2000, false, 10, 110, 0, false);    // продали их на рынке

        // Главное: книга сошлась в ноль. В прежней схеме здесь был шорт −10.
        assertEquals(0, l.position().qty(), EPS, "книга обязана сойтись в ноль");
        // И результат не приписан торговле: покупки бот не делал.
        assertEquals(0, l.tradingRealisedSince(0), EPS,
                "пара с ногой-передачей не может считаться захватом спреда");
        assertEquals(100, l.handoverRealisedSince(0), EPS);
        assertEquals(0, l.tradingClosedSince(0), "торговых пар здесь нет");
    }

    @Test
    void ownRoundTripIsStillCounted() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, 1, 100, 0, false);
        l.add(2000, false, 1, 110, 0, false);

        assertEquals(10, l.tradingRealisedSince(0), EPS);
        assertEquals(0, l.handoverRealisedSince(0), EPS);
        assertEquals(1, l.tradingClosedSince(0));
    }

    @Test
    void fifoSplitsGiftAndOwnLotsByOrder() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, 1, 100, 0, true);       // подарок пришёл первым
        l.add(2000, true, 1, 100, 0, false);      // своя покупка второй
        l.add(3000, false, 2, 110, 0, false);     // продали обе разом

        // FIFO закрывает подарок первым — эта пара идёт в передачи,
        // вторая, целиком своя, в торговлю.
        assertEquals(10, l.handoverRealisedSince(0), EPS);
        assertEquals(10, l.tradingRealisedSince(0), EPS);
        assertEquals(2, l.closedSince(0), "пар всего две");
        assertEquals(1, l.tradingClosedSince(0), "из них своя одна");
        assertEquals(0, l.position().qty(), EPS);
    }

    @Test
    void givingInventoryAwayIsNotALoss() {
        FifoLedger l = new FifoLedger();
        l.add(1000, true, 1, 100, 0, false);      // купили сами
        l.add(2000, false, 1, 90, 0, true);       // отдали другому боту дешевле

        // Отдача по цене ниже входа не должна выглядеть торговым убытком:
        // сделки с рынком тут не было вовсе.
        assertEquals(0, l.tradingRealisedSince(0), EPS,
                "передача инвентаря не является торговым результатом");
        assertEquals(-10, l.handoverRealisedSince(0), EPS);
        assertEquals(0, l.position().qty(), EPS);
    }

    @Test
    void theLiveCaseOfBotA() {
        // Живая последовательность бота A 04.09.2026, огрублённая до лотов.
        FifoLedger l = new FifoLedger();
        l.add(1_000, true, 9.8, 79_228.49, 0, true);    // 13:36 взял 9.8 лота
        l.add(2_000, false, 3.0, 79_427.85, 0, true);   // отдал 3.0 лота
        l.add(3_000, true, 14.0, 78_843.06, 0, true);   // 14:51 правка фантома
        for (int i = 0; i < 20; i++) {                  // и распродал всё в рынок
            l.add(4_000 + i, false, 1.04, 79_190.82, 0, false);
        }

        assertTrue(Math.abs(l.position().qty()) < 0.05,
                "книга обязана сойтись, а осталось " + l.position().qty());
        // Всё, что произошло, — реализация подаренного. Собственной торговли ноль.
        assertEquals(0, l.tradingRealisedSince(0), EPS,
                "у бота A за это окно не было ни одной пары из своих покупок");
    }
}
