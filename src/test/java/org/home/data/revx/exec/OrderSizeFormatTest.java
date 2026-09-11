package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Размер заявки обязан ложиться в шаг пары, а не в двоичную арифметику.
 *
 * <h2>Зачем тест</h2>
 *
 * ⚠️ 11.09.2026 опытный бот d не мог продать ВООБЩЕ: три лота ETH по 0.00040265
 * в сумме дают 0.0012079499999999999, {@code BigDecimal.valueOf} печатает все
 * девятнадцать знаков, и площадка отвечает
 * {@code 400 base_size precision must not exceed 8 decimal places}.
 *
 * За пятнадцать минут — тридцать таких отказов при двух удачных постановках.
 * Снаружи это выглядело как нехватка лимитов запросов: бот молотил вхолостую и
 * жёг общий бюджет, а настоящая причина была в одной строке форматирования.
 */
class OrderSizeFormatTest {

    /** Та же арифметика, что в {@code QuoteLoop.fmtSize}. */
    private static String fmtSize(double size, double baseStep) {
        BigDecimal step = BigDecimal.valueOf(baseStep);
        return BigDecimal.valueOf(size)
                .divide(step, 0, RoundingMode.DOWN)
                .multiply(step)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static int decimals(String s) {
        int dot = s.indexOf('.');
        return dot < 0 ? 0 : s.length() - dot - 1;
    }

    @Test
    void computedSizeNeverLeaksExcessPrecision() {
        // ⚠️ Проверяется КОНТРАКТ, а не заранее угаданное число: какая именно
        // арифметика даёт лишние знаки, зависит от пути (скос, доля лота,
        // остаток после частичного исполнения), и подбирать «то самое» значение
        // бессмысленно. Важно одно: до площадки не должно доходить больше
        // знаков, чем она принимает.
        double[] dirty = {
                0.00040265 * 7 / 3.0,          // доля потолка
                0.00040265 * 1.3333333333,     // размер со скосом
                0.001 / 3.0,
                0.00003765 * 2.7,
        };
        for (double v : dirty) {
            String sent = fmtSize(v, 1e-8);
            assertTrue(decimals(sent) <= 8,
                    "у " + v + " вышло " + sent + " — больше восьми знаков");
        }
    }

    @Test
    void roundsDownNeverUp() {
        // Продать больше, чем есть, нельзя: округление только вниз.
        assertEquals("0.001207", fmtSize(0.00120799, 1e-6));
        assertEquals("0.00120799", fmtSize(0.001207999, 1e-8));
    }

    @Test
    void coarseStepPairIsQuantisedToo() {
        // У SOL шаг 1e-6: дробь мельче него до площадки доходить не должна.
        assertEquals("0.00944", fmtSize(0.009440000000000001, 1e-6));
        assertTrue(decimals(fmtSize(0.00944123456, 1e-6)) <= 6);
    }

    @Test
    void wholeLotsSurviveUnchanged() {
        assertEquals("0.00003765", fmtSize(0.00003765, 1e-8));
        assertEquals("0.02832", fmtSize(0.02832, 1e-6));
    }
}
