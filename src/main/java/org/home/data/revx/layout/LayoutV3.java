package org.home.data.revx.layout;

import org.home.data.revx.sim.Fill;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Пропорциональная сетка, которая НЕ РАСТЯГИВАЕТСЯ вместе с раздвижением.
 *
 * <h2>Что чинится по сравнению с V2</h2>
 *
 * {@link LayoutV2} берёт долю от отступа, каким он получился ПОСЛЕ раздвижения
 * на широкую опору и на дефицит постановок. А динамический гейт раздвигает ENA
 * до семидесяти с лишним базисных пунктов, и тогда сетка в 30% разъезжается на
 * 74 / 96 / 118 вместо 74 / 82 / 90. Замер 08.09.2026: 614% против 650% у
 * абсолютного шага в 8 б.п., и доля ленты падает с 49% до 47% — уровни просто
 * уходят туда, где до них не доходит торговля.
 *
 * Это не ошибка счёта, а свойство конструкции: доля от раздвинутого отступа
 * УМНОЖАЕТ раздвижение. Замысел же был в другом — сделать форму сетки
 * соразмерной отступу, а не усиливать гейт.
 *
 * <h2>Как здесь</h2>
 *
 * Доля берётся от БАЗОВОГО отступа — того, что дала политика до всякого
 * раздвижения. Само раздвижение прибавляется всем уровням ОДИНАКОВОЙ абсолютной
 * величиной, поэтому сетка едет целиком, сохраняя форму:
 *
 * <pre>
 *   база:        26 / 33.8 / 41.6 б.п.
 *   раздвижение: +48 всем троим
 *   итог:        74 / 81.8 / 89.6 б.п.
 * </pre>
 *
 * Это почти в точности абсолютный шаг в 8 б.п., который и выигрывал, — но
 * записанный так, что переносится на любую пару и любой отступ без пересчёта.
 *
 * <h2>Пол по тикам</h2>
 *
 * Остаётся от V2 и по той же причине: доля отступа бывает мельче шага цены, и
 * тогда уровни лягут на одну цену. ⚠️ Но замер показал, что для ENA пол ВРЕДЕН
 * (297 → 251 → 194 при полах в 1/2/3 тика): раздвигать уровни на 12–18 б.п.
 * ради различимости дороже, чем оставить их на одной цене. Поэтому умолчание —
 * один тик, и поднимать его стоит только с числами в руках.
 */
public final class LayoutV3 implements OrderLayout {

    private final Quoter.Params params;
    private final QuotePolicy policy;
    private final int levels;
    private final double stepPct;
    private final int minTicks;
    private final boolean innerFirst;
    private final double spreadToOffset;
    private final double spreadToOffsetMaxPct;
    private final double budgetWiden;

    public LayoutV3(Quoter.Params params, QuotePolicy policy, int levels, double stepPct,
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
        return "v3:" + Math.round(stepPct * 100) + "%"
                + (minTicks > 1 ? "×" + minTicks + "тик" : "");
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
        Quoter.Quotes base = policy.quotes(s.fair(), s.inventory(), s.drift());
        Quoter.Quotes wide = LayoutV1.widenForBudget(
                LayoutV1.widenForSpread(base, s.fair(), s.referenceSpreadPct(), spreadToOffset),
                s.fair(), s.budgetPressure(), budgetWiden);

        // Раздвижение как АБСОЛЮТНАЯ добавка: на сколько ушла нулевая цена.
        // Дальше она прибавляется всем уровням поровну, и форма сетки не меняется.
        double shiftBid = base.bid() != null && wide.bid() != null
                ? base.bid() - wide.bid() : 0;
        double shiftAsk = base.ask() != null && wide.ask() != null
                ? wide.ask() - base.ask() : 0;

        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            Double bid = onTick(Side.BUY, noCross(Side.BUY,
                    levelPrice(Side.BUY, base.bid(), shiftBid, s, i), s), s.quoteStep());
            Double ask = onTick(Side.SELL, noCross(Side.SELL,
                    levelPrice(Side.SELL, base.ask(), shiftAsk, s, i), s), s.quoteStep());
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
     * Цена уровня: доля от БАЗОВОГО отступа плюс общее раздвижение.
     *
     * @param base  цена уровня 0 ДО раздвижения
     * @param shift на сколько раздвижение увело нулевой уровень
     */
    private Double levelPrice(Side side, Double base, double shift, MarketState s, int level) {
        if (base == null) {
            return null;
        }
        double fair = s.fair();
        double offset = side == Side.BUY ? (fair - base) / fair : (base - fair) / fair;
        if (!(offset > 0)) {
            return side == Side.BUY ? base - shift : base + shift;
        }
        double scaled = offset * (1 + level * stepPct);
        double floor = offset + level * minTicks * s.quoteStep() / fair;
        double used = Math.max(scaled, floor);
        double price = side == Side.BUY ? fair * (1 - used) : fair * (1 + used);
        return side == Side.BUY ? price - shift : price + shift;
    }

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

    private static Double onTick(Side side, Double price, double step) {
        if (price == null || !(step > 0) || !(price > 0)) {
            return price;
        }
        double units = price / step;
        double snapped = (side == Side.BUY ? Math.floor(units) : Math.ceil(units)) * step;
        return snapped > 0 ? snapped : price;
    }
}
