package org.home.data.theory.kelly;

import org.home.data.core.Db;
import org.home.data.theory.Jlog;
import org.home.data.theory.TheoryDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Исходы событий S5 (ТЗ 66 §3.1): доходность <b>позиции</b> от входа до выхода,
 * на задействованный на неё капитал, <b>после всех издержек</b>.
 *
 * <p>Считается фактический P&amp;L позиции, а не аномальная доходность: аномальная
 * (за вычетом BTC) — правильная величина для вопроса «есть ли эффект», а для
 * вопроса «сколько ставить» нужна фактическая, потому что риск считается по ней.
 *
 * <p>§3.2: распределение строится <b>дважды</b> — с усечением стопом (то, что
 * торгуется) и без стопа (гипотетическое). Разница σ — цена стопа в терминах
 * сайзинга.
 */
@Component
@Lazy
public class S5Outcomes {

    private static final Logger log = LoggerFactory.getLogger(S5Outcomes.class);

    private final TheoryDb theoryDb;
    private final Db db;

    public S5Outcomes(TheoryDb theoryDb, Db db) {
        this.theoryDb = theoryDb;
        this.db = db;
    }

    /**
     * Исход одного события.
     *
     * @param base       токен
     * @param unlockDay  день разблокировки
     * @param pctSupply  доля разлока от циркулирующего предложения
     * @param outcome    фактический P&amp;L позиции после издержек (со стопом)
     * @param outcomeNoStop тот же P&amp;L без стопа (гипотетический)
     * @param stopHit    сработал ли стоп
     * @param heldDays   длительность удержания
     * @param bullRegime режим SMA200 на дату входа: true = BULL
     * @param btcMove    доходность BTC за окно события (общий фактор для §4.5)
     */
    public record Outcome(String base, String unlockDay, double pctSupply, double outcome,
                          double outcomeNoStop, boolean stopHit, int heldDays, boolean bullRegime,
                          double btcMove) {
    }

    /** Датасет исходов плюс счётчики отбраковки — обязательные поля отчёта §6.1. */
    public record Dataset(List<Outcome> outcomes, int rawEvents, int skippedFunding,
                          int skippedNoPrice, int cancelledByFilter) {

        public double[] values() {
            return outcomes.stream().mapToDouble(Outcome::outcome).toArray();
        }

        public double[] valuesNoStop() {
            return outcomes.stream().mapToDouble(Outcome::outcomeNoStop).toArray();
        }
    }

    /**
     * Строит исходы по правилам стратегии: шорт за {@code lead} дней до разлока,
     * закрытие в день разлока, стоп {@code stop}, отмена при дорогом шорте.
     *
     * @param stop стоп против позиции (0 = без стопа)
     */
    public Dataset build(KellyConfig cfg, double stop) {
        Map<String, TreeMap<String, double[]>> prices = new HashMap<>();
        theoryDb.query("SELECT base, day, close, high FROM s5_price ORDER BY base, day", rs -> {
            prices.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                    .put(rs.getString(2), new double[]{rs.getDouble(3), rs.getDouble(4)});
            return null;
        });
        Map<String, TreeMap<String, Double>> funding = new HashMap<>();
        theoryDb.query("SELECT base, day, rate_sum FROM s5_funding_daily", rs -> {
            funding.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                    .put(rs.getString(2), rs.getDouble(3));
            return null;
        });
        TreeMap<String, Double> btc = new TreeMap<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time", rs -> {
            btc.put(rs.getString(1), rs.getDouble(2));
            return null;
        });
        TreeMap<String, Boolean> bull = sma200Regime(btc);

        List<Object[]> raw = theoryDb.query(
                "SELECT base, unlock_day, pct_supply FROM s5_event WHERE pct_supply >= ? AND unlock_day >= ? "
                        + "ORDER BY unlock_day",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)},
                cfg.minPct(), cfg.from());

        List<Outcome> out = new ArrayList<>();
        int skippedNoPrice = 0;
        int skippedFunding = 0;
        for (Object[] row : raw) {
            String base = (String) row[0];
            String unlockDay = (String) row[1];
            double pct = (double) row[2];
            TreeMap<String, double[]> px = prices.get(base);
            if (px == null) {
                skippedNoPrice++;
                continue;
            }
            String entryDay = LocalDate.parse(unlockDay).minusDays(cfg.lead()).toString();
            double[] entry = px.get(entryDay);
            double[] exit = px.get(unlockDay);
            if (entry == null || exit == null || entry[0] <= 0) {
                skippedNoPrice++;
                continue;
            }
            TreeMap<String, Double> f = funding.getOrDefault(base, new TreeMap<>());
            // ожидание стоимости шорта — по 5 дням ДО входа (причинно: решение принимается на входе)
            double expected = 0;
            for (int d = cfg.lead(); d >= 1; d--) {
                expected += f.getOrDefault(LocalDate.parse(entryDay).minusDays(d).toString(), 0.0);
            }
            if (-expected > cfg.maxFundingCost()) {
                skippedFunding++;
                continue;
            }
            // funding за период удержания: шорт получает при rate > 0
            double held = 0;
            for (int d = 1; d <= cfg.lead(); d++) {
                held += f.getOrDefault(LocalDate.parse(entryDay).plusDays(d).toString(), 0.0);
            }
            double entryPrice = entry[0];
            double priceRet = (entryPrice - exit[0]) / entryPrice;
            boolean stopHit = false;
            if (stop > 0) {
                for (int d = 1; d <= cfg.lead(); d++) {
                    double[] bar = px.get(LocalDate.parse(entryDay).plusDays(d).toString());
                    if (bar != null && bar[1] >= entryPrice * (1 + stop)) {
                        stopHit = true;
                        break;
                    }
                }
            }
            double noStop = priceRet + held - cfg.tradeCost();
            double outcome = stopHit ? -(stop + cfg.slippage()) - cfg.tradeCost() : noStop;
            Double btcEntry = floor(btc, entryDay);
            Double btcExit = floor(btc, unlockDay);
            double btcMove = btcEntry == null || btcExit == null || btcEntry <= 0
                    ? 0 : btcExit / btcEntry - 1;
            Boolean regime = floor(bull, entryDay);
            out.add(new Outcome(base, unlockDay, pct, outcome, noStop, stopHit, cfg.lead(),
                    regime != null && regime, btcMove));
        }
        out.sort(Comparator.comparing(Outcome::unlockDay));
        Jlog.info(log, "kelly.outcomes", Map.of("raw", raw.size(), "events", out.size(),
                "skipped_no_price", skippedNoPrice, "skipped_funding", skippedFunding,
                "stop", stop, "stopped", out.stream().filter(Outcome::stopHit).count()));
        return new Dataset(out, raw.size(), skippedFunding, skippedNoPrice, skippedFunding);
    }

    private static <T> T floor(TreeMap<String, T> map, String day) {
        Map.Entry<String, T> e = map.floorEntry(day);
        return e == null ? null : e.getValue();
    }

    /** Режим SMA200 по дням (close > SMA200 → BULL); нужен для разреза §3.1. */
    private static TreeMap<String, Boolean> sma200Regime(TreeMap<String, Double> btc) {
        TreeMap<String, Boolean> out = new TreeMap<>();
        List<String> days = new ArrayList<>(btc.keySet());
        double sum = 0;
        for (int i = 0; i < days.size(); i++) {
            sum += btc.get(days.get(i));
            if (i >= 200) {
                sum -= btc.get(days.get(i - 200));
            }
            if (i >= 199) {
                out.put(days.get(i), btc.get(days.get(i)) > sum / 200);
            }
        }
        return out;
    }
}
