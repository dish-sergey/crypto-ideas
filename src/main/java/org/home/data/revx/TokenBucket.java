package org.home.data.revx;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Глобальный ограничитель темпа запросов (токен-бакет).
 *
 * Почему не пер-хостовый интервал, как в {@link org.home.data.core.ApiClient}:
 * там каждый запрос к хосту сериализуется минимальным интервалом, а ТЗ §3.3
 * требует, чтобы обе ноги пары (USDC и USD) уходили ОДНОВРЕМЕННО — иначе skew
 * между снимками сделает базис неизмеримым. Бакет ограничивает суммарный темп,
 * но позволяет забрать несколько разрешений разом и выстрелить запросами вместе.
 *
 * Честная блокировка (fair lock) — чтобы батчи не голодали при конкуренции.
 */
public class TokenBucket {

    private final double ratePerSecond;
    private final double capacity;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition refilled = lock.newCondition();

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond должен быть > 0: " + ratePerSecond);
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = Math.max(capacity, 1);
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Максимальный размер батча, который можно забрать одним acquire. */
    public int capacity() {
        return (int) capacity;
    }

    /** Блокирует, пока не наберётся {@code permits} разрешений. */
    public void acquire(int permits) {
        if (permits <= 0) {
            return;
        }
        if (permits > capacity) {
            throw new IllegalArgumentException(
                    "запрошено " + permits + " разрешений при ёмкости " + capacity
                            + " — батч надо разбить, иначе бакет никогда не наполнится");
        }
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                long waitNanos = (long) ((permits - tokens) / ratePerSecond * 1_000_000_000L);
                refilled.awaitNanos(Math.max(waitNanos, 1_000_000L));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("прервано ожидание разрешения бакета", e);
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = now;
        }
    }
}
