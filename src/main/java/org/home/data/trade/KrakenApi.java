package org.home.data.trade;

/**
 * Низкоуровневый доступ к Kraken Futures REST. За этим интерфейсом прячется HTTP+подпись
 * ({@link KrakenFuturesClient}); в тестах — фейк с готовыми JSON, поэтому разбор ответов в
 * {@link KrakenFuturesExchange}/{@link KrakenFundingSource} проверяется без сети и ключей.
 */
public interface KrakenApi {
    /** GET path. signed=true — с подписью (openpositions/accounts); false — публично (tickers). */
    String get(String path, boolean signed) throws Exception;
    /** POST path с form-urlencoded телом (всегда с подписью): sendorder. */
    String post(String path, String formBody) throws Exception;
}
