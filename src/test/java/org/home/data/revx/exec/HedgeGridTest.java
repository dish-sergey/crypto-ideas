package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СЕТКА ХЕДЖА: то, на чём держится честность сравнения.
 *
 * ⚠️ Главное свойство — без перевешиваний разница «с хеджем минус без» ровно
 * ноль. Иначе прибор приписывал бы хеджу то, что сидит в разметке суток или беты.
 */
class HedgeGridTest {

    private static final long MIN = 60_000L;

    /** Синтетический бот: запас растёт на лот каждый час, цена идёт вниз. */
    private static HedgeGrid.Bot bot() {
        HedgeGrid.Bot b = new HedgeGrid.Bot();
        b.id = "x";
        b.base = "SOL";
        b.lot = 0.03;
        int n = 3 * 24 * 60;            // трое суток по минуте
        b.ts = new long[n];
        b.fair = new double[n];
        b.inv = new double[n];
        long t0 = LocalDate.of(2026, 9, 10).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        for (int i = 0; i < n; i++) {
            b.ts[i] = t0 + i * MIN;
            b.fair[i] = 120 - i * 0.001;
            b.inv[i] = Math.min(0.2, (i / 60) * 0.03 % 0.21);
        }
        b.fillTs = new long[0];
        b.fillDq = new double[0];
        b.fillPx = new double[0];
        return b;
    }

    private static TreeMap<Long, Double> marks(HedgeGrid.Bot b) {
        TreeMap<Long, Double> m = new TreeMap<>();
        for (int i = 0; i < b.ts.length; i++) {
            m.put(b.ts[i], b.fair[i] * 1.0002);
        }
        return m;
    }

    private static double run(HedgeGrid.Config cfg, HedgeGrid.Exec exec, TreeMap<Long, Double> fund) {
        HedgeGrid.Bot b = bot();
        Map<LocalDate, double[]> days = new TreeMap<>();
        double[] agg = new double[6];
        double[] taker = new double[2];
        long[] window = {b.ts[0], b.ts[b.ts.length - 1] + 1};
        new HedgeGrid(null).sim(b, cfg, exec, window, marks(b), fund, 7, days, agg, taker);
        return agg[0];
    }

    @Test
    void безПеревешиванийРазностьНоль() {
        var cfg = new HedgeGrid.Config(HedgeGrid.Rule.BAND, 1000, 0, 30, false,
                HedgeGrid.Exec.SIM, 0);
        assertEquals(0.0, run(cfg, HedgeGrid.Exec.SIM, new TreeMap<>()), 1e-12);
    }

    /** Розыгрыш обязан лежать между «всё мейкером» и «всё тейкером». */
    @Test
    void розыгрышМеждуГраницами() {
        var cfg = new HedgeGrid.Config(HedgeGrid.Rule.BAND, 1, 0, 30, false,
                HedgeGrid.Exec.SIM, 0);
        double maker = run(cfg, HedgeGrid.Exec.MAKER, new TreeMap<>());
        double taker = run(cfg, HedgeGrid.Exec.TAKER, new TreeMap<>());
        double sim = run(cfg, HedgeGrid.Exec.SIM, new TreeMap<>());
        assertTrue(maker > taker, maker + " " + taker);
        assertTrue(sim <= maker + 1e-9 && sim >= taker - 1e-3, maker + " " + sim + " " + taker);
    }

    /** Шорт получает фандинг при положительной ставке. */
    @Test
    void шортПолучаетПриПоложительнойСтавке() {
        var cfg = new HedgeGrid.Config(HedgeGrid.Rule.BAND, 0.5, 0, 30, false,
                HedgeGrid.Exec.SIM, 0);
        TreeMap<Long, Double> fund = new TreeMap<>();
        HedgeGrid.Bot b = bot();
        for (long t = b.ts[0] - 3_600_000L; t < b.ts[b.ts.length - 1] + 3_600_000L; t += 3_600_000L) {
            fund.put(t, 1e-3);
        }
        assertTrue(run(cfg, HedgeGrid.Exec.MAKER, fund) > run(cfg, HedgeGrid.Exec.MAKER, new TreeMap<>()));
    }

    @Test
    void фандингПоЧасам() {
        TreeMap<Long, Double> fund = new TreeMap<>();
        fund.put(0L, 1e-4);
        fund.put(3_600_000L, 3e-4);
        // полчаса по 1e-4 и полчаса по 3e-4
        assertEquals(0.5e-4 + 1.5e-4,
                HedgeGrid.fundingBetween(fund, 1_800_000L, 5_400_000L), 1e-15);
    }

    @Test
    void кластернаяОшибкаПоСуткам() {
        Map<LocalDate, double[]> days = new TreeMap<>();
        days.put(LocalDate.of(2026, 9, 1), new double[]{2, 2});
        days.put(LocalDate.of(2026, 9, 2), new double[]{0, 2});
        // среднее 0.5 на бот-сутки; остатки кластеров 1 и −1 → var = 2·2/1, se = √4/4
        assertEquals(0.5, HedgeGrid.clusterSe(days), 1e-12);
    }

    @Test
    void вероятностьИсполненияМонотонна() {
        double[] p = HedgeGrid.FILL.get("SOL");
        assertEquals(0.659, HedgeGrid.fillProb(p, 30), 1e-12);
        assertEquals(0.760, HedgeGrid.fillProb(p, 120), 1e-12);
        assertTrue(HedgeGrid.fillProb(p, 10) < HedgeGrid.fillProb(p, 30));
        assertEquals(30, HedgeGrid.inverseFill(p, 0.659), 1e-9);
        List.of(0.1, 0.3, 0.5, 0.7).forEach(u ->
                assertEquals(u, HedgeGrid.fillProb(p, HedgeGrid.inverseFill(p, u)), 1e-9));
    }
}
