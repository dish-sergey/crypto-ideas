package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ЗНАК КРУГА: как отличить длинную пару от короткой по одной записи.
 *
 * <h2>Зачем этот тест существует</h2>
 *
 * Мерка настройки (док. 154 §I) считает средний круг по {@code Realisation}, и
 * первая её версия брала {@code (выход − вход)/вход}, то есть объявляла ЛОНГОМ
 * каждую пару. Книга партий открывает и короткие: продажа сверх инвентаря
 * (расхождение с площадкой, затравка, обнуление на границе суток) кладёт
 * отрицательную партию. У такой пары знак результата ОБРАТНЫЙ, и на окне
 * 10–14.09.2026 это дало суточное отношение +2.84 при фактических годовых
 * −65% — величина разошлась с деньгами ЗНАКОМ.
 *
 * Это третье появление одной и той же ловушки: она описана в javadoc
 * {@link FifoLedger}, повторена разовым скриптом 11.09 (медиана 209 минут
 * вместо 8.5) и теперь — в мерке. Поэтому правило различения закреплено тестом.
 */
class RoundSignTest {

    private static final double LOT = 1.255e-05;

    /** То же правило, что в мерке: {@code pnl} длинной пары равен ходу цены. */
    private static boolean isLong(FifoLedger.Realisation r) {
        return Math.abs(r.pnl() - (r.exit() - r.entry()) * r.qty())
                <= 1e-9 * Math.max(1, Math.abs(r.pnl()));
    }

    @Test
    void longRoundIsRecognisedAndItsSignMatchesPriceMove() {
        FifoLedger l = new FifoLedger();
        l.add(1_000, true, LOT, 80_000, 0);
        l.add(2_000, false, LOT, 80_100, 0);
        List<FifoLedger.Realisation> closed = l.realisations();
        assertEquals(1, closed.size());
        FifoLedger.Realisation r = closed.get(0);
        assertTrue(isLong(r));
        assertTrue(r.pnl() > 0, "купили дешевле, продали дороже");
        // Результат в б.п. от цены входа — то, что кладётся в мерку.
        assertEquals(12.5, r.pnl() / (r.qty() * r.entry()) * 10_000, 0.01);
    }

    @Test
    void shortRoundIsRecognisedAndHasTheOppositeSign() {
        FifoLedger l = new FifoLedger();
        l.add(1_000, false, LOT, 80_500, 0);     // продажа сверх инвентаря
        l.add(2_000, true, LOT, 80_100, 0);      // закрылась покупкой дешевле
        FifoLedger.Realisation r = l.realisations().get(0);
        assertFalse(isLong(r), "это короткая пара, и правило обязано её отличить");
        assertTrue(r.pnl() > 0, "шорт закрыт дешевле — прибыль");
        // ⚠️ А наивная формула дала бы МИНУС на прибыльной паре.
        assertTrue((r.exit() - r.entry()) < 0);
    }

    @Test
    void naiveFormulaFlipsTheSignOnEveryShortRound() {
        FifoLedger l = new FifoLedger();
        l.add(1_000, false, LOT, 80_000, 0);
        l.add(2_000, true, LOT, 80_400, 0);      // закрылись дороже — убыток
        FifoLedger.Realisation r = l.realisations().get(0);
        assertTrue(r.pnl() < 0, "шорт закрыт дороже — убыток");
        double naiveBp = (r.exit() - r.entry()) / r.entry() * 10_000;
        double correctBp = r.pnl() / (r.qty() * r.entry()) * 10_000;
        assertTrue(naiveBp > 0 && correctBp < 0,
                "наивная формула показала бы прибыль там, где потеря");
    }
}
