package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
     * Снимок {@code i}: биды в {@code [bidOff[i], askOff[i])}, аски в
     * {@code [askOff[i], bidOff[i+1])}.
     *
     * 🔑 УРОВНИ ВНЕ КУЧИ (18.10.2026). Даже плотно уровни окна 15.09–17.10 — 4.5–5 ГБ на
     * прогон (16 байт × ~300 млн), с пиком роста массивов в полтора раза, и прогоны SOL/BTC
     * не влезли в 8 ГБ. Теперь уровни — пары {@code (цена, объём)} подряд в {@link #lv}: с
     * кэшем это файл, отображённый в память (страницы держит ОС, читаются по курсору
     * вперёд, прогоны одной монеты делят одни и те же страницы); без кэша — массив в
     * куче, как раньше. Значения те же до бита — {@code double}, как и были.
     */
    private final int[] bidOff;
    private final int[] askOff;
    private final MemorySegment lv;
    private int tradeCursor;
    private int bookCursor;
    private int afterCursor;
    private int cachedIdx = -1;
    private BookView cached;

    private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE_UNALIGNED;

    /**
     * Рынок из готовых рядов — для тестов.
     *
     * Модели исполнения проверяются на СИНТЕТИЧЕСКОМ рынке намеренно: правила
     * дележа объёма и приоритета цены должны держаться на данных, которые
     * подобраны под проверку, а не на тех, где всё и так сходится.
     */
    public static MarketData of(List<MarketTrade> trades, long[] bookTs, List<BookView> books) {
        Packer p = new Packer(null);
        for (BookView b : books) {
            p.add(b.bids(), b.asks());
        }
        return new MarketData(trades, bookTs, p.bidOff(), p.askOff(), p.heapLevels());
    }

    private MarketData(List<MarketTrade> trades, long[] bookTs, int[] bidOff, int[] askOff,
                       MemorySegment lv) {
        this.trades = trades;
        this.bookTs = bookTs;
        this.bidOff = bidOff;
        this.askOff = askOff;
        this.lv = lv;
    }

    /**
     * Накопитель плотной книги. Смещения снимков — растущие массивы в куче (их мало:
     * 8 байт на снимок), уровни — либо растущий {@code double[]} пар, либо поток в файл
     * ({@code sink}), чтобы разбор длинного окна не держал их в куче вовсе.
     */
    private static final class Packer {
        private int n;
        private int m;
        private int[] bo = new int[1024];
        private int[] ao = new int[1024];
        private double[] p;
        private final java.nio.channels.FileChannel sink;
        private final java.nio.ByteBuffer buf;

        Packer(java.nio.channels.FileChannel sink) {
            this.sink = sink;
            this.p = sink == null ? new double[32768] : null;
            this.buf = sink == null ? null
                    : java.nio.ByteBuffer.allocateDirect(1 << 23).order(java.nio.ByteOrder.nativeOrder());
        }

        void level(double price, double size) {
            if (m == Integer.MAX_VALUE) {
                throw new IllegalStateException("уровней книги больше 2^31 — окно слишком длинное для int-смещений");
            }
            if (sink == null) {
                if (2L * m + 2 > p.length) {
                    p = java.util.Arrays.copyOf(p, Math.toIntExact(Math.min(Integer.MAX_VALUE - 8,
                            (long) p.length + (p.length >> 1))));
                }
                p[2 * m] = price;
                p[2 * m + 1] = size;
            } else {
                if (buf.remaining() < 16) {
                    flush();
                }
                buf.putDouble(price).putDouble(size);
            }
            m++;
        }

        void flush() {
            buf.flip();
            try {
                while (buf.hasRemaining()) {
                    sink.write(buf);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            buf.clear();
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

        /** Уровни в куче — без обрезки: копия на пике удвоила бы память. */
        MemorySegment heapLevels() {
            return MemorySegment.ofArray(p);
        }
    }

    private double px(int k) {
        return lv.getAtIndex(D, 2L * k);
    }

    private double qty(int k) {
        return lv.getAtIndex(D, 2L * k + 1);
    }

    /** Снимок {@code i} как {@link BookView}; последний собранный держится в кэше. */
    private BookView view(int i) {
        if (i == cachedIdx) {
            return cached;
        }
        List<BookView.Level> bids = new ArrayList<>(askOff[i] - bidOff[i]);
        for (int k = bidOff[i]; k < askOff[i]; k++) {
            bids.add(new BookView.Level(px(k), qty(k)));
        }
        List<BookView.Level> asks = new ArrayList<>(bidOff[i + 1] - askOff[i]);
        for (int k = askOff[i]; k < bidOff[i + 1]; k++) {
            asks.add(new BookView.Level(px(k), qty(k)));
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
        return new MarketData(trades, bookTs, bidOff, askOff, lv);
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
    /** Принты мельче этого номинала стенд не видит ({@code -Drevx.sim.min-print-usd}, 0 — все). */
    static final double MIN_PRINT_USD = Double.parseDouble(System.getProperty("revx.sim.min-print-usd", "0"));


    /**
     * КЭШ РЫНКА (17.10.2026, {@code -Drevx.forecast.cache-dir}): разбор книги окна w24 — 7 минут
     * на каждый прогон (текст глубины в числа), а результат у всех прогонов окна одинаков.
     * Ключ: база (путь, размер, время изменения), пара, окно, порог мелких принтов.
     *
     * Два файла (18.10.2026, формат 2): {@code .bin} — лента и смещения снимков, читается в
     * кучу; {@code .bin.lv} — уровни книги парами {@code (цена, объём)} в порядке байт машины,
     * отображается в память и в кучу не грузится. Первый прогон пишет уровни сразу в файл,
     * по ходу разбора базы, так что и он не держит книгу в куче. {@code .bin} пишется
     * последним — его наличие значит, что {@code .lv} целый.
     */
    public static MarketData load(String standDbPath, String symbol, long fromMs, long toMs) {
        String dir = System.getProperty("revx.forecast.cache-dir", "");
        if (dir.isBlank()) {
            return loadDb(standDbPath, symbol, fromMs, toMs, null);
        }
        java.nio.file.Path file;
        try {
            java.nio.file.Path db = java.nio.file.Path.of(standDbPath);
            String key = "v2|" + db.toAbsolutePath().normalize() + "|" + java.nio.file.Files.size(db) + "|"
                    + java.nio.file.Files.getLastModifiedTime(db).toMillis() + "|" + symbol + "|" + fromMs + "|"
                    + toMs + "|" + MIN_PRINT_USD;
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file = java.nio.file.Path.of(dir, "market-" + symbol.replace('/', '-') + "-"
                    + java.util.HexFormat.of().formatHex(h, 0, 8) + ".bin");
        } catch (Exception e) {
            log.warn("кэш рынка: ключ не посчитан ({}) — читаю базу", e.getMessage());
            return loadDb(standDbPath, symbol, fromMs, toMs, null);
        }
        if (java.nio.file.Files.exists(file) && java.nio.file.Files.exists(levelsPath(file))) {
            try {
                MarketData m = readCache(file);
                log.warn("кэш рынка {}: сделок {}, снимков книги {}", symbol, m.trades.size(), m.bookTs.length);
                return m;
            } catch (Exception e) {
                log.warn("кэш рынка {} не прочитан ({}) — читаю базу", file.getFileName(), e.getMessage());
            }
        }
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Path tmpLv = java.nio.file.Files.createTempFile(file.getParent(),
                    file.getFileName().toString() + ".lv", ".tmp");
            MarketData m;
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmpLv,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                m = loadDb(standDbPath, symbol, fromMs, toMs, ch);
            }
            java.nio.file.Files.move(tmpLv, levelsPath(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(file.getParent(),
                    file.getFileName().toString(), ".tmp");
            m.writeBin(tmp);
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.warn("кэш рынка записан: {}", file.getFileName());
            return readCache(file);
        } catch (Exception e) {
            log.warn("кэш рынка не записан ({}) — читаю базу в кучу", e.toString());
            return loadDb(standDbPath, symbol, fromMs, toMs, null);
        }
    }

    private static final int CACHE_MAGIC = 0x4D4B5432;   // "MKT2"

    static java.nio.file.Path levelsPath(java.nio.file.Path bin) {
        return bin.resolveSibling(bin.getFileName().toString() + ".lv");
    }

    /** Кэш целиком: {@code file} (лента, смещения) и {@code file.lv} (уровни). */
    void writeCache(java.nio.file.Path file) throws java.io.IOException {
        int m = bookTs.length == 0 ? 0 : bidOff[bookTs.length];
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(levelsPath(file),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1 << 20).order(java.nio.ByteOrder.nativeOrder());
            for (int k = 0; k < m; k++) {
                if (b.remaining() < 16) {
                    b.flip();
                    while (b.hasRemaining()) {
                        ch.write(b);
                    }
                    b.clear();
                }
                b.putDouble(px(k)).putDouble(qty(k));
            }
            b.flip();
            while (b.hasRemaining()) {
                ch.write(b);
            }
        }
        writeBin(file);
    }

    private void writeBin(java.nio.file.Path file) throws java.io.IOException {
        int n = bookTs.length;
        int m = n == 0 ? 0 : bidOff[n];
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(16 + trades.size() * 25);
            head.putInt(CACHE_MAGIC).putInt(trades.size()).putInt(n).putInt(m);
            for (MarketTrade t : trades) {
                head.putLong(t.tsMs()).putDouble(t.price()).putDouble(t.qty())
                        .put((byte) (t.aggressor() == null ? 0 : t.aggressor() == Side.BUY ? 1 : 2));
            }
            head.flip();
            while (head.hasRemaining()) {
                ch.write(head);
            }
            writeLongs(ch, bookTs, n);
            writeInts(ch, bidOff, n + 1);
            writeInts(ch, askOff, n);
        }
    }

    static MarketData readCache(java.nio.file.Path file) throws java.io.IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer h = read(ch, 16);
            if (h.getInt() != CACHE_MAGIC) {
                throw new java.io.IOException("не тот формат");
            }
            int nt = h.getInt();
            int n = h.getInt();
            int m = h.getInt();
            java.nio.ByteBuffer tb = read(ch, nt * 25L);
            List<MarketTrade> trades = new ArrayList<>(nt);
            for (int i = 0; i < nt; i++) {
                long ts = tb.getLong();
                double p = tb.getDouble();
                double q = tb.getDouble();
                byte s = tb.get();
                trades.add(new MarketTrade(ts, p, q, s == 0 ? null : s == 1 ? Side.BUY : Side.SELL));
            }
            long[] bookTs = new long[n];
            read(ch, n * 8L).asLongBuffer().get(bookTs);
            int[] bo = new int[n + 1];
            read(ch, (n + 1) * 4L).asIntBuffer().get(bo);
            int[] ao = new int[n];
            read(ch, n * 4L).asIntBuffer().get(ao);
            MemorySegment lv;
            try (java.nio.channels.FileChannel lc = java.nio.channels.FileChannel.open(levelsPath(file),
                    java.nio.file.StandardOpenOption.READ)) {
                if (lc.size() != m * 16L) {
                    throw new java.io.IOException("уровни: " + lc.size() + " байт вместо " + m * 16L);
                }
                // Отображение живёт, пока жив сегмент (Arena.ofAuto): закрытие канала его не снимает.
                lv = m == 0 ? MemorySegment.ofArray(new double[0])
                        : lc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, lc.size(), Arena.ofAuto());
            }
            return new MarketData(trades, bookTs, bo, ao, lv);
        }
    }

    private static java.nio.ByteBuffer read(java.nio.channels.FileChannel ch, long bytes) throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(Math.toIntExact(bytes));
        while (b.hasRemaining()) {
            if (ch.read(b) < 0) {
                throw new java.io.EOFException("кэш рынка обрезан");
            }
        }
        return b.flip();
    }

    private static void writeLongs(java.nio.channels.FileChannel ch, long[] a, int len) throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(len * 8);
        b.asLongBuffer().put(a, 0, len);
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    private static void writeInts(java.nio.channels.FileChannel ch, int[] a, int len) throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(len * 4);
        b.asIntBuffer().put(a, 0, len);
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    /** Разбор базы; {@code sink != null} — уровни идут потоком в файл, а не в кучу. */
    private static MarketData loadDb(String standDbPath, String symbol, long fromMs, long toMs,
                                     java.nio.channels.FileChannel sink) {
        List<MarketTrade> trades = new ArrayList<>();
        long[] ts = new long[1024];
        int nts = 0;
        Packer books = new Packer(sink);
        try (Connection c = StandDb.open(standDbPath);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = '" + symbol
                            + "' AND ts_ms >= " + fromMs + " AND ts_ms <= " + toMs
                            + " ORDER BY ts_ms")) {
                while (rs.next()) {
                    // Опыт владельца 13.10.2026: мелкий бот (с 08.10, ~$0.10) — 40–60% принтов ленты.
                    if (MIN_PRINT_USD > 0 && rs.getDouble(2) * rs.getDouble(3) < MIN_PRINT_USD) {
                        continue;
                    }
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
                    if (nts == ts.length) {
                        ts = java.util.Arrays.copyOf(ts, ts.length * 2);
                    }
                    ts[nts++] = rs.getLong(1);
                    books.add(bids, asks);      // временные списки уходят в GC сразу
                }
            }
            if (sink != null) {
                books.flush();
            }
        } catch (Exception e) {
            log.error("не прочитался рынок из {}: {}", standDbPath, e.toString());
            if (sink != null) {
                throw new IllegalStateException("рынок не прочитан в кэш", e);
            }
        }
        long[] arr = java.util.Arrays.copyOf(ts, nts);
        log.warn("рынок {}: сделок {}, снимков книги {}", symbol, trades.size(), arr.length);
        return new MarketData(trades, arr, books.bidOff(), books.askOff(),
                sink == null ? books.heapLevels() : null);
    }
}
