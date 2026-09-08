package org.home.data.revx.layout;

import org.home.data.revx.sim.Fill;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Расстановка с сеткой, заданной ДОЛЕЙ ОТСТУПА, а не абсолютной величиной.
 *
 * <h2>Чем отличается от V1</h2>
 *
 * У {@link LayoutV1} шаг сетки задан в долях цены: 2 базисных пункта независимо
 * от того, стоит ли бот на десяти базисных пунктах или на двадцати шести. От
 * этого сетка получается несоразмерной самой себе: у биткойна на десяти уровни
 * идут 10/12/14 — то есть третий уровень на 40% дальше первого; у ENA на
 * двадцати шести те же 26/28/30 — всего на 15% дальше. Одна и та же настройка
 * означает разную конструкцию.
 *
 * Здесь шаг — доля отступа: при {@code stepPct} = 0.3 уровни идут
 * {@code d, 1.3d, 1.6d}, и форма сетки одинакова на любой паре и любом отступе.
 * Сравнивать пары между собой становится осмысленно.
 *
 * <h2>Пол по тикам, и почему он обязателен</h2>
 *
 * Доля отступа может оказаться мельче шага цены. У ENA тик равен 6.01 б.п. при
 * цене 0.166, и на отступе 26 б.п. шаг в 20% — это 5.2 б.п., то есть МЕНЬШЕ
 * ТИКА: три уровня лягут на одну цену, как это и было до 07.09.2026, когда в
 * книге стояли три аска по одной цене. Поэтому расстояние между соседними
 * уровнями поднимается до {@link #minTicks} шагов цены, если доля дала меньше.
 *
 * Пол — не украшение: без него настройка «20% на ENA» молча превращается в один
 * уровень с тройным лотом, и опыт мерит не то, что написано.
 *
 * <h2>Отступ берётся из ЦЕНЫ, а не из параметров</h2>
 *
 * Базовый отступ вычисляется как {@code (fair − bid) / fair} по тому, что выдала
 * политика. Так сетка наследует всё, что политика уже сделала: скос по
 * инвентарю, раздвижение на широкую опору, раздвижение на дефицит постановок.
 * Взять {@code params.offset()} значило бы разложить уровни вокруг цены, которой
 * на этом тике нет.
 *
 * Стороны масштабируются каждая от своего отступа: при скосе они не равны, и
 * усреднять их — значит терять сам скос.
 */
public final class LayoutV2 implements OrderLayout {

    private final Quoter.Params params;
    private final QuotePolicy policy;
    private final int levels;
    private final double stepPct;
    private final int minTicks;
    private final boolean innerFirst;
    private final double spreadToOffset;
    private final double spreadToOffsetMaxPct;
    private final double budgetWiden;

    /**
     * @param stepPct  шаг сетки как доля отступа: 0.3 даёт уровни d, 1.3d, 1.6d
     * @param minTicks минимальное расстояние между уровнями в шагах цены
     */
    public LayoutV2(Quoter.Params params, QuotePolicy policy, int levels, double stepPct,
                    int minTicks, boolean innerFirst, double spreadToOffset,
                    double spreadToOffsetMaxPct, double budgetWiden) {
        this.params = params;
        this.policy = policy;
        this.levels = Math.max(1, levels);
        this.stepPct = stepPct;
        this.minTicks = Math.max(1, minTicks);
        this.innerFirst = innerFirst;
        this.spreadToOffset = spreadToOffset;
        this.spreadToOffsetMaxPct = spreadToOffsetMaxPct;
        this.budgetWiden = budgetWiden;
    }

    @Override
    public String name() {
        return "v2:" + Math.round(stepPct * 100) + "%×" + minTicks + "тик";
    }

    @Override
    public void onFill(Fill fill) {
        policy.onFill(fill);
    }

    @Override
    public List<DesiredOrder> layout(MarketState s) {
        List<DesiredOrder> out = new ArrayList<>();
        if (!s.usable()) {
            return out;
        }
        Quoter.Quotes target = policy.quotes(s.fair(), s.inventory(), s.drift());
        target = LayoutV1.widenForSpread(target, s.fair(), s.referenceSpreadPct(), spreadToOffset);
        target = LayoutV1.widenForBudget(target, s.fair(), s.budgetPressure(), budgetWiden);

        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            Double bid = onTick(Side.BUY,
                    noCross(Side.BUY, levelPrice(Side.BUY, target.bid(), s, i), s),
                    s.quoteStep());
            Double ask = onTick(Side.SELL,
                    noCross(Side.SELL, levelPrice(Side.SELL, target.ask(), s, i), s),
                    s.quoteStep());
            if (bid != null && bid > 0) {
                out.add(new DesiredOrder(Side.BUY, i, bid,
                        params.sizeFor(Side.BUY, s.inventory())));
            }
            if (ask != null && ask > 0) {
                out.add(new DesiredOrder(Side.SELL, i, ask,
                        params.sizeFor(Side.SELL, s.inventory())));
            }
        }
        return out;
    }

    /**
     * Цена уровня: отступ умножается на {@code 1 + i·stepPct}, но расстояние до
     * предыдущего уровня не может быть меньше {@link #minTicks} шагов цены.
     */
    private Double levelPrice(Side side, Double base, MarketState s, int level) {
        if (base == null || level == 0) {
            return base;
        }
        double fair = s.fair();
        double offset = side == Side.BUY ? (fair - base) / fair : (base - fair) / fair;
        if (!(offset > 0)) {
            return base;                  // цена уже по ту сторону — множить нечего
        }
        double scaled = offset * (1 + level * stepPct);
        // Пол по тикам: доля отступа могла дать меньше шага цены.
        double floor = offset + level * minTicks * s.quoteStep() / fair;
        double used = Math.max(scaled, floor);
        return side == Side.BUY ? fair * (1 - used) : fair * (1 + used);
    }

    /** Не пересекать книгу площадки — то же правило, что и в V1. */
    private static Double noCross(Side side, Double price, MarketState s) {
        if (price == null) {
            return null;
        }
        double step = Math.max(s.quoteStep(), 1e-9);
        if (side == Side.BUY && s.bookAsk() > 0 && price >= s.bookAsk()) {
            double clamped = s.bookAsk() - step;
            return clamped > 0 ? clamped : null;
        }
        if (side == Side.SELL && s.bookBid() > 0 && price <= s.bookBid()) {
            return s.bookBid() + step;
        }
        return price;
    }

    /** К допустимому тику, наружу от рынка. */
    private static Double onTick(Side side, Double price, double step) {
        if (price == null || !(step > 0) || !(price > 0)) {
            return price;
        }
        double units = price / step;
        double snapped = (side == Side.BUY ? Math.floor(units) : Math.ceil(units)) * step;
        return snapped > 0 ? snapped : price;
    }
}
