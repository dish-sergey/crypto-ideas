package org.home.data.revx.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Сборка базы стенда из инкрементных копий.
 *
 * <h2>Зачем</h2>
 *
 * Прогоны переехали с сервера на локальную машину: там одно ядро, которое делят
 * шесть живых ботов и сбор, здесь двенадцать. Но снять четырёхгигабайтный снимок
 * с работающего сервера дорого — первая попытка дала 380 МБ за 25 минут, потому
 * что копирование конкурировало за диск с самим сбором.
 *
 * Копии при этом уже есть: cron на micro каждую ночь забирает с ARM только новые
 * строки, жмёт и складывает. Восемнадцать файлов на 1.2 ГБ вместо 4.7 ГБ, и
 * забирать их можно с micro, вообще не трогая торговый сервер.
 *
 * <h2>Почему склейка безопасна</h2>
 *
 * Каждый файл — полноценная база SQLite с той же схемой и своим куском строк.
 * {@code INSERT OR IGNORE} склеивает их по первичному ключу, поэтому перекрытия
 * между копиями безвредны, а порядок обработки не важен. Это не оптимизация, а
 * условие корректности: инкременты режутся по курсору {@code rowid}, и границы
 * суток в них не выровнены.
 *
 * ⚠️ Проверять результат надо по ДИАПАЗОНУ ВРЕМЕНИ и числу строк, а не по факту
 * «команда прошла». Пропущенный файл не вызовет ошибки — он просто оставит дыру
 * в истории, а обход по такой базе покажет сутки с подозрительно малым числом
 * снимков и спишет их на простой сбора.
 */
public final class StandAssembler {

    private static final Logger log = LoggerFactory.getLogger(StandAssembler.class);

    /** Таблицы, которые нужны прогонам. Остальное в инкрементах не хранится. */
    private static final List<String> TABLES = List.of("revx_pair", "revx_book", "revx_trade");

    private StandAssembler() {
    }

    public static void assemble(String dirPath, String outPath) {
        try {
            Path dir = Path.of(dirPath);
            Path out = Path.of(outPath);
            List<Path> parts;
            try (var files = Files.list(dir)) {
                parts = files.filter(p -> p.getFileName().toString().endsWith(".gz"))
                        .sorted().toList();
            }
            if (parts.isEmpty()) {
                log.error("в {} нет ни одного .gz — собирать нечего", dirPath);
                return;
            }
            Files.deleteIfExists(out);
            Path tmp = dir.resolve("part.tmp.db");

            // Первый файл становится основой: схема, индексы и первичные ключи
            // приезжают из него готовыми, и создавать их руками не нужно.
            unpack(parts.get(0), out);
            log.warn("основа: {} ({} книг)", parts.get(0).getFileName(), count(out, "revx_book"));

            for (int i = 1; i < parts.size(); i++) {
                unpack(parts.get(i), tmp);
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out);
                     Statement st = c.createStatement()) {
                    st.execute("ATTACH '" + tmp.toString().replace("\\", "/") + "' AS s");
                    for (String t : TABLES) {
                        try {
                            // ⚠️ НЕ «SELECT *»: у частей может быть РАЗНОЕ число
                            // колонок. 10.09.2026 в revx_book добавились deep_bids
                            // и deep_asks, и звёздочка сложила бы базу, собранную
                            // до правки, с базой после неё — с невнятной ошибкой
                            // про число значений. Переносим ПЕРЕСЕЧЕНИЕ колонок:
                            // то, чего в источнике нет, останется NULL.
                            String cols = String.join(",", shared(st, t));
                            st.executeUpdate("INSERT OR IGNORE INTO " + t + "(" + cols + ")"
                                    + " SELECT " + cols + " FROM s." + t);
                        } catch (Exception e) {
                            // Таблицы может не быть в раннем инкременте — это не
                            // повод бросать всю сборку.
                            log.warn("{}: таблица {} не перенеслась — {}",
                                    parts.get(i).getFileName(), t, e.getMessage());
                        }
                    }
                    st.execute("DETACH s");
                }
                log.warn("{} → книг всего {}", parts.get(i).getFileName(), count(out, "revx_book"));
            }
            Files.deleteIfExists(tmp);
            report(out);
        } catch (Exception e) {
            log.error("сборка базы не прошла: {}", e.toString(), e);
        }
    }

    private static void unpack(Path gz, Path to) throws Exception {
        Files.deleteIfExists(to);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz));
             OutputStream os = Files.newOutputStream(to)) {
            in.transferTo(os);
        }
    }

    /**
     * Колонки, которые есть И в приёмнике, И в источнике.
     *
     * Порядок берётся из приёмника: перечисление в INSERT и в SELECT должно
     * совпадать, а полагаться на совпадение порядка в двух файлах нельзя.
     */
    private static List<String> shared(Statement st, String table) throws SQLException {
        List<String> destination = columns(st, "main", table);
        List<String> source = columns(st, "s", table);
        List<String> both = new ArrayList<>();
        for (String c : destination) {
            if (source.contains(c)) {
                both.add(c);
            }
        }
        return both;
    }

    private static List<String> columns(Statement st, String schema, String table)
            throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    private static long count(Path db, String table) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Итог по диапазону, а не по факту «прошло»: дыру видно только так. */
    private static void report(Path db) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*), MIN(t_recv_ms), MAX(t_recv_ms), "
                            + "COUNT(DISTINCT symbol) FROM revx_book")) {
                if (rs.next()) {
                    log.warn("СОБРАНО: книг {}, символов {}, с {} по {}",
                            rs.getLong(1), rs.getInt(4),
                            java.time.Instant.ofEpochMilli(rs.getLong(2)),
                            java.time.Instant.ofEpochMilli(rs.getLong(3)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT date(t_recv_ms/1000,'unixepoch') d, COUNT(*) n FROM revx_book "
                            + "GROUP BY d ORDER BY d")) {
                StringBuilder sb = new StringBuilder("по суткам:\n");
                while (rs.next()) {
                    sb.append(String.format("  %s  %8d%n", rs.getString(1), rs.getLong(2)));
                }
                log.warn(sb.toString());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM revx_trade")) {
                if (rs.next()) {
                    log.warn("сделок {}", rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.error("итог не собрался: {}", e.toString());
        }
    }
}
