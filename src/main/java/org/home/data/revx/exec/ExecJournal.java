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
                status       TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_exec_fill_ts ON exec_fill(ts_ms);
            CREATE TABLE IF NOT EXISTS exec_state (
                key    TEXT PRIMARY KEY,
                value  REAL NOT NULL,
                ts_ms  INTEGER NOT NULL
            );
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
            }
            log.info("журнал исполнителя: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть журнал исполнителя " + path, e);
        }
    }

    /**
     * Долгоживущее состояние бота: позиция и касса.
     *
     * С двумя ботами на одном аккаунте остатки площадки перестали быть «нашей»
     * позицией — там лежит сумма обоих. Поэтому позицию каждый ведёт сам, по
     * своим исполнениям, и хранит здесь, чтобы пережить перезапуск. Остатки
     * остаются контролем: наша позиция не может превышать общую.
     */
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
     *                 числом НЕЛЬЗЯ: ведро постановок общее на трёх ботов и
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
     * Комиссия здесь не для бухгалтерии. Вся конструкция измерялась при maker 0%,
     * и это промо-тариф молодой площадки: в тот день, когда он кончится, край
     * в 8 б.п. начнёт съедаться, а по остаткам это заметят не сразу. Поэтому
     * комиссия читается из ответа по каждой сделке и любое ненулевое значение
     * останавливает торговлю.
     */
    public synchronized void fill(String venueId, String side, double qty, double price,
                                  double fair, double fee, String feeCurrency, String status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO exec_fill(ts_ms, venue_id, side, qty, price, fair, fee, fee_currency, status)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, clock.now());
            ps.setString(2, venueId);
            ps.setString(3, side);
            ps.setDouble(4, qty);
            ps.setDouble(5, price);
            ps.setDouble(6, fair);
            ps.setDouble(7, fee);
            ps.setString(8, feeCurrency);
            ps.setString(9, status);
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
    public record FillRow(long tsMs, boolean buy, double qty, double price, double fee,
                          boolean handover) {
    }

    /**
     * Все исполнения по времени. Читается целиком: за неделю их сотни, а книга
     * партий по построению требует ВСЮ историю — остаток сегодня объясняется
     * покупками произвольной давности.
     */
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
     * <b>Считаются и неудачные постановки.</b> Тратит ли площадка лимит на
     * отказ, мы тоже не знаем; считать их — консервативная сторона. 29.08.2026
     * бот A сделал 766 отказных `POST` подряд, и если они расходуют лимит, то
     * тысяча кончилась бы за один эпизод.
     */
    public synchronized long placementsSince(long fromMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM exec_request WHERE method = 'POST' "
                        + "AND path LIKE '%/orders' AND ts_ms >= ?")) {
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
