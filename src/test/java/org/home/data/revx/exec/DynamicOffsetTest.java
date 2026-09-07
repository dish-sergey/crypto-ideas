package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гейт по опоре РАЗДВИГАЕТ отступ, а не выключает котирование, и то же делает
 * дефицит постановок.
 *
 * Проверяется настоящий код {@link QuoteLoop}, а не его копия в тесте: расчёт
 * вынесен в статические методы именно ради этого.
 *
 * Живой довод, зачем это вообще нужно: за сутки 06-07.09.2026 гейт по ширине
 * опоры закрыл у ENA 15 623 тика из 77 395, то есть каждый пятый. У остальных
 * пяти пар — от нуля (BTC, ETH) до 0.6% (PEPE). На стенде за 16 суток та же
 * замена дала ENA +5.38 USDC против +2.81 у бинарного гейта.
 */
class DynamicOffsetTest {

    private static final String WIDE = "опорная книга широка: 0.233% при пороге 0.2%";
    private static final String DIVERGED = "опора разошлась с рынком: остаток 0.503% при пороге ±0.5%";
    private static final double K = 1.0 / 3;
    private static final double MAX_PCT = 1.0;

    private static Quoter.Quotes quotes(double bid, double ask) {
        return new Quoter.Quotes(bid, ask);
    }

    @Test
    void широкаяОпораРаздвигаетВместоОстановки() {
        assertTrue(QuoteLoop.gateWidens(false, WIDE, 0.1705, 0.233, K, MAX_PCT),
                "при ширине 0.233% и потолке 1% обязаны раздвигать, а не вставать");
    }

    @Test
    void сломаннаяОпораВсёЖеОстанавливает() {
        // 1.18% у ETH 19.08.2026 на движении 18%: отступ вырос бы до абсурда.
        assertFalse(QuoteLoop.gateWidens(false, "опорная книга широка: 1.18% при пороге 0.2%",
                        2500.0, 1.18, K, MAX_PCT),
                "выше потолка ширины котировать нельзя ни на каком отступе");
    }

    @Test
    void другиеПричиныПаузыНеРаздвигаются() {
        // Здесь справедливая цена посчитана НЕВЕРНО, а не неточно. Отодвигать
        // заявку от неверной цены бессмысленно — дальше от чего?
        assertFalse(QuoteLoop.gateWidens(false, DIVERGED, 0.1705, 0.05, K, MAX_PCT),
                "расхождение опоры с рынком обязано оставаться бинарным гейтом");
        assertFalse(QuoteLoop.gateWidens(false, "курс ненадёжен: разброс implied 0.102%",
                        0.1705, 0.05, K, MAX_PCT));
        assertFalse(QuoteLoop.gateWidens(false, null, 0.1705, 0.05, K, MAX_PCT));
    }

    @Test
    void нулевойKВозвращаетПрежнийБинарныйГейт() {
        assertFalse(QuoteLoop.gateWidens(false, WIDE, 0.1705, 0.233, 0, MAX_PCT),
                "k=0 обязан вести себя ровно как раньше");
    }

    @Test
    void открытыйГейтНичегоНеРаздвигает() {
        assertFalse(QuoteLoop.gateWidens(true, null, 0.1705, 0.05, K, MAX_PCT));
    }

    @Test
    void отступРастётНаПоловинуШириныДелённуюНаДолю() {
        // ENA по 0.1705 при ширине опоры 0.233% и k = 1/3.
        // Половина ширины = 0.1165% цены = 0.00019863. Делим на 1/3 → 0.00059588.
        double price = 0.1705;
        Quoter.Quotes before = quotes(price - 0.00034, price + 0.00034);   // отступ 20 б.п.
        Quoter.Quotes after = QuoteLoop.widenForSpread(before, price, 0.233, K);
        double extra = price * 0.00233 / 2 / K;
        assertEquals(before.bid() - extra, after.bid(), 1e-12);
        assertEquals(before.ask() + extra, after.ask(), 1e-12);
        assertTrue(after.bid() < before.bid() && after.ask() > before.ask(),
                "раздвигать надо в обе стороны ОТ цены");
    }

    @Test
    void раздвижениеСимметричноОтносительноЦены() {
        double price = 100.0;
        Quoter.Quotes after = QuoteLoop.widenForSpread(quotes(99.9, 100.1), price, 0.4, K);
        assertEquals(price - after.bid(), after.ask() - price, 1e-12,
                "неопределённость цены одинакова с обеих сторон");
    }

    @Test
    void однаСторонаБезЗаявкиНеЛомаетРасчёт() {
        // Сторона без средств приходит как null и обязана такой и остаться.
        Quoter.Quotes onlyBid = QuoteLoop.widenForSpread(
                new Quoter.Quotes(99.9, null), 100.0, 0.4, K);
        assertTrue(onlyBid.bid() < 99.9);
        assertEquals(null, onlyBid.ask());
        Quoter.Quotes onlyAsk = QuoteLoop.widenForBudget(
                new Quoter.Quotes(null, 100.1), 100.0, 1.0);
        assertEquals(null, onlyAsk.bid());
        assertTrue(onlyAsk.ask() > 100.1);
    }

    @Test
    void дефицитБюджетаРаздвигаетОтступВдвое() {
        // При полном дефиците отступ обязан удвоиться: BUDGET_WIDEN = 1.
        double price = 100.0;
        Quoter.Quotes after = QuoteLoop.widenForBudget(quotes(99.0, 101.0), price, 1.0);
        assertEquals(98.0, after.bid(), 1e-12);
        assertEquals(102.0, after.ask(), 1e-12);
    }

    @Test
    void дефицитБюджетаРаздвигаетПропорционально() {
        // Наказание задано В ДОЛЯХ ОТСТУПА: тонкая пара с широким отступом и
        // биткойн с узким не должны получать одинаковую прибавку в долларах.
        Quoter.Quotes wide = QuoteLoop.widenForBudget(quotes(97.0, 103.0), 100.0, 0.5);
        Quoter.Quotes narrow = QuoteLoop.widenForBudget(quotes(99.0, 101.0), 100.0, 0.5);
        assertEquals(1.5, 100.0 - wide.bid() - 3.0, 1e-12);
        assertEquals(0.5, 100.0 - narrow.bid() - 1.0, 1e-12);
    }

    @Test
    void полныйБюджетНичегоНеМеняет() {
        Quoter.Quotes before = quotes(99.0, 101.0);
        assertEquals(before, QuoteLoop.widenForBudget(before, 100.0, 0.0),
                "при полном ведре котировка обязана остаться прежней");
    }

    @Test
    void давлениеВышеЕдиницыНеРазноситОтступ() {
        // Защита от арифметической неожиданности: доля дефицита ограничена.
        Quoter.Quotes after = QuoteLoop.widenForBudget(quotes(99.0, 101.0), 100.0, 5.0);
        assertEquals(98.0, after.bid(), 1e-12);
        assertEquals(102.0, after.ask(), 1e-12);
    }
}
