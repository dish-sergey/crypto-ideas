package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Прогон котирования по окнам данных. Окно — промежуток между снимками книги:
 * в его начале известны книга и справедливая цена, внутри проходят сделки.
 *
 * Порядок внутри окна намеренно такой: сначала обновляем видимость и котировки
 * ПО ДАННЫМ НАЧАЛА окна, потом пропускаем через них сделки. Решение на момент t
 * не может опираться на снимок, полученный после t (ТЗ §4.6 п.4).
 *
 * Прогон детерминирован: одинаковый вход даёт побитово одинаковый результат,
 * случайности нет вовсе (ТЗ §7 «Воспроизводимость»).
 */
public final class SimEngine {

    /** Окно данных. {@code quotable} = разрешено ли котировать (гейт из FairPrice). */
    public record Window(long tsMs, double fair, boolean quotable, BookView book,
                         List<MarketTrade> trades) {

        public static Window of(long tsMs, double fair, BookView book, List<MarketTrade> trades) {
            return new Window(tsMs, fair, true, book, trades);
        }
    }

    public record Result(
            PnlBook.Decomposition pnl,
            List<Fill> fills,
            int requotes,
            int windows,
            int windowsPaused,
            double maxInventory,
            double avgInventory,
            int windowsAtCap,             // нет БИДА: инвентарь упёрся в потолок
            int windowsAtZero,            // нет АСКА: продавать нечем, спот

            double filledQty,
            double marketQty,
            double maxDrawdown,
            double fairFirst,
            double fairLast,
            TreeMap<Long, Double> fairSeries,
            ExecutionModel.Stats execution) {

        /** Перевыставлений в сутки — сверяется с лимитом запросов (ТЗ §4.2, §5.4 п.6). */
        public double requotesPerDay(long spanMs) {
            return spanMs <= 0 ? 0 : requotes * 86_400_000.0 / spanMs;
        }

        /** Какую долю рыночного потока мы забрали (ТЗ §5.3). */
        public double flowShare() {
            return marketQty <= 0 ? 0 : filledQty / marketQty;
        }

        /**
         * Контроль buy & hold (ТЗ §4.7): держать средний инвентарь стратегии тот же
         * период без торговли. Отвечает, не был ли весь результат просто движением рынка.
         */
        public double buyAndHoldPnl() {
            return avgInventory * (fairLast - fairFirst);
        }
    }

    private final QuotePolicy policy;
    private final Quoter quoter;
    private final Quoter.Params params;
    private final ExecutionModel.Limits limits;
    private final double makerFeeRate;
    private final int quotePeriodWindows;

    public SimEngine(Quoter.Params params, ExecutionModel.Limits limits, double makerFeeRate) {
        this(params, limits, makerFeeRate, new Quoter(params));
    }

    public SimEngine(Quoter.Params params, ExecutionModel.Limits limits, double makerFeeRate,
                     QuotePolicy policy) {
        this(params, limits, makerFeeRate, policy, 1);
    }

    /**
     * @param quotePeriodWindows раз во сколько окон мы СМОТРИМ НА РЫНОК и принимаем
     *                           решение о котировке. 1 = каждое окно (текущие 5 с),
     *                           12 = раз в минуту.
     *
     * Это и есть цена отсутствия WebSocket, переведённая в измеримую величину.
     * Между решениями заявка висит по старой цене: рынок за это время уходит, и
     * часть исполнений достаётся нам по устаревшей котировке. Быстрее пяти секунд
     * промоделировать нечего — данные собраны с этим шагом, — но наклон кривой
     * показывает, сколько стоит каждая ступень задержки.
     *
     * Гейт справедливой цены на неквотирующих окнах не перепроверяется намеренно:
     * если мы смотрим на рынок раз в минуту, то и о поломке опоры узнаём раз в
     * минуту. Иначе получилась бы задержка только в цене, но не в защите.
     */
    public SimEngine(Quoter.Params params, ExecutionModel.Limits limits, double makerFeeRate,
                     QuotePolicy policy, int quotePeriodWindows) {
        this.params = params;
        this.quoter = new Quoter(params);
        this.policy = policy;
        this.limits = limits;
        this.makerFeeRate = makerFeeRate;
        this.quotePeriodWindows = Math.max(1, quotePeriodWindows);
    }

    public Result run(List<Window> windows) {
        ExecutionModel execution = new ExecutionModel(limits);
        PnlBook pnl = new PnlBook(makerFeeRate);
        TreeMap<Long, Double> fairSeries = new TreeMap<>();
        List<Fill> allFills = new ArrayList<>();

        Double restingBid = null;
        Double restingAsk = null;
        int requotes = 0;
        int paused = 0;
        int atCap = 0;
        int atZero = 0;
        double maxInventory = 0;
        double inventorySum = 0;
        int inventorySamples = 0;
        double filledQty = 0;
        double marketQty = 0;
        double peakEquity = 0;
        double maxDrawdown = 0;
        double fairFirst = 0;

        for (int wi = 0; wi < windows.size(); wi++) {
            Window window = windows.get(wi);
            if (window.fair() > 0) {
                fairSeries.put(window.tsMs(), window.fair());
                if (fairFirst == 0) {
                    fairFirst = window.fair();
                }
            }
            for (MarketTrade trade : window.trades()) {
                marketQty += trade.qty();
            }

            // Смотрим на рынок и принимаем решения только на квотирующих окнах.
            // Между ними заявка висит по старой цене — это и есть задержка.
            boolean decisionWindow = wi % quotePeriodWindows == 0;

            if (decisionWindow && (!window.quotable() || !(window.fair() > 0))) {
                // курс ненадёжен или опора сломана — снимаем котировки целиком
                if (restingBid != null || restingAsk != null) {
                    execution.cancelAll();
                    restingBid = null;
                    restingAsk = null;
                    requotes++;
                }
                paused++;
                continue;
            }
            if (!(window.fair() > 0)) {
                paused++;
                continue;                 // без справедливой цены нечего мерить
            }

            execution.refresh(window.book());
            Quoter.Quotes target = decisionWindow
                    ? policy.quotes(window.fair(), pnl.inventory())
                    : new Quoter.Quotes(restingBid, restingAsk);

            if (quoter.shouldRequote(restingBid, target.bid())) {
                execution.cancel(Side.BUY);
                restingBid = target.bid();
                if (restingBid != null) {
                    execution.place(Side.BUY, restingBid, params.size(), window.book(), window.tsMs());
                }
                requotes++;
            }
            if (quoter.shouldRequote(restingAsk, target.ask())) {
                execution.cancel(Side.SELL);
                restingAsk = target.ask();
                if (restingAsk != null) {
                    // продать можно только то, что есть: спот, шортить нечем
                    double size = Math.min(params.size(), pnl.inventory());
                    if (size > 0) {
                        execution.place(Side.SELL, restingAsk, size, window.book(), window.tsMs());
                    } else {
                        restingAsk = null;
                    }
                }
                requotes++;
            }

            execution.observe();
            // Захват считается против ПЕРВОЙ справедливой цены после исполнения:
            // заявка висит целое окно и может устареть. Хвост окна сравнивать не с
            // чем — там остаётся цена котирования.
            double fairAfter = window.fair();
            for (int next = wi + 1; next < windows.size(); next++) {
                if (windows.get(next).fair() > 0) {
                    fairAfter = windows.get(next).fair();
                    break;
                }
            }
            List<Fill> fills = execution.onWindow(window.trades(), window.fair(), fairAfter);
            for (Fill fill : fills) {
                pnl.add(fill);
                allFills.add(fill);
                filledQty += fill.qty();
            }
            if (!fills.isEmpty()) {
                // исполненную сторону надо будет выставить заново
                for (Fill fill : fills) {
                    if (fill.side() == Side.BUY && execution.remaining(Side.BUY) <= 0) {
                        restingBid = null;
                    }
                    if (fill.side() == Side.SELL && execution.remaining(Side.SELL) <= 0) {
                        restingAsk = null;
                    }
                }
            }

            maxInventory = Math.max(maxInventory, pnl.inventory());
            inventorySum += pnl.inventory();
            inventorySamples++;
            if (pnl.inventory() >= params.inventoryCap() - 1e-12) {
                atCap++;
            }
            // Зеркальная беда: при нулевом инвентаре нет АСКА — продавать нечем.
            // Считается там же и так же, чтобы две колонки читались рядом.
            if (pnl.inventory() <= 1e-12) {
                atZero++;
            }

            // просадка считается по переоценке на каждом окне, а не по итогу
            double equity = pnl.mark(window.fair());
            peakEquity = Math.max(peakEquity, equity);
            maxDrawdown = Math.max(maxDrawdown, peakEquity - equity);
        }

        double fairLast = fairSeries.isEmpty() ? 0 : fairSeries.lastEntry().getValue();
        double avgInventory = inventorySamples == 0 ? 0 : inventorySum / inventorySamples;
        return new Result(pnl.decompose(fairLast), allFills, requotes, windows.size(), paused,
                maxInventory, avgInventory, atCap, atZero, filledQty, marketQty, maxDrawdown,
                fairFirst, fairLast, fairSeries, execution.stats());
    }
}
