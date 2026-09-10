package org.home.data.revx.replay;

import org.home.data.revx.exec.ExecJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздвижение отступа от дефицита постановок доезжает из живого журнала в повтор.
 *
 * <h2>Почему это отдельный тест</h2>
 *
 * Величина приходит из ОБЩЕГО ведра постановок, у которого нет истории:
 * восстановить задним числом, каким было давление вчера, нельзя ниоткуда.
 * Значит единственный путь — записать её в тик, и если этот путь порвётся,
 * сверка снова начнёт показывать провал при исправном боте. 10.09.2026 она
 * показала 0.62% совпавших котировок у бота A ровно поэтому: давление 0.00336
 * сдвигало цену на треть процента отступа, чего хватало, чтобы она легла на
 * СОСЕДНИЙ тик, а сравнение идёт до знака.
 */
class QuotePressureTest {

    @Test
    void pressureSurvivesTheRoundTrip(@TempDir Path dir) {
        Path db = dir.resolve("j.db");
        try (ExecJournal j = new ExecJournal(db.toString())) {
            j.quote(100.0, 99.0, 101.0, 0.5, true, null, 0.0033629);
            j.quote(100.0, 99.0, 101.0, 0.5, true, null, 0);
        }
        List<ReplayFair.Tick> ticks = ReplayRunner.readTicks(db.toString(), 0);
        assertEquals(2, ticks.size());
        // ⚠️ До знака: округли мы её при записи, повтор дал бы соседний тик и
        // сверка снова провалилась бы — ровно та ошибка, ради которой всё это.
        assertEquals(0.0033629, ticks.get(0).pressure(), 0.0);
        assertEquals(0.0, ticks.get(1).pressure(), 0.0);
    }

    /**
     * ⚠️ Журнал БЕЗ колонки читается, а не падает.
     *
     * Записи живых ботов до 10.09.2026 её не содержат, и повтор на них обязан
     * работать по-прежнему: ноль там означает «бот этого не записал», а не
     * «раздвижения не было». Разницу между этими двумя случаями по журналу не
     * восстановить, и притворяться, что она известна, нельзя.
     */
    @Test
    void oldJournalWithoutTheColumnStillReads(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("old.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE exec_quote (ts_ms INTEGER NOT NULL, fair REAL, bid REAL,
                    ask REAL, inventory REAL, quotable INTEGER NOT NULL, reason TEXT)""");
            st.execute("INSERT INTO exec_quote VALUES (1000, 100.0, 99.0, 101.0, 0.5, 1, NULL)");
        }
        List<ReplayFair.Tick> ticks = ReplayRunner.readTicks(db.toString(), 0);
        assertEquals(1, ticks.size());
        assertEquals(99.0, ticks.getFirst().bid(), 1e-12);
        assertEquals(0.0, ticks.getFirst().pressure(), 0.0);
    }

    /** Колонка досоздаётся в СУЩЕСТВУЮЩЕЙ базе: иначе живые журналы её не получат. */
    @Test
    void columnIsAddedToAnExistingJournal(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("grow.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE exec_quote (ts_ms INTEGER NOT NULL, fair REAL, bid REAL,
                    ask REAL, inventory REAL, quotable INTEGER NOT NULL, reason TEXT)""");
        }
        try (ExecJournal j = new ExecJournal(db.toString())) {
            j.quote(100.0, 99.0, 101.0, 0.5, true, null, 0.004);
        }
        List<ReplayFair.Tick> ticks = ReplayRunner.readTicks(db.toString(), 0);
        assertTrue(ticks.size() == 1, "тик должен записаться в дополненную таблицу");
        assertEquals(0.004, ticks.getFirst().pressure(), 0.0);
    }
}
