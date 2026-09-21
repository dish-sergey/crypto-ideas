package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * РЕАКЦИЯ НА СВИП: временный сдвиг опоры в сторону крупного заказа.
 *
 * <h2>Что измерено, прежде чем это писать</h2>
 *
 * Свип — цепочка принтов одной стороны в пределах 100 мс. После КРУПНОГО свипа
 * (верхние 10% по номиналу) опора идёт В СТОРОНУ свипа, и путь этот измерен на
 * 414 событиях с двумя контролями (задачи A81–A84):
 *
 * <pre>
 *   от конца свипа:  1 с   5 с   15 с   30 с   60 с   5 мин   15 мин
 *   путь, б.п.:     0.61  0.85   1.30   1.82   3.40    3.97     1.04
 *   t:              5.56  5.40   5.14   6.22   7.77    4.39     0.72
 * </pre>
 *
 * 🔑 Числа даны ПОСЛЕ внутричасового контроля (средний путь из остальных минут
 * того же часа): без него пятиминутный путь выглядел как 6.53, а
 * пятнадцатиминутный — как 6.08, и то и другое было на треть и полностью
 * направленностью часа, а не свойством свипа.
 *
 * ⚠️ Отсюда же горизонт действия: **информация свипа живёт минуту-две и к
 * пятнадцатой минуте исчезает** (`t` = 0.72). Сдвиг обязан затухать, иначе бот
 * будет держать перекос там, где эффекта уже нет.
 *
 * <h2>Что делает</h2>
 *
 * Ожидаемый ОСТАТОК пути = {@code ИТОГ − путь(τ)}, где τ — время от конца свипа.
 * На него и сдвигается опора: бот котирует вокруг ОЖИДАЕМОЙ середины, а не
 * текущей. Это форма «сдвиг вместо гейта» (док. 179): она действует на обе
 * стороны и ничего не блокирует.
 *
 * ⚠️ Сдвиг НЕ является предсказанием цены в обычном смысле: он гасит ровно ту
 * часть будущего хода, которую мы успеваем увидеть. Всё, что прошло до нашего
 * опроса ленты, уже недоступно — поэтому частота опроса ленты (задача A83) и
 * решает, сколько от этого остаётся.
 *
 * <h2>Почему читает базу стенда</h2>
 *
 * Лента пишется тем же сборщиком, что и книга, в ту же базу; с 21.09.2026
 * торгуемые пары опрашиваются раз в 3 секунды (A83), то есть принт становится
 * виден в среднем через 2 с. Отдельного потока для этого заводить не нужно.
 */
public final class SweepWatch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SweepWatch.class);

    /** Измеренный путь после свипа: секунды от конца свипа и базисные пункты. */
    private static final double[] CURVE_SEC = {1, 5, 15, 30, 60, 300};
    private static final double[] CURVE_BP = {0.61, 0.85, 1.30, 1.82, 3.40, 3.97};

    /** Итог пути: дальше он не растёт, а к 15 минутам эффект исчезает вовсе. */
    private static final double TOTAL_BP = 3.97;

    /** Принты дальше этого в прошлое не читаем — они уже ничего не двигают. */
    private static final long LOOKBACK_MS = 300_000;

    private final Connection connection;
    private final String symbol;
    private final double minNotional;
    private final long chainMs;
    private final double coefficient;
    private final double maxBp;
    /**
     * Задержка узнавания ленты, мс: принты моложе неё бот ещё не видит.
     *
     * 🔑 Измерено: после A83 торгуемые пары опрашиваются раз в 3 с, средняя
     * задержка 2.0 с. На стенде без этой поправки получился бы бот с мгновенной
     * лентой — то есть результат, которого живьём не бывает.
     */
    private final long delayMs;

    /** Последний ПРИМЕНЁННЫЙ свип: конец, знак. Нужен, чтобы не логировать каждый тик. */
    private long lastSeenEndMs;

    /**
     * @param dbPath      база стенда (та же, что у {@link StandReader})
     * @param symbol      пара в формате ленты, например {@code BTC/USDC}
     * @param minNotional порог «крупного» свипа в долларах
     * @param chainMs     склейка принтов в один свип, мс
     * @param coefficient доля ожидаемого остатка, на которую двигаем опору
     * @param maxBp       потолок сдвига, б.п.
     */
    public SweepWatch(String dbPath, String symbol, double minNotional, long chainMs,
                      double coefficient, double maxBp) {
        this(dbPath, symbol, minNotional, chainMs, coefficient, maxBp, 2_000);
    }

    public SweepWatch(String dbPath, String symbol, double minNotional, long chainMs,
                      double coefficient, double maxBp, long delayMs) {
        this.delayMs = delayMs;
        this.symbol = symbol;
        this.minNotional = minNotional;
        this.chainMs = chainMs;
        this.coefficient = coefficient;
        this.maxBp = maxBp;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
        } catch (Exception e) {
            throw new IllegalStateException("не открыть базу стенда для ленты: " + dbPath, e);
        }
        log.info("сторож свипов: {} от ${}, склейка {} мс, доля {}, потолок {} б.п., задержка {} мс",
                symbol, Math.round(minNotional), chainMs, coefficient, maxBp, delayMs);
    }

    /**
     * Сдвиг опоры ПРЯМО СЕЙЧАС, в базисных пунктах со знаком: плюс — вверх.
     *
     * ⚠️ Ноль означает «нет свежего крупного свипа», а не «ошибка»: свипы редки
     * (верхние 10% по номиналу — это около двадцати событий в сутки).
     */
    public double shiftBp(long nowMs) {
        Sweep last = lastSweep(nowMs);
        if (last == null) {
            return 0;
        }
        double tau = (nowMs - last.endMs) / 1000.0;
        double remaining = TOTAL_BP - path(tau);
        if (remaining <= 0) {
            return 0;
        }
        // Потолок режет ВЕЛИЧИНУ, а не значение со знаком: при отрицательной доле
        // (плацебо с перевёрнутым знаком) `Math.min` пропустил бы что угодно.
        double raw = coefficient * remaining;
        double bp = Math.copySign(Math.min(maxBp, Math.abs(raw)), raw) * last.side;
        lastSeenEndMs = last.endMs;
        return bp;
    }

    /** Конец последнего крупного свипа и его знак; {@code null} — такого нет. */
    public Sweep lastSweep(long nowMs) {
        Sweep best = null;
        // 🔑 ⚠️ ВЕРХНЯЯ ГРАНИЦА ОБЯЗАТЕЛЬНА, И БЕЗ НЕЁ ПРИБОР ЛЖЁТ НА СТЕНДЕ.
        //
        // Живьём будущих принтов в базе нет, и запрос без верхней границы
        // безобиден. В ПОВТОРЕ база содержит всю запись целиком, а часы
        // симулированные, — и тот же запрос вернул бы сделки из будущего. Бот
        // «реагировал» бы на свип до того, как тот случился, и стенд показал бы
        // блестящий результат, которого живьём не бывает.
        //
        // ⚠️ И вторая граница, {@code delayMs}: живой бот видит принт не в
        // момент сделки, а когда лента доедет (2.0 с после A83). Без этой
        // поправки стенд моделирует бота с мгновенной лентой.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ts_ms, price, qty, side FROM revx_trade"
                        + " WHERE symbol = ? AND ts_ms >= ? AND ts_ms <= ? ORDER BY ts_ms")) {
            ps.setString(1, symbol);
            ps.setLong(2, nowMs - LOOKBACK_MS);
            ps.setLong(3, nowMs - delayMs);
            try (ResultSet rs = ps.executeQuery()) {
                long chainStart = 0;
                long chainEnd = 0;
                int chainSide = 0;
                double chainNotional = 0;
                while (rs.next()) {
                    long ts = rs.getLong(1);
                    String s = rs.getString(4);
                    int side = s != null && s.toLowerCase().startsWith("b") ? 1 : -1;
                    boolean same = side == chainSide && ts - chainEnd <= chainMs;
                    if (!same) {
                        if (chainNotional >= minNotional && chainSide != 0) {
                            best = new Sweep(chainStart, chainEnd, chainSide, chainNotional);
                        }
                        chainStart = ts;
                        chainSide = side;
                        chainNotional = 0;
                    }
                    chainNotional += rs.getDouble(2) * rs.getDouble(3);
                    chainEnd = ts;
                }
                if (chainNotional >= minNotional && chainSide != 0) {
                    best = new Sweep(chainStart, chainEnd, chainSide, chainNotional);
                }
            }
        } catch (Exception e) {
            // ⚠️ Отказ чтения ленты НЕ должен останавливать котирование: сдвиг —
            // добавка к опоре, а не условие торговли. Молча возвращаем «нет
            // свипа» и работаем как раньше.
            log.warn("лента {}: {}", symbol, e.toString());
            return null;
        }
        return best;
    }

    /** Свип: начало, конец, знак (+1 покупатель), номинал в долларах. */
    public record Sweep(long startMs, long endMs, int side, double notional) {
    }

    /** Был ли этот свип уже учтён предыдущим вызовом — чтобы не сорить в журнал. */
    public boolean isNew(Sweep sweep) {
        return sweep != null && sweep.endMs() != lastSeenEndMs;
    }

    /**
     * Измеренный путь к моменту τ секунд после свипа, линейной интерполяцией.
     *
     * ⚠️ Вне сетки: до первой точки — пропорционально, после последней — итог.
     * Экстраполировать нечем, да и незачем: к пяти минутам путь уже стоит.
     */
    static double path(double tauSec) {
        if (tauSec <= 0) {
            return 0;
        }
        if (tauSec >= CURVE_SEC[CURVE_SEC.length - 1]) {
            return TOTAL_BP;
        }
        if (tauSec <= CURVE_SEC[0]) {
            return CURVE_BP[0] * tauSec / CURVE_SEC[0];
        }
        for (int i = 1; i < CURVE_SEC.length; i++) {
            if (tauSec <= CURVE_SEC[i]) {
                double w = (tauSec - CURVE_SEC[i - 1]) / (CURVE_SEC[i] - CURVE_SEC[i - 1]);
                return CURVE_BP[i - 1] + w * (CURVE_BP[i] - CURVE_BP[i - 1]);
            }
        }
        return TOTAL_BP;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("не закрыть чтение ленты: {}", e.toString());
        }
    }
}
