package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ВОССТАНОВЛЕНИЕ ПРИ СТАРТЕ: хвосты цепочек заявок и память об учтённом.
 *
 * ⚠️ Дыра, ради которой это написано, стоила двух потерянных исполнений за сутки
 * (13–14.09.2026). Бот узнаёт о сделке единственным способом: заметив, что его
 * заявки не стало, и спросив о ней площадку. Пока процесса нет, замечать некому
 * — заявка, исполнившаяся за тринадцать секунд остановки на выкатку, не попадает
 * ни в книгу, ни в журнал.
 *
 * Восстановление берёт идентификаторы из СВОЕГО ЖЕ журнала. Замена создаёт новую
 * заявку, цепочка связана полем {@code previous_order_id}, и спрашивать надо
 * только ХВОСТЫ: промежуточные звенья заведомо заменены.
 */
class OrderTailsTest {

    /** Часы, которыми тест расставляет отметки времени записям журнала. */
    private static final class Hand implements Clock {
        long ms = System.currentTimeMillis();

        @Override
        public long now() {
            return ms;
        }

        @Override
        public void sleep(long ignored) {
            // в тесте ждать нечего
        }
    }

    private final Hand hand = new Hand();

    private ExecJournal journal(Path dir) {
        ExecJournal j = new ExecJournal(dir.resolve("exec.db").toString());
        j.clock(hand);
        return j;
    }

    /** Записать ответ площадки с нужной отметкой времени. */
    private void put(ExecJournal j, long ts, String method, int status, String response) {
        hand.ms = ts;
        j.request(method, "/api/1.0/orders", "", status, response, 5, null);
    }

    /** Ответ площадки на постановку или замену. */
    private static String created(String id, String previous) {
        return previous == null
                ? "{\"data\":{\"venue_order_id\":\"" + id + "\",\"status\":\"new\"}}"
                : "{\"data\":{\"venue_order_id\":\"" + id + "\",\"previous_order_id\":\""
                        + previous + "\",\"status\":\"new\"}}";
    }

    /**
     * Цепочка из трёх замен: спрашивать надо ТОЛЬКО последний идентификатор.
     * Судьба промежуточных известна — заменены, и лишние вопросы это лишние GET
     * ровно тогда, когда площадка и так режет запросы.
     */
    @Test
    void onlyTheTailOfAChainIsReturned(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            long now = System.currentTimeMillis();
            put(j, now, "POST", 200, created("id1", null));
            put(j, now + 1, "PUT", 200, created("id2", "id1"));
            put(j, now + 2, "PUT", 200, created("id3", "id2"));

            List<String> tails = j.recentOrderTails(now - 1000, 10);

            assertEquals(List.of("id3"), tails, "хвост один: id1 и id2 заменены");
        }
    }

    /** Две стороны — два хвоста, и оба надо спросить. */
    @Test
    void bothSidesGiveTheirOwnTail(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            long now = System.currentTimeMillis();
            put(j, now, "POST", 200, created("buy1", null));
            put(j, now + 1, "POST", 200, created("sell1", null));
            put(j, now + 2, "PUT", 200, created("buy2", "buy1"));

            List<String> tails = j.recentOrderTails(now - 1000, 10);

            assertEquals(2, tails.size(), tails.toString());
            assertTrue(tails.contains("buy2"), tails.toString());
            assertTrue(tails.contains("sell1"), tails.toString());
            assertFalse(tails.contains("buy1"), "заменённое звено спрашивать незачем");
        }
    }

    /** Старое окно не тянем: вопрос имеет смысл только про недавние заявки. */
    @Test
    void oldOrdersAreOutsideTheWindow(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            long now = System.currentTimeMillis();
            put(j, now - 7_200_000L, "POST", 200, created("старая", null));
            put(j, now, "POST", 200, created("свежая", null));

            assertEquals(List.of("свежая"), j.recentOrderTails(now - 1800_000L, 10));
        }
    }

    /** Неудавшиеся запросы заявок не создают: спрашивать про них нечего. */
    @Test
    void failedRequestsAreIgnored(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            long now = System.currentTimeMillis();
            put(j, now, "PUT", 429, "<!doctype html>слишком часто");
            put(j, now + 1, "POST", 200, created("живая", null));

            assertEquals(List.of("живая"), j.recentOrderTails(now - 1000, 10));
        }
    }

    /**
     * 🔑 ПАМЯТЬ ОБ УЧТЁННОМ ПЕРЕЖИВАЕТ ПЕРЕЗАПУСК.
     *
     * Котировщик пишет РАЗНИЦУ между тем, что площадка называет исполненным, и
     * тем, что уже проведено. Карта живёт в памяти процесса; если после
     * перезапуска переспросить старую заявку, её исполнение запишется второй раз
     * и инвентарь бота сместится навсегда. Восстановление спрашивает старые
     * заявки НАМЕРЕННО, поэтому карта поднимается из журнала.
     */
    @Test
    void bookedQuantitiesSurviveRestart(@TempDir Path dir) {
        try (ExecJournal j = journal(dir)) {
            long now = System.currentTimeMillis();
            j.fill("id1", "BUY", 0.00001023, 77000, 77010, 0, null, "partially_filled");
            j.fill("id1", "BUY", 0.00002742, 77000, 77010, 0, null, "filled");
            j.fill("id2", "SELL", 0.00003765, 77100, 77090, 0, null, "filled");
            j.fill(null, "BUY", 0.00003765, 77000, 77000, 0, null, "handover");

            Map<String, Double> booked = j.bookedByOrder();

            assertEquals(0.00003765, booked.get("id1"), 1e-12, "части одной заявки складываются");
            assertEquals(0.00003765, booked.get("id2"), 1e-12);
            assertEquals(2, booked.size(), "передача заявкой не является: " + booked);
            assertFalse(booked.containsKey(null));
        }
    }
}
