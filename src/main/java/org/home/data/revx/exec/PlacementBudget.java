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

/**
 * Общее ведро постановок на весь аккаунт.
 *
 * <h2>Зачем понадобилось</h2>
 *
 * Суточный лимит постановок у площадки один на АККАУНТ, а ботов шесть. До этого
 * класса бюджет резался на неподвижные доли в {@link ExecLimits}, и 07.09.2026
 * это стоило простоя: ADA выключилась на 11 часов, а PEPE на 7, упёршись в свои
 * 80 и 60, тогда как у SOL и ETH пустовало 240 неиспользованных постановок.
 * Суммарный спрос всех шести при этом — 871 из 1000, то есть бюджета хватало.
 *
 * <h2>Ведро, а не окно — потому что у площадки ведро</h2>
 *
 * Формулировка площадки — «1,000 tokens / day, 1 token / request», и пополнение
 * непрерывное: полночь ни при чём. Это не догадка, а замер на секундном ведре,
 * где при 19 запросах в секунду {@code Retry-After} приходил 334 и 507 мс —
 * ровно время до следующего токена при одном токене на 100 мс. Суточное ведро —
 * тот же механизм, 1 токен на 86.4 с.
 *
 * Зеркалить чужое ведро приходится потому, что спросить остаток НЕЛЬЗЯ: за 925
 * отказов и сотни тысяч успешных ответов единственный заголовок про лимиты —
 * {@code Retry-After}, и только на 429. Ни {@code X-RateLimit-Remaining}, ни
 * эндпоинта квоты у площадки нет.
 *
 * <h2>Почему пол, а не пропорции</h2>
 *
 * Соблазн раздавать бюджет по доходности велик: за первые сутки ADA приносила
 * +0.0031 USDC на постановку, а BTC ТЕРЯЛ 0.0004. Но это одни сутки и 33–146
 * сделок на бота — на такой выборке пары не ранжируются. Поэтому здесь только
 * защита от вытеснения: каждому гарантирован {@link #FLOOR_PER_DAY}, а остаток
 * разбирается первым пришедшим. Доходность появится в дележе тогда, когда её
 * можно будет измерить, и это будет отдельное решение.
 *
 * <h2>Согласованность между процессами</h2>
 *
 * Боты — шесть отдельных процессов, поэтому весь захват идёт одной транзакцией
 * {@code BEGIN IMMEDIATE}: SQLite отдаёт запись на файл ровно одному, остальные
 * ждут по {@code busy_timeout}. Синхронизация внутри JVM тут не помогла бы.
 *
 * ⚠️ <b>Отказ обязан быть закрытым.</b> Не сумели посчитать — не разрешаем. Иначе
 * первая же ошибка базы превращается в шесть ботов, молотящих общий лимит без
 * учёта, и суточная квота кончается на весь аккаунт, а не на одного бота.
 */
public final class PlacementBudget implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlacementBudget.class);

    /**
     * Ёмкость нашего ведра. У площадки 1000; берём 850, и запас тут не суеверие.
     *
     * Считаем мы ТОЛЬКО свои постановки, а расходовать лимит может и то, чего мы
     * не видим: отказные {@code POST} (тратит ли их площадка — неизвестно, см.
     * {@link ExecJournal#placementsSince}), ручные заявки из веб-приложения,
     * разовые команды вроде {@code --revx-order-probe}. Полтораста токенов —
     * плата за то, что зеркало неточно.
     */
    public static final double CAPACITY = 850.0;

    /** Пополнение: вся ёмкость за сутки, то есть 1 токен на 101.6 с. */
    private static final double REFILL_PER_MS = CAPACITY / 86_400_000.0;

    /**
     * Сколько постановок в сутки гарантировано каждому боту.
     *
     * Сотня — это заметно больше, чем нужно сетке из трёх уровней на поддержание
     * (замеренный спрос самых скромных — SOL 95 и ETH 99 в сутки), и вшестером
     * они резервируют 600 из 850. Остальные 250 — общий котёл.
     */
    public static final int FLOOR_PER_DAY = 100;

    private static final long DAY_MS = 86_400_000L;

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS placement_bucket (
                id         INTEGER PRIMARY KEY CHECK (id = 1),
                tokens     REAL    NOT NULL,
                updated_ms INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS placement_spend (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                bot_id TEXT    NOT NULL,
                ts_ms  INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_placement_spend ON placement_spend(ts_ms, bot_id);
            """;

    /** Что бот узнаёт о бюджете, не тратя его. */
    public record State(double tokens, long ownSpendDay, long totalSpendDay, double reserve) {

        /**
         * Насколько туго с бюджетом: 0 — ведро полно, 1 — свободного нет вовсе.
         *
         * Это и есть ручка, которой бот сужает поток вместо остановки. Свободным
         * считается то, что выше брони чужих полов: свои сто постановок бот
         * получит в любом случае, а за остальное конкурирует.
         */
        public double pressure() {
            // ⚠️ Пока свой пол не выбран, отказать боту НЕ МОГУТ — правило выдачи
            // пропускает его мимо брони. Значит и давить на него незачем.
            //
            // Без этой ветки давление считалось от чужих невыбранных полов, и
            // сразу после перезапуска, когда броня максимальна, все шестеро
            // раздвигали отступ на 8% при ведре, полном на 96%. Замечено на
            // живых ботах 07.09.2026 через минуту после включения. Цена ошибки
            // не косметическая: у биткойна лестница отступа крутая, переход
            // 10 → 14 б.п. стоит трети дохода, так что лишние 8% — это проценты
            // результата, отданные ни за что.
            if (ownSpendDay < FLOOR_PER_DAY) {
                return tokens >= 1.0 ? 0.0 : 1.0;
            }
            double free = tokens - reserve;
            double pool = Math.max(1.0, CAPACITY - reserve);
            return Math.max(0.0, Math.min(1.0, 1.0 - free / pool));
        }
    }

    private final Connection connection;
    private final int bots;

    public PlacementBudget(String path, int bots) {
        this.bots = Math.max(1, bots);
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
                // Первый запуск застаёт ведро полным: до появления этого класса
                // расход шёл по неподвижным долям, и переносить в новую схему
                // нечего. Пустое ведро на старте выключило бы всех разом.
                st.execute("INSERT OR IGNORE INTO placement_bucket (id, tokens, updated_ms) "
                        + "VALUES (1, " + CAPACITY + ", " + System.currentTimeMillis() + ")");
            }
            log.info("общее ведро постановок: {} (ёмкость {}, пол {}/сут на бота)",
                    path, (int) CAPACITY, FLOOR_PER_DAY);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть ведро постановок " + path, e);
        }
    }

    /**
     * Взять один токен под постановку.
     *
     * @return true, если постановку разрешено делать; false — бюджета нет.
     */
    public synchronized boolean tryAcquire(String botId, long nowMs) {
        try {
            begin();
            double tokens = refill(nowMs);
            long own = spendSince(botId, nowMs - DAY_MS);
            double reserve = reserveFor(botId, nowMs);
            boolean allowed = tokens >= 1.0
                    && (own < FLOOR_PER_DAY || tokens - 1.0 >= reserve);
            if (allowed) {
                writeTokens(tokens - 1.0, nowMs);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO placement_spend (bot_id, ts_ms) VALUES (?, ?)")) {
                    ps.setString(1, botId);
                    ps.setLong(2, nowMs);
                    ps.executeUpdate();
                }
            } else {
                // Пополнение всё равно фиксируем: иначе следующий вызов начислит
                // его заново от старой отметки времени и ведро будет расти вдвое.
                writeTokens(tokens, nowMs);
            }
            commit();
            return allowed;
        } catch (Exception e) {
            rollback();
            log.error("ведро постановок недоступно, постановка запрещена: {}", e.toString());
            return false;
        }
    }

    /** Состояние без расхода: для отчётов и для сужения потока. */
    public synchronized State state(String botId, long nowMs) {
        try {
            begin();
            double tokens = refill(nowMs);
            writeTokens(tokens, nowMs);
            State state = new State(tokens, spendSince(botId, nowMs - DAY_MS),
                    spendSince(null, nowMs - DAY_MS), reserveFor(botId, nowMs));
            commit();
            return state;
        } catch (Exception e) {
            rollback();
            log.error("не прочитать ведро постановок: {}", e.toString());
            // Неизвестность обязана выглядеть как дефицит, а не как изобилие.
            return new State(0, 0, 0, CAPACITY);
        }
    }

    /**
     * Сколько токенов забронировано под ПОЛЫ ОСТАЛЬНЫХ ботов.
     *
     * Ровно это мешает одному прожорливому боту съесть общий котёл: пока он не
     * исчерпал свой пол, он берёт свободно, а после — только из того, что выше
     * брони. Расход соседей считается по журналу ведра, а не по их процессам:
     * упавший бот брони не теряет, иначе перезапуск отдавал бы его долю соседям.
     */
    private double reserveFor(String botId, long nowMs) throws Exception {
        double reserve = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bot_id, COUNT(*) FROM placement_spend "
                        + "WHERE ts_ms >= ? GROUP BY bot_id")) {
            ps.setLong(1, nowMs - DAY_MS);
            java.util.Map<String, Long> spent = new java.util.HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    spent.put(rs.getString(1), rs.getLong(2));
                }
            }
            // Боты, которые сегодня ещё не ставили, в таблице отсутствуют, но
            // пол за ними числится: иначе молчащий с ночи бот обнаружил бы утром,
            // что его долю уже разобрали.
            int known = 0;
            for (var e : spent.entrySet()) {
                if (e.getKey().equals(botId)) {
                    continue;
                }
                known++;
                reserve += Math.max(0, FLOOR_PER_DAY - e.getValue());
            }
            reserve += (double) Math.max(0, bots - 1 - known) * FLOOR_PER_DAY;
        }
        return reserve;
    }

    private double refill(long nowMs) throws Exception {
        double tokens = CAPACITY;
        long updated = nowMs;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tokens, updated_ms FROM placement_bucket WHERE id = 1")) {
            if (rs.next()) {
                tokens = rs.getDouble(1);
                updated = rs.getLong(2);
            }
        }
        // Ход часов назад не должен ни начислять, ни отнимать.
        long elapsed = Math.max(0, nowMs - updated);
        return Math.min(CAPACITY, tokens + elapsed * REFILL_PER_MS);
    }

    private void writeTokens(double tokens, long nowMs) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE placement_bucket SET tokens = ?, updated_ms = ? WHERE id = 1")) {
            ps.setDouble(1, tokens);
            ps.setLong(2, nowMs);
            ps.executeUpdate();
        }
    }

    private long spendSince(String botId, long fromMs) throws Exception {
        String sql = botId == null
                ? "SELECT COUNT(*) FROM placement_spend WHERE ts_ms >= ?"
                : "SELECT COUNT(*) FROM placement_spend WHERE ts_ms >= ? AND bot_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            if (botId != null) {
                ps.setString(2, botId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Чистка следов старше двух суток: таблица расхода растёт вечно. */
    public synchronized void prune(long nowMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM placement_spend WHERE ts_ms < ?")) {
            ps.setLong(1, nowMs - 2 * DAY_MS);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("не почистить расход постановок: {}", e.getMessage());
        }
    }

    /**
     * ⚠️ Транзакция ведётся ЯВНЫМИ операторами, а не через setAutoCommit.
     * Драйвер SQLite на setAutoCommit(false) сам открывает транзакцию, и
     * следующий BEGIN IMMEDIATE падает с «cannot start a transaction within a
     * transaction» — а поскольку отказ здесь закрытый, каждая постановка молча
     * запрещалась бы. Найдено тестом до выкатки.
     */
    private void begin() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("COMMIT");
        }
    }

    private void rollback() {
        try (Statement st = connection.createStatement()) {
            st.execute("ROLLBACK");
        } catch (Exception ignored) {
            // Откатывать нечего — соединение уже в негодном состоянии.
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("ведро постановок не закрылось: {}", e.getMessage());
        }
    }
}
