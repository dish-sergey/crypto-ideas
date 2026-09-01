package org.home.data.revx.exec;

import java.util.UUID;

/**
 * Метка бота в идентификаторе заявки.
 *
 * Два бота на одном аккаунте и одной паре видят в {@code GET /orders/active}
 * заявки друг друга. Сверка, которая снимает «всё чужое на нашем символе»
 * (см. {@link QuoteLoop#reconcile}), без метки снимала бы заявки соседа каждую
 * минуту — это первое, что ломается при параллельном запуске.
 *
 * Отдельного поля для метки у площадки нет: в ответе только идентификаторы,
 * символ, сторона, цены, статус и время. Зато {@code client_order_id} задаём мы
 * сами, и он возвращается в списке активных. Значит заявка может носить имя
 * владельца на себе, и хранить у себя ничего не нужно.
 *
 * Формат обязан остаться валидным UUID — площадка принимает только его, — поэтому
 * метка занимает первый блок:
 *
 * <pre>
 * бот A: aaaaaaaa-xxxx-4xxx-yxxx-xxxxxxxxxxxx
 * бот B: bbbbbbbb-xxxx-4xxx-yxxx-xxxxxxxxxxxx
 * </pre>
 *
 * Остальные 24 шестнадцатеричные цифры — случайные, так что уникальности
 * идентификатора это не вредит: 96 бит энтропии против 122 у полного UUID.
 */
public record BotTag(String id) {

    public BotTag {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("идентификатор бота пуст");
        }
    }

    /**
     * Префикс метки. Берётся первый символ идентификатора; если он не
     * шестнадцатеричный, используется его позиция в алфавите — лишь бы получилась
     * валидная UUID-цифра и разные боты не совпали.
     */
    public String prefix() {
        char c = Character.toLowerCase(id.charAt(0));
        char hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                ? c
                : Character.forDigit(Math.abs(c) % 16, 16);
        return String.valueOf(hex).repeat(8);
    }

    /** Новый идентификатор заявки с меткой этого бота. */
    public String newClientOrderId() {
        String random = UUID.randomUUID().toString();
        return prefix() + random.substring(8);
    }

    /**
     * Наша ли это заявка. {@code null} у клиентского идентификатора означает «не
     * знаем» — и такую заявку трогать НЕЛЬЗЯ: при двух ботах молчаливое
     * присвоение чужого хуже, чем оставленный в книге хвост.
     */
    public boolean owns(String clientOrderId) {
        return clientOrderId != null && clientOrderId.toLowerCase().startsWith(prefix());
    }
}
