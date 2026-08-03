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
 * Детектор режима рынка v3 (док. 01-detektor-rezhima-v3). Ревизия {@link RegimeDetectorV2}
 * по итогам диагностики (18-ispravlenie-zalipaniya-crash-v2, фаза A):
 * <ul>
 *   <li>ось <b>S удалена</b>, состояние <b>CRASH удалено</b> (A4: строго хуже отсутствия);</li>
 *   <li><b>dd_speed удалён</b> как дефект определения (храповик, A1/A2);</li>
 *   <li><b>vol_z перенесён в модификатор stress_level</b>, по умолчанию выключенный
 *       ({@link #STRESS_MULTIPLIER_ENABLED} = false) — до разбора провала voltarget (doc 19)
 *       он вычисляется и логируется, но не влияет ни на состояние, ни на экспозицию.</li>
 * </ul>
 * Оси D и T, пороги, dwell, confidence — <b>без изменений с v2</b> (пороги не
 * перекалибровались после удаления CRASH, док. v3 §6.1). Чистые функции компонентов
 * переиспользуются из {@link RegimeDetectorV2}. Разметка пишется в regime_daily_v3
 * строго по available_at (без look-ahead, док. 04 §1).
 */
@Component
public class RegimeDetectorV3 {

    private static final Logger log = LoggerFactory.getLogger(RegimeDetectorV3.class);

    /** Множитель экспозиции от stress_level по умолчанию выключен (док. v3 §2.4). */
    private static final boolean STRESS_MULTIPLIER_ENABLED = false;

    private static final int SMA_N = 200;
    private static final int ATR_N = 90;
    private static final int SLOPE_N = 30;
    private static final int WARMUP = SMA_N + SLOPE_N - 1;   // 229 — первый валидный D
    private static final int VOL_N = 7;                       // реализованная волатильность
    private static final int VOL_Z_N = 365;                   // окно z-score для stress_level
    private static final int OI_LOOKBACK = 14;
    private static final int MACRO_LOOKBACK = 90;
    private static final double MC_SCALE = 1e9;
    private static final long DAY_MS = 86_400_000L;

    private static final double LEV_OI_GROWTH = 0.20;
    private static final double LEV_FUNDING = 0.0005;
    private static final int LEV_FUNDING_DAYS = 7;

    private final Db db;

    public RegimeDetectorV3(Db db) {
        this.db = db;
    }

    private record Candle(LocalDate day, double high, double low, double close, long closeTime) {}
    private record Onchain(long availableAt, double mvrv, double mc) {}
    private record SymClose(String symbol, LocalDate day, double close) {}
    private record TsVal(long ts, double value) {}

    /** Разметка v3 с fromDay до последней дневной свечи BTC. */
    public void backfill(String fromDay) {
        LocalDate from = LocalDate.parse(fromDay);
        List<Candle> c = loadCandles();
        int n = c.size();
        if (n < WARMUP + 1) {
            log.warn("detector-v3: мало свечей BTC ({}), нужно > {}", n, WARMUP);
            return;
        }
        double[] high = new double[n], low = new double[n], close = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = c.get(i).high();
            low[i] = c.get(i).low();
            close[i] = c.get(i).close();
        }

        // ---- ценовые оси (каузально): D, T переиспользуют логику v2 ----
        double[] sma = sma(close);
        double[] atr = atr(high, low, close);
        double[] adx = RegimeDetectorV2.adx(high, low, close);
        double[] tRaw = new double[n], tRank = new double[n];
        double[] dAxis = new double[n], dDist = new double[n], dSlope = new double[n];
        double[] stress = rv(close);   // сперва реализованная волатильность 7д
        Arrays.fill(tRaw, Double.NaN);
        Arrays.fill(tRank, Double.NaN);
        Arrays.fill(dAxis, Double.NaN);
        double[] stressLevel = new double[n];
        Arrays.fill(stressLevel, Double.NaN);

        for (int i = 0; i < n; i++) {
            if (i >= WARMUP) {
                RegimeDetectorV2.Direction dir = RegimeDetectorV2.direction(close[i], sma[i], sma[i - SLOPE_N], atr[i]);
                if (dir != null) {
                    dAxis[i] = dir.d();
                    dDist[i] = dir.distTerm();
                    dSlope[i] = dir.slopeTerm();
                }
            }
            Double tr = RegimeDetectorV2.trendRaw(close, adx, i);
            if (tr != null) {
                tRaw[i] = tr;
            }
            Double sl = stressLevel(stress, i);
            if (sl != null) {
                stressLevel[i] = sl;
            }
        }
        RegimeDetectorV2.percentileRank(tRaw, tRank);

        // ---- модификаторы (as-of по available_at) ----
        List<Onchain> oc = loadOnchain();
        Map<LocalDate, long[]> breadthMap = loadBreadth();
        List<TsVal> funding = loadFunding();
        List<TsVal> oi = loadOi();
        List<TsVal> dff = loadMacro("DFF");
        List<TsVal> walcl = loadMacro("WALCL");
        List<TsVal> dxy = loadMacro("DTWEXBGS");

        int ocIdx = 0, oiIdx = 0, dffIdx = 0, walIdx = 0, dxyIdx = 0, fIdx = 0;
        double mcSum = 0, mcSumSq = 0;
        long mcCount = 0;
        double curMvrv = Double.NaN, curMcRaw = Double.NaN, curOi = Double.NaN;
        double curDff = Double.NaN, curWal = Double.NaN, curDxy = Double.NaN;
        ArrayDeque<TsVal> fWin = new ArrayDeque<>();
        double fSum = 0;

        double[] oiRatio = new double[n];
        double[] dffA = new double[n], walA = new double[n], dxyA = new double[n];
        Arrays.fill(oiRatio, Double.NaN);
        int highFundingStreak = 0;

        RegimeFsmV3 fsm = new RegimeFsmV3();
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

            oiRatio[i] = (!Double.isNaN(curOi) && !Double.isNaN(curMcRaw) && curMcRaw > 0)
                    ? curOi / curMcRaw : Double.NaN;
            dffA[i] = curDff;
            walA[i] = curWal;
            dxyA[i] = curDxy;
            double f30 = fWin.isEmpty() ? Double.NaN : fSum / fWin.size();
            highFundingStreak = (!Double.isNaN(f30) && f30 > LEV_FUNDING) ? highFundingStreak + 1 : 0;

            if (i < WARMUP || Double.isNaN(dAxis[i]) || Double.isNaN(tRank[i])) {
                continue;
            }

            String phase = RegimeDetectorV2.cyclePhase(
                    RegimeDetectorV2.mvrvZ(curMvrv, curMcRaw, mcSum, mcSumSq, mcCount));
            Integer macro = i >= MACRO_LOOKBACK
                    ? RegimeDetectorV2.macroFlag(dffA[i], dffA[i - MACRO_LOOKBACK], walA[i], walA[i - MACRO_LOOKBACK],
                    dxyA[i], dxyA[i - MACRO_LOOKBACK]) : null;
            Double breadth = RegimeDetectorV2.breadth(breadthMap.get(cur.day()));
            boolean lev = leverageWarning(i, oiRatio, close, highFundingStreak);
            Double sl = Double.isNaN(stressLevel[i]) ? null : stressLevel[i];

            RegimeFsmV3.State state = fsm.step(dAxis[i], tRank[i]);   // крутится и на прогреве
            if (cur.day().isBefore(from)) {
                continue;
            }

            int failed = (phase == null ? 1 : 0) + (macro == null ? 1 : 0) + (breadth == null ? 1 : 0);
            double conf = RegimeDetectorV2.confidence(dAxis[i], dDist[i], dSlope[i], fsm.daysInState(), lev, failed);
            // множитель по умолчанию выключен -> stress_multiplier_applied всегда 0
            int stressApplied = STRESS_MULTIPLIER_ENABLED && sl != null ? 1 : 0;

            db.upsert("INSERT OR REPLACE INTO regime_daily_v3(day, d, t, cycle_phase, macro_flag, breadth, "
                            + "leverage_warning, stress_level, stress_multiplier_applied, state, confidence, "
                            + "days_in_state, available_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    cur.day().toString(), dAxis[i], tRank[i], phase, macro == null ? null : macro, breadth,
                    lev ? 1 : 0, sl, stressApplied, state.name(), conf, fsm.daysInState(), cur.closeTime());
            written++;
        }
        log.info("detector-v3: разметка по {} дням (с {}), stress_multiplier_enabled={}",
                written, from, STRESS_MULTIPLIER_ENABLED);
    }

    // ---- stress_level (перенесённый vol_z, не влияет на состояние) ----

    /** Реализованная волатильность 7д: std дневных лог-доходностей. */
    private static double[] rv(double[] close) {
        int n = close.length;
        double[] ret = new double[n];
        ret[0] = Double.NaN;
        for (int i = 1; i < n; i++) {
            ret[i] = Math.log(close[i] / close[i - 1]);
        }
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        for (int i = VOL_N; i < n; i++) {
            double sum = 0, sumSq = 0;
            for (int j = i - VOL_N + 1; j <= i; j++) {
                sum += ret[j];
                sumSq += ret[j] * ret[j];
            }
            double mean = sum / VOL_N;
            out[i] = Math.sqrt(Math.max(sumSq / VOL_N - mean * mean, 0));
        }
        return out;
    }

    /** stress_level = clip(vol_z / 3, 0, 1); vol_z — z-score реализованной волатильности 7д к 1 году. */
    static Double stressLevel(double[] rv, int i) {
        if (i < VOL_Z_N || Double.isNaN(rv[i])) {
            return null;
        }
        double sum = 0, sumSq = 0;
        int cnt = 0;
        for (int j = i - VOL_Z_N + 1; j <= i; j++) {
            if (!Double.isNaN(rv[j])) {
                sum += rv[j];
                sumSq += rv[j] * rv[j];
                cnt++;
            }
        }
        if (cnt < 30) {
            return null;
        }
        double mean = sum / cnt;
        double std = Math.sqrt(Math.max(sumSq / cnt - mean * mean, 0));
        double z = std <= 0 ? 0 : (rv[i] - mean) / std;
        return Math.max(0, Math.min(1, z / 3));
    }

    private static boolean leverageWarning(int i, double[] oiRatio, double[] close, int highFundingStreak) {
        boolean oiWarn = i >= OI_LOOKBACK && !Double.isNaN(oiRatio[i]) && !Double.isNaN(oiRatio[i - OI_LOOKBACK])
                && oiRatio[i - OI_LOOKBACK] > 0
                && oiRatio[i] / oiRatio[i - OI_LOOKBACK] - 1 >= LEV_OI_GROWTH
                && close[i] <= close[i - OI_LOOKBACK];
        return oiWarn || highFundingStreak > LEV_FUNDING_DAYS;
    }

    // ---- индикаторы (sma/atr — локальные, как в v2) ----

    private static double[] sma(double[] close) {
        int n = close.length;
        double[] out = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += close[i];
            if (i >= SMA_N) {
                sum -= close[i - SMA_N];
            }
            out[i] = i >= SMA_N - 1 ? sum / SMA_N : Double.NaN;
        }
        return out;
    }

    private static double[] atr(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            double pc = close[i - 1];
            tr[i] = Math.max(high[i] - low[i], Math.max(Math.abs(high[i] - pc), Math.abs(low[i] - pc)));
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

    // ---- загрузка (те же источники, что v2) ----

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

    private static LocalDate day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
