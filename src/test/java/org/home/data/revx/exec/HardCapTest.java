package org.home.data.revx.exec;

import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ЖЁСТКИЙ ПРЕДЕЛ ЗАЯВКИ ({@link QuoteLoop#hardCap}) И «ПЫЛЬ В ЗАЯВКУ» ({@link QuoteLoop#absorbDust}).
 *
 * <h2>Зачем тест</h2>
 *
 * Прежние тесты предела ({@code OwnPositionCapTest}, {@code SellClampedByClaimTest}) повторяли арифметику
 * {@code sizeFor} у себя и настоящий код не вызывали — поэтому 14.11.2026 не поймали, что правило «пыль в заявку»
 * ставит продажу размером в весь пул МИМО проверки доступного. У c доступно 0.01929261 ETH, пул по позиции
 * 0.01945439, остаток 0.00016178 (~$0.30) счёлся пылью — 87 отказов 422 подряд, суточный предел постановок выбран.
 * Здесь проверяется та же функция, что вызывает бот, по каждому ограничению отдельно.
 */
class HardCapTest {

    static final double NO = Double.NaN;      // реестра нет
    static final double PX = 1830.0;

    /** Продажа: позиция 1, без запертого, без реестра; доступное и своя заявка — аргументы. */
    static double sell(double available, double ownSize, double inventory, double locked, double claim) {
        return QuoteLoop.hardCap(Side.SELL, PX, available, 0, ownSize, PX, inventory, locked, 10, false, claim, NO, 0);
    }

    /** Покупка: потолок 2, запас inventory, касса quote, своя претензия на кассу claimQuote. */
    static double buy(double quote, double ownSize, double inventory, boolean frozen, double claimQuote, double lockedQuote) {
        return QuoteLoop.hardCap(Side.BUY, PX, 0, quote, ownSize, PX, inventory, 0, 2, frozen, NO, claimQuote, lockedQuote);
    }

    @Test
    void продажаНеБольшеДоступногоНаСчёте() {
        assertEquals(0.01929261, sell(0.01929261, 0, 0.01945439, 0, NO), 1e-12, "случай c: доступно меньше позиции");
    }

    @Test
    void своейЗаменойВозвращаетсяТолькоОстатокНадкушенной() {
        double own = QuoteLoop.ownRemaining(0.01945439, 0.00016178);
        assertEquals(0.01929261, sell(0.0, own, 0.05, 0, NO), 1e-12);
        assertEquals(0.03, sell(0.01, 0.02, 0.05, 0, NO), 1e-12, "целая своя заявка возвращает весь свой резерв");
    }

    @Test
    void продажаНеБольшеСвоейПозицииДажеНаБогатомСчёте() {
        assertEquals(0.4, sell(5.0, 0, 0.4, 0, NO), 1e-12);
    }

    @Test
    void запертоеПризракомНеПродаётся() {
        assertEquals(0.1, sell(5.0, 0, 0.4, 0.3, NO), 1e-12);
        assertEquals(0.0, sell(5.0, 0, 0.4, 0.5, NO), 1e-12, "запертого больше позиции — продавать нечего, не минус");
    }

    @Test
    void продажаНеБольшеПретензииВРеестре() {
        assertEquals(0.2, sell(5.0, 0, 0.4, 0, 0.2), 1e-12, "счётчик уехал выше реестра — продаём по реестру");
        assertEquals(0.1, sell(5.0, 0, 0.4, 0.1, 0.2), 1e-12, "запертое вычитается и из претензии");
    }

    @Test
    void покупкаНеБольшеКассыИСвоегоРезерва() {
        assertEquals(0.01, buy(18.30, 0, 0, false, NO, 0), 1e-12);
        assertEquals(0.02, buy(18.30, 0.01, 0, false, NO, 0), 1e-12, "своя стоящая покупка возвращает свой резерв");
    }

    @Test
    void покупкаНеВышеПотолкаИНеВЗаморозке() {
        assertEquals(0.5, buy(1e6, 0, 1.5, false, NO, 0), 1e-12, "до потолка 2 осталось 0.5");
        assertEquals(0.0, buy(1e6, 0, 0, true, NO, 0), 1e-12, "распродажа при заморозке — покупок нет");
        assertEquals(0.0, buy(1e6, 0, 3, false, NO, 0), 1e-12, "запас выше потолка — не минус");
    }

    @Test
    void покупкаТолькоНаСвоюКассуБезЗапертой() {
        assertEquals(10.0 / PX, buy(1e6, 0, 0, false, 18.30, 8.30), 1e-12);
    }

    @Test
    void безРеестраПретензияНеОграничивает() {
        assertEquals(0.4, sell(5.0, 0, 0.4, 0, NO), 1e-12);
    }

    // ---------- пыль в заявку ----------

    @Test
    void пыльНеПоднимаетПродажуВышеДоступного() {
        double size = Math.min(0.01929261, 0.01945439);   // sizeFor уже ограничил доступным
        double out = QuoteLoop.absorbDust(size, 0.01945439, 0.01929261, PX, 0.15 * 3, 100);
        assertEquals(0.01929261, out, 1e-12, "случай c: пыль сверх доступного не добавляется");
    }

    @Test
    void пыльДобавляетсяКогдаДоступноХватает() {
        assertEquals(0.0102, QuoteLoop.absorbDust(0.01, 0.0102, 1.0, PX, 0.5, 25), 1e-12);
    }

    @Test
    void крупныйОстатокНеПыль() {
        assertEquals(0.01, QuoteLoop.absorbDust(0.01, 0.02, 1.0, PX, 0.5, 25), 1e-12);
    }

    @Test
    void пыльНеВыводитЗаПределЗаявки() {
        assertEquals(0.01, QuoteLoop.absorbDust(0.01, 0.0102, 1.0, PX, 0.5, 18.5), 1e-12);
    }
}
