package org.home.data.trade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** SQLite-аудит S5: персистентность сыгранных событий + запись входов/выходов/Telegram, best-effort. */
class S5DbTest {

    @Test void dismissedPersistsAcrossInstances() throws Exception {
        Path dir = Files.createTempDirectory("s5db");
        String path = dir.resolve("s5.db").toString();
        S5Db db = new S5Db(path);
        db.record("PF_XPLUSD@20320");
        assertTrue(db.load().contains("PF_XPLUSD@20320"));
        db.record("PF_XPLUSD@20320");                              // повтор не дублируется (INSERT OR IGNORE)
        assertEquals(1, db.load().size());
        // новый инстанс на том же файле видит запись (переживает рестарт)
        assertTrue(new S5Db(path).load().contains("PF_XPLUSD@20320"), "сыгранное событие пережило рестарт");
    }

    @Test void recordsOpenCloseTelegramWithoutError() throws Exception {
        Path dir = Files.createTempDirectory("s5db");
        S5Db db = new S5Db(dir.resolve("s5.db").toString());
        assertDoesNotThrow(() -> {
            db.recordOpen("e1", "PF_APTUSD", 20005, "investors", 4.0, 10.0, 40.0);
            db.recordClose("PF_APTUSD", "STOP", 10.0, 13.0, -0.30, "стоп");
            db.recordTelegram("out", "open", "Открыт шорт PF_APTUSD");
            db.recordTelegram("in", "command", "/status");
        });
    }

    @Test void dbFailureIsBestEffort() throws Exception {
        // родитель — файл, а не каталог → БД не откроется; операции не должны бросать (торговля важнее аудита)
        Path file = Files.createTempFile("notadir", ".tmp");
        S5Db db = new S5Db(file.resolve("s5.db").toString());
        assertDoesNotThrow(() -> {
            db.record("x");
            db.recordOpen("e", "S", 1, "c", 1, 1, 1);
            db.recordClose("S", "STOP", 1, 1, 0, "n");
            db.recordTelegram("out", "k", "t");
        });
        assertTrue(db.load().isEmpty(), "БД недоступна → load пустой, без падения");
    }
}
