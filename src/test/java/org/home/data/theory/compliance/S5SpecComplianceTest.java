package org.home.data.theory.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сверка живых констант S5 со спецификацией — ТЗ 72 §5.2, §7.3.
 *
 * <p>Тест <b>не решает</b>, кто прав: он делает расхождение видимым в момент
 * появления. Зарегистрированное расхождение фиксирует обе стороны, поэтому
 * «тихо поправить код» или «тихо поправить документ» невозможно — упадёт и в том,
 * и в другом случае.
 */
class S5SpecComplianceTest {

    @Test
    @DisplayName("незарегистрированных расхождений кода со спецификацией нет")
    void noUnregisteredDivergences() {
        List<S5SpecCheck.Row> unregistered = S5SpecCheck.unregisteredDivergences();
        assertTrue(unregistered.isEmpty(), () -> "расхождение кода и спецификации не зарегистрировано в "
                + "s5-spec.tsv:\n" + unregistered.stream()
                .map(r -> String.format("  %s: код %s, спецификация %s (%s); статус %s, ожидалось в коде %s",
                        r.key(), r.codeValue(), r.specValue(), r.specSource(), r.status(), r.expectedCode()))
                .reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    @Test
    @DisplayName("каждая живая константа покрыта строкой реестра со ссылкой на документ")
    void everyConstantIsRegistered() {
        List<S5SpecCheck.Row> rows = S5SpecCheck.rows();
        assertTrue(rows.size() >= 9, "реестр обязан покрывать весь живой путь: " + rows.size());
        for (S5SpecCheck.Row row : rows) {
            assertFalse(row.specSource().isBlank(), row.key() + ": нет ссылки на документ");
            assertFalse(row.codeLocation().isBlank(), row.key() + ": не указано место в коде");
        }
    }

    /**
     * Главная проверка §7.3: механизм обязан ловить расхождение. Демонстрируется на
     * том самом расхождении, ради которого ТЗ и написано, — стоп 30% против 8%.
     */
    @Test
    @DisplayName("стоп: код 30%, спецификация 8% — расхождение зарегистрировано и видно")
    void stopDivergenceIsVisible() {
        S5SpecCheck.Row stop = row("stop_frac");
        assertEquals("known", stop.status(), "расхождение стопа обязано быть зарегистрировано явно");
        assertEquals(0.30, Double.parseDouble(stop.codeValue()), 1e-9, "в коде 30%");
        assertEquals(0.08, Double.parseDouble(stop.specValue()), 1e-9, "в спецификации 8%");
        assertFalse(stop.matches(), "код и спецификация по стопу НЕ совпадают — это и есть открытый вопрос");
        assertTrue(stop.asRegistered(), "расхождение выглядит ровно так, как записано в реестре");
    }

    /**
     * Обратная сторона: если код «починят» под спецификацию, не тронув реестр, тест
     * тоже обязан упасть — иначе решение пройдёт мимо документа.
     */
    @Test
    @DisplayName("механизм срабатывает в обе стороны: изменение любой из сторон ломает регистрацию")
    void registrationBreaksOnEitherSide() {
        S5SpecCheck.Row stop = row("stop_frac");
        S5SpecCheck.Row codeFixed = withCodeValue(stop, stop.specValue());
        assertFalse(codeFixed.asRegistered(), "код подтянули к спецификации → регистрация недействительна");
        S5SpecCheck.Row specChanged = new S5SpecCheck.Row(stop.key(), "0.30", stop.specSource(),
                stop.status(), stop.expectedCode(), stop.note(), stop.codeValue(), stop.codeLocation());
        assertFalse(specChanged.asRegistered(), "спецификацию переписали → регистрация недействительна");
        S5SpecCheck.Row codeDrifted = withCodeValue(stop, "0.25");
        assertFalse(codeDrifted.asRegistered(), "код уехал на третье значение → регистрация недействительна");
    }

    @Test
    @DisplayName("совпадающие константы действительно совпадают: вход, порог разлока, фильтр funding")
    void matchingConstantsMatch() {
        assertTrue(row("entry_lead").matches(), "вход за 5 дней");
        assertTrue(row("min_pct_supply").matches(), "разлок ≥ 3% circ");
        assertTrue(row("expensive_funding_threshold").matches(), "отмена при дорогом шорте");
        assertTrue(row("exit_on_unlock_day").matches(), "плановый выход в день разлока");
    }

    @Test
    @DisplayName("список зарегистрированных расхождений — то, что ждёт решения")
    void registeredDivergencesAreListed() {
        List<S5SpecCheck.Row> known = S5SpecCheck.registeredDivergences();
        assertEquals(4, known.size(), () -> "ожидаются четыре расхождения (размер позиции, число "
                + "одновременных, стоп, фильтр получателей), найдено: " + known.stream()
                .map(S5SpecCheck.Row::key).toList());
        for (S5SpecCheck.Row row : known) {
            assertTrue(row.asRegistered(), row.key() + ": расхождение не соответствует записанному");
            assertFalse(row.note().isBlank(), row.key() + ": не объяснено, чем расхождение вызвано");
        }
    }

    private static S5SpecCheck.Row row(String key) {
        return S5SpecCheck.rows().stream().filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("нет строки реестра " + key));
    }

    private static S5SpecCheck.Row withCodeValue(S5SpecCheck.Row row, String codeValue) {
        return new S5SpecCheck.Row(row.key(), row.specValue(), row.specSource(), row.status(),
                row.expectedCode(), row.note(), codeValue, row.codeLocation());
    }
}
