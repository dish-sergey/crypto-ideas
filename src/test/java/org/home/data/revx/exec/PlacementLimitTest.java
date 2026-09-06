package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Суточный лимит постановок — по ботам, а не поровну (док. 74: потолок у
 * площадки общий на ВЕСЬ аккаунт).
 *
 * С 06.09.2026 ботов шесть, по одному на пару. Тест сторожит три свойства:
 * сумма долей не превышает того, что даёт площадка; каждому хватает на его
 * измеренный расход; незнакомый бот получает консервативную долю, а не максимум.
 */
class PlacementLimitTest {

    /** Столько даёт площадка на аккаунт. */
    private static final int VENUE_DAILY = 1000;

    private static final List<String> BOTS = List.of("a", "b", "c", "d", "e", "f");

    /**
     * Сколько постановок в сутки бот тратит на самом деле.
     *
     * Числа из обхода вселенной за 16 суток (20.08-04.09.2026), каждая пара на
     * своём отступе. У биткойна стоит оценка для 10 б.п. — обход печатал расход
     * только для лучшей ступени (8 б.п., 311 постановок), а 10 б.п. выбраны ради
     * экономии бюджета, и 160 здесь получены экстраполяцией по закону прихода.
     */
    private static final Map<String, Integer> MEASURED_USAGE = Map.of(
            "a", 160,      // BTC  10 б.п. (оценка)
            "b", 134,      // SOL  18 б.п.
            "c", 130,      // ETH  14 б.п.
            "d", 50,       // ADA  14 б.п.
            "e", 41,       // ENA  20 б.п.
            "f", 38);      // PEPE 20 б.п.

    @Test
    void sumOverBotsStaysUnderVenueLimit() {
        int total = BOTS.stream().mapToInt(ExecLimits::maxPlacementsPerDay).sum();
        assertTrue(total <= VENUE_DAILY,
                "сумма по шести ботам " + total + " превышает потолок площадки " + VENUE_DAILY);
        // Запас на повторы и ручные проверки. Единственный буфер на разбор
        // аварии, когда боты уже съели своё.
        assertTrue(VENUE_DAILY - total >= 50,
                "запас на повторы и ручные проверки меньше полусотни: " + (VENUE_DAILY - total));
    }

    /**
     * У каждого бота доля с запасом к измеренному расходу.
     *
     * ⚠️ Свойство не косметическое: при исчерпании лимита бот не пропускает
     * постановку, а ВЫКЛЮЧАЕТСЯ совсем. Доля впритык означает, что в первый же
     * оживлённый день бот молча перестанет торговать, и мы получим не измерение,
     * а дыру в нём.
     */
    @Test
    void everyBotHasHeadroomOverItsMeasuredUsage() {
        for (String bot : BOTS) {
            int share = ExecLimits.maxPlacementsPerDay(bot);
            int usage = MEASURED_USAGE.get(bot);
            assertTrue(share >= usage * 3 / 2,
                    "боту " + bot + " дано " + share + " при измеренном расходе " + usage
                            + " — запаса меньше полутора крат, упрётся в первый оживлённый день");
        }
    }

    /**
     * Тонкие пары получают меньше биткойна и эфира.
     *
     * Расход идёт за исполнениями, а на ENA и PEPE их в разы меньше: 41 и 38 в
     * сутки против 160 у BTC. Раздать поровну значило бы отнять бюджет у тех,
     * кто его действительно тратит.
     */
    @Test
    void thinPairsGetLessThanTheDeepOnes() {
        for (String thin : List.of("d", "e", "f")) {
            for (String deep : List.of("a", "b", "c")) {
                assertTrue(ExecLimits.maxPlacementsPerDay(thin)
                                < ExecLimits.maxPlacementsPerDay(deep),
                        "тонкая пара " + thin + " не должна получать больше глубокой " + deep);
            }
        }
    }

    @Test
    void unknownBotGetsConservativeShare() {
        int unknown = ExecLimits.maxPlacementsPerDay("z");
        int smallest = BOTS.stream().mapToInt(ExecLimits::maxPlacementsPerDay).min().orElseThrow();
        assertEquals(smallest, unknown,
                "незнакомая метка обязана получать САМУЮ МАЛУЮ долю: иначе опечатка в"
                        + " --revx.exec.bot-id молча выдаёт бюджет больше настроенного");
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(null));
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(""));
    }

    @Test
    void idIsMatchedByFirstLetterAndCase() {
        assertEquals(ExecLimits.maxPlacementsPerDay("a"), ExecLimits.maxPlacementsPerDay("A"));
        assertEquals(ExecLimits.maxPlacementsPerDay("a"), ExecLimits.maxPlacementsPerDay(" a "));
    }

    /**
     * Экспозиции хватает на полный инвентарь плюс сетку в книге.
     *
     * ⚠️ Предел блокирует ЗАЯВКУ, а не кричит. Если $20 инвентаря и три уровня
     * по доллару на сторону в него не влезут, дальний уровень молча перестанет
     * ставиться, и сетка окажется не той, что измерена.
     */
    @Test
    void exposureFitsFullInventoryPlusGrid() {
        double inventoryCap = 20.0;
        double grid = 3 * 1.0 * 2;                // три уровня, обе стороны, лот $1
        assertTrue(ExecLimits.exposureAllowed(inventoryCap + grid),
                "предел экспозиции " + ExecLimits.MAX_TOTAL_EXPOSURE_USDC
                        + " не вмещает потолок инвентаря $20 и сетку на $6");
    }
}
