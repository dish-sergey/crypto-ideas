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
 * Журнал исполнителя (ТЗ §6: «полный журнал всех отправленных запросов и ответов»).
 *
 * Отдельная база, отдельное соединение, никакой связи с базой стенда. Причина не
 * в аккуратности, а в разделении ролей: стенд измеряет, исполнитель торгует, и
 * авария одного не должна касаться данных другого. База стенда исполнителем
 * открывается только на чтение, эта — только им и только на запись.
 *
 * Пишется КАЖДЫЙ запрос, включая неудавшиеся и включая те, что не дошли. Когда
 * через неделю окажется, что заявка повела себя не так, единственным источником
 * правды будет эта таблица, а не память о том, что «вроде отправляли».
 */
public final class ExecJournal implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecJournal.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS exec_request (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms      INTEGER NOT NULL,
                method     TEXT    NOT NULL,
                path       TEXT    NOT NULL,
                body       TEXT,
                status     INTEGER,
                response   TEXT,
                latency_ms INTEGER,
                error      TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_request_ts ON exec_request(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_event (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms  INTEGER NOT NULL,
                kind   TEXT    NOT NULL,
                detail TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_event_ts ON exec_event(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_quote (
                ts_ms     INTEGER NOT NULL,
                fair      REAL,
                bid       REAL,
                ask       REAL,
                inventory REAL,
                quotable  INTEGER NOT NULL,
                reason    TEXT,
                pressure  REAL
            );
            CREATE INDEX IF NOT EXISTS idx_exec_quote_ts ON exec_quote(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_fill (
                ts_ms        INTEGER NOT NULL,
                venue_id     TEXT,
                side         TEXT,
                qty          REAL,
                price        REAL,
                fair         REAL,
                fee          REAL,
                fee_currency TEXT,
                level        INTEGER,
                status       TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_fill_ts ON exec_fill(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_state (
                key    TEXT PRIMARY KEY,
                value  REAL NOT NULL,
                ts_ms  INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS exec_open_order (
                venue_id  TEXT PRIMARY KEY,
                side      TEXT    NOT NULL,
                level     INTEGER,
                price     REAL,
                size      REAL,
                opened_ms INTEGER NOT NULL,
                closed_ms INTEGER,
                status    TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_open_order ON exec_open_order(closed_ms);
            """;

    private final Connection connection;
    private final String path;

    /** Путь к файлу журнала: прогнозу нужно перечитать свои же тики. */
    public String path() {
        return path;
    }

    /**
     * Часы журнала. ⚠️ Отметки времени обязаны идти ОТТУДА ЖЕ, откуда их берёт
     * цикл котирования: повтор сверяет свои тики с живыми ПО ВРЕМЕНИ, и журнал,
     * пишущий настоящее время в прогоне по записи, не совпадёт ни с чем.
     */
    private Clock clock = Clock.system();

    public void clock(Clock clock) {
        this.clock = clock != null ? clock : Clock.system();
    }

    /**
     * Журнал ЧУЖОГО бота, только на чтение.
     *
     * ⚠️ Обычный конструктор открывает базу на запись и досоздаёт схему. Для
     * сводного бота это недопустимо вдвойне: он смотрит в журналы шести живых
     * исполнителей, и второй писатель в базу работающего бота — это блокировки
     * на его горячем пути ради отчёта. Здесь только {@code mode=ro}, никакого
     * DDL и никаких PRAGMA.
     *
     * Записывающие методы на таком журнале упадут — и правильно: сводный бот
     * ничего не пишет по построению.
     */
    public static ExecJournal readOnly(String path) {
        return new ExecJournal(path, true);
    }

    public ExecJournal(String path) {
        this(path, false);
    }

    private ExecJournal(String path, boolean readOnly) {
        if (readOnly) {
            try {
                this.path = path;
                connection = DriverManager.getConnection(
                        "jdbc:sqlite:file:" + path + "?mode=ro");
            } catch (Exception e) {
                throw new IllegalStateException("не открыть журнал на чтение " + path, e);
            }
            return;
        }
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.path = path;
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                // ⚠️ ЖДАТЬ ЧУЖУЮ ЗАПИСЬ, А НЕ ПАДАТЬ (27.09.2026). В журнал пишет не
                // только бот: ночная чистка (deploy/revx-journal-prune.sh) удаляет
                // старые строки пачками. Без ожидания запись бота, совпавшая с
                // пачкой, получала бы «database is locked» сразу — и терялась бы
                // любая, включая исполнение. Пачка чистки держит замок десятки мс.
                st.execute("PRAGMA busy_timeout=10000");
                for (String part : SCHEMA.split(";")) {
                    if (!part.isBlank()) {
                        st.execute(part);
                    }
                }
                // ⚠️ CREATE TABLE IF NOT EXISTS новую колонку в СУЩЕСТВУЮЩУЮ
                // таблицу не добавляет — журналы живых ботов её не получили бы
                // никогда, а запись котировок падала бы каждую секунду.
                // Повторный ALTER даёт «duplicate column name»; это не ошибка,
                // а «уже есть».
                try {
                    st.execute("ALTER TABLE exec_quote ADD COLUMN pressure REAL");
                    log.warn("в exec_quote добавлена колонка pressure "
                            + "(раздвижение отступа от дефицита постановок)");
                } catch (Exception already) {
                    log.debug("колонка pressure уже есть: {}", already.getMessage());
                }
                // Уровень сетки, на котором стояла исполнившаяся заявка. Нужен,
                // чтобы понять, зарабатывают ли дальние уровни или числятся:
                // до 11.09.2026 доход был известен только целиком по боту.
                try {
                    st.execute("ALTER TABLE exec_fill ADD COLUMN level INTEGER");
                    log.warn("в exec_fill добавлена колонка level (уровень сетки)");
                } catch (Exception already) {
                    log.debug("колонка level уже есть: {}", already.getMessage());
                }
            }
            log.info("журнал исполнителя: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть журнал исполнителя " + path, e);
        }
    }

    /** Заявка, которую мы поставили и чья судьба ещё не выяснена. */
    public record OpenOrder(String venueId, String side, int level, double price, double size,
                            long openedMs) {
    }

    /**
     * 🔑 ЖИВАЯ ЗАЯВКА ЗАПИСЫВАЕТСЯ НА ДИСК, А НЕ ТОЛЬКО В ПАМЯТЬ.
     *
     * <h2>Зачем</h2>
     *
     * Идентификатор стоящей заявки жил в поле объекта, и с концом процесса он
     * исчезал. Дальше новый процесс спрашивал {@code /orders/active} и усыновлял
     * то, что там видит, — но у этого списка есть две дыры, и обе стоили нам
     * денег:
     * <ul>
     *   <li><b>заявка успела исполниться в момент перезапуска.</b> В списке её
     *       уже нет, идентификатор забыт, спросить не о чем — исполнение
     *       теряется навсегда. Так 13.09.2026 в 22:28:33 исполнилась продажа
     *       {@code dc4d7e77} на 0.00120795 ETH: площадка до сих пор отвечает
     *       {@code filled}, а в журнале бота этой сделки нет;</li>
     *   <li><b>заявка жива, но в списке её нет.</b> Тогда она остаётся в книге
     *       навсегда: никто её не заменит и не снимет, а её резерв делает
     *       монету неотчуждаемой — площадка показывает {@code available} ноль
     *       при непустом остатке.</li>
     * </ul>
     *
     * Поэтому каждая постановка и замена пишет идентификатор СЮДА, а закрывает
     * запись только выясненная судьба (исполнена, снята, заменена). При старте
     * бот читает незакрытые и спрашивает площадку о каждой поимённо.
     *
     * ⚠️ Запись идёт ПОСЛЕ ответа площадки, но ДО любого учёта: если процесс
     * умрёт между ними, останется лишний вопрос при старте, а не потерянная
     * заявка. Обратный порядок терял бы именно её.
     */
    public synchronized void openOrder(String venueId, String side, int level,
                                       double price, double size, long tsMs) {
        if (venueId == null || venueId.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_open_order(venue_id, side, level, price, size, opened_ms)"
                        + " VALUES(?,?,?,?,?,?) ON CONFLICT(venue_id) DO UPDATE SET"
                        + " price = excluded.price, size = excluded.size")) {
            ps.setString(1, venueId);
            ps.setString(2, side);
            ps.setInt(3, level);
            ps.setDouble(4, price);
            ps.setDouble(5, size);
            ps.setLong(6, tsMs);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записать открытую заявку {}: {}", venueId, e.getMessage());
        }
    }

    /** Судьба выяснена: заявка исполнена, снята или заменена. */
    public synchronized void closeOrder(String venueId, String status, long tsMs) {
        if (venueId == null || venueId.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE exec_open_order SET closed_ms = ?, status = ? WHERE venue_id = ?")) {
            ps.setLong(1, tsMs);
            ps.setString(2, status);
            ps.setString(3, venueId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не закрыть открытую заявку {}: {}", venueId, e.getMessage());
        }
    }

    /**
     * Заявки, чья судьба не выяснена, — от самой старой.
     *
     * ⚠️ Хвост обрезается по возрасту: заявка, поставленная неделю назад и не
     * закрытая, почти наверняка уже неактуальна, а спрашивать площадку про
     * каждую из тысяч — это часы GET-ов при общем лимите в тысячу в минуту.
     */
    public synchronized List<OpenOrder> openOrders(long sinceMs) {
        List<OpenOrder> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT venue_id, side, level, price, size, opened_ms FROM exec_open_order"
                        + " WHERE closed_ms IS NULL AND opened_ms >= ? ORDER BY opened_ms")) {
            ps.setLong(1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new OpenOrder(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getLong(6)));
                }
            }
        } catch (Exception e) {
            log.error("не прочитать открытые заявки: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Долгоживущее состояние бота: позиция и касса.
     *
     * С двумя ботами на одном аккаунте остатки площадки перестали быть «нашей»
     * позицией — там лежит сумма обоих. Поэтому позицию каждый ведёт сам, по
     * своим исполнениям, и хранит здесь, чтобы пережить перезапуск. Остатки
     * остаются контролем: наша позиция не может превышать общую.
     */
    /**
     * Убрать ОДНУ строку исполнения — ту, что записана повторно.
     *
     * ⚠️ Удаляется строго по паре «заявка + отметка времени», а не по заявке:
     * у частичного исполнения строк по одной заявке несколько, и все они
     * законные. Ошибиться здесь значит потерять настоящую сделку.
     */
    public synchronized void deleteFill(String venueId, long tsMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM exec_fill WHERE venue_id = ? AND ts_ms = ?")) {
            ps.setString(1, venueId);
            ps.setLong(2, tsMs);
            int n = ps.executeUpdate();
            log.warn("убрана лишняя запись исполнения {} в {}: строк {}", venueId, tsMs, n);
        } catch (Exception e) {
            throw new IllegalStateException("не убрать запись исполнения " + venueId, e);
        }
    }

    public synchronized void putState(String key, double value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_state(key, value, ts_ms) VALUES(?,?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value, ts_ms=excluded.ts_ms")) {
            ps.setString(1, key);
            ps.setDouble(2, value);
            ps.setLong(3, clock.now());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записать состояние {}: {}", key, e.toString());
        }
    }

    /** {@code null} = значения нет, и это отличается от нуля. */
    public synchronized Double getState(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM exec_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        } catch (Exception e) {
            log.error("не прочитать состояние {}: {}", key, e.toString());
            return null;
        }
    }

    /** Запрос записывается ВСЕГДА — и удавшийся, и упавший. */
    public synchronized void request(String method, String path, String body,
                                     Integer status, String response, long latencyMs, String error) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_request(ts_ms, method, path, body, status, response, latency_ms, error)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, clock.now());
            ps.setString(2, method);
            ps.setString(3, path);
            ps.setString(4, body);
            if (status == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, status);
            }
            ps.setString(6, response);
            ps.setLong(7, latencyMs);
            ps.setString(8, error);
            ps.executeUpdate();
        } catch (Exception e) {
            // Журнал не должен ронять торговлю, но и молчать о своей поломке нельзя.
            log.error("не записался запрос в журнал: {}", e.getMessage());
        }
    }

    /**
     * Справедливая цена и наши котировки НА КАЖДОМ ТИКЕ.
     *
     * Без этого измерение не состоится вовсе: чтобы сравнить живое исполнение с
     * моделью, нужен захват — расстояние от цены исполнения до справедливой цены
     * В ТОТ МОМЕНТ. Восстановить его задним числом из базы стенда можно лишь
     * приблизительно, а цена за секунду уходит на пару базисных пунктов.
     */
    /**
     * Стенду котировки в базе НЕ НУЖНЫ.
     *
     * Единственное, ради чего они писались, — доля времени с полным инвентарём,
     * и она теперь считается в памяти ({ QuoteLoop.Stats.ticksAtCap}).
     * Запись же стоила дорого: замер 07.09.2026 дал 39 МБ/с и 666 операций в
     * секунду при чтении 0.4 МБ/с, то есть обход упирался в собственный журнал.
     *
     * ⚠️ У ЖИВОГО бота выключать это нельзя: захват и markout восстанавливаются
     * только по справедливой цене в момент котировки, а её больше взять неоткуда.
     */
    public void quotesOff() {
        this.quotesOff = true;
    }

    private boolean quotesOff;

    public synchronized void quote(double fair, Double bid, Double ask, double inventory,
                                   boolean quotable, String reason) {
        quote(fair, bid, ask, inventory, quotable, reason, 0);
    }

    /**
     * @param pressure применённая доля раздвижения отступа от дефицита
     *                 постановок. Пишется потому, что восстановить её задним
     *                 числом НЕЛЬЗЯ: ведро постановок общее на всех шестерых и
     *                 истории не хранит. Без неё повтор котирует по
     *                 нераздвинутому отступу, и сверка врёт (см. QuoteLoop).
     */
    public synchronized void quote(double fair, Double bid, Double ask, double inventory,
                                   boolean quotable, String reason, double pressure) {
        if (quotesOff) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_quote(ts_ms, fair, bid, ask, inventory, quotable, reason,"
                        + " pressure) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, clock.now());
            ps.setDouble(2, fair);
            if (bid == null) ps.setNull(3, java.sql.Types.REAL); else ps.setDouble(3, bid);
            if (ask == null) ps.setNull(4, java.sql.Types.REAL); else ps.setDouble(4, ask);
            ps.setDouble(5, inventory);
            ps.setInt(6, quotable ? 1 : 0);
            ps.setString(7, reason);
            ps.setDouble(8, pressure);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записалась котировка: {}", e.getMessage());
        }
    }

    /**
     * Исполнение — с ФАКТИЧЕСКОЙ ценой, объёмом и КОМИССИЕЙ, взятыми у площадки,
     * а не выведенными из изменения остатков.
     *
     * Комиссия здесь не для бухгалтерии. Вся конструкция измерялась при maker 0%.
     *
     * ⚠️ Раньше здесь было написано «это промо-тариф молодой площадки». Тариф
     * сверен 12.09.2026 (задача A31): ноль у мейкера — ШТАТНАЯ схема без ступеней
     * по обороту и без объявленного срока, и подтверждён он не рекламой, а нашими
     * 2106 исполнениями, где {@code fee} ровно ноль у всех шести ботов. Смысл
     * предохранителя от этого не меняется: смена тарифа — это смена экономики, а
     * заметить её по остаткам можно сильно позже, чем по первой же сделке.
     * Поэтому комиссия читается из ответа по каждой сделке и любое ненулевое
     * значение останавливает торговлю.
     */
    public synchronized void fill(String venueId, String side, double qty, double price,
                                  double fair, double fee, String feeCurrency, String status) {
        fill(venueId, side, qty, price, fair, fee, feeCurrency, status, -1);
    }

    /**
     * То же, но с УРОВНЕМ СЕТКИ, на котором стояла заявка.
     *
     * ⚠️ Уровень пишется отдельным полем, а не выводится задним числом из цены:
     * цена уровня зависит от скоса и раздвижений, и восстановить по ней номер
     * нельзя — соседние уровни в момент скоса сходятся почти вплотную.
     *
     * Значение −1 означает «неизвестен»: так пишутся записи из путей, где слот
     * уже потерян (перехват чужой заявки при старте), и все записи старше
     * 11.09.2026.
     */
    public synchronized void fill(String venueId, String side, double qty, double price,
                                  double fair, double fee, String feeCurrency, String status,
                                  int level) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_fill(ts_ms, venue_id, side, qty, price, fair, fee, fee_currency,"
                        + " status, level)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, clock.now());
            ps.setString(2, venueId);
            ps.setString(3, side);
            ps.setDouble(4, qty);
            ps.setDouble(5, price);
            ps.setDouble(6, fair);
            ps.setDouble(7, fee);
            ps.setString(8, feeCurrency);
            ps.setString(9, status);
            ps.setInt(10, level);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записалось исполнение: {}", e.getMessage());
        }
    }

    /**
     * Сколько по этой заявке УЖЕ записано.
     *
     * ⚠️ {@code filled_quantity} у площадки накопительный, а спрашивать одну и ту
     * же заявку можно не раз — при частичном исполнении, при усыновлении
     * наследника, при разборе исчезнувшей. Записывать надо разницу, иначе первый
     * объём попадёт в журнал дважды: ровно так 08.09.2026 в журнале бота D
     * появилось задвоенное исполнение ADA.
     */
    public synchronized double filledByOrder(String venueId) {
        if (venueId == null) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(qty), 0) FROM exec_fill WHERE venue_id = ?")) {
            ps.setString(1, venueId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            log.error("не прочиталось записанное по заявке {}: {}", venueId, e.getMessage());
            return 0;
        }
    }

    /** Одно исполнение из журнала — вход для {@link FifoLedger}. */
    /**
     *  handover передача инвентаря между ботами, а не сделка с рынком.
     *                 Вся статистика (захват, κ, markout, «на сделку») обязана
     *                 её исключать, иначе каждый перезапуск впрыскивает в
     *                 измерения фальшивое исполнение. Книга партий, наоборот,
     *                 её учитывает: партии передача действительно открывает.
     */

    /** Исполнение с уровнем сетки и справедливой ценой — для разреза по уровням. */
    public record LevelFill(long tsMs, boolean buy, double qty, double price, double fair,
                            int level) {
    }

    /**
     * Исполнения с уровнем сетки, на котором стояла заявка.
     *
     * ⚠️ Записи старше 11.09.2026 колонки не имеют и приходят с уровнем −1:
     * разрез по ним посчитать нельзя, и смешивать их с новыми — значит получить
     * «уровень −1» размером во всю историю.
     */
    public synchronized List<LevelFill> levelFills() {
        List<LevelFill> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts_ms, side, qty, price, fair, COALESCE(level, -1), status"
                             + " FROM exec_fill ORDER BY ts_ms")) {
            while (rs.next()) {
                if ("handover".equalsIgnoreCase(rs.getString(7))) {
                    continue;             // проводка владения, а не сделка
                }
                out.add(new LevelFill(rs.getLong(1), "BUY".equalsIgnoreCase(rs.getString(2)),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6)));
            }
        } catch (Exception e) {
            log.error("не прочитались исполнения по уровням: {}", e.getMessage());
        }
        return out;
    }
    public record FillRow(long tsMs, boolean buy, double qty, double price, double fee,
                          boolean handover) {
    }

    /**
     * Все исполнения по времени. Читается целиком: за неделю их сотни, а книга
     * партий по построению требует ВСЮ историю — остаток сегодня объясняется
     * покупками произвольной давности.
     */
    /**
     * СКОЛЬКО ПО КАЖДОЙ ЗАЯВКЕ УЖЕ ЗАПИСАНО — чтобы после перезапуска не
     * записать то же исполнение второй раз.
     *
     * ⚠️ Котировщик помнит это в памяти ({@code bookedByOrder}) и пишет РАЗНИЦУ
     * между тем, что площадка называет исполненным, и тем, что уже проведено.
     * Перезапуск память обнуляет, и повторный вопрос о старой заявке провёл бы
     * её исполнение заново — инвентарь бота сместился бы навсегда. Пока бот
     * старые заявки не переспрашивал, это не стреляло; с появлением
     * восстановления при старте вопрос задаётся намеренно, поэтому память
     * восстанавливается отсюда.
     *
     * Передачи ({@code venue_id IS NULL}) сюда не попадают: у них нет заявки.
     */
    /**
     * Сколько уже записано по ОДНОЙ заявке.
     *
     * ⚠️ Нужен потому, что карта в памяти — LRU на 512 записей, а заявок в
     * журнале втрое больше, и промах по ней ничего не говорит о том, была ли
     * заявка учтена. 16.09.2026 такой промах провёл одну продажу дважды и увёл
     * позицию спотового бота в минус.
     */
    public synchronized double bookedFor(String venueId) {
        if (venueId == null) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ifnull(sum(qty), 0) FROM exec_fill WHERE venue_id = ?")) {
            ps.setString(1, venueId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            // Молча вернуть ноль нельзя: это означало бы «не учтено» и привело
            // бы ровно к тому двойному счёту, от которого мы защищаемся.
            throw new IllegalStateException("не прочиталось учтённое по заявке " + venueId, e);
        }
    }

    public synchronized java.util.Map<String, Double> bookedByOrder() {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT venue_id, sum(qty) FROM exec_fill WHERE venue_id IS NOT NULL"
                             + " GROUP BY venue_id")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getDouble(2));
            }
        } catch (Exception e) {
            log.error("не прочиталось учтённое по заявкам: {}", e.getMessage());
        }
        return out;
    }

    /**
     * ХВОСТЫ ЦЕПОЧЕК ЗАЯВОК за последнее время: идентификаторы, которые мы
     * создали и больше не заменяли.
     *
     * Замена создаёт НОВУЮ заявку, и цепочка связана полем
     * {@code previous_order_id}. Живых хвостов у бота единицы (по одному на
     * уровень и сторону), а промежуточные звенья спрашивать незачем: их судьба
     * известна — заменены.
     *
     * Нужно для восстановления после перезапуска: заявка, исполнившаяся в те
     * секунды, пока процесса не было, не попадает ни в один список — в книге её
     * уже нет, а в памяти бота ещё нет. Единственный способ узнать о ней —
     * спросить площадку по идентификатору из СВОЕГО ЖЕ журнала.
     *
     * @param sinceMs с какого момента смотреть
     * @param cap     сколько хвостов вернуть максимум (новые первыми)
     */
    public synchronized List<String> recentOrderTails(long sinceMs, int cap) {
        java.util.LinkedHashSet<String> created = new java.util.LinkedHashSet<>();
        java.util.Set<String> superseded = new java.util.HashSet<>();
        java.util.regex.Pattern venue = java.util.regex.Pattern
                .compile("\"venue_order_id\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Pattern previous = java.util.regex.Pattern
                .compile("\"previous_order_id\"\\s*:\\s*\"([^\"]+)\"");
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT response FROM exec_request WHERE ts_ms >= ? AND status = 200"
                        + " AND (method = 'POST' OR method = 'PUT') ORDER BY ts_ms DESC")) {
            ps.setLong(1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String body = rs.getString(1);
                    if (body == null) {
                        continue;
                    }
                    java.util.regex.Matcher m = venue.matcher(body);
                    if (m.find()) {
                        created.add(m.group(1));
                    }
                    java.util.regex.Matcher p = previous.matcher(body);
                    while (p.find()) {
                        superseded.add(p.group(1));
                    }
                }
            }
        } catch (Exception e) {
            log.error("не прочитались хвосты цепочек заявок: {}", e.getMessage());
        }
        List<String> out = new ArrayList<>();
        for (String id : created) {
            if (!superseded.contains(id)) {
                out.add(id);
            }
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    public synchronized List<FillRow> fills() {
        List<FillRow> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts_ms, side, qty, price, fee, status FROM exec_fill ORDER BY ts_ms")) {
            while (rs.next()) {
                out.add(new FillRow(rs.getLong(1),
                        "BUY".equalsIgnoreCase(rs.getString(2)),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        "handover".equalsIgnoreCase(rs.getString(6))));
            }
        } catch (Exception e) {
            log.error("не прочитались исполнения: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Затравка позиции: когда принята и сколько.
     *
     * У неё НЕТ цены входа — это позиция, существовавшая до бота. Для книги
     * партий цена нужна, и берётся справедливая цена того момента: то есть бот
     * считается «купившим» остаток по рынку в секунду своего первого запуска.
     * Условность, но единственная, при которой реализованный результат СОБСТВЕННОЙ
     * торговли остаётся верным.
     */
    public synchronized FillRow seed() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT e.ts_ms, e.detail, (SELECT q.fair FROM exec_quote q "
                             + "WHERE q.ts_ms <= e.ts_ms AND q.fair > 0 "
                             + "ORDER BY q.ts_ms DESC LIMIT 1) fair "
                             + "FROM exec_event e WHERE e.kind = 'position_seed' "
                             + "ORDER BY e.ts_ms LIMIT 1")) {
            if (!rs.next()) {
                return null;
            }
            String detail = rs.getString(2);
            double qty = Double.parseDouble(detail.trim().split("\\s+")[0]);
            double fair = rs.getDouble(3);
            return qty > 0 && fair > 0 ? new FillRow(rs.getLong(1), true, qty, fair, 0, true) : null;
        } catch (Exception e) {
            log.error("не прочиталась затравка: {}", e.getMessage());
            return null;
        }
    }

    /** Запуск процесса: когда и с какими параметрами. {@code null}, если записи нет. */
    public record Boot(long tsMs, String detail) {
    }

    /**
     * Последний запуск процесса — начало окна «с запуска».
     *
     * Считаем по СТАРТУ ПРОЦЕССА, а не по команде {@code /start}: сравнивать
     * между собой надо версии бота, а котирование в пределах одной версии
     * человек включает и выключает по десять раз. Если события нет (журнал
     * старше этой правки) — {@code null}, и окно просто не печатается.
     */
    public synchronized Boot lastBoot() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts_ms, detail FROM exec_event WHERE kind = 'boot' "
                             + "ORDER BY ts_ms DESC LIMIT 1")) {
            return rs.next() ? new Boot(rs.getLong(1), rs.getString(2)) : null;
        } catch (Exception e) {
            log.error("не прочиталась точка запуска: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Сколько раз случилось событие такого рода.
     *
     * Нужно прогнозу: без этого нельзя отличить «бот отработал окно» от «бот
     * встал на предохранителе через час, а остальные три дня простоял». Такие
     * прогоны сравнивать между собой нельзя, а выглядят они одинаково.
     */
    public synchronized long countEvents(String kind) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM exec_event WHERE kind = ?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Моменты событий одного вида начиная с {@code sinceMs} — для авто-минуты затыков. */
    public synchronized List<Long> eventTimes(String kind, long sinceMs) {
        List<Long> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ts_ms FROM exec_event WHERE kind = ? AND ts_ms >= ?")) {
            ps.setString(1, kind);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.warn("события {} не прочитаны: {}", kind, e.getMessage());
        }
        return out;
    }

    /** События уровня решений: запуск, остановка, паника, срабатывание лимита. */
    public synchronized void event(String kind, String detail) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_event(ts_ms, kind, detail) VALUES (?,?,?)")) {
            ps.setLong(1, clock.now());
            ps.setString(2, kind);
            ps.setString(3, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("не записалось событие в журнал: {}", e.getMessage());
        }
    }

    /**
     * Сколько постановок сделано за последние {@code windowMs} — по ЖУРНАЛУ, а
     * не по счётчику в памяти.
     *
     * <b>Зачем из журнала.</b> Счётчик в памяти обнулялся при каждом запуске
     * процесса, а у бота A их было 23 — то есть суточный предел постановок
     * фактически не действовал ни разу. Журнал переживает рестарт, деплой и
     * падение, и только он и знает настоящий расход.
     *
     * <b>Почему окно СКОЛЬЗЯЩЕЕ.</b> Как площадка обнуляет свою тысячу —
     * в полночь UTC или тоже скользящим окном — мы не знаем: за всю историю ни
     * одного отказа по лимиту не приходило, проверить не на чем. Скользящее окно
     * безопасно при ОБЕИХ гипотезах: «не более N за любые 24 часа» автоматически
     * означает «не более N за календарные сутки», а обратное неверно. Прежнее
     * окно было опрокидывающимся от старта процесса и допускало до 2N подряд на
     * стыке.
     *
     * <b>🔑 ⚠️ ОТКАЗ ПО ЛИМИТУ (429) НЕ СЧИТАЕТСЯ — и только он</b> (22.09.2026).
     *
     * Прежде считались ВСЕ {@code POST}, «консервативной стороной». Но 429 — это
     * ровно тот случай, когда площадка сказала «не приняла»: тратить на него
     * токен значило бы наказывать нас дважды за один отказ, и сам смысл
     * {@code Retry-After} в том, что запрос НЕ состоялся.
     *
     * Цена прежнего счёта измерена: за скользящие сутки до 21.09 12:00 UTC вышло
     * **1022 запроса, из них успешных 978**. Разница в 44 — это ровно те 429,
     * из-за которых счётчик показывал «больше тысячи» там, где тысячи не было.
     *
     * ⚠️ Исключается ТОЛЬКО 429, а не «всё, что не 200». Остальное считается:
     * <ul>
     *   <li>422 — площадка запрос ПРИНЯЛА и отклонила по делу; расходует ли она
     *       на это токен, неизвестно (за шесть суток случай ровно один);</li>
     *   <li>{@code status IS NULL} — ответа не было вовсе, и дошёл ли запрос до
     *       площадки, мы не знаем. Неизвестность обязана считаться расходом, а
     *       не подарком.</li>
     * </ul>
     *
     * ⚠️ Это СОЗНАТЕЛЬНОЕ ослабление осторожности, и вот чем оно оплачено: если
     * 429 всё же расходуют квоту, мы теперь недосчитываем. Сделано намеренно —
     * настройка долей (1050 при тысяче площадки) и заведена ради того, чтобы
     * однажды увидеть пробой суточного ведра.
     */
    public synchronized long placementsSince(long fromMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM exec_request WHERE method = 'POST' "
                        + "AND path LIKE '%/orders' AND ts_ms >= ? "
                        + "AND (status IS NULL OR status <> 429)")) {
            ps.setLong(1, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            // Считать нечем — значит верхнюю границу неизвестна. Возвращаем
            // предел, а не ноль: неизвестность обязана останавливать торговлю,
            // а не разрешать её.
            log.error("не удалось посчитать постановки за окно: {}", e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    /**
     * Включено ли котирование — по ПОСЛЕДНЕМУ из событий start/stop/boot.
     *
     * ⚠️ Именно последнему по времени, а не «встречался ли start». Остановленный
     * командой бот хранит в журнале оба события, и проверка на наличие показала
     * бы его работающим.
     *
     * ⚠️ <b>{@code boot} считается выключением, и это не мелочь.</b> Перезапуск
     * процесса гасит котирование МОЛЧА: событие {@code stop} пишет только
     * команда человека, а поднявшийся бот просто стартует с выключенным флагом.
     * Без {@code boot} в этом запросе последним в журнале остаётся давнишний
     * {@code start}, и сводный бот бодро показывает «торгует» у всех шести,
     * которые на самом деле стоят. Замечено 07.09.2026 после выкатки: последний
     * start в 15:04, за ним два boot, а в сводке — шесть зелёных строк.
     */
    /** Когда котирование последний раз включали ({@code start}); 0 — не включали. */
    public synchronized long lastStartMs() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(ts_ms) FROM exec_event WHERE kind = 'start'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized boolean quotingOn() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT kind FROM exec_event WHERE kind IN ('start','stop','boot') "
                        + "ORDER BY ts_ms DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && "start".equals(rs.getString(1));
        } catch (Exception e) {
            log.warn("не прочитал состояние котирования: {}", e.toString());
            return false;
        }
    }

    /** Время последнего тика котировки: мера того, жив ли бот вообще. */
    public synchronized long lastQuoteMs() {
        return queryLong("SELECT MAX(ts_ms) FROM exec_quote");
    }

    /** Последняя справедливая цена — ею оценивается непроданный остаток. */
    public synchronized double lastFair() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT fair FROM exec_quote WHERE fair > 0 ORDER BY ts_ms DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDouble(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long queryLong(String sql) {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Последний тик котировки: разрешили ли гейты и, если нет, почему. */
    public record LastQuote(long tsMs, boolean quotable, String reason) {
    }

    /**
     * Чем бот занят прямо сейчас.
     *
     * ⚠️ «Котирование включено» и «бот торгует» — РАЗНЫЕ вещи, и путать их
     * дорого. Бот E 06.09.2026 час стоял с включённым котированием и не
     * торговал вовсе: гейт по ширине опорной книги закрывался, заявки уходили в
     * отвод, а по сводке он выглядел работающим.
     */
    public synchronized LastQuote lastQuote() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ts_ms, quotable, reason FROM exec_quote ORDER BY ts_ms DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new LastQuote(rs.getLong(1), rs.getInt(2) != 0, rs.getString(3));
            }
        } catch (Exception e) {
            log.warn("не прочитал последнюю котировку: {}", e.toString());
        }
        return new LastQuote(0, false, null);
    }

    /** Сколько раз бот отводил заявки за окно — прямой признак закрытого гейта. */
    public synchronized long parksSince(long fromMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM exec_event WHERE kind = 'park' AND ts_ms > ?")) {
            ps.setLong(1, fromMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized long countRequests() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM exec_request")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("журнал закрылся с ошибкой: {}", e.getMessage());
        }
    }
}
