package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Аварийный потолок постановок — то, что осталось от неподвижных долей.
 *
 * ⚠️ <b>Роль этого предела изменилась 07.09.2026.</b> Раньше он делил суточный
 * лимит между ботами, и тест сторожил, чтобы сумма долей укладывалась в тысячу.
 * Делит теперь {@link PlacementBudget} — общее ведро, из которого боты берут по
 * мере надобности; его свойства сторожит {@code PlacementBudgetTest}.
 *
 * Прежнюю раскладку пришлось отменить, потому что она предсказывала расход
 * плохо: у ADA спрос вышел 217 против выделенных 80, у PEPE 111 против 60, и
 * оба бота встали — ADA на 11 часов, PEPE на 7 — при 240 неиспользованных
 * постановках у соседей. Сумма спросов при этом была 871 из 1000, то есть
 * бюджета хватало, и виновата была именно нарезка.
 *
 * Здесь остались свойства предохранителя: он должен пропускать любой
 * наблюдавшийся расход, но не давать одному боту выбрать всю тысячу в одиночку,
 * и незнакомая метка не должна получать больше знакомой.
 */
class PlacementLimitTest {

    /** Столько даёт площадка на аккаунт. */
    private static final int VENUE_DAILY = 1000;

    private static final List<String> BOTS = List.of("a", "b", "c", "d", "e", "f");

    /**
     * Спрос, ЗАМЕРЕННЫЙ на живых ботах за сутки 06-07.09.2026.
     *
     * Это не оценка по закону прихода, как было в прежней версии теста, а счёт
     * фактических {@code POST /orders} по журналам, приведённый к суткам. Именно
     * эти числа и опровергли прежнюю раскладку.
     */
    private static final Map<String, Integer> MEASURED_DEMAND = Map.of(
            "a", 218,      // BTC
            "b", 95,       // SOL
            "c", 99,       // ETH
            "d", 217,      // ADA
            "e", 131,      // ENA
            "f", 111);     // PEPE

    /**
     * Предохранитель обязан пропускать любой наблюдавшийся спрос.
     *
     * Иначе он сработает вместо ведра и вернёт ровно ту беду, ради которой ведро
     * заводилось: остановку бота при живом общем бюджете.
     */
    @Test
    void аварийныйПотолокВышеЛюбогоЗамеренногоСпроса() {
        for (String bot : BOTS) {
            int cap = ExecLimits.maxPlacementsPerDay(bot);
            int demand = MEASURED_DEMAND.get(bot);
            assertTrue(cap >= demand * 2,
                    "боту " + bot + " дан потолок " + cap + " при замеренном спросе "
                            + demand + " — сработает раньше общего ведра");
        }
    }

    /**
     * Один бот не должен успеть выбрать весь лимит аккаунта.
     *
     * Предохранитель на то и предохранитель, что действует, когда ведро сломано.
     * В этот момент единственная защита аккаунта — то, что одного бота не хватит
     * на всю тысячу.
     */
    @Test
    void одинБотНеВыбираетВесьЛимитАккаунта() {
        for (String bot : BOTS) {
            assertTrue(ExecLimits.maxPlacementsPerDay(bot) <= VENUE_DAILY / 2,
                    "бот " + bot + " в одиночку может выбрать больше половины лимита аккаунта");
        }
    }

    /**
     * Рабочий бюджет ведра обязан быть ниже лимита площадки.
     *
     * Считаем мы только свои постановки, а лимит расходует и то, чего мы не
     * видим: ручные заявки, разовые команды, возможно — отказные POST.
     */
    @Test
    void ёмкостьВедраОставляетЗапасКЛимитуПлощадки() {
        assertTrue(PlacementBudget.CAPACITY <= VENUE_DAILY - 100,
                "ведро " + PlacementBudget.CAPACITY + " не оставляет сотни в запас к "
                        + VENUE_DAILY);
        int floors = BOTS.size() * PlacementBudget.FLOOR_PER_DAY;
        assertTrue(floors < PlacementBudget.CAPACITY,
                "полы шести ботов (" + floors + ") не помещаются в ведро "
                        + PlacementBudget.CAPACITY + " — общего котла не остаётся");
    }

    /**
     * Пол каждому обязан покрывать спрос самых скромных пар.
     *
     * Гарантия, которой не хватает на обычный день, гарантией не является.
     */
    @Test
    void полПокрываетСпросСкромныхПар() {
        int floor = PlacementBudget.FLOOR_PER_DAY;
        assertTrue(floor >= MEASURED_DEMAND.get("b"),
                "пол " + floor + " ниже спроса SOL " + MEASURED_DEMAND.get("b"));
        assertTrue(floor >= MEASURED_DEMAND.get("c"),
                "пол " + floor + " ниже спроса ETH " + MEASURED_DEMAND.get("c"));
    }

    @Test
    void незнакомаяМеткаНеПолучаетБольшеЗнакомой() {
        int unknown = ExecLimits.maxPlacementsPerDay("z");
        int smallest = BOTS.stream().mapToInt(ExecLimits::maxPlacementsPerDay).min().orElseThrow();
        assertEquals(smallest, unknown,
                "незнакомая метка обязана получать САМУЮ МАЛУЮ долю: иначе опечатка в"
                        + " --revx.exec.bot-id молча выдаёт бюджет больше настроенного");
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(null));
        assertEquals(unknown, ExecLimits.maxPlacementsPerDay(""));
    }
}
