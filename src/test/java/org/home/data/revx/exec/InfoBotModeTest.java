package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Сводка показывает режим бота: обычная цель, распродажа перед пределом, только продажа. */
class InfoBotModeTest {

    private static String mode(Path dir, String... events) throws Exception {
        String path = dir.resolve("f.db").toString();
        try (ExecJournal j = new ExecJournal(path)) {
            j.event("boot", "SOL/USDC | {\"skewTarget\":0.3}");
            for (int i = 0; i + 1 < events.length; i += 2) {
                Thread.sleep(2);
                j.event(events[i], events[i + 1]);
            }
        }
        InfoBot bot = new InfoBot("t", 1, List.of(new InfoBot.Watched("f", "SOL/USDC", path)), "нет.db");
        return bot.modeOf("f");
    }

    @Test
    void обычнаяЦель(@TempDir Path dir) throws Exception {
        assertEquals("цель 30%", mode(dir, "start", "котирование включено"));
    }

    @Test
    void распродажаПередПределом(@TempDir Path dir) throws Exception {
        assertEquals("цель 0% — распродажа перед пределом (235 из 250, обычная цель вернётся на 80%)", mode(dir,
                "budget_unwind", "постановок за сутки 235 из 250 — цель скоса 0: продаю охотнее, покупаю реже"));
    }

    /** a, 07.10.2026: «цель 0» в 02:56, потом отказы по экспозиции — сводка обязана показывать ноль. */
    @Test
    void отказыЭкспозицииНеСбрасываютЦель(@TempDir Path dir) throws Exception {
        assertEquals("цель 0% — распродажа перед пределом (90 из 100, обычная цель вернётся на 80%)", mode(dir,
                "budget_unwind", "постановок за сутки 90 из 100 — цель скоса 0: продаю охотнее, покупаю реже",
                "limit_blocked", "экспозиция",
                "limit_blocked", "экспозиция"));
    }

    @Test
    void вернуласьОбычная(@TempDir Path dir) throws Exception {
        assertEquals("цель 30%", mode(dir,
                "budget_unwind", "постановок за сутки 235 из 250 — цель скоса 0: продаю охотнее",
                "budget_unwind", "постановок за сутки 175 из 250 — цель скоса снова обычная"));
    }

    @Test
    void толькоПродажа(@TempDir Path dir) throws Exception {
        assertEquals("ТОЛЬКО ПРОДАЖА до нуля — предел постановок исчерпан",
                mode(dir, "sell_only", "постановок за сутки 250 из 250"));
    }
}
