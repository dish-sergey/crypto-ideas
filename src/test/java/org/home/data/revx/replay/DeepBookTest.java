package org.home.data.revx.replay;

import org.home.data.revx.BookCollector;
import org.home.data.revx.BookParser;
import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Глубина книги за пятым уровнем: запись, чтение и совместимость.
 *
 * <h2>Зачем</h2>
 *
 * Счётчики отсева 10.09.2026 показали, во что обходились пять уровней: заявка
 * BTC в 12 б.п. была для модели невидима в 19% случаев, в 14 б.п. — в 38%.
 * Заявка просто уходила за последний собранный уровень, и вся правая часть
 * лестницы отступов оказывалась занижена систематически.
 *
 * ⚠️ Главное, что здесь закреплено, — <b>совместимость</b>. Баз, собранных до
 * правки, у нас гигабайты, и читаться они обязаны по-прежнему: пустая колонка
 * глубины это норма, а не повреждение.
 */
class DeepBookTest {

    private static List<BookParser.Level> levels(int n) {
        List<BookParser.Level> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BookParser.Level(100 - i, 10 + i, 1));
        }
        return out;
    }

    @Test
    void fiveLevelsOrFewerPackNothing() {
        // Пять уровней целиком лежат в колонках — строке глубины взяться неоткуда.
        assertNull(BookCollector.deepOf(levels(5)));
        assertNull(BookCollector.deepOf(levels(3)));
        assertNull(BookCollector.deepOf(List.of()));
    }

    @Test
    void deeperLevelsPackFromTheSixth() {
        String packed = BookCollector.deepOf(levels(8));
        assertEquals("95.0:15.0,94.0:16.0,93.0:17.0", packed,
                "в строку идут уровни С ШЕСТОГО, первые пять уже в колонках");
    }

    @Test
    void packedLevelsComeBackInTheSameOrder() {
        // ⚠️ Порядок трогать нельзя: asks площадка отдаёт по УБЫВАНИЮ, и
        // сортировка здесь сломала бы deepestVisible.
        List<BookView.Level> side = new ArrayList<>();
        side.add(new BookView.Level(100, 10));
        MarketData.appendDeep(side, "95.0:15.0,94.0:16.0");

        assertEquals(3, side.size());
        assertEquals(95.0, side.get(1).price(), 1e-9);
        assertEquals(16.0, side.get(2).qty(), 1e-9);
    }

    @Test
    void baseWithoutDepthColumnsStillLoads() throws Exception {
        // ⚠️ РЕГРЕССИЯ 10.09.2026, пойманная в тот же день. Запрос спрашивал
        // deep_bids безусловно, на базе до миграции SQLite отвечал «no such
        // column», чтение рынка падало целиком — и прогон печатал «0 сделок,
        // доход +0.0000». Ошибка, неотличимая от «настройка не торгует»: она
        // отравила бы каждый локальный обход, и молча.
        java.nio.file.Path db = java.nio.file.Files.createTempFile("old-base", ".db");
        java.nio.file.Files.delete(db);
        try (java.sql.Connection c =
                     java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE revx_book (symbol TEXT, t_recv_ms INTEGER,"
                    + " bp1 REAL, bq1 REAL, bp2 REAL, bq2 REAL, bp3 REAL, bq3 REAL,"
                    + " bp4 REAL, bq4 REAL, bp5 REAL, bq5 REAL,"
                    + " ap1 REAL, aq1 REAL, ap2 REAL, aq2 REAL, ap3 REAL, aq3 REAL,"
                    + " ap4 REAL, aq4 REAL, ap5 REAL, aq5 REAL)");
            st.execute("CREATE TABLE revx_trade (trade_id TEXT, symbol TEXT, ts_ms INTEGER,"
                    + " price REAL, qty REAL, side TEXT)");
            st.execute("INSERT INTO revx_book(symbol,t_recv_ms,bp1,bq1,ap1,aq1)"
                    + " VALUES('BTC/USDC', 1000, 100, 5, 101, 5)");
            assertTrue(!MarketData.hasColumn(st, "deep_bids"),
                    "в базе до миграции колонок глубины нет");
        }
        MarketData md = MarketData.load(db.toString(), "BTC/USDC", 0, 2000);
        BookView book = md.bookAt(1500);
        assertTrue(book != null && !book.empty(),
                "старая база обязана читаться, а не отдавать пустой рынок");
        assertEquals(100.0, book.bestBid(), 1e-9);
        java.nio.file.Files.deleteIfExists(db);
    }

    @Test
    void oldBasesWithoutDepthStillRead() {
        // База, собранная до 10.09.2026: колонки глубины пустые или отсутствуют.
        List<BookView.Level> side = new ArrayList<>();
        side.add(new BookView.Level(100, 10));
        MarketData.appendDeep(side, null);
        MarketData.appendDeep(side, "");

        assertEquals(1, side.size(), "пустая глубина — норма, а не повреждение");
    }

    @Test
    void brokenPackingDoesNotKillTheRun() {
        // Пять колонок уже прочитаны; битый хвост не повод ронять прогон.
        List<BookView.Level> side = new ArrayList<>();
        side.add(new BookView.Level(100, 10));
        MarketData.appendDeep(side, "95.0:15.0,мусор,:,7,93.0:17.0");

        assertEquals(3, side.size());
        assertEquals(93.0, side.get(2).price(), 1e-9);
    }

    @Test
    void depthMovesTheVisibilityEdge() {
        // Тот самый эффект, ради которого всё делалось: заявка глубже пятого
        // уровня перестаёт быть невидимой.
        List<BookView.Level> bids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bids.add(new BookView.Level(100 - i, 10));
        }
        BookView shallow = new BookView(bids, List.of(new BookView.Level(101, 10)));
        assertEquals(96.0, shallow.deepestVisible(Side.BUY), 1e-9);

        List<BookView.Level> deep = new ArrayList<>(bids);
        MarketData.appendDeep(deep, "95.0:10.0,94.0:10.0,93.0:10.0");
        BookView wide = new BookView(deep, List.of(new BookView.Level(101, 10)));
        assertTrue(wide.deepestVisible(Side.BUY) < shallow.deepestVisible(Side.BUY),
                "с глубиной видимая часть книги уходит дальше от середины");
        assertEquals(93.0, wide.deepestVisible(Side.BUY), 1e-9);
    }
}
