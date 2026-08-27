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
     * Поверхность API, НАЙДЕННАЯ перебором 27.08.2026 (ответы площадки в скобках):
     *
     * <pre>
     * GET    /api/1.0/orders/active   — открытые заявки (200, {"data":[],...})
     * GET    /api/1.0/orders/{id}     — одна заявка; на /orders/open площадка
     *                                   ответила «Invalid order ID: 'open'», чем
     *                                   и выдала форму пути
     * GET    /api/1.0/balances        — остатки по валютам (200)
     * POST   /api/1.0/orders          — постановка (по документации, 10/с + 1000/сутки)
     * PUT    /api/1.0/orders/{id}     — замена (10/с, без суточного потолка)
     * DELETE /api/1.0/orders/{id}     — отмена (100/с)
     * </pre>
     *
     * Остальные кандидаты (`/balance`, `/accounts`, `/account`, `/wallet`,
     * `/trades/my`, а также GET на `/orders`) дали 401 — их нет.
     */
    private static final List<String> CANDIDATES = List.of(
            "/api/1.0/orders/active",
            "/api/1.0/balances");

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
                        path.endsWith("/balances")
                                ? nonZeroBalances(response.body())
                                : summarize(response.body())));
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

    /**
     * Из списка остатков показываются только НЕНУЛЕВЫЕ. Площадка возвращает все
     * валюты подряд, и в логе это сотня строк с нулями, среди которых не видно
     * того единственного, ради чего смотрели: есть ли на счёте обе валюты пары.
     * Котировать две стороны без обеих нельзя — постановка будет отклонена.
     */
    private static String nonZeroBalances(String body) {
        if (body == null || body.isBlank()) {
            return "(пусто)";
        }
        var matcher = java.util.regex.Pattern
                .compile("\\{\"currency\":\"([A-Z0-9]+)\"[^}]*?\"total\":\"([0-9.]+)\"")
                .matcher(body);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (Double.parseDouble(matcher.group(2)) > 0) {
                out.append(out.isEmpty() ? "" : ", ")
                        .append(matcher.group(1)).append(' ').append(matcher.group(2));
            }
        }
        return out.isEmpty() ? "все остатки нулевые — торговать нечем" : out.toString();
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
