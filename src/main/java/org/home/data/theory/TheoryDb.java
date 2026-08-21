package org.home.data.theory;

import org.home.data.core.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * База счётных стендов теории оптимальности (ТЗ docs/65–68) — отдельный файл
 * data/theory.db со своей схемой (schema-theory.sql).
 *
 * Почему не crypto.db: стенды пишут прогоны и импортированный датасет событий S5,
 * а crypto.db непрерывно реплицируется Litestream в B2 (deploy/SERVERS.md) —
 * счётному мусору там не место. Плюс изоляция: стенд нельзя уронить коллекторам.
 *
 * {@code @Lazy}: в режиме планировщика стенды не участвуют, файл не создаётся.
 */
@Component
@Lazy
public class TheoryDb extends Db {

    public TheoryDb(@Value("${theory.db-path}") String dbPath) {
        super(dbPath, "schema-theory.sql");
    }
}
