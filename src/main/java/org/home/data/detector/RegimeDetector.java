package org.home.data.detector;

import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Детектор режима рынка (док. 01): по BTC-прокси считает суточный композит C1–C5,
 * прогоняет через гистерезис-FSM и пишет разметку {BULL,RANGE,BEAR,TRANSITION} в
 * regime_daily. Данные читаются строго по available_at (без look-ahead, док. 04 §1).
 *
 * Этап реализации: C1 (тренд). C2–C5 и FSM добавляются далее.
 */
@Component
public class RegimeDetector {

    private static final Logger log = LoggerFactory.getLogger(RegimeDetector.class);

    private static final int SMA_N = 200;
    private static final int ATR_N = 90;
    private static final int SLOPE_N = 30;

    private final Db db;

    public RegimeDetector(Db db) {
        this.db = db;
    }

    private record Candle(LocalDate day, double high, double low, double close, long closeTime) {}

    /** Разметка с fromDay до последней дневной свечи BTC. */
    public void backfill(String fromDay) {
        LocalDate from = LocalDate.parse(fromDay);
        List<Candle> c = db.query(
                "SELECT open_time, high, low, close, close_time FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> new Candle(
                        Instant.ofEpochMilli(rs.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate(),
                        rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5)));
        int n = c.size();
        if (n < SMA_N + SLOPE_N) {
            log.warn("detector: мало свечей BTC ({}), нужно >= {}", n, SMA_N + SLOPE_N);
            return;
        }

        double[] sma = sma(c);
        double[] atr = atr(c);

        int written = 0;
        for (int i = SMA_N + SLOPE_N - 1; i < n; i++) { // нужен sma[i-30]
            Candle cur = c.get(i);
            if (cur.day().isBefore(from)) {
                continue;
            }
            double c1 = computeC1(cur.close(), sma[i], sma[i - SLOPE_N], atr[i]);
            db.upsert("INSERT OR REPLACE INTO regime_daily(day, c1, available_at) VALUES(?,?,?)",
                    cur.day().toString(), c1, cur.closeTime());
            written++;
        }
        log.info("detector: C1 рассчитан по {} дням (с {})", written, from);
    }

    /** Скользящее среднее close за SMA_N; NaN пока окно не заполнено. */
    private static double[] sma(List<Candle> c) {
        double[] out = new double[c.size()];
        double sum = 0;
        for (int i = 0; i < c.size(); i++) {
            sum += c.get(i).close();
            if (i >= SMA_N) {
                sum -= c.get(i - SMA_N).close();
            }
            out[i] = i >= SMA_N - 1 ? sum / SMA_N : Double.NaN;
        }
        return out;
    }

    /** ATR за ATR_N (среднее true range); NaN пока окно не заполнено. */
    private static double[] atr(List<Candle> c) {
        int n = c.size();
        double[] tr = new double[n];
        tr[0] = c.get(0).high() - c.get(0).low();
        for (int i = 1; i < n; i++) {
            double prevClose = c.get(i - 1).close();
            tr[i] = Math.max(c.get(i).high() - c.get(i).low(),
                    Math.max(Math.abs(c.get(i).high() - prevClose), Math.abs(c.get(i).low() - prevClose)));
        }
        double[] out = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tr[i];
            if (i >= ATR_N) {
                sum -= tr[i - ATR_N];
            }
            out[i] = i >= ATR_N - 1 ? sum / ATR_N : Double.NaN;
        }
        return out;
    }

    /** C1: расстояние до SMA200 (70%) + наклон SMA200 за 30д (30%), нормировка через ATR. */
    private static double computeC1(double close, double sma, double smaPrev, double atr) {
        double c1main = clip((close - sma) / atr / 4, -1, 1);       // ±4 ATR = насыщение
        double c1slope = clip((sma - smaPrev) / atr, -1, 1);        // изменение SMA200 в ATR
        return 0.7 * c1main + 0.3 * c1slope;
    }

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
