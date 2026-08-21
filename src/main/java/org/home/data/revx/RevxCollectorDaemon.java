package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Долгоживущий сборщик. ТЗ §3.5: приоритет надёжности выше приоритета
 * функциональности — данные книги задним числом невосполнимы.
 *
 * Расписание ярусное, потому что площадка даёт ~1 req/s на IP (замер шага 1):
 * опрашивать 23 пары раз в 5 секунд, как просит ТЗ §3.4, физически нельзя.
 * Единица планирования — ОДНА пара, а не ярус целиком: обход яруса 3 занял бы
 * почти минуту и заблокировал бы ярус 1, а так между задачами яруса 1 вклинивается
 * максимум один залп из двух запросов.
 *
 * Сбои изолированы (принцип 3 CLAUDE.md): падение задачи по одной паре пишется
 * в журнал и не трогает остальные.
 */
@Component
@Lazy
public class RevxCollectorDaemon {

    private static final Logger log = LoggerFactory.getLogger(RevxCollectorDaemon.class);

    private final RevxConfig cfg;
    private final PairsCatalog catalog;
    private final BookCollector books;
    private final TradesCollector trades;
    private final AnomalyLog anomalies;
    private final RevxHttp http;
    private final RevxEndpoints endpoints;

    private final AtomicLong lastBookWriteMs = new AtomicLong();
    private final AtomicLong bookSnapshots = new AtomicLong();
    private final AtomicLong tradeRecords = new AtomicLong();
    private volatile boolean running;
    private volatile long lastHealthWriteMs;

    public RevxCollectorDaemon(RevxConfig cfg, PairsCatalog catalog, BookCollector books,
                               TradesCollector trades, AnomalyLog anomalies, RevxHttp http,
                               RevxEndpoints endpoints) {
        this.cfg = cfg;
        this.catalog = catalog;
        this.books = books;
        this.trades = trades;
        this.anomalies = anomalies;
        this.http = http;
        this.endpoints = endpoints;
    }

    private static final class Task implements Comparable<Task> {
        final String name;
        final long periodMs;
        final int priority;                       // меньше = важнее
        final Runnable action;
        long dueMs;

        Task(String name, long periodMs, int priority, long firstDueMs, Runnable action) {
            this.name = name;
            this.periodMs = periodMs;
            this.priority = priority;
            this.dueMs = firstDueMs;
            this.action = action;
        }

        /**
         * Сначала срок, при равном сроке — приоритет. Ярус 1 опрашивается раз в
         * 10 с, и его снимки — единственные, по которым симуляция честна: он не
         * должен ждать, пока отработает хвост из 15 редких пар.
         */
        @Override
        public int compareTo(Task other) {
            int bySchedule = Long.compare(dueMs, other.dueMs);
            return bySchedule != 0 ? bySchedule : Integer.compare(priority, other.priority);
        }
    }

    /** Разовый обход всей вселенной: книги по всем парам + сделки. Для проверки. */
    public void collectOnce() {
        List<PairsCatalog.Leg> universe = catalog.universe();
        log.info("разовый сбор: {} пар", universe.size());
        for (PairsCatalog.Leg pair : universe) {
            safely("book " + pair.quoted().symbol(), () -> {
                BookCollector.Snapshot snap = books.collect(pair);
                log.info("{}: {} ног записано, skew {} мс{}", pair.quoted().symbol(), snap.written(),
                        snap.skewMs(), snap.skewExceeded() ? " (ВЫШЕ ПОРОГА)" : "");
            });
        }
        for (PairsCatalog.Leg pair : universe) {
            safely("trades " + pair.quoted().symbol(), () -> {
                TradesCollector.Result result = trades.collect(pair.quoted());
                log.info("{}: +{} сделок ({} страниц, получено {})", pair.quoted().symbol(),
                        result.written(), result.pages(), result.fetched());
            });
        }
        writeHealth(true);
        log.info("разовый сбор завершён: {}", http.stats());
    }

    /**
     * Демон: работает, пока не остановят. Блокирует вызывающий поток.
     *
     * Задачи исполняются пулом воркеров, а не по очереди в одном потоке. Причина
     * измеренная: запрос к площадке идёт ~200 мс, и последовательный обход 23 пар
     * занимал ~9.5 с при периоде 5 с — упирались не в лимит запросов (шло 4.85
     * из разрешённых 12 req/s), а в ожидание ответов. Темп по-прежнему держит
     * общий бакет, поэтому параллельность не нарушает лимитов площадки.
     *
     * Одна и та же задача повторно не запускается: она возвращается в очередь
     * только после завершения предыдущего запуска.
     */
    public void run() {
        running = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "revx-shutdown"));

        PriorityQueue<Task> queue = buildTasks();
        int workers = endpoints.workers();
        log.info("сбор запущен: {} задач, {} воркеров, потолок {} req/s",
                queue.size(), workers, endpoints.maxRequestsPerSecond());

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            while (running) {
                Task task;
                synchronized (queue) {
                    Task next = queue.peek();
                    long wait = next == null ? 200 : next.dueMs - System.currentTimeMillis();
                    if (wait > 0) {
                        if (!waitOn(queue, Math.min(wait, 200))) {
                            break;
                        }
                        continue;
                    }
                    task = queue.poll();
                }

                long lag = System.currentTimeMillis() - task.dueMs;
                if (lag > task.periodMs / 2) {
                    // очередь не успевает: либо воркеров мало, либо периоды заданы
                    // плотнее, чем позволяет площадка — это дыра в данных, не мелочь
                    log.warn("задача {} опоздала на {} мс (период {} мс)", task.name, lag, task.periodMs);
                    if (lag > task.periodMs) {
                        anomalies.record("schedule_lag", task.name, "опоздание " + lag + " мс");
                    }
                }

                pool.submit(() -> {
                    try {
                        safely(task.name, task.action);
                    } finally {
                        synchronized (queue) {
                            task.dueMs = Math.max(System.currentTimeMillis(), task.dueMs + task.periodMs);
                            queue.add(task);
                            queue.notifyAll();
                        }
                    }
                });

                if (System.currentTimeMillis() - lastHealthWriteMs > 30_000) {
                    writeHealth(false);
                }
            }
        }
        writeHealth(false);
        log.info("сбор остановлен: {}", http.stats());
    }

    /** false = пора останавливаться. */
    private boolean waitOn(Object monitor, long ms) {
        try {
            monitor.wait(ms);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * Задачи с разнесённым стартом: без этого все пары яруса ломанулись бы
     * одновременно, упёрлись в бакет и первая же минута дала бы дыры.
     */
    private PriorityQueue<Task> buildTasks() {
        Map<String, PairSpec> specs = catalog.refresh();
        List<PairsCatalog.Leg> universe = catalog.universe(specs);
        PriorityQueue<Task> queue = new PriorityQueue<>();
        long now = System.currentTimeMillis();

        // С ключом ярусы не нужны: бюджета хватает на всю вселенную раз в 5 секунд,
        // как и просил ТЗ §3.4. Ярусы остаются запасным путём — если ключ отзовут
        // или лимиты изменятся, сбор продолжится на публичных эндпоинтах.
        if (endpoints.singleTier()) {
            long bookPeriodMs = endpoints.bookPeriodSeconds() * 1000L;
            long tradesPeriodMs = endpoints.tradesPeriodSeconds() * 1000L;
            addBookTasks(queue, universe, bookPeriodMs, now, 0);
            addTradeTasks(queue, universe, tradesPeriodMs, now + 2_000, 1);
            long refresh = cfg.pairsRefreshHours() * 3600_000L;
            queue.add(new Task("pairs", refresh, 5, now + refresh, catalog::refresh));
            log.info("единый ярус: {} пар, книги раз в {} c, сделки раз в {} c; расчётная нагрузка {} req/s",
                    universe.size(), endpoints.bookPeriodSeconds(), endpoints.tradesPeriodSeconds(),
                    Math.round((2.0 * universe.size() / endpoints.bookPeriodSeconds()
                            + (double) universe.size() / endpoints.tradesPeriodSeconds()) * 100) / 100.0);
            return queue;
        }

        List<PairsCatalog.Leg> tier1 = new ArrayList<>();
        List<PairsCatalog.Leg> tier2 = new ArrayList<>();
        List<PairsCatalog.Leg> tier3 = new ArrayList<>();
        for (PairsCatalog.Leg pair : universe) {
            if (cfg.tier1().contains(pair.base())) {
                tier1.add(pair);
            } else if (cfg.tier2().contains(pair.base())) {
                tier2.add(pair);
            } else {
                tier3.add(pair);
            }
        }

        // Группы стартуют со сдвигом, иначе на нулевой секунде разом становятся
        // должны первые задачи каждой группы и ярус 1 сразу уходит в опоздание.
        addBookTasks(queue, tier1, cfg.bookPeriodTier1Seconds() * 1000L, now, 0);
        addBookTasks(queue, tier2, cfg.bookPeriodTier2Seconds() * 1000L, now + 5_000, 2);
        addBookTasks(queue, tier3, cfg.bookPeriodTier3Seconds() * 1000L, now + 15_000, 3);
        addTradeTasks(queue, tier1, cfg.tradesPeriodTier1Seconds() * 1000L, now + 30_000, 1);
        List<PairsCatalog.Leg> rest = new ArrayList<>(tier2);
        rest.addAll(tier3);
        addTradeTasks(queue, rest, cfg.tradesPeriodOtherSeconds() * 1000L, now + 60_000, 4);

        long refreshMs = cfg.pairsRefreshHours() * 3600_000L;
        queue.add(new Task("pairs", refreshMs, 5, now + refreshMs, catalog::refresh));

        log.info("ярусы: {} пар в первом, {} во втором, {} в третьем; расчётная нагрузка {} req/s",
                tier1.size(), tier2.size(), tier3.size(),
                Math.round(estimatedRps(tier1.size(), tier2.size(), tier3.size()) * 100) / 100.0);
        return queue;
    }

    private void addBookTasks(PriorityQueue<Task> queue, List<PairsCatalog.Leg> pairs,
                              long periodMs, long now, int priority) {
        for (int i = 0; i < pairs.size(); i++) {
            PairsCatalog.Leg pair = pairs.get(i);
            long offset = periodMs * i / Math.max(1, pairs.size());
            queue.add(new Task("book " + pair.quoted().symbol(), periodMs, priority, now + offset,
                    () -> {
                        BookCollector.Snapshot snap = books.collect(pair);
                        if (snap.written() > 0) {
                            lastBookWriteMs.set(System.currentTimeMillis());
                            bookSnapshots.addAndGet(snap.written());
                        }
                    }));
        }
    }

    private void addTradeTasks(PriorityQueue<Task> queue, List<PairsCatalog.Leg> pairs,
                               long periodMs, long now, int priority) {
        for (int i = 0; i < pairs.size(); i++) {
            PairSpec spec = pairs.get(i).quoted();
            long offset = periodMs * i / Math.max(1, pairs.size());
            queue.add(new Task("trades " + spec.symbol(), periodMs, priority, now + offset,
                    () -> tradeRecords.addAndGet(trades.collect(spec).written())));
        }
    }

    private double estimatedRps(int tier1, int tier2, int tier3) {
        return 2.0 * tier1 / cfg.bookPeriodTier1Seconds()
                + 2.0 * tier2 / cfg.bookPeriodTier2Seconds()
                + 2.0 * tier3 / cfg.bookPeriodTier3Seconds()
                + (double) tier1 / cfg.tradesPeriodTier1Seconds()
                + (double) (tier2 + tier3) / cfg.tradesPeriodOtherSeconds();
    }

    /** Изоляция сбоев: одна упавшая пара не роняет сбор остальных. */
    private void safely(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("задача {} упала: {}", name, e.toString());
            anomalies.record("task_failed", name, e.toString());
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Healthcheck-файл (ТЗ §3.5): время последней успешной записи и счётчики.
     * Пишем через временный файл с атомарной заменой — иначе внешний монитор
     * однажды прочитает половину строки и решит, что сбор умер.
     */
    private void writeHealth(boolean force) {
        lastHealthWriteMs = System.currentTimeMillis();
        RevxHttp.Stats stats = http.stats();
        String text = String.join("\n",
                "now=" + Instant.ofEpochMilli(lastHealthWriteMs),
                "last_book_write=" + (lastBookWriteMs.get() == 0 ? "-"
                        : Instant.ofEpochMilli(lastBookWriteMs.get()).toString()),
                "book_legs=" + bookSnapshots.get(),
                "trades=" + tradeRecords.get(),
                "requests=" + stats.requests(),
                "http_429=" + stats.http429(),
                "failures=" + stats.failures(),
                "ratio_429=" + Math.round(stats.ratio429() * 10000) / 100.0 + "%",
                "");
        try {
            Path path = Path.of(cfg.healthFile());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (force) {
                log.warn("не записался healthcheck-файл {}: {}", cfg.healthFile(), e.getMessage());
            }
        }
    }
}
