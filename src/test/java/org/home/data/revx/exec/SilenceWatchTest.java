package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СТОРОЖ МОЛЧАНИЯ: включён и не тикает — тревога; выключен и не тикает — норма.
 *
 * <h2>Зачем</h2>
 *
 * 16.09.2026 пять ботов из шести умерли в течение часа после выкатки (jar
 * перезаписали под работающими процессами, классы грузятся лениво), и никто не
 * узнал об этом двадцать три часа: процесс жив, {@code systemctl} показывает
 * {@code active (running)}, а сводка — 🟢 «торгует», потому что флаг котирования
 * она берёт из журнала, где записана команда человека, а не состояние потока.
 *
 * Здесь закреплено само правило: признаком служит ОТСУТСТВИЕ ТИКОВ у бота,
 * который числится включённым. Три состояния, которые обязаны различаться:
 * <ul>
 *   <li>включён и тикает — норма;</li>
 *   <li>включён и молчит — тревога (мёртвый поток);</li>
 *   <li>выключен и молчит — норма: остановленный бот не тикает, и будить
 *       по нему нельзя, иначе тревога станет фоном.</li>
 * </ul>
 */
class SilenceWatchTest {

    /** Журнал с последним событием котирования и последним тиком. */
    private static String journal(Path dir, String name, String lastKind, long tickMs) {
        String path = dir.resolve(name).toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE exec_event(id INT, ts_ms INT, kind TEXT, detail TEXT)");
            st.execute("CREATE TABLE exec_quote(ts_ms INT, fair REAL, bid REAL, ask REAL,"
                    + " inventory REAL, quotable INT, reason TEXT, pressure REAL)");
            st.execute("INSERT INTO exec_event(ts_ms, kind, detail) VALUES ("
                    + (tickMs - 60_000) + ", 'boot', '')");
            st.execute("INSERT INTO exec_event(ts_ms, kind, detail) VALUES ("
                    + (tickMs - 30_000) + ", '" + lastKind + "', '')");
            st.execute("INSERT INTO exec_quote(ts_ms, fair, inventory, quotable)"
                    + " VALUES (" + tickMs + ", 100.0, 0.0, 1)");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return path;
    }

    /** То же правило, что в {@code InfoBot.watchSilence}. */
    private static boolean silent(String path, long now) {
        try (ExecJournal j = ExecJournal.readOnly(path)) {
            long tick = j.lastQuoteMs();
            return j.quotingOn() && tick > 0 && now - tick > InfoBot.SILENCE_MS;
        }
    }

    @Test
    void quotingAndTickingIsFine(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        String p = journal(dir, "live.db", "start", now - 1_000);
        assertFalse(silent(p, now), "бот тикает секунду назад — тревоги быть не должно");
    }

    @Test
    void quotingAndSilentIsAnAlarm(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        String p = journal(dir, "dead.db", "start", now - 23 * 3_600_000L);
        assertTrue(silent(p, now), "включён, а тиков нет 23 часа — это мёртвый поток");
    }

    @Test
    void stoppedAndSilentIsNormal(@TempDir Path dir) {
        long now = System.currentTimeMillis();
        String p = journal(dir, "stopped.db", "stop", now - 23 * 3_600_000L);
        assertFalse(silent(p, now), "остановленный бот не тикает по построению");
    }

    @Test
    void freshRestartIsNotAnAlarm(@TempDir Path dir) {
        // ⚠️ boot считается ВЫКЛЮЧЕНИЕМ (см. ExecJournal.quotingOn): поднявшийся
        // процесс стартует с погашенным котированием, поэтому окно выкатки под
        // порог не попадает вовсе — даже если тиков ещё нет совсем.
        long now = System.currentTimeMillis();
        String p = journal(dir, "booted.db", "boot", now - 10 * 60_000L);
        assertFalse(silent(p, now), "после перезапуска котирование выключено — не тревога");
    }

    @Test
    void thresholdSurvivesTheLongestKnownPause(@TempDir Path dir) {
        // Самая длинная штатная пауза — отвод на затыке площадки, 60 с
        // (venue_stall). Тики в ней пишутся, но порог обязан быть с запасом.
        long now = System.currentTimeMillis();
        String p = journal(dir, "stalled.db", "start", now - 90_000);
        assertFalse(silent(p, now), "минутная пауза площадки не должна будить");
        assertTrue(InfoBot.SILENCE_MS > 60_000, "порог обязан быть шире паузы venue_stall");
    }
}
