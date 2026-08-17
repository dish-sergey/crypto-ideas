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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Сухой» прогон S5 против реального мира без реальных денег: живой фид разлоков (DefiLlama) +
 * настоящие марк-цены Kraken Futures + реальный Telegram (пуши, кнопки Подтвердить/Отклонить, /status),
 * но исполнение — на {@link MockExchange}. Проверяет весь контур на настоящих данных — то, что нельзя
 * прогнать на моке, — и ничем не рискует.
 *
 * <p>Режимы: {@code digestOnce()} — разовый дайджест «что впереди» в Telegram и выход (годится как крон);
 * {@code runDaemon()} — интерактивный: слушатель команд + минутный цикл (обновить марки → discover →
 * исполнить подтверждённое → сопровождение → стопы). Funding-фильтр в dry-run отключён (нет KrakenFundingSource).
 */
public class S5DryRun {

    private static final Logger log = LoggerFactory.getLogger(S5DryRun.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
    private final String configPath;

    public S5DryRun(String configPath) { this.configPath = configPath; }

    private record Ctx(UnlockFeed feed, TelegramNotifier notifier, MockExchange ex,
                       S5Orchestrator orch, TelegramCommandListener listener) {}

    private Ctx build() throws Exception {
        S5TelegramConfig tg = S5TelegramConfig.load(configPath);
        TelegramTransport tx = new HttpTelegramTransport(tg.token());
        TelegramNotifier notifier = new TelegramNotifier(tx, tg.chatId());

        Map<String, Double> marks = krakenMarks();                 // реальные PF_*USD марки
        UnlockFeed feed = new UnlockFeed(new DefiLlamaEmissionSource(), geckoToTicker(), basesFrom(marks));

        MockExchange ex = new MockExchange(1000);
        marks.forEach(ex::tick);                                    // сид цен реальными марками Kraken
        krakenMinSizes().forEach(ex::setMinSize);                   // сид реальных мин. лотов Kraken
        ApprovalGate gate = new ApprovalGate();
        TradeJournal j = new TradeJournal();
        StopEngine engine = new StopEngine(ex, j, S5Config.protocol().stopFrac(), 3);
        FundingSource funding = base -> 0.0;                        // dry-run: фильтр дорогого шорта выключен
        S5Orchestrator orch = new S5Orchestrator(ex, feed, funding, gate, engine,
                new ScheduleTracker(), new DegradationMonitor(), j, S5Config.protocol(), notifier);
        TelegramCommandListener listener = new TelegramCommandListener(tx, tg.chatId(), orch);
        return new Ctx(feed, notifier, ex, orch, listener);
    }

    /** Разовый дайджест «что впереди» в Telegram и выход. */
    public void digestOnce() throws Exception {
        Ctx c = build();
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        c.notifier.push(Alert.info("S5 dry-run — дайджест", digest(c, today)));
        log.info("digest отправлен");
    }

    /** Интерактивный демон: слушатель команд/кнопок + минутный торговый цикл. Блокирует поток. */
    public void runDaemon() throws Exception {
        Ctx c = build();
        long today0 = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        c.notifier.push(Alert.info("S5 dry-run запущен",
                "Живой DefiLlama + реальные марки Kraken, деньги виртуальные.\nКоманды: /status, /positions.\n\n" + digest(c, today0)));
        c.listener.start();
        log.info("dry-run daemon: слушатель запущен, цикл раз в 60с (скан фида — раз в день)");
        long lastScanDay = -1;
        while (true) {
            try {
                long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
                long nowSec = System.currentTimeMillis() / 1000;
                krakenMarks().forEach(c.ex::tick);                 // свежие марки (1 HTTP)
                if (today != lastScanDay) {                        // тяжёлый скан фида — раз в день
                    c.orch.discover(today);                        // новые кандидаты → первый пуш с кнопками
                    c.orch.maintain(today);                        // плановый выход / перенос
                    lastScanDay = today;
                }
                c.orch.pollReminders(nowSec);                      // эскалация напоминаний 24/12/3/1ч
                c.orch.executeApproved(today);                     // подтверждённые → открыть в день входа
                c.orch.pollStops();                                // стопы по свежим маркам
            } catch (Exception e) {
                log.warn("dry-run цикл: {}", e.toString());
            }
            Thread.sleep(60_000);
        }
    }

    /** Текст дайджеста: ближайшие ≥3% события и сколько в окне входа. */
    private String digest(Ctx c, long today) throws Exception {
        List<UnlockEvent> up = c.feed.upcoming(today);
        StringBuilder sb = new StringBuilder("📊 Ближайшие клифф-разлоки ≥3% на Kraken:");
        int shown = 0;
        for (UnlockEvent e : up) {
            if (shown++ >= 12) break;
            sb.append("\n").append(e.krakenSymbol())
              .append("  через ").append(e.unlockDay() - today).append("д  ")
              .append(e.pctLabel());
        }
        if (up.isEmpty()) sb.append("\n(пусто — нет предстоящих ≥3%)");
        int lead = S5Config.protocol().entryLead();
        sb.append("\n\nвсего впереди: ").append(up.size())
          .append("\nв окне входа (≤").append(lead).append("д): ").append(c.feed.enterableWithin(today, lead).size());
        return sb.toString();
    }

    // --- живые источники ---

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " " + url);
        return M.readTree(r.body());
    }

    /** gecko_id -> TICKER (upper) из CoinGecko coins/list. */
    private Map<String, String> geckoToTicker() throws Exception {
        Map<String, String> m = new HashMap<>();
        for (JsonNode n : getJson("https://api.coingecko.com/api/v3/coins/list"))
            m.put(n.path("id").asText(), n.path("symbol").asText().toUpperCase());
        log.info("CoinGecko coins: {}", m.size());
        return m;
    }

    /** Реальные марк-цены перпов Kraken Futures: PF_<BASE>USD -> markPrice. */
    private Map<String, Double> krakenMarks() throws Exception {
        Map<String, Double> marks = new LinkedHashMap<>();
        for (JsonNode n : getJson("https://futures.kraken.com/derivatives/api/v3/tickers").path("tickers")) {
            String sym = n.path("symbol").asText("").toUpperCase();
            if (!sym.startsWith("PF_") || !sym.endsWith("USD")) continue;
            double mk = n.path("markPrice").asDouble(n.path("last").asDouble(0));
            if (mk > 0) marks.put(sym, mk);
        }
        return marks;
    }

    /** Мин. лоты перпов Kraken: PF_<BASE>USD -> 10^(−contractValueTradePrecision). */
    private Map<String, Double> krakenMinSizes() throws Exception {
        Map<String, Double> min = new LinkedHashMap<>();
        for (JsonNode i : getJson("https://futures.kraken.com/derivatives/api/v3/instruments").path("instruments")) {
            String sym = i.path("symbol").asText("").toUpperCase();
            if (!sym.startsWith("PF_") || !sym.endsWith("USD") || !i.path("tradeable").asBoolean(false)) continue;
            min.put(sym, Math.pow(10, -i.path("contractValueTradePrecision").asInt(0)));
        }
        return min;
    }

    /** Из символов PF_<BASE>USD — базы-тикеры (XBT->BTC), как ждёт UnlockFeed. */
    private static Set<String> basesFrom(Map<String, Double> marks) {
        Set<String> b = new HashSet<>();
        for (String s : marks.keySet()) {
            String core = s.substring(3, s.length() - 3);
            b.add(core.equals("XBT") ? "BTC" : core);
        }
        return b;
    }
}
