package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приведение цены к допустимому тику — и то, что оно ДЕЛАЕТ ВИДИМЫМ.
 *
 * Площадка округляла цену молча, и из-за этого почти сутки считалось, что у ENA
 * работает сетка из трёх уровней. Её шаг цены 0.0001 — это 6.01 б.п. при цене
 * 0.16641, а шаг сетки задан в 2 б.п.: три уровня различаются арифметически, но
 * ложатся на один тик. 07.09.2026 в книге стояли три аска по одной цене.
 *
 * Здесь проверяется арифметика снапа и считается, сколько уровней ФАКТИЧЕСКИ
 * получается у каждой живой пары. Второе важнее первого: именно оно ловит
 * настройку, в которой сетка вырождается.
 */
class TickRoundingTest {

    /** Та же арифметика, что в {@code QuoteLoop.onTick}. */
    private static double onTick(boolean buy, double price, double step) {
        double units = price / step;
        return (buy ? Math.floor(units) : Math.ceil(units)) * step;
    }

    @Test
    void покупкаОкругляетсяВниз() {
        // Наружу от рынка: округление к ближайшему могло бы подтянуть заявку
        // внутрь и превратить её в пересекающую.
        assertEquals(79223.65, onTick(true, 79223.6589, 0.01), 1e-9);
        assertTrue(onTick(true, 79223.6589, 0.01) <= 79223.6589);
    }

    @Test
    void продажаОкругляетсяВверх() {
        assertEquals(79223.66, onTick(false, 79223.6511, 0.01), 1e-9);
        assertTrue(onTick(false, 79223.6511, 0.01) >= 79223.6511);
    }

    @Test
    void ценаНаТикеНеДвигается() {
        assertEquals(2495.42, onTick(true, 2495.42, 0.01), 1e-9);
        assertEquals(2495.42, onTick(false, 2495.42, 0.01), 1e-9);
    }

    /**
     * Сколько уровней ФАКТИЧЕСКИ различимо у каждой живой пары.
     *
     * ⚠️ Это и есть тот тест, которого не хватало. Настройка «три уровня шагом
     * 2 б.п.» у ENA даёт один, и узнать это по конфигу нельзя — нужно поделить
     * шаг сетки на шаг цены.
     */
    @Test
    void сеткаНеДолжнаВырождатьсяУЖивыхПар() {
        // пара, цена, шаг цены, шаг сетки в долях цены
        record Pair(String name, double price, double step, double gridStep) { }
        var pairs = new Pair[]{
                new Pair("BTC", 79223.65, 0.01, 0.0002),
                new Pair("ETH", 2495.42, 0.01, 0.0002),
                new Pair("SOL", 103.884, 0.001, 0.0002),
                new Pair("ADA", 0.2204932, 0.00001, 0.0002),
                new Pair("PEPE", 0.00000361, 0.0000000001, 0.0002),
        };
        for (Pair p : pairs) {
            double gridBp = 10000 * p.gridStep();
            double tickBp = 10000 * p.step() / p.price();
            assertTrue(gridBp >= tickBp,
                    p.name() + ": шаг сетки " + String.format("%.2f", gridBp)
                            + " б.п. меньше тика " + String.format("%.2f", tickBp)
                            + " б.п. — уровни лягут на одну цену");
        }
    }

    /** ENA на нынешней настройке вырождается — это зафиксировано, а не забыто. */
    @Test
    void ENAНаДвухБазисныхПунктахВырождается() {
        double price = 0.16641185;
        double step = 0.0001;
        assertTrue(10000 * step / price > 2.0,
                "если тик ENA стал мельче 2 б.п., сетку можно вернуть на 2 — проверь заново");
        // Три уровня 20/22/24 б.п. на продажу.
        double[] levels = {price * 1.0020, price * 1.0022, price * 1.0024};
        long distinct = java.util.Arrays.stream(levels)
                .map(p -> onTick(false, p, step)).distinct().count();
        assertTrue(distinct < 3,
                "три уровня ENA обязаны схлопнуться, а вышло различимых: " + distinct);
        // ⚠️ Живьём наблюдались ТРИ одинаковых аска, а тут выходит два разных:
        // площадка округляет по-своему, и какое именно у неё правило, мы не
        // знаем. Отсюда и смысл снапа — считать самим, а не гадать.
        assertEquals(2, distinct,
                "при нашем округлении вверх ENA даёт ровно две различимые цены из трёх");
    }
}
