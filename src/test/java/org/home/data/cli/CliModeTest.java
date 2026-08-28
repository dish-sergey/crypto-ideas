package org.home.data.cli;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор аргументов, который дважды ошибался в РАЗНЫЕ стороны.
 *
 * Сначала он был слишком мягким: неизвестная опция проваливалась в режим
 * планировщика, и на торговом сервере вместо аварийной отмены заявок поднялся
 * весь слой данных. Потом слишком строгим: параметры команды (`--symbol`,
 * `--hours`) считались неизвестными опциями и заворачивали законный прогон.
 *
 * Здесь закреплены обе границы сразу.
 */
class CliModeTest {

    private static CliMode of(String... args) {
        return new CliMode(new DefaultApplicationArguments(args));
    }

    @Test
    void noArgumentsMeansScheduler() {
        CliMode mode = of();
        assertTrue(mode.isScheduleMode(), "без аргументов работает планировщик");
        assertTrue(mode.unknownOptions().isEmpty());
    }

    @Test
    void commandWithItsParametersIsAccepted() {
        CliMode mode = of("--revx-sim", "--symbol=BTC/USDC", "--hours=14",
                "--to=2026-08-28T07:40:00Z", "--out=reports/x.md");

        assertTrue(mode.unknownOptions().isEmpty(),
                "параметры команды не могут быть неизвестными опциями: " + mode.unknownOptions());
        assertFalse(mode.isScheduleMode(), "команда задана — планировщик не запускается");
    }

    @Test
    void springPropertyOverrideIsNotAnUnknownOption() {
        CliMode mode = of("--revx-sim", "--revx.db-path=data/revx-win.db");
        assertTrue(mode.unknownOptions().isEmpty(), "точечные свойства принадлежат Spring");
    }

    @Test
    void unknownOptionNeverFallsThroughToScheduler() {
        CliMode mode = of("--revx-panik");            // опечатка в имени команды

        assertEquals(java.util.Set.of("revx-panik"), mode.unknownOptions());
        assertFalse(mode.isScheduleMode(),
                "опечатка обязана останавливать запуск, а не поднимать слой данных");
    }

    @Test
    void parametersWithoutCommandAreAnErrorToo() {
        // Опечатка в имени команды может оставить одни параметры — и раньше это
        // выглядело как «аргументов нет», то есть как запуск планировщика.
        CliMode mode = of("--symbol=BTC/USDC", "--hours=14");

        assertEquals(java.util.Set.of("symbol", "hours"), mode.unknownOptions());
        assertFalse(mode.isScheduleMode(), "параметры без команды — не повод запускать планировщик");
    }
}
