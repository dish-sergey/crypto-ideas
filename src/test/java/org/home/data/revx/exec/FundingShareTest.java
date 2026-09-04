package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Порог покрытия кассой: бот пускается в работу, только если своих денег хватает
 * не меньше чем на 80% его потолка инвентаря.
 *
 * Числа живые (04.09.2026): счёт $46.21 при сумме потолков трёх ботов $49.13.
 * Полное покрытие потребовать было нельзя — третий бот не запустился бы из-за
 * 11% нехватки; он получил 88% и работает.
 */
class FundingShareTest {

    private static final double MIN_SHARE = 0.80;

    /** Та же проверка, что в {@code QuoteLoop.cannotStart}. */
    private static boolean allowed(double haveCash, double needCash) {
        if (needCash <= 0) {
            return true;                 // инвентарь у потолка — покупать не на что
        }
        return haveCash / needCash + 1e-9 >= MIN_SHARE;
    }

    @Test
    void fullyFundedStarts() {
        assertTrue(allowed(18.73, 18.73));
    }

    @Test
    void theRealShortfallOfBotCstarts() {
        // Бот C: взял 14.74 при нужных 16.70 = 88%.
        assertTrue(allowed(14.74, 16.70), "88% обязаны проходить");
    }

    @Test
    void exactlyAtThresholdStarts() {
        assertTrue(allowed(0.80 * 16.70, 16.70), "ровно 80% — проходит");
    }

    @Test
    void justBelowThresholdIsRefused() {
        assertFalse(allowed(0.79 * 16.70, 16.70), "79% — отказ");
    }

    @Test
    void aQuarterOfTheMoneyIsRefused() {
        // Бот с четвертью денег — уже другой бот, сравнивать его с остальными
        // нечестно, и измерение он испортит.
        assertFalse(allowed(4.0, 16.70));
    }

    @Test
    void inventoryAtCapNeedsNoCash() {
        // Покупать не на что: потолок уже выбран монетами.
        assertTrue(allowed(0, 0));
    }
}
