package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ПОИСК ОПОРЫ ПО ВРЕМЕНИ: «не позже» и «не раньше» — разные стороны, и путать их
 * дорого.
 *
 * <h2>Зачем тест на две строчки поиска</h2>
 *
 * На этой паре методов держатся оба прибора по ленте: {@code at} даёт опору В
 * МОМЕНТ принта (от неё считается расстояние), {@code after} — опору ЧЕРЕЗ
 * горизонт (от неё считается отбор). Ошибка на один тик здесь не падает и не
 * видна в отчёте: она просто смещает отбор в сторону, на которую цена уже
 * сходила, — ровно тот класс ошибки, который в этом проекте уже стоил
 * переделанного вывода про марку сделки (тест {@code CarryIdentityTest}).
 */
class TapeFairTest {

    private static final TapeData.Fair FAIR = new TapeData.Fair(
            new long[]{1_000, 2_000, 3_000},
            new double[]{100, 200, 300});

    @Test
    void опораВМоментБерётсяНеПозже() {
        assertEquals(0, FAIR.at(999), "до начала ряда опоры нет");
        assertEquals(100, FAIR.at(1_000), "ровно на отметке — она сама");
        assertEquals(100, FAIR.at(1_999), "между тиками — предыдущий");
        assertEquals(300, FAIR.at(9_999), "после конца — последний известный");
    }

    @Test
    void опораЧерезГоризонтБерётсяНеРаньше() {
        assertEquals(100, FAIR.after(1_000), "ровно на отметке — она сама");
        assertEquals(200, FAIR.after(1_001), "между тиками — следующий");
        assertEquals(100, FAIR.after(0), "до начала ряда — первый");
        assertEquals(0, FAIR.after(3_001), "за концом ряда опоры нет, и это ноль, а не последняя");
    }
}
