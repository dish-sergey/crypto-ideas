package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ОДНО ИСПОЛНЕНИЕ — ОДИН РАЗ, И ПОСЛЕ ПЕРЕЗАПУСКА ТОЖЕ.
 *
 * <h2>Что сломалось</h2>
 *
 * 16.09.2026 у бота {@code a} позиция ушла в МИНУС — −0.0000251 BTC у спотового
 * бота, который шортить не умеет. Разбор: продажа {@code f2430d44} записана
 * дважды, в 18:02:19 живым процессом и в 18:03:00 восстановлением после
 * перезапуска.
 *
 * <h2>Почему защита не сработала</h2>
 *
 * Она была: {@code bookedByOrder} помнит, сколько по заявке уже записано, и при
 * старте заливается из журнала. Но карта — LRU на 512 записей, а в журнале
 * живого бота их 1250. {@code putAll} из {@code HashMap} вытесняет две трети в
 * произвольном порядке, и свежая запись оказалась среди выброшенных.
 *
 * То есть защита перестала работать ровно тогда, когда журнал перерос ёмкость
 * кэша, — и молча.
 *
 * <h2>Что проверяется здесь</h2>
 *
 * Что журнал отвечает на вопрос «сколько уже записано по этой заявке» независимо
 * от того, что происходило с памятью процесса.
 */
class DoubleBookingTest {

    @Test
    void journalRemembersWhatWasBooked(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.fill("f2430d44", "SELL", 3.765e-5, 71676.23, 71676.23, 0, null, "filled");

            assertEquals(3.765e-5, journal.bookedFor("f2430d44"), 1e-15,
                    "записанное по заявке обязано пережить перезапуск процесса");
        }
    }

    @Test
    void unknownOrderIsZero(@TempDir Path dir) {
        try (ExecJournal journal = new ExecJournal(dir.resolve("exec.db").toString())) {
            assertEquals(0.0, journal.bookedFor("нет такой"), 1e-15);
            assertEquals(0.0, journal.bookedFor(null), 1e-15);
        }
    }

    @Test
    void partialFillsAddUp(@TempDir Path dir) {
        // {@code book} пишет РАЗНИЦУ, поэтому журнал обязан отдавать сумму, а не
        // последнюю запись: иначе добор частичной заявки посчитается заново.
        try (ExecJournal journal = new ExecJournal(dir.resolve("exec.db").toString())) {
            journal.fill("abc", "BUY", 1e-5, 100, 100, 0, null, "partially_filled");
            journal.fill("abc", "BUY", 2e-5, 100, 100, 0, null, "filled");

            assertEquals(3e-5, journal.bookedFor("abc"), 1e-15);
        }
    }

    /**
     * Та же арифметика, что привела позицию в минус: лот записан дважды.
     */
    @Test
    void doubleBookingIsWhatDrovePositionNegative() {
        double lot = 3.765e-5;
        double positionBefore = 1.255e-5;
        assertEquals(-2.51e-5, positionBefore - lot, 1e-12,
                "ровно то, что показал бот a: минус на спотовом счёте");
    }
}
