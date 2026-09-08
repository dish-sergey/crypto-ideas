package org.home.data.revx.place;

import org.home.data.revx.layout.DesiredOrder;
import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Два способа привести книгу к желаемой расстановке.
 *
 * Разница между ними и есть цена задержки перестановки — величина, которую
 * иначе неоткуда взять. Поэтому тесты сторожат не «работает», а ровно те
 * свойства, ради которых версии и различаются.
 */
class PlacerTest {

    private static final long NOW = 1_700_000_000_000L;
    /** Порог перевыставления боевой настройки. */
    private static final double TH = 0.0002;

    private static DesiredOrder want(Side side, int level, double price) {
        return new DesiredOrder(side, level, price, 1.0);
    }

    private static RestingOrder resting(Side side, int level, String id, double price) {
        return new RestingOrder(side, level, id, price, 1.0, 0);
    }

    private static RestingOrder empty(Side side, int level) {
        return new RestingOrder(side, level, null, 0, 0, 0);
    }

    private static long count(List<Action> plan, Action.Kind kind) {
        return plan.stream().filter(a -> a.kind() == kind).count();
    }

    @Test
    void ограниченныйДвигаетТолькоОднуЗаявку() {
        var placer = new RateLimitedPlacer(TH);
        var desired = List.of(want(Side.BUY, 0, 99.0), want(Side.BUY, 1, 98.0),
                want(Side.BUY, 2, 97.0));
        var resting = List.of(resting(Side.BUY, 0, "a", 99.5),
                resting(Side.BUY, 1, "b", 98.5), resting(Side.BUY, 2, "c", 97.5));
        var plan = placer.plan(desired, resting, NOW);
        assertEquals(1, count(plan, Action.Kind.REPLACE),
                "у шести ботов по одной замене в тик — это 6 запросов при лимите 10");
    }

    @Test
    void мгновенныйДвигаетВсе() {
        var placer = new InstantPlacer(TH);
        var desired = List.of(want(Side.BUY, 0, 99.0), want(Side.BUY, 1, 98.0),
                want(Side.BUY, 2, 97.0));
        var resting = List.of(resting(Side.BUY, 0, "a", 99.5),
                resting(Side.BUY, 1, "b", 98.5), resting(Side.BUY, 2, "c", 97.5));
        assertEquals(3, count(placer.plan(desired, resting, NOW), Action.Kind.REPLACE));
    }

    /**
     * Замена достаётся самой отставшей заявке, а не первой по порядку.
     *
     * Порядок обхода задан раздачей капитала. Отдать замену по нему значило бы
     * обновлять дальние заявки, которые почти не двигаются, пока ближняя — та,
     * что и приносит исполнения, — висит по устаревшей цене.
     */
    @Test
    void заменаДостаётсяСамойОтставшей() {
        var placer = new RateLimitedPlacer(TH);
        var desired = List.of(want(Side.BUY, 0, 99.9), want(Side.BUY, 1, 90.0));
        var resting = List.of(resting(Side.BUY, 0, "ближний", 99.95),
                resting(Side.BUY, 1, "дальний", 98.0));
        var plan = placer.plan(desired, resting, NOW);
        assertEquals(1, plan.size());
        assertEquals("дальний", plan.get(0).venueId(),
                "право на замену обязано идти по величине расхождения");
    }

    /** Расхождение считается ОТНОСИТЕЛЬНОЕ: иначе биткойн всегда обгонит PEPE. */
    @Test
    void расхождениеОтносительноеАНеВДолларах() {
        var placer = new RateLimitedPlacer(TH);
        // BTC отстал на 8 долларов (0.01%), PEPE — на 0.0000002 (5.6%).
        var desired = List.of(want(Side.BUY, 0, 79_000.0), want(Side.SELL, 0, 0.0000034));
        var resting = List.of(resting(Side.BUY, 0, "btc", 79_008.0),
                resting(Side.SELL, 0, "pepe", 0.0000036));
        var plan = placer.plan(desired, resting, NOW);
        assertEquals("pepe", plan.get(0).venueId(),
                "в долларах BTC всегда больше — сравнивать надо доли");
    }

    /** Постановка потолком НЕ ограничена: пустой слот дороже устаревшей цены. */
    @Test
    void постановкиПотолкомНеОграничены() {
        var placer = new RateLimitedPlacer(TH);
        var desired = List.of(want(Side.BUY, 0, 99.0), want(Side.BUY, 1, 98.0),
                want(Side.BUY, 2, 97.0));
        var resting = List.of(empty(Side.BUY, 0), empty(Side.BUY, 1), empty(Side.BUY, 2));
        assertEquals(3, count(placer.plan(desired, resting, NOW), Action.Kind.PLACE));
    }

    /** Лишняя заявка снимается всегда: отмена бесплатна и безопасна. */
    @Test
    void лишнееСнимаетсяОбеимиВерсиями() {
        var desired = List.<DesiredOrder>of();
        var resting = List.of(resting(Side.BUY, 0, "a", 99.0), resting(Side.SELL, 0, "b", 101.0));
        for (Placer p : List.of(new RateLimitedPlacer(TH), new InstantPlacer(TH))) {
            assertEquals(2, count(p.plan(desired, resting, NOW), Action.Kind.CANCEL),
                    p.name() + ": лишние заявки обязаны сниматься");
        }
    }

    /**
     * Пауза после отказа соблюдается ОБЕИМИ версиями.
     *
     * Это не потолок темпа, а защита от долбёжки в отказывающую площадку: отказ
     * на замене стоит четырёх запросов, и повторять его каждую секунду так же
     * вредно, как долбиться постановкой.
     */
    @Test
    void паузаПослеОтказаСоблюдаетсяВсеми() {
        var desired = List.of(want(Side.BUY, 0, 99.0));
        var resting = List.of(new RestingOrder(Side.BUY, 0, "a", 99.9, 1.0, NOW + 30_000));
        for (Placer p : List.of(new RateLimitedPlacer(TH), new InstantPlacer(TH))) {
            assertTrue(p.plan(desired, resting, NOW).isEmpty(),
                    p.name() + ": заявку под паузой трогать нельзя");
        }
    }

    @Test
    void совпадающаяЗаявкаНеТрогаетсяНикем() {
        var desired = List.of(want(Side.BUY, 0, 99.0));
        var resting = List.of(resting(Side.BUY, 0, "a", 99.0));
        for (Placer p : List.of(new RateLimitedPlacer(TH), new InstantPlacer(TH))) {
            assertTrue(p.plan(desired, resting, NOW).isEmpty(),
                    p.name() + ": заявка уже на месте, двигать нечего");
        }
    }
}
