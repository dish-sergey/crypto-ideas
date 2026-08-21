package org.home.data.revx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Лента сделок с пагинацией по cursor (ТЗ §3.2: без неё поток систематически
 * занижается — limit упирается в 100).
 *
 * Идём страницами назад по времени, пока не упрёмся в уже известную сделку.
 * Отсюда же и «докачка пропущенного окна после простоя» из ТЗ §3.5: после
 * перезапуска граница — последняя записанная сделка, а не текущий момент.
 * Запись идемпотентна по trade_id, ingest_ms первой записи не перетирается:
 * available_at — это когда данные стали доступны НАМ, и переписывать его
 * повторным заходом нельзя.
 */
@Component
@Lazy
public class TradesCollector {

    private static final Logger log = LoggerFactory.getLogger(TradesCollector.class);

    private static final String INSERT_TRADE = """
            INSERT OR IGNORE INTO revx_trade(trade_id, symbol, ts_ms, price, qty, side, ingest_ms)
            VALUES(?,?,?,?,?,?,?)
            """;

    private final RevxConfig cfg;
    private final RevxEndpoints endpoints;
    private final RevxHttp http;
    private final RevxDb db;
    private final AnomalyLog anomalies;
    private final UptimeTracker uptime;

    public TradesCollector(RevxConfig cfg, RevxEndpoints endpoints, RevxHttp http, RevxDb db,
                           AnomalyLog anomalies, UptimeTracker uptime) {
        this.cfg = cfg;
        this.endpoints = endpoints;
        this.http = http;
        this.db = db;
        this.anomalies = anomalies;
        this.uptime = uptime;
    }

    public record Result(int pages, int fetched, int written, boolean reachedKnown) {
    }

    /** Незаконченная докачка вглубь: откуда продолжать и до какой границы идти. */
    private record Backfill(String cursor, long floorMs) {
    }

    private final java.util.Map<String, Backfill> backfills = new java.util.concurrent.ConcurrentHashMap<>();

    public Result collect(PairSpec pair) {
        String symbol = pair.symbol();

        // Голова ленты берётся всегда, а незаконченная докачка вглубь продолжается
        // с сохранённого курсора. Без этого один заход мог занять 20 запросов подряд
        // и на полминуты выжрать весь бюджет — ярус 1 при этом опаздывал на 17 с
        // (наблюдалось на ARM 19.08.2026). Теперь длинная докачка размазана по заходам.
        Backfill pending = backfills.get(symbol);
        String cursor = pending != null ? pending.cursor() : null;
        long floorMs;
        if (pending != null) {
            floorMs = pending.floorMs();
        } else {
            Long lastKnownTs = db.queryLong("SELECT MAX(ts_ms) FROM revx_trade WHERE symbol=?", symbol);
            floorMs = lastKnownTs != null
                    ? lastKnownTs
                    : System.currentTimeMillis() - cfg.tradesBackfillMaxHours() * 3600_000L;
        }

        // INSERT OR IGNORE не сообщает, сколько строк реально легло, а нам нужен
        // именно прирост: перекрытие страниц с уже записанным — норма, а не работа
        long changesBefore = changes();
        int pages = 0;
        int fetched = 0;
        int ok = 0;
        int http429 = 0;
        int failures = 0;
        boolean reachedKnown = false;

        while (pages < cfg.tradesPagesPerRun()) {
            RevxHttp.Response response = http.get(endpoints.trades(pair.pathSymbol(), cursor));
            pages++;
            if (!response.ok()) {
                if (response.status() == 429) {
                    http429++;
                    anomalies.record("http_429", symbol, "сделки: лимит запросов");
                } else {
                    failures++;
                    anomalies.record("http_error", symbol,
                            "сделки: HTTP " + response.status()
                                    + (response.error() == null ? "" : " " + response.error()));
                }
                break;
            }
            ok++;

            TradesParser.Page page;
            try {
                page = TradesParser.parse(response.body());
            } catch (RuntimeException e) {
                failures++;
                anomalies.record("trades_parse", symbol, e.getMessage());
                break;
            }
            fetched += page.trades().size();
            write(symbol, page.trades(), response.recvMs());

            if (page.empty() || page.nextCursor() == null) {
                reachedKnown = true;                 // лента кончилась — дыр не осталось
                break;
            }
            if (page.oldestTsMs() <= floorMs) {
                reachedKnown = true;                 // догнали уже записанное
                break;
            }
            cursor = page.nextCursor();
        }

        int written = (int) (changes() - changesBefore);
        if (reachedKnown) {
            backfills.remove(symbol);
        } else if (cursor != null) {
            // не дошли до известного — продолжим с этого места в следующий заход,
            // сохранив исходную границу, иначе докачка никогда не закроет окно
            backfills.put(symbol, new Backfill(cursor, floorMs));
            log.debug("{}: докачка продолжится с курсора (граница {})", symbol, floorMs);
        }
        uptime.record("trades", pages, ok, http429, failures, written);
        if (written > 0) {
            log.debug("{}: +{} сделок за {} страниц", symbol, written, pages);
        }
        return new Result(pages, fetched, written, reachedKnown);
    }

    /**
     * Сколько строк реально легло: INSERT OR IGNORE об этом не сообщает, а
     * COUNT(*) по символу дорожал бы с ростом таблицы. total_changes() —
     * счётчик соединения, а сбор однопоточный, так что чужих записей между
     * двумя замерами не появится.
     */
    private long changes() {
        Long v = db.queryLong("SELECT total_changes()");
        return v == null ? 0 : v;
    }

    private int write(String symbol, List<TradesParser.Trade> trades, long ingestMs) {
        List<Object[]> rows = new ArrayList<>(trades.size());
        for (TradesParser.Trade t : trades) {
            rows.add(new Object[]{t.id(), symbol, t.tsMs(), t.price(), t.qty(), t.side(), ingestMs});
        }
        return db.batch(INSERT_TRADE, rows);
    }
}
