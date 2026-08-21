package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Снимки книги. Единица работы — ПАРА ног (USDC + опорная USD): сравнивать
 * середины разнесённых во времени снимков нельзя, дрейф цены неотличим от
 * базиса (ТЗ §3.3).
 *
 * ТЗ требовало брать ноги ОДНОВРЕМЕННО со skew < 250 мс. С одного IP это
 * недостижимо: площадка ограничивает минимальный интервал между запросами
 * (~1 с) и отбивает второй запрос 429 при любой паузе между парами — замер
 * 19.08.2026. Поэтому ноги идут встык, но последовательно, и skew получается
 * около периода бакета (~1.2 с). Наведённый этим шум по ETH/BTC — порядка
 * 0.01% (секундная волатильность) против базиса 0.08% и спреда 0.24%, то есть
 * измерению не мешает, но обязан быть в отчёте, а не подразумеваться.
 *
 * Пишем оба времени каждой ноги, серверное время из metadata и skew_ms. Снимок
 * со skew выше порога не выбрасывается — сохраняется с флагом и отсеивается на
 * чтении, а доля таких снимков идёт в отчёт (ТЗ §5.1).
 */
@Component
@Lazy
public class BookCollector {

    private static final Logger log = LoggerFactory.getLogger(BookCollector.class);

    private static final String INSERT_BOOK = """
            INSERT OR REPLACE INTO revx_book(symbol, t_sent_ms, snap_id, leg, t_recv_ms, server_ts_ms,
                skew_ms, flags,
                bp1, bq1, bp2, bq2, bp3, bq3, bp4, bq4, bp5, bq5,
                ap1, aq1, ap2, aq2, ap3, aq3, ap4, aq4, ap5, aq5,
                n_bid, n_ask)
            VALUES(?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?, ?,?)
            """;

    private final RevxConfig cfg;
    private final RevxEndpoints endpoints;
    private final RevxHttp http;
    private final RevxDb db;
    private final AnomalyLog anomalies;
    private final UptimeTracker uptime;

    /** snap_id строго возрастает, чтобы две ноги одного снимка гарантированно совпали. */
    private final AtomicLong snapSeq = new AtomicLong(System.currentTimeMillis());

    /** Чередование порядка ног — см. комментарий в collect(). Атомарный: пары собираются пулом воркеров. */
    private final AtomicLong legOrderCounter = new AtomicLong();

    public BookCollector(RevxConfig cfg, RevxEndpoints endpoints, RevxHttp http, RevxDb db,
                         AnomalyLog anomalies, UptimeTracker uptime) {
        this.cfg = cfg;
        this.endpoints = endpoints;
        this.http = http;
        this.db = db;
        this.anomalies = anomalies;
        this.uptime = uptime;
    }

    /** Результат одного залпа по паре — для лога и здоровья процесса. */
    public record Snapshot(long snapId, int written, long skewMs, boolean skewExceeded) {
    }

    public Snapshot collect(PairsCatalog.Leg pair) {
        long snapId = snapSeq.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));

        // Ноги неизбежно разнесены на ~1.2 с, и если USDC всегда идти первой, то
        // при тренде базис получит систематический сдвиг в одну сторону. Порядок
        // чередуется от снимка к снимку: сдвиг остаётся, но становится шумом с
        // нулевым средним. Какая нога была первой, видно по t_sent_ms в данных.
        boolean quotedFirst = legOrderCounter.getAndIncrement() % 2 == 0;
        String quotedUrl = endpoints.book(pair.quoted().pathSymbol());
        String referenceUrl = endpoints.book(pair.reference().pathSymbol());
        List<String> urls = quotedFirst
                ? List.of(quotedUrl, referenceUrl)
                : List.of(referenceUrl, quotedUrl);
        // с ключом залпы разрешены — ноги уходят параллельно и skew падает до
        // миллисекунд, как и требует ТЗ §3.3; без ключа остаётся последовательный путь
        List<RevxHttp.Response> ordered = endpoints.authenticated()
                ? http.getParallel(urls)
                : http.getSequential(urls);

        RevxHttp.Response quoted = quotedFirst ? ordered.get(0) : ordered.get(1);
        RevxHttp.Response reference = quotedFirst ? ordered.get(1) : ordered.get(0);
        List<RevxHttp.Response> responses = List.of(quoted, reference);
        long skewMs = Math.abs(quoted.recvMs() - reference.recvMs());
        boolean skewExceeded = skewMs > endpoints.skewThresholdMs();
        if (skewExceeded) {
            anomalies.record("skew", pair.quoted().symbol(),
                    "расхождение ног " + skewMs + " мс при пороге " + endpoints.skewThresholdMs());
        }

        List<Object[]> rows = new ArrayList<>(2);
        int ok = 0;
        int http429 = 0;
        int failures = 0;
        for (int i = 0; i < 2; i++) {
            RevxHttp.Response response = responses.get(i);
            PairSpec spec = i == 0 ? pair.quoted() : pair.reference();
            String leg = i == 0 ? "usdc" : "usd";
            if (!response.ok()) {
                if (response.status() == 429) {
                    http429++;
                    anomalies.record("http_429", spec.symbol(), "книга: лимит запросов");
                } else {
                    failures++;
                    anomalies.record("http_error", spec.symbol(),
                            "книга: HTTP " + response.status()
                                    + (response.error() == null ? "" : " " + response.error()));
                }
                continue;
            }
            ok++;
            Object[] row = row(spec.symbol(), leg, snapId, response, skewMs, skewExceeded);
            if (row != null) {
                rows.add(row);
            }
        }

        db.batch(INSERT_BOOK, rows);
        uptime.record("book", 2, ok, http429, failures, rows.size());
        return new Snapshot(snapId, rows.size(), skewMs, skewExceeded);
    }

    /** null = снимок отбракован (перекрещенная или пустая книга) — ТЗ §3.2. */
    private Object[] row(String symbol, String leg, long snapId, RevxHttp.Response response,
                         long skewMs, boolean skewExceeded) {
        BookParser.Book book;
        try {
            book = BookParser.parse(response.body(), cfg.bookDepth());
        } catch (RuntimeException e) {
            anomalies.record("book_parse", symbol, e.getMessage());
            return null;
        }
        if (!book.usable()) {
            // тело в журнал: без него «пустая сторона» неотличима от смены схемы
            // ответа, а именно на этом мы уже один раз потеряли час (19.08.2026)
            anomalies.record(BookFlags.has(book.flags(), BookFlags.CROSSED) ? "crossed_book" : "empty_side",
                    symbol, "снимок пропущен: " + BookFlags.describe(book.flags())
                            + " | ответ: " + truncate(response.body()));
            return null;
        }
        int flags = book.flags() | (skewExceeded ? BookFlags.SKEW_EXCEEDED : 0);
        if (log.isDebugEnabled()) {
            log.debug("{} {} спред {}%, флаги [{}]", symbol, leg,
                    Math.round(book.relativeSpread() * 1e6) / 1e4, BookFlags.describe(flags));
        }

        Object[] row = new Object[30];
        row[0] = symbol;
        row[1] = response.sentMs();
        row[2] = snapId;
        row[3] = leg;
        row[4] = response.recvMs();          // = available_at
        row[5] = book.serverTsMs();
        row[6] = skewMs;
        row[7] = flags;
        fillSide(row, 8, book.bids());
        fillSide(row, 18, book.asks());
        row[28] = book.bids().size();
        row[29] = book.asks().size();
        return row;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "пусто";
        }
        return body.length() <= 220 ? body : body.substring(0, 220) + "…";
    }

    /** Пять уровней стороны в десять колонок; недостающие остаются NULL. */
    private void fillSide(Object[] row, int offset, List<BookParser.Level> levels) {
        for (int i = 0; i < 5; i++) {
            if (i < levels.size()) {
                row[offset + i * 2] = levels.get(i).price();
                row[offset + i * 2 + 1] = levels.get(i).qty();
            }
        }
    }
}
