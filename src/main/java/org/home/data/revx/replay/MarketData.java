package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Рынок из базы стенда: лента сделок и снимки книги.
 *
 * Отсюда модели исполнения берут всё, что знают о реальности. Данные те же,
 * что видел живой бот, и из той же базы — иначе расхождение можно будет списать
 * на разные источники (док. 89 §4).
 *
 * ⚠️ Книга — не более ПЯТИ уровней на сторону: это потолок API площадки, а не
 * наше решение. Из-за него заявка вне видимой части считается неисполнимой:
 * ни объёма перед ней, ни того, дошла ли до неё торговля, мы не знаем.
 */
public final class MarketData {

    private static final Logger log = LoggerFactory.getLogger(MarketData.class);

    private final List<MarketTrade> trades;
    private final long[] bookTs;
    private final List<BookView> books;
    private int tradeCursor;
    private int bookCursor;

    /**
     * Рынок из готовых рядов — для тестов.
     *
     * Модели исполнения проверяются на СИНТЕТИЧЕСКОМ рынке намеренно: правила
     * дележа объёма и приоритета цены должны держаться на данных, которые
     * подобраны под проверку, а не на тех, где всё и так сходится.
     */
    public static MarketData of(List<MarketTrade> trades, long[] bookTs, List<BookView> books) {
        return new MarketData(trades, bookTs, books);
    }

    private MarketData(List<MarketTrade> trades, long[] bookTs, List<BookView> books) {
        this.trades = trades;
        this.bookTs = bookTs;
        this.books = books;
    }

    public int tradeCount() {
        return trades.size();
    }

    public int bookCount() {
        return books.size();
    }

    /**
     * Сделки в промежутке {@code (from, to]}.
     *
     * Курсор односторонний: повтор идёт вперёд, и перечитывать прошлое незачем.
     */
    public List<MarketTrade> tradesBetween(long fromMs, long toMs) {
        List<MarketTrade> out = new ArrayList<>();
        while (tradeCursor < trades.size() && trades.get(tradeCursor).tsMs() <= fromMs) {
            tradeCursor++;
        }
        int i = tradeCursor;
        while (i < trades.size() && trades.get(i).tsMs() <= toMs) {
            out.add(trades.get(i));
            i++;
        }
        return out;
    }

    /** Последний снимок книги не позже {@code tsMs}; {@code null}, если такого нет. */
    public BookView bookAt(long tsMs) {
        while (bookCursor + 1 < bookTs.length && bookTs[bookCursor + 1] <= tsMs) {
            bookCursor++;
        }
        if (bookTs.length == 0 || bookTs[bookCursor] > tsMs) {
            return null;
        }
        return books.get(bookCursor);
    }

    /**
     * Загрузить окно целиком.
     *
     * ⚠️ Сторона сделки в {@code revx_trade} — это АГРЕССОР. Сделка без стороны
     * исполнения не вызывает (ТЗ §4.3): трактовать неизвестное в свою пользу
     * нельзя, а в чужую — можно и нужно.
     */
    public static MarketData load(String standDbPath, String symbol, long fromMs, long toMs) {
        List<MarketTrade> trades = new ArrayList<>();
        List<Long> ts = new ArrayList<>();
        List<BookView> books = new ArrayList<>();
        String url = "jdbc:sqlite:file:" + standDbPath + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = '" + symbol
                            + "' AND ts_ms >= " + fromMs + " AND ts_ms <= " + toMs
                            + " ORDER BY ts_ms")) {
                while (rs.next()) {
                    String side = rs.getString(4);
                    Side aggressor = side == null ? null
                            : ("buy".equalsIgnoreCase(side) ? Side.BUY : Side.SELL);
                    trades.add(new MarketTrade(rs.getLong(1), rs.getDouble(2),
                            rs.getDouble(3), aggressor));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT t_recv_ms, bp1,bq1,bp2,bq2,bp3,bq3,bp4,bq4,bp5,bq5,"
                            + "ap1,aq1,ap2,aq2,ap3,aq3,ap4,aq4,ap5,aq5 FROM revx_book"
                            + " WHERE symbol = '" + symbol + "' AND t_recv_ms >= " + fromMs
                            + " AND t_recv_ms <= " + toMs + " ORDER BY t_recv_ms")) {
                while (rs.next()) {
                    List<BookView.Level> bids = new ArrayList<>();
                    List<BookView.Level> asks = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        double p = rs.getDouble(2 + i * 2);
                        double q = rs.getDouble(3 + i * 2);
                        if (p > 0 && q > 0) {
                            bids.add(new BookView.Level(p, q));
                        }
                    }
                    for (int i = 0; i < 5; i++) {
                        double p = rs.getDouble(12 + i * 2);
                        double q = rs.getDouble(13 + i * 2);
                        if (p > 0 && q > 0) {
                            asks.add(new BookView.Level(p, q));
                        }
                    }
                    ts.add(rs.getLong(1));
                    books.add(new BookView(bids, asks));
                }
            }
        } catch (Exception e) {
            log.error("не прочитался рынок из {}: {}", standDbPath, e.toString());
        }
        long[] arr = new long[ts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ts.get(i);
        }
        log.warn("рынок {}: сделок {}, снимков книги {}", symbol, trades.size(), books.size());
        return new MarketData(trades, arr, books);
    }
}
