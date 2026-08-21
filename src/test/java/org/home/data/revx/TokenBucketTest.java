package org.home.data.revx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ограничитель темпа. Изначально он задумывался так, чтобы РАЗРЕШАТЬ залп из
 * двух ног пары (ТЗ §3.3 требует одновременности). Замер площадки это отменил:
 * запросы встык отбиваются 429, минимальный интервал ~1 с. Теперь бакет,
 * наоборот, обязан залпы запрещать.
 */
class TokenBucketTest {

    @Test
    void limitsSustainedRate() {
        TokenBucket bucket = new TokenBucket(20, 4);   // 20 req/s, бёрст 4
        bucket.acquire(4);                              // выбираем стартовый запас

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            bucket.acquire(1);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 20 разрешений при 20/с — не быстрее ~1 с (допуск на планировщик)
        assertTrue(elapsedMs >= 850, "темп не ограничен: " + elapsedMs + " мс на 20 разрешений");
    }

    @Test
    void capacityOneForbidsBackToBackRequests() {
        // Площадка ограничивает минимальный интервал между запросами (~1 с), а не
        // средний темп: два запроса встык = 429 на втором. Ёмкость 1 гарантирует,
        // что накопленный простой не выльется в залп.
        TokenBucket bucket = new TokenBucket(1, 1);
        bucket.acquire(1);
        sleepMs(3000);                                  // копим простой

        long start = System.nanoTime();
        bucket.acquire(1);
        bucket.acquire(1);                              // второй обязан ждать полный интервал
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 900, "запросы ушли встык через " + elapsedMs + " мс — будет 429");
    }

    @Test
    void refusesBatchLargerThanCapacity() {
        TokenBucket bucket = new TokenBucket(5, 4);
        // тихое ожидание было бы вечным: бакет никогда не наберёт больше ёмкости
        assertThrows(IllegalArgumentException.class, () -> bucket.acquire(5));
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
