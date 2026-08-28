package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетические сценарии с известным ответом (ТЗ §7).
 *
 * Последний из них — главный: он проверяет, что стенд СПОСОБЕН обнаружить
 * неблагоприятный отбор. Стенд, который не умеет показать отрицательный
 * результат на данных, где он заведомо есть, бесполезен.
 */
class ScenarioTest {

    private static final double D = 0.0004;              // отступ 0.04%
    private static final double SIZE = 1.0;
    private static final double CAP = 5.0;

    private static Quoter.Params params(double skewK) {
        return new Quoter.Params(D, SIZE, CAP, skewK, 0.00005, 0.01);
    }

    private static SimEngine engine() {
        return new SimEngine(params(0.0002), ExecutionModel.Limits.of(0.0001), 0.0);
    }

    /** Книга вокруг цены: наши котировки всегда попадают в видимую часть. */
    private static BookView bookAround(double price) {
        double tick = price * 0.001;
        return BookView.of(
                new double[][]{{price - tick, 10}, {price - 2 * tick, 10}, {price - 3 * tick, 10},
                        {price - 4 * tick, 10}, {price - 5 * tick, 10}},
                new double[][]{{price + tick, 10}, {price + 2 * tick, 10}, {price + 3 * tick, 10},
                        {price + 4 * tick, 10}, {price + 5 * tick, 10}});
    }

    @Test
    void zeroFlowGivesZeroFillsAndZeroPnl() {
        List<SimEngine.Window> windows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            windows.add(SimEngine.Window.of(i * 5_000L, 100.0, bookAround(100.0), List.of()));
        }

        SimEngine.Result result = engine().run(windows);

        assertEquals(0, result.fills().size(), "без потока исполнений быть не может");
        assertEquals(0.0, result.pnl().total(), 1e-12);
        assertEquals(0.0, result.pnl().spreadCapture(), 1e-12);
        assertEquals(0.0, result.pnl().inventoryPnl(), 1e-12);
    }

    /**
     * Строго односторонний поток вниз: инвентарь упирается в потолок, бид
     * перестаёт выставляться, захват спреда мал, переоценка инвентаря
     * отрицательна (ТЗ §7).
     */
    @Test
    void oneWayFlowDownFillsInventoryAndLosesOnIt() {
        List<SimEngine.Window> windows = new ArrayList<>();
        double fair = 100.0;
        for (int i = 0; i < 60; i++) {
            fair *= 0.999;                                   // рынок валится
            double bid = fair * (1 - D - 0.0002 * 0);        // примерно наша котировка
            windows.add(SimEngine.Window.of(i * 5_000L, fair, bookAround(fair),
                    List.of(MarketTrade.sell(i * 5_000L + 100, bid * 0.999, 3))));
        }

        SimEngine.Result result = engine().run(windows);
        PnlBook.Decomposition pnl = result.pnl();

        assertTrue(pnl.inventory() >= CAP - 1e-9,
                "односторонний поток вниз обязан набить инвентарь до потолка: " + pnl.inventory());
        assertTrue(result.windowsAtCap() > 0, "и какое-то время простоять с полным инвентарём");
        assertTrue(pnl.inventoryPnl() < 0,
                "инвентарь куплен по дороге вниз — переоценка отрицательна: " + pnl.inventoryPnl());
        assertTrue(Math.abs(pnl.spreadCapture()) < Math.abs(pnl.inventoryPnl()),
                "захват спреда должен быть мал против убытка по инвентарю");
        assertTrue(pnl.reconciles(1e-9), "разложение обязано сходиться");
    }

    /**
     * Зеркало предыдущего сценария — и та беда, которую стенд год не измерял.
     *
     * Строго односторонний поток ВВЕРХ бьёт только по аску. Инвентарь стартует с
     * нуля, продавать нечего, аск не выставляется вовсе — и поток проходит мимо:
     * ни одного исполнения, всё время в одностороннем режиме. Полный инвентарь
     * стенд считал с самого начала, нулевой — нет, и из-за этого модель не
     * показывала состояние, которое на живом счёте заняло больше половины
     * времени (док. 93).
     */
    @Test
    void oneWayFlowUpFindsNoInventoryToSell() {
        List<SimEngine.Window> windows = new ArrayList<>();
        double fair = 100.0;
        for (int i = 0; i < 60; i++) {
            fair *= 1.001;                                   // рынок растёт
            double ask = fair * (1 + D);                     // примерно наша котировка
            windows.add(SimEngine.Window.of(i * 5_000L, fair, bookAround(fair),
                    List.of(MarketTrade.buy(i * 5_000L + 100, ask * 1.001, 3))));
        }

        SimEngine.Result result = engine().run(windows);

        assertEquals(0, result.fills().size(),
                "продавать нечем, а покупателей в потоке нет — исполнений быть не может");
        assertEquals(0.0, result.pnl().inventory(), 1e-12, "инвентарь обязан остаться нулевым");
        assertEquals(0, result.windowsAtCap(), "до потолка тут не добраться");
        assertTrue(result.windowsAtZero() >= windows.size() - 1,
                "почти всё время аска нет вовсе: " + result.windowsAtZero()
                        + " из " + windows.size());
    }

    /**
     * Идеальный колебательный поток без тренда: захват спреда близок к
     * теоретическому 2·d за round-trip, переоценка инвентаря около нуля.
     */
    @Test
    void oscillatingFlowCapturesTwiceTheOffsetPerRoundTrip() {
        double fair = 100.0;
        List<SimEngine.Window> windows = new ArrayList<>();
        int roundTrips = 20;
        for (int i = 0; i < roundTrips; i++) {
            // окно 1: продавец добивает до нашего бида
            double bid = fair * (1 - D);
            windows.add(SimEngine.Window.of(i * 20_000L, fair, bookAround(fair),
                    List.of(MarketTrade.sell(i * 20_000L + 100, bid, 1))));
            // окно 2: покупатель забирает наш аск (инвентарь есть, аск выставлен)
            double ask = fair * (1 + D);
            windows.add(SimEngine.Window.of(i * 20_000L + 10_000L, fair, bookAround(fair),
                    List.of(MarketTrade.buy(i * 20_000L + 10_100L, ask * 1.0001, 1))));
        }

        // скос выключен: он смещал бы котировки и размывал теоретическую величину
        SimEngine engine = new SimEngine(params(0.0), ExecutionModel.Limits.of(0.0001), 0.0);
        SimEngine.Result result = engine.run(windows);
        PnlBook.Decomposition pnl = result.pnl();

        int roundTripsDone = (int) result.fills().stream().filter(f -> f.side() == Side.SELL).count();
        assertEquals(roundTrips, roundTripsDone, "каждый цикл обязан давать ровно один round-trip");
        double capturePerRoundTrip = pnl.spreadCapture() / roundTripsDone;
        double theoretical = 2 * D * 100.0;                  // 2·d от цены, на объём 1

        assertEquals(theoretical, capturePerRoundTrip, theoretical * 0.35,
                "захват должен быть близок к 2·d за round-trip: " + capturePerRoundTrip);
        assertTrue(Math.abs(pnl.inventoryPnl()) < Math.abs(pnl.spreadCapture()) * 0.25,
                "без тренда переоценка инвентаря должна быть около нуля: " + pnl.inventoryPnl());
        assertTrue(pnl.reconciles(1e-9));
    }

    /**
     * ГЛАВНЫЙ сценарий: поток только по лучшей цене со стороны информированного
     * агрессора. Нас исполняют ровно перед движением против нас — markout(60с)
     * обязан быть отрицательным. Если стенд этого не показывает, он бесполезен.
     */
    @Test
    void informedFlowProducesNegativeMarkout() {
        List<SimEngine.Window> windows = new ArrayList<>();
        double fair = 100.0;
        long t = 0;
        for (int i = 0; i < 30; i++) {
            // Нас исполняют по биду. Цена сделки уходит ЧУТЬ ГЛУБЖЕ нашей котировки:
            // информированный продавец сметает уровень, а не печатает ровно по нему.
            // (При печати ровно по цене скос после первой же покупки уводит наш бид
            // ниже потока, и сценарий проверял бы работу скоса, а не markout.)
            windows.add(SimEngine.Window.of(t, fair, bookAround(fair),
                    List.of(MarketTrade.sell(t + 100, fair * (1 - D - 0.0005), 2))));
            t += 5_000;
            // ...и сразу после этого цена уходит вниз и там остаётся
            for (int k = 0; k < 13; k++) {
                fair *= 0.9995;
                windows.add(SimEngine.Window.of(t, fair, bookAround(fair), List.of()));
                t += 5_000;
            }
        }

        SimEngine.Result result = engine().run(windows);
        Markout.Stats immediate = Markout.compute(result.fills(), result.fairSeries(), 0);
        Markout.Stats after60s = Markout.compute(result.fills(), result.fairSeries(), 60_000);

        // инвентарь упирается в потолок (обратного потока нет), поэтому исполнений
        // ровно столько, сколько влезает до потолка — это тоже часть ожидаемого ответа
        assertEquals((int) CAP, immediate.fills(),
                "исполнений должно быть ровно до потолка инвентаря");
        assertTrue(immediate.mean() > 0,
                "в момент исполнения покупка ниже справедливой цены выглядит выгодной: " + immediate.mean());
        assertTrue(after60s.mean() < 0,
                "через 60 с цена ушла против нас — markout обязан стать отрицательным: "
                        + after60s.mean());
        assertTrue(after60s.mean() < immediate.mean(),
                "и он обязан быть хуже мгновенного — это и есть неблагоприятный отбор");
    }

    @Test
    void quotingStopsWhenFairPriceIsUnreliable() {
        List<SimEngine.Window> windows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // курс ненадёжен (гейт FairPrice) — котировать нельзя, что бы ни шло в ленте
            windows.add(new SimEngine.Window(i * 5_000L, 100.0, false, bookAround(100.0),
                    List.of(MarketTrade.sell(i * 5_000L + 100, 99.0, 10))));
        }

        SimEngine.Result result = engine().run(windows);

        assertEquals(0, result.fills().size(), "с выключенным котированием исполнений быть не может");
        assertEquals(20, result.windowsPaused());
    }

    @Test
    void resultIsBitwiseReproducible() {
        List<SimEngine.Window> windows = new ArrayList<>();
        double fair = 100.0;
        for (int i = 0; i < 50; i++) {
            fair *= (i % 3 == 0) ? 1.0002 : 0.9999;
            windows.add(SimEngine.Window.of(i * 5_000L, fair, bookAround(fair),
                    List.of(MarketTrade.sell(i * 5_000L + 100, fair * (1 - D), 1.5),
                            MarketTrade.buy(i * 5_000L + 200, fair * (1 + D), 1.5))));
        }

        SimEngine.Result first = engine().run(windows);
        SimEngine.Result second = engine().run(windows);

        assertEquals(first.pnl().total(), second.pnl().total(), 0.0,
                "повторный прогон обязан давать побитово тот же результат (ТЗ §7)");
        assertEquals(first.fills().size(), second.fills().size());
        assertEquals(first.requotes(), second.requotes());
    }
}
