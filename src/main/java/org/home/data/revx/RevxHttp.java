package org.home.data.revx;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP-клиент стенда Revolut X.
 *
 * Отличия от {@link org.home.data.core.ApiClient}, из-за которых он здесь не годится:
 *  - глобальный токен-бакет вместо пер-хостового интервала: ноги пары уходят
 *    одновременно (ТЗ §3.3, skew < 250 мс), а суммарный темп всё равно ограничен;
 *  - параллельность на виртуальных потоках (батч запросов = один залп);
 *  - экспоненциальный backoff С ДЖИТТЕРОМ на 429/5xx (ТЗ §3.2);
 *  - счётчики запросов/429/ошибок — метрика прогона: доля 429 выше 1% помечает
 *    отчёт как неполный.
 */
@Component
@Lazy
public class RevxHttp {

    private static final Logger log = LoggerFactory.getLogger(RevxHttp.class);
    private static final String USER_AGENT = "crypto-ideas-revx-stand/1.0";

    private final RevxConfig cfg;
    private final RevxAuth auth;
    private final TokenBucket bucket;
    private final HttpClient client;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong http429 = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    public RevxHttp(RevxConfig cfg, RevxEndpoints endpoints, RevxAuth auth) {
        this.cfg = cfg;
        this.auth = auth;
        // Темп и залп зависят от того, есть ли ключ:
        //  - публично: 1 токен/с и НИКАКИХ залпов. Замер 19.08.2026 — площадка
        //    ограничивает минимальный интервал между запросами, два запроса встык
        //    дают 429 на втором при любой паузе между парами;
        //  - с ключом: 100/с и 1000/мин, залпы разрешены, ноги пары снова
        //    можно слать одновременно (ТЗ §3.3).
        this.bucket = new TokenBucket(endpoints.maxRequestsPerSecond(), endpoints.burstCapacity());
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(pool)
                .build();
    }

    /** Ответ с обоими временами: t_sent/t_recv нужны для skew_ms и available_at. */
    public record Response(String url, int status, String body, long sentMs, long recvMs, String error,
                           long retryAfterMs) {

        public boolean ok() {
            return status >= 200 && status < 300 && error == null;
        }
    }

    /** Одиночный GET с ретраями (каталог пар, страница сделок). Блокирующий. */
    public Response get(String url) {
        bucket.acquire(1);
        return sendWithRetries(url);
    }

    /**
     * Последовательные GET-ы БЕЗ ретраев — для двух ног пары.
     *
     * Параллельно их запросить нельзя: площадка отбивает второй запрос 429 при
     * любой паузе между парами (замер 19.08.2026), ограничение — минимальный
     * интервал ~1 с между запросами. Поэтому ноги идут встык через бакет, а
     * skew между ними получается порядка периода бакета и пишется в снимок.
     *
     * Ретраев здесь нет намеренно: повтор сдвигает ногу на секунды и превращает
     * снимок пары в бессмысленный для базиса — дешевле пропустить цикл.
     */
    public List<Response> getSequential(List<String> urls) {
        List<Response> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            bucket.acquire(1);
            out.add(send(url));
        }
        return out;
    }

    /**
     * Одновременный залп — только для авторизованного пути, где лимит 100 req/s
     * это позволяет. Разрешения берутся сразу на весь залп, запросы стартуют
     * параллельно на виртуальных потоках: именно так две ноги пары получают
     * skew в миллисекундах вместо секунды (ТЗ §3.3).
     */
    public List<Response> getParallel(List<String> urls) {
        bucket.acquire(urls.size());
        List<java.util.concurrent.Future<Response>> futures = new ArrayList<>(urls.size());
        for (String url : urls) {
            futures.add(pool.submit(() -> send(url)));
        }
        List<Response> out = new ArrayList<>(urls.size());
        for (var future : futures) {
            try {
                out.add(future.get());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("сбой залпа запросов", e);
            }
        }
        return out;
    }

    /**
     * Один запрос в обход бакета и без ретраев — только для {@link RateLimitProbe}:
     * он сам задаёт темп, а ретраи исказили бы измерение потолка.
     */
    public Response sendOnce(String url) {
        return send(url);
    }

    private Response sendWithRetries(String url) {
        Response last = send(url);
        for (int attempt = 1; attempt <= cfg.maxRetries() && retryable(last); attempt++) {
            retries.incrementAndGet();
            sleep(waitBeforeRetryMs(attempt, last));
            bucket.acquire(1);
            last = send(url);
        }
        if (!last.ok()) {
            log.warn("revx GET {} → {} {}", url, last.status(),
                    last.error() != null ? last.error() : "");
        }
        return last;
    }

    private static boolean retryable(Response r) {
        return r.status() == 429 || r.status() >= 500 || r.error() != null;
    }

    private Response send(String url) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET();
        // подпись ставится непосредственно перед отправкой: в неё входит timestamp,
        // и на ретрае она обязана быть новой
        auth.headers("GET", uri).forEach(builder::header);
        HttpRequest request = builder.build();
        requests.incrementAndGet();
        long sent = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long recv = System.currentTimeMillis();
            int code = response.statusCode();
            if (code == 429) {
                http429.incrementAndGet();
            } else if (code >= 200 && code < 300) {
                ok.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
            return new Response(url, code, response.body(), sent, recv, null, retryAfterMs(response));
        } catch (IOException e) {
            failures.incrementAndGet();
            return new Response(url, 0, null, sent, System.currentTimeMillis(),
                    Objects.toString(e.getMessage(), e.getClass().getSimpleName()), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.incrementAndGet();
            return new Response(url, 0, null, sent, System.currentTimeMillis(), "прервано", 0);
        }
    }

    /**
     * Revolut X отдаёт в 429 заголовок Retry-After со значениями вида 375 и 531,
     * при том что следующий запрос проходит уже через секунду (замер 19.08.2026).
     * То есть это МИЛЛИСЕКУНДЫ, вопреки HTTP-спеке, где Retry-After — секунды.
     * Значения меньше 60 трактуем всё же как секунды: так ведёт себя спека, и
     * ошибка в эту сторону безопаснее (подождём дольше, чем нужно).
     */
    private static long retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .map(v -> {
                    try {
                        long value = Long.parseLong(v.trim());
                        return value <= 60 ? value * 1000 : value;
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /**
     * Пауза перед повтором: если сервер сам назвал срок — слушаем его (плюс
     * небольшой джиттер, чтобы параллельные ноги не бились в один момент),
     * иначе экспоненциальный backoff с джиттером.
     */
    private static long waitBeforeRetryMs(int attempt, Response response) {
        if (response.retryAfterMs() > 0) {
            long capped = Math.min(response.retryAfterMs(), 30_000L);
            return capped + ThreadLocalRandom.current().nextLong(100);
        }
        long base = (response.status() == 429 ? 5000L : 1000L) * (1L << (attempt - 1));
        return base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Счётчики за время жизни процесса — идут в телеметрию прогона (ТЗ §5.1). */
    public record Stats(long requests, long ok, long http429, long failures, long retries) {

        public double ratio429() {
            return requests == 0 ? 0 : (double) http429 / requests;
        }
    }

    public Stats stats() {
        return new Stats(requests.get(), ok.get(), http429.get(), failures.get(), retries.get());
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }
}
