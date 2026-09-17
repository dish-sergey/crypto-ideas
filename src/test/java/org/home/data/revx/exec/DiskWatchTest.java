package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ДИСК В СВОДКЕ: «суток запаса» считается от прироста, а не от красивого числа.
 *
 * <h2>Зачем</h2>
 *
 * 17.09.2026 диск на ARM дошёл до 92% (4.0 ГБ), и узналось это случайно — при
 * разборе другой поломки (задача A65). Отказ записи книги невосполним, поэтому
 * тревога нужна заранее.
 *
 * ⚠️ Гигабайты сами по себе ничего не значат: пять свободных — это неделя при
 * работающем сборщике и вечность при остановленном. Поэтому сводка печатает
 * ПРИРОСТ (размер базы на длину окна книги) и срок до отказа записи. И этот срок
 * отвечает на вопрос «сколько осталось, если чистка снова сломается»: при
 * работающей чистке файл на полке и не растёт вовсе.
 */
class DiskWatchTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void daysLeftComesFromMeasuredGrowth() {
        // База 9.7 ГБ за 15.6 суток = 0.62 ГБ/сут; свободно 19 ГБ.
        InfoBot.Disk d = new InfoBot.Disk(45 * GB, 19 * GB, (long) (9.7 * GB), 15.6);
        assertEquals(0.62, d.gbPerDay(), 0.02, "прирост = размер базы / длина окна");
        assertEquals(30.5, d.daysLeft(), 1.0, "19 ГБ при 0.62 ГБ/сут — это месяц");
        assertEquals(58, d.usedPct(), 1, "занято считается от общего объёма");
    }

    @Test
    void emptyBookWindowGivesNoForecastInsteadOfNonsense() {
        // Сборщик только что поднялся: окна ещё нет, делить не на что.
        InfoBot.Disk d = new InfoBot.Disk(45 * GB, 19 * GB, 100 * 1024 * 1024L, 0);
        assertEquals(0.0, d.gbPerDay(), 1e-9);
        assertTrue(Double.isInfinite(d.daysLeft()), "без прироста срока нет, а не ноль");
        assertTrue(d.line().contains("свободно"), "место показывается всегда");
        assertTrue(!d.line().contains("хватит на"), "срок без прироста не печатается");
    }

    @Test
    void thresholdsAreWeekAndCoupleOfDaysAtMeasuredRate() {
        // Пороги обязаны иметь смысл в сутках при измеренных 0.6–0.8 ГБ/сут.
        double slow = 0.6;
        double fast = 0.8;
        assertTrue(InfoBot.DISK_WARN_BYTES / (double) GB / fast >= 6, "предупреждение — неделя");
        assertTrue(InfoBot.DISK_CRIT_BYTES / (double) GB / slow <= 4, "красный — пара суток");
        assertTrue(InfoBot.DISK_CRIT_BYTES < InfoBot.DISK_WARN_BYTES);
    }

    @Test
    void staleBookWindowIsTheEarlierSignal() {
        // Окно книги ловит сломанную чистку ЗА ДНИ до того, как кончится место:
        // 13–17.09.2026 чистка падала через день, а диска ещё хватало.
        InfoBot.Disk stale = new InfoBot.Disk(45 * GB, 12 * GB, (long) (9.7 * GB), 18.0);
        assertTrue(stale.bookDays() > InfoBot.BOOK_DAYS_ALARM,
                "18 суток книги при плане 14 — чистка не доехала");
        assertTrue(stale.freeBytes() > InfoBot.DISK_WARN_BYTES,
                "и места при этом ещё вдоволь — потому сигнал и нужен отдельный");
    }
}
