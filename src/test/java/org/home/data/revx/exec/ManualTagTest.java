package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * МЕТКА РУЧНОЙ ЗАЯВКИ: её обязаны узнавать одни и не узнавать другие.
 *
 * <h2>Зачем</h2>
 *
 * 15.09.2026 две заявки, поставленные через {@code --revx-order}, висели в
 * книге; аудит их видел, а {@code --revx-panic} ответил «открытых заявок нет» и
 * вышел с успехом (задача A50). Паника фильтрует по метке бота, а ручная заявка
 * шла со случайным UUID — для неё она была чужой.
 *
 * Почему не выдать ей метку бота: тогда её съел бы сам бот. Сверка
 * {@code QuoteLoop.reconcile} со своей меткой либо снимает заявку как хвост,
 * либо усыновляет в слот и двигает заменами, — а {@code --revx-order} ставит
 * заявку ровно затем, чтобы она СТОЯЛА. Отсюда третье состояние: своя, но не
 * бота.
 */
class ManualTagTest {

    @Test
    void manualIdStaysAValidUuid() {
        // Площадка принимает client_order_id только как UUID. Именно поэтому
        // просимый владельцем префикс `zzzzzzzzz-` невозможен: `z` не
        // шестнадцатеричная цифра.
        String id = ManualTag.newClientOrderId();
        assertEquals(36, id.length(), id);
        assertEquals(id, UUID.fromString(id).toString(), "перестал разбираться как UUID: " + id);
    }

    @Test
    void manualIdIsRecognised() {
        assertTrue(ManualTag.is(ManualTag.newClientOrderId()));
    }

    @Test
    void noBotClaimsTheManualOrder() {
        // Иначе котировщик усыновил бы её в слот и начал двигать заменами.
        String id = ManualTag.newClientOrderId();
        for (String bot : new String[]{"a", "b", "c", "d", "e", "f"}) {
            assertFalse(new BotTag(bot).owns(id), "бот " + bot + " считает своей ручную заявку " + id);
        }
    }

    @Test
    void manualTagDoesNotClaimBotOrders() {
        for (String bot : new String[]{"a", "b", "c", "d", "e", "f"}) {
            assertFalse(ManualTag.is(new BotTag(bot).newClientOrderId()),
                    "заявка бота " + bot + " принята за ручную");
        }
    }

    @Test
    void namelessOrderIsNotManual() {
        // Безымянными приходят и заявки, поставленные владельцем в приложении
        // Revolut (легаси-продажа ADA была именно такой). Стоп-кран трогать их
        // не должен, поэтому «не знаем» — это не «ручная».
        assertFalse(ManualTag.is(null));
        assertFalse(ManualTag.is(""));
        assertFalse(ManualTag.is(UUID.randomUUID().toString().replaceFirst("^.", "7")));
    }

    @Test
    void prefixIsHexAndEightLong() {
        assertEquals(8, ManualTag.PREFIX.length(), "первый блок UUID — ровно восемь цифр");
        assertTrue(ManualTag.PREFIX.matches("[0-9a-f]+"), "префикс обязан быть шестнадцатеричным");
    }
}
