package org.home.data.revx.sim;

import org.home.data.revx.BookFlags;
import org.home.data.revx.RevxConfig;
import org.home.data.revx.RevxDb;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сборка окон симуляции из собранных данных.
 *
 * Окно = снимок книги USDC-пары + справедливая цена на тот же момент + сделки,
 * прошедшие ДО следующего снимка. Справедливая цена берётся из опорной USD-книги
 * через курс, посчитанный по всей вселенной (FairPrice), а не из книги, в которой
 * торгуем (ТЗ §4.6 п.3).
 *
 * Правило available_at соблюдается буквально: окно с меткой t собирается из
 * снимков, полученных к моменту t, а сделки в него попадают только те, что
 * произошли ПОСЛЕ t. Заглянуть вперёд негде.
 */
@Component
@Lazy
public class SimDataReader {

    private static final String SELECT_BOOK = """
            SELECT t_recv_ms, flags, skew_ms,
                   bp1, bq1, bp2, bq2, bp3, bq3, bp4, bq4, bp5, bq5,
                   ap1, aq1, ap2, aq2, ap3, aq3, ap4, aq4, ap5, aq5
            FROM revx_book
            WHERE symbol = ? AND leg = 'usdc' AND t_recv_ms BETWEEN ? AND ?
            ORDER BY t_recv_ms
            """;

    private static final String SELECT_TRADES = """
            SELECT ts_ms, price, qty, side
            FROM revx_trade
            WHERE symbol = ? AND ts_ms BETWEEN ? AND ?
            ORDER BY ts_ms
            """;

    private final RevxDb db;
    private final RevxConfig cfg;
    private final SnapshotReader snapshots;

    public SimDataReader(RevxDb db, RevxConfig cfg, SnapshotReader snapshots) {
        this.db = db;
        this.cfg = cfg;
        this.snapshots = snapshots;
    }

    public record Dataset(String symbol, List<SimEngine.Window> windows, long fromMs, long toMs,
                          int tradesTotal, int tradesUnknownSide, int windowsPaused) {

        public long spanMs() {
            return toMs - fromMs;
        }

        /**
         * ФАКТИЧЕСКИЙ шаг окна — по данным, а не по конфигу.
         *
         * Окно симуляции равно строке книги торгуемой пары, поэтому шаг задаёт
         * период опроса ЭТОЙ пары. С появлением быстрого яруса (BTC раз в
         * секунду при общем периоде 5 с) конфигурационное число перестало
         * описывать данные: лестница задержки, считавшая рунги делением на
         * конфиг, промахивалась ровно в пять раз (док. 94).
         */
        public double windowPeriodSec() {
            return windows.size() < 2 ? 1
                    : spanMs() / 1000.0 / windows.size();
        }
    }

    /**
     * @param bucketMs корзина для расчёта курса: срез рынка, по которому считается
     *                 медиана implied (должен совпадать с периодом опроса книг)
     */
    public Dataset read(String symbol, long fromMs, long toMs, long bucketMs) {
        String base = symbol.substring(0, symbol.indexOf('/'));

        // 1. Курс и гейты — по всей вселенной, срезами
        SnapshotReader.Window universe = snapshots.read(fromMs, toMs, cfg.fairMaxSkewMs());
        FairPrice.Limits limits = new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct());
        TreeMap<Long, FairPrice.Result> fairByBucket = new TreeMap<>();
        for (Map.Entry<Long, List<PairQuote>> bucket : SnapshotReader.bucket(universe.quotes(), bucketMs)) {
            fairByBucket.put(bucket.getKey(), FairPrice.compute(bucket.getValue(), limits));
        }

        // 2. Книги торгуемой пары
        record BookRow(long tsMs, BookView book) {
        }
        List<BookRow> books = new ArrayList<>();
        db.query(SELECT_BOOK, rs -> {
            int flags = rs.getInt("flags");
            if (BookFlags.has(flags, BookFlags.CROSSED) || BookFlags.has(flags, BookFlags.EMPTY_SIDE)) {
                return null;
            }
            List<BookView.Level> bids = new ArrayList<>(5);
            List<BookView.Level> asks = new ArrayList<>(5);
            for (int i = 1; i <= 5; i++) {
                // NULL в колонке уровня = уровня не было; getDouble вернёт 0
                double bp = rs.getDouble("bp" + i);
                double bq = rs.getDouble("bq" + i);
                if (bp > 0 && bq > 0) {
                    bids.add(new BookView.Level(bp, bq));
                }
                double ap = rs.getDouble("ap" + i);
                double aq = rs.getDouble("aq" + i);
                if (ap > 0 && aq > 0) {
                    asks.add(new BookView.Level(ap, aq));
                }
            }
            books.add(new BookRow(rs.getLong("t_recv_ms"), new BookView(bids, asks)));
            return null;
        }, symbol, fromMs, toMs);

        // 3. Сделки; сторона агрессора приходит из ленты (документация Revolut X)
        record TradeRow(long tsMs, double price, double qty, Side aggressor) {
        }
        List<TradeRow> trades = new ArrayList<>();
        int[] unknownSide = {0};
        db.query(SELECT_TRADES, rs -> {
            String side = rs.getString("side");
            Side aggressor = "buy".equalsIgnoreCase(side) ? Side.BUY
                    : "sell".equalsIgnoreCase(side) ? Side.SELL : null;
            if (aggressor == null) {
                unknownSide[0]++;
            }
            trades.add(new TradeRow(rs.getLong("ts_ms"), rs.getDouble("price"),
                    rs.getDouble("qty"), aggressor));
            return null;
        }, symbol, fromMs, toMs);
        trades.sort(Comparator.comparingLong(TradeRow::tsMs));

        // 4. Сшивка: сделки раскладываются по окнам между снимками
        List<SimEngine.Window> windows = new ArrayList<>(books.size());
        int tradeIdx = 0;
        int paused = 0;
        for (int i = 0; i < books.size(); i++) {
            BookRow row = books.get(i);
            long windowEnd = i + 1 < books.size() ? books.get(i + 1).tsMs() : toMs;

            List<MarketTrade> inWindow = new ArrayList<>();
            while (tradeIdx < trades.size() && trades.get(tradeIdx).tsMs() < row.tsMs()) {
                tradeIdx++;                       // сделки до первого снимка отбрасываем
            }
            int cursor = tradeIdx;
            while (cursor < trades.size() && trades.get(cursor).tsMs() < windowEnd) {
                TradeRow t = trades.get(cursor);
                inWindow.add(new MarketTrade(t.tsMs(), t.price(), t.qty(), t.aggressor()));
                cursor++;
            }
            tradeIdx = cursor;

            FairPrice.Result fair = fairByBucket.floorEntry(row.tsMs()) == null
                    ? null : fairByBucket.floorEntry(row.tsMs()).getValue();
            FairPrice.PairState state = fair == null ? null : fair.pair(base);
            boolean quotable = state != null && state.quotable();
            double fairPrice = state == null ? Double.NaN : state.fairUsdc();
            if (!quotable) {
                paused++;
            }
            windows.add(new SimEngine.Window(row.tsMs(), fairPrice, quotable, row.book(), inWindow));
        }

        return new Dataset(symbol, windows, fromMs, toMs, trades.size(), unknownSide[0], paused);
    }
}
