package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/**
 * ТИШИНА В ПОКУПКАХ: сколько покупок-тейкеров на нашей ленте за последний час дошло до опоры + X б.п.
 * (18.10.2026, идея владельца «на тихом рынке нет смысла закупать весь запас — мы его не продадим»).
 *
 * <h2>Что измерено, прежде чем это писать</h2>
 *
 * Лента Revolut, окно 15.09–18.10 (33 дня, `BuyFlow` в разборе), часы по числу покупок, дошедших до
 * +10 б.п. от опоры (там стоит наш аск). После самого тихого квинтиля часов в следующий час до +10 б.п.
 * не доходит НИ ОДНОЙ покупки в 61% часов у BTC, 52% у ETH, 38% у SOL — против 1–6% после самого живого.
 * Поток держится, то есть тишина видна заранее. Направление цены при этом НЕ предсказывается (ход
 * следующего часа по квинтилям −6.6…+7.4 б.п. со скачущим знаком). Значит купленное в тишине сверх цели
 * не даёт круга, а только везёт запас — то самое «время под позицией» (A62), главная статья убытка.
 *
 * <h2>Как считает</h2>
 *
 * Принт засчитывается, если это покупка и цена ≥ опора В МОМЕНТ ПРИНТА × (1 + X): опору на время принта
 * даёт котировщик (своя история). Опора «сейчас» не годится: на падении старые принты выше нынешней
 * опоры выглядели бы живым спросом. Принты моложе задержки ленты не видны (2 с, как у сторожа свипов).
 *
 * Два режима: на стенде лента грузится в память на всё окно; живьём дочитывается из базы сборщика не
 * чаще раза в 10 с.
 */
public final class BuyQuiet implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BuyQuiet.class);
    private static final long REFRESH_MS = 10_000;

    /** Опора на момент времени — даёт котировщик. */
    public interface FairAt {
        double at(long tsMs);
    }

    private final Connection connection;
    private final String symbol;
    private final long delayMs;
    private final boolean preloaded;

    private long[] ts = new long[0];
    private double[] px = new double[0];
    private int n;
    private long lastTs = Long.MIN_VALUE;
    private long refreshedMs;                           // 0, а не MIN_VALUE: «сейчас − MIN_VALUE» переполняется

    private BuyQuiet(String dbPath, String symbol, long delayMs, boolean preloaded) {
        this.symbol = symbol;
        this.delayMs = delayMs;
        this.preloaded = preloaded;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
        } catch (Exception e) {
            throw new IllegalStateException("не открыть базу для ленты: " + dbPath, e);
        }
    }

    /** Живой режим: дочитывает ленту из базы сборщика. */
    public static BuyQuiet live(String dbPath, String symbol, long delayMs) {
        return new BuyQuiet(dbPath, symbol, delayMs, false);
    }

    /** Стенд: лента покупок за окно в памяти. */
    public static BuyQuiet preloaded(String dbPath, String symbol, long delayMs, long fromMs, long toMs) {
        BuyQuiet q = new BuyQuiet(dbPath, symbol, delayMs, true);
        q.read(fromMs, toMs);
        log.info("тишина в покупках: {} — {} покупок в памяти", symbol, q.n);
        return q;
    }

    private void read(long fromMs, long toMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ts_ms, price, side FROM revx_trade WHERE symbol = ? AND ts_ms > ? AND ts_ms <= ?"
                        + " ORDER BY ts_ms")) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String s = rs.getString(3);
                    if (s == null || !s.toLowerCase().startsWith("b")) {
                        continue;
                    }
                    long t = rs.getLong(1);
                    if (t <= lastTs) {
                        continue;
                    }
                    if (n == ts.length) {
                        int cap = Math.max(1024, n * 2);
                        ts = Arrays.copyOf(ts, cap);
                        px = Arrays.copyOf(px, cap);
                    }
                    ts[n] = t;
                    px[n] = rs.getDouble(2);
                    n++;
                    lastTs = t;
                }
            }
        } catch (Exception e) {
            log.warn("лента {} не дочиталась: {}", symbol, e.getMessage());
        }
    }

    /** Живьём: дочитать новое и выбросить старше окна (с запасом). */
    private void refresh(long nowMs, long windowMs) {
        if (preloaded || nowMs - refreshedMs < REFRESH_MS) {
            return;
        }
        refreshedMs = nowMs;
        read(lastTs == Long.MIN_VALUE ? nowMs - windowMs - delayMs - 60_000 : lastTs - 1, Long.MAX_VALUE);
        long keepFrom = nowMs - windowMs - delayMs - 60_000;
        int k = lowerBound(keepFrom);
        if (k > 0) {
            System.arraycopy(ts, k, ts, 0, n - k);
            System.arraycopy(px, k, px, 0, n - k);
            n -= k;
        }
    }

    /**
     * Сколько покупок за {@code windowMs} до {@code now − задержка} дошло до опоры (на момент принта) + {@code xBp}.
     */
    public int reached(long nowMs, long windowMs, double xBp, FairAt fair) {
        refresh(nowMs, windowMs);
        long to = nowMs - delayMs;
        long from = to - windowMs;
        int c = 0;
        for (int i = lowerBound(from + 1); i < n && ts[i] <= to; i++) {
            double f = fair.at(ts[i]);
            if (f > 0 && px[i] >= f * (1 + xBp / 1e4)) {
                c++;
            }
        }
        return c;
    }

    private int lowerBound(long t) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (ts[m] < t) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
            // только чтение
        }
    }
}
