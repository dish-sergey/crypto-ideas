package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Журнал исполнителя (ТЗ §6: «полный журнал всех отправленных запросов и ответов»).
 *
 * Отдельная база, отдельное соединение, никакой связи с базой стенда. Причина не
 * в аккуратности, а в разделении ролей: стенд измеряет, исполнитель торгует, и
 * авария одного не должна касаться данных другого. База стенда исполнителем
 * открывается только на чтение, эта — только им и только на запись.
 *
 * Пишется КАЖДЫЙ запрос, включая неудавшиеся и включая те, что не дошли. Когда
 * через неделю окажется, что заявка повела себя не так, единственным источником
 * правды будет эта таблица, а не память о том, что «вроде отправляли».
 */
public final class ExecJournal implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecJournal.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS exec_request (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms      INTEGER NOT NULL,
                method     TEXT    NOT NULL,
                path       TEXT    NOT NULL,
                body       TEXT,
                status     INTEGER,
                response   TEXT,
                latency_ms INTEGER,
                error      TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_request_ts ON exec_request(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_event (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms  INTEGER NOT NULL,
                kind   TEXT    NOT NULL,
                detail TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_event_ts ON exec_event(ts_ms);
            """;

    private final Connection connection;

    public ExecJournal(String path) {
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                for (String part : SCHEMA.split(";")) {
                    if (!part.isBlank()) {
                        st.execute(part);
                    }
                }
            }
            log.info("журнал исполнителя: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть журнал исполнителя " + path, e);
        }
    }

    /** Запрос записывается ВСЕГДА — и удавшийся, и упавший. */
    public synchronized void request(String method, String path, String body,
                                     Integer status, String response, long latencyMs, String error) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_request(ts_ms, method, path, body, status, response, latency_ms, error)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, method);
            ps.setString(3, path);
            ps.setString(4, body);
            if (status == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, status);
            }
            ps.setString(6, response);
            ps.setLong(7, latencyMs);
            ps.setString(8, error);
            ps.executeUpdate();
        } catch (Exception e) {
            // Журнал не должен ронять торговлю, но и молчать о своей поломке нельзя.
            log.error("не записался запрос в журнал: {}", e.getMessage());
        }
    }

    /** События уровня решений: запуск, остановка, паника, срабатывание лимита. */
    public synchronized void event(String kind, String detail) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_event(ts_ms, kind, detail) VALUES (?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, kind);
            ps.setString(3, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записалось событие в журнал: {}", e.getMessage());
        }
    }

    public synchronized long countRequests() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM exec_request")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("журнал закрылся с ошибкой: {}", e.getMessage());
        }
    }
}
