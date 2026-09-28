package org.home.data.revx.exec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СВОДКА ЧИТАЕТ СОСТОЯНИЕ ЧИТАТЕЛЯ ПЛОЩАДКИ (задача A92).
 *
 * ⚠️ Главное, что сторожит тест: «упал» считается по ПОСЛЕДНЕМУ УСПЕШНОМУ
 * циклу, а не по последнему циклу вообще. Живой процесс, которому площадка
 * отвечает отказами, двигает {@code last_cycle_ms}, но не {@code last_ok_ms} —
 * и для ботов он так же бесполезен, как мёртвый.
 */
class InfoBotVenueTest {

    @AfterEach
    void clear() {
        System.clearProperty("revx.info.venue");
    }

    private static InfoBot bot(Path venue) {
        System.setProperty("revx.info.venue", venue.toString());
        return new InfoBot("t", 1, List.of(), "нет-реестра.db");
    }

    private static void venue(Path file, long lastOk, long lastCycle, String... sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            for (String ddl : VenueReader.SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
            st.execute("INSERT INTO heartbeat(name, started_ms, last_cycle_ms, last_ok_ms, cycles, "
                    + "errors, throttled, last_error) VALUES('reader', 0, " + lastCycle + ", "
                    + lastOk + ", 10, 3, 1, 'orders/active 429')");
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    /** Базы нет — читатель не развёрнут, и это не тревога. */
    @Test
    void безБазыЧитательНеРазвёрнут(@TempDir Path dir) {
        assertFalse(bot(dir.resolve("venue.db")).venueState().deployed());
    }

    @Test
    void свежийУспешныйЦикл(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path db = dir.resolve("venue.db");
        venue(db, now - 2_000, now - 1_000);
        InfoBot.VenueState v = bot(db).venueState();
        assertTrue(v.deployed());
        assertTrue(now - v.lastOkMs() < InfoBot.VENUE_SILENCE_MS);
        assertEquals(3, v.errors());
    }

    /** Циклы идут, а успешных нет уже пять минут — это «упал». */
    @Test
    void циклыБезУспехаСчитаютсяПадением(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path db = dir.resolve("venue.db");
        venue(db, now - 300_000, now - 1_000);
        InfoBot.VenueState v = bot(db).venueState();
        assertTrue(now - v.lastOkMs() > InfoBot.VENUE_SILENCE_MS);
        assertEquals("orders/active 429", v.lastError());
    }

    /** Незаписанные исполнения считаются только нерешённые. */
    @Test
    void считаетНезаписанные(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path db = dir.resolve("venue.db");
        venue(db, now, now,
                "INSERT INTO shadow_diff(oid, bot, traded, booked) VALUES('x', 'a', 1, 0)",
                "INSERT INTO shadow_diff(oid, bot, traded, booked, resolved_ms) "
                        + "VALUES('y', 'a', 1, 1, " + now + ")");
        assertEquals(1, bot(db).venueState().unbooked());
    }

    /**
     * Запертое без заявки: какие монеты, сколько и во что это в USDC. Цена — из
     * последнего тика бота на этой монете; без бота — «цена ?», не ноль.
     */
    @Test
    void запертоеПоМонетамИВДолларах(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path db = dir.resolve("venue.db");
        venue(db, now, now,
                "INSERT INTO reserve_gap VALUES('USDC', 48.4, 33.3, 15.1, " + (now - 600_000) + ", " + now + ")",
                "INSERT INTO reserve_gap VALUES('SOL', 0.05, 0, 0.05, " + (now - 900_000) + ", " + now + ")",
                "INSERT INTO reserve_gap VALUES('ADA', 3, 0, 3, " + (now - 900_000) + ", " + now + ")",
                // свежее пяти минут — ещё не показываем
                "INSERT INTO reserve_gap VALUES('ETH', 1, 0, 1, " + (now - 60_000) + ", " + now + ")");
        Path journal = dir.resolve("f.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + journal);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE exec_quote(ts_ms INTEGER, fair REAL)");
            st.execute("INSERT INTO exec_quote VALUES(1, 100), (2, 120)");
        }
        System.setProperty("revx.info.venue", db.toString());
        InfoBot bot = new InfoBot("t", 1,
                List.of(new InfoBot.Watched("f", "SOL/USDC", journal.toString())), "нет-реестра.db");
        InfoBot.VenueState v = bot.venueState();
        assertEquals(3, v.reserveGaps());
        String text = bot.gapLines(v.gaps(), now);
        assertTrue(text.contains("21.10 USDC + монеты без цены"), text);   // 15.1 + 0.05 × 120
        assertTrue(text.contains("SOL 0.05000000 (≈ 6.00 USDC)"), text);
        assertTrue(text.contains("USDC 15.10 (≈ 15.10 USDC)"), text);
        assertTrue(text.contains("ADA 3.00000000 (цена ?)"), text);
        assertFalse(text.contains("ETH"), text);
    }
}
