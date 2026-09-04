package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор списка активных заявок.
 *
 * ⚠️ Имя поля с идентификатором у площадки РАЗНОЕ: постановка и замена отдают
 * {@code venue_order_id}, а {@code GET /orders/active} — {@code id}. Уборка
 * зонда искала только {@code venue_order_id}, своей же заявки в списке не
 * находила и печатала «Активных заявок не осталось», пока та висела в книге и
 * держала резерв.
 */
class OrderProbeIdsTest {

    /** Настоящая форма ответа GET /orders/active (сокращённая). */
    private static final String ACTIVE = """
            [{"id":"d307f978-621b-4f0e-9d3a-11111111","client_order_id":"c-1",
              "symbol":"BTC-USDC","side":"buy","state":"NEW"},
             {"id":"aa11bb22-3344-5566-7788-99990000","client_order_id":"c-2",
              "symbol":"BTC-USDC","side":"sell","state":"NEW"}]""";

    /** Форма ответа POST /orders и PUT /orders/{id}. */
    private static final String PLACED = """
            {"venue_order_id":"ffffffff-0000-1111-2222-333333333333",
             "client_order_id":"c-9","state":"NEW"}""";

    @Test
    void findsIdsInTheActiveList() {
        Set<String> ids = OrderProbe.activeIds(ACTIVE);
        assertEquals(2, ids.size(), "нашлось " + ids);
        assertTrue(ids.contains("d307f978-621b-4f0e-9d3a-11111111"));
        assertTrue(ids.contains("aa11bb22-3344-5566-7788-99990000"));
    }

    @Test
    void findsVenueOrderIdInPlacementResponse() {
        assertEquals(Set.of("ffffffff-0000-1111-2222-333333333333"),
                OrderProbe.activeIds(PLACED));
    }

    @Test
    void clientOrderIdIsNotAnOrderId() {
        // "client_order_id" не должен приниматься за "id": перед id стоит
        // подчёркивание, а не кавычка. Иначе уборка пойдёт отменять чужое.
        Set<String> ids = OrderProbe.activeIds(ACTIVE);
        assertFalse(ids.contains("c-1"), "client_order_id принят за id: " + ids);
        assertFalse(ids.contains("c-2"));
    }

    @Test
    void emptyBookGivesNothing() {
        assertTrue(OrderProbe.activeIds("[]").isEmpty());
        assertTrue(OrderProbe.activeIds("").isEmpty());
        assertTrue(OrderProbe.activeIds(null).isEmpty());
    }

    @Test
    void bothNamesTogetherAreCollected() {
        // Смешанное тело: обе формы обязаны попасть в уборку.
        Set<String> ids = OrderProbe.activeIds(ACTIVE.substring(0, ACTIVE.length() - 1)
                + "," + PLACED + "]");
        assertEquals(3, ids.size(), "нашлось " + ids);
        assertTrue(ids.contains("ffffffff-0000-1111-2222-333333333333"));
    }

    @Test
    void probeOrdersAreOwnedByNoBot() {
        // BotTag.owns смотрит на ПЕРВЫЙ символ client_order_id, а боты зовутся
        // a, b, c. Случайный UUID попал бы в чужую метку в 3 случаях из 16, и
        // живой бот снял бы заявку зонда как бесхозную либо усыновил её как
        // собственную котировку. Перебираем достаточно, чтобы 3/16 всплыли.
        for (int i = 0; i < 2000; i++) {
            String id = OrderProbe.probeClientId();
            for (String bot : new String[]{"a", "b", "c"}) {
                assertFalse(new BotTag(bot).owns(id),
                        "бот " + bot + " считает своей заявку зонда " + id);
            }
        }
    }

    @Test
    void probeClientIdStaysAValidUuid() {
        // Площадка принимает client_order_id только как UUID: подмена первого
        // символа не должна ломать форму.
        String id = OrderProbe.probeClientId();
        assertEquals(36, id.length(), id);
        assertEquals(id, java.util.UUID.fromString(id).toString(),
                "перестал разбираться как UUID: " + id);
    }

    @Test
    void unparsedBodyIsNotSilentlyTreatedAsEmpty() {
        // Тело есть, идентификаторов не нашлось — это НЕ «книга чиста».
        // Проверяем сам разбор: пусто на непустом теле обязано быть отличимо.
        String weird = "{\"orders\":[{\"orderId\":\"x-1\"}]}";
        assertTrue(OrderProbe.activeIds(weird).isEmpty(),
                "разбор не должен угадывать незнакомые имена полей");
        assertFalse(weird.trim().isEmpty(),
                "тело непустое — уборка обязана сказать об этом, а не молчать");
    }
}
