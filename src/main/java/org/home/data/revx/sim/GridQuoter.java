package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Сетка с якорем на СЕБЕСТОИМОСТИ. Принципиально другой механизм, чем
 * {@link Quoter}, и разница в том, к чему привязан аск.
 *
 * <pre>
 * Quoter:     аск = справедливая × (1 + отступ − скос)    ← якорь на рынке
 * GridQuoter: аск = цена покупки лота × (1 + маржа)       ← якорь на нашей цене входа
 * </pre>
 *
 * У {@link Quoter} логика «рынок хочет нашу ликвидность — пусть платит премию»,
 * и цена входа в ней не участвует вовсе: при уходе рынка вниз скос велит
 * разгружаться, и аск продаёт в убыток. Здесь наоборот: **продаём только дороже,
 * чем купили**, а падение переживаем накоплением.
 *
 * <h2>Управление расстоянием</h2>
 *
 * Единственная защита от «быстро набрать полный инвентарь на падении» — шаг
 * бида, растущий с числом набранных лотов:
 *
 * <pre>шаг(q) = шаг₀ × (1 + η · лотов)</pre>
 *
 * При {@code шаг₀ = 10 б.п.} и {@code η = 0.5} десятый лот покупается уже на
 * 60 б.п. ниже справедливой, сороковой — на 2%. Это и есть «на падении набирать
 * всё медленнее», выраженное одним числом.
 *
 * <h2>Чего эта модель НЕ делает</h2>
 *
 * Настоящая сетка держит в книге по заявке на каждый открытый лот. Модель
 * исполнения стенда умеет одну заявку на сторону, поэтому аск ставится на
 * САМЫЙ ДЕШЁВЫЙ открытый лот — тот, чья цель ближе всех к рынку и до кого
 * очередь дойдёт первой. Разница проявляется только на выносе, который забрал бы
 * сразу несколько уровней: у нас он заберёт один, а остальные уйдут на следующие
 * окна. Это занижает число продаж на резких взлётах, то есть ошибается в
 * консервативную сторону.
 *
 * <h2>Что здесь заведомо опасно</h2>
 *
 * Правило «продавать только выше входа» делает КАЖДУЮ закрытую сделку
 * прибыльной по построению. Убыток не исчезает — он копится в незакрытом
 * инвентаре и выходит наружу одним куском, если рынок ушёл и не вернулся. Это
 * структура проданного опциона, и мерить её надо по просадке и по поведению на
 * падении, а не по доле прибыльных сделок.
 */
public final class GridQuoter implements QuotePolicy {

    /** Открытая покупка: по какой цене взяли и сколько осталось непроданным. */
    private record Lot(double price, double qty) {
    }

    private final double lotSize;
    private final double margin;          // минимальная прибыль сделки, доля
    private final double baseStep;        // шаг бида при пустом инвентаре, доля
    private final double widening;        // η: во сколько раз шаг растёт на лот
    private final double maxStep;         // потолок шага, доля
    private final double inventoryCap;
    private final double quoteStep;

    private final List<Lot> lots = new ArrayList<>();

    public GridQuoter(double lotSize, double margin, double baseStep, double widening,
                      double maxStep, double inventoryCap, double quoteStep) {
        this.lotSize = lotSize;
        this.margin = margin;
        this.baseStep = baseStep;
        this.widening = widening;
        this.maxStep = maxStep > 0 ? maxStep : Double.MAX_VALUE;
        this.inventoryCap = inventoryCap;
        this.quoteStep = quoteStep;
    }

    @Override
    public Quoter.Quotes quotes(double fair, double inventory, double drift) {
        if (!(fair > 0)) {
            return new Quoter.Quotes(null, null);
        }
        Double bid = null;
        if (inventory < inventoryCap) {
            bid = round(fair * (1 - step(inventory)), true);
        }
        // Аск — на самый дешёвый открытый лот: его цель ближе всех к рынку.
        Double ask = null;
        double best = Double.MAX_VALUE;
        for (Lot lot : lots) {
            if (lot.qty() > 0) {
                best = Math.min(best, lot.price() * (1 + margin));
            }
        }
        if (best < Double.MAX_VALUE && inventory > 0) {
            ask = round(best, false);
        }
        return new Quoter.Quotes(bid, ask);
    }

    /** Шаг бида как функция набранного: чем больше держим, тем ниже следующая покупка. */
    public double step(double inventory) {
        double held = lotSize > 0 ? Math.max(0, inventory) / lotSize : 0;
        return Math.min(maxStep, baseStep * (1 + widening * held));
    }

    /**
     * Книга лотов ведётся по фактическим исполнениям, а не по нашим намерениям:
     * частичное исполнение оставляет остаток лота на месте, и его цель не меняется.
     */
    @Override
    public void onFill(Fill fill) {
        if (fill.side() == Side.BUY) {
            lots.add(new Lot(fill.price(), fill.qty()));
            return;
        }
        // Продаём тот лот, чью цель и выставляли, — самый дешёвый.
        double left = fill.qty();
        while (left > 1e-15) {
            int cheapest = -1;
            for (int i = 0; i < lots.size(); i++) {
                if (lots.get(i).qty() > 1e-15
                        && (cheapest < 0 || lots.get(i).price() < lots.get(cheapest).price())) {
                    cheapest = i;
                }
            }
            if (cheapest < 0) {
                return;                   // продали больше, чем помним: не наш лот
            }
            Lot lot = lots.get(cheapest);
            double take = Math.min(left, lot.qty());
            lots.set(cheapest, new Lot(lot.price(), lot.qty() - take));
            left -= take;
        }
        lots.removeIf(lot -> lot.qty() <= 1e-15);
    }

    /** Сколько лотов сейчас открыто — попадает в отчёт как мера набранного риска. */
    public int openLots() {
        return (int) lots.stream().filter(lot -> lot.qty() > 1e-15).count();
    }

    private Double round(double price, boolean down) {
        if (quoteStep <= 0) {
            return price;
        }
        double steps = price / quoteStep;
        return (down ? Math.floor(steps + 1e-9) : Math.ceil(steps - 1e-9)) * quoteStep;
    }
}
