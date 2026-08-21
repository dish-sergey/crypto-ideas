package org.home.data.revx;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Выбор эндпоинтов: с ключом идём по авторизованным (100 req/s, 1000/мин),
 * без ключа — по публичным (1 req/s). Пути у них разные, поэтому решение
 * собрано в одном месте, а не размазано по коллекторам.
 *
 * Формат ответов совпадает, так что парсеры общие.
 */
@Component
@Lazy
public class RevxEndpoints {

    private final RevxConfig cfg;
    private final RevxAuth auth;

    public RevxEndpoints(RevxConfig cfg, RevxAuth auth) {
        this.cfg = cfg;
        this.auth = auth;
    }

    public boolean authenticated() {
        return auth.enabled();
    }

    public String pairs() {
        return auth.enabled()
                ? cfg.baseUrl() + "/api/1.0/configuration/pairs?region=" + cfg.region()
                : cfg.pairsUrl();
    }

    public String book(String pathSymbol) {
        return auth.enabled()
                ? cfg.baseUrl() + "/api/1.0/order-book/" + pathSymbol
                        + "?limit=" + cfg.bookDepth() + "&region=" + cfg.region()
                : cfg.bookUrl(pathSymbol);
    }

    /**
     * Лента сделок. Публично символ — параметр запроса, а с ключом он часть ПУТИ:
     * `/api/1.0/trades/all/{symbol}`. Тот же адрес с symbol в параметрах отвечает
     * 401 «Unauthenticated access» — маршрута просто нет, и ошибка выглядит как
     * проблема с подписью, хотя дело в пути (проверено 19.08.2026).
     */
    public String trades(String pathSymbol, String cursor) {
        if (!auth.enabled()) {
            return cfg.tradesUrl(pathSymbol, cursor);
        }
        String url = cfg.baseUrl() + "/api/1.0/trades/all/" + pathSymbol
                + "?limit=" + cfg.tradesPageLimit();
        return cursor == null || cursor.isBlank() ? url : url + "&cursor=" + cursor;
    }

    /** Темп: с ключом лимит на два порядка выше, и залпы снова разрешены. */
    public double maxRequestsPerSecond() {
        return auth.enabled() ? cfg.authMaxRequestsPerSecond() : cfg.maxRequestsPerSecond();
    }

    public int burstCapacity() {
        return auth.enabled() ? cfg.authBurstCapacity() : cfg.burstCapacity();
    }

    /**
     * Порог skew. С ключом ноги уходят залпом и расходятся на миллисекунды —
     * держим требование ТЗ §3.3 (250 мс). Без ключа минимальный интервал между
     * запросами держит сама площадка, и порог 250 мс помечал бы вообще всё.
     */
    public long skewThresholdMs() {
        return auth.enabled() ? cfg.authSkewThresholdMs() : cfg.skewThresholdMs();
    }

    /**
     * Ярусы нужны только на публичном пути, где бюджет 0.8 req/s. С ключом
     * (9 req/s) вся вселенная опрашивается одним ярусом раз в 5 секунд —
     * ровно как требует ТЗ §3.4, и симуляция становится честной по всем парам.
     */
    public boolean singleTier() {
        return auth.enabled();
    }

    /**
     * Воркеров столько, чтобы перекрыть задержку сети: запрос идёт ~200 мс,
     * и последовательный обход 23 пар не влезал в 5-секундный период.
     */
    public int workers() {
        return auth.enabled() ? cfg.authWorkers() : cfg.workers();
    }

    public int bookPeriodSeconds() {
        return cfg.authBookPeriodSeconds();
    }

    public int tradesPeriodSeconds() {
        return cfg.authTradesPeriodSeconds();
    }
}
