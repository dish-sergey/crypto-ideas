package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Спецификации пар различаются, и подставлять чужие нельзя.
 *
 * До 04.09.2026 шаги были зашиты в {@code Executor} под BTC/USDC, и запустить
 * бота на другой паре было нельзя вовсе. Числа ниже — из каталога площадки
 * ({@code revx_pair}, снято 04.09.2026).
 */
class PairSpecTest {

    private static final StandReader.PairSpec BTC =
            new StandReader.PairSpec("BTC/USDC", 1e-8, 0.01, 0.1);
    private static final StandReader.PairSpec SOL =
            new StandReader.PairSpec("SOL/USDC", 1e-6, 0.001, 0.1);
    private static final StandReader.PairSpec ETH =
            new StandReader.PairSpec("ETH/USDC", 1e-8, 0.01, 0.1);

    @Test
    void solDiffersFromBtcInBothSteps() {
        assertNotEquals(BTC.quoteStep(), SOL.quoteStep(),
                "шаг ЦЕНЫ у SOL 0.001 против 0.01 у BTC — на BTC-шаге цена уйдёт неверной");
        assertNotEquals(BTC.baseStep(), SOL.baseStep(),
                "шаг КОЛИЧЕСТВА у SOL 1e-6 против 1e-8 у BTC");
    }

    @Test
    void ethMatchesBtcAndThatIsFineToo() {
        // У ETH шаги совпадают с биткойновыми — но узнать это можно только
        // прочитав каталог, а не предположив.
        assertEquals(BTC.quoteStep(), ETH.quoteStep());
        assertEquals(BTC.baseStep(), ETH.baseStep());
    }

    @Test
    void minNotionalIsTheBindingMinimum() {
        // Связывает min_order_size_quote (0.1 USDC), а не min_order_size:
        // у BTC последний равен 1e-8 монеты и пренебрежим.
        for (StandReader.PairSpec s : new StandReader.PairSpec[]{BTC, SOL, ETH}) {
            assertEquals(0.1, s.minNotional(), 1e-12, s.symbol());
            assertTrue(s.minNotional() > s.baseStep(), s.symbol()
                    + ": минимум в деньгах обязан связывать раньше шага количества");
        }
    }

    @Test
    void liveSolLotClearsBothLimits() {
        // Лот $1 при цене 101.5 = 0.009851 SOL.
        double lot = 0.009851;
        double price = 101.517;
        assertTrue(lot / SOL.baseStep() > 1000,
                "лот обязан быть много крупнее шага количества, иначе округление съест его");
        assertTrue(lot * price >= SOL.minNotional() * 5,
                "лот обязан с запасом проходить минимум заявки: остаток от частичного "
                        + "исполнения бывает мельче, и стучаться с ним значит тратить лимит");
    }
}
