package org.home.data.theory.ou;

import org.home.data.core.Db;
import org.home.data.revx.RevxDb;
import org.home.data.theory.Jlog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Ряды-кандидаты и контроли для предтеста OU (ТЗ 67 §2).
 *
 * <p>Контроли идут первыми и решают, действительны ли остальные результаты:
 * если цена BTC или случайное блуждание проходят допуск, тесты настроены
 * неверно (§0).
 */
@Component
@Lazy
public class OuSeriesLoader {

    private static final Logger log = LoggerFactory.getLogger(OuSeriesLoader.class);

    private final Db db;
    private final org.home.data.theory.TheoryDb theoryDb;
    private final ObjectProvider<RevxDb> revxDb;
    private final OuConfig cfg;

    public OuSeriesLoader(Db db, org.home.data.theory.TheoryDb theoryDb,
                          ObjectProvider<RevxDb> revxDb, OuConfig cfg) {
        this.db = db;
        this.theoryDb = theoryDb;
        this.revxDb = revxDb;
        this.cfg = cfg;
    }

    // ------------------------------------------------------------- контроли

    /** {@code POS_SYN} — синтетический OU с известными параметрами: обязан пройти. */
    public OuSeries positiveSynthetic(double kappa, double theta, double sigma, int n, double thresholdSd) {
        Random rnd = new Random(cfg.seed());
        double[] dt = new double[n - 1];
        java.util.Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(kappa, theta, sigma, dt, rnd);
        double sd = sigma / Math.sqrt(2 * kappa);
        return synthetic("POS_SYN", "синтетический OU: κ=" + kappa + ", θ=" + theta + ", σ=" + sigma
                + ", шаг 1 день", x, sd * thresholdSd, thresholdSd);
    }

    /** {@code POS_SYN_SLOW} — OU с полупериодом длиннее окна применимости: обязан провалить T2. */
    public OuSeries slowSynthetic(double halfLifeDays, double sigma, int n, double thresholdSd) {
        double kappa = Math.log(2) / halfLifeDays;
        Random rnd = new Random(cfg.seed() + 1);
        double[] dt = new double[n - 1];
        java.util.Arrays.fill(dt, 1.0);
        double[] x = OuCalibration.simulate(kappa, 0, sigma, dt, rnd);
        double sd = sigma / Math.sqrt(2 * kappa);
        return synthetic("POS_SYN_SLOW", "синтетический OU с полупериодом " + halfLifeDays
                + " дней — длиннее окна применимости " + cfg.holdingHorizonDays() + " дней",
                x, sd * thresholdSd, thresholdSd);
    }

    /** {@code NEG_RW} — случайное блуждание той же длины: обязан не пройти. */
    public OuSeries randomWalk(int n, double sigma) {
        Random rnd = new Random(cfg.seed() + 2);
        double[] x = OuCalibration.randomWalk(n, sigma, rnd);
        double sd = Math.sqrt(variance(x));
        return synthetic("NEG_RW", "случайное блуждание той же длины, тот же seed", x, sd * 0.5, 0.5);
    }

    /** {@code NEG_BTC} — лог-цена BTC: обязан не пройти (S3 закрыта измерением). */
    public OuSeries btcLogPrice() {
        List<double[]> rows = new ArrayList<>();
        db.query("SELECT open_time, close FROM candles WHERE symbol='BTCUSDT' AND interval='1d' "
                + "AND date(open_time/1000,'unixepoch') >= ? ORDER BY open_time", rs -> {
            rows.add(new double[]{rs.getLong(1) / 86_400_000.0, Math.log(rs.getDouble(2))});
            return null;
        }, cfg.from());
        double[] times = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] values = rows.stream().mapToDouble(r -> r[1]).toArray();
        double mean = OuCalibration.mean(values);
        double sd = Math.sqrt(variance(values));
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i] - mean) > 0.5 * sd;
        }
        return new OuSeries("NEG_BTC", "лог-цена BTC, дневные свечи Binance", times, values, inEpisode,
                "|log P − среднее| > 0.5σ", "дни", 0,
                "контроль: цена не обязана возвращаться ни к какому уровню (док. 22, S3 закрыта)", false);
    }

    // ------------------------------------------------------------- кандидаты

    /**
     * {@code FUNDDIFF} — дифференциал funding между площадками по одному активу.
     * Kraken отдаёт <b>часовые</b> ставки, Binance — восьмичасовые (CLAUDE.md),
     * поэтому обе приводятся к суточной сумме — иначе сравниваются разные
     * единицы.
     */
    public OuSeries fundingDifferential(String krakenSymbol, String binanceSymbol) {
        TreeMap<String, Double> kraken = new TreeMap<>();
        db.query("SELECT date(funding_time/1000,'unixepoch') d, sum(rel_rate) FROM kraken_funding "
                + "WHERE symbol=? GROUP BY d", rs -> {
            kraken.put(rs.getString(1), rs.getDouble(2));
            return null;
        }, krakenSymbol);
        TreeMap<String, Double> binance = new TreeMap<>();
        db.query("SELECT date(funding_time/1000,'unixepoch') d, sum(rate) FROM funding "
                + "WHERE exchange='binance' AND symbol=? GROUP BY d", rs -> {
            binance.put(rs.getString(1), rs.getDouble(2));
            return null;
        }, binanceSymbol);

        List<double[]> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : kraken.entrySet()) {
            Double b = binance.get(e.getKey());
            if (b != null) {
                rows.add(new double[]{java.time.LocalDate.parse(e.getKey()).toEpochDay(), e.getValue() - b});
            }
        }
        double[] times = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] values = rows.stream().mapToDouble(r -> r[1]).toArray();
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i]) > cfg.fundDiffThreshold();
        }
        Jlog.info(log, "ou.funddiff", Map.of("points", values.length,
                "kraken_days", kraken.size(), "binance_days", binance.size()));
        return new OuSeries("FUNDDIFF",
                "дифференциал суточного funding: Kraken " + krakenSymbol + " (часовые ставки, сумма за сутки) "
                        + "минус Binance " + binanceSymbol + " (8-часовые, сумма за сутки)",
                times, values, inEpisode,
                "|дифференциал| > " + cfg.fundDiffThreshold() + " (round-trip издержки)", "дни", 0,
                "механизм привязки — межплощадочный арбитраж; ставки площадок невзаимозаменяемы "
                        + "(док. 09 §4.7), поэтому сравнивается именно дифференциал, а не уровни", false);
    }

    /**
     * {@code PAIRSPREAD} — коинтеграционный спред BTC/ETH. Включён намеренно как
     * <b>граничный случай</b>: механической привязки у него нет, только
     * статистическая гипотеза, и проходить он должен те же тесты строже.
     *
     * <p>β оценивается на всей выборке — это in-sample, и для граничного случая
     * это работает в его пользу: если он не проходит даже так, вопрос закрыт.
     */
    public OuSeries pairSpread() {
        TreeMap<String, Double> btc = new TreeMap<>();
        TreeMap<String, Double> eth = new TreeMap<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, symbol, close FROM candles "
                + "WHERE interval='1d' AND symbol IN ('BTCUSDT','ETHUSDT') AND date(open_time/1000,'unixepoch') >= ?",
                rs -> {
                    if ("BTCUSDT".equals(rs.getString(2))) {
                        btc.put(rs.getString(1), Math.log(rs.getDouble(3)));
                    } else {
                        eth.put(rs.getString(1), Math.log(rs.getDouble(3)));
                    }
                    return null;
                }, cfg.from());
        List<String> days = new ArrayList<>(btc.keySet());
        days.retainAll(eth.keySet());
        int n = days.size();
        double[] xb = new double[n];
        double[] xe = new double[n];
        double[] times = new double[n];
        for (int i = 0; i < n; i++) {
            xb[i] = btc.get(days.get(i));
            xe[i] = eth.get(days.get(i));
            times[i] = java.time.LocalDate.parse(days.get(i)).toEpochDay();
        }
        double mb = OuCalibration.mean(xb);
        double me = OuCalibration.mean(xe);
        double cov = 0;
        double var = 0;
        for (int i = 0; i < n; i++) {
            cov += (xb[i] - mb) * (xe[i] - me);
            var += (xe[i] - me) * (xe[i] - me);
        }
        double beta = var > 0 ? cov / var : 1;
        double[] spread = new double[n];
        for (int i = 0; i < n; i++) {
            spread[i] = xb[i] - beta * xe[i];
        }
        // z-скор на скользящем окне: эпизод — |z| > порога
        boolean[] inEpisode = new boolean[n];
        int w = cfg.pairWindow();
        for (int i = w; i < n; i++) {
            double m = 0;
            for (int k = i - w; k < i; k++) {
                m += spread[k];
            }
            m /= w;
            double v = 0;
            for (int k = i - w; k < i; k++) {
                v += (spread[k] - m) * (spread[k] - m);
            }
            double sd = Math.sqrt(v / w);
            inEpisode[i] = sd > 0 && Math.abs((spread[i] - m) / sd) > cfg.pairZThreshold();
        }
        return new OuSeries("PAIRSPREAD",
                String.format(java.util.Locale.ROOT,
                        "коинтеграционный спред log(BTC) − %.3f·log(ETH), дневные свечи", beta),
                times, spread, inEpisode,
                "|z| > " + cfg.pairZThreshold() + " (окно " + w + ")", "дни", 0,
                "МЕХАНИЧЕСКОЙ ПРИВЯЗКИ НЕТ — только статистическая гипотеза; β оценена in-sample", false);
    }

    /**
     * {@code BASIS} — базис спот–перп <b>одной площадки</b> на минутной сетке
     * (П1 док. 71). Спот — свеча Binance из {@code crypto.db}, перп — свеча fapi
     * из {@code perp_1m}; совпадение по {@code close_time} точное, поэтому
     * рассинхронизация ног равна нулю по построению.
     *
     * <p>Это исправление первого прогона, где базис мерился кросс-площадочно и на
     * пятиминутной сетке: отклонения базиса живут минуты, и на такой сетке они
     * усредняются независимо от того, есть эффект или нет.
     */
    /**
     * Непрерывный минутный базис с даты {@code theory.ou.basis-from} — ряд, на
     * котором тесты стационарности применимы, а частота эпизодов календарно
     * честна.
     */
    public OuSeries basisSameVenue(String symbol) {
        return basisSameVenue(symbol, "BASIS", basisFromMs(), Long.MAX_VALUE, false);
    }

    /**
     * Стресс-выборка того же базиса: 26 самых волатильных дней до начала
     * непрерывного окна. Ряд <b>сшит</b>, поэтому годится для статистик эпизодов
     * (глубина, длительность, доля возвратов) и не годится для тестов
     * стационарности — это и помечено флагом.
     */
    public OuSeries basisStress(String symbol) {
        return basisSameVenue(symbol, "BASIS_STRESS", 0, basisFromMs(), true);
    }

    private long basisFromMs() {
        return java.time.LocalDate.parse(cfg.basisFrom()).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli();
    }

    private OuSeries basisSameVenue(String symbol, String id, long fromMs, long toMs, boolean stitched) {
        TreeMap<Long, Double> spot = new TreeMap<>();
        db.query("SELECT close_time, close FROM candles WHERE symbol=? AND interval='1m' "
                        + "AND close_time >= ? AND close_time < ? ORDER BY open_time",
                rs -> {
                    spot.put(rs.getLong(1), rs.getDouble(2));
                    return null;
                }, symbol, fromMs, toMs);
        theoryDb.query("SELECT close_time, close FROM spot_1m WHERE symbol=? AND close_time >= ? "
                + "AND close_time < ? ORDER BY close_time", rs -> {
            spot.put(rs.getLong(1), rs.getDouble(2));
            return null;
        }, symbol, fromMs, toMs);
        TreeMap<Long, Double> perp = new TreeMap<>();
        theoryDb.query("SELECT close_time, close FROM perp_1m WHERE symbol=? ORDER BY close_time", rs -> {
            perp.put(rs.getLong(1), rs.getDouble(2));
            return null;
        }, symbol);

        List<double[]> rows = new ArrayList<>();
        int missing = 0;
        for (Map.Entry<Long, Double> e : spot.entrySet()) {
            Double p = perp.get(e.getKey());
            if (p == null || e.getValue() <= 0) {
                missing++;                     // минута без пары: считаем и отчитываем
                continue;
            }
            rows.add(new double[]{e.getKey() / 60_000.0, p / e.getValue() - 1});
        }
        double[] times = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] values = rows.stream().mapToDouble(r -> r[1]).toArray();
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i]) > cfg.basisThreshold();
        }
        double droppedShare = spot.isEmpty() ? 1 : (double) missing / spot.size();
        Jlog.info(log, "ou.basis.same-venue", Map.of("id", id, "symbol", symbol,
                "points", values.length, "missing", missing, "dropped_share", droppedShare));
        String window = stitched
                ? "сшитая стресс-выборка: самые волатильные дни до " + cfg.basisFrom()
                + " (там док. 61 §2.3 помещает отклонения выше порога)"
                : "непрерывное окно с " + cfg.basisFrom();
        return new OuSeries(id,
                "базис спот–перп ОДНОЙ площадки: перп Binance " + symbol + " против спота Binance "
                        + symbol + ", минутные свечи; " + window,
                times, values, inEpisode,
                "|базис| > " + cfg.basisThreshold() + " (порог входа, док. 61 §2.3)", "минуты",
                droppedShare,
                "рассинхронизация ног НУЛЕВАЯ по построению: обе ноги — свечи с одним close_time; "
                        + "доля минут без пары отчитана отдельно", stitched);
    }

    /**
     * {@code BASIS_XVENUE} — тот же базис, но <b>кросс-площадочный</b> и на
     * пятиминутной сетке снимков тикера Kraken. Оставлен как диагностика: он
     * показывает, сколько «эффекта» съедают разрешение и разные площадки по
     * сравнению с {@link #basisSameVenue}.
     */
    public OuSeries basis(String krakenSymbol, String spotSymbol) {
        TreeMap<Long, Double> perp = new TreeMap<>();
        db.query("SELECT ts, mark_price FROM kraken_ticker WHERE symbol=? AND mark_price IS NOT NULL "
                + "ORDER BY ts", rs -> {
            perp.put(rs.getLong(1), rs.getDouble(2));
            return null;
        }, krakenSymbol);
        TreeMap<Long, Double> spot = new TreeMap<>();
        db.query("SELECT close_time, close FROM candles WHERE symbol=? AND interval='1m' ORDER BY open_time",
                rs -> {
                    spot.put(rs.getLong(1), rs.getDouble(2));
                    return null;
                }, spotSymbol);

        List<double[]> rows = new ArrayList<>();
        int dropped = 0;
        for (Map.Entry<Long, Double> e : perp.entrySet()) {
            Map.Entry<Long, Double> nearest = nearest(spot, e.getKey());
            if (nearest == null) {
                dropped++;
                continue;
            }
            long skew = Math.abs(nearest.getKey() - e.getKey());
            if (skew > cfg.basisSkewToleranceMs()) {
                dropped++;                        // снимок сохраняется в сыром виде, в расчёт не идёт
                continue;
            }
            rows.add(new double[]{e.getKey() / 3_600_000.0, e.getValue() / nearest.getValue() - 1});
        }
        double[] times = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] values = rows.stream().mapToDouble(r -> r[1]).toArray();
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = Math.abs(values[i]) > cfg.basisThreshold();
        }
        double droppedShare = perp.isEmpty() ? 1 : (double) dropped / perp.size();
        Jlog.info(log, "ou.basis", Map.of("points", values.length, "dropped", dropped,
                "dropped_share", droppedShare));
        return new OuSeries("BASIS_XVENUE",
                "базис КРОСС-ПЛОЩАДОЧНЫЙ (диагностика разрешения): марк-цена перпа Kraken "
                        + krakenSymbol + " против спота Binance " + spotSymbol + ", снимки раз в 5 минут",
                times, values, inEpisode,
                "|базис| > " + cfg.basisThreshold() + " (порог входа, док. 61 §2.3)", "часы", droppedShare,
                "ОТКЛОНЕНИЕ ОТ ТЗ §3: синхронных снимков одной площадки нет, величина кросс-площадочная — "
                        + "в неё подмешан спред между площадками; допуск рассинхронизации "
                        + cfg.basisSkewToleranceMs() + " мс вместо 250 мс", false);
    }

    /**
     * {@code DEPEG} — отклонение USDC от паритета по данным стенда Revolut X:
     * подразумеваемый курс USDC/USD из пары {X/USDC} против {X/USD}.
     */
    public OuSeries depeg(String base) {
        RevxDb revx = revxDb.getIfAvailable();
        if (revx == null) {
            return null;
        }
        TreeMap<Long, Double> usdc = new TreeMap<>();
        TreeMap<Long, Double> usd = new TreeMap<>();
        revx.query("SELECT t_recv_ms, leg, bp1, ap1 FROM revx_book WHERE symbol IN (?, ?) "
                + "AND bp1 IS NOT NULL AND ap1 IS NOT NULL ORDER BY t_recv_ms", rs -> {
            double mid = (rs.getDouble(3) + rs.getDouble(4)) / 2;
            if ("usdc".equals(rs.getString(2))) {
                usdc.put(rs.getLong(1), mid);
            } else {
                usd.put(rs.getLong(1), mid);
            }
            return null;
        }, base + "/USDC", base + "/USD");

        List<double[]> rows = new ArrayList<>();
        int dropped = 0;
        for (Map.Entry<Long, Double> e : usdc.entrySet()) {
            Map.Entry<Long, Double> other = nearest(usd, e.getKey());
            if (other == null || Math.abs(other.getKey() - e.getKey()) > cfg.basisSkewToleranceMs()) {
                dropped++;
                continue;
            }
            // цена в USDC делённая на цену в USD = сколько USD стоит один USDC
            rows.add(new double[]{e.getKey() / 3_600_000.0, other.getValue() / e.getValue()});
        }
        double[] times = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] values = rows.stream().mapToDouble(r -> r[1]).toArray();
        boolean[] inEpisode = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            inEpisode[i] = values[i] < cfg.depegThreshold();
        }
        double droppedShare = usdc.isEmpty() ? 1 : (double) dropped / usdc.size();
        return new OuSeries("DEPEG",
                "подразумеваемый курс USDC/USD по паре " + base + " на Revolut X (mid/mid)",
                times, values, inEpisode,
                "курс ниже " + cfg.depegThreshold() + " дольше " + cfg.depegMinHours() + " ч (док. 60 §7, Z5)",
                "часы", droppedShare,
                "механизм привязки — погашение по номиналу; данные накапливаются стендом Revolut X", false);
    }

    // ------------------------------------------------------------------ утилиты

    private OuSeries synthetic(String id, String description, double[] x, double threshold,
                               double thresholdSd) {
        double[] times = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            times[i] = i;
        }
        double mean = OuCalibration.mean(x);
        boolean[] inEpisode = new boolean[x.length];
        for (int i = 0; i < x.length; i++) {
            inEpisode[i] = Math.abs(x[i] - mean) > threshold;
        }
        return new OuSeries(id, description, times, x, inEpisode,
                "|x − среднее| > " + thresholdSd + "σ стационарного распределения", "дни", 0,
                "контроль: параметры известны заранее", false);
    }

    private static Map.Entry<Long, Double> nearest(TreeMap<Long, Double> map, long key) {
        Map.Entry<Long, Double> floor = map.floorEntry(key);
        Map.Entry<Long, Double> ceiling = map.ceilingEntry(key);
        if (floor == null) {
            return ceiling;
        }
        if (ceiling == null) {
            return floor;
        }
        return key - floor.getKey() <= ceiling.getKey() - key ? floor : ceiling;
    }

    private static double variance(double[] v) {
        double m = OuCalibration.mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return v.length > 1 ? s / (v.length - 1) : 0;
    }
}
