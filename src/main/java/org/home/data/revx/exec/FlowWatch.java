package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * СДВИГ ОПОРЫ ПО ПЕРЕВЕСУ ТЕЙКЕРОВ БИНАНСА (док. 179 — форма, док. 178 — величина).
 *
 * <h2>Что измерено, прежде чем это писать</h2>
 *
 * В минутной свече архива Бинанса есть {@code taker_buy_volume} и {@code volume},
 * то есть перевес считается по ОБЪЁМУ десятков тысяч сделок, а не по нашей тонкой
 * ленте. Отсюда и надёжность: {@code t} = 19.18 на минутном окне против 3.65 у
 * ленты Revolut (задача A80).
 *
 * ⚠️ Но величина от этого не выросла: типичный предсказанный ход —
 * <b>0.46–0.64 б.п.</b>, до 0.87 в сильных случаях. Это в разы меньше сдвига по
 * свипу (до 6 б.п.) и в десять раз меньше боевого отступа.
 *
 * <h2>Форма: сдвиг, а не гейт</h2>
 *
 * Опора двигается на {@code доля × наклон × перевес}: бот котирует вокруг
 * ожидаемой середины. Гейт убирал бы вместе с плохими сделками и хорошие — на
 * этом провалились гейты A46.
 *
 * <h2>🔑 ⚠️ ГЛАВНОЕ ОГРАНИЧЕНИЕ: СВЕЧА ГОТОВА ТОЛЬКО ПОСЛЕ ЗАКРЫТИЯ МИНУТЫ</h2>
 *
 * Перевес минуты {@code [M, M+60)} известен НЕ РАНЬШЕ {@code M+60}, плюс время на
 * то, чтобы забрать свечу. А информация живёт 1–5 минут (док. 178). Значит
 * задержка съедает от четверти до половины сигнала ещё до того, как бот что-то
 * сделает, и она обязана быть параметром, а не нулём: стенд с мгновенной свечой
 * смоделировал бы бота, которого не бывает — ровно та ошибка, что чуть не
 * испортила замер свипов (док. 184).
 */
public final class FlowWatch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlowWatch.class);

    /** Минута свечи, мс. */
    private static final long MINUTE_MS = 60_000L;

    /**
     * Наклон: базисных пунктов хода на единицу перевеса.
     *
     * Взят из предрегистрированного П5.1-бис (док. 178): пятиминутное окно
     * Бинанса, {@code +2.92 б.п.} на единицу при {@code t} = 5.44. ⚠️ Перевес
     * нормирован как {@code 2·покупки/объём − 1}, то есть лежит в [−1, +1], и
     * типичные его значения — десятые доли.
     */
    public static final double SLOPE_BP = 2.92;

    private final Connection connection;
    private final String candleSymbol;
    private final double coefficient;
    private final double maxBp;
    private final long delayMs;

    private long[] closeMs;
    private double[] imbalance;

    /**
     * @param cryptoDbPath база со свечами ({@code data/crypto.db})
     * @param symbol       пара площадки, например {@code BTC/USDC}
     * @param coefficient  доля предсказанного хода, на которую двигаем опору
     * @param maxBp        потолок сдвига, б.п.
     * @param delayMs      сколько ПОСЛЕ закрытия минуты свеча становится доступна
     */
    public FlowWatch(String cryptoDbPath, String symbol, double coefficient,
                     double maxBp, long delayMs, long fromMs, long toMs) {
        this.candleSymbol = candleSymbol(symbol);
        this.coefficient = coefficient;
        this.maxBp = maxBp;
        this.delayMs = delayMs;
        try {
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + cryptoDbPath + "?mode=ro");
        } catch (Exception e) {
            throw new IllegalStateException("не открыть базу свечей: " + cryptoDbPath, e);
        }
        preload(fromMs - 10 * MINUTE_MS, toMs);
        log.info("сторож потока: {} → {}, минут {}, доля {}, потолок {} б.п., "
                        + "задержка после закрытия свечи {} мс",
                symbol, candleSymbol, closeMs.length, coefficient, maxBp, delayMs);
    }

    /**
     * Пара площадки → символ свечей Бинанса.
     *
     * ⚠️ Минутные свечи с {@code taker_buy_volume} собраны только по BTCUSDT и
     * ETHUSDT; для SOL сигнала нет вовсе, и это не забывчивость, а предел данных.
     */
    private static String candleSymbol(String symbol) {
        String base = symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/')) : symbol;
        return base + "USDT";
    }

    private void preload(long fromMs, long toMs) {
        List<long[]> ts = new ArrayList<>();
        List<double[]> im = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT open_time, volume, taker_buy_volume FROM candles"
                        + " WHERE symbol = ? AND interval = '1m'"
                        + " AND open_time >= ? AND open_time <= ?"
                        + " AND volume > 0 AND taker_buy_volume IS NOT NULL"
                        + " ORDER BY open_time")) {
            ps.setString(1, candleSymbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double v = rs.getDouble(2);
                    double tb = rs.getDouble(3);
                    ts.add(new long[]{rs.getLong(1) + MINUTE_MS});
                    im.add(new double[]{2 * tb / v - 1});
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("не прочитались свечи " + candleSymbol, e);
        }
        closeMs = new long[ts.size()];
        imbalance = new double[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            closeMs[i] = ts.get(i)[0];
            imbalance[i] = im.get(i)[0];
        }
    }

    /**
     * Сдвиг опоры прямо сейчас, б.п. со знаком: плюс — вверх.
     *
     * Берётся ПОСЛЕДНЯЯ свеча, которая успела и закрыться, и доехать. Ноль
     * означает «свечи ещё нет», а не «перевес нулевой».
     */
    public double shiftBp(long nowMs) {
        int i = lastReady(nowMs);
        if (i < 0) {
            return 0;
        }
        double raw = coefficient * SLOPE_BP * imbalance[i];
        return Math.copySign(Math.min(maxBp, Math.abs(raw)), raw);
    }

    /** Индекс последней свечи, доступной к моменту {@code nowMs}; −1 — такой нет. */
    private int lastReady(long nowMs) {
        long limit = nowMs - delayMs;
        int lo = 0;
        int hi = closeMs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (closeMs[mid] <= limit) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // ⚠️ И ещё одна граница: свеча старше десяти минут сигналом уже не
        // является (информация живёт 1–5 минут, док. 178). Держать её значило бы
        // котировать вокруг вчерашнего перевеса в час, когда данных нет.
        return lo > 0 && limit - closeMs[lo - 1] <= 10 * MINUTE_MS ? lo - 1 : -1;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("не закрыть чтение свечей: {}", e.toString());
        }
    }
}
