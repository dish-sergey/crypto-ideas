package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * {@code --revx-trade-check}: проверка торгового ключа БЕЗ ЕДИНОГО ОРДЕРА.
 *
 * Делает ровно две вещи и обе безопасны:
 *
 * 1. Подтверждает, что ключ, подпись и IP-whitelist работают. Пока это не
 *    проверено, любая ошибка в торговом цикле будет неотличима от «ключ не тот».
 * 2. Разведывает адреса торговых эндпоинтов. Документация даёт лимиты
 *    (`POST /orders` 10/с и 1000/сутки, `PUT /orders/{id}` 10/с), но точные пути
 *    мы не проверяли ни разу, а угадывать путь и сразу слать по нему ордер —
 *    именно тот способ потерять деньги на опечатке.
 *
 * Только GET. Ни одного метода, изменяющего состояние счёта, здесь нет и быть
 * не должно: это диагностика, а не первый шаг торговли.
 */
@Component
@Lazy
public class TradeCheck {

    private static final Logger log = LoggerFactory.getLogger(TradeCheck.class);

    /**
     * Кандидаты. Пути не документированы у нас в проекте, поэтому проверяются
     * перебором: 200 — нашли, 404 — нет такого, 401/403 — есть, но прав не хватает
     * (и это тоже информация: значит адрес верный, а ключ выпущен не с теми правами).
     */
    private static final List<String> CANDIDATES = List.of(
            "/api/1.0/orders",
            "/api/1.0/orders/open",
            "/api/1.0/orders/active",
            "/api/1.0/balances",
            "/api/1.0/balance",
            "/api/1.0/accounts",
            "/api/1.0/account",
            "/api/1.0/wallet",
            "/api/1.0/trades/my");

    private final RevxConfig cfg;

    public TradeCheck(RevxConfig cfg) {
        this.cfg = cfg;
    }

    public void run() {
        TradeAuth auth = TradeAuth.fromEnvironment();
        log.info("торговый ключ загружен ({}), проверяю доступ — ОРДЕРА НЕ ОТПРАВЛЯЮТСЯ",
                auth.keyFingerprint());

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        StringBuilder report = new StringBuilder("\n=== Разведка торговых эндпоинтов ===\n");
        for (String path : CANDIDATES) {
            URI uri = URI.create(cfg.baseUrl() + path);
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .GET();
                auth.headers("GET", uri, "").forEach(request::header);
                HttpResponse<String> response = http.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                report.append(String.format("%-28s %d  %s%n", path, response.statusCode(),
                        summarize(response.body())));
                // Пауза между запросами: у площадки ограничен минимальный интервал,
                // и разведка не должна выглядеть как залп (см. CLAUDE.md).
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                report.append(String.format("%-28s  —  ошибка: %s%n", path, e.getMessage()));
            }
        }
        report.append("""

                Как читать: 200 — адрес есть и ключ пущен; 401/403 — адрес есть,
                но прав не хватает (ключ выпущен не с теми правами или IP не тот);
                404 — такого адреса нет, кандидат отпадает.
                """);
        log.info(report.toString());
    }

    /** Тело урезается: в нём могут быть остатки на счёте, а в журнале это лишнее. */
    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "(пусто)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
