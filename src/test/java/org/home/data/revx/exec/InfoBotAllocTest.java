package org.home.data.revx.exec;

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

    /**
     * ⚠️ ЖИВОЙ СЛУЧАЙ 13.09.2026. Владелец сделал {@code /release} и
     * {@code /claim} на всех шести ботах и всё равно видел на счёте монеты,
     * которых нет ни у кого: у BTC при остатке 0.00002529 за ботами числилось
     * 0.00001256. Сводка обязана сказать об этом прямо и подсказать действие.
     */
    @Test
    void ничейноеВидноИСказаноЧтоДелать(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "e;BTC;0.00001256;0");
        Path journal = dir.resolve("j.db");
        balances(journal, "[{\"currency\":\"BTC\",\"available\":\"0.00001274\","
                + "\"reserved\":\"0.00001255\",\"total\":\"0.00002529\"}]");
        String out = alloc(new InfoBot("t", 1,
                List.of(new InfoBot.Watched("e", "BTC/USDC", journal.toString())), db.toString()));
        assertTrue(out.contains("СВОБОДНО"), out);
        assertTrue(out.contains("/claim всё"), "подсказка действия обязательна: " + out);
    }

    /**
     * 🔑 А вот это ничейное забрать НЕЛЬЗЯ: монета заперта в продаже, у которой
     * нет живого хозяина (бот убит, заявка осталась). Забравший получил бы
     * фантомный инвентарь — заявка исполнится сама.
     */
    @Test
    void запертоеБезХозяинаНеПредлагаетсяЗабрать(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("alloc.db");
        registry(db, "e;BTC;0.00000001;600000");          // сердцебиение десять минут назад
        Path journal = dir.resolve("j.db");
        balances(journal, "[{\"currency\":\"BTC\",\"available\":\"0.00000019\","
                + "\"reserved\":\"0.00003764\",\"total\":\"0.00003783\"}]");
        orders(journal, "{\"data\":[{\"id\":\"1\","
                + "\"client_order_id\":\"eeeeeeee-fb87-4ec7-9acb-aba878f42c62\","
                + "\"symbol\":\"BTC/USDC\",\"side\":\"sell\",\"quantity\":\"0.00003764\","
                + "\"leaves_quantity\":\"0.00003764\",\"price\":\"77000.0\","
                + "\"created_date\":1789290000000}]}");
        String out = alloc(new InfoBot("t", 1,
                List.of(new InfoBot.Watched("e", "BTC/USDC", journal.toString())), db.toString()));
        assertTrue(out.contains("без живого хозяина"), out);
        assertFalse(out.contains("/claim всё"), "забирать ловушку предлагать нельзя: " + out);
    }

    /** Разбор запертого сам по себе: чья заявка держит монету. */
    @Test
    void запертоеСчитаетсяТолькоПоПродажамИБезЖивогоХозяина() {
        String book = "{\"data\":[{\"id\":\"1\",\"client_order_id\":\"eeeeeeee-1\","
                + "\"symbol\":\"BTC/USDC\",\"side\":\"sell\",\"leaves_quantity\":\"0.00001255\","
                + "\"price\":\"77000\",\"created_date\":1789290000000},"
                + "{\"id\":\"2\",\"client_order_id\":\"eeeeeeee-2\",\"symbol\":\"BTC/USDC\","
                + "\"side\":\"buy\",\"leaves_quantity\":\"0.00001255\",\"price\":\"76000\","
                + "\"created_date\":1789290000000}]}";
        long now = System.currentTimeMillis();
        var orders = ActiveOrder.parse(book);

        // хозяин жив — монета его, ловушки нет
        assertEquals(0.0, InfoBot.orphanLocked("BTC", orders,
                java.util.Map.of("e", new double[]{0.00001255, now}), now), 1e-12);
        // хозяин молчит дольше аренды — монета заперта и ничья
        assertEquals(0.00001255, InfoBot.orphanLocked("BTC", orders,
                java.util.Map.of("e", new double[]{0.00001255, now - 10 * 60_000L}), now), 1e-12);
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

    private static void orders(Path file, String json) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO exec_request (ts_ms, method, path, status, response) VALUES ("
                    + System.currentTimeMillis() + ",'GET','/api/1.0/orders/active',200,'"
                    + json.replace("'", "''") + "')");
        }
    }

    /**
     * ⚠️ МЕНЮ TELEGRAM И {@code /help} — ДВА РАЗНЫХ СПИСКА, и они разъезжаются.
     *
     * 13.09.2026 команда {@code /alloc} была добавлена в текст {@code /help}, но
     * не в {@code setMyCommands}, и в кнопке «/» её не было — со стороны это
     * выглядело как «команду не добавили». Тест сторожит, чтобы всякая команда,
     * названная в справке, попадала и в меню.
     */
    @Test
    void менюСовпадаетСоСправкой() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/org/home/data/revx/exec/InfoBot.java"));
        java.util.Set<String> inHelp = new java.util.TreeSet<>();
        java.util.regex.Matcher h = java.util.regex.Pattern
                .compile("(?m)^\s+/([a-z]+) —").matcher(src);
        while (h.find()) {
            inHelp.add(h.group(1));
        }
        java.util.Set<String> inMenu = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[{]\"command\":\"([a-z]+)\"").matcher(src);
        while (m.find()) {
            inMenu.add(m.group(1));
        }
        inHelp.removeAll(inMenu);
        assertTrue(inHelp.isEmpty(),
                "в справке есть, а в меню Telegram нет: " + inHelp);
    }
}
