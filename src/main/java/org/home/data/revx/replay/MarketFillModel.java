package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * РАБОЧАЯ модель: заявка исполняется, только когда через её цену прошёл объём,
 * достаточный чтобы выбрать очередь перед ней.
 *
 * <h2>Правила и почему они такие</h2>
 *
 * Где ситуацию можно трактовать в свою пользу или против — выбирается ПРОТИВ
 * нас (ТЗ §4.3). Иначе стенд рисует красивую эквити, а живой бот её не находит.
 *
 * <ul>
 *   <li><b>Очередь считается по видимой книге</b> в момент постановки: объём на
 *       уровнях лучше нашей цены плюс объём на самой цене — мы встаём в конец
 *       этого уровня. Если наша цена лучше лучшей в книге, очередь пуста: мы
 *       создаём новый уровень;</li>
 *   <li><b>очередь не уменьшается от чужих отмен</b> — мы их не видим, а
 *       предполагать их выгодно нам, значит нельзя;</li>
 *   <li><b>сделка без известного агрессора исполнения не вызывает</b>;</li>
 *   <li><b>заявка вне пяти видимых уровней не исполняется никогда</b>: ни
 *       объёма перед ней, ни того, дошла ли до неё торговля, мы не знаем
 *       (ТЗ §4.6 п.7);</li>
 *   <li><b>замена сбрасывает очередь</b>. Площадка на замену создаёт ДРУГУЮ
 *       заявку с новым идентификатором — проверено живой заявкой 27.08.2026, —
 *       и наследник встаёт в конец. Это не мелочь: живой бот делает около
 *       14 000 замен в сутки против 38 постановок, то есть приоритет он теряет
 *       постоянно, и модель, сохраняющая его, завысит исполнения в разы.</li>
 * </ul>
 *
 * ⚠️ Перехват (принт прошёл по цене ХУЖЕ нашей, и мы считаем, что в реальности
 * он достался бы нам) здесь ВКЛЮЧЁН: экономически он защитим — продавец,
 * отдавший по дальнему биду, тем более отдал бы по нашему, — но это и есть
 * единственное реально связывающее допущение стенда. Проверить его можно только
 * живыми заявками, поэтому доля исполнений, полученных перехватом, считается
 * отдельно и должна попадать в отчёт.
 */
public final class MarketFillModel implements FillModel {

    /** Состояние очереди перед нашей заявкой. */
    private static final class Queue {
        double ahead;
        boolean visible;
        boolean improving;
    }

    private final MarketData market;
    private final Map<String, Queue> queues = new HashMap<>();
    private long lastMs = Long.MIN_VALUE;
    private long interceptFills;
    private long queueFills;
    private long invisibleSkips;

    public MarketFillModel(MarketData market) {
        this.market = market;
    }

    public long interceptFills() {
        return interceptFills;
    }

    public long queueFills() {
        return queueFills;
    }

    /** Сколько раз заявка стояла вне видимой части и потому исполниться не могла. */
    public long invisibleSkips() {
        return invisibleSkips;
    }

    @Override
    public void placed(Resting order) {
        Queue q = new Queue();
        BookView book = market.bookAt(order.placedMs());
        if (book == null || book.empty()) {
            q.visible = false;
            queues.put(order.id(), q);
            return;
        }
        Side side = order.buy() ? Side.BUY : Side.SELL;
        double best = order.buy() ? book.bestBid() : book.bestAsk();
        double deepest = book.deepestVisible(side);
        boolean better = order.buy() ? order.price() > best : order.price() < best;
        boolean inside = order.buy()
                ? order.price() >= deepest
                : order.price() <= deepest;
        q.visible = better || inside;
        q.improving = better;
        // Объём перед нами: всё, что стоит по цене строго лучше нашей, плюс весь
        // уровень нашей цены — приоритет там уже занят.
        double ahead = 0;
        for (BookView.Level l : order.buy() ? book.bids() : book.asks()) {
            boolean strictlyBetter = order.buy() ? l.price() > order.price() : l.price() < order.price();
            boolean same = Math.abs(l.price() - order.price()) < 1e-9;
            if (strictlyBetter || same) {
                ahead += l.qty();
            }
        }
        q.ahead = better ? 0 : ahead;
        queues.put(order.id(), q);
    }

    @Override
    public void cancelled(String orderId) {
        queues.remove(orderId);
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
        // ⚠️ Обход идёт по СДЕЛКАМ, а не по заявкам, и объём каждой сделки
        // тратится ОДИН раз. Наоборот было бы удобнее, но неверно: на счёте
        // несколько ботов (A и C котируют одну BTC/USDC с отступами 10 и 14
        // б.п.), их заявки стоят в книге одновременно, и обход по заявкам выдал
        // бы им обоим исполнение об один и тот же принт. Поток на площадке
        // конечен — за 17 часов на BTC/USDC прошло всего 314 сделок, — и делить
        // его между своими же заявками надо честно, по приоритету цены.
        Map<String, Double> left = new HashMap<>();
        for (Resting r : resting) {
            left.put(r.id(), r.size());
        }
        for (MarketTrade t : trades) {
            if (t.aggressor() == null) {
                continue;              // агрессор неизвестен — исполнения нет
            }
            boolean hitsBuys = t.aggressor() == Side.SELL;
            List<Resting> queueAtTrade = new ArrayList<>();
            for (Resting r : resting) {
                if (r.buy() != hitsBuys) {
                    continue;
                }
                boolean reached = r.buy() ? t.price() <= r.price() : t.price() >= r.price();
                if (reached && left.getOrDefault(r.id(), 0.0) > 1e-15) {
                    queueAtTrade.add(r);
                }
            }
            // Приоритет цены: лучший бид (выше) и лучший аск (ниже) исполняются
            // первыми, как и в настоящей книге.
            queueAtTrade.sort((x, y) -> hitsBuys
                    ? Double.compare(y.price(), x.price())
                    : Double.compare(x.price(), y.price()));

            double volume = t.qty();
            for (Resting r : queueAtTrade) {
                if (volume <= 1e-15) {
                    break;
                }
                Queue q = queues.get(r.id());
                if (q == null) {
                    continue;
                }
                if (!q.visible) {
                    invisibleSkips++;
                    continue;
                }
                // ⚠️ Очередь у каждой заявки списывается ОТДЕЛЬНО, из полного
                // объёма сделки, а не из остатка после соседней. Очереди перед
                // нашими заявками в книге ПЕРЕКРЫВАЮТСЯ — это одни и те же чужие
                // лоты, — и последовательное вычитание считало их дважды.
                // Измерено 05.09.2026: перед нашей ценой стоит ~7550 лотов и на
                // 7, и на 10 б.п. (котировка почти всегда ниже всех пяти
                // видимых уровней), а медианная сделка — 25 лотов. При таком
                // соотношении двойной счёт съедал объём подчистую и рисовал
                // конкуренцию там, где её нет: прогноз показывал падение A с 30
                // исполнений до 12 при добавлении второго бота.
                if (q.ahead > 0) {
                    q.ahead -= t.qty();
                    if (q.ahead > 0) {
                        continue;      // до нас очередь ещё не дошла
                    }
                    q.ahead = 0;
                }
                // А вот САМ объём сделки на всех наших заявках общий: делить его
                // надо. Это единственная настоящая конкуренция, и она невелика —
                // 27% сделок мельче двух лотов, остальным места хватает обоим.
                if (volume <= 1e-15) {
                    break;
                }
                double qty = Math.min(left.get(r.id()), volume);
                left.merge(r.id(), -qty, Double::sum);
                volume -= qty;
                boolean intercept = r.buy() ? t.price() < r.price() : t.price() > r.price();
                if (intercept) {
                    interceptFills++;
                } else {
                    queueFills++;
                }
                out.add(new Filled(r.id(), qty, r.price()));
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "очередь по видимой книге (рабочая)";
    }
}
