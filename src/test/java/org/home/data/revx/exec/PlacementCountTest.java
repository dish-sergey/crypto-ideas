package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * СЧЁТЧИК ПОСТАНОВОК: отказ по лимиту токен не тратит, остальное тратит.
 *
 * <h2>Зачем тест</h2>
 *
 * Счётчик решает, когда бот выключится, упершись в суточную долю. До 22.09.2026
 * он считал ВСЕ {@code POST /orders}, включая 429, и это давало видимый перебор
 * там, где его не было: за скользящие сутки до 21.09 12:00 UTC вышло 1022
 * запроса при **978 успешных**, и разница в 44 — ровно отказы по лимиту.
 *
 * <h2>Что именно проверяется</h2>
 *
 * Граница проведена по ОДНОМУ коду, а не по «успешно / неуспешно», и у каждой
 * стороны своя причина:
 * <ul>
 *   <li><b>429 не считается</b> — площадка прямо сказала «не приняла», и смысл
 *       {@code Retry-After} в том, что запрос не состоялся;</li>
 *   <li><b>422 считается</b> — запрос ПРИНЯТ и отклонён по делу; расходует ли
 *       площадка на это токен, неизвестно;</li>
 *   <li><b>ответа нет ({@code NULL}) — считается</b>: дошёл ли запрос, мы не
 *       знаем, и неизвестность обязана быть расходом, а не подарком.</li>
 * </ul>
 */
class PlacementCountTest {

    private static final long T0 = 1_790_000_000_000L;

    @Test
    void отказПоЛимитуНеТратитТокен(@TempDir Path dir) {
        try (ExecJournal j = new ExecJournal(dir.resolve("j.db").toString())) {
            post(j, T0 + 1, 200);
            post(j, T0 + 2, 200);
            post(j, T0 + 3, 429);
            post(j, T0 + 4, 429);

            assertEquals(2, j.placementsSince(T0),
                    "две успешные постановки; два отказа 429 квоту не расходуют");
        }
    }

    @Test
    void принятыеНоОтклонённыеИНеотвеченныеСчитаются(@TempDir Path dir) {
        try (ExecJournal j = new ExecJournal(dir.resolve("j.db").toString())) {
            post(j, T0 + 1, 200);
            post(j, T0 + 2, 422);      // принята и отклонена по делу
            post(j, T0 + 3, null);     // ответа не было — дошла ли, неизвестно
            post(j, T0 + 4, 429);      // единственное, что не считается

            assertEquals(3, j.placementsSince(T0),
                    "200 + 422 + без ответа = 3; вычитается только 429");
        }
    }

    /** ⚠️ Окно скользящее: то, что старше границы, в счёт не идёт. */
    @Test
    void окноСкользящее(@TempDir Path dir) {
        try (ExecJournal j = new ExecJournal(dir.resolve("j.db").toString())) {
            post(j, T0 - 86_400_001L, 200);   // ровно за сутки до границы
            post(j, T0 + 1, 200);

            assertEquals(1, j.placementsSince(T0), "старая постановка выпала из окна");
        }
    }

    /** ⚠️ Замена — ДРУГОЕ ведро (10/с без суточного), в счёт постановок не идёт. */
    @Test
    void заменаНеСчитаетсяПостановкой(@TempDir Path dir) {
        try (ExecJournal j = new ExecJournal(dir.resolve("j.db").toString())) {
            post(j, T0 + 1, 200);
            j.clock(at(T0 + 2));
            j.request("PUT", "/api/1.0/orders/abc", null, 200, "{}", 5, null);

            assertEquals(1, j.placementsSince(T0), "PUT живёт в другом ведре");
        }
    }

    /**
     * Одна постановка с заданным временем.
     *
     * ⚠️ Время журнал берёт из ЧАСОВ, а не из аргумента: прямой вызов
     * {@code System.currentTimeMillis()} в котировщике запрещён ради
     * воспроизводимости стенда. Поэтому здесь подменяются часы.
     */
    private static void post(ExecJournal j, long ts, Integer status) {
        j.clock(at(ts));
        j.request("POST", "/api/1.0/orders", "{}", status,
                status == null ? null : "{}", 5, null);
    }

    private static Clock at(long ts) {
        return new Clock() {
            @Override
            public long now() {
                return ts;
            }

            @Override
            public void sleep(long ms) {
            }
        };
    }
}
