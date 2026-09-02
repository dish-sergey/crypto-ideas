package org.home.data.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Разовый импорт исторического фондирования по ВСЕЙ вселенной перпов Kraken.
 *
 * <b>Зачем.</b> Порог фондирования S5 (−1.5%) откалиброван на Binance с его
 * восьмичасовой ставкой, а применяется к Kraken с часовой — перенос без
 * проверки (док. 130 §III п.6). Проверить его нельзя было не из-за метода, а
 * из-за данных: {@code collectors.kraken-symbols} с самого начала перечисляет
 * **два** символа, PF_XBTUSD и PF_ETHUSD, тогда как торгуемая вселенная S5 —
 * 274 перпа. Персистентность часовой ставки по двум монетам не измеряется.
 *
 * <b>Почему это отдельный импортёр, а не расширение коллектора.</b> Расписание
 * коллектора рассчитано на постоянную работу и добирает свежие часы (док. 09
 * §5); тянуть по 274 символа каждый час незачем — ставка меняется раз в час, а
 * история за год приходит одним ответом. Здесь разовая операция: пройти всю
 * вселенную, дописать всё, чего нет, и выйти. Дальше свежие часы по
 * интересующим символам добирает обычный коллектор.
 *
 * <b>Идемпотентность</b> та же, что у коллектора (принцип 2): записи идут через
 * {@code INSERT OR REPLACE} по естественному ключу, а точки старше уже
 * известного максимума пропускаются. Повторный запуск не создаёт дублей и
 * ничего не портит.
 *
 * ⚠️ Эндпоинт — **API v4**: v3 на этот путь отвечает 404 (док. 09 §2.9). Один
 * ответ содержит около года часовых ставок, поэтому пагинация не нужна, но и
 * глубже года так не уйти — это предел бесплатного источника.
 */
@Component
public class KrakenFundingBackfill {

    private static final Logger log = LoggerFactory.getLogger(KrakenFundingBackfill.class);

    private static final String INSTRUMENTS =
            "https://futures.kraken.com/derivatives/api/v3/instruments";
    private static final String FUNDING =
            "https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol=";

    private static final String UPSERT = """
            INSERT OR REPLACE INTO kraken_funding(symbol, funding_time, rate, rel_rate, available_at)
            VALUES(?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public KrakenFundingBackfill(Db db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    public void run() {
        List<String> symbols = perpetuals();
        if (symbols.isEmpty()) {
            log.error("вселенная перпов пуста — instruments недоступны, импорт отменён");
            return;
        }
        log.info("бэкфилл фондирования Kraken: {} перпов", symbols.size());

        int done = 0;
        int written = 0;
        int empty = 0;
        int failed = 0;
        for (String symbol : symbols) {
            try {
                int n = backfill(symbol);
                written += n;
                if (n == 0) {
                    empty++;
                }
            } catch (RuntimeException e) {
                // Изоляция сбоев (принцип 3): один символ не валит остальные 273.
                failed++;
                log.warn("фондирование {}: {}", symbol, e.toString());
            }
            if (++done % 25 == 0) {
                log.info("  {} из {}, записей {}", done, symbols.size(), written);
            }
        }
        log.info("бэкфилл готов: {} символов, {} часовых ставок записано, "
                + "без новых данных {}, с ошибкой {}", symbols.size(), written, empty, failed);
    }

    /**
     * Линейные бессрочные перпы: {@code PF_<BASE>USD}.
     *
     * Берётся именно {@code instruments}, а не {@code tickers}: там есть и
     * делистнутые инструменты, у которых история фондирования всё равно нужна —
     * иначе выборка станет survivorship-free только на бумаге.
     */
    private List<String> perpetuals() {
        TreeSet<String> out = new TreeSet<>();
        try {
            JsonNode root = mapper.readTree(api.get(INSTRUMENTS));
            for (JsonNode node : root.path("instruments")) {
                String symbol = node.path("symbol").asText("").toUpperCase(java.util.Locale.ROOT);
                if (symbol.startsWith("PF_") && symbol.endsWith("USD")) {
                    out.add(symbol);
                }
            }
        } catch (Exception e) {
            log.error("instruments Kraken: {}", e.toString());
        }
        return List.copyOf(out);
    }

    /** Дописывает всё, чего нет; возвращает число новых часовых ставок. */
    private int backfill(String symbol) {
        JsonNode rates = readTree(FUNDING + symbol).path("rates");
        if (!rates.isArray() || rates.isEmpty()) {
            return 0;
        }
        Long newest = db.queryLong(
                "SELECT MAX(funding_time) FROM kraken_funding WHERE symbol=?", symbol);
        long floor = newest != null ? newest : Long.MIN_VALUE;
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode n : rates) {
            long ts = parseTs(n.get("timestamp"));
            if (ts <= floor) {
                continue;
            }
            rows.add(new Object[]{symbol, ts, n.path("fundingRate").asDouble(),
                    n.path("relativeFundingRate").asDouble(), ts});
        }
        return rows.isEmpty() ? 0 : db.batch(UPSERT, rows);
    }

    /** Метка времени приходит то строкой ISO, то числом в секундах или мс. */
    private static long parseTs(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isTextual()) {
            return Instant.parse(node.asText()).toEpochMilli();
        }
        long v = node.asLong();
        return v < 1_000_000_000_000L ? v * 1000 : v;
    }

    private JsonNode readTree(String url) {
        try {
            return mapper.readTree(api.get(url));
        } catch (java.io.IOException e) {
            throw new ApiClient.ApiException("Некорректный JSON от " + url, e);
        }
    }
}
