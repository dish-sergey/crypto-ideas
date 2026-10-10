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
    /**
     * 🔑 КНИГА ПЛОТНЫМИ МАССИВАМИ (09.10.2026). Прежде каждый снимок жил списками
     * объектов {@code Level}: ~2 млн снимков × ~100 уровней за 24 дня — 200 млн мелких
     * объектов, 8–9 ГБ кучи, и два прогона длинного окна в 32 ГБ не помещались.
     * Теперь цены и объёмы лежат в двух {@code double[]} подряд, снимок собирается в
     * {@link BookView} только при обращении (с кэшем последнего). Значения те же до
     * бита — {@code double}, как и были.
     * Снимок {@code i}: биды в {@code [bidOff[i], askOff[i])}, аски в
     * {@code [askOff[i], bidOff[i+1])}.
     */
    private final int[] bidOff;
    private final int[] askOff;
    private final double[] px;
    private final double[] qty;
    private int tradeCursor;
    private int bookCursor;
    private int afterCursor;
    private int cachedIdx = -1;
    private BookView cached;

    /**
     * Рынок из готовых рядов — для тестов.
     *
     * Модели исполнения проверяются на СИНТЕТИЧЕСКОМ рынке намеренно: правила
     * дележа объёма и приоритета цены должны держаться на данных, которые
     * подобраны под проверку, а не на тех, где всё и так сходится.
     */
    public static MarketData of(List<MarketTrade> trades, long[] bookTs, List<BookView> books) {
        Packer p = new Packer();
        for (BookView b : books) {
            p.add(b.bids(), b.asks());
        }
        return new MarketData(trades, bookTs, p.bidOff(), p.askOff(), p.px(), p.qty());
    }

    private MarketData(List<MarketTrade> trades, long[] bookTs, int[] bidOff, int[] askOff,
                       double[] px, double[] qty) {
        this.trades = trades;
        this.bookTs = bookTs;
        this.bidOff = bidOff;
        this.askOff = askOff;
        this.px = px;
        this.qty = qty;
    }

    /** Накопитель плотной книги: растущие массивы без промежуточных объектов. */
    private static final class Packer {
        private int n;
        private int m;
        private int[] bo = new int[1024];
        private int[] ao = new int[1024];
        private double[] p = new double[16384];
        private double[] q = new double[16384];

        void level(double price, double size) {
            if (m == p.length) {
                p = java.util.Arrays.copyOf(p, p.length + (p.length >> 1));
                q = java.util.Arrays.copyOf(q, q.length + (q.length >> 1));
            }
            p[m] = price;
            q[m] = size;
            m++;
        }

        void startBids() {
            if (n + 1 >= bo.length) {
                bo = java.util.Arrays.copyOf(bo, bo.length * 2);
                ao = java.util.Arrays.copyOf(ao, ao.length * 2);
            }
            bo[n] = m;
        }

        void startAsks() {
            ao[n] = m;
        }

        void end() {
            n++;
            bo[n] = m;
        }

        void add(List<BookView.Level> bids, List<BookView.Level> asks) {
            startBids();
            for (BookView.Level l : bids) {
                level(l.price(), l.qty());
            }
            startAsks();
            for (BookView.Level l : asks) {
                level(l.price(), l.qty());
            }
            end();
        }

        int[] bidOff() {
            int[] out = java.util.Arrays.copyOf(bo, n + 1);
            out[n] = m;
            return out;
        }

        int[] askOff() {
            return java.util.Arrays.copyOf(ao, n);
        }

        double[] px() {
            return p;                    // без обрезки: копия на пике удвоила бы память
        }

        double[] qty() {
            return q;
        }
    }

    /** Снимок {@code i} как {@link BookView}; последний собранный держится в кэше. */
    private BookView view(int i) {
        if (i == cachedIdx) {
            return cached;
        }
        List<BookView.Level> bids = new ArrayList<>(askOff[i] - bidOff[i]);
        for (int k = bidOff[i]; k < askOff[i]; k++) {
            bids.add(new BookView.Level(px[k], qty[k]));
        }
        List<BookView.Level> asks = new ArrayList<>(bidOff[i + 1] - askOff[i]);
        for (int k = askOff[i]; k < bidOff[i + 1]; k++) {
            asks.add(new BookView.Level(px[k], qty[k]));
        }
        cached = new BookView(bids, asks);
        cachedIdx = i;
        return cached;
    }

    /**
     * Тот же рынок с нуля: ряды общие, курсоры свои.
     *
     * ⚠️ Курсоры односторонние — второй прогон по одному объекту увидел бы
     * пустую ленту и молча дал бы ноль исполнений. Обход вселенной гоняет одну
     * пару по нескольким отступам и двум моделям, поэтому копия обязательна.
     */
    public MarketData fresh() {
        return new MarketData(trades, bookTs, bidOff, askOff, px, qty);
    }

    public int tradeCount() {
        return trades.size();
    }

    /**
     * Вся лента окна, без курсора.
     *
     * Нужна приборам, которые ходят по ленте не как повтор — не вперёд по
     * времени, а разом (кривая отбора {@code FlowMarkout}). {@link #tradesBetween}
     * для них не годится: он двигает односторонний курсор, и второй проход по
     * тем же данным вернул бы пусто.
     */
    public List<MarketTrade> trades() {
        return java.util.List.copyOf(trades);
    }

    public int bookCount() {
        return bookTs.length;
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
    /**
     * Дочитать уровни глубже пятого из компактной строки { цена:объём,…}.
     *
     * Порядок в строке тот же, в каком отдала площадка, то есть продолжение
     * пяти колонок; переупорядочивать не надо и нельзя — { asks} приходят
     * по убыванию, и сортировка здесь сломала бы { deepestVisible}.
     */
    /** Есть ли такая колонка в revx_book этой базы. */
    static boolean hasColumn(java.sql.Statement st, String column) {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(revx_book)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("не прочиталась схема revx_book: {}", e.toString());
        }
        return false;
    }

    public static void appendDeep(List<BookView.Level> side, String packed) {
        if (packed == null || packed.isEmpty()) {
            return;
        }
        for (String part : packed.split(",")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                double p = Double.parseDouble(part.substring(0, colon));
                double q = Double.parseDouble(part.substring(colon + 1));
                if (p > 0 && q > 0) {
                    side.add(new BookView.Level(p, q));
                }
            } catch (NumberFormatException ignore) {
                // битая строка не должна ронять прогон: пять колонок уже прочитаны
            }
        }
    }

    public BookView bookAt(long tsMs) {
        while (bookCursor + 1 < bookTs.length && bookTs[bookCursor + 1] <= tsMs) {
            bookCursor++;
        }
        if (bookTs.length == 0 || bookTs[bookCursor] > tsMs) {
            return null;
        }
        return view(bookCursor);
    }

    /**
     * ПЕРВЫЙ снимок книги ПОСЛЕ момента; {@code null}, если такого нет.
     *
     * Нужен, чтобы спросить у книги, случился ли свип на самом деле: настоящий
     * агрессор, продавший ниже нашего бида, обязан был съесть всё, что стояло
     * выше, — и следующий снимок это покажет. Принт, после которого книга цела,
     * до книги не доходил (внутренний зачёт, RFQ), и нас исполнить не мог.
     *
     * ⚠️ Курсор здесь СВОЙ и назад не ходит, как и у {@link #bookAt}: обход
     * идёт вперёд по времени.
     */
    public BookView bookAfter(long tsMs) {
        while (afterCursor < bookTs.length && bookTs[afterCursor] <= tsMs) {
            afterCursor++;
        }
        return afterCursor < bookTs.length ? view(afterCursor) : null;
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
        Packer books = new Packer();
        try (Connection c = StandDb.open(standDbPath);
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
            // ⚠️ Колонки глубины есть НЕ ВЕЗДЕ: они добавлены 10.09.2026, а баз,
            // снятых до этого, у нас гигабайты. Спрашивать их безусловно нельзя —
            // SQLite отвечает «no such column», чтение рынка падает целиком, и
            // прогон печатает «0 сделок, доход +0.0000», то есть ошибку,
            // неотличимую от честного результата «настройка не торгует». Ровно
            // на этом уже теряли время 09.09.2026 с упавшим котировщиком.
            boolean hasDeep = hasColumn(st, "deep_bids");
            try (ResultSet rs = st.executeQuery(
                    "SELECT t_recv_ms, bp1,bq1,bp2,bq2,bp3,bq3,bp4,bq4,bp5,bq5,"
                            + "ap1,aq1,ap2,aq2,ap3,aq3,ap4,aq4,ap5,aq5,"
                            + (hasDeep ? "deep_bids,deep_asks" : "NULL,NULL") + " FROM revx_book"
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
                    // ⚠️ Уровни глубже пятого лежат текстом и есть НЕ ВЕЗДЕ: до
                    // 10.09.2026 их не собирали вовсе, а собираются они только
                    // по парам, в которых котируем. Пустая колонка — норма, а не
                    // повреждение, и старые базы обязаны читаться по-прежнему.
                    appendDeep(bids, rs.getString(22));
                    appendDeep(asks, rs.getString(23));
                    ts.add(rs.getLong(1));
                    books.add(bids, asks);      // временные списки уходят в GC сразу
                }
            }
        } catch (Exception e) {
            log.error("не прочитался рынок из {}: {}", standDbPath, e.toString());
        }
        long[] arr = new long[ts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ts.get(i);
        }
        log.warn("рынок {}: сделок {}, снимков книги {}", symbol, trades.size(), arr.length);
        return new MarketData(trades, arr, books.bidOff(), books.askOff(), books.px(), books.qty());
    }
}
