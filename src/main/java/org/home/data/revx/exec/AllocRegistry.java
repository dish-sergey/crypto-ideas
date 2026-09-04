package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Реестр владения инвентарём: кто из ботов сколько держит на общем счёте.
 *
 * <h2>Зачем</h2>
 *
 * У Revolut X один счёт на всех, и разделения он не даёт — проверено 04.09.2026:
 * адресов вида {@code /accounts}, {@code /subaccounts} нет вовсе (несуществующий
 * адрес отвечает тем же {@code 401}, что и они), а в ответах нет измерения
 * «счёт»: ни в остатках, ни в заявке. Значит разделять приходится нам.
 *
 * До реестра это стоило реального убытка: бот A продавал биткойн, купленный B и
 * C, весь день 03.09 простоял в шорте на 7–13 лотов и прошёл в нём ралли
 * 77 000 → 81 400.
 *
 * <h2>Главное правило: захват ТОЛЬКО при инициализации</h2>
 *
 * Во время работы инвентарь бота меняется исключительно его собственными
 * сделками. Иначе теряется смысл проверки логики: нельзя отличить «продал
 * купленное» от «продал найденное», и любое измерение захвата, κ и markout
 * перестаёт что-либо значить.
 *
 * <h2>Реестр — КЭШ, а не первоисточник</h2>
 *
 * Первоисточник — журнал каждого бота: {@code позиция = затравка + Σ своих
 * филлов}. Потеря этого файла лечится пересборкой из журналов, а не руками.
 *
 * <h2>Аренда</h2>
 *
 * Претензия жива, пока сердцебиение моложе {@link #LEASE_MS}. Сердцебиение
 * продлевается, пока ЖИВ ПРОЦЕСС, независимо от того, котирует бот или нет:
 * {@code /stop} на час претензию не теряет, а убитый по SIGKILL процесс теряет
 * её через пять минут. Без аренды инвентарь мёртвого бота остался бы за ним
 * навсегда.
 *
 * <b>Истёкшая претензия не стирается сама.</b> Она распускается только в тот
 * момент, когда кто-то приходит забирать, — и тогда же записывается событие с
 * ценой. Если никто не пришёл, вернувшийся хозяин восстанавливает позицию из
 * своего журнала, и передачи не происходит: монеты никуда не двигались.
 */
public final class AllocRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AllocRegistry.class);

    /** Сколько живёт претензия без продления. Переживает деплой, не переживает падение. */
    public static final long LEASE_MS = 5 * 60_000L;

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS claim (
                bot_id       TEXT    NOT NULL,
                currency     TEXT    NOT NULL,
                qty          REAL    NOT NULL,
                since_ms     INTEGER NOT NULL,
                heartbeat_ms INTEGER NOT NULL,
                PRIMARY KEY (bot_id, currency)
            );
            CREATE TABLE IF NOT EXISTS claim_event (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms    INTEGER NOT NULL,
                bot_id   TEXT    NOT NULL,
                currency TEXT    NOT NULL,
                qty      REAL    NOT NULL,
                kind     TEXT    NOT NULL,
                price    REAL,
                detail   TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_claim_event_bot ON claim_event(bot_id, ts_ms);
            """;

    /** Строка реестра. {@code live} — жива ли аренда на момент чтения. */
    public record Claim(String botId, String currency, double qty,
                        long sinceMs, long heartbeatMs, boolean live) {
    }

    /** Что бот увидит перед захватом. Все величины в монетах. */
    public record Free(double venueTotal, double claimedLive, double claimedExpired,
                       double free) {
    }

    private final Connection connection;

    public AllocRegistry(String path) {
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                org.home.data.core.Db.applySchema(connection, SCHEMA);
            }
            log.info("реестр владения инвентарём: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть реестр владения " + path, e);
        }
    }

    /** Все претензии по валюте, с отметкой живости. */
    public synchronized List<Claim> claims(String currency, long nowMs) {
        List<Claim> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bot_id, qty, since_ms, heartbeat_ms FROM claim "
                        + "WHERE currency = ? ORDER BY bot_id")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long hb = rs.getLong(4);
                    out.add(new Claim(rs.getString(1), currency, rs.getDouble(2),
                            rs.getLong(3), hb, nowMs - hb < LEASE_MS));
                }
            }
        } catch (Exception e) {
            log.error("не прочитать претензии: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Сколько свободно. Свободным считается остаток счёта минус ЖИВЫЕ претензии:
     * инвентарь мёртвого бота свободен, но пока не распущен — см. {@link #claim}.
     */
    public synchronized Free free(String currency, double venueTotal, long nowMs) {
        double live = 0;
        double expired = 0;
        for (Claim c : claims(currency, nowMs)) {
            if (c.live()) {
                live += c.qty();
            } else {
                expired += c.qty();
            }
        }
        return new Free(venueTotal, live, expired, Math.max(0, venueTotal - live));
    }

    /**
     * Захват свободного инвентаря. Вызывается ТОЛЬКО при инициализации бота —
     * проверку «бот не котирует» делает вызывающий, здесь её сделать нечем.
     *
     * Истёкшие претензии распускаются ЗДЕСЬ и записываются событием
     * {@code expire} с текущей ценой: это и есть момент, когда инвентарь
     * реально сменил хозяина, и именно эту цену потом ищет обделённый бот.
     *
     * @return true, если захват состоялся; false — если просят больше свободного
     */
    public synchronized boolean claim(String botId, String currency, double qty,
                                      double venueTotal, double price, long nowMs) {
        if (!(qty > 0)) {
            return false;
        }
        Free before = free(currency, venueTotal, nowMs);
        if (qty > before.free() + 1e-15) {
            log.warn("{}: просит {} {}, свободно {} — отказ", botId, qty, currency, before.free());
            return false;
        }
        try {
            connection.setAutoCommit(false);
            // Истёкшие претензии распускаем — но только сейчас, когда за
            // инвентарём действительно пришли.
            for (Claim c : claims(currency, nowMs)) {
                if (!c.live() && c.qty() > 0 && !c.botId().equals(botId)) {
                    event(c.botId(), currency, c.qty(), "expire", price,
                            "распущена при захвате ботом " + botId);
                    setQty(c.botId(), currency, 0, nowMs, c.sinceMs());
                }
            }
            double own = own(botId, currency);
            setQty(botId, currency, own + qty, nowMs, nowMs);
            event(botId, currency, qty, "claim", price, "захват при инициализации");
            connection.commit();
            return true;
        } catch (Exception e) {
            rollback();
            log.error("не выполнить захват: {}", e.getMessage());
            return false;
        } finally {
            autoCommit();
        }
    }

    /** Отдать инвентарь в общий котёл. Заявки к этому моменту обязаны быть сняты. */
    public synchronized void release(String botId, String currency, double price, long nowMs) {
        double own = own(botId, currency);
        if (own <= 0) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            event(botId, currency, own, "release", price, "освобождение по команде");
            setQty(botId, currency, 0, nowMs, nowMs);
            connection.commit();
        } catch (Exception e) {
            rollback();
            log.error("не выполнить освобождение: {}", e.getMessage());
        } finally {
            autoCommit();
        }
    }

    /**
     * Изменение позиции СВОИМИ сделками. Единственный способ двигать инвентарь
     * во время работы — см. правило в шапке класса.
     */
    public synchronized void applyFill(String botId, String currency, double delta, long nowMs) {
        double own = own(botId, currency);
        setQty(botId, currency, own + delta, nowMs, sinceOf(botId, currency, nowMs));
    }

    /** Продление аренды. Зовётся, пока жив ПРОЦЕСС, а не пока идёт котирование. */
    public synchronized void heartbeat(String botId, String currency, long nowMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE claim SET heartbeat_ms = ? WHERE bot_id = ? AND currency = ?")) {
            ps.setLong(1, nowMs);
            ps.setString(2, botId);
            ps.setString(3, currency);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не продлить аренду: {}", e.getMessage());
        }
    }

    public synchronized double own(String botId, String currency) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT qty FROM claim WHERE bot_id = ? AND currency = ?")) {
            ps.setString(1, botId);
            ps.setString(2, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            log.error("не прочитать свою претензию: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Цена, по которой у бота ЗАБРАЛИ инвентарь, если это случилось.
     *
     * Нужна для конфликтного случая: бот возвращается, его претензия распущена,
     * и списывать позицию надо по цене МОМЕНТА ИЗЪЯТИЯ, а не текущей — иначе
     * движение рынка за время простоя припишется боту, которого не было.
     *
     * @return null, если изъятия не было
     */
    public synchronized Double seizedPrice(String botId, String currency, long afterMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT price FROM claim_event WHERE bot_id = ? AND currency = ? "
                        + "AND kind = 'expire' AND ts_ms >= ? ORDER BY ts_ms DESC LIMIT 1")) {
            ps.setString(1, botId);
            ps.setString(2, currency);
            ps.setLong(3, afterMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                double p = rs.getDouble(1);
                return p > 0 ? p : null;
            }
        } catch (Exception e) {
            log.error("не прочитать цену изъятия: {}", e.getMessage());
            return null;
        }
    }

    /** Проверка инварианта: сумма живых претензий не может превышать остаток счёта. */
    public synchronized String checkInvariant(String currency, double venueTotal, long nowMs) {
        Free f = free(currency, venueTotal, nowMs);
        if (f.claimedLive() > venueTotal + 1e-12) {
            return String.format(java.util.Locale.ROOT,
                    "живых претензий %.8f при остатке счёта %.8f — расхождение %.8f",
                    f.claimedLive(), venueTotal, f.claimedLive() - venueTotal);
        }
        return null;
    }

    // --- внутреннее -----------------------------------------------------------

    private void setQty(String botId, String currency, double qty, long nowMs, long sinceMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO claim(bot_id, currency, qty, since_ms, heartbeat_ms) "
                        + "VALUES(?,?,?,?,?) ON CONFLICT(bot_id, currency) DO UPDATE SET "
                        + "qty = excluded.qty, heartbeat_ms = excluded.heartbeat_ms")) {
            ps.setString(1, botId);
            ps.setString(2, currency);
            ps.setDouble(3, qty);
            ps.setLong(4, sinceMs);
            ps.setLong(5, nowMs);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записать претензию: {}", e.getMessage());
        }
    }

    private long sinceOf(String botId, String currency, long fallback) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT since_ms FROM claim WHERE bot_id = ? AND currency = ?")) {
            ps.setString(1, botId);
            ps.setString(2, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : fallback;
            }
        } catch (Exception e) {
            return fallback;
        }
    }

    private void event(String botId, String currency, double qty, String kind,
                       double price, String detail) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO claim_event(ts_ms, bot_id, currency, qty, kind, price, detail) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, botId);
            ps.setString(3, currency);
            ps.setDouble(4, qty);
            ps.setString(5, kind);
            ps.setDouble(6, price);
            ps.setString(7, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записать событие реестра: {}", e.getMessage());
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // соединение уже сломано — писать об этом нечем
        }
    }

    private void autoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (Exception ignored) {
            // то же
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.error("реестр не закрылся: {}", e.getMessage());
        }
    }
}
