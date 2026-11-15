package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ЗАПЕРТОЕ ПРИЗРАКОМ И СВОЯ СТОЯЩАЯ ПРОДАЖА ({@link QuoteLoop#lockedUncovered}).
 *
 * <h2>Зачем тест</h2>
 *
 * 14.11.2026, бот a (BTC). В 15:38 площадка заперла призраком 0.00082646 BTC (отпустила в 19:53). Запертое своё
 * покрывается ничейным свободным буфером, а ничейное свободное считалось как min(всего − претензии, доступно).
 * Своя стоящая продажа 0.00050473 держит свои монеты в резерве и уменьшает «доступно» (0.00055099 → 0.00004626),
 * поэтому запертое вырастало на объём собственной заявки, продавать становилось «нечем», и бот снимал свою же
 * принятую продажу. После снятия «доступно» возвращалось — и через минуту продажа ставилась снова. За 15:38–17:45
 * это 57 постановок и 27 таких снятий; суточный предел 160 выбран, бот остановился с запасом.
 *
 * <h2>Главное свойство</h2>
 *
 * Результат не зависит от того, стоит ли своя продажа: её постановка лишь переводит монеты из «доступно» в её резерв.
 */
class GhostLockedUncoveredTest {

    static final double LOCKED = 0.00082646;     // заперто призраком
    static final double OWNERLESS = 0.001;       // всего на счёте − претензии всех ботов
    static final double INVENTORY = 0.00078020;  // позиция бота
    static final double RESTING = 0.00050473;    // своя стоящая продажа (остаток)
    static final double AVAIL_WITH = 0.00004626; // доступно, пока она стоит
    static final double AVAIL_WITHOUT = AVAIL_WITH + RESTING;

    @Test
    void стоящаяПродажаНеМеняетЗапертое() {
        double with = QuoteLoop.lockedUncovered(LOCKED, OWNERLESS, 0, AVAIL_WITH, RESTING);
        double without = QuoteLoop.lockedUncovered(LOCKED, OWNERLESS, 0, AVAIL_WITHOUT, 0);
        assertEquals(without, with, 1e-12, "постановка своей продажи не должна менять запертое");
    }

    @Test
    void принятаяПродажаНеСнимаетсяКакНечемКотировать() {
        double sellable = Math.max(0, INVENTORY - QuoteLoop.lockedUncovered(LOCKED, OWNERLESS, 0, AVAIL_WITH, RESTING));
        assertTrue(sellable >= RESTING - 1e-12,
                "продать можно не меньше стоящей продажи: " + sellable + " при заявке " + RESTING);

        // так было до правки: своя заявка не возвращалась в доступное — продавать «нечем»
        double before = Math.max(0, INVENTORY - QuoteLoop.lockedUncovered(LOCKED, OWNERLESS, 0, AVAIL_WITH, 0));
        assertEquals(0.0, before, 1e-12);
    }

    /** Буфер не больше ничейного: остаток своих продаж не даёт продать чужое. */
    @Test
    void буферОграниченНичейным() {
        double lockedEff = QuoteLoop.lockedUncovered(LOCKED, 0.0001, 0, AVAIL_WITH, RESTING);
        assertEquals(LOCKED - 0.0001, lockedEff, 1e-12);
        assertEquals(LOCKED, QuoteLoop.lockedUncovered(LOCKED, 0.0005, 0.0006, AVAIL_WITH, RESTING), 1e-12,
                "ничейного нет (претензий больше, чем на счёте) — покрывать нечем");
    }

    @Test
    void безПризракаЗапертогоНет() {
        assertEquals(0.0, QuoteLoop.lockedUncovered(0, OWNERLESS, 0, AVAIL_WITH, RESTING), 1e-12);
    }
}
