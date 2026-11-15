package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * {@code --revx-liq-state}: ПОТОК ЛИКВИДАЦИЙ ДЛЯ ЖИВЫХ БОТОВ (13.11.2026, док. 246 §3). Служба на micro, рядом со
 * сборщиком ликвидаций: раз в {@code period-ms} (3 с) читает {@code liquidations} в {@code crypto.db} и переписывает
 * одну строку {@code время_мс;сумма_$;порог_$} в файл {@code out} (через временный файл и переименование — читатель не
 * увидит половину строки).
 * <p>
 * Сумма — как у стенда ({@code d277/liqst/LiqState.java}): лонги Bybit (сторона BUY) и OKX (SELL, контракт 0.01 BTC /
 * 0.1 ETH / 1 SOL), без Binance, BTC + ETH + SOL вместе, по времени получения ({@code available_at}) за последние
 * {@code window-sec} (300 с). Порог — уровень, выше которого сумма лежала {@code share}% (1.6) времени за прошлые
 * {@code days} (30) суток; пересчёт раз в сутки (и на старте). До 7 суток истории или при сбое пересчёта — постоянный
 * {@code fixed-usd} ($1 000 000). Файл бот читает сам (revx.exec.liq-live-file); доставка на bot-arm — отдельно.
 */
public final class LiqStateWriter {
    private static final Logger log = LoggerFactory.getLogger(LiqStateWriter.class);

    private final String db;
    private final Path out;
    private final long periodMs, windowMs;
    private final double share, fixedUsd;
    private final int days;
    private volatile boolean stop;
    private double threshold;
    private long thresholdAt = Long.MIN_VALUE;

    public LiqStateWriter(String db, String out, long periodMs, long windowSec, double share, int days, double fixedUsd) {
        this.db = db;
        this.out = Path.of(out);
        this.periodMs = periodMs;
        this.windowMs = windowSec * 1000L;
        this.share = share;
        this.days = days;
        this.fixedUsd = fixedUsd;
        this.threshold = fixedUsd;
    }

    public void stop() {
        stop = true;
    }

    public void run() {
        log.info("поток ликвидаций: {} → {}, окно {} с, доля {}%, {} сут.", db, out, windowMs / 1000, share, days);
        while (!stop) {
            long now = System.currentTimeMillis();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                c.createStatement().execute("PRAGMA busy_timeout = 2000");
                if (now - thresholdAt > 86_400_000L) {
                    thresholdAt = now;
                    try {
                        double t = relativeThreshold(c, now);
                        if (t > 0) {
                            log.info("порог ликвидаций: ${} (уровень, выше которого {}% времени за {} сут.)", String.format(Locale.ROOT, "%.0f", t), share, days);
                            threshold = t;
                        }
                    } catch (Exception e) {
                        log.warn("порог не пересчитан ({}), остаётся ${}", e.toString(), String.format(Locale.ROOT, "%.0f", threshold));
                    }
                }
                double sum = sumLongs(c, now - windowMs, now);
                write(now, sum, threshold);
            } catch (Exception e) {
                log.warn("поток ликвидаций: шаг не прошёл ({})", e.toString());
            }
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void write(long now, double sum, double thr) throws Exception {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, String.format(Locale.ROOT, "%d;%.0f;%.0f%n", now, sum, thr), StandardCharsets.UTF_8);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static final String Q = "select exchange, symbol, side, price, qty, available_at from liquidations "
            + "where available_at > ? and available_at <= ? and exchange in ('bybit','okx') "
            + "and (symbol like 'BTC%' or symbol like 'ETH%' or symbol like 'SOL%')";

    /** Номинал ликвидации лонга, $; 0 — не лонг или не та биржа. Тот же разбор, что LiqState стенда. */
    static double longUsd(String ex, String sym, String side, double px, double qty) {
        boolean lng = ex.equals("bybit") ? side.equals("BUY") : ex.equals("okx") && side.equals("SELL");
        if (!lng) {
            return 0;
        }
        double ct = ex.equals("okx") ? (sym.startsWith("BTC") ? 0.01 : sym.startsWith("ETH") ? 0.1 : 1) : 1;
        return px * qty * ct;
    }

    static double sumLongs(Connection c, long from, long to) throws Exception {
        double s = 0;
        try (PreparedStatement ps = c.prepareStatement(Q)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    s += longUsd(r.getString(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5));
                }
            }
        }
        return s;
    }

    /** Порог по истории: доля времени, когда сумма за окно ≥ X, равна share%. */
    double relativeThreshold(Connection c, long now) throws Exception {
        long from = now - days * 86_400_000L;
        List<long[]> ev = new ArrayList<>();
        List<Double> val = new ArrayList<>();
        long first = Long.MAX_VALUE;
        try (PreparedStatement ps = c.prepareStatement(Q)) {
            ps.setLong(1, from - windowMs);
            ps.setLong(2, now);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    double v = longUsd(r.getString(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5));
                    if (v > 0) {
                        long t = r.getLong(6);
                        ev.add(new long[]{t});
                        val.add(v);
                        first = Math.min(first, t);
                    }
                }
            }
        }
        if (ev.isEmpty() || now - first < 7 * 86_400_000L) {
            log.info("порог: истории меньше 7 суток — постоянный ${}", String.format(Locale.ROOT, "%.0f", fixedUsd));
            return fixedUsd;
        }
        long[] t = new long[ev.size()];
        double[] v = new double[ev.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ev.get(i)[0];
            v[i] = val.get(i);
        }
        return thresholdOf(t, v, Math.max(from, first), now, windowMs, share);
    }

    /**
     * Уровень X, при котором сумма за окно ≥ X занимает share% времени на [from, to). Сумма ступенчатая: меняется в
     * моменты t (+v) и t + окно (−v); время каждой ступени копится к её значению, затем ищется квантиль по времени.
     */
    static double thresholdOf(long[] t, double[] v, long from, long to, long windowMs, double sharePct) {
        TreeMap<Long, Double> d = new TreeMap<>();
        for (int i = 0; i < t.length; i++) {
            d.merge(t[i], v[i], Double::sum);
            d.merge(t[i] + windowMs, -v[i], Double::sum);
        }
        List<double[]> steps = new ArrayList<>();   // значение, длительность
        double s = 0;
        long prev = from;
        for (var e : d.entrySet()) {
            long k = e.getKey();
            if (k > from) {
                long a = Math.max(prev, from), b = Math.min(k, to);
                if (b > a) {
                    steps.add(new double[]{s, b - a});
                }
                prev = k;
            }
            s += e.getValue();
            if (k >= to) {
                break;
            }
        }
        if (prev < to) {
            steps.add(new double[]{s, to - prev});
        }
        double total = steps.stream().mapToDouble(x -> x[1]).sum();
        double[][] st = steps.toArray(new double[0][]);
        Arrays.sort(st, (x, y) -> Double.compare(y[0], x[0]));
        double acc = 0, want = total * sharePct / 100;
        for (double[] x : st) {
            acc += x[1];
            if (acc >= want) {
                return Math.max(1, x[0]);
            }
        }
        return st.length > 0 ? st[st.length - 1][0] : 0;
    }
}
