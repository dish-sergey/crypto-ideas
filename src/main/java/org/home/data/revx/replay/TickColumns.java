package org.home.data.revx.replay;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.RandomAccess;

/**
 * РЯД ТИКОВ СТОЛБЦАМИ (12.11.2026, задача владельца: окна в полгода). Прежде ряд опоры жил списком
 * объектов {@link ReplayFair.Tick}: ~100 байт на тик с упакованными {@code Double} бида и аска, то есть
 * ~1.6 ГБ кучи на монету за полгода посекундных тиков. Здесь каждое поле — свой примитивный массив, а
 * столбцы, которые у ряда опоры почти всегда пустые (бид, аск, запас, давление), заводятся только
 * при первом непустом значении. Ряд стенда так весит ~19 байт на тик.
 *
 * Снаружи это тот же {@code List<Tick>}: {@link #get} собирает запись на лету, значения — те же до бита
 * (в столбцах лежат те же {@code double}). Поддерживаются {@link #add} и {@link #set}; удалять нельзя.
 */
public final class TickColumns extends AbstractList<ReplayFair.Tick> implements RandomAccess {
    private int n;
    private long[] ts;
    private double[] fair;
    private byte[] flags;          // 1 — бид есть, 2 — аск есть, 4 — котировать можно
    private double[] bid, ask, inv, pressure;   // null, пока все значения по умолчанию
    private short[] reason;        // индекс в reasons, −1 — нет причины
    private final Map<String, Short> reasonIdx = new HashMap<>();
    private String[] reasons = new String[16];

    public TickColumns(int capacity) {
        int c = Math.max(16, capacity);
        ts = new long[c];
        fair = new double[c];
        flags = new byte[c];
        reason = new short[c];
    }

    public TickColumns() {
        this(1024);
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public ReplayFair.Tick get(int i) {
        if (i < 0 || i >= n) {
            throw new IndexOutOfBoundsException(i);
        }
        int f = flags[i];
        return new ReplayFair.Tick(ts[i], fair[i],
                (f & 1) != 0 ? bid[i] : null, (f & 2) != 0 ? ask[i] : null,
                inv == null ? 0 : inv[i], (f & 4) != 0,
                reason[i] < 0 ? null : reasons[reason[i]], pressure == null ? 0 : pressure[i]);
    }

    /** Момент тика без сборки записи (для расписаний и поиска окна). */
    public long tsAt(int i) {
        return ts[i];
    }

    @Override
    public boolean add(ReplayFair.Tick t) {
        if (n == ts.length) {
            int c = ts.length + (ts.length >> 1) + 16;
            ts = Arrays.copyOf(ts, c);
            fair = Arrays.copyOf(fair, c);
            flags = Arrays.copyOf(flags, c);
            reason = Arrays.copyOf(reason, c);
            if (bid != null) bid = Arrays.copyOf(bid, c);
            if (ask != null) ask = Arrays.copyOf(ask, c);
            if (inv != null) inv = Arrays.copyOf(inv, c);
            if (pressure != null) pressure = Arrays.copyOf(pressure, c);
        }
        n++;
        put(n - 1, t);
        modCount++;
        return true;
    }

    @Override
    public ReplayFair.Tick set(int i, ReplayFair.Tick t) {
        ReplayFair.Tick old = get(i);
        put(i, t);
        return old;
    }

    private void put(int i, ReplayFair.Tick t) {
        ts[i] = t.tsMs();
        fair[i] = t.fair();
        int f = (t.quotable() ? 4 : 0);
        if (t.bid() != null) {
            if (bid == null) bid = new double[ts.length];
            bid[i] = t.bid();
            f |= 1;
        }
        if (t.ask() != null) {
            if (ask == null) ask = new double[ts.length];
            ask[i] = t.ask();
            f |= 2;
        }
        flags[i] = (byte) f;
        // 0.0 и −0.0 различаются: столбец заводится на любое значение, отличное от +0.0 по битам
        if (Double.doubleToRawLongBits(t.inventory()) != 0 || inv != null) {
            if (inv == null) inv = new double[ts.length];
            inv[i] = t.inventory();
        }
        if (Double.doubleToRawLongBits(t.pressure()) != 0 || pressure != null) {
            if (pressure == null) pressure = new double[ts.length];
            pressure[i] = t.pressure();
        }
        reason[i] = t.reason() == null ? -1 : code(t.reason());
    }

    private short code(String r) {
        Short c = reasonIdx.get(r);
        if (c != null) {
            return c;
        }
        int k = reasonIdx.size();
        if (k >= Short.MAX_VALUE) {
            throw new IllegalStateException("слишком много разных причин паузы в ряду тиков");
        }
        if (k == reasons.length) {
            reasons = Arrays.copyOf(reasons, k * 2);
        }
        reasons[k] = r;
        reasonIdx.put(r, (short) k);
        return (short) k;
    }

    /** Индекс первого тика с моментом ≥ t. */
    public int lowerBound(long t) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (ts[m] < t) lo = m + 1; else hi = m;
        }
        return lo;
    }
}
