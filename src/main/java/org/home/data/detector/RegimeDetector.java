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
 * regime_daily. Данные читаются строго по available_at (без look-ahead, док. 04 §1):
 * суточный цикл идёт по дням BTC-свечи, а лагающие источники (on-chain +30ч)
 * подтягиваются as-of по их available_at.
 *
 * Этап реализации: C1 (тренд), C2 (on-chain, MVRV). C3–C5 и FSM добавляются далее.
 */
@Component
public class RegimeDetector {

    private static final Logger log = LoggerFactory.getLogger(RegimeDetector.class);

    private static final int SMA_N = 200;
    private static final int ATR_N = 90;
    private static final int SLOPE_N = 30;
    private static final int C1_MIN = SMA_N + SLOPE_N - 1;   // индекс, с которого C1 определён
    private static final double MC_SCALE = 1e9;              // считаем market cap в $млрд (устойчивость std)
    private static final int C2_MIN_HISTORY = 200;           // дней on-chain для осмысленного z

    private final Db db;

    public RegimeDetector(Db db) {
        this.db = db;
    }

    private record Candle(LocalDate day, double high, double low, double close, long closeTime) {}
    private record Onchain(long availableAt, double mvrv, double mc) {}

    /** Разметка с fromDay до последней дневной свечи BTC. */
    public void backfill(String fromDay) {
        LocalDate from = LocalDate.parse(fromDay);
        List<Candle> c = loadCandles();
        int n = c.size();
        if (n < C1_MIN + 1) {
            log.warn("detector: мало свечей BTC ({}), нужно > {}", n, C1_MIN);
            return;
        }
        double[] sma = sma(c);
        double[] atr = atr(c);
        List<Onchain> oc = loadOnchain(); // отсортирован по available_at

        // Экспандинг-статистика market cap (масштабирована) для MVRV Z-score.
        int ocIdx = 0;
        double mcSum = 0, mcSumSq = 0;
        long mcCount = 0;
        double curMvrv = Double.NaN, curMc = Double.NaN;

        int written = 0;
        for (int i = 0; i < n; i++) {
            Candle cur = c.get(i);
            long cutoff = cur.closeTime(); // конец дня D — всё с available_at <= cutoff доступно
            while (ocIdx < oc.size() && oc.get(ocIdx).availableAt() <= cutoff) {
                Onchain o = oc.get(ocIdx++);
                curMvrv = o.mvrv();
                curMc = o.mc() / MC_SCALE;
                mcSum += curMc;
                mcSumSq += curMc * curMc;
                mcCount++;
            }
            if (cur.day().isBefore(from) || i < C1_MIN) {
                continue;
            }
            Double c1 = computeC1(cur.close(), sma[i], sma[i - SLOPE_N], atr[i]);
            Double c2 = computeC2(curMvrv, curMc, mcSum, mcSumSq, mcCount);
            db.upsert("INSERT OR REPLACE INTO regime_daily(day, c1, c2, available_at) VALUES(?,?,?,?)",
                    cur.day().toString(), c1, c2, cur.closeTime());
            written++;
        }
        log.info("detector: C1+C2 рассчитаны по {} дням (с {})", written, from);
    }

    private List<Candle> loadCandles() {
        return db.query(
                "SELECT open_time, high, low, close, close_time FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> new Candle(
                        Instant.ofEpochMilli(rs.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate(),
                        rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5)));
    }

    /** On-chain btc: MVRV (CapMVRVCur) и market cap (CapMrktCurUSD) по дням, сорт. по available_at. */
    private List<Onchain> loadOnchain() {
        return db.query(
                "SELECT MAX(CASE WHEN metric='CapMVRVCur' THEN value END) mvrv, "
                        + "MAX(CASE WHEN metric='CapMrktCurUSD' THEN value END) mc, MAX(available_at) av "
                        + "FROM onchain_daily WHERE asset='btc' AND metric IN ('CapMVRVCur','CapMrktCurUSD') "
                        + "GROUP BY day HAVING mvrv IS NOT NULL AND mc IS NOT NULL AND mvrv>0 "
                        + "ORDER BY av",
                rs -> new Onchain(rs.getLong("av"), rs.getDouble("mvrv"), rs.getDouble("mc")));
    }

    /** C1: расстояние до SMA200 (70%) + наклон SMA200 за 30д (30%), нормировка через ATR. */
    private static Double computeC1(double close, double sma, double smaPrev, double atr) {
        if (Double.isNaN(sma) || Double.isNaN(atr) || atr <= 0) {
            return null;
        }
        double c1main = clip((close - sma) / atr / 4, -1, 1);   // ±4 ATR = насыщение
        double c1slope = clip((sma - smaPrev) / atr, -1, 1);    // изменение SMA200 в ATR
        return 0.7 * c1main + 0.3 * c1slope;
    }

    /**
     * C2 (on-chain, старт = только MVRV): MVRV Z-score → C2a = 1 − clip(|z−1|/2.5, 0, 2).
     * Максимум при z≈1 (здоровый рост), отрицателен у вершин (z высок), слабо+ у дна.
     * z = (MC − RC) / std(MC), RC = MC/MVRV, std — экспандинг (без look-ahead).
     */
    private static Double computeC2(double mvrv, double mc, double mcSum, double mcSumSq, long count) {
        if (Double.isNaN(mvrv) || mvrv <= 0 || count < C2_MIN_HISTORY) {
            return null; // отказ данных = нейтраль (вес перераспределится в композите)
        }
        double rc = mc / mvrv;
        double mean = mcSum / count;
        double var = mcSumSq / count - mean * mean;
        double std = Math.sqrt(Math.max(var, 0));
        if (std <= 0) {
            return null;
        }
        double z = (mc - rc) / std;
        return 1 - clip(Math.abs(z - 1) / 2.5, 0, 2);
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

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
