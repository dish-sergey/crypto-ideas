package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ЛУЧШИЕ БИД И АСК БИНАНСА ПО WEBSOCKET, ВНУТРИ ПРОЦЕССА БОТА (пилот ADA, 15.10.2026).
 *
 * Служба {@link BnbFeed} спрашивает Бинанс раз в секунду, а плохие покупки на стенде
 * приходили в пределах секунды после рывка Бинанса (−19 б.п. в среднем): секундная
 * реакция их не ловит. Поток {@code <symbol>@bookTicker} присылает каждое изменение
 * лучшей цены, и бот узнаёт о рывке за десятки миллисекунд, а не за секунду.
 *
 * Адрес — комбинированный поток спота {@code wss://stream.binance.com:9443/stream}.
 * Бинанс сам шлёт ping, JDK отвечает pong без нашего участия (как у сборщика
 * ликвидаций). Соединение Бинанс рвёт раз в сутки; поток переподключается сам.
 *
 * ⚠️ Молчание — тоже сбой: если нет ни сообщений, ни ответа на наш пинг дольше {@link #SILENT_MS}, сессия
 * обрывается и открывается заново. По ADAUSDT и USDCUSDT в норме приходят десятки
 * сообщений в секунду, так что три секунды тишины — это обрыв, а не спокойный рынок.
 * Свежесть цены проверяет опора ({@link FastBnbFair}), и при старой цене бот отводит
 * заявки, а не торгует по ней. ⚠️ Отдельная пара может молчать и при живой сессии:
 * {@code bookTicker} шлёт сообщение только на изменение верха книги, а у USDCUSDT
 * (и у ADAUSDT ночью) он стоит секундами — сухой прогон 14.10 ловил это 156 раз за час.
 */
public final class BnbStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BnbStream.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String URL = System.getProperty("revx.bnb.ws-url", "wss://stream.binance.com:9443/stream");
    static final long SILENT_MS = Long.getLong("revx.bnb.ws-silent-ms", 3_000L);
    /**
     * СВОЙ ПИНГ раз в столько мс (15.10.2026). Сухой прогон SOL с одной парой SOLUSDC порвал
     * живую сессию 163 раза за 8 ч: вечером верх книги стоит по 3+ с, и тишина принималась за
     * обрыв. Теперь признак жизни — сообщение ИЛИ ответ на наш пинг; молчит дольше
     * {@link #SILENT_MS} и то и другое — тогда обрыв. 0 — без пинга (прежнее поведение).
     */
    static final long PING_MS = Long.getLong("revx.bnb.ws-ping-ms", 1_000L);
    private volatile long pongs;

    /** Сколько ответов на наш пинг пришло (диагностика). */
    public long pongs() {
        return pongs;
    }

    /** Лучшие цены пары и момент, когда мы их получили (наши часы). */
    public record Tick(double bid, double ask, long recvMs) {
        public double mid() {
            return (bid + ask) / 2;
        }
    }

    private final String[] symbols;
    private final Map<String, Tick> last = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile boolean alive = true;
    private volatile long lastMsgMs;
    private volatile Runnable onUpdate = () -> { };
    private Thread thread;
    private long reconnects;

    /** @param symbols пары Бинанса, например {@code ADAUSDT,USDCUSDT} */
    public BnbStream(String... symbols) {
        this.symbols = new String[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            this.symbols[i] = symbols[i].trim().toUpperCase(Locale.ROOT);
        }
    }

    /** Последний признак жизни сессии (наши часы): сообщение по ЛЮБОЙ из пар или ответ на наш пинг. */
    public long lastMsgMs() {
        return lastMsgMs;
    }

    /** Вызывается из потока websocket на каждое изменение лучших цен. */
    public void onUpdate(Runnable r) {
        this.onUpdate = r == null ? () -> { } : r;
    }

    public Tick tick(String symbol) {
        return last.get(symbol.toUpperCase(Locale.ROOT));
    }

    public long reconnects() {
        return reconnects;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "bnb-stream");
        thread.setDaemon(true);
        thread.start();
    }

    private String url() {
        StringBuilder sb = new StringBuilder(URL).append("?streams=");
        for (int i = 0; i < symbols.length; i++) {
            sb.append(i > 0 ? "/" : "").append(symbols[i].toLowerCase(Locale.ROOT)).append("@bookTicker");
        }
        return sb.toString();
    }

    private void loop() {
        String url = url();
        log.warn("Бинанс по websocket: {}", url);
        long backoff = 500;
        while (alive) {
            long started = System.currentTimeMillis();
            try {
                session(url);
            } catch (Exception e) {
                log.warn("Бинанс websocket: {}", e.toString());
            }
            if (!alive) {
                return;
            }
            reconnects++;
            // Долгая сессия — обычный суточный обрыв, переподключаемся сразу; частые обрывы —
            // с нарастающей паузой до 10 с, чтобы не долбить сервер.
            backoff = System.currentTimeMillis() - started > 60_000 ? 500 : Math.min(10_000, backoff * 2);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void session(String url) throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean lastPart) {
                buffer.append(data);
                if (lastPart) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    try {
                        handle(message);
                    } catch (Exception e) {
                        log.warn("Бинанс websocket: не разобрал сообщение: {}", e.getMessage());
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPong(WebSocket ws, java.nio.ByteBuffer message) {
                // Ответ на наш пинг: сессия жива, даже если верх книги стоит.
                lastMsgMs = System.currentTimeMillis();
                pongs++;
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                log.warn("Бинанс websocket закрыт: {} {}", statusCode, reason);
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                log.warn("Бинанс websocket ошибка: {}", error.toString());
                closed.countDown();
            }
        };
        WebSocket ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(url), listener).get(5, TimeUnit.SECONDS);
        lastMsgMs = System.currentTimeMillis();
        long lastPing = 0;
        try {
            while (alive && closed.getCount() > 0) {
                if (closed.await(500, TimeUnit.MILLISECONDS)) {
                    break;
                }
                long nowPing = System.currentTimeMillis();
                if (PING_MS > 0 && nowPing - lastPing >= PING_MS) {
                    lastPing = nowPing;
                    ws.sendPing(java.nio.ByteBuffer.wrap(new byte[]{'p'}));
                }
                long silent = System.currentTimeMillis() - lastMsgMs;
                if (silent > SILENT_MS) {
                    log.warn("Бинанс websocket молчит {} мс — переподключаюсь", silent);
                    break;
                }
            }
        } finally {
            ws.abort();
        }
    }

    /** {"stream":"adausdt@bookTicker","data":{"u":…,"s":"ADAUSDT","b":"0.2357","B":"…","a":"0.2358","A":"…"}} */
    void handle(String message) throws Exception {
        JsonNode root = MAPPER.readTree(message);
        JsonNode d = root.has("data") ? root.path("data") : root;
        String s = d.path("s").asText("");
        if (s.isEmpty()) {
            return;
        }
        double bid = d.path("b").asDouble(Double.NaN);
        double ask = d.path("a").asDouble(Double.NaN);
        if (!(bid > 0) || !(ask >= bid)) {
            return;
        }
        long now = System.currentTimeMillis();
        lastMsgMs = now;
        last.put(s, new Tick(bid, ask, now));
        onUpdate.run();
    }

    /** Тест: положить цену с заданным моментом получения, как будто она пришла из потока. */
    void inject(String symbol, double bid, double ask, long recvMs) {
        last.put(symbol.toUpperCase(Locale.ROOT), new Tick(bid, ask, recvMs));
        lastMsgMs = Math.max(lastMsgMs, recvMs);
        onUpdate.run();
    }

    @Override
    public void close() {
        alive = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
