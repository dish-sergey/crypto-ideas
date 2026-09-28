package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 🔑 ЭТАП 3б ЧИТАТЕЛЯ: {@code /orders/active} и {@code /balances} — из его снимков.
 *
 * Обёртка над клиентом площадки. Запись ({@code POST/PUT/DELETE}) и вопрос о
 * заявке идут как прежде; два списочных GET отдаются из {@code venue.db}, где
 * читатель хранит СЫРОЙ ответ площадки, — поэтому разбор у бота тот же самый, что
 * и для его собственного запроса, и чинить в двух местах нечего.
 *
 * ⚠️ Снимок годится, только если читатель НАЧАЛ его после того, как бот получил
 * ответ на свой последний запрос записи (ЧИТАТЕЛЬ-ПЛОЩАДКИ.md §3.3, «свежесть»).
 * Иначе в нём может стоять заявка, которую бот уже заменил, и сверка усыновит
 * покойника или снимет живую. Плюс предел возраста: при упавшем читателе снимок
 * стареет, и бот сам уходит на свои GET — страховка встроена в то же правило.
 */
public final class VenueReads implements Venue {

    private static final Logger log = LoggerFactory.getLogger(VenueReads.class);

    static final String ACTIVE = "orders/active";
    static final String BALANCES = "balances";
    /** Снимок заявок старше этого не берём: читатель снимает раз в секунду. */
    static final long ACTIVE_MAX_AGE_MS = 3_000L;
    /** Остатки читатель снимает раз в пять циклов. */
    static final long BALANCES_MAX_AGE_MS = 10_000L;

    private final Venue inner;
    private final String dbPath;
    private final LongSupplier clock;
    /** Когда бот получил ответ на последний свой запрос записи. */
    private volatile long lastWriteMs;

    private final AtomicLong served = new AtomicLong();
    private final AtomicLong fallback = new AtomicLong();

    public VenueReads(Venue inner, String dbPath) {
        this(inner, dbPath, System::currentTimeMillis);
    }

    VenueReads(Venue inner, String dbPath, LongSupplier clock) {
        this.inner = inner;
        this.dbPath = dbPath;
        this.clock = clock;
    }

    /** Живой клиент под обёрткой — для проверок «это живая площадка». */
    public Venue inner() {
        return inner;
    }

    @Override
    public Response activeOrders() {
        Response r = fromReader(ACTIVE, ACTIVE_MAX_AGE_MS);
        return r != null ? r : inner.activeOrders();
    }

    @Override
    public Response balances() {
        Response r = fromReader(BALANCES, BALANCES_MAX_AGE_MS);
        return r != null ? r : inner.balances();
    }

    @Override
    public Response order(String id) {
        return inner.order(id);
    }

    @Override
    public Response place(String json) {
        try {
            return inner.place(json);
        } finally {
            lastWriteMs = clock.getAsLong();
        }
    }

    @Override
    public Response replace(String id, String json) {
        try {
            return inner.replace(id, json);
        } finally {
            lastWriteMs = clock.getAsLong();
        }
    }

    @Override
    public Response cancel(String id) {
        try {
            return inner.cancel(id);
        } finally {
            lastWriteMs = clock.getAsLong();
        }
    }

    /** Сколько списочных GET отдано из снимка и сколько ушло на площадку. */
    public String stats() {
        return "из читателя " + served.get() + ", сами " + fallback.get();
    }

    private Response fromReader(String name, long maxAgeMs) {
        long now = clock.getAsLong();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT started_ms, body FROM raw_response WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long started = rs.getLong(1);
                    String body = rs.getString(2);
                    if (usable(started, lastWriteMs, now, maxAgeMs) && body != null) {
                        served.incrementAndGet();
                        return new Response(200, body, 0);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("снимок {} не прочитан: {}", name, e.toString());
        }
        fallback.incrementAndGet();
        return null;
    }

    /** Снимок начат после ответа на нашу последнюю запись и не старше предела. */
    static boolean usable(long startedMs, long lastWriteMs, long nowMs, long maxAgeMs) {
        return startedMs > lastWriteMs && nowMs - startedMs <= maxAgeMs;
    }
}
