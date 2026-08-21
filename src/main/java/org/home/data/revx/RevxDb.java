package org.home.data.revx;

import org.home.data.core.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * База стенда Revolut X — отдельный файл (data/revx.db) со своей схемой.
 *
 * Почему не crypto.db: объём (~0.8 млн снимков книги в сутки) и Litestream —
 * crypto.db непрерывно реплицируется в B2 с free-лимитом 10 ГБ, туда этот поток
 * пускать нельзя (см. schema-revx.sql и deploy/SERVERS.md).
 *
 * {@code @Lazy}: в режиме планировщика стенд не участвует, файл БД не создаётся.
 */
@Component
@Lazy
public class RevxDb extends Db {

    public RevxDb(@Value("${revx.db-path}") String dbPath) {
        super(dbPath, "schema-revx.sql");
    }
}
