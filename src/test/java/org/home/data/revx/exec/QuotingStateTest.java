package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Состояние котирования читается из журнала — им живёт сводный бот.
 *
 * ⚠️ Свойство не косметическое: сводка нужна ровно для того, чтобы не
 * проглядывать шесть ботов по одному. Сводка, которая показывает «торгует» у
 * стоящего бота, хуже отсутствующей — она сообщает, что смотреть не надо.
 *
 * Живой случай 07.09.2026: после выкатки последний start остался в 15:04, за ним
 * прошли два boot, и сводный бот показал шесть зелёных строк при шести стоящих
 * ботах. Перезапуск гасит котирование МОЛЧА — stop пишет только команда
 * человека.
 */
class QuotingStateTest {

    private ExecJournal journal(Path dir) {
        return new ExecJournal(dir.resolve("exec.db").toString());
    }

    @Test
    void свежийЖурналЗначитВыключено(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            assertFalse(j.quotingOn(), "без событий котирование включённым быть не может");
        }
    }

    @Test
    void startВключает(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            j.event("boot", "запуск");
            j.event("start", "котирование включено");
            assertTrue(j.quotingOn());
        }
    }

    @Test
    void stopВыключает(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            j.event("start", "котирование включено");
            j.event("stop", "котирование выключено");
            assertFalse(j.quotingOn(), "остановленный командой бот не торгует");
        }
    }

    @Test
    void bootПослеStartВыключает(@TempDir Path dir) {
        // Ровно живой случай: человек включил, потом была выкатка.
        try (ExecJournal j = journal(dir)) {
            j.event("boot", "первый запуск");
            j.event("start", "котирование включено");
            j.event("boot", "перезапуск после выкатки");
            assertFalse(j.quotingOn(),
                    "перезапуск гасит котирование молча — сводка обязана это видеть");
        }
    }

    @Test
    void startПослеBootСноваВключает(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            j.event("start", "котирование включено");
            j.event("boot", "перезапуск");
            j.event("start", "человек включил снова");
            assertTrue(j.quotingOn());
        }
    }

    @Test
    void постороннееСобытиеСостоянияНеМеняет(@TempDir Path dir) {
        // В журнале десятки видов событий; состояние определяют только три.
        try (ExecJournal j = journal(dir)) {
            j.event("start", "котирование включено");
            j.event("park_move", "заявка отведена");
            j.event("budget_denied", "бюджет исчерпан");
            j.event("adopt", "усыновил заявку");
            assertTrue(j.quotingOn(), "парковка и бюджет к состоянию котирования не относятся");
        }
    }
}
