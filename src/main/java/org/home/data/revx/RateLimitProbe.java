package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Эмпирический замер потолка запросов (ТЗ §3.2: «отдельный скрипт медленно
 * повышает частоту до первого устойчивого 429 и записывает найденный потолок
 * в конфиг»).
 *
 * Зачем это первым шагом: вселенная — 46 символов, при опросе раз в 5 с это
 * 9.2 req/s, а 429 на /order-book уже ловили. Пока потолок неизвестен, частота
 * опроса в конфиге была бы догадкой, а от неё зависит грубость модели исполнения.
 *
 * Логика: ступень = целевой темп в течение dwell секунд. Доля 429 выше порога —
 * ступень «throttled», после паузы она повторяется; если подтвердилась — это
 * «устойчивый 429», замер останавливается, потолком считается последняя чистая
 * ступень. Запросы идут в обход бакета и без ретраев: темп задаёт сам замер,
 * а ретраи исказили бы картину.
 */
@Component
@Lazy
public class RateLimitProbe {

    private static final Logger log = LoggerFactory.getLogger(RateLimitProbe.class);

    private static final String INSERT_LEVEL = """
            INSERT OR REPLACE INTO revx_probe(run_ms, level_rps, attempt, requests, ok, http_429,
                                              other, p50_ms, p95_ms, verdict)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """;

    private final RevxConfig cfg;
    private final RevxEndpoints endpoints;
    private final RevxHttp http;
    private final RevxDb db;
    private final PairsCatalog catalog;

    public RateLimitProbe(RevxConfig cfg, RevxEndpoints endpoints, RevxHttp http, RevxDb db,
                          PairsCatalog catalog) {
        this.cfg = cfg;
        this.endpoints = endpoints;
        this.http = http;
        this.db = db;
        this.catalog = catalog;
    }

    private record Level(double rps, int attempt, int requests, int ok, int http429, int other,
                         long p50, long p95, long retryAfterMs, String verdict) {

        double ratio429() {
            return requests == 0 ? 0 : (double) http429 / requests;
        }
    }

    /** Переопределения ступеней из CLI; null = значение из конфига. */
    public record Ladder(Double startRps, Double stepRps, Double maxRps, Integer dwellSeconds) {
    }

    /** Полный замер. */
    public void run(Ladder ladder) {
        long runMs = System.currentTimeMillis();
        double startRps = ladder.startRps() != null ? ladder.startRps() : cfg.probeStartRps();
        double stepRps = ladder.stepRps() != null ? ladder.stepRps() : cfg.probeStepRps();
        double maxRps = ladder.maxRps() != null ? ladder.maxRps() : cfg.probeMaxRps();
        int dwell = ladder.dwellSeconds() != null ? ladder.dwellSeconds() : cfg.probeDwellSeconds();

        Map<String, PairSpec> specs = catalog.refresh();
        List<String> targets = probeTargets(catalog.universe(specs));
        log.info("замер лимита: {} → {} req/s шагом {}, ступень {} c, {} эндпоинтов",
                startRps, maxRps, stepRps, dwell, targets.size());

        List<Level> history = new ArrayList<>();
        double ceiling = 0;
        double sustainedAt = 0;

        for (double rps = startRps; rps <= maxRps + 1e-9; rps += stepRps) {
            Level level = measure(runMs, rps, 1, dwell, targets);
            history.add(level);
            if (level.ratio429() < cfg.probeFailRatio()) {
                ceiling = rps;
                continue;
            }
            log.warn("ступень {} req/s: 429 в {}% запросов — пауза {} c и повтор",
                    fmt(rps), Math.round(level.ratio429() * 1000) / 10.0, cfg.probeCooldownSeconds());
            sleepSeconds(cfg.probeCooldownSeconds());
            Level confirm = measure(runMs, rps, 2, dwell, targets);
            history.add(confirm);
            if (confirm.ratio429() >= cfg.probeFailRatio()) {
                sustainedAt = rps;
                break;
            }
            ceiling = rps; // не подтвердилось — считаем ступень чистой и идём выше
        }

        report(history, ceiling, sustainedAt, maxRps, catalog.universe(specs).size() * 2);
    }

    /** Одна ступень: держим заданный темп dwell секунд, считаем статусы и задержки. */
    private Level measure(long runMs, double rps, int attempt, int dwellSeconds, List<String> targets) {
        int total = Math.max(1, (int) Math.round(rps * dwellSeconds));
        long intervalNanos = (long) (1_000_000_000L / rps);
        List<Future<RevxHttp.Response>> futures = new ArrayList<>(total);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            for (int i = 0; i < total; i++) {
                long due = start + i * intervalNanos;
                parkUntil(due);
                String url = endpoints.book(targets.get(i % targets.size()));
                futures.add(pool.submit(() -> http.sendOnce(url)));
            }
        }

        int ok = 0;
        int http429 = 0;
        int other = 0;
        List<Long> latencies = new ArrayList<>(total);
        List<Long> retryAfters = new ArrayList<>();
        for (Future<RevxHttp.Response> f : futures) {
            RevxHttp.Response r;
            try {
                r = f.get();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("замер прерван", e);
            }
            if (r.status() == 429) {
                http429++;
                if (r.retryAfterMs() > 0) {
                    retryAfters.add(r.retryAfterMs());
                }
            } else if (r.ok()) {
                ok++;
                latencies.add(r.recvMs() - r.sentMs());
            } else {
                other++;
            }
        }

        Collections.sort(latencies);
        Collections.sort(retryAfters);
        // вердикт считается по тому же порогу, что и решение о переходе на следующую
        // ступень: единичный 429 ниже порога — это шум, а не потолок
        boolean overThreshold = total > 0 && (double) http429 / total >= cfg.probeFailRatio();
        Level level = new Level(rps, attempt, total, ok, http429, other,
                percentile(latencies, 0.50), percentile(latencies, 0.95),
                percentile(retryAfters, 0.50),
                !overThreshold ? "clean" : (attempt == 1 ? "throttled" : "sustained"));
        db.upsert(INSERT_LEVEL, runMs, rps, attempt, level.requests(), level.ok(), level.http429(),
                level.other(), level.p50(), level.p95(), level.verdict());
        log.info("{} req/s (попытка {}): ok={} 429={} прочих={} p50={} мс p95={} мс retry-after={} мс",
                fmt(rps), attempt, ok, http429, other, level.p50(), level.p95(), level.retryAfterMs());
        return level;
    }

    /**
     * Эндпоинты для нагрузки: обе ноги первых пар вселенной. Берём и USDC, и USD —
     * 429 в док. 62 ловили именно на BTC-USD, и неизвестно, лимит общий или
     * пер-эндпоинтный; равномерная ротация не даёт ответа на этот вопрос, но и
     * не смещает замер в сторону одного эндпоинта.
     */
    private List<String> probeTargets(List<PairsCatalog.Leg> universe) {
        List<String> targets = new ArrayList<>();
        for (PairsCatalog.Leg leg : universe.subList(0, Math.min(4, universe.size()))) {
            targets.add(leg.quoted().pathSymbol());
            targets.add(leg.reference().pathSymbol());
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("вселенная пуста — каталог пар не отдал USDC-пар с опорой");
        }
        return targets;
    }

    private void report(List<Level> history, double ceiling, double sustainedAt, double maxRps,
                        int universeLegs) {
        StringBuilder sb = new StringBuilder("\n=== Замер лимита запросов Revolut X ===\n");
        sb.append(String.format("%-8s %-8s %8s %6s %6s %7s %8s %8s %12s  %s%n",
                "req/s", "попытка", "запросов", "ok", "429", "прочих", "p50, мс", "p95, мс",
                "retry-after", "вердикт"));
        for (Level l : history) {
            sb.append(String.format("%-8s %-8d %8d %6d %6d %7d %8d %8d %12d  %s%n",
                    fmt(l.rps()), l.attempt(), l.requests(), l.ok(), l.http429(), l.other(),
                    l.p50(), l.p95(), l.retryAfterMs(), l.verdict()));
        }

        if (sustainedAt > 0) {
            sb.append(String.format("%nУстойчивый 429 начинается с %s req/s.%n", fmt(sustainedAt)));
        } else {
            sb.append(String.format("%nДо потолка замера (%s req/s) устойчивого 429 не получено — "
                    + "лимит выше проверенного, поднимать --max-rps.%n", fmt(maxRps)));
        }

        if (ceiling <= 0) {
            sb.append("Чистых ступеней нет: 429 уже на стартовом темпе. Снизить revx.probe.start-rps.\n");
        } else {
            double recommended = Math.round(ceiling * 0.8 * 100) / 100.0;  // запас 20% на джиттер и ретраи
            double fullCycleSeconds = universeLegs / recommended;
            int legsIn5s = (int) Math.floor(recommended * 5);
            sb.append(String.format("Последняя чистая ступень: %s req/s.%n", fmt(ceiling)));
            sb.append(String.format("Рекомендация в конфиг: revx.max-requests-per-second=%s (запас 20%%).%n",
                    fmt(recommended)));
            sb.append(String.format("Полный обход %d ног занимает %.0f c.%n", universeLegs, fullCycleSeconds));
            sb.append(fullCycleSeconds <= 5
                    ? "Хватает на опрос всей вселенной раз в 5 с.\n"
                    : String.format("В 5-секундное окно влезает %d ног = %d пар. Вселенную придётся сузить "
                            + "или снимать реже — решение фиксируется в конфиге и в записи прогона.%n",
                            legsIn5s, legsIn5s / 2));
        }
        sb.append("Замер записан в revx_probe.\n");
        log.info(sb.toString());
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static void parkUntil(long dueNanos) {
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
