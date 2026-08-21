package org.home.data.theory.alloc;

import org.home.data.core.Db;
import org.home.data.theory.Jlog;
import org.home.data.theory.TheoryDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Слой данных стенда аллокации (ТЗ 65 §3): приводит стратегии пула и бенчмарки к
 * единому дневному формату {@link Curve}.
 *
 * <p><b>Пул фиксирован до прогона</b> (§8) и задаётся конфигом
 * {@code theory.alloc.pool}. Стратегии, для которых кривой построить нельзя,
 * перечисляются в {@link CurveSet#missing()} с причиной и попадают в отчёт: это
 * не то же самое, что исключение убыточной стратегии по результату.
 *
 * <p>Все кривые, кроме {@code CASH}, помечены {@link CurveKind#IN_SAMPLE}:
 * параметры стратегий выбраны со знанием истории проекта, и результат
 * аллокатора наследует это смещение целиком (§0 п.1). Отчёт помечается как
 * верхняя граница.
 */
@Component
@Lazy
public class CurveBuilder {

    private static final Logger log = LoggerFactory.getLogger(CurveBuilder.class);

    private final Db db;
    private final TheoryDb theoryDb;
    private final AllocConfig cfg;

    public CurveBuilder(Db db, TheoryDb theoryDb, AllocConfig cfg) {
        this.db = db;
        this.theoryDb = theoryDb;
        this.cfg = cfg;
    }

    /** Дневные ряды одного символа, выровненные на календарь (NaN — нет данных). */
    private record Series(double[] close, double[] high, double[] low) {
    }

    public CurveSet build() {
        List<String> days = db.queryStrings(
                "SELECT date(open_time/1000,'unixepoch') FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' AND date(open_time/1000,'unixepoch') >= ? "
                        + "ORDER BY open_time", cfg.from());
        if (days.size() < 400) {
            throw new IllegalStateException("мало дней календаря: " + days.size() + " (нужны свечи BTCUSDT 1d)");
        }
        String[] cal = days.toArray(new String[0]);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < cal.length; i++) {
            idx.put(cal[i], i);
        }
        long[] dayMs = new long[cal.length];
        for (int i = 0; i < cal.length; i++) {
            dayMs[i] = LocalDate.parse(cal[i]).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }

        Series btc = series("BTCUSDT", idx);
        Series eth = series("ETHUSDT", idx);
        double[] cash = cashRate(cal);
        String[] regime = new String[cal.length];
        String[] phase = new String[cal.length];
        db.query("SELECT day, state, cycle_phase FROM regime_daily_v5", rs -> {
            Integer i = idx.get(rs.getString(1));
            if (i != null) {
                regime[i] = rs.getString(2);
                phase[i] = rs.getString(3);
            }
            return null;
        });

        Map<String, String> missing = new LinkedHashMap<>();
        List<Curve> pool = new ArrayList<>();
        for (String id : cfg.pool()) {
            Curve c = switch (id) {
                case "S1" -> s1(cal, idx);
                case "S2S9" -> s2s9(cal, idx);
                case "S3" -> s3(cal, btc, eth);
                case "S4" -> s4(cal, cash);
                case "S5" -> s5(cal, idx);
                case "S6" -> s6(cal, btc, regime, phase);
                case "S7" -> s7(cal, btc);
                default -> null;
            };
            if (c == null) {
                missing.put(id, "кривая не реализована");
            } else {
                pool.add(c);
            }
        }
        for (Map.Entry<String, String> e : cfg.excluded().entrySet()) {
            missing.put(e.getKey(), e.getValue());
        }

        Map<String, Curve> benchmarks = new LinkedHashMap<>();
        benchmarks.put("BH_BTC", buyHold("BH_BTC", btc, "buy & hold BTC, дневные свечи Binance"));
        benchmarks.put("BH_ETH", buyHold("BH_ETH", eth, "buy & hold ETH, дневные свечи Binance"));
        benchmarks.put("SMA200", sma200(btc, cash));
        benchmarks.put("CASH", cashCurve(cash));

        Jlog.info(log, "alloc.curves", Map.of("days", cal.length, "from", cal[0], "to", cal[cal.length - 1],
                "pool", String.join(",", pool.stream().map(Curve::id).toList()),
                "missing", String.join(",", missing.keySet())));
        return new CurveSet(cal, dayMs, pool, benchmarks, cash, regime, phase, missing);
    }

    // ------------------------------------------------------------------ данные

    private Series series(String symbol, Map<String, Integer> idx) {
        double[] close = new double[idx.size()];
        double[] high = new double[idx.size()];
        double[] low = new double[idx.size()];
        Arrays.fill(close, Double.NaN);
        Arrays.fill(high, Double.NaN);
        Arrays.fill(low, Double.NaN);
        db.query("SELECT date(open_time/1000,'unixepoch') d, high, low, close FROM candles "
                + "WHERE symbol=? AND interval='1d' ORDER BY open_time", rs -> {
            Integer i = idx.get(rs.getString(1));
            if (i != null) {
                high[i] = rs.getDouble(2);
                low[i] = rs.getDouble(3);
                close[i] = rs.getDouble(4);
            }
            return null;
        }, symbol);
        return new Series(close, high, low);
    }

    /**
     * Ставка кэша по датам (§3.2). Источник — FRED DFF (эффективная ставка ФРС)
     * из macro_series, доступность по правилу день+1 сутки: на день t берётся
     * значение дня t−1 или последнее известное до него.
     *
     * <p>Константа 8%, использованная в старых прокси-прогонах проекта, — прямое
     * завышение (док. 01 v5 §6.4), поэтому здесь её нет.
     */
    private double[] cashRate(String[] cal) {
        TreeMap<String, Double> dff = new TreeMap<>();
        db.query("SELECT day, value FROM macro_series WHERE series_id='DFF' AND value IS NOT NULL", rs -> {
            dff.put(rs.getString(1), rs.getDouble(2));
            return null;
        });
        if (dff.isEmpty()) {
            throw new IllegalStateException("нет DFF в macro_series: ставка кэша по датам недоступна");
        }
        double[] out = new double[cal.length];
        for (int i = 0; i < cal.length; i++) {
            String previous = LocalDate.parse(cal[i]).minusDays(1).toString();
            Map.Entry<String, Double> e = dff.floorEntry(previous);
            double annual = (e == null ? dff.firstEntry().getValue() : e.getValue()) / 100.0;
            out[i] = Math.pow(1 + annual, 1.0 / 365) - 1;
        }
        return out;
    }

    // ---------------------------------------------------------------- стратегии

    /**
     * S1 — funding-арбитраж (дельта-нейтраль: спот лонг + перп шорт). Доход —
     * funding-платежи шортам. Гейт доступности: {@code EMA7(funding)} лучшего
     * символа ≥ порога (док. 00 v3 §3.1 — та самая доступность 17% времени).
     * Издержки: переключение символа (4 ноги) и трение ребаланса дельты.
     */
    private Curve s1(String[] cal, Map<String, Integer> idx) {
        List<String> symbols = db.queryStrings(
                "SELECT DISTINCT symbol FROM funding WHERE exchange='binance' ORDER BY symbol");
        if (symbols.isEmpty()) {
            return null;
        }
        Map<String, double[]> daily = new LinkedHashMap<>();
        for (String s : symbols) {
            double[] f = new double[cal.length];
            db.query("SELECT date(funding_time/1000,'unixepoch') d, sum(rate) FROM funding "
                    + "WHERE exchange='binance' AND symbol=? GROUP BY d", rs -> {
                Integer i = idx.get(rs.getString(1));
                if (i != null) {
                    f[i] = rs.getDouble(2);
                }
                return null;
            }, s);
            daily.put(s, f);
        }
        Map<String, Series> px = new LinkedHashMap<>();
        for (String s : symbols) {
            px.put(s, series(s, idx));
        }

        int n = cal.length;
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        Map<String, double[]> ema = new LinkedHashMap<>();
        double alpha = 2.0 / (7 + 1);
        for (String s : symbols) {
            double[] e = new double[n];
            double[] f = daily.get(s);
            e[0] = f[0];
            for (int i = 1; i < n; i++) {
                e[i] = alpha * f[i] + (1 - alpha) * e[i - 1];
            }
            ema.put(s, e);
        }
        String held = null;
        for (int t = 1; t < n; t++) {
            String best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (String s : symbols) {
                double score = ema.get(s)[t - 1];
                double[] close = px.get(s).close();
                if (Double.isNaN(close[t]) || Double.isNaN(close[t - 1])) {
                    continue;                       // символа ещё нет: перп не торгуется
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best == null || bestScore < cfg.s1GatePer8h() * 3) {
                held = null;                        // гейт закрыт → капитал в кэше
                continue;
            }
            avail[t] = true;
            double gross = daily.get(best)[t];
            if (!best.equals(held)) {
                gross -= cfg.s1SwitchCost();
                turnover[t] = 1;
                held = best;
            }
            double[] close = px.get(best).close();
            double move = Math.abs(close[t] / close[t - 1] - 1);
            gross -= cfg.s1RebalanceK() * Math.max(0, move - 0.01);
            ret[t] = gross;
        }
        return new Curve("S1", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "дельта-нейтральный funding-carry на перпах Binance (" + symbols.size()
                        + " символа с историей funding); выбор символа по EMA7(funding) вчерашнего дня, "
                        + "гейт доступности EMA7 ≥ " + cfg.s1GatePer8h() + " за 8ч (док. 00 v3 §3.1), "
                        + "издержки переключения " + cfg.s1SwitchCost() + " и трение ребаланса дельты");
    }

    /**
     * S2/S9 — кросс-секционная моментум-ротация: лонг топ-K по доходности за
     * {@code lookback} дней, удержание {@code hold} дней, равные веса.
     *
     * <p><b>Ограничение, обязательное к упоминанию:</b> вселенная — символы, по
     * которым в базе есть дневные свечи, то есть <b>сегодняшний</b> список
     * (PIT-снимков капитализаций в базе нет, док. 09 §4.3). Это survivorship —
     * кривая S2S9 завышена. Механизм уже закрыт измерением IC ≈ 0 (док. 45),
     * поэтому смещение работает против вывода «аллокатор бесполезен», а не за
     * него: если даже с завышенной S2S9 аллокатор не выигрывает, тем более.
     */
    private Curve s2s9(String[] cal, Map<String, Integer> idx) {
        List<String> symbols = db.queryStrings(
                "SELECT symbol FROM candles WHERE interval='1d' GROUP BY symbol HAVING count(*) >= ? ORDER BY symbol",
                cfg.s2s9MinHistory());
        Map<String, double[]> closes = new LinkedHashMap<>();
        for (String s : symbols) {
            closes.put(s, series(s, idx).close());
        }
        int n = cal.length;
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        List<String> holdings = new ArrayList<>();
        int lookback = cfg.s2s9Lookback();

        for (int t = 1; t < n; t++) {
            if (!holdings.isEmpty()) {
                double sum = 0;
                int counted = 0;
                for (String s : holdings) {
                    double[] c = closes.get(s);
                    if (!Double.isNaN(c[t]) && !Double.isNaN(c[t - 1]) && c[t - 1] > 0) {
                        sum += c[t] / c[t - 1] - 1;
                        counted++;
                    }
                }
                if (counted > 0) {
                    ret[t] = sum / counted;
                    avail[t] = true;
                }
            }
            if ((t - 1) % cfg.s2s9Hold() != 0 || t <= lookback) {
                continue;
            }
            List<String> ranked = new ArrayList<>();
            Map<String, Double> score = new HashMap<>();
            for (String s : symbols) {
                double[] c = closes.get(s);
                if (Double.isNaN(c[t - 1]) || Double.isNaN(c[t - 1 - lookback]) || c[t - 1 - lookback] <= 0) {
                    continue;
                }
                score.put(s, c[t - 1] / c[t - 1 - lookback] - 1);
                ranked.add(s);
            }
            if (ranked.size() < cfg.s2s9MinUniverse()) {
                holdings = List.of();
                continue;
            }
            ranked.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
            List<String> next = new ArrayList<>(ranked.subList(0, Math.min(cfg.s2s9Top(), ranked.size())));
            double changed = 0;
            for (String s : next) {
                if (!holdings.contains(s)) {
                    changed++;
                }
            }
            double turn = next.isEmpty() ? 0 : changed / next.size();
            turnover[t] = turn;
            ret[t] -= cfg.cost() * turn;
            holdings = next;
        }
        return new Curve("S2S9", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "кросс-секционный моментум: лонг топ-" + cfg.s2s9Top() + " по доходности за "
                        + lookback + " дней, ротация раз в " + cfg.s2s9Hold() + " дней, равные веса, издержки "
                        + cfg.cost() + " с оборота. ВСЕЛЕННАЯ СЕГОДНЯШНЯЯ (PIT-капитализаций нет) → survivorship, "
                        + "кривая завышена");
    }

    /**
     * S3 — канонический mean reversion по z-score (окно 20, вход ±1σ, выход к
     * средней), BTC и ETH равными долями. Закрыта измерением (док. 22): минус
     * при нулевых издержках. В пуле остаётся, потому что пул фиксируется до
     * прогона, а исключение убыточной стратегии по результату — look-ahead (§8).
     */
    private Curve s3(String[] cal, Series btc, Series eth) {
        int n = cal.length;
        double[] retBtc = meanReversionLeg(btc.close());
        double[] retEth = meanReversionLeg(eth.close());
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        for (int t = 0; t < n; t++) {
            int legs = 0;
            double sum = 0;
            if (!Double.isNaN(retBtc[t])) {
                sum += retBtc[t];
                legs++;
            }
            if (!Double.isNaN(retEth[t])) {
                sum += retEth[t];
                legs++;
            }
            if (legs > 0) {
                ret[t] = sum / legs;
                avail[t] = true;
                turnover[t] = 1.0 / cfg.s3Window();
            }
        }
        return new Curve("S3", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "канонический mean reversion (z=(close−SMA" + cfg.s3Window() + ")/std, вход ±"
                        + cfg.s3Entry() + "σ, выход к средней), BTC и ETH равными долями, издержки "
                        + cfg.cost() + " с оборота; закрыта измерением (док. 22), в пуле по правилу §8");
    }

    /** Одна нога S3: NaN, когда позиции нет (капитал не задействован). */
    private double[] meanReversionLeg(double[] close) {
        int n = close.length;
        int w = cfg.s3Window();
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        int position = 0;
        for (int t = w + 1; t < n; t++) {
            if (Double.isNaN(close[t]) || Double.isNaN(close[t - 1])) {
                continue;
            }
            double mean = 0;
            int counted = 0;
            for (int k = t - w; k < t; k++) {
                if (!Double.isNaN(close[k])) {
                    mean += close[k];
                    counted++;
                }
            }
            if (counted < w) {
                continue;
            }
            mean /= counted;
            double var = 0;
            for (int k = t - w; k < t; k++) {
                var += (close[k] - mean) * (close[k] - mean);
            }
            double sd = Math.sqrt(var / counted);
            double z = sd > 0 ? (close[t - 1] - mean) / sd : 0;
            int previous = position;
            if (position == 0) {
                if (z < -cfg.s3Entry()) {
                    position = 1;
                } else if (z > cfg.s3Entry()) {
                    position = -1;
                }
            } else if ((position == 1 && z >= 0) || (position == -1 && z <= 0)) {
                position = 0;
            }
            if (position != 0) {
                double r = position * (close[t] / close[t - 1] - 1);
                out[t] = r - (previous != position ? cfg.cost() : 0);
            }
        }
        return out;
    }

    /**
     * S4 — стейблкоин-доходность. Не бэктест, а ряд достижимых ставок по
     * подпериодам (док. 24 §6.4): 8% в 2019–20, 6% в 2021–22, 5% с 2023.
     * Помечена как {@link CurveKind#IN_SAMPLE} именно поэтому: это допущение,
     * а не измерение.
     */
    private Curve s4(String[] cal, double[] cash) {
        int n = cal.length;
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        for (int t = 0; t < n; t++) {
            int year = Integer.parseInt(cal[t].substring(0, 4));
            double annual = cfg.s4Yield(year);
            ret[t] = Math.pow(1 + annual, 1.0 / 365) - 1;
            avail[t] = true;
        }
        return new Curve("S4", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "стейблкоин-доходность как ряд достижимых ставок по подпериодам (док. 24 §6.4): "
                        + cfg.s4YieldsRaw() + " — допущение, не измерение; контрагентский риск в кривой не отражён");
    }

    /**
     * S5 — event-шорты под токен-разлоки (единственное подтверждённое
     * преимущество проекта). Кривая на задействованный капитал: в день с k
     * открытыми позициями доходность стратегии — среднее по позициям.
     *
     * <p>Фильтр funding-а причинный: ожидание берётся по 5 дням <b>до входа</b>,
     * а не по периоду удержания (иначе отмена сделки знала бы будущее).
     */
    private Curve s5(String[] cal, Map<String, Integer> idx) {
        Long events = theoryDb.queryLong("SELECT count(*) FROM s5_event");
        if (events == null || events == 0) {
            return null;
        }
        Map<String, double[]> close = new HashMap<>();
        Map<String, double[]> high = new HashMap<>();
        Map<String, double[]> funding = new HashMap<>();
        theoryDb.query("SELECT base, day, close, high FROM s5_price", rs -> {
            Integer i = idx.get(rs.getString(2));
            if (i != null) {
                String base = rs.getString(1);
                close.computeIfAbsent(base, k -> nanArray(cal.length))[i] = rs.getDouble(3);
                high.computeIfAbsent(base, k -> nanArray(cal.length))[i] = rs.getDouble(4);
            }
            return null;
        });
        theoryDb.query("SELECT base, day, rate_sum FROM s5_funding_daily", rs -> {
            Integer i = idx.get(rs.getString(2));
            if (i != null) {
                funding.computeIfAbsent(rs.getString(1), k -> new double[cal.length])[i] = rs.getDouble(3);
            }
            return null;
        });

        record Event(String base, int entry, int exit) {
        }
        List<Event> planned = new ArrayList<>();
        int skippedFunding = 0;
        int skippedNoPrice = 0;
        int skippedOutOfWindow = 0;
        List<Object[]> raw = theoryDb.query(
                "SELECT base, unlock_day, pct_supply FROM s5_event WHERE pct_supply >= ? ORDER BY unlock_day",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)}, cfg.s5MinPct());
        for (Object[] row : raw) {
            String base = (String) row[0];
            Integer unlock = idx.get((String) row[1]);
            if (unlock == null) {
                skippedOutOfWindow++;        // разлок вне календаря стенда (до 2020 или после конца данных)
                continue;
            }
            double[] c = close.get(base);
            if (c == null) {
                skippedNoPrice++;
                continue;
            }
            int entry = unlock - cfg.s5Lead();
            if (entry < 1 || unlock >= cal.length || Double.isNaN(c[entry]) || Double.isNaN(c[unlock])) {
                skippedNoPrice++;
                continue;
            }
            double[] f = funding.getOrDefault(base, new double[cal.length]);
            double expected = 0;
            for (int d = Math.max(0, entry - cfg.s5Lead()); d < entry; d++) {
                expected += f[d];
            }
            // шорт получает при rate>0 и платит при rate<0: дорогой шорт — это ожидание < −порога
            if (-expected > cfg.s5MaxFundingCost()) {
                skippedFunding++;
                continue;
            }
            planned.add(new Event(base, entry, unlock));
        }

        int n = cal.length;
        double[] sum = new double[n];
        int[] count = new int[n];
        int stopped = 0;
        double perTradeSum = 0;
        int trades = 0;
        for (Event e : planned) {
            double[] c = close.get(e.base());
            double[] h = high.get(e.base());
            double[] f = funding.getOrDefault(e.base(), new double[n]);
            double entryPrice = c[e.entry()];
            boolean open = true;
            double cumulative = 0;                         // накопленная доходность позиции
            for (int t = e.entry() + 1; t <= e.exit() && open; t++) {
                if (Double.isNaN(c[t]) || Double.isNaN(c[t - 1]) || c[t - 1] <= 0) {
                    continue;
                }
                double r = (c[t - 1] - c[t]) / c[t - 1] + f[t];
                if (t == e.entry() + 1) {
                    // издержки сделки — round-trip, списываются один раз (как в модели док. 52),
                    // а не по разу на ногу: двойное списание съедало бы всю премию события
                    r -= cfg.s5TradeCost();
                }
                double adverse = Double.isNaN(h[t]) ? 0 : (h[t] - entryPrice) / entryPrice;
                if (adverse >= cfg.s5Stop()) {
                    // стоп: итог позиции фиксируется на −(stop + проскальзывание) − издержки,
                    // дневная доходность — остаток до этого уровня, иначе убыток считался бы дважды
                    double target = -(cfg.s5Stop() + cfg.s5Slippage()) - cfg.s5TradeCost();
                    r = (1 + target) / (1 + cumulative) - 1;
                    open = false;
                    stopped++;
                }
                cumulative = (1 + cumulative) * (1 + r) - 1;
                sum[t] += r;
                count[t]++;
            }
            perTradeSum += cumulative;
            trades++;
        }
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        for (int t = 0; t < n; t++) {
            if (count[t] > 0) {
                ret[t] = sum[t] / count[t];
                avail[t] = true;
                turnover[t] = 1.0 / cfg.s5Lead();
            }
        }
        double perTrade = trades == 0 ? 0 : perTradeSum / trades;
        Jlog.info(log, "alloc.s5", Map.of("events", planned.size(), "stopped", stopped,
                "skipped_funding", skippedFunding, "skipped_no_price", skippedNoPrice,
                "skipped_out_of_window", skippedOutOfWindow, "raw", raw.size(),
                "mean_per_trade", perTrade));
        return new Curve("S5", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "event-шорты под cliff-разлоки ≥ " + (cfg.s5MinPct() * 100) + "% circ: вход за "
                        + cfg.s5Lead() + " дней, выход в день разлока, стоп " + (cfg.s5Stop() * 100)
                        + "%, отмена при ожидаемом funding-расходе > " + (cfg.s5MaxFundingCost() * 100)
                        + "% (ожидание — по 5 дням ДО входа, причинно — в модели док. 52 фильтр брал "
                        + "funding самого периода удержания, то есть подглядывал); из " + raw.size()
                        + " разлоков ≥ порога сыграли " + planned.size() + " (вне окна календаря "
                        + skippedOutOfWindow + ", без цены перпа " + skippedNoPrice + ", отменено по funding "
                        + skippedFunding + "), стоп сработал " + stopped + " раз; издержки "
                        + cfg.s5TradeCost() + " round-trip (один раз на сделку) + проскальзывание "
                        + cfg.s5Slippage() + " только при стопе. Сверка с моделью док. 52: средняя "
                        + "доходность позиции здесь " + String.format(java.util.Locale.ROOT, "%+.2f%%", perTrade * 100)
                        + " против +2.49% там (там 550 сделок, фильтр funding подглядывал в период удержания, "
                        + "проскальзывание вычиталось только при стопе, окно календаря шире)");
    }

    /**
     * S6 — лестница накопления: активируется при {@code BEAR + cycle_phase =
     * ACCUMULATION}, {@code steps} ступеней вниз с шагом {@code stepDrop} от
     * цены активации, выход при переходе в BULL. Доходность на задействованный
     * капитал — дневная доходность BTC на заполненных ступенях (это бета, и
     * кривая честно её показывает).
     */
    private Curve s6(String[] cal, Series btc, String[] regime, String[] phase) {
        int n = cal.length;
        double[] close = btc.close();
        double[] low = btc.low();
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        double anchor = Double.NaN;
        int filled = 0;
        for (int t = 1; t < n; t++) {
            String state = regime[t - 1];
            boolean gate = "BEAR".equals(state) && "ACCUMULATION".equals(phase[t - 1]);
            if (!gate) {
                if (filled > 0) {
                    turnover[t] = 1;                       // выход из лестницы
                }
                filled = 0;
                anchor = Double.NaN;
                continue;
            }
            if (Double.isNaN(anchor)) {
                anchor = close[t - 1];
            }
            if (filled > 0) {
                ret[t] = close[t] / close[t - 1] - 1;
                avail[t] = true;
            }
            int target = filled;
            for (int step = filled; step < cfg.s6Steps(); step++) {
                double trigger = anchor * (1 - cfg.s6StepDrop() * step);
                if (!Double.isNaN(low[t]) && low[t] <= trigger) {
                    target = step + 1;
                }
            }
            if (target > filled) {
                double added = (target - filled) / (double) cfg.s6Steps();
                ret[t] -= cfg.cost() * added;
                turnover[t] = added;
                filled = target;
                avail[t] = true;
            }
        }
        return new Curve("S6", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "лестница накопления BTC: гейт BEAR + cycle_phase=ACCUMULATION (regime_daily_v5), "
                        + cfg.s6Steps() + " ступеней с шагом " + (cfg.s6StepDrop() * 100)
                        + "% вниз от цены активации, выход при BULL; доходность на задействованный капитал = "
                        + "дневная доходность BTC (это бета, а не альфа)");
    }

    /**
     * S7 — fade panic в дневном приближении: после дня с падением ≤ порога —
     * лонг на следующий день. Внутридневного контура у проекта нет (док. 29),
     * поэтому это <b>прокси</b>, и он помечен как таковой. Механизм закрыт
     * измерением (док. 49: каскады не отскакивают).
     */
    private Curve s7(String[] cal, Series btc) {
        int n = cal.length;
        double[] close = btc.close();
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        for (int t = 2; t < n; t++) {
            double prior = close[t - 1] / close[t - 2] - 1;
            if (prior <= cfg.s7CrashThreshold()) {
                ret[t] = (close[t] / close[t - 1] - 1) - 2 * cfg.cost();
                avail[t] = true;
                turnover[t] = 1;
            }
        }
        return new Curve("S7", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "fade panic в ДНЕВНОМ приближении: после дня с падением ≤ "
                        + (cfg.s7CrashThreshold() * 100) + "% — лонг BTC на следующий день, издержки "
                        + (2 * cfg.cost()) + " на сделку. Внутридневного контура нет (док. 29) → это прокси; "
                        + "механизм закрыт измерением (док. 49)");
    }

    // --------------------------------------------------------------- бенчмарки

    private Curve buyHold(String id, Series s, String note) {
        int n = s.close().length;
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        for (int t = 1; t < n; t++) {
            double[] c = s.close();
            if (!Double.isNaN(c[t]) && !Double.isNaN(c[t - 1]) && c[t - 1] > 0) {
                ret[t] = c[t] / c[t - 1] - 1;
                avail[t] = true;
            }
        }
        return new Curve(id, CurveKind.IN_SAMPLE, ret, avail, new double[n], note);
    }

    /** SMA200 — главный бенчмарк: именно его не превзошёл детектор (док. 20). */
    private Curve sma200(Series btc, double[] cash) {
        double[] close = btc.close();
        int n = close.length;
        double[] ret = new double[n];
        boolean[] avail = new boolean[n];
        double[] turnover = new double[n];
        // SMA считается по истории до дня t−1 включительно (причинность)
        boolean prevIn = false;
        for (int t = 1; t < n; t++) {
            avail[t] = true;
            int window = 200;
            double sum = 0;
            int counted = 0;
            for (int k = Math.max(0, t - window); k < t; k++) {
                if (!Double.isNaN(close[k])) {
                    sum += close[k];
                    counted++;
                }
            }
            boolean in = counted >= window && close[t - 1] > sum / counted;
            double r = in ? close[t] / close[t - 1] - 1 : cash[t];
            if (in != prevIn) {
                r -= cfg.cost();
                turnover[t] = 1;
            }
            ret[t] = r;
            prevIn = in;
        }
        return new Curve("SMA200", CurveKind.IN_SAMPLE, ret, avail, turnover,
                "BTC при close > SMA200, иначе кэш по ставке DFF; издержки " + cfg.cost() + " на переключение");
    }

    private Curve cashCurve(double[] cash) {
        int n = cash.length;
        boolean[] avail = new boolean[n];
        Arrays.fill(avail, true);
        return new Curve("CASH", CurveKind.LIVE, cash.clone(), avail, new double[n],
                "ставка кэша по датам: FRED DFF, доступность день+1 (не константа 8% — док. 01 v5 §6.4)");
    }

    private static double[] nanArray(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }
}
