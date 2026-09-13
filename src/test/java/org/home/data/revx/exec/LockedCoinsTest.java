package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠️ ЖИВОЙ СЛУЧАЙ 13.09.2026: «на счёте монеты вижу, а боты их не видят».
 *
 * Владелец сделал {@code /release} и {@code /claim} на всех шести ботах и всё
 * равно остался с ничейными лотами. Причина была в знаменателе реестра: он
 * считался как {@code свой available + СВОИ стоящие аски}, то есть остаток счёта
 * МИНУС монеты, запертые в заявках соседнего бота. Свободное реестр считает как
 * {@code знаменатель − живые претензии}, поэтому претензия соседа вычиталась
 * дважды — его монеты и в знаменатель не входили, и из него же вычитались.
 *
 * Пока на паре работал один бот, разницы не было. С 12.09.2026 на каждой паре
 * ДВА бота (опыт «один уровень против трёх»), и ошибка стала постоянной: у BTC
 * при остатке счёта 0.00002529 и соседском аске 0.00001255 бот показывал
 * «свободно 0.00000018» вместо лота.
 *
 * Числа в тесте — из журнала бота e и реестра за 13.09.2026 12:08 UTC.
 */
class LockedCoinsTest {

    private static final BotTag A = new BotTag("a");
    private static final BotTag E = new BotTag("e");

    /** Аск соседа {@code e} на один лот BTC и покупка того же соседа (монету не запирает). */
    private static final String BOOK = """
            {"data":[
            {"id":"1","client_order_id":"eeeeeeee-fb87-4ec7-9acb-aba878f42c62",
             "symbol":"BTC/USDC","side":"sell","quantity":"0.00001255",
             "leaves_quantity":"0.00001255","price":"76900.0","created_date":1789290000000},
            {"id":"2","client_order_id":"eeeeeeee-b078-4f9c-9960-5c587376908f",
             "symbol":"BTC/USDC","side":"buy","quantity":"0.00001255",
             "leaves_quantity":"0.00001255","price":"76700.0","created_date":1789290000000},
            {"id":"3","client_order_id":"dddddddd-32d3-4d81-9ca3-1f86605e5a74",
             "symbol":"ETH/USDC","side":"sell","quantity":"0.0012",
             "leaves_quantity":"0.0012","price":"4600.0","created_date":1789290000000}]}""";

    private static AllocRegistry.Claim claim(String bot, double qty, boolean live) {
        return new AllocRegistry.Claim(bot, "BTC", qty, 0, 0, live);
    }

    @Test
    void neighbourAskIsNotOrphanWhileItsOwnerIsAlive() {
        QuoteLoop.Locked locked = QuoteLoop.lockedInOrders("BTC/USDC", A,
                ActiveOrder.parse(BOOK), List.of(claim("e", 0.00001256, true)));

        assertEquals(0.0, locked.mine(), 1e-12, "своих заявок у бота a нет");
        assertEquals(0.00001255, locked.liveNeighbours(), 1e-12);
        assertEquals(0.0, locked.orphans(), 1e-12, "у живого соседа монета не бесхозная");
    }

    /**
     * 🔑 Та самая ошибка. Свободное считается от остатка СЧЁТА, а не от «своего
     * доступного»: иначе монета соседа вычитается дважды и лот становится
     * невидимым.
     */
    @Test
    void freeIsCountedFromAccountTotalNotFromOwnAvailable() {
        double accountTotal = 0.00002529;      // available 0.00001274 + reserved 0.00001255
        double ownAvailablePlusOwnAsks = 0.00001274;   // так считалось раньше
        double neighbourClaim = 0.00001256;

        double wrong = ownAvailablePlusOwnAsks - neighbourClaim;
        double right = accountTotal - neighbourClaim;

        // 0.00000018 против лота 0.00001255 — ровно то, что видел владелец.
        assertTrue(wrong < 0.02 * 0.00001255, "старый счёт показывал пустоту: " + wrong);
        assertEquals(0.00001273, right, 1e-11, "ничейный лот обязан быть виден");
    }

    /**
     * Заявка БЕЗ живого хозяина — ловушка: монета в остатке видна, но забравший
     * её получит фантомный инвентарь (заявка исполнится сама). Такие монеты из
     * свободного вычитаются.
     */
    @Test
    void askOfDeadBotIsOrphanAndMustBeSubtracted() {
        QuoteLoop.Locked locked = QuoteLoop.lockedInOrders("BTC/USDC", A,
                ActiveOrder.parse(BOOK), List.of(claim("e", 0.00001256, false)));

        assertEquals(0.00001255, locked.orphans(), 1e-12);
        assertEquals(1, locked.orphanOrders());
        assertEquals(0.0, locked.liveNeighbours(), 1e-12);
    }

    /** Заявка без метки (досталась от старой версии) хозяина не имеет — тоже ловушка. */
    @Test
    void untaggedOrderCountsAsOrphan() {
        String legacy = """
                {"data":[{"id":"9","client_order_id":"79e0b544-21be-4434-b41d-fd841a820e45",
                 "symbol":"BTC/USDC","side":"sell","quantity":"0.00004000",
                 "leaves_quantity":"0.00004000","price":"77000.0","created_date":1789290000000}]}""";

        QuoteLoop.Locked locked = QuoteLoop.lockedInOrders("BTC/USDC", A,
                ActiveOrder.parse(legacy), List.of(claim("e", 0.00001256, true)));

        assertEquals(0.00004, locked.orphans(), 1e-12);
    }

    /** Чужая пара не считается: по ETH нас не спрашивали. */
    @Test
    void otherSymbolIsIgnored() {
        QuoteLoop.Locked locked = QuoteLoop.lockedInOrders("BTC/USDC", E,
                ActiveOrder.parse(BOOK), List.of(claim("e", 0.00001256, true)));

        assertEquals(0.00001255, locked.mine(), 1e-12, "своя продажа BTC");
        assertEquals(0.0, locked.liveNeighbours(), 1e-12);
        assertEquals(0.0, locked.orphans(), 1e-12, "аск бота d по ETH к нашей паре не относится");
    }

    /**
     * Частично исполненная заявка запирает только ОСТАТОК — и он приходит в
     * {@code leaves_quantity}, который {@link ActiveOrder} и кладёт в {@code size}.
     * Первая версия правки вычитала {@code filled_quantity} ещё раз и занижала
     * запертое почти вдвое.
     */
    @Test
    void partiallyFilledLocksOnlyTheRemainder() {
        String partial = """
                {"data":[{"id":"7","client_order_id":"eeeeeeee-1111-4111-8111-111111111111",
                 "symbol":"BTC/USDC","side":"sell","quantity":"0.00003765",
                 "filled_quantity":"0.00001023","leaves_quantity":"0.00002742",
                 "status":"partially_filled","price":"77000.0","created_date":1789290000000}]}""";

        QuoteLoop.Locked locked = QuoteLoop.lockedInOrders("BTC/USDC", A,
                ActiveOrder.parse(partial), List.of(claim("e", 0.00002742, false)));

        assertEquals(0.00002742, locked.orphans(), 1e-11);
    }
}
