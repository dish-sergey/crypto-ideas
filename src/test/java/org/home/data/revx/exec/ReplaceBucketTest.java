package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaceBucketTest {

    /** Не @TempDir: на Windows отображённый в память файл не удалить, уборка JUnit падает. */
    private static Path file() throws Exception {
        Path f = Files.createTempFile("replace-bucket", ".bin");
        f.toFile().deleteOnExit();
        return f;
    }

    @Test
    void safeStopsAtReserveDangerTakesTheRest() throws Exception {
        ReplaceBucket b = new ReplaceBucket(file(), 10, 7, 3);
        long now = 1_000_000;
        for (int i = 0; i < 4; i++) {
            assertEquals(0, b.tryTake(false, now), "безопасная " + i);
        }
        assertTrue(b.tryTake(false, now) > 0, "запас остаётся опасным");
        for (int i = 0; i < 3; i++) {
            assertEquals(0, b.tryTake(true, now), "опасная " + i);
        }
        assertTrue(b.tryTake(true, now) > 0, "ведро пусто");
    }

    @Test
    void refillsWithTimeAndReportsWait() throws Exception {
        ReplaceBucket b = new ReplaceBucket(file(), 10, 2, 0);
        long now = 1_000_000;
        assertEquals(0, b.tryTake(true, now));
        assertEquals(0, b.tryTake(true, now));
        assertEquals(100, b.tryTake(true, now), "следующий жетон через шаг");
        assertEquals(0, b.tryTake(true, now + 100));
    }

    @Test
    void twoHandlesShareOneBucket() throws Exception {
        Path f = file();
        ReplaceBucket a = new ReplaceBucket(f, 10, 2, 0);
        ReplaceBucket c = new ReplaceBucket(f, 10, 2, 0);
        long now = 1_000_000;
        assertEquals(0, a.tryTake(true, now));
        assertEquals(0, c.tryTake(true, now));
        assertTrue(a.tryTake(true, now) > 0, "счётчик один на всех");
    }

    @Test
    void retryAfterIsMillisecondsAndOnlyOn429() {
        assertEquals(177, TradeClient.retryAfterMs(429, "177"));
        assertEquals(-1, TradeClient.retryAfterMs(429, null));
        assertEquals(-1, TradeClient.retryAfterMs(429, "soon"));
        assertEquals(-1, TradeClient.retryAfterMs(200, "177"));
    }
}
