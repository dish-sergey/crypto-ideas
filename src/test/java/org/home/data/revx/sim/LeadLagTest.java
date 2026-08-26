package org.home.data.revx.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кто кого ведёт. Проверка не «корреляция посчиталась», а «сдвиг найден там,
 * где он заложен»: синтетика строится с ИЗВЕСТНЫМ запаздыванием, и тест падает,
 * если знак или величина сдвига определяются неправильно.
 */
class LeadLagTest {

    private static SimEngine.Window window(long tsMs, double fair, double mid) {
        BookView book = BookView.of(new double[][]{{mid - 0.5, 1.0}}, new double[][]{{mid + 0.5, 1.0}});
        return SimEngine.Window.of(tsMs, fair, book, List.of());
    }

    /** Опора повторяет котируемую книгу с запаздыванием ровно в lag окон. */
    private static List<SimEngine.Window> withLag(int lag, int size) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(42);
        double[] path = new double[size + lag];
        path[0] = 100.0;
        for (int i = 1; i < path.length; i++) {
            path[i] = path[i - 1] * (1 + (rng.nextDouble() - 0.5) * 0.002);
        }
        List<SimEngine.Window> windows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            windows.add(window(i * 5_000L, path[i], path[i + lag]));
        }
        return windows;
    }

    @Test
    void синхронныеРядыДаютПикНаНуле() {
        LeadLag.Result result = LeadLag.compute(withLag(0, 500), 6);
        assertEquals(0, result.peak().lagWindows());
        assertTrue(result.peak().correlation() > 0.9,
                "идентичные ряды обязаны дать корреляцию около единицы");
    }

    /**
     * Котируемая книга идёт впереди на два окна — значит опора догоняет её через
     * два окна, и пик обязан быть на lag = +2. Знак здесь и есть весь смысл теста:
     * перепутанный знак превратил бы «опора отстаёт» в «опора опережает».
     */
    @Test
    void опораОтстаётНаДваОкнаИЭтоВидноПоЗнакуСдвига() {
        LeadLag.Result result = LeadLag.compute(withLag(2, 500), 6);
        assertEquals(2, result.peak().lagWindows());
    }

    @Test
    void частотаИзмененийСчитаетсяПоОбоимРядам() {
        List<SimEngine.Window> windows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            // опора стоит на месте, котируемая книга меняется каждое окно
            windows.add(window(i * 5_000L, 100.0, 100.0 + i * 0.01));
        }
        LeadLag.Result result = LeadLag.compute(windows, 3);
        assertEquals(0.0, result.referenceChangeRate(), 1e-9);
        assertEquals(1.0, result.quotedChangeRate(), 1e-9);
    }

    @Test
    void пустыхДанныхДостаточноЧтобыНеУпасть() {
        LeadLag.Result result = LeadLag.compute(List.of(), 6);
        assertTrue(result.points().isEmpty());
    }
}
