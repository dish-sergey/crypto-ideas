package org.home.data.revx.sim;

import org.home.data.revx.BookFlags;
import org.home.data.revx.RevxConfig;
import org.home.data.revx.RevxDb;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Чтение парных снимков книги из revx_book: две ноги одного snap_id собираются
 * в {@link PairQuote}.
 *
 * Два правила, которые здесь важнее скорости:
 *
 *  1. Пара считается известной по ПОЗДНЕЙ ноге (MAX(t_recv_ms)). Взять раннюю
 *     значило бы объявить снимок доступным до того, как пришла вторая половина —
 *     то есть подглядывать в будущее, что ТЗ §4.6 п.4 прямо запрещает.
 *  2. Снимок без обеих ног не используется вовсе: одна нога не даёт базиса.
 *     Отбракованные коллектором книги (перекрещенные, пустые) сюда не попадают
 *     физически — их не записывали.
 */
@Component
@Lazy
public class SnapshotReader {

    private static final String SELECT_PAIRS = """
            SELECT snap_id,
                   MAX(CASE WHEN leg='usdc' THEN symbol END)                          AS usdc_symbol,
                   MAX(CASE WHEN leg='usdc' THEN (ap1 + bp1) / 2.0 END)               AS mid_usdc,
                   MAX(CASE WHEN leg='usd'  THEN (ap1 + bp1) / 2.0 END)               AS mid_usd,
                   MAX(CASE WHEN leg='usdc' THEN (ap1 - bp1) / ((ap1 + bp1) / 2.0) END) AS spread_usdc,
                   MAX(CASE WHEN leg='usd'  THEN (ap1 - bp1) / ((ap1 + bp1) / 2.0) END) AS spread_usd,
                   MAX(CASE WHEN leg='usdc' THEN flags END)                           AS flags_usdc,
                   MAX(CASE WHEN leg='usd'  THEN flags END)                           AS flags_usd,
                   MAX(skew_ms)                                                       AS skew_ms,
                   MAX(t_recv_ms)                                                     AS available_at,
                   COUNT(*)                                                           AS legs
            FROM revx_book
            WHERE t_recv_ms BETWEEN ? AND ?
            GROUP BY snap_id
            HAVING legs = 2 AND mid_usdc IS NOT NULL AND mid_usd IS NOT NULL
            ORDER BY available_at
            """;

    private final RevxDb db;
    private final RevxConfig cfg;

    public SnapshotReader(RevxDb db, RevxConfig cfg) {
        this.db = db;
        this.cfg = cfg;
    }

    /** Сколько снимков отброшено и почему — это поля раздела «Данные» отчёта (ТЗ §5.1). */
    public record Stats(int pairsTotal, int droppedBySkew, int droppedByFlags) {
    }

    public record Window(List<PairQuote> quotes, Stats stats) {
    }

    /**
     * Парные снимки за интервал. {@code maxSkewMs} отсекает снимки, у которых
     * ноги разъехались: их держат в базе с флагом, но базис по ним не считают.
     */
    public Window read(long fromMs, long toMs, long maxSkewMs) {
        Set<String> memecoins = new HashSet<>(cfg.memecoins());
        List<PairQuote> quotes = new ArrayList<>();
        int[] droppedBySkew = {0};
        int[] droppedByFlags = {0};

        List<PairQuote> rows = db.query(SELECT_PAIRS, rs -> {
            String usdcSymbol = rs.getString("usdc_symbol");
            String base = usdcSymbol.substring(0, usdcSymbol.indexOf('/'));
            int flags = rs.getInt("flags_usdc") | rs.getInt("flags_usd");
            long skew = rs.getLong("skew_ms");
            if (BookFlags.has(flags, BookFlags.CROSSED) || BookFlags.has(flags, BookFlags.EMPTY_SIDE)) {
                droppedByFlags[0]++;
                return null;
            }
            if (skew > maxSkewMs) {
                droppedBySkew[0]++;
                return null;
            }
            return new PairQuote(base,
                    rs.getDouble("mid_usdc"), rs.getDouble("mid_usd"),
                    rs.getDouble("spread_usdc"), rs.getDouble("spread_usd"),
                    memecoins.contains(base),
                    rs.getLong("available_at"));
        }, fromMs, toMs);

        int total = rows.size();
        for (PairQuote q : rows) {
            if (q != null) {
                quotes.add(q);
            }
        }
        return new Window(quotes, new Stats(total, droppedBySkew[0], droppedByFlags[0]));
    }

    /**
     * Сколько раз площадка отдала непригодную книгу, по видам и по базовому активу.
     *
     * Это не шум логов, а свойство источника: за 12 ч 20.08.2026 — 424 полностью
     * пустых ответа `{"asks":[],"bids":[]}` (0.7–2.7% опросов, тонкие пары),
     * 3 перекрещенные книги, 36 разъехавшихся снимков. Такие ответы в базу не
     * попадают, поэтому без этого счётчика они исчезли бы из отчёта совсем.
     */
    public Map<String, Integer> anomalyCountsByBase(long fromMs, long toMs, String kind) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        db.query("""
                SELECT symbol, COUNT(*) AS n FROM revx_anomaly
                WHERE kind = ? AND ts_ms BETWEEN ? AND ?
                GROUP BY symbol
                """, rs -> {
            String symbol = rs.getString("symbol");
            int slash = symbol.indexOf('/');
            if (slash > 0) {
                counts.merge(symbol.substring(0, slash), rs.getInt("n"), Integer::sum);
            }
            return symbol;
        }, kind, fromMs, toMs);
        return counts;
    }

    public int anomalyTotal(long fromMs, long toMs, String kind) {
        Long v = db.queryLong(
                "SELECT COUNT(*) FROM revx_anomaly WHERE kind = ? AND ts_ms BETWEEN ? AND ?",
                kind, fromMs, toMs);
        return v == null ? 0 : v.intValue();
    }

    /**
     * Разбиение на моменты времени: в пределах корзины берём по каждой паре
     * последний снимок. Курс считается по срезу рынка, а не по разнесённым во
     * времени котировкам разных пар.
     */
    public static List<Map.Entry<Long, List<PairQuote>>> bucket(List<PairQuote> quotes, long bucketMs) {
        TreeMap<Long, Map<String, PairQuote>> byBucket = new TreeMap<>();
        for (PairQuote q : quotes) {
            long bucket = q.availableAtMs() / bucketMs * bucketMs;
            byBucket.computeIfAbsent(bucket, k -> new LinkedHashMap<>())
                    .merge(q.base(), q, (older, newer) ->
                            newer.availableAtMs() >= older.availableAtMs() ? newer : older);
        }
        List<Map.Entry<Long, List<PairQuote>>> out = new ArrayList<>(byBucket.size());
        byBucket.forEach((bucket, byBase) -> out.add(Map.entry(bucket, List.copyOf(byBase.values()))));
        return out;
    }
}
