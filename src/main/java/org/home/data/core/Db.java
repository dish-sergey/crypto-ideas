package org.home.data.core;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQLite-хранилище. Один writer-connection (SQLite всё равно однописательный),
 * WAL-режим, все записи через синхронизированные upsert/batch.
 */
@Component
public class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final Connection conn;

    public Db(@Value("${data.db-path}") String dbPath) {
        try {
            Path path = Path.of(dbPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            initSchema();
            log.info("SQLite открыт: {}", path.toAbsolutePath());
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Не удалось открыть SQLite: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException, IOException {
        String schema = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try (Statement st = conn.createStatement()) {
            for (String sql : schema.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    /** Одиночный upsert. SQL обязан быть INSERT OR REPLACE / ON CONFLICT. */
    public synchronized int upsert(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("upsert failed: " + sql, e);
        }
    }

    /** Батч в одной транзакции. Возвращает число строк. */
    public synchronized int batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            return rows.size();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignore) {
                // отчёт ниже по исходной ошибке
            }
            throw new IllegalStateException("batch failed: " + sql, e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignore) {
                // соединение в любом случае живо
            }
        }
    }

    /** Список строк из первого столбца (напр. символы вселенной по рангу). */
    public synchronized List<String> queryStrings(String sql, Object... params) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
        return out;
    }

    /** Скалярный запрос (MAX(ts) и т.п.); null, если нет строк или NULL. */
    public synchronized Long queryLong(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    @PreDestroy
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Ошибка закрытия SQLite", e);
        }
    }
}
