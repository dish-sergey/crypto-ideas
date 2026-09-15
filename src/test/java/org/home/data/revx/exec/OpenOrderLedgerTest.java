package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ЖУРНАЛ ОТКРЫТЫХ ЗАЯВОК: идентификатор переживает процесс.
 *
 * <h2>Зачем</h2>
 *
 * Идентификатор стоящей заявки жил только в памяти, и с концом процесса
 * исчезал. Отсюда две потери, обе наблюдённые на живых деньгах:
 * <ul>
 *   <li>заявка исполнилась в момент перезапуска — спросить не о чем, сделка
 *       теряется навсегда (13.09.2026, продажа {@code dc4d7e77} на 0.00120795
 *       ETH: площадка отвечает {@code filled}, в журнале бота её нет);</li>
 *   <li>заявка жива, но не попала в {@code /orders/active} — её никто не
 *       заменит и не снимет, а резерв делает монету неотчуждаемой (15.09.2026:
 *       весь ETH, BTC и SOL на счёте с {@code available} ноль).</li>
 * </ul>
 *
 * Поэтому запись открывается на КАЖДОЙ постановке и замене, а закрывается
 * только выясненной судьбой.
 */
class OpenOrderLedgerTest {

    @Test
    void placedOrderSurvivesRestart(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder("id-1", "SELL", 0, 2500.0, 0.0012, 1_000);
        }
        // Другой процесс: журнал открыт заново, память пуста.
        try (ExecJournal journal = new ExecJournal(path)) {
            List<ExecJournal.OpenOrder> open = journal.openOrders(0);
            assertEquals(1, open.size());
            assertEquals("id-1", open.get(0).venueId());
            assertEquals("SELL", open.get(0).side());
            assertEquals(2500.0, open.get(0).price(), 1e-9);
        }
    }

    @Test
    void closedOrderIsNotAskedAgain(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder("id-1", "BUY", 0, 100, 1, 1_000);
            journal.openOrder("id-2", "BUY", 0, 101, 1, 2_000);
            journal.closeOrder("id-1", "filled", 3_000);
            List<ExecJournal.OpenOrder> open = journal.openOrders(0);
            assertEquals(1, open.size());
            assertEquals("id-2", open.get(0).venueId());
        }
    }

    @Test
    void replacementKeepsBothUntilPredecessorIsClosed(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder("old", "SELL", 0, 100, 1, 1_000);
            journal.openOrder("new", "SELL", 0, 101, 1, 2_000);
            // Пока предшественник не закрыт, в списке обе: замена могла и не
            // пройти, и тогда старая заявка всё ещё стоит в книге.
            assertEquals(2, journal.openOrders(0).size());
            journal.closeOrder("old", "replaced", 2_001);
            assertEquals(1, journal.openOrders(0).size());
        }
    }

    @Test
    void oldRecordsAreOutsideTheWindow(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder("ancient", "BUY", 0, 100, 1, 1_000);
            journal.openOrder("fresh", "BUY", 0, 100, 1, 10_000);
            assertEquals(1, journal.openOrders(5_000).size());
            assertEquals("fresh", journal.openOrders(5_000).get(0).venueId());
        }
    }

    @Test
    void repeatedOpenUpdatesPriceInsteadOfDuplicating(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder("id-1", "BUY", 0, 100, 1, 1_000);
            journal.openOrder("id-1", "BUY", 0, 105, 2, 2_000);
            List<ExecJournal.OpenOrder> open = journal.openOrders(0);
            assertEquals(1, open.size());
            assertEquals(105.0, open.get(0).price(), 1e-9);
            assertEquals(2.0, open.get(0).size(), 1e-9);
        }
    }

    @Test
    void emptyIdIsIgnored(@TempDir Path dir) {
        String path = dir.resolve("exec.db").toString();
        try (ExecJournal journal = new ExecJournal(path)) {
            journal.openOrder(null, "BUY", 0, 100, 1, 1_000);
            journal.openOrder("", "BUY", 0, 100, 1, 1_000);
            assertTrue(journal.openOrders(0).isEmpty(),
                    "заявка без идентификатора в журнал не пишется: спросить о ней нечем");
        }
    }
}
