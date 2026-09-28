package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Давление ведра с коленом на половине и распродажа перед пределом постановок. */
class BudgetUnwindTest {

    @Test
    void гистерезис90и70() {
        assertFalse(QuoteLoop.budgetUnwindNext(false, 0.85));
        assertTrue(QuoteLoop.budgetUnwindNext(false, 0.90));
        assertTrue(QuoteLoop.budgetUnwindNext(true, 0.80));   // между порогами — держим
        assertFalse(QuoteLoop.budgetUnwindNext(true, 0.70));
    }

    /** Ведро на 80% полно — давления нет; на четверти — половинное. */
    @Test
    void коленоНаПоловинеКотла() {
        // свой пол выбран (150 ≥ 100), брони чужих нет
        assertEquals(0.0, new PlacementBudget.State(679, 150, 400, 0).pressure(), 1e-9);
        assertEquals(0.0, new PlacementBudget.State(425, 150, 400, 0).pressure(), 1e-9);
        assertEquals(0.5, new PlacementBudget.State(212.5, 150, 400, 0).pressure(), 1e-9);
        assertEquals(1.0, new PlacementBudget.State(0, 150, 400, 0).pressure(), 1e-9);
    }
}
