package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * РЕГРЕССИЯ ЯДРА (П2.4): достаёт заложенные коэффициенты и честно жалуется на
 * вырожденную выборку.
 *
 * <h2>Зачем этот тест</h2>
 *
 * У прибора два способа соврать, и оба тихие. Первый — арифметика МНК: ошибку в
 * обращении матрицы не видно, отчёт всё равно печатает правдоподобные числа.
 * Второй, и он опаснее: на ОДНОМ падающем окне {@code |Δp| = −Δp}, регрессоры
 * вырождены, и свободный член берётся из ничего — ровно та ловушка, из-за
 * которой 165 требует гонять только объединённо. Здесь оба случая построены с
 * известным ответом.
 */
class CoreRegressionTest {

    /** Часовые строки с заданными {@code a}, {@code b}, {@code g}. */
    private static Path rows(Path dir, String name, double a, double b, double g,
                             boolean onlyFalling) throws IOException {
        StringBuilder sb = new StringBuilder("бот;пара;час;без_хеджа;с_хеджем;опора0;опора1\n");
        Random rnd = new Random(42);
        for (int h = 0; h < 200; h++) {
            // Ход часа: при onlyFalling все часы падающие — регрессоры сливаются.
            double move = onlyFalling ? -Math.abs(rnd.nextGaussian()) * 0.3
                    : rnd.nextGaussian() * 0.3;
            double y = a + b * move + g * Math.abs(move);
            double p0 = 100.0;
            double p1 = p0 * (1 + move / 100.0);
            sb.append(String.format(Locale.ROOT, "a;BTC;%d;%.8f;%.8f;%.8f;%.8f%n",
                    h, y, y, p0, p1));
        }
        Path p = dir.resolve(name);
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void достаётКоэффициентыНаДвухВетвях(@TempDir Path dir) throws IOException {
        Path in = rows(dir, "hours.csv", 0.006, 0.02, -0.01, false);
        Path out = dir.resolve("core.md");
        new CoreRegression().run("оба=" + in, out.toString());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        // Ряд построен без шума, значит МНК обязан вернуть вход в точности.
        assertTrue(text.contains("+0.0060"), "ядро a должно быть +0.0060:\n" + text);
        assertTrue(text.contains("+0.0200"), "бета b должна быть +0.0200:\n" + text);
        assertTrue(text.contains("-0.0100"), "вогнутость g должна быть −0.0100:\n" + text);
        // VIF при симметричном ходе близок к единице: ветви разделены.
        assertTrue(text.contains("| 1.0 |") || text.contains("| 1.1 |"),
                "VIF на симметричном окне должен быть около 1:\n" + text);
    }

    @Test
    void наОдномПадающемОкнеВетвиНеРазделены(@TempDir Path dir) throws IOException {
        Path in = rows(dir, "fall.csv", 0.006, 0.02, -0.01, true);
        Path out = dir.resolve("core2.md");
        new CoreRegression().run("падающее=" + in, out.toString());

        String text = Files.readString(out, StandardCharsets.UTF_8);
        // 🔑 Главное свойство: прибор обязан ПОКАЗАТЬ вырождение, а не спрятать его.
        double vif = vifFromReport(text);
        assertTrue(vif > 5, "на падающем окне VIF обязан быть велик, а вышел " + vif + ":\n" + text);
    }

    /** Последняя колонка строки «без хеджа» — VIF. */
    private static double vifFromReport(String text) {
        for (String line : text.split("\n")) {
            if (line.contains("без хеджа") && line.startsWith("|")) {
                String[] cells = line.split("\\|");
                return Double.parseDouble(cells[cells.length - 1].trim());
            }
        }
        return assertFail(text);
    }

    private static double assertFail(String text) {
        assertEquals("", text, "строки «без хеджа» в отчёте нет");
        return 0;
    }
}
