package org.home.data.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Forward-only запись ликвидаций (док. 09 §5, поток №9). Исторических данных
 * бесплатно нет — каждый день без работающего коллектора потерян навсегда.
 * Три CEX-источника: Binance !forceOrder@arr (весь рынок, UM+CM), OKX
 * liquidation-orders (все SWAP), Bybit allLiquidation (пер-символьно).
 * available_at = момент получения из WS.
 */
@Component
public class LiquidationWsCollector {

    private static final Logger log = LoggerFactory.getLogger(LiquidationWsCollector.class);

    // Binance liquidations: !forceOrder@arr — объединённый UM+CM поток (поле st: 1=UM, 2=CM,
    // после CM-миграции). Рабочий эндпоинт — /market/stream + SUBSCRIBE, combined-формат
    // {"stream":..,"data":{"o":..}}. Устаревшие /ws и /stream отдают только ack без данных
    // (проверено с micro 2026-07-23) — отсюда прежние нули по Binance-ликвидациям.
    private static final String BINANCE_URL = "wss://fstream.binance.com/market/stream";
    private static final String BINANCE_SUBSCRIBE =
            "{\"method\":\"SUBSCRIBE\",\"params\":[\"!forceOrder@arr\"],\"id\":1}";
    private static final String BYBIT_URL = "wss://stream.bybit.com/v5/public/linear";
    // OKX: публичный канал liquidation-orders по всем SWAP-инструментам (третий CEX-источник).
    private static final String OKX_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String OKX_SUBSCRIBE =
            "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"liquidation-orders\",\"instType\":\"SWAP\"}]}";
    private static final String BYBIT_INSTRUMENTS =
            "https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000";
    // Bybit: allLiquidation.<symbol> пер-символьный, wildcard'а нет. Тянем весь список
    // инструментов и подписываемся батчами; реконнект раз в 6ч подхватывает новые листинги.
    private static final long BYBIT_SESSION_MS = 6 * 3600_000L;
    private static final int BYBIT_BATCH = 100;
    private static final long RECONNECT_DELAY_MS = 5000;

    private static final String INSERT = """
            INSERT OR IGNORE INTO liquidations(exchange, symbol, ts, side, price, qty, available_at)
            VALUES(?,?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final boolean binanceEnabled;
    private final boolean bybitAllSymbols;
    private final List<String> bybitSymbols;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private volatile boolean running;

    public LiquidationWsCollector(Db db, ApiClient api,
                                  @Value("${collectors.liq-binance-enabled}") boolean binanceEnabled,
                                  @Value("${collectors.liq-bybit-all-symbols:true}") boolean bybitAllSymbols,
                                  @Value("${collectors.liq-bybit-symbols}") List<String> bybitSymbols) {
        this.db = db;
        this.api = api;
        this.binanceEnabled = binanceEnabled;
        this.bybitAllSymbols = bybitAllSymbols;
        this.bybitSymbols = bybitSymbols;
    }

    /** Запускается только в режиме планировщика (см. CliRunner). */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (binanceEnabled) {
            // Binance сам шлёт ping-фреймы, JDK отвечает pong — app-ping не нужен (null).
            startLoop("liq-binance", () -> connect(BINANCE_URL, List.of(BINANCE_SUBSCRIBE), null, 0, this::handleBinance));
        }
        // Bybit: список символов пересобирается на каждом коннекте (свежие листинги).
        startLoop("liq-bybit", () -> connect(BYBIT_URL, bybitSubscribes(), "{\"op\":\"ping\"}", BYBIT_SESSION_MS, this::handleBybit));
        // OKX закрывает соединение без активности ~30с — шлём его raw-ping "ping".
        startLoop("liq-okx", () -> connect(OKX_URL, List.of(OKX_SUBSCRIBE), "ping", 0, this::handleOkx));
    }

    private void startLoop(String threadName, Runnable session) {
        Thread thread = new Thread(() -> {
            while (running) {
                try {
                    session.run();
                } catch (Exception e) {
                    log.warn("{}: сессия упала: {}", threadName, e.getMessage());
                }
                if (running) {
                    sleep(RECONNECT_DELAY_MS);
                }
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        log.info("{} запущен", threadName);
    }

    /** Обработчик сообщения; в отличие от Consumer позволяет checked-исключения. */
    @FunctionalInterface
    private interface MessageHandler {
        void accept(String message) throws Exception;
    }

    /**
     * Одна WS-сессия: блокируется до разрыва соединения. pingMessage=null — app-ping не слать.
     * sessionMaxMs>0 — завершить сессию через это время (форс-реконнект для обновления подписок).
     */
    private void connect(String url, List<String> subscribeMessages, String pingMessage,
                         long sessionMaxMs, MessageHandler handler) {
        CountDownLatch closed = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    try {
                        handler.accept(message);
                    } catch (Exception e) {
                        log.warn("Ошибка обработки WS-сообщения: {}", e.getMessage());
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                log.warn("WS ошибка {}: {}", url, error.getMessage());
                closed.countDown();
            }
        };

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .join();
        // JDK-требование: не слать следующий text, пока предыдущий не завершился — join().
        for (String s : subscribeMessages) {
            ws.sendText(s, true).join();
        }
        long deadline = sessionMaxMs > 0 ? System.currentTimeMillis() + sessionMaxMs : Long.MAX_VALUE;
        try {
            // Bybit/OKX требуют app-ping каждые ~20с; у Binance pingMessage=null (пингует сам).
            while (closed.getCount() > 0 && running && System.currentTimeMillis() < deadline) {
                if (closed.await(20, TimeUnit.SECONDS)) {
                    break;
                }
                if (pingMessage != null) {
                    ws.sendText(pingMessage, true).join();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ws.abort();
        }
    }

    /** Combined: {"stream":"!forceOrder@arr","data":{"o":{...}}}; ack {"result":null,"id":1} пропускаем. */
    private void handleBinance(String message) throws Exception {
        JsonNode root = mapper.readTree(message);
        JsonNode o = root.has("data") ? root.path("data").path("o") : root.path("o");
        if (o.isMissingNode()) {
            return;
        }
        db.upsert(INSERT, "binance", o.path("s").asText(), o.path("T").asLong(),
                o.path("S").asText(), o.path("ap").asDouble(), o.path("q").asDouble(),
                System.currentTimeMillis());
    }

    /** Bybit: {"topic":"allLiquidation.BTCUSDT","data":[{"T","s","S","v","p"}]} */
    private void handleBybit(String message) throws Exception {
        JsonNode root = mapper.readTree(message);
        if ("subscribe".equals(root.path("op").asText()) && !root.path("success").asBoolean(true)) {
            log.warn("bybit subscribe отклонён: {}", root.path("ret_msg").asText());
            return;
        }
        JsonNode data = root.path("data");
        if (!root.path("topic").asText("").startsWith("allLiquidation") || !data.isArray()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (JsonNode n : data) {
            db.upsert(INSERT, "bybit", n.path("s").asText(), n.path("T").asLong(),
                    n.path("S").asText().toUpperCase(), n.path("p").asDouble(),
                    n.path("v").asDouble(), now);
        }
    }

    /** OKX: {"arg":{..},"data":[{"instId":..,"details":[{"side","sz","bkPx","ts"},..]}]}. */
    private void handleOkx(String message) throws Exception {
        JsonNode data = mapper.readTree(message).path("data");
        if (!data.isArray()) {
            return; // ack/pong/event — не данные
        }
        long now = System.currentTimeMillis();
        for (JsonNode inst : data) {
            String symbol = inst.path("instId").asText().replace("-SWAP", "").replace("-", "");
            for (JsonNode d : inst.path("details")) {
                db.upsert(INSERT, "okx", symbol, d.path("ts").asLong(),
                        d.path("side").asText().toUpperCase(), d.path("bkPx").asDouble(),
                        d.path("sz").asDouble(), now);
            }
        }
    }

    /** Батчи subscribe-сообщений для Bybit (все символы рынка или список из конфига). */
    private List<String> bybitSubscribes() {
        List<String> symbols = bybitAllSymbols ? fetchBybitSymbols() : bybitSymbols;
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += BYBIT_BATCH) {
            messages.add(bybitSubscribeMessage(symbols.subList(i, Math.min(i + BYBIT_BATCH, symbols.size()))));
        }
        return messages;
    }

    /** Все торгуемые linear-перпы Bybit; при сбое REST — fallback на список из конфига. */
    private List<String> fetchBybitSymbols() {
        try {
            JsonNode list = mapper.readTree(api.get(BYBIT_INSTRUMENTS)).path("result").path("list");
            List<String> symbols = new ArrayList<>();
            for (JsonNode n : list) {
                if ("Trading".equals(n.path("status").asText())) {
                    symbols.add(n.path("symbol").asText());
                }
            }
            if (!symbols.isEmpty()) {
                log.info("liq-bybit: подписка на {} символов рынка", symbols.size());
                return symbols;
            }
        } catch (Exception e) {
            log.warn("liq-bybit: список символов недоступен ({}), fallback на конфиг", e.getMessage());
        }
        return bybitSymbols;
    }

    public static String bybitSubscribeMessage(List<String> symbols) {
        StringBuilder sb = new StringBuilder("{\"op\":\"subscribe\",\"args\":[");
        for (int i = 0; i < symbols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"allLiquidation.").append(symbols.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
    }
}
