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
        for (Resting r : resting) {
            double left = r.size();
            for (MarketTrade t : trades) {
                if (left <= 1e-15 || t.aggressor() == null) {
                    continue;
                }
                // Покупку исполняет продавец, ударивший в бид не выше нашей цены.
                boolean hits = r.buy()
                        ? t.aggressor() == org.home.data.revx.sim.Side.SELL && t.price() <= r.price()
                        : t.aggressor() == org.home.data.revx.sim.Side.BUY && t.price() >= r.price();
                if (!hits) {
                    continue;
                }
                double qty = Math.min(left, t.qty());
                left -= qty;
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
