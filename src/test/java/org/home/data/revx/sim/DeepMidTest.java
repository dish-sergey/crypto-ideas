package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Глубокая середина (док. 154 §IV): опора по цене, где набирается D денег.
 *
 * ⚠️ Главная ловушка здесь — ПОРЯДОК УРОВНЕЙ. Площадка отдаёт аски в убывающем
 * порядке (проверено на 324 снимках из 324), и наивный проход по строке начал
 * бы набирать объём с ХУДШЕГО уровня. Тогда глубокая середина считалась бы по
 * краю книги: у неё был бы правдоподобный вид и совершенно неверное значение —
 * ровно тот класс ошибок, на который в этом проекте уже уходили недели.
 */
class DeepMidTest {

    /** Книга: лучший бид 99 на 1 монету, аск 101 на 1; дальше по 10 монет. */
    private static FlowMarkout.Top book() {
        return new FlowMarkout.Top(99, 101, 1, 1,
                "98:10,97:10,96:10",
                // аски намеренно в УБЫВАЮЩЕМ порядке, как отдаёт площадка
                "104:10,103:10,102:10");
    }

    @Test
    void topLevelAloneIsEnoughForSmallDepth() {
        // 99 × 1 = 99 денег с бида, 101 с аска: на D = 50 хватает лучших уровней.
        assertEquals(100.0, book().deepMid(50), 1e-9);
    }

    @Test
    void deeperMoneyWalksTheBookFromTheBestLevel() {
        // D = 500: бид 99 + 98×10 = 1079 набирается на 98; аск 101 + 102×10 на 102.
        assertEquals((98 + 102) / 2.0, book().deepMid(500), 1e-9);
    }

    @Test
    void askSideIsNotReadFromTheWorstLevel() {
        // Если бы уровни брались в том порядке, в каком их отдаёт площадка,
        // аск получился бы 104 и середина уехала бы на 100 б.п. вверх.
        assertTrue(book().deepMid(500) < 101, "аск набирается с ближнего уровня, а не с дальнего");
    }

    @Test
    void undefinedWhenBookIsThinnerThanAsked() {
        // Всего в книге около 3000 денег с каждой стороны; просим 100 000.
        assertTrue(Double.isNaN(book().deepMid(100_000)),
                "опоры нет — это не ноль и не середина, подменять её нельзя");
    }

    @Test
    void vwapIsSmootherAndLiesBetweenLevels() {
        // Средневзвешенная цена набора 500 денег с бида лежит между 99 и 98.
        double vwap = book().deepVwapMid(500);
        assertTrue(vwap > 98 && vwap < 102, "средневзвешенная внутри пройденных уровней");
        // И она НЕ равна ступенчатой: в этом весь смысл второго варианта.
        assertTrue(Math.abs(vwap - book().deepMid(500)) > 1e-6);
    }

    @Test
    void emptyDepthColumnsFallBackToTopOfBook() {
        // Старые базы (до 10.09.2026) глубины не имеют: опора должна быть
        // неопределённой, а не молча посчитанной по одному уровню.
        FlowMarkout.Top shallow = new FlowMarkout.Top(99, 101, 1, 1);
        assertEquals(100.0, shallow.deepMid(50), 1e-9);
        assertTrue(Double.isNaN(shallow.deepMid(5000)));
    }
}
