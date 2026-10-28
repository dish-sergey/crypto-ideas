package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ОТСТУП ПО РАСПИСАНИЮ (док. 217): файл «ts_ms;бп», шапка и пустые строки пропускаются, пустой файл — ошибка, а не тишина. */
class OffsetScheduleTest {

    @Test
    void читаетОтметкиИОтдаётДействующуюНаМомент(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("s.csv");
        Files.writeString(f, "ts_ms;bp\n1000;12\n\n5000;10.5\n");
        var m = QuoteLoop.loadOffsetSchedule(f.toString());
        assertNull(m.floorEntry(999L), "до первой отметки — настройка запуска");
        assertEquals(12.0, m.floorEntry(4999L).getValue());
        assertEquals(10.5, m.floorEntry(9999L).getValue());
    }

    @Test
    void безСвойстваРасписанияНет_аПустойФайлПадает(@TempDir Path dir) throws Exception {
        assertTrue(QuoteLoop.loadOffsetSchedule(null).isEmpty());
        Path f = dir.resolve("e.csv");
        Files.writeString(f, "ts_ms;bp\n");
        assertThrows(IllegalStateException.class, () -> QuoteLoop.loadOffsetSchedule(f.toString()));
    }

    @Test
    void темпПостановок_ближеПриЗапасе_ширеПриРасходе_вПределах() {
        assertEquals(8.0, QuoteLoop.tempoOffset(12, 0.0, 16, 0.75, 4), "пустое ведро — до упора ближе");
        assertEquals(12.0, QuoteLoop.tempoOffset(12, 0.75, 16, 0.75, 4), "на цели — настройка");
        assertEquals(16.0, QuoteLoop.tempoOffset(12, 1.5, 16, 0.75, 4), "перерасход — до упора шире");
        assertEquals(10.0, QuoteLoop.tempoOffset(12, 0.625, 16, 0.75, 4), "к целому б.п.");
    }
}
