package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠️ ЛОВУШКА, СТОИВШАЯ ГЛАВНОГО ВЫВОДА ОТЧЁТА ЗА 11–12.09.2026.
 *
 * Затравку и передачи между ботами ({@code status='handover'}) соблазнительно
 * выкинуть из книги партий: они не сделки, и в статистику заработка им
 * действительно нельзя. Но позицию они ОТКРЫВАЮТ, и если их не положить в
 * книгу, продажа этой позиции не находит встречной партии — книга уходит в
 * ШОРТ, а каждое следующее «закрытие» становится покрытием фантомного шорта.
 *
 * Время такой пары — это расстояние от ПРОДАЖИ до более поздней ПОКУПКИ, а не
 * время, которое лот пролежал под риском. На живом журнале бота A это дало
 * медиану 121 минуту вместо 8.5 и максимум 1238.6 вместо 187.0 — и ровно эти
 * числа попали в отчёт как «медиана удержания 209 минут, максимум 1239»,
 * откуда вышел вывод «конструкция не окупает риск ни при каких настройках».
 *
 * Та же ошибка уже была сделана 04.09.2026 и описана в {@link FifoLedger} —
 * и повторена разовым скриптом четыре дня спустя. Тест нужен именно потому,
 * что ошибка выглядит как осторожность.
 */
class HoldCheckTest {

    private static final long MIN = 60_000L;

    /** {@code match} приватный: вызываем отражением, чтобы не расширять API ради теста. */
    @SuppressWarnings("unchecked")
    private static List<?> match(List<ExecJournal.FillRow> fills, boolean fifo,
                                 boolean skipHandover) throws Exception {
        Method m = HoldCheck.class.getDeclaredMethod("match", List.class, boolean.class,
                boolean.class);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, fills, fifo, skipHandover);
    }

    private static double minutes(Object pair) throws Exception {
        Method m = pair.getClass().getDeclaredMethod("minutes");
        m.setAccessible(true);
        return (double) m.invoke(pair);
    }

    /**
     * Затравка в 10:00, покупка в 11:00, две продажи в 11:10 и 11:20.
     *
     * С затравкой В КНИГЕ: первая продажа закрывает её (70 минут, пара-передача),
     * вторая закрывает настоящую покупку (20 минут).
     * БЕЗ затравки: первая продажа закрывает покупку (10 минут), вторая уходит в
     * шорт и ждёт покупки, которой в окне нет.
     */
    @Test
    void seedInBookGivesRealHoldingTime() throws Exception {
        List<ExecJournal.FillRow> fills = List.of(
                new ExecJournal.FillRow(600 * MIN, true, 1, 100, 0, true),   // затравка
                new ExecJournal.FillRow(660 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(670 * MIN, false, 1, 101, 0, false),
                new ExecJournal.FillRow(680 * MIN, false, 1, 101, 0, false));

        List<?> withSeed = match(fills, true, false);
        assertEquals(2, withSeed.size());
        assertEquals(70.0, minutes(withSeed.get(0)), 1e-9);
        assertEquals(20.0, minutes(withSeed.get(1)), 1e-9);
    }

    /**
     * ⚠️ Без затравки в книге вторая продажа открывает ШОРТ, и в окне он не
     * закрывается вовсе: пара пропадает. На живом журнале она не пропадает, а
     * закрывается покупкой через много часов — и эти часы попадают в статистику
     * как «время под позицией».
     */
    @Test
    void seedSkippedTurnsBookShort() throws Exception {
        List<ExecJournal.FillRow> fills = List.of(
                new ExecJournal.FillRow(600 * MIN, true, 1, 100, 0, true),
                new ExecJournal.FillRow(660 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(670 * MIN, false, 1, 101, 0, false),
                new ExecJournal.FillRow(680 * MIN, false, 1, 101, 0, false),
                new ExecJournal.FillRow(1400 * MIN, true, 1, 99, 0, false));

        List<?> skipped = match(fills, true, true);
        assertEquals(2, skipped.size());
        assertEquals(10.0, minutes(skipped.get(0)), 1e-9);
        // Покрытие фантомного шорта: 1400 − 680 = 720 минут «удержания»,
        // которого не было — позиции всё это время не существовало.
        assertEquals(720.0, minutes(skipped.get(1)), 1e-9);
    }

    /** LIFO берёт партию из хвоста: та же затравка, но закрывается последней. */
    @Test
    void lifoTakesTheNewestLot() throws Exception {
        List<ExecJournal.FillRow> fills = List.of(
                new ExecJournal.FillRow(600 * MIN, true, 1, 100, 0, true),
                new ExecJournal.FillRow(660 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(670 * MIN, false, 1, 101, 0, false));

        List<?> lifo = match(fills, false, false);
        assertEquals(1, lifo.size());
        assertEquals(10.0, minutes(lifo.get(0)), 1e-9);

        List<?> fifo = match(fills, true, false);
        assertEquals(70.0, minutes(fifo.get(0)), 1e-9);
    }

    /** Частичное закрытие: продажа вдвое крупнее партии закрывает две подряд. */
    @Test
    void oneSellClosesSeveralLots() throws Exception {
        List<ExecJournal.FillRow> fills = List.of(
                new ExecJournal.FillRow(600 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(620 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(630 * MIN, false, 2, 101, 0, false));

        List<?> pairs = match(fills, true, false);
        assertEquals(2, pairs.size());
        assertEquals(30.0, minutes(pairs.get(0)), 1e-9);
        assertEquals(10.0, minutes(pairs.get(1)), 1e-9);
    }

    /** Пара считается передачей, если ХОТЬ ОДНА нога передача. */
    @Test
    void pairIsHandoverIfEitherLegIs() throws Exception {
        List<ExecJournal.FillRow> fills = List.of(
                new ExecJournal.FillRow(600 * MIN, true, 1, 100, 0, false),
                new ExecJournal.FillRow(610 * MIN, false, 1, 101, 0, true));

        List<?> pairs = match(fills, true, false);
        assertEquals(1, pairs.size());
        Method handover = pairs.get(0).getClass().getDeclaredMethod("handover");
        handover.setAccessible(true);
        assertTrue((boolean) handover.invoke(pairs.get(0)));
    }
}
