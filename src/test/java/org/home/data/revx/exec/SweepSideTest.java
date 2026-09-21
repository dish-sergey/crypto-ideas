package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ОДНОСТОРОННЯЯ реакция на свип: двигается ровно одна сторона.
 *
 * <h2>Зачем разделять стороны</h2>
 *
 * Сдвиг опоры двигает бид и аск ВМЕСТЕ, а действуют они противоположно. На
 * свипе ВНИЗ опора падает, и вместе с ней:
 * <ul>
 *   <li><b>бид уходит от рынка</b> — защита: не ловим падающий нож;</li>
 *   <li><b>аск приближается к рынку</b> — разгрузка: успеваем продать.</li>
 * </ul>
 *
 * Это две разные ставки с разной ценой. В сводном замере 21.09 они были
 * смешаны, и весь плюс пришёл оттуда, где бот РАСКЛИНИЛСЯ у потолка инвентаря
 * (SOL 11.09: 68.8% времени в потолке → 0.0%), то есть похоже на работу аска. Но
 * порознь это не мерилось, поэтому и заведена ось.
 *
 * <h2>Что именно проверяется</h2>
 *
 * Что нетронутая сторона берётся ИЗ КОТИРОВКИ БЕЗ СДВИГА целиком, а не
 * пересчитывается заново. Между опорой и ценой стоят скос по инвентарю, снос и
 * форма сетки; любая попытка «сдвинуть полцены» в обход {@code policy} завела бы
 * вторую реализацию котировщика.
 */
class SweepSideTest {

    /** Свип ВНИЗ: обе цены сдвинутой котировки ниже нетронутых. */
    private static final Quoter.Quotes SHIFTED = new Quoter.Quotes(99.88, 100.08);
    private static final Quoter.Quotes PLAIN = new Quoter.Quotes(99.92, 100.12);

    @Test
    void обеСтороныЭтоПрежнееПоведение() {
        Quoter.Quotes q = QuoteLoop.spliceSide(SHIFTED, PLAIN, QuoteLoop.SweepSide.BOTH);
        assertEquals(SHIFTED, q, "BOTH обязан вернуть сдвинутую котировку как есть");
    }

    @Test
    void толькоБидДвигаетБидИНеТрогаетАск() {
        Quoter.Quotes q = QuoteLoop.spliceSide(SHIFTED, PLAIN, QuoteLoop.SweepSide.BID);
        assertEquals(99.88, q.bid(), 1e-9, "бид обязан уйти от рынка вместе с опорой");
        assertEquals(100.12, q.ask(), 1e-9,
                "аск обязан остаться там, где его поставила бы логика без правки");
    }

    @Test
    void толькоАскДвигаетАскИНеТрогаетБид() {
        Quoter.Quotes q = QuoteLoop.spliceSide(SHIFTED, PLAIN, QuoteLoop.SweepSide.ASK);
        assertEquals(99.92, q.bid(), 1e-9, "бид обязан остаться нетронутым");
        assertEquals(100.08, q.ask(), 1e-9, "аск обязан приблизиться к рынку вместе с опорой");
    }

    /**
     * ⚠️ Отсутствующая сторона — не ошибка, а обычное состояние: пустой бот не
     * котирует продажу, а упёршийся в потолок — покупку. Склейка обязана
     * переносить это «нечем котировать», а не подставлять цену с другой ветки.
     */
    @Test
    void отсутствующаяСторонаПереноситсяКакЕсть() {
        Quoter.Quotes shiftedNoAsk = new Quoter.Quotes(99.88, null);
        Quoter.Quotes plainNoAsk = new Quoter.Quotes(99.92, null);

        assertNull(QuoteLoop.spliceSide(shiftedNoAsk, plainNoAsk,
                QuoteLoop.SweepSide.BID).ask(), "нечего продавать — значит аска нет");
        assertNull(QuoteLoop.spliceSide(shiftedNoAsk, plainNoAsk,
                QuoteLoop.SweepSide.ASK).ask(), "и со стороны ASK тоже нет");
        assertEquals(99.92, QuoteLoop.spliceSide(shiftedNoAsk, plainNoAsk,
                QuoteLoop.SweepSide.ASK).bid(), 1e-9,
                "а бид при этом обязан остаться нетронутым");
    }
}
