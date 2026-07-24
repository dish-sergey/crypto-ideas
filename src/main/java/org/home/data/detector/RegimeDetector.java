package org.home.data.detector;

import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Детектор режима рынка (док. 01): по BTC-прокси считает суточный композит C1–C5,
 * прогоняет через гистерезис-FSM и пишет разметку {BULL,RANGE,BEAR,TRANSITION} в
 * regime_daily. Данные читаются строго по available_at (без look-ahead, док. 04 §1):
 * суточный цикл идёт по дням BTC-свечи, лагающие источники подтягиваются as-of.
 *
 * Компоненты: C1 тренд, C2 on-chain (MVRV), C3 деривативы (funding+OI), C4 макро, C5 breadth.
 * Отказ данных = нейтраль: null-компонент выпадает из композита, его вес перераспределяется.
 */
@Component
public class RegimeDetector {

    private static final Logger log = LoggerFactory.getLogger(RegimeDetector.class);

    private static final int SMA_N = 200;
    private static final int ATR_N = 90;
    private static final int SLOPE_N = 30;
    private static final int C1_MIN = SMA_N + SLOPE_N - 1;
    private static final int OI_LOOKBACK = 14;   // дней для штрафа OI/MarketCap
    private static final int MACRO_LOOKBACK = 90; // дней для тренда макро
    private static final double MC_SCALE = 1e9;   // market cap в $млрд (устойчивость std)
    private static final int C2_MIN_HISTORY = 200;
    private static final long DAY_MS = 86_400_000L;

    // Веса композита (док. 01 §2). Фиксированы до бэктеста.
    private static final double[] W = {0.30, 0.25, 0.20, 0.15, 0.10};

    private final Db db;

    public RegimeDetector(Db db) {
        this.db = db;
    }

    private record Candle(LocalDate day, double high, double low, double close, long closeTime) {}
    private record Onchain(long availableAt, double mvrv, double mc) {}
    private record SymClose(String symbol, LocalDate day, double close) {}
    private record TsVal(long ts, double value) {}

    /** Разметка режима с fromDay до последней дневной свечи BTC. */
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
        List<Onchain> oc = loadOnchain();
        Map<LocalDate, long[]> breadth = loadBreadth();
        List<TsVal> funding = loadFunding();
        List<TsVal> oi = loadOi();
        List<TsVal> dff = loadMacro("DFF");
        List<TsVal> walcl = loadMacro("WALCL");
        List<TsVal> dxy = loadMacro("DTWEXBGS");

        // as-of указатели / скользящие окна
        int ocIdx = 0, oiIdx = 0, dffIdx = 0, walIdx = 0, dxyIdx = 0, fIdx = 0;
        double mcSum = 0, mcSumSq = 0;
        long mcCount = 0;
        double curMvrv = Double.NaN, curMcRaw = Double.NaN, curOi = Double.NaN;
        double curDff = Double.NaN, curWal = Double.NaN, curDxy = Double.NaN;
        ArrayDeque<TsVal> fWin = new ArrayDeque<>();
        double fSum = 0;

        // per-index as-of ряды для lookback
        double[] price = new double[n], oiRatio = new double[n], f30 = new double[n];
        double[] dffA = new double[n], walA = new double[n], dxyA = new double[n];
        Arrays.fill(oiRatio, Double.NaN);
        Double[] c1 = new Double[n], c2 = new Double[n], c3 = new Double[n], c4 = new Double[n], c5 = new Double[n];
        boolean[] lev = new boolean[n];

        HysteresisFsm fsm = new HysteresisFsm();
        int written = 0;

        for (int i = 0; i < n; i++) {
            Candle cur = c.get(i);
            long cutoff = cur.closeTime();

            while (ocIdx < oc.size() && oc.get(ocIdx).availableAt() <= cutoff) {
                Onchain o = oc.get(ocIdx++);
                curMvrv = o.mvrv();
                curMcRaw = o.mc();
                double s = o.mc() / MC_SCALE;
                mcSum += s;
                mcSumSq += s * s;
                mcCount++;
            }
            while (oiIdx < oi.size() && oi.get(oiIdx).ts() <= cutoff) {
                curOi = oi.get(oiIdx++).value();
            }
            while (dffIdx < dff.size() && dff.get(dffIdx).ts() <= cutoff) {
                curDff = dff.get(dffIdx++).value();
            }
            while (walIdx < walcl.size() && walcl.get(walIdx).ts() <= cutoff) {
                curWal = walcl.get(walIdx++).value();
            }
            while (dxyIdx < dxy.size() && dxy.get(dxyIdx).ts() <= cutoff) {
                curDxy = dxy.get(dxyIdx++).value();
            }
            while (fIdx < funding.size() && funding.get(fIdx).ts() <= cutoff) {
                TsVal f = funding.get(fIdx++);
                fWin.addLast(f);
                fSum += f.value();
            }
            long cut30 = cutoff - 30 * DAY_MS;
            while (!fWin.isEmpty() && fWin.peekFirst().ts() < cut30) {
                fSum -= fWin.removeFirst().value();
            }

            price[i] = cur.close();
            oiRatio[i] = (!Double.isNaN(curOi) && !Double.isNaN(curMcRaw) && curMcRaw > 0)
                    ? curOi / curMcRaw : Double.NaN;
            dffA[i] = curDff;
            walA[i] = curWal;
            dxyA[i] = curDxy;
            f30[i] = fWin.isEmpty() ? Double.NaN : fSum / fWin.size();

            c1[i] = i >= C1_MIN ? computeC1(cur.close(), sma[i], sma[i - SLOPE_N], atr[i]) : null;
            c2[i] = computeC2(curMvrv, curMcRaw / MC_SCALE, mcSum, mcSumSq, mcCount);
            c5[i] = computeC5(breadth.get(cur.day()));
            c4[i] = i >= MACRO_LOOKBACK
                    ? computeC4(dffA[i], dffA[i - MACRO_LOOKBACK], walA[i], walA[i - MACRO_LOOKBACK],
                    dxyA[i], dxyA[i - MACRO_LOOKBACK]) : null;
            // C3: funding + штраф OI/MarketCap (рост плеча без роста цены за 14д)
            Double c3f = computeC3Funding(f30[i]);
            boolean warn = i >= OI_LOOKBACK && !Double.isNaN(oiRatio[i]) && !Double.isNaN(oiRatio[i - OI_LOOKBACK])
                    && oiRatio[i] > oiRatio[i - OI_LOOKBACK] && price[i] <= price[i - OI_LOOKBACK];
            if (c3f != null && warn) {
                c3[i] = clip(c3f - 0.5, -1, 1);
            } else {
                c3[i] = c3f;
            }
            lev[i] = warn;

            if (i < C1_MIN) {
                continue;
            }
            Double score = composite(c1[i], c2[i], c3[i], c4[i], c5[i]);
            HysteresisFsm.State state = fsm.step(score); // FSM крутится и до from (прогрев)
            if (cur.day().isBefore(from)) {
                continue;
            }
            double confidence = clip(Math.abs(score), 0, 1) * (lev[i] ? 0.7 : 1.0);
            db.upsert("INSERT OR REPLACE INTO regime_daily(day, c1, c2, c3, c4, c5, score, state, "
                            + "confidence, days_in_state, leverage_warning, available_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    cur.day().toString(), c1[i], c2[i], c3[i], c4[i], c5[i], score, state.name(),
                    confidence, fsm.daysInState(), lev[i] ? 1 : 0, cur.closeTime());
            written++;
        }
        log.info("detector: разметка по {} дням (с {})", written, from);
    }

    // ---- загрузка ----

    private List<Candle> loadCandles() {
        return db.query(
                "SELECT open_time, high, low, close, close_time FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> new Candle(day(rs.getLong(1)), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5)));
    }

    private List<Onchain> loadOnchain() {
        return db.query(
                "SELECT MAX(CASE WHEN metric='CapMVRVCur' THEN value END) mvrv, "
                        + "MAX(CASE WHEN metric='CapMrktCurUSD' THEN value END) mc, MAX(available_at) av "
                        + "FROM onchain_daily WHERE asset='btc' AND metric IN ('CapMVRVCur','CapMrktCurUSD') "
                        + "GROUP BY day HAVING mvrv IS NOT NULL AND mc IS NOT NULL AND mvrv>0 ORDER BY av",
                rs -> new Onchain(rs.getLong("av"), rs.getDouble("mvrv"), rs.getDouble("mc")));
    }

    private Map<LocalDate, long[]> loadBreadth() {
        List<SymClose> rows = db.query(
                "SELECT symbol, open_time, close FROM candles WHERE interval='1d' ORDER BY symbol, open_time",
                rs -> new SymClose(rs.getString(1), day(rs.getLong(2)), rs.getDouble(3)));
        Map<LocalDate, long[]> out = new HashMap<>();
        String curSym = null;
        ArrayDeque<Double> win = new ArrayDeque<>();
        double sum = 0;
        for (SymClose r : rows) {
            if (!r.symbol().equals(curSym)) {
                curSym = r.symbol();
                win.clear();
                sum = 0;
            }
            win.addLast(r.close());
            sum += r.close();
            if (win.size() > SMA_N) {
                sum -= win.removeFirst();
            }
            if (win.size() == SMA_N) {
                long[] agg = out.computeIfAbsent(r.day(), k -> new long[2]);
                agg[1]++;
                if (r.close() > sum / SMA_N) {
                    agg[0]++;
                }
            }
        }
        return out;
    }

    /** BTC+ETH binance funding (available_at = funding_time). */
    private List<TsVal> loadFunding() {
        return db.query(
                "SELECT funding_time, rate FROM funding WHERE exchange='binance' "
                        + "AND symbol IN ('BTCUSDT','ETHUSDT') ORDER BY funding_time",
                rs -> new TsVal(rs.getLong(1), rs.getDouble(2)));
    }

    private List<TsVal> loadOi() {
        return db.query(
                "SELECT ts, oi_usd FROM open_interest WHERE exchange='binance' AND symbol='BTCUSDT' "
                        + "AND oi_usd IS NOT NULL ORDER BY ts",
                rs -> new TsVal(rs.getLong(1), rs.getDouble(2)));
    }

    private List<TsVal> loadMacro(String series) {
        return db.query(
                "SELECT available_at, value FROM macro_series WHERE series_id=? AND value IS NOT NULL "
                        + "ORDER BY available_at",
                rs -> new TsVal(rs.getLong(1), rs.getDouble(2)), series);
    }

    // ---- компоненты ----

    private static Double computeC1(double close, double sma, double smaPrev, double atr) {
        if (Double.isNaN(sma) || Double.isNaN(atr) || atr <= 0) {
            return null;
        }
        double main = clip((close - sma) / atr / 4, -1, 1);
        double slope = clip((sma - smaPrev) / atr, -1, 1);
        return 0.7 * main + 0.3 * slope;
    }

    private static Double computeC2(double mvrv, double mc, double mcSum, double mcSumSq, long count) {
        if (Double.isNaN(mvrv) || mvrv <= 0 || count < C2_MIN_HISTORY) {
            return null;
        }
        double rc = mc / mvrv;
        double mean = mcSum / count;
        double std = Math.sqrt(Math.max(mcSumSq / count - mean * mean, 0));
        if (std <= 0) {
            return null;
        }
        double z = (mc - rc) / std;
        return 1 - clip(Math.abs(z - 1) / 2.5, 0, 2);
    }

    /** C3-часть funding: пик у здорового умеренного положительного, спад у перегрева и негатива. */
    private static Double computeC3Funding(double a) {
        if (Double.isNaN(a)) {
            return null;
        }
        if (a <= 0) {
            return clip(a / 0.0002, -1, 0);        // 0 -> -1 при -0.02%/8ч
        }
        if (a <= 0.0002) {
            return clip(a / 0.00005, 0, 1);        // +1 к 0.005%, плато до 0.02%
        }
        return clip(1 - (a - 0.0002) / 0.0006, -1, 1); // перегрев: спад, 0 при ~0.08%
    }

    /** C4 макро: баланс ФРС (QE) 0.4 + ставка (смягчение) 0.35 + доллар (падение) 0.25. */
    private static Double computeC4(double dff, double dffPrev, double wal, double walPrev,
                                    double dxy, double dxyPrev) {
        if (Double.isNaN(dff) || Double.isNaN(dffPrev) || Double.isNaN(wal) || Double.isNaN(walPrev)
                || Double.isNaN(dxy) || Double.isNaN(dxyPrev)) {
            return null;
        }
        double qe = Math.signum(wal - walPrev);        // баланс растёт = QE = +
        double easing = Math.signum(dffPrev - dff);    // ставка падает = смягчение = +
        double dollar = Math.signum(dxyPrev - dxy);    // DXY падает = попутный ветер = +
        return clip(0.4 * qe + 0.35 * easing + 0.25 * dollar, -1, 1);
    }

    private static Double computeC5(long[] agg) {
        if (agg == null || agg[1] < 20) {
            return null;
        }
        return clip(((double) agg[0] / agg[1] - 0.5) / 0.2, -1, 1);
    }

    /** Взвешенная сумма; null-компоненты выпадают, веса перераспределяются (отказ = нейтраль). */
    private static Double composite(Double... comp) {
        double s = 0, wsum = 0;
        for (int i = 0; i < comp.length; i++) {
            if (comp[i] != null) {
                s += W[i] * comp[i];
                wsum += W[i];
            }
        }
        return wsum > 0 ? s / wsum : null;
    }

    // ---- индикаторы ----

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

    private static LocalDate day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
