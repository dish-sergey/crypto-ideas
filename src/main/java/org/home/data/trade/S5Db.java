package org.home.data.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SQLite-аудит S5 (собственная БД, отдельно от слоя данных): «сыгранные» события ({@link TradedStore} —
 * переживают рестарт) + журнал входов/выходов/Telegram ({@link TradeRecorder}). Один writer-connection,
 * WAL. Все операции best-effort: любой сбой БД логируется и НЕ влияет на торговлю (принцип 3).
 */
public class S5Db implements TradedStore, TradeRecorder {

    private static final Logger log = LoggerFactory.getLogger(S5Db.class);
    private final Connection conn;

    public S5Db(String path) {
        Connection c = null;
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS dismissed(event_id TEXT PRIMARY KEY, ts INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS trade_open(event_id TEXT, symbol TEXT, unlock_day INTEGER,"
                        + " category TEXT, qty REAL, entry_px REAL, notional_usd REAL, opened_at INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS trade_close(symbol TEXT, category TEXT, entry_px REAL,"
                        + " exit_px REAL, qty REAL, entry_notional_usd REAL, pnl_pct REAL, pnl_usd REAL,"
                        + " note TEXT, closed_at INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS tg_event(direction TEXT, kind TEXT, text TEXT, ts INTEGER)");
                // миграция старой схемы trade_close (до фин. колонок): ADD COLUMN идемпотентно (ошибка «дубль» — игнор)
                migrate(st, "ALTER TABLE trade_close ADD COLUMN qty REAL");
                migrate(st, "ALTER TABLE trade_close ADD COLUMN entry_notional_usd REAL");
                migrate(st, "ALTER TABLE trade_close ADD COLUMN pnl_usd REAL");
            }
            // прогрев JDBC4PreparedStatement — устранить интермиттентную гонку загрузки класса в nested-jar
            try (PreparedStatement ps = c.prepareStatement("SELECT 1")) { ps.executeQuery(); }
            log.info("S5 БД открыта: {}", path);
        } catch (Throwable e) {
            log.warn("S5 БД не открыта ({}): {} — аудит выключен, торговля продолжается", path, e.toString());
            c = null;
        }
        this.conn = c;
    }

    // --- TradedStore (дедуп сыгранных/снятых) ---

    @Override public synchronized void record(String eventId) {
        exec("INSERT OR IGNORE INTO dismissed(event_id, ts) VALUES(?,?)", ps -> {
            ps.setString(1, eventId); ps.setLong(2, now());
        });
    }

    @Override public synchronized Set<String> load() {
        Set<String> out = new LinkedHashSet<>();
        if (conn == null) return out;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT event_id FROM dismissed")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Throwable e) { log.warn("S5 БД load dismissed: {}", e.toString()); }
        return out;
    }

    // --- TradeRecorder (аудит) ---

    @Override public synchronized void recordOpen(String eventId, String symbol, long unlockDay, String category,
                                                  double qty, double entryPx, double notionalUsd) {
        exec("INSERT INTO trade_open(event_id,symbol,unlock_day,category,qty,entry_px,notional_usd,opened_at) VALUES(?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, eventId); ps.setString(2, symbol); ps.setLong(3, unlockDay);
                    ps.setString(4, category); ps.setDouble(5, qty); ps.setDouble(6, entryPx);
                    ps.setDouble(7, notionalUsd); ps.setLong(8, now());
                });
    }

    @Override public synchronized void recordClose(String symbol, String category, double entryPx, double exitPx,
                                                   double qty, double pnlPct, String note) {
        double entryNotional = entryPx * qty;
        double pnlUsd = (entryPx - exitPx) * qty;               // шорт: (вход − выход) × qty
        exec("INSERT INTO trade_close(symbol,category,entry_px,exit_px,qty,entry_notional_usd,pnl_pct,pnl_usd,note,closed_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, symbol); ps.setString(2, category); ps.setDouble(3, entryPx);
                    ps.setDouble(4, exitPx); ps.setDouble(5, qty); ps.setDouble(6, entryNotional);
                    ps.setDouble(7, pnlPct); ps.setDouble(8, pnlUsd); ps.setString(9, note); ps.setLong(10, now());
                });
    }

    @Override public synchronized void recordTelegram(String direction, String kind, String text) {
        exec("INSERT INTO tg_event(direction,kind,text,ts) VALUES(?,?,?,?)", ps -> {
            ps.setString(1, direction); ps.setString(2, kind); ps.setString(3, text); ps.setLong(4, now());
        });
    }

    /** Идемпотентная миграция: ADD COLUMN, «дубликат столбца» на уже-мигрированной БД игнорируем. */
    private static void migrate(Statement st, String sql) {
        try { st.execute(sql); } catch (Throwable e) { /* колонка уже есть — ок */ }
    }

    @Override public synchronized String recentHistory(int n) {
        if (conn == null) return "история недоступна (БД не открыта)";
        StringBuilder sb = new StringBuilder("📒 Последние закрытые сделки:");
        int cnt = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT symbol,category,entry_notional_usd,pnl_pct,pnl_usd"
                + " FROM trade_close ORDER BY closed_at DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cnt++;
                    double pnlUsd = rs.getDouble("pnl_usd"), pnlPct = rs.getDouble("pnl_pct");
                    sb.append("\n").append(rs.getString("symbol")).append(" ").append(rs.getString("category"))
                      .append(": вход $").append(fmt2(rs.getDouble("entry_notional_usd")))
                      .append(" → ").append(pnlUsd >= 0 ? "+" : "-").append("$").append(fmt2(Math.abs(pnlUsd)))
                      .append(" (").append(pnlPct >= 0 ? "+" : "-").append(fmt1(Math.abs(pnlPct) * 100)).append("%)");
                }
            }
        } catch (Throwable e) { return "история: ошибка чтения БД"; }
        if (cnt == 0) sb.append("\n(пока нет закрытых сделок)");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*), coalesce(sum(pnl_usd),0) FROM trade_close")) {
            if (rs.next()) sb.append("\n\nвсего сделок: ").append(rs.getInt(1))
                    .append(", суммарный результат: ").append(rs.getDouble(2) >= 0 ? "+" : "-")
                    .append("$").append(fmt2(Math.abs(rs.getDouble(2))));
        } catch (Throwable ignore) { }
        return sb.toString();
    }

    private static String fmt2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String fmt1(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private interface Binder { void bind(PreparedStatement ps) throws Exception; }

    private void exec(String sql, Binder binder) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (Throwable e) {
            log.warn("S5 БД запись: {}", e.toString());
        }
    }

    private static long now() { return System.currentTimeMillis(); }
}
