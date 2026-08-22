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

        UnlockFeed feed = new UnlockFeed(new DefiLlamaEmissionSource(), geckoToTicker(), krakenBases());

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
