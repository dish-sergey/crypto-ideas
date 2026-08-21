package org.home.data.theory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Запись рядов в parquet (§2 ТЗ 65–68: «встраиваемая БД для метаданных прогонов +
 * колоночный формат (parquet) для рядов, никакого внешнего сервера»).
 *
 * Реализация — через встраиваемый DuckDB в памяти: {@code CREATE TABLE} +
 * {@code COPY … TO … (FORMAT PARQUET)}. Выбор обоснован в README: одна
 * зависимость-джарник вместо parquet-avro, тянущего за собой Hadoop, и она же
 * даёт возможность потом читать эти же файлы SQL-ом без внешнего сервера.
 */
public final class ParquetOut {

    private static final Logger log = LoggerFactory.getLogger(ParquetOut.class);

    private ParquetOut() {
    }

    /**
     * Пишет таблицу в parquet.
     *
     * @param out     путь к файлу (каталог создаётся)
     * @param columns имя колонки → тип DuckDB (VARCHAR / DOUBLE / BIGINT / BOOLEAN)
     * @param rows    строки, значения в порядке columns
     */
    public static void write(Path out, Map<String, String> columns, List<Object[]> rows) {
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Class.forName("org.duckdb.DuckDBDriver");
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
                StringJoiner cols = new StringJoiner(", ");
                StringJoiner qs = new StringJoiner(", ");
                for (Map.Entry<String, String> e : columns.entrySet()) {
                    cols.add("\"" + e.getKey() + "\" " + e.getValue());
                    qs.add("?");
                }
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE t(" + cols + ")");
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES(" + qs + ")")) {
                    for (Object[] row : rows) {
                        for (int i = 0; i < row.length; i++) {
                            ps.setObject(i + 1, row[i]);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (Statement st = c.createStatement()) {
                    st.execute("COPY t TO '" + out.toAbsolutePath().toString().replace("\\", "/")
                            + "' (FORMAT PARQUET)");
                }
            }
            Jlog.info(log, "parquet.write", Map.of("path", out.toString(), "rows", rows.size()));
        } catch (Exception e) {
            throw new IllegalStateException("не удалось записать parquet: " + out, e);
        }
    }
}
