package org.home.data.revx.exec;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * ОБЩЕЕ ВЕДРО ЗАМЕН НА ВСЕХ БОТОВ СЕРВЕРА (03.11.2026, владелец: «самим решать, что сейчас важнее отправить»).
 *
 * Лимит {@code PUT /orders} — 10 в секунду на весь аккаунт, а каждый бот решает за себя: опасную замену одного
 * отвергали из-за того, что другой двигал безопасную. Боты — отдельные процессы на одной машине, поэтому счётчик
 * лежит в файле, отображённом в память, и берётся сравнением с обменом, без блокировок и системных вызовов.
 *
 * Ведро — по времени (GCRA): в файле один {@code long}, момент, когда ведро было бы полным. Опасная замена (рынок
 * идёт на заявку, или заявка уходит от рынка) может взять всё ведро; обычная — только пока в нём остаётся
 * {@code reserve} жетонов. Отказ стоит тика без запроса, 429 — наказания, поэтому лучше не спрашивать.
 */
public final class ReplaceBucket {

    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private final ByteBuffer buf;
    private final long stepMs;
    private final int burst;
    private final int reserve;

    public ReplaceBucket(Path file, double perSecond, int burst, int reserve) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            this.buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, Long.BYTES);
        }
        this.stepMs = Math.max(1, Math.round(1000 / perSecond));
        this.burst = burst;
        this.reserve = Math.min(reserve, burst - 1);
    }

    /**
     * Взять жетон.
     *
     * @return 0 — взят; иначе через сколько мс он появится для этой очерёдности
     */
    public long tryTake(boolean danger, long nowMs) {
        long limit = nowMs + (danger ? burst : burst - reserve) * stepMs;
        while (true) {
            long tat = (long) LONGS.getVolatile(buf, 0);
            long next = Math.max(tat, nowMs) + stepMs;
            if (next > limit) {
                return next - limit;
            }
            if (LONGS.compareAndSet(buf, 0, tat, next)) {
                return 0;
            }
        }
    }
}
