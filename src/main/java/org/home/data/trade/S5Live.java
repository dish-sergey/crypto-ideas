package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LIVE-режим S5 — тот же оркестратор, что и dry-run, но против РЕАЛЬНОЙ биржи ({@link KrakenFuturesExchange}
 * + {@link KrakenFundingSource}) вместо мока. Деньги настоящие. Защита та же: ручной Approval Gate (ни один
 * ордер без подтверждения оператора), стоп −30%, фильтр дорогого шорта, лимит экспозиции, проверка средств
 * и минимального ордера. На старте усыновляет открытые позиции с биржи ({@link S5Orchestrator#recover}).
 *
 * <p>Один потребитель Telegram getUpdates: live и dry-run с одним ботом одновременно НЕ запускать (409).
 */
public class S5Live {

    private static final Logger log = LoggerFactory.getLogger(S5Live.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();

    private final String telegramConfig;
    private final String krakenConfig;

    public S5Live(String telegramConfig, String krakenConfig) {
        this.telegramConfig = telegramConfig; this.krakenConfig = krakenConfig;
    }

    private record Ctx(UnlockFeed feed, TelegramNotifier notifier, KrakenFuturesExchange ex,
                       S5Orchestrator orch, TelegramCommandListener listener) {}

    private Ctx build() throws Exception {
        S5TelegramConfig tg = S5TelegramConfig.load(telegramConfig);
        S5Db db = new S5Db("state/s5.db");                          // аудит + персистентность сыгранных
        TelegramTransport tx = new HttpTelegramTransport(tg.token());
        TelegramNotifier notifier = new TelegramNotifier(tx, tg.chatId(), db);

        KrakenConfig kc = KrakenConfig.load(krakenConfig);
        KrakenApi api = new KrakenFuturesClient(kc.apiKey(), kc.apiSecretB64());
        KrakenFuturesExchange ex = new KrakenFuturesExchange(api);
        KrakenFundingSource funding = new KrakenFundingSource(api);

        Map<String, String> gecko = geckoToTicker();
        Set<String> bases = krakenBases();
        UnlockFeed feed = new UnlockFeed(new DefiLlamaEmissionSource(), gecko, bases,
                canonicalByTicker(gecko, bases));
        feed.warnAmbiguous(log);

        ApprovalGate gate = new ApprovalGate();
        TradeJournal j = new TradeJournal();
        StopEngine engine = new StopEngine(ex, j, S5Config.protocol().stopFrac(), 3);
        S5Orchestrator orch = new S5Orchestrator(ex, feed, funding, gate, engine,
                new ScheduleTracker(), new DegradationMonitor(), j, S5Config.protocol(), notifier);
        orch.useTradedStore(db);                                    // сыгранные события переживают рестарт
        orch.useRecorder(db);                                       // аудит входов/выходов
        TelegramCommandListener listener = new TelegramCommandListener(tx, tg.chatId(), orch, db);
        return new Ctx(feed, notifier, ex, orch, listener);
    }

    /** Боевой демон: усыновить позиции, объявить LIVE, слушатель + минутный цикл. Блокирует поток. */
    public void run() throws Exception {
        Ctx c = build();
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        c.orch.recover(today);                                     // усыновить позиции + восстановить расписание выхода
        double bal = safeBalance(c);
        c.notifier.push(Alert.warn("🔴 S5 LIVE запущен — РЕАЛЬНЫЕ ДЕНЬГИ",
                "Kraken Futures, исполнение настоящее. Ордер только после ручного подтверждения (кнопки).\n"
                        + "баланс: $" + String.format(Locale.ROOT, "%.2f", bal)
                        + (bal <= 0 ? " — пополни счёт, пока входы будут отклоняться по нехватке средств" : "")
                        + "\nоткрытых позиций: " + c.orch.openPositions()
                        + "\n\n" + digest(c, today)));
        c.listener.start();
        log.info("S5 LIVE: слушатель запущен, цикл раз в 60с (скан фида — раз в день)");
        long lastScanDay = -1;
        while (true) {
            try {
                long t = LocalDate.now(ZoneOffset.UTC).toEpochDay();
                long nowSec = System.currentTimeMillis() / 1000;
                if (t != lastScanDay) {                            // тяжёлый скан фида — раз в день
                    c.orch.discover(t);
                    c.orch.maintain(t);
                    lastScanDay = t;
                }
                c.orch.pollReminders(nowSec);                      // эскалация напоминаний
                c.orch.executeApproved(t);                         // подтверждённые → реальный ордер в день входа
                c.orch.pollStops();                                // стоп по реальной марке
            } catch (Throwable e) {                                // даже Error не должен остановить торговый цикл
                log.warn("S5 LIVE цикл: {}", e.toString());
            }
            Thread.sleep(60_000);
        }
    }

    private static double safeBalance(Ctx c) {
        try { return c.ex.balance(); } catch (Exception e) { return 0; }
    }

    private String digest(Ctx c, long today) throws Exception {
        List<UnlockEvent> up = c.feed.upcoming(today);
        StringBuilder sb = new StringBuilder("📊 Ближайшие клифф-разлоки ≥3% на Kraken:");
        int shown = 0;
        for (UnlockEvent e : up) {
            if (shown++ >= 10) break;
            sb.append("\n").append(e.krakenSymbol()).append("  через ").append(e.unlockDay() - today)
              .append("д  ").append(e.pctLabel());
        }
        if (up.isEmpty()) sb.append("\n(пусто)");
        int lead = S5Config.protocol().entryLead();
        sb.append("\nвсего впереди: ").append(up.size())
          .append(", в окне входа (≤").append(lead).append("д): ").append(c.feed.enterableWithin(today, lead).size());
        return sb.toString();
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " " + url);
        return M.readTree(r.body());
    }

    private Map<String, String> geckoToTicker() throws Exception {
        Map<String, String> m = new HashMap<>();
        for (JsonNode n : getJson("https://api.coingecko.com/api/v3/coins/list"))
            m.put(n.path("id").asText(), n.path("symbol").asText().toUpperCase());
        log.info("CoinGecko coins: {}", m.size());
        return m;
    }

    /**
     * Тикер → главная по капитализации монета с этим тикером.
     *
     * Нужно, чтобы отличить наш протокол от однофамильца: перп на Kraken
     * торгуется на ГЛАВНУЮ монету с тикером, а не на любую (док. 130 §III п.7).
     *
     * Это первый проход — верхушка рейтинга. Её НЕ хватает: OPN (Opinion) имеет
     * ранг 1185 и сюда не попадает, а именно его S5 и торговал. Спорные тикеры,
     * оставшиеся без ответа, добираются точечным поиском — см.
     * {@link #searchCanonical}.
     *
     * Страницы идут по 250. Первым в карту попадает старший по капитализации:
     * страницы отсортированы по убыванию, и {@code putIfAbsent} сохраняет
     * именно его.
     *
     * ⚠️ Глубина рейтинга — не украшение, а рабочий параметр. На первой тысяче
     * неразрешёнными остались 17 тикеров, и среди них **OPN**, который S5
     * фактически торговал 24.08.2026. Монета вне первой тысячи по
     * капитализации — обычное дело для свежего листинга, а именно свежие и дают
     * разлоки. Две тысячи закрывают вселенную; тикеры, не разрешённые и на этой
     * глубине, честно теряются и попадают в предупреждение при старте.
     *
     * Между страницами пауза: у бесплатного CoinGecko лимит порядка десятков
     * запросов в минуту, и восемь подряд без паузы ловят 429.
     */
    static Map<String, String> canonicalByTicker(java.util.function.Function<String, JsonNode> fetch) {
        Map<String, String> out = new HashMap<>();
        for (int page = 1; page <= 8; page++) {
            if (page > 1) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            JsonNode arr = fetch.apply("https://api.coingecko.com/api/v3/coins/markets"
                    + "?vs_currency=usd&order=market_cap_desc&per_page=250&page=" + page);
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            for (JsonNode n : arr) {
                String symbol = n.path("symbol").asText("").toUpperCase();
                String id = n.path("id").asText("");
                if (!symbol.isEmpty() && !id.isEmpty()) {
                    out.putIfAbsent(symbol, id);
                }
            }
        }
        return out;
    }

    private Map<String, String> canonicalByTicker(Map<String, String> geckoToTicker,
                                                  Set<String> krakenBases) {
        java.util.function.Function<String, JsonNode> fetch = url -> {
            try {
                return getJson(url);
            } catch (Exception e) {
                log.warn("CoinGecko: {}", e.toString());
                return null;
            }
        };
        Map<String, String> m = canonicalByTicker(fetch);
        log.info("главных монет по тикеру из рейтинга: {}", m.size());

        // Добор тем, кого рейтинг не достал. Глубокая пагинация тут не работает:
        // бесплатный CoinGecko отдаёт 429 уже на пятой странице подряд, и цикл
        // обрывается на первой тысяче. А монета вне первой тысячи — обычное дело
        // для свежего листинга, и именно свежие дают разлоки: OPN (Opinion) имеет
        // ранг 1185 и на 02.09.2026 был единственным, что S5 вообще торговал.
        //
        // Поэтому спорные тикеры добираются ТОЧЕЧНО: один маленький запрос
        // /search на тикер, и только на те, где спор есть. Их полтора десятка,
        // а не две тысячи.
        Set<String> contested = UnlockFeed.contested(geckoToTicker, krakenBases);
        int recovered = 0;
        Set<String> unchecked = new java.util.TreeSet<>();
        for (String ticker : contested) {
            if (m.containsKey(ticker)) {
                continue;
            }
            JsonNode found = throttled("https://api.coingecko.com/api/v3/search?query=" + ticker);
            if (found == null) {
                // ⚠️ Запрос НЕ ПРОШЁЛ — это не то же самое, что «монета спорная».
                // Смешивать их нельзя: первое временно и лечится повтором, второе
                // постоянно. Слив их вместе, мы бы вычёркивали монету из торгуемой
                // вселенной из-за одного 429 и не знали бы об этом.
                unchecked.add(ticker);
                continue;
            }
            String id = pickCanonical(found, ticker);
            if (id != null) {
                m.put(ticker, id);
                recovered++;
            }
        }
        log.info("спорных тикеров {}, добрано поиском {}", contested.size(), recovered);
        if (!unchecked.isEmpty()) {
            log.warn("НЕ ПРОВЕРЕНЫ из-за отказов CoinGecko: {} — это НЕ двусмысленность, "
                    + "а недоступность справочника; на следующем старте попробуем снова",
                    unchecked);
        }
        return m;
    }

    /**
     * Главная монета с этим тикером по {@code /search}: ответ содержит
     * {@code market_cap_rank}, и меньший ранг означает старшую монету.
     * Кандидаты без ранга не рассматриваются — это ровно те однофамильцы,
     * от которых мы и защищаемся.
     */
    /**
     * Запрос с паузой и одним повтором.
     *
     * У бесплатного CoinGecko лимит порядка десятка запросов в минуту. Первая
     * версия слала поиск по спорным тикерам подряд — семь в секунду — и получала
     * 429 на большинстве; из-за этого COOKIE, GOAT, LAYER, MIRA, PIXEL и SPELL
     * выпали из вселенной, хотя двусмысленными не были.
     */
    private JsonNode throttled(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 2_500 : 15_000);
                return getJson(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                if (attempt == 1) {
                    log.warn("CoinGecko после повтора: {}", e.toString());
                }
            }
        }
        return null;
    }

    static String searchCanonical(java.util.function.Function<String, JsonNode> fetch, String ticker) {
        JsonNode root = fetch.apply("https://api.coingecko.com/api/v3/search?query=" + ticker);
        return root == null ? null : pickCanonical(root, ticker);
    }

    /** Из ответа {@code /search} — монета с наименьшим рангом капитализации. */
    static String pickCanonical(JsonNode root, String ticker) {
        String best = null;
        long bestRank = Long.MAX_VALUE;
        for (JsonNode n : root.path("coins")) {
            if (!ticker.equalsIgnoreCase(n.path("symbol").asText(""))) {
                continue;
            }
            JsonNode rank = n.path("market_cap_rank");
            if (rank.isNull() || !rank.isNumber()) {
                continue;
            }
            if (rank.asLong() < bestRank) {
                bestRank = rank.asLong();
                best = n.path("id").asText(null);
            }
        }
        return best;
    }

    /** Базы-тикеры торгуемых PF_<BASE>USD перпов Kraken (XBT->BTC). */
    private Set<String> krakenBases() throws Exception {
        Set<String> b = new HashSet<>();
        for (JsonNode t : getJson("https://futures.kraken.com/derivatives/api/v3/tickers").path("tickers")) {
            String sym = t.path("symbol").asText("").toUpperCase();
            if (!sym.startsWith("PF_") || !sym.endsWith("USD")) continue;
            String core = sym.substring(3, sym.length() - 3);
            b.add(core.equals("XBT") ? "BTC" : core);
        }
        return b;
    }
}
