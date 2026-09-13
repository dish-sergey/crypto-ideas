package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СВОДКА ПО РЕЗЕРВАЦИЯМ: /alloc.
 *
 * ⚠️ Главное свойство, которое тест сторожит, — сводка НЕ ХОДИТ НА ПЛОЩАДКУ.
 * Остатки счёта она берёт из ответов {@code /balances}, которые боты уже
 * записали в свои журналы, а реестр открывает только на чтение. Ключа у сводки
 * нет и быть не должно (правило в CLAUDE.md), и тест работает с пустыми
 * журналами именно поэтому: без сети команда обязана отвечать.
 */
class InfoBotAllocTest {

    private static String alloc(InfoBot bot) throws Exception {
        var m = InfoBot.class.getDeclaredMethod("alloc");
        m.setAccessible(true);
        return (String) m.invoke(bot);
    }

    private static void registry(Path file, String... rows) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE claim (bot_id TEXT, currency TEXT, qty REAL,"
                    + " since_ms INTEGER, heartbeat_ms INTEGER)");
            long now = System.currentTimeMillis();
            for (String r : rows) {
                String[] p = r.split(";");
                st.execute("INSERT INTO claim VALUES ('" + p[0] + "','" + p[1] + "'," + p[2]
                        + "," + now + "," + (now - Long.parseLong(p[3])) + ")");
            }
        }
    }

    @Test
    void показываетКтоСколькоДержит(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "a;USDC;6.75;0", "e;USDC;3.86;0", "e;BTC;0.00003765;0");
        String out = alloc(new InfoBot("t", 1, List.of(), db.toString()));
        assertTrue(out.contains("USDC"), out);
        assertTrue(out.contains("A"), out);
        assertTrue(out.contains("6.75"), out);
        assertTrue(out.contains("BTC"), out);
    }

    /** ⚠️ Нулевые резервации не показываются: их много и они ничего не значат. */
    @Test
    void нулевыеНеПоказываются(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "a;USDC;5.0;0", "b;USDC;0.0;0");
        String out = alloc(new InfoBot("t", 1, List.of(), db.toString()));
        assertTrue(out.contains("A"), out);
        assertFalse(out.contains("B  0."), out);
    }

    /**
     * ⚠️ Резервация мёртвого бота держится до роспуска — и это надо ВИДЕТЬ.
     * Молчание дольше десяти минут помечается рядом с числом.
     */
    @Test
    void молчащийБотПомечен(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "f;USDC;5.49;" + (30 * 60_000L));
        String out = alloc(new InfoBot("t", 1, List.of(), db.toString()));
        assertTrue(out.contains("молчит"), out);
    }

    /** Нет реестра — это ответ, а не падение. */
    @Test
    void отсутствующийРеестрНеРонитСводку(@TempDir Path dir) throws Exception {
        String out = alloc(new InfoBot("t", 1, List.of(), dir.resolve("нет.db").toString()));
        assertTrue(out.contains("не прочитался") || out.contains("пуст"), out);
    }

    /**
     * 🔑 СЧИТАТЬ ПО {@code total}, А НЕ ПО {@code available}.
     *
     * Первая версия брала available и показывала «разобрано больше, чем есть» на
     * КАЖДОЙ паре, где бот стоит в книге: монета в выставленной заявке лежит в
     * reserved. У BTC 13.09.2026 было available 0.00000019 при reserved
     * 0.00003764 — то есть вся позиция выглядела пропавшей.
     */
    @Test
    void монетаВЗаявкеНеСчитаетсяПропавшей(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "a;BTC;0.00003765;0");
        Path journal = dir.resolve("j.db");
        balances(journal, "[{\"currency\":\"BTC\",\"available\":\"0.00000019\","
                + "\"reserved\":\"0.00003764\",\"total\":\"0.00003783\"}]");
        String out = alloc(new InfoBot("t", 1,
                List.of(new InfoBot.Watched("a", "BTC/USDC", journal.toString())), db.toString()));
        assertFalse(out.contains("разобрано БОЛЬШЕ"), out);
        assertTrue(out.contains("в заявках"), out);
    }

    /** Крупная недостача — настоящая тревога: у d осталась резервация ADA с прошлой пары. */
    @Test
    void крупнаяНедостачаКричит(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "d;ADA;59.7754;0");
        Path journal = dir.resolve("j.db");
        balances(journal, "[{\"currency\":\"ADA\",\"available\":\"4.5981\","
                + "\"reserved\":\"0.0000\",\"total\":\"4.5981\"}]");
        String out = alloc(new InfoBot("t", 1,
                List.of(new InfoBot.Watched("d", "ADA/USDC", journal.toString())), db.toString()));
        assertTrue(out.contains("разобрано БОЛЬШЕ"), out);
    }

    private static void balances(Path file, String json) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE exec_request (ts_ms INTEGER, method TEXT, path TEXT,"
                    + " body TEXT, status INTEGER, response TEXT, latency_ms INTEGER, error TEXT)");
            st.execute("INSERT INTO exec_request (ts_ms, method, path, status, response) VALUES ("
                    + System.currentTimeMillis() + ",'GET','/api/1.0/balances',200,'"
                    + json.replace("'", "''") + "')");
        }
    }
}
