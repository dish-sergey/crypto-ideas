package org.home.data.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Read-only проверка реального ключа Kraken Futures: подпись работает (приватные эндпоинты отвечают success),
 * баланс/позиции/марка парсятся верно. НИКАКИХ ордеров. Печатает и разобранные значения, и сырой JSON
 * accounts/openpositions — чтобы сверить структуру перед подключением live. Ключи в вывод не попадают.
 */
public class S5KrakenCheck {

    private static final Logger log = LoggerFactory.getLogger(S5KrakenCheck.class);

    public static void run(String configPath) throws Exception {
        KrakenConfig cfg = KrakenConfig.load(configPath);
        KrakenApi api = new KrakenFuturesClient(cfg.apiKey(), cfg.apiSecretB64());
        KrakenFuturesExchange ex = new KrakenFuturesExchange(api);

        log.info("=== Kraken read-only проверка (ордеров НЕ будет) ===");

        // 1) публичный tickers → марка (без подписи)
        double mark = ex.mark("PF_XBTUSD");
        log.info("[1] mark PF_XBTUSD = {}", mark);

        // 2) приватный accounts → подпись + структура баланса
        log.info("[2] raw /api/v3/accounts:\n{}", api.get("/api/v3/accounts", true));
        log.info("[2] parsed balance (flex.portfolioValue) = {}", ex.balance());

        // 3) приватный openpositions → подпись + форма позиций
        log.info("[3] raw /api/v3/openpositions:\n{}", api.get("/api/v3/openpositions", true));
        List<Position> ps = ex.positions();
        log.info("[3] parsed positions: {}", ps.size());
        for (Position p : ps) log.info("     {} {} qty={} @ {}", p.symbol(), p.side(), p.qty(), p.entryPx());

        log.info("=== READ-ONLY OK: подпись принята, приватные эндпоинты ответили ===");
    }
}
