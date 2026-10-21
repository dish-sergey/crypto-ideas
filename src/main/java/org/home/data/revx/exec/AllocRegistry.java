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
            CREATE TABLE IF NOT EXISTS pool (
                name        TEXT    PRIMARY KEY,
                pct         REAL    NOT NULL,
                floor       REAL    NOT NULL,
                ceil        REAL    NOT NULL,
                shrink      INTEGER NOT NULL,
                buffer      REAL    NOT NULL,
                step_hour   INTEGER NOT NULL,
                steps       INTEGER NOT NULL DEFAULT 0,
                reinvested  REAL    NOT NULL DEFAULT 0,
                cut         REAL    NOT NULL DEFAULT 0,
                created_ms  INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS pool_member (
                pool        TEXT    NOT NULL,
                bot_id      TEXT    NOT NULL PRIMARY KEY,
                capital0    REAL    NOT NULL,
                capital     REAL    NOT NULL,
                pending     REAL    NOT NULL DEFAULT 0,
                contributed REAL    NOT NULL DEFAULT 0,
                funded      INTEGER NOT NULL DEFAULT 0,
                joined_ms   INTEGER NOT NULL,
                reported_ms INTEGER
            );
            CREATE TABLE IF NOT EXISTS pool_event (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms   INTEGER NOT NULL,
                pool    TEXT    NOT NULL,
                kind    TEXT    NOT NULL,
                detail  TEXT
            );
            """;

    /**
     * Претензия ОБЩЕГО БУФЕРА пула — {@code pool:<имя>} в валюте котировки. Процесса у
     * неё нет, сердцебиения тоже: она жива всегда, иначе через пять минут её распустил
     * бы первый же захват как бесхозную.
     */
    public static final String POOL_PREFIX = "pool:";
    public static final String POOL_CURRENCY = "USDC";

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
                    String bot = rs.getString(1);
                    out.add(new Claim(bot, currency, rs.getDouble(2),
                            rs.getLong(3), hb, nowMs - hb < LEASE_MS || bot.startsWith(POOL_PREFIX)));
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
        // ⚠️ ДОПУСК ОТНОСИТЕЛЬНЫЙ, А НЕ АБСОЛЮТНЫЙ.
        //
        // Стоял 1e-15 — меньше, чем сама точность double на этих величинах:
        // у числа около 25 соседнее отличается примерно на 3.6e-15. Поэтому бот,
        // просящий РОВНО остаток, отказ получал не по делу, а по крошке
        // округления.
        //
        // Поймано 11.09.2026 на прогоне трёх ботов: третий просил
        // 24.81717010645232 при свободных 24.817170106452316 — разница в четыре
        // фемтодоллара, — получал отказ и не котировал ВООБЩЕ. Все прогоны
        // мульти-бота до этого меряли двоих вместо троих. Живой бот наступит на
        // то же, если попросит ровно свободное.
        double slack = Math.max(1e-12, Math.abs(before.free()) * 1e-12);
        if (qty > before.free() + slack) {
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

    /**
     * Отдать ЧАСТЬ claim'а в общий котёл.
     *
     * Нужно для сведе́ния кассы: бот держит денег ровно на недостающую до потолка
     * часть инвентаря, и когда инвентарь ПРИБАВЛЯЕТСЯ передачей (а не покупкой),
     * лишние деньги обязаны вернуться в котёл. Иначе доля бота на общем счёте
     * растёт с каждым захватом: монеты пришли, а деньги под них остались за ним
     * — найдено владельцем 13.09.2026.
     *
     * @return сколько фактически отдано (не больше того, что есть)
     */
    public synchronized double releasePart(String botId, String currency, double qty,
                                           double price, long nowMs) {
        double own = own(botId, currency);
        double give = Math.min(Math.max(0, qty), Math.max(0, own));
        if (!(give > 0)) {
            return 0;
        }
        try {
            connection.setAutoCommit(false);
            event(botId, currency, give, "release", price, "сведение кассы под потолок");
            setQty(botId, currency, own - give, nowMs, sinceOf(botId, currency, nowMs));
            connection.commit();
            return give;
        } catch (Exception e) {
            rollback();
            log.error("не выполнить частичное освобождение: {}", e.getMessage());
            return 0;
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

    /**
     * Продление аренды — сразу по ВСЕМ валютам бота.
     *
     * ⚠️ Аренда принадлежит ПРОЦЕССУ, а не паре «бот+валюта», и продлевать её
     * по одной валюте нельзя. 04.09.2026 сердцебиение шло только по базовой:
     * строки USDC у всех троих истекали через пять минут, переставали считаться
     * живыми, и `/free` показывал «свободно 46.21» при полностью разобранных
     * деньгах. Затем бот A при обычном `/start` РАСПУСТИЛ кассу B и C как
     * бесхозную — 13.19 и 14.74 USDC, — и оба остались без денег на покупку.
     *
     * Зовётся, пока жив процесс, независимо от того, идёт ли котирование.
     */
    /**
     * СТЕНД: не продлевать аренду.
     *
     * Аренда сторожит один случай — процесс бота умер, а его инвентарь числится
     * за ним. В прогоне процесс один, соседей нет, и терять претензию некому.
     * Зато цена продления настоящая: сердцебиение идёт раз в минуту МОДЕЛЬНОГО
     * времени, а модельные часы проходят шестнадцать суток за минуту живого —
     * это 23 тысячи записей в SQLite на бота. Замер 07.09.2026: после снятия
     * записи котировок именно alloc.db-wal остался главным писателем.
     */
    public void heartbeatOff() {
        this.heartbeatOff = true;
    }

    private boolean heartbeatOff;

    public synchronized void heartbeat(String botId, long nowMs) {
        if (heartbeatOff) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE claim SET heartbeat_ms = ? WHERE bot_id = ?")) {
            ps.setLong(1, nowMs);
            ps.setString(2, botId);
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

    // --- общий буфер реинвестирования (20.10.2026) ----------------------------
    //
    // Учёт пула — ReinvestPool, его состояние живёт здесь. Шаг делает сводный бот
    // (revx-info) раз в час; деньги двигает САМ бот-участник между своей претензией
    // и претензией pool:<имя>, чтобы касса в его журнале и в реестре шла вместе.

    public record Pool(String name, double pct, double floor, double ceil, boolean shrink,
                       double buffer, long stepHour, long steps, double reinvested, double cut,
                       long createdMs) {
    }

    /**
     * @param funded своими деньгами (из ничейного) капитал уже обеспечен; false — после
     *               {@code /pool add} и {@code /pool set}, бот сводит разницу с ничейным
     */
    public record PoolMember(String pool, String botId, double capital0, double capital,
                             double pending, double contributed, boolean funded, long joinedMs,
                             long reportedMs) {
    }

    public synchronized List<Pool> pools() {
        List<Pool> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, pct, floor, ceil, shrink, buffer, step_hour, steps, reinvested, cut, "
                        + "created_ms FROM pool ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Pool(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getInt(5) != 0, rs.getDouble(6), rs.getLong(7), rs.getLong(8),
                        rs.getDouble(9), rs.getDouble(10), rs.getLong(11)));
            }
        } catch (Exception e) {
            log.error("не прочитать пулы: {}", e.getMessage());
        }
        return out;
    }

    public synchronized Pool pool(String name) {
        for (Pool p : pools()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public synchronized List<PoolMember> poolMembers(String name) {
        List<PoolMember> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pool, bot_id, capital0, capital, pending, contributed, funded, joined_ms, "
                        + "COALESCE(reported_ms, 0) FROM pool_member WHERE pool = ? ORDER BY bot_id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(member(rs));
                }
            }
        } catch (Exception e) {
            log.error("не прочитать участников пула: {}", e.getMessage());
        }
        return out;
    }

    /** Членство бота; null — бот ни в каком пуле. */
    public synchronized PoolMember poolOf(String botId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pool, bot_id, capital0, capital, pending, contributed, funded, joined_ms, "
                        + "COALESCE(reported_ms, 0) FROM pool_member WHERE bot_id = ?")) {
            ps.setString(1, botId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? member(rs) : null;
            }
        } catch (Exception e) {
            log.error("не прочитать членство в пуле: {}", e.getMessage());
            return null;
        }
    }

    private static PoolMember member(ResultSet rs) throws java.sql.SQLException {
        return new PoolMember(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                rs.getDouble(5), rs.getDouble(6), rs.getInt(7) != 0, rs.getLong(8), rs.getLong(9));
    }

    /** Создать пул. @return null при успехе, иначе причина */
    public synchronized String poolCreate(String name, double pct, double floor, double ceil,
                                          boolean shrink, long nowMs) {
        if (pool(name) != null) {
            return "пул " + name + " уже есть";
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pool(name, pct, floor, ceil, shrink, buffer, step_hour, created_ms) "
                        + "VALUES(?,?,?,?,?,0,?,?)")) {
            ps.setString(1, name);
            ps.setDouble(2, pct);
            ps.setDouble(3, floor);
            ps.setDouble(4, ceil);
            ps.setInt(5, shrink ? 1 : 0);
            ps.setLong(6, nowMs / HOUR_MS);
            ps.setLong(7, nowMs);
            ps.executeUpdate();
            poolEvent(name, "create", String.format(java.util.Locale.ROOT,
                    "буфер %.1f%%, капитал от %.2f до %.2f стартового, недостача %s", pct, floor, ceil,
                    shrink ? "режет капиталы" : "ждёт дохода"));
            return null;
        } catch (Exception e) {
            return "не создать пул: " + e.getMessage();
        }
    }

    /**
     * Принять бота в пул со стартовым капиталом {@code capital} USDC. Буфер — {@code pct}%
     * капитала — резервируется сразу из НИЧЕЙНЫХ денег (претензия {@code pool:<имя>}).
     * Свою кассу под капитал бот сведёт с ничейным сам, при ближайшем {@code /start} или
     * через минуту, если уже котирует.
     *
     * @param venueTotal остаток USDC на счёте (total)
     * @return null при успехе, иначе причина
     */
    public synchronized String poolAdd(String name, String botId, double capital, double venueTotal,
                                       long nowMs) {
        Pool p = pool(name);
        if (p == null) {
            return "пула " + name + " нет";
        }
        PoolMember was = poolOf(botId);
        if (was != null) {
            return "бот " + botId + " уже в пуле " + was.pool();
        }
        if (!(capital > 0)) {
            return "капитал должен быть больше нуля";
        }
        double reserve = capital * p.pct() / 100;
        double free = free(POOL_CURRENCY, venueTotal, nowMs).free();
        if (reserve > free + 1e-9) {
            return String.format(java.util.Locale.ROOT,
                    "под буфер нужно %.2f %s, ничейного %.2f", reserve, POOL_CURRENCY, free);
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pool_member(pool, bot_id, capital0, capital, joined_ms) VALUES(?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, botId);
                ps.setDouble(3, capital);
                ps.setDouble(4, capital);
                ps.setLong(5, nowMs);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE pool SET buffer = buffer + ? WHERE name = ?")) {
                ps.setDouble(1, reserve);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            String id = POOL_PREFIX + name;
            setQty(id, POOL_CURRENCY, own(id, POOL_CURRENCY) + reserve, nowMs,
                    sinceOf(id, POOL_CURRENCY, nowMs));
            event(id, POOL_CURRENCY, reserve, "pool_reserve", 1, "буфер под " + botId);
            poolEvent(name, "add", String.format(java.util.Locale.ROOT,
                    "%s: капитал %.2f, в буфер из ничейного %.2f", botId, capital, reserve));
            connection.commit();
            return null;
        } catch (Exception e) {
            rollback();
            return "не принять в пул: " + e.getMessage();
        } finally {
            autoCommit();
        }
    }

    /**
     * Задать капитал участника руками (и он же новый стартовый: границы ½…3× от него).
     * Разницу бот сведёт с НИЧЕЙНЫМ, а не с буфером: это решение владельца, а не доход.
     */
    public synchronized String poolSet(String botId, double capital, long nowMs) {
        PoolMember m = poolOf(botId);
        if (m == null) {
            return "бот " + botId + " ни в каком пуле";
        }
        if (!(capital > 0)) {
            return "капитал должен быть больше нуля";
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE pool_member SET capital0 = ?, capital = ?, funded = 0 WHERE bot_id = ?")) {
            ps.setDouble(1, capital);
            ps.setDouble(2, capital);
            ps.setString(3, botId);
            ps.executeUpdate();
            poolEvent(m.pool(), "set", String.format(java.util.Locale.ROOT,
                    "%s: капитал %.2f → %.2f руками", botId, m.capital(), capital));
            return null;
        } catch (Exception e) {
            return "не задать капитал: " + e.getMessage();
        }
    }

    /** Вывести бота из пула. Буфер остаётся пулу; касса бота — за ботом. */
    public synchronized String poolRemove(String botId, long nowMs) {
        PoolMember m = poolOf(botId);
        if (m == null) {
            return "бот " + botId + " ни в каком пуле";
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM pool_member WHERE bot_id = ?")) {
            ps.setString(1, botId);
            ps.executeUpdate();
            poolEvent(m.pool(), "remove", String.format(java.util.Locale.ROOT,
                    "%s: капитал был %.2f, не учтено в шаге %+.4f", botId, m.capital(), m.pending()));
            return null;
        } catch (Exception e) {
            return "не вывести из пула: " + e.getMessage();
        }
    }

    /** Бот докладывает реализованное с прошлого доклада. */
    public synchronized void poolReport(String botId, double delta, long nowMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE pool_member SET pending = pending + ?, reported_ms = ? WHERE bot_id = ?")) {
            ps.setDouble(1, delta);
            ps.setLong(2, nowMs);
            ps.setString(3, botId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не доложить пулу: {}", e.getMessage());
        }
    }

    /** Сумма передач бота с буфером пула ({@code pool_in} +, {@code pool_out} −) за всё время. */
    public synchronized double poolTransferred(String botId, String currency) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(qty), 0) FROM claim_event "
                        + "WHERE bot_id = ? AND currency = ? AND kind IN ('pool_in', 'pool_out')")) {
            ps.setString(1, botId);
            ps.setString(2, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            log.error("не прочитать передачи с пулом: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized void poolFunded(String botId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE pool_member SET funded = 1 WHERE bot_id = ?")) {
            ps.setString(1, botId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не отметить обеспечение капитала: {}", e.getMessage());
        }
    }

    /**
     * Передача денег между ботом и буфером его пула. {@code amount} &gt; 0 — из буфера
     * боту (не больше, чем в буфере), &lt; 0 — от бота в буфер (не больше его претензии).
     * @return сколько передано, со знаком
     */
    public synchronized double poolTransfer(String botId, String currency, double amount, long nowMs) {
        PoolMember m = poolOf(botId);
        if (m == null || amount == 0 || !Double.isFinite(amount)) {
            return 0;
        }
        String id = POOL_PREFIX + m.pool();
        double inPool = own(id, currency);
        double mine = own(botId, currency);
        double move = amount > 0 ? Math.min(amount, Math.max(0, inPool)) : -Math.min(-amount, Math.max(0, mine));
        if (move == 0) {
            return 0;
        }
        try {
            connection.setAutoCommit(false);
            setQty(id, currency, inPool - move, nowMs, sinceOf(id, currency, nowMs));
            setQty(botId, currency, mine + move, nowMs, sinceOf(botId, currency, nowMs));
            event(botId, currency, move, move > 0 ? "pool_in" : "pool_out", 1,
                    (move > 0 ? "из буфера " : "в буфер ") + m.pool());
            connection.commit();
            return move;
        } catch (Exception e) {
            rollback();
            log.error("не выполнить передачу с пулом: {}", e.getMessage());
            return 0;
        } finally {
            autoCommit();
        }
    }

    /**
     * Свести кассу бота с НИЧЕЙНЫМ (решение владельца: {@code /pool add}, {@code /pool set}).
     * {@code amount} &gt; 0 — взять из ничейного (сколько есть), &lt; 0 — вернуть.
     * @return сколько передано, со знаком
     */
    public synchronized double poolFundFree(String botId, String currency, double amount,
                                            double venueTotal, long nowMs) {
        if (amount == 0 || !Double.isFinite(amount)) {
            return 0;
        }
        double mine = own(botId, currency);
        double move = amount > 0
                ? Math.min(amount, free(currency, venueTotal, nowMs).free())
                : -Math.min(-amount, Math.max(0, mine));
        if (move == 0) {
            return 0;
        }
        try {
            connection.setAutoCommit(false);
            setQty(botId, currency, mine + move, nowMs, sinceOf(botId, currency, nowMs));
            event(botId, currency, move, "pool_fund", 1,
                    move > 0 ? "капитал пула: взято из ничейного" : "капитал пула: возвращено в ничейное");
            connection.commit();
            return move;
        } catch (Exception e) {
            rollback();
            log.error("не свести кассу с ничейным: {}", e.getMessage());
            return 0;
        } finally {
            autoCommit();
        }
    }

    public static final long HOUR_MS = 3_600_000L;

    /**
     * Часовой шаг пула. Делается, если с прошлого шага сменился час; пропущенные часы
     * (сводка лежала) схлопываются в один шаг — накопленное всё равно лежит в pending.
     * @return строка о шаге, если капиталы сдвинулись; иначе null
     */
    public synchronized String poolStep(String name, long nowMs) {
        Pool p = pool(name);
        long hour = nowMs / HOUR_MS;
        if (p == null || hour <= p.stepHour()) {
            return null;
        }
        List<PoolMember> ms = poolMembers(name);
        try {
            connection.setAutoCommit(false);
            if (ms.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE pool SET step_hour = ? WHERE name = ?")) {
                    ps.setLong(1, hour);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
                connection.commit();
                return null;
            }
            java.util.Map<String, double[]> state = new java.util.TreeMap<>();
            java.util.Map<String, Double> deltas = new java.util.TreeMap<>();
            for (PoolMember m : ms) {
                state.put(m.botId(), new double[]{m.capital0(), m.capital(), m.contributed()});
                deltas.put(m.botId(), m.pending());
            }
            ReinvestPool rp = ReinvestPool.restore(p.pct(), p.floor(), p.ceil(), p.shrink(), p.buffer(), state);
            rp.step(deltas);
            boolean moved = false;
            for (PoolMember m : ms) {
                ReinvestPool.Member after = rp.members().get(m.botId());
                moved |= Math.abs(after.capital - m.capital()) > 1e-9;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE pool_member SET capital = ?, contributed = ?, pending = pending - ? "
                                + "WHERE bot_id = ?")) {
                    ps.setDouble(1, after.capital);
                    ps.setDouble(2, after.contributed);
                    ps.setDouble(3, m.pending());
                    ps.setString(4, m.botId());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE pool SET buffer = ?, step_hour = ?, steps = steps + 1, "
                            + "reinvested = reinvested + ?, cut = cut + ? WHERE name = ?")) {
                ps.setDouble(1, rp.buffer());
                ps.setLong(2, hour);
                ps.setDouble(3, rp.reinvested());
                ps.setDouble(4, rp.cut());
                ps.setString(5, name);
                ps.executeUpdate();
            }
            String d = String.format(java.util.Locale.ROOT, "шаг: принесено %+.4f; %s",
                    deltas.values().stream().mapToDouble(Double::doubleValue).sum(), rp.describe());
            poolEvent(name, moved ? "step" : "step_hold", d);
            connection.commit();
            return moved ? d : null;
        } catch (Exception e) {
            rollback();
            log.error("пул {}: шаг не сделан — {}", name, e.getMessage());
            return null;
        } finally {
            autoCommit();
        }
    }

    private void poolEvent(String pool, String kind, String detail) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pool_event(ts_ms, pool, kind, detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, pool);
            ps.setString(3, kind);
            ps.setString(4, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записать событие пула: {}", e.getMessage());
        }
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
