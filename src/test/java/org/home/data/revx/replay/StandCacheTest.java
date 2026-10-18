package org.home.data.revx.replay;

import org.home.data.revx.sim.Side;
import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Кэши стенда (17.10.2026): запись и чтение возвращают те же данные до бита. */
class StandCacheTest {

    @Test
    void marketRoundTrip() throws Exception {
        List<MarketTrade> trades = List.of(
                new MarketTrade(1_000, 100.25, 0.5, Side.BUY),
                new MarketTrade(2_000, 100.10, 1.25, null),
                new MarketTrade(3_000, 99.95, 0.01, Side.SELL));
        List<BookView> books = List.of(
                new BookView(List.of(new BookView.Level(100.0, 1), new BookView.Level(99.9, 2)),
                        List.of(new BookView.Level(100.1, 3))),
                new BookView(List.of(new BookView.Level(100.05, 0.3)),
                        List.of(new BookView.Level(100.2, 0.7), new BookView.Level(100.3, 0.9))));
        MarketData m = MarketData.of(trades, new long[]{1_500, 2_500}, books);
        Path f = Files.createTempFile("mkt", ".bin");
        m.writeCache(f);
        MarketData r = MarketData.readCache(f);

        assertEquals(m.trades(), r.trades());
        for (long ts : new long[]{1_500, 2_000, 2_500, 9_000}) {
            assertEquals(m.bookAt(ts).bids(), r.bookAt(ts).bids());
            assertEquals(m.bookAt(ts).asks(), r.bookAt(ts).asks());
        }
    }

    @Test
    void fairRoundTrip() throws Exception {
        List<ReplayFair.Tick> ticks = List.of(
                new ReplayFair.Tick(1, 100.5, 100.4, 100.6, 0, true, null, 0),
                new ReplayFair.Tick(2, 100.7, null, null, 0, false, "гейт: курс", 0.25),
                new ReplayFair.Tick(3, 100.8, 100.7, null, 1.5, false, "гейт: курс", 0));
        Path f = Files.createTempFile("fair", ".bin");
        FairCache.write(f, new FairCache.Result(ticks, 18));
        FairCache.Result r = FairCache.read(f);
        assertEquals(18, r.pairs());
        assertEquals(ticks, r.ticks());
    }
}
