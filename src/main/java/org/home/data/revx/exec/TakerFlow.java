package org.home.data.revx.exec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Тейкерский поток по сторонам из ленты (док. 221 §5б): сколько $ встречные тейкеры напечатали за окно. Нужен
 * «стене от потока» — порогу стены в минутах встречного потока скользящего часа.
 *
 * Стенд ({@link #preloaded}): лента всего окна в памяти, накопленные суммы по сторонам. Живьём ({@link #live},
 * 01.11.2026): раз в {@code refreshMs} перечитывается последний час с запасом из базы сборщика — та же формула окна.
 * Задержки живьём нет: в базе лента и так появляется через ~2 с после сделки (A83), стенд моделирует ровно это.
 *⚠️ Видны лишь принты старше
 * {@code delayMs} (живой бот узнаёт ленту с задержкой ~2 с, A83) — без этого стенд знал бы поток из будущего.
 */
public final class TakerFlow {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TakerFlow.class);
    /** живьём держим столько ленты: окно функции — час, плюс запас */
    static final long LIVE_KEEP_MS = 3_600_000L + 120_000L;

    private final long delayMs;
    private String liveDb;
    private String liveSymbol;
    private long refreshMs;
    private long loadedMs = Long.MIN_VALUE;
    private long failedSinceMs;
    private Connection liveConn;
    private long[] ts = new long[0];
    private double[][] cum = new double[2][1];   // [0] — продажи тейкеров (по нашему биду), [1] — покупки (по аску)

    private TakerFlow(long delayMs) {
        this.delayMs = delayMs;
    }

    public static TakerFlow preloaded(String dbPath, String symbol, long delayMs, long fromMs, long toMs) {
        TakerFlow f = new TakerFlow(delayMs);
        java.util.List<long[]> t = new java.util.ArrayList<>();
        java.util.List<Double> v = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = ? AND ts_ms >= ? AND ts_ms <= ? ORDER BY ts_ms")) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String s = rs.getString(4);
                    if (s == null) {
                        continue;
                    }
                    t.add(new long[]{rs.getLong(1), s.toLowerCase().startsWith("b") ? 1 : 0});
                    v.add(rs.getDouble(2) * rs.getDouble(3));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("не прочиталась лента " + symbol, e);
        }
        f.fill(t, v);
        return f;
    }

    /** Живьём: лента из базы сборщика ({@code revx_trade}), перечитывается не чаще {@code refreshMs}. */
    public static TakerFlow live(String dbPath, String symbol, long refreshMs) {
        TakerFlow f = new TakerFlow(0);
        f.liveDb = dbPath;
        f.liveSymbol = symbol;
        f.refreshMs = refreshMs;
        return f;
    }

    /** Когда лента последний раз прочиталась (живьём); {@code Long.MIN_VALUE} — ещё ни разу. */
    public long loadedMs() {
        return loadedMs;
    }

    private void refresh(long nowMs) {
        if (liveDb == null || (loadedMs != Long.MIN_VALUE && nowMs - loadedMs < refreshMs)
                || (failedSinceMs > 0 && nowMs - failedSinceMs < refreshMs)) {
            return;
        }
        try {
            if (liveConn == null) {
                liveConn = DriverManager.getConnection("jdbc:sqlite:file:" + liveDb + "?mode=ro");
            }
            java.util.List<long[]> t = new java.util.ArrayList<>();
            java.util.List<Double> v = new java.util.ArrayList<>();
            try (PreparedStatement ps = liveConn.prepareStatement(
                    "SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = ? AND ts_ms >= ? ORDER BY ts_ms")) {
                ps.setString(1, liveSymbol);
                ps.setLong(2, nowMs - LIVE_KEEP_MS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String s = rs.getString(4);
                        if (s == null) {
                            continue;
                        }
                        t.add(new long[]{rs.getLong(1), s.toLowerCase().startsWith("b") ? 1 : 0});
                        v.add(rs.getDouble(2) * rs.getDouble(3));
                    }
                }
            }
            fill(t, v);
            loadedMs = nowMs;
            failedSinceMs = 0;
        } catch (Exception e) {
            // Остаётся прежняя лента; повтор не чаще refreshMs. Свежесть видна по loadedMs().
            if (failedSinceMs == 0) {
                log.warn("лента {} не прочиталась: {}", liveSymbol, e.getMessage());
            }
            failedSinceMs = nowMs;
            try {
                if (liveConn != null) {
                    liveConn.close();
                }
            } catch (Exception ignored) {
            }
            liveConn = null;
        }
    }

    private void fill(java.util.List<long[]> t, java.util.List<Double> v) {
        int n = t.size();
        long[] nts = new long[n];
        double[][] ncum = new double[2][n + 1];
        for (int i = 0; i < n; i++) {
            nts[i] = t.get(i)[0];
            for (int s = 0; s < 2; s++) {
                ncum[s][i + 1] = ncum[s][i] + (t.get(i)[1] == s ? v.get(i) : 0);
            }
        }
        ts = nts;
        cum = ncum;
    }

    /** $ тейкеров стороны ({@code takerBuys} — покупки) за [now − window, now − delay). */
    public double usd(boolean takerBuys, long nowMs, long windowMs) {
        refresh(nowMs);
        int s = takerBuys ? 1 : 0;
        long hi = nowMs - delayMs;
        return cum[s][lowerBound(hi)] - cum[s][lowerBound(hi - windowMs)];
    }

    private int lowerBound(long key) {
        int lo = 0;
        int hi = ts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
