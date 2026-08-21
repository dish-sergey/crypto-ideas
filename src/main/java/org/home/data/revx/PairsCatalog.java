package org.home.data.revx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Каталог торговых пар (ТЗ §3.4: список USDC-пар берётся из API динамически,
 * не хардкодится; появление и исчезновение пар отслеживается).
 *
 * Замер 19.08.2026: всего 385 пар, из них USDC — 23 (в док. 62 было 20:
 * добавились JUP, XRP, XLM), и USD-аналог есть у каждой. Вселенная стенда =
 * 23 * 2 = 46 символов, отсюда и бюджет запросов.
 */
@Component
@Lazy
public class PairsCatalog {

    private static final Logger log = LoggerFactory.getLogger(PairsCatalog.class);

    private static final String UPSERT_PAIR = """
            INSERT INTO revx_pair(symbol, base, quote, base_step, quote_step,
                                  min_order_size, max_order_size, min_order_size_quote, max_order_size_quote,
                                  status, first_seen_ms, last_seen_ms)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(symbol) DO UPDATE SET
                base_step = excluded.base_step,
                quote_step = excluded.quote_step,
                min_order_size = excluded.min_order_size,
                max_order_size = excluded.max_order_size,
                min_order_size_quote = excluded.min_order_size_quote,
                max_order_size_quote = excluded.max_order_size_quote,
                status = excluded.status,
                last_seen_ms = excluded.last_seen_ms
            """;

    private final RevxConfig cfg;
    private final RevxEndpoints endpoints;
    private final RevxHttp http;
    private final RevxDb db;
    private final AnomalyLog anomalies;
    private final ObjectMapper mapper = new ObjectMapper();

    public PairsCatalog(RevxConfig cfg, RevxEndpoints endpoints, RevxHttp http, RevxDb db,
                        AnomalyLog anomalies) {
        this.cfg = cfg;
        this.endpoints = endpoints;
        this.http = http;
        this.db = db;
        this.anomalies = anomalies;
    }

    /** Пара «нога в USDC + опорная нога в USD» — единица опроса (ТЗ §3.3). */
    public record Leg(String base, PairSpec quoted, PairSpec reference, boolean memecoin) {
    }

    /**
     * Тянет каталог, пишет в revx_pair, логирует появившиеся и пропавшие пары.
     * Возвращает актуальный снимок каталога.
     */
    public Map<String, PairSpec> refresh() {
        RevxHttp.Response response = http.get(endpoints.pairs());
        if (!response.ok()) {
            throw new IllegalStateException("каталог пар недоступен: HTTP " + response.status()
                    + (response.error() == null ? "" : " " + response.error()));
        }
        Map<String, PairSpec> specs = parse(response.body());
        long now = response.recvMs();

        Set<String> knownActive = new HashSet<>(
                db.queryStrings("SELECT symbol FROM revx_pair WHERE status='active'"));

        List<Object[]> rows = new ArrayList<>(specs.size());
        for (PairSpec p : specs.values()) {
            rows.add(new Object[]{p.symbol(), p.base(), p.quote(), p.baseStep(), p.quoteStep(),
                    p.minOrderSize(), p.maxOrderSize(), p.minOrderSizeQuote(), p.maxOrderSizeQuote(),
                    p.status(), now, now});
            // на первом прогоне база пуста — тогда весь каталог не «новые пары», а базовая линия
            if (p.active() && !knownActive.isEmpty() && !knownActive.contains(p.symbol())) {
                anomalies.record("pair_new", p.symbol(), "пара появилась в каталоге");
            }
        }
        db.batch(UPSERT_PAIR, rows);

        for (String known : knownActive) {
            PairSpec current = specs.get(known);
            if (current == null || !current.active()) {
                anomalies.record("pair_gone", known,
                        current == null ? "пара исчезла из каталога" : "статус: " + current.status());
                if (current == null) {
                    db.upsert("UPDATE revx_pair SET status='gone' WHERE symbol=?", known);
                }
            }
        }

        log.info("каталог пар: {} всего, {} активных, из них котируемых в {}: {}", specs.size(),
                specs.values().stream().filter(PairSpec::active).count(), cfg.quoteCurrency(),
                specs.values().stream()
                        .filter(p -> p.active() && cfg.quoteCurrency().equalsIgnoreCase(p.quote()))
                        .count());
        return specs;
    }

    /**
     * Вселенная стенда: активные пары в котируемой валюте (USDC), у которых есть
     * активный аналог в опорной валюте (USD). Пара без опоры выбрасывается —
     * справедливую цену для неё взять неоткуда (ТЗ §4.1).
     */
    public List<Leg> universe(Map<String, PairSpec> specs) {
        return selectUniverse(specs, cfg.quoteCurrency(), cfg.referenceQuote(),
                cfg.memecoins(), cfg.priority(),
                (symbol, detail) -> anomalies.record("no_reference", symbol, detail));
    }

    /** Чистый отбор вселенной — вынесен из бина, чтобы проверяться без Spring. */
    static List<Leg> selectUniverse(Map<String, PairSpec> specs, String quoteCurrency,
                                    String referenceQuote, List<String> memecoinList,
                                    List<String> priority,
                                    java.util.function.BiConsumer<String, String> onMissingReference) {
        Set<String> memecoins = new HashSet<>(memecoinList);
        List<Leg> legs = new ArrayList<>();
        for (PairSpec p : specs.values()) {
            if (!p.active() || !quoteCurrency.equalsIgnoreCase(p.quote())) {
                continue;
            }
            PairSpec reference = specs.get(p.base() + "/" + referenceQuote);
            if (reference == null || !reference.active()) {
                onMissingReference.accept(p.symbol(),
                        "нет активной опорной пары " + p.base() + "/" + referenceQuote);
                continue;
            }
            legs.add(new Leg(p.base(), p, reference, memecoins.contains(p.base())));
        }
        legs.sort(Comparator
                .comparingInt((Leg l) -> {
                    int i = priority.indexOf(l.base());
                    return i < 0 ? Integer.MAX_VALUE : i;
                })
                .thenComparing(Leg::base));
        return legs;
    }

    public List<Leg> universe() {
        return universe(refresh());
    }

    /**
     * Разбор ответа каталога. Формат — СЛОВАРЬ, ключ 'ETH/USDC', а не массив в
     * поле data (в отличие от книги и ленты сделок). Проверено на живом API 19.08.2026.
     */
    public static Map<String, PairSpec> parse(String json) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("некорректный JSON каталога пар", e);
        }
        if (!root.isObject()) {
            throw new IllegalStateException("ожидался объект-словарь пар, пришло: " + root.getNodeType());
        }
        Map<String, PairSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            String symbol = e.getKey();
            JsonNode n = e.getValue();
            int slash = symbol.indexOf('/');
            String base = n.hasNonNull("base") ? n.get("base").asText()
                    : (slash > 0 ? symbol.substring(0, slash) : symbol);
            String quote = n.hasNonNull("quote") ? n.get("quote").asText()
                    : (slash > 0 ? symbol.substring(slash + 1) : "");
            out.put(symbol, new PairSpec(symbol, base, quote,
                    dbl(n, "base_step", 0), dbl(n, "quote_step", 0),
                    nullableDbl(n, "min_order_size"), nullableDbl(n, "max_order_size"),
                    nullableDbl(n, "min_order_size_quote"), nullableDbl(n, "max_order_size_quote"),
                    n.hasNonNull("status") ? n.get("status").asText() : "unknown"));
        }
        return out;
    }

    /** Числа в ответе — строки ("0.00000001"), поэтому парсим через asDouble по тексту. */
    private static double dbl(JsonNode node, String field, double fallback) {
        Double v = nullableDbl(node, field);
        return v == null ? fallback : v;
    }

    private static Double nullableDbl(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return v.isNumber() ? v.asDouble() : Double.parseDouble(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
