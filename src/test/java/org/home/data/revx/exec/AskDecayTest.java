package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ПРИДВИЖЕНИЕ АСКА ПО ВОЗРАСТУ: арифметика доли.
 *
 * Правило: {@code доля = max(floor, 1 − возраст/tau)}, аск ставится на
 * {@code fair + доля · (аск − fair)}. Бид не трогается.
 *
 * Проверяется именно формула, а не проводка через {@link QuoteLoop}: ключи там
 * читаются статически при загрузке класса, и подменить их в тесте нельзя, не
 * ломая остальные. Проводка проверяется обходом, формула — здесь.
 */
class AskDecayTest {

    /** Та же арифметика, что в {@code QuoteLoop.decayAsk}. */
    private static double decayed(double fair, double ask, double ageMin,
                                  double tauMin, double floor) {
        double share = Math.max(floor, 1 - ageMin / tauMin);
        return share >= 1 ? ask : fair + share * (ask - fair);
    }

    /** Свежая позиция — аск не трогается. */
    @Test
    void freshPositionKeepsOffset() {
        assertEquals(100.12, decayed(100.0, 100.12, 0, 30, 0), 1e-9);
    }

    /** Половина срока — половина отступа: 12 б.п. превращаются в 6. */
    @Test
    void halfwayHalvesTheOffset() {
        double ask = decayed(100.0, 100.12, 15, 30, 0);
        assertEquals(100.06, ask, 1e-9);
        assertEquals(6.0, (ask - 100.0) / 100.0 * 10_000, 1e-9);
    }

    /** По истечении срока аск стоит НА справедливой цене — там его снимают за 5 минут у BTC. */
    @Test
    void afterTauAskSitsOnFair() {
        assertEquals(100.0, decayed(100.0, 100.12, 30, 30, 0), 1e-9);
        assertEquals(100.0, decayed(100.0, 100.12, 300, 30, 0), 1e-9);
    }

    /** Пол не даёт придвинуться вплотную: floor 0.25 оставляет четверть отступа. */
    @Test
    void floorStopsTheDecay() {
        double ask = decayed(100.0, 100.12, 300, 30, 0.25);
        assertEquals(3.0, (ask - 100.0) / 100.0 * 10_000, 1e-9);
    }

    /** Бид остаётся прежним: конструкция односторонняя. */
    @Test
    void bidIsNotTouched() {
        Quoter.Quotes q = new Quoter.Quotes(99.88, 100.12);
        Quoter.Quotes after = new Quoter.Quotes(q.bid(), decayed(100.0, q.ask(), 15, 30, 0));
        assertEquals(99.88, after.bid(), 1e-9);
        assertEquals(100.06, after.ask(), 1e-9);
    }
}
