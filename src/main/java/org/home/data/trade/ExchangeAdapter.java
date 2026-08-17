package org.home.data.trade;

import java.util.List;

/**
 * Абстракция исполнения (doc 57 §6): одна и та же логика поверх мока и прода.
 * Прод — смена реализации, переключение base URL (одна константа), для микро-live по протоколу 54.
 * S5 торгует только шорт перпа; открытие/закрытие — рыночным ордером (reduce-only на закрытии).
 *
 * <p>Договор о сбоях (проверяется на {@link MockExchange} в фазе 0, §4.2):
 * <ul>
 *   <li>разрыв соединения → метод бросает {@link ExchangeDisconnectedException};
 *   <li>отклонение ордера → {@link OrderResult} со статусом REJECTED (не исключение);
 *   <li>{@link #positions()} — источник истины: состояние восстанавливается с биржи, не из памяти.
 * </ul>
 */
public interface ExchangeAdapter {

    /** Текущая mark-цена инструмента. */
    double mark(String symbol) throws ExchangeDisconnectedException;

    /** Открыть шорт рыночным ордером. */
    OrderResult openShort(String symbol, double qty) throws ExchangeDisconnectedException;

    /** Закрыть шорт (reduce-only рыночный ордер на выкуп). Идемпотентно по смыслу: если позиции нет — REJECTED. */
    OrderResult closeShort(String symbol, double qty) throws ExchangeDisconnectedException;

    /** Все открытые позиции — источник истины для восстановления состояния. */
    List<Position> positions() throws ExchangeDisconnectedException;

    /** Баланс счёта (для лимитов сайзинга). */
    double balance() throws ExchangeDisconnectedException;

    /**
     * Минимальный размер ордера в базовой валюте (он же шаг лота). 0 — ограничения нет/неизвестно.
     * У Kraken PF-перпов = 10^(−contractValueTradePrecision). Нужно, чтобы микро-позиция не отклонялась
     * биржей и чтобы показать оператору, сколько должно быть на счёте для валидного входа.
     */
    default double minOrderSize(String symbol) throws ExchangeDisconnectedException { return 0.0; }
}
