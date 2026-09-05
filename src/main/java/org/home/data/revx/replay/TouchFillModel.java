package org.home.data.revx.replay;

import org.home.data.revx.sim.MarketTrade;

import java.util.ArrayList;
import java.util.List;

/**
 * ВЕРХНЯЯ ГРАНИЦА: «цена коснулась — меня исполнили».
 *
 * <h2>Зачем держать заведомо неверную модель</h2>
 *
 * Это то самое наивное допущение, от которого ТЗ §4.3 предостерегает прямым
 * текстом: очереди нет, объёма перед нами нет, любой принт через нашу цену —
 * наше исполнение. Само по себе оно завышает результат в разы.
 *
 * Польза не в точности, а в том, что граница ЗАВЕДОМО сверху. Прогноз без вилки
 * отдавать нельзя: одно число от модели очереди выглядит убедительнее, чем оно
 * есть, потому что держится на допущениях, не проверенных живыми заявками.
 * Здесь же логика простая:
 *
 * <ul>
 *   <li>результат стратегии обязан лежать МЕЖДУ этой моделью и рабочей;</li>
 *   <li>если даже здесь прибыли нет — считать дальше незачем, и это решается
 *       за один прогон.</li>
 * </ul>
 *
 * ⚠️ Одно правило всё же соблюдается и здесь: сделка без известного агрессора
 * исполнения не вызывает. Трактовать неизвестное в свою пользу нельзя даже в
 * заведомо оптимистичной модели — иначе граница перестанет быть осмысленной и
 * станет просто произвольно большой.
 */
public final class TouchFillModel implements FillModel {

    private final MarketData market;
    private long lastMs = Long.MIN_VALUE;

    public TouchFillModel(MarketData market) {
        this.market = market;
    }

    @Override
    public List<Filled> advance(long nowMs, List<Resting> resting) {
        List<Filled> out = new ArrayList<>();
        if (lastMs == Long.MIN_VALUE) {
            lastMs = nowMs;
            return out;
        }
        List<MarketTrade> trades = market.tradesBetween(lastMs, nowMs);
        lastMs = nowMs;
        if (resting.isEmpty() || trades.isEmpty()) {
            return out;
        }
        // Обход по СДЕЛКАМ: объём каждой тратится один раз и делится между
        // нашими заявками по приоритету цены. Даже в заведомо оптимистичной
        // модели двум ботам нельзя отдать один и тот же принт целиком каждому —
        // это была бы не верхняя граница, а произвольно большое число.
        java.util.Map<String, Double> left = new java.util.HashMap<>();
        for (Resting r : resting) {
            left.put(r.id(), r.size());
        }
        for (MarketTrade t : trades) {
            if (t.aggressor() == null) {
                continue;
            }
            // Покупку исполняет продавец, ударивший в бид не выше нашей цены.
            boolean hitsBuys = t.aggressor() == org.home.data.revx.sim.Side.SELL;
            List<Resting> queue = new ArrayList<>();
            for (Resting r : resting) {
                if (r.buy() != hitsBuys) {
                    continue;
                }
                boolean reached = r.buy() ? t.price() <= r.price() : t.price() >= r.price();
                if (reached && left.getOrDefault(r.id(), 0.0) > 1e-15) {
                    queue.add(r);
                }
            }
            queue.sort((x, y) -> hitsBuys
                    ? Double.compare(y.price(), x.price())
                    : Double.compare(x.price(), y.price()));

            double volume = t.qty();
            for (Resting r : queue) {
                if (volume <= 1e-15) {
                    break;
                }
                double qty = Math.min(left.get(r.id()), volume);
                left.merge(r.id(), -qty, Double::sum);
                volume -= qty;
                // Цена НАША, а не принта: лимитная заявка лучше своей цены не
                // исполняется, а хуже — не может по определению.
                out.add(new Filled(r.id(), qty, r.price()));
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "касание цены (ВЕРХНЯЯ ГРАНИЦА, очередь не учитывается)";
    }
}
