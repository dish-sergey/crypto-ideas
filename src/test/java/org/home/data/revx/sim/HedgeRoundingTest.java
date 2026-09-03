package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Округление целевого шорта. Тест существует из-за дефекта, найденного в
 * док. 127 §8.4: цель считалась «к ближайшему», и на грубом шаге контракта это
 * ПЕРЕВОРАЧИВАЕТ позицию.
 *
 * Масштабы взяты живые: лот бота 0.0000125 BTC, потолок 0.00025 BTC, шаг
 * контракта Kraken 0.0001 BTC. Весь потолок — две с половиной ступени, поэтому
 * половина шага это 4 лота, то есть 20% потолка в обратную сторону.
 */
class HedgeRoundingTest {

    private static final double STEP = 0.0001;
    private static final double LOT = 0.0000125;
    private static final double CAP = 0.00025;

    private static Quoter.Hedge hedge(boolean roundDown) {
        return Quoter.Hedge.OFF.withRebalance(0).withStep(STEP).withFee(0.0005).withRoundDown(roundDown);
    }

    @Test
    void roundingDownNeverGoesNetShort() {
        Quoter.Hedge h = hedge(true);
        // Весь диапазон живого инвентаря с шагом в лот.
        for (int lots = 0; lots * LOT <= CAP + 1e-12; lots++) {
            double inventory = lots * LOT;
            double net = inventory + h.target(inventory);
            assertTrue(net >= -1e-12,
                    "нетто-позиция ушла в шорт при инвентаре " + inventory + ": " + net);
            assertTrue(net <= STEP + 1e-12,
                    "непокрытая часть больше шага контракта при инвентаре " + inventory);
        }
    }

    @Test
    void roundingToNearestFlipsPositionShort() {
        Quoter.Hedge h = hedge(false);
        // 0.00016 BTC = 12.8 лота: ближайшее кратное шагу — 0.0002, то есть шорт
        // на 0.00004 больше инвентаря. Это 3.2 лота и 16% потолка НАОБОРОТ.
        double inventory = 0.00016;
        double net = inventory + h.target(inventory);
        assertTrue(net < 0, "округление к ближайшему обязано было дать нетто-шорт");
        assertEquals(-0.00004, net, 1e-12);
        assertTrue(Math.abs(net) / CAP > 0.15,
                "скрытая обратная экспозиция должна быть заметной долей потолка");
    }

    @Test
    void nothingToHedgeBelowOneContractStep() {
        // Инвентарь мельче шага контракта: хеджировать нечем, и это ноль, а не
        // «округлим вверх и постоим в шорте».
        assertEquals(0.0, hedge(true).target(LOT * 3), 1e-12);
    }

    @Test
    void deadbandLeavesInventoryBelowItUnhedged() {
        // Полоса 0.0001 = один шаг контракта: всё, что ниже, не хеджируется вовсе,
        // и под ней остаётся направленный лонг ровно на её величину.
        Quoter.Hedge banded = hedge(true).withDeadband(0.0001);
        assertEquals(0.0, banded.target(0.00009), 1e-12, "под полосой шорта нет");
        assertEquals(-0.0001, banded.target(0.00021), 1e-12, "хеджируется только избыток");
        // Непокрытым остаётся полоса ПЛЮС остаток округления вниз: при инвентаре
        // 0.00025 избыток 0.00015 = полтора шага, хеджируется один.
        double inv = 0.00025;
        assertEquals(0.00015, inv + banded.target(inv), 1e-12,
                "полоса 0.0001 плюс полшага округления");
        assertTrue(inv + banded.target(inv) >= 0.0001,
                "непокрытая часть не может быть меньше самой полосы");
    }

    @Test
    void dislocationThresholdIsOffByDefaultAndSettable() {
        // Приёмка формы «ноль цены вне срабатывания» (док. 108): по умолчанию
        // предохранителя нет вовсе, и включение его не трогает ничего другого.
        Quoter.Hedge plain = hedge(true);
        assertEquals(0.0, plain.dislocationBp(), 1e-12, "по умолчанию предохранитель выключен");

        Quoter.Hedge guarded = plain.withDislocation(50);
        assertEquals(50.0, guarded.dislocationBp(), 1e-12);
        // Целевой шорт от предохранителя не зависит: правило решает, ДЕЛАТЬ ли
        // ребалансировку, а не какой она должна быть.
        assertEquals(plain.target(0.00021), guarded.target(0.00021), 1e-15);
        assertEquals(plain.step(), guarded.step(), 1e-15);
        assertEquals(plain.feeRate(), guarded.feeRate(), 1e-15);
        assertEquals(plain.roundDown(), guarded.roundDown());
        assertEquals(plain.deadband(), guarded.deadband(), 1e-15);
    }

    @Test
    void zeroStepMeansContinuousHedge() {
        // Шаг не задан — модель непрерывного хеджа, остаток тождественно нулевой.
        Quoter.Hedge continuous = Quoter.Hedge.OFF.withRebalance(0).withStep(0);
        assertEquals(-0.000123, continuous.target(0.000123), 1e-15);
    }
}
