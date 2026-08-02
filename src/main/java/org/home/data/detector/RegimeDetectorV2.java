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
 * Детектор режима рынка v2 (док. 01-detektor-rezhima-v2). Ревизия {@link RegimeDetector}:
 * вместо одного скаляра score детектор выдаёт <b>вектор из трёх ортогональных осей</b>
 * плюс модификаторы, а дискретное состояние собирается явными правилами
 * ({@link RegimeFsmV2}). Разметка пишется в regime_daily_v2 строго по available_at
 * (без look-ahead, док. 04 §1).
 *
 * <ul>
 *   <li><b>D — direction</b> ∈[−1,+1]: направление тренда, <i>только цена</i>
 *       (единственный источник без ревизий задним числом и платного вендора).</li>
 *   <li><b>T — trendiness</b> ∈[0,1]: медиана ER/R²/ADX, затем процентильный ранг
 *       по скользящему окну ~3 года (устойчивость к дрейфу абсолютных уровней).</li>
 *   <li><b>S — stress</b> ∈[0,1]: max(vol_z, dd_speed[, liq_pct]). Лента ликвидаций
 *       на истории неполна — liq_pct выключен (док. 15 §2), S по двум метрикам.</li>
 * </ul>
 *
 * Модификаторы (не двигают состояние — уходят аллокатору отдельно, док. 01-v2 §2):
 * cycle_phase (on-chain MVRV-Z), macro_flag (FRED+DXY), breadth (топ-100 &gt; SMA200),
 * leverage_warning (OI/MarketCap или устойчиво высокий funding). supply_in_profit в
 * БД нет — cycle_phase считается по MVRV-Z (порог ACCUMULATION смягчён).
 *
 * Все пороги фиксированы до бэктеста (док. 01-v2 §6). Отказ данных = нейтраль:
 * недоступный модификатор выпадает, confidence снижается.
 */
@Component
public class RegimeDetectorV2 {

    private static final Logger log = LoggerFactory.getLogger(RegimeDetectorV2.class);

    // --- окна индикаторов ---
    private static final int SMA_N = 200;
    private static final int ATR_N = 90;
    private static final int SLOPE_N = 30;
    private static final int TREND_N = 90;    // ER / R² окно
    private static final int ADX_N = 14;
    private static final int VOL_N = 7;        // реализованная волатильность
    private static final int VOL_Z_N = 365;    // окно z-score волатильности
    private static final int DD_N = 30;        // окно максимума для скорости просадки
    private static final int RANK_N = 1095;    // ~3 года для процентильного ранга T
    private static final int WARMUP = SMA_N + SLOPE_N - 1;  // 229 — первый валидный D
    private static final int OI_LOOKBACK = 14;
    private static final int MACRO_LOOKBACK = 90;
    private static final double MC_SCALE = 1e9;
    private static final int MVRV_MIN_HISTORY = 200;
    private static final long DAY_MS = 86_400_000L;

    // --- пороги осей D (унаследованы из v1, док. 01-v2 §2.1) ---
    private static final double DIST_SAT = 4.0;   // ±4 ATR — насыщение dist
    private static final double SLOPE_SAT = 1.5;  // насыщение наклона

    // --- leverage_warning ---
    private static final double LEV_OI_GROWTH = 0.20;      // +20% OI/MC за 14д
    private static final double LEV_FUNDING = 0.0005;      // 0.05%/8ч
    private static final int LEV_FUNDING_DAYS = 7;         // держится > 7 дней

    private final Db db;

    public RegimeDetectorV2(Db db) {
        this.db = db;
    }

    private record Candle(LocalDate day, double high, double low, double close, long closeTime) {}
    private record Onchain(long availableAt, double mvrv, double mc) {}
    private record SymClose(String symbol, LocalDate day, double close) {}
    private record TsVal(long ts, double value) {}

    /** Направление D и две его составляющие (для agreement в confidence). */
    record Direction(double d, double distTerm, double slopeTerm) {}

    /** Разметка v2 с fromDay до последней дневной свечи BTC. */
    public void backfill(String fromDay) {
        LocalDate from = LocalDate.parse(fromDay);
        List<Candle> c = loadCandles();
        int n = c.size();
        if (n < WARMUP + 1) {
            log.warn("detector-v2: мало свечей BTC ({}), нужно > {}", n, WARMUP);
            return;
        }
        double[] high = new double[n], low = new double[n], close = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = c.get(i).high();
            low[i] = c.get(i).low();
            close[i] = c.get(i).close();
        }

        // ---- ценовые оси (всё каузально: индекс i зависит только от [0..i]) ----
        double[] sma = sma(close);
        double[] atr = atr(high, low, close);
        double[] adx = adx(high, low, close);
        double[] tRaw = new double[n];
        double[] tRank = new double[n];
        double[] dAxis = new double[n], dDist = new double[n], dSlope = new double[n];
        double[] sAxis = new double[n];
        Arrays.fill(tRaw, Double.NaN);
        Arrays.fill(tRank, Double.NaN);
        Arrays.fill(dAxis, Double.NaN);
        Arrays.fill(sAxis, Double.NaN);

        double[] rv = realizedVol(close);          // реализованная волатильность 7д
        for (int i = 0; i < n; i++) {
            if (i >= WARMUP) {
                Direction dir = direction(close[i], sma[i], sma[i - SLOPE_N], atr[i]);
                if (dir != null) {
                    dAxis[i] = dir.d();
                    dDist[i] = dir.distTerm();
                    dSlope[i] = dir.slopeTerm();
                }
            }
            Double tr = trendRaw(close, adx, i);
            if (tr != null) {
                tRaw[i] = tr;
            }
            Double s = stress(close, atr, rv, i);
            if (s != null) {
                sAxis[i] = s;
            }
        }
        percentileRank(tRaw, tRank);               // T = ранг T_raw по трейлинг-окну ~3г

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

        RegimeFsmV2 fsm = new RegimeFsmV2();
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

            if (i < WARMUP || Double.isNaN(dAxis[i]) || Double.isNaN(tRank[i]) || Double.isNaN(sAxis[i])) {
                continue;
            }

            // модификаторы
            String phase = cyclePhase(mvrvZ(curMvrv, curMcRaw, mcSum, mcSumSq, mcCount));
            Integer macro = macroFlag(i, dffA, walA, dxyA);
            Double breadth = breadth(breadthMap.get(cur.day()));
            boolean lev = leverageWarning(i, oiRatio, close, highFundingStreak);

            RegimeFsmV2.State state = fsm.step(dAxis[i], tRank[i], sAxis[i]);  // крутится и на прогреве
            if (cur.day().isBefore(from)) {
                continue;
            }

            int failed = (phase == null ? 1 : 0) + (macro == null ? 1 : 0) + (breadth == null ? 1 : 0);
            double conf = confidence(dAxis[i], dDist[i], dSlope[i], fsm.daysInState(), lev, failed);

            db.upsert("INSERT OR REPLACE INTO regime_daily_v2(day, d, t, s, cycle_phase, macro_flag, "
                            + "breadth, leverage_warning, state, confidence, days_in_state, available_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    cur.day().toString(), dAxis[i], tRank[i], sAxis[i], phase,
                    macro == null ? null : macro, breadth, lev ? 1 : 0,
                    state.name(), conf, fsm.daysInState(), cur.closeTime());
            written++;
        }
        log.info("detector-v2: разметка по {} дням (с {})", written, from);
    }

    // ================= оси =================

    /** Ось D — направление, только цена (док. 01-v2 §2.1): 0.7·dist + 0.3·slope. */
    static Direction direction(double close, double sma, double smaPrev, double atr) {
        if (Double.isNaN(sma) || Double.isNaN(smaPrev) || Double.isNaN(atr) || atr <= 0) {
            return null;
        }
        double distTerm = clip((close - sma) / atr / DIST_SAT, -1, 1);
        double slopeTerm = clip((sma - smaPrev) / atr / SLOPE_SAT, -1, 1);
        return new Direction(0.7 * distTerm + 0.3 * slopeTerm, distTerm, slopeTerm);
    }

    /** T_raw — медиана трёх измерителей трендовости (ER, R², ADX_n), окно 90д. */
    static Double trendRaw(double[] close, double[] adx, int i) {
        if (i < TREND_N || Double.isNaN(adx[i])) {
            return null;
        }
        double er = efficiencyRatio(close, i);
        double r2 = logR2(close, i);
        double adxN = clip((adx[i] - 15) / 25, 0, 1);
        return median3(er, r2, adxN);
    }

    /** Kaufman Efficiency Ratio: чистое смещение / суммарный путь за 90д. */
    static double efficiencyRatio(double[] close, int i) {
        double path = 0;
        for (int j = i - TREND_N + 1; j <= i; j++) {
            path += Math.abs(close[j] - close[j - 1]);
        }
        if (path <= 0) {
            return 0;
        }
        return clip(Math.abs(close[i] - close[i - TREND_N]) / path, 0, 1);
    }

    /** R² линейной регрессии log(P) по времени, окно 90д. */
    static double logR2(double[] close, int i) {
        int m = TREND_N;
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int k = 0; k < m; k++) {
            double x = k;
            double y = Math.log(close[i - m + 1 + k]);
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
        }
        double covxy = m * sxy - sx * sy;
        double varx = m * sxx - sx * sx;
        double vary = m * syy - sy * sy;
        if (varx <= 0 || vary <= 0) {
            return 0;
        }
        double r = covxy / Math.sqrt(varx * vary);
        return clip(r * r, 0, 1);
    }

    /** Ось S — стресс (док. 01-v2 §2.3), liq_pct выключен: max(vol_z-терм, dd_speed-терм). */
    static Double stress(double[] close, double[] atr, double[] rv, int i) {
        Double volZ = volZScore(rv, i);
        double ddSpeed = ddSpeed(close, atr, i);
        double a = volZ == null ? 0 : clip(volZ / 3, 0, 1);
        double b = Double.isNaN(ddSpeed) ? 0 : clip(ddSpeed / 6, 0, 1);
        if (volZ == null && Double.isNaN(ddSpeed)) {
            return null;
        }
        return Math.max(a, b);
    }

    private static Double volZScore(double[] rv, int i) {
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
        return std <= 0 ? 0.0 : (rv[i] - mean) / std;
    }

    private static double ddSpeed(double[] close, double[] atr, int i) {
        if (i < DD_N || Double.isNaN(atr[i]) || atr[i] <= 0) {
            return Double.NaN;
        }
        double peak = close[i];
        for (int j = i - DD_N + 1; j <= i; j++) {
            peak = Math.max(peak, close[j]);
        }
        return (peak - close[i]) / atr[i];
    }

    // ================= модификаторы =================

    /** MVRV Z-score: (MarketCap − RealizedCap) / std(MarketCap). RealizedCap = MC/MVRV. */
    static Double mvrvZ(double mvrv, double mcRaw, double mcSum, double mcSumSq, long count) {
        if (Double.isNaN(mvrv) || mvrv <= 0 || Double.isNaN(mcRaw) || count < MVRV_MIN_HISTORY) {
            return null;
        }
        double mc = mcRaw / MC_SCALE;
        double rc = mc / mvrv;
        double mean = mcSum / count;
        double std = Math.sqrt(Math.max(mcSumSq / count - mean * mean, 0));
        if (std <= 0) {
            return null;
        }
        return (mc - rc) / std;
    }

    /**
     * Фаза цикла (док. 01-v2 §2.4). Только дискретная метка, без арифметики.
     * supply_in_profit в БД нет — используется только MVRV-Z; порог ACCUMULATION
     * (в оригинале «MVRV-Z &lt; 0 и supply &lt; 50%») смягчён до «MVRV-Z &lt; 0».
     */
    static String cyclePhase(Double z) {
        if (z == null) {
            return null;
        }
        if (z < 0) {
            return "ACCUMULATION";
        }
        if (z < 1) {
            return "EARLY";
        }
        if (z < 2.5) {
            return "MID";
        }
        if (z < 3.5) {
            return "LATE";
        }
        return "EUPHORIA";
    }

    /** macro_flag ∈{−1,0,+1}: знак композита ФРС-баланс/ставка/DXY (як v1 C4). */
    private static Integer macroFlag(int i, double[] dff, double[] wal, double[] dxy) {
        if (i < MACRO_LOOKBACK) {
            return null;
        }
        return macroFlag(dff[i], dff[i - MACRO_LOOKBACK], wal[i], wal[i - MACRO_LOOKBACK],
                dxy[i], dxy[i - MACRO_LOOKBACK]);
    }

    static Integer macroFlag(double dff, double dffPrev, double wal, double walPrev,
                             double dxy, double dxyPrev) {
        if (Double.isNaN(dff) || Double.isNaN(dffPrev) || Double.isNaN(wal) || Double.isNaN(walPrev)
                || Double.isNaN(dxy) || Double.isNaN(dxyPrev)) {
            return null;
        }
        double qe = Math.signum(wal - walPrev);        // баланс растёт = QE = +
        double easing = Math.signum(dffPrev - dff);    // ставка падает = смягчение = +
        double dollar = Math.signum(dxyPrev - dxy);    // DXY падает = попутный ветер = +
        return (int) Math.signum(0.4 * qe + 0.35 * easing + 0.25 * dollar);
    }

    /** breadth — доля монет топ-100 выше своей SMA200 (survivorship, см. док. 01-v2 §1.4). */
    static Double breadth(long[] agg) {
        if (agg == null || agg[1] < 20) {
            return null;
        }
        return (double) agg[0] / agg[1];
    }

    /**
     * leverage_warning (док. 01-v2 §2.4): OI/MarketCap вырос ≥20% за 14д без роста
     * цены, ИЛИ funding BTC+ETH держится &gt;0.05%/8ч более 7 дней подряд.
     */
    private static boolean leverageWarning(int i, double[] oiRatio, double[] close, int highFundingStreak) {
        boolean oiWarn = i >= OI_LOOKBACK && !Double.isNaN(oiRatio[i]) && !Double.isNaN(oiRatio[i - OI_LOOKBACK])
                && oiRatio[i - OI_LOOKBACK] > 0
                && oiRatio[i] / oiRatio[i - OI_LOOKBACK] - 1 >= LEV_OI_GROWTH
                && close[i] <= close[i - OI_LOOKBACK];
        return oiWarn || highFundingStreak > LEV_FUNDING_DAYS;
    }

    // ================= confidence =================

    /** confidence из согласия, а не из силы (док. 01-v2 §3.1). */
    static double confidence(double d, double distTerm, double slopeTerm, int daysInState,
                             boolean lev, int failedSources) {
        double clarity = Math.abs(d);
        double agreement = 1 - Math.abs(distTerm - slopeTerm) / 2;
        double persistence = clip((double) daysInState / 30, 0, 1);
        double conf = 0.4 * clarity + 0.3 * agreement + 0.3 * persistence;
        if (lev) {
            conf *= 0.7;
        }
        conf *= (1 - 0.15 * failedSources);
        return clip(conf, 0, 1);
    }

    // ================= индикаторы =================

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
        double[] tr = trueRange(high, low, close);
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

    private static double[] trueRange(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            double pc = close[i - 1];
            tr[i] = Math.max(high[i] - low[i], Math.max(Math.abs(high[i] - pc), Math.abs(low[i] - pc)));
        }
        return tr;
    }

    /** ADX(14) по Уайлдеру. NaN, пока не набралось ~2·N баров для стабилизации. */
    static double[] adx(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        if (n < 2 * ADX_N + 1) {
            return out;
        }
        double[] tr = trueRange(high, low, close);
        double[] plusDm = new double[n], minusDm = new double[n];
        for (int i = 1; i < n; i++) {
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            plusDm[i] = (up > down && up > 0) ? up : 0;
            minusDm[i] = (down > up && down > 0) ? down : 0;
        }
        // Уайлдер-сглаживание TR/+DM/−DM: первое значение — сумма за N (с индекса 1).
        double atrW = 0, plusW = 0, minusW = 0;
        for (int i = 1; i <= ADX_N; i++) {
            atrW += tr[i];
            plusW += plusDm[i];
            minusW += minusDm[i];
        }
        double[] dx = new double[n];
        Arrays.fill(dx, Double.NaN);
        for (int i = ADX_N + 1; i < n; i++) {
            atrW = atrW - atrW / ADX_N + tr[i];
            plusW = plusW - plusW / ADX_N + plusDm[i];
            minusW = minusW - minusW / ADX_N + minusDm[i];
            if (atrW <= 0) {
                continue;
            }
            double diPlus = 100 * plusW / atrW;
            double diMinus = 100 * minusW / atrW;
            double sum = diPlus + diMinus;
            dx[i] = sum <= 0 ? 0 : 100 * Math.abs(diPlus - diMinus) / sum;
        }
        // ADX = Уайлдер-сглаживание DX по N. Первое — среднее первых N значений DX.
        int firstDx = ADX_N + 1;
        int adxStart = firstDx + ADX_N - 1;   // нужно N значений DX
        if (adxStart >= n) {
            return out;
        }
        double adxW = 0;
        for (int i = firstDx; i < firstDx + ADX_N; i++) {
            adxW += dx[i];
        }
        adxW /= ADX_N;
        out[adxStart] = adxW;
        for (int i = adxStart + 1; i < n; i++) {
            adxW = (adxW * (ADX_N - 1) + dx[i]) / ADX_N;
            out[i] = adxW;
        }
        return out;
    }

    /** Реализованная волатильность: стандартное отклонение дневных лог-доходностей за 7д. */
    private static double[] realizedVol(double[] close) {
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

    /**
     * Процентильный ранг каждого T_raw по трейлинг-окну ~3 года (док. 01-v2 §2.2).
     * Каузально: ранг в точке i считается только по [i−RANK_N+1 .. i]. Пока истории
     * меньше 3 лет — окно расширяющееся. Ранг = доля значений окна ≤ текущего.
     */
    static void percentileRank(double[] raw, double[] rank) {
        int n = raw.length;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(raw[i])) {
                continue;
            }
            int lo = Math.max(0, i - RANK_N + 1);
            int le = 0, total = 0;
            for (int j = lo; j <= i; j++) {
                if (!Double.isNaN(raw[j])) {
                    total++;
                    if (raw[j] <= raw[i]) {
                        le++;
                    }
                }
            }
            rank[i] = total > 0 ? (double) le / total : Double.NaN;
        }
    }

    static double median3(double a, double b, double cc) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), cc));
    }

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ================= загрузка (те же источники, что v1) =================

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
