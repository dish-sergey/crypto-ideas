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
            int gateOpenWindows,          // окон, где страховка от тренда была включена
            double avgEr,                 // средний коэффициент эффективности
            int stopHits,                 // срабатываний стопа по просадке
            int stoppedWindows,           // окон в режиме остановки

            double filledQty,
            double marketQty,
            double maxDrawdown,
            double fairFirst,
            double fairLast,
            TreeMap<Long, Double> fairSeries,
            double pnlAtStart,            // P&L, если бы цена вернулась к началу окна (b&h там = 0)
            double capAtDropPct,          // просадка цены в момент ПЕРВОГО заполнения потолка, %
            int frozenCycles,             // сколько раз замороженная пара выпускалась заново
            long frozenHeldWindows,       // окон, в которые пара стояла нетронутой
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

    /**
     * Держим ли заявку на месте вместо переставления (задание Z8, док. 109 §II).
     *
     * Выключенная липкость возвращает {@code false} всегда — поведение в точности
     * прежнее, и это приёмочное условие задания: базовый прогон обязан совпасть
     * до последнего знака.
     *
     * Отступ считается ОТНОСИТЕЛЬНЫМ: {@code (fair − цена) / fair} для бида. Он
     * растёт, когда цена уходит вверх (заявка отстала — безопасно, но бесполезно),
     * и уменьшается вплоть до отрицательного, когда цена падает (заявка оказалась
     * НАД справедливой — опасный снос, механизм M3).
     */
    private static boolean sticks(Quoter.Params params, Double resting, Side side,
                                  double fair, long ageMs, double skewShift,
                                  boolean filledLast) {
        Quoter.Sticky sticky = params.sticky();
        if (!sticky.enabled() || resting == null || !(fair > 0)) {
            return false;
        }
        if (sticky.replaceOnFill() && filledLast) {
            return false;
        }
        if (ageMs >= sticky.maxAgeMs()) {
            return false;
        }
        if (Math.abs(skewShift) > sticky.skewDelta()) {
            return false;
        }
        double delta = side == Side.BUY ? (fair - resting) / fair : (resting - fair) / fair;
        double d = params.offset();
        if (delta > sticky.outerMult() * d) {
            return false;                     // пассивный снос: заявка уехала далеко
        }
        return !(delta < sticky.innerMult() * d);   // опасный снос: заявка у справедливой
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
        // Насколько глубоко успела упасть цена, когда инвентарь впервые упёрся в
        // потолок. Это и есть ЁМКОСТЬ ПАДЕНИЯ: дальше конструкция уже не котирует
        // на покупку, а просто держит позицию.
        double fairPeak = 0;
        double capAtDropPct = Double.NaN;
        double fairFirst = 0;
        int driftAnchor = 0;
        EfficiencyRatio er = new EfficiencyRatio(
                params.erWindowMs() > 0 ? params.erWindowMs() : 86_400_000L,
                params.erSampleMs() > 0 ? params.erSampleMs() : 3_600_000L);
        int gateOpenWindows = 0;
        double erSum = 0;
        long stoppedUntilMs = Long.MIN_VALUE;
        int stopHits = 0;
        // Состояние липкой котировки: когда и при каком скосе заявка выставлена.
        long bidPlacedMs = 0;
        long askPlacedMs = 0;
        double bidSkewAtPlace = 0;
        double askSkewAtPlace = 0;
        boolean bidFilledLast = false;
        boolean askFilledLast = false;
        int stoppedWindows = 0;
        int erSamples = 0;
        // Состояние замороженной пары: выставлена ли она и когда её можно трогать.
        boolean frozenArmed = false;
        long frozenRequoteAtMs = Long.MIN_VALUE;
        long frozenPlacedMs = 0;
        int frozenCycles = 0;
        long frozenHeldWindows = 0;

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
                // Гейт снял пару целиком — значит замораживать больше нечего, и
                // после его открытия пара выставляется заново как с нуля.
                frozenArmed = false;
                paused++;
                continue;
            }
            if (!(window.fair() > 0)) {
                paused++;
                continue;                 // без справедливой цены нечего мерить
            }

            double erNow = er.accept(window.tsMs(), window.fair());
            if (!Double.isNaN(erNow)) {
                erSum += erNow;
                erSamples++;
            }

            // Дрейф опоры за окно наблюдения. Якорь двигается монотонно вперёд,
            // поэтому весь ряд обходится один раз, а не заново на каждом тике.
            boolean gateOpen = er.open(params.driftGateEr());
            if (gateOpen) {
                gateOpenWindows++;
            }
            // Страховка от тренда включается только в режиме, ради которого она
            // куплена. Гейт закрывает СЛАГАЕМОЕ ДРЕЙФА, а не котирование целиком.
            double driftNow = 0;
            if (params.driftBeta() != 0 && params.driftWindowMs() > 0) {
                long since = window.tsMs() - params.driftWindowMs();
                while (driftAnchor + 1 < wi && windows.get(driftAnchor + 1).tsMs() < since) {
                    driftAnchor++;
                }
                double anchorFair = windows.get(driftAnchor).fair();
                if (anchorFair > 0 && windows.get(driftAnchor).tsMs() >= since - params.driftWindowMs()) {
                    driftNow = (window.fair() - anchorFair) / anchorFair;
                }
            }
            if (!gateOpen) {
                driftNow = 0;
            }

            // Стоп по просадке инвентаря. Он ничего не КЛАССИФИЦИРУЕТ — реагирует на
            // реализованный ущерб, величину полностью наблюдаемую. Гейт провалился
            // именно на классификации ненаблюдаемого признака (док. 106 §5), стоп
            // этого вопроса не задаёт вовсе (док. 107 §5).
            boolean stopped = window.tsMs() < stoppedUntilMs;
            if (params.stopDrawdownPct() > 0) {
                double limit = params.stopDrawdownPct() / 100.0
                        * params.inventoryCap() * window.fair();
                double equityNow = pnl.mark(window.fair());
                if (!stopped && peakEquity - equityNow > limit && limit > 0) {
                    stopped = true;
                    stopHits++;
                    stoppedUntilMs = window.tsMs() + params.stopCoolOffMs();
                    // Пик сбрасывается на выходе, иначе правило срабатывает один раз
                    // и навсегда: старая вершина недостижима, просадка от неё вечна.
                    peakEquity = equityNow;
                }
            }
            if (stopped) {
                stoppedWindows++;
            }

            execution.refresh(window.book());
            Quoter.Quotes target = decisionWindow
                    ? policy.quotes(window.fair(), pnl.inventory(), driftNow)
                    : new Quoter.Quotes(restingBid, restingAsk);

            if (stopped) {
                // Набирать перестаём совсем, а разгрузку делаем маркетабельной:
                // аск переставляется на лучший бид. Это платная продажа — половина
                // спреда, — и она в модели учитывается как обычное исполнение, а не
                // дарится по справедливой цене.
                Double liquidate = window.book().bestBid() > 0 ? window.book().bestBid() : null;
                target = new Quoter.Quotes(null, pnl.inventory() > 0 ? liquidate : null);
            }
            double skewNow = Quoter.skew(params, pnl.inventory(), driftNow);
            boolean bidSticks = sticks(params, restingBid, Side.BUY, window.fair(),
                    window.tsMs() - bidPlacedMs, skewNow - bidSkewAtPlace, bidFilledLast);
            boolean askSticks = sticks(params, restingAsk, Side.SELL, window.fair(),
                    window.tsMs() - askPlacedMs, skewNow - askSkewAtPlace, askFilledLast);
            bidFilledLast = false;
            askFilledLast = false;

            // Замороженная пара: пока держим, заявки не трогаем ВООБЩЕ — ни ту, что
            // уехала, ни ту, что исполнилась. Держим до истечения паузы после
            // исполнения, а без исполнений — до предохранителя по возрасту (если он
            // включён) либо бесконечно, и это часть проверяемого правила.
            boolean frozenHold = false;
            if (params.frozen().enabled() && frozenArmed) {
                boolean aged = params.frozen().maxAgeMs() > 0
                        && window.tsMs() - frozenPlacedMs >= params.frozen().maxAgeMs();
                frozenHold = !aged && window.tsMs() < frozenRequoteAtMs;
            }
            if (frozenHold) {
                frozenHeldWindows++;
            }

            if (!frozenHold && !bidSticks && quoter.shouldRequote(restingBid, target.bid())) {
                execution.cancel(Side.BUY);
                restingBid = target.bid();
                if (restingBid != null) {
                    execution.place(Side.BUY, restingBid, params.sizeFor(Side.BUY, pnl.inventory()),
                            window.book(), window.tsMs());
                    bidPlacedMs = window.tsMs();
                    bidSkewAtPlace = skewNow;
                }
                requotes++;
            } else if (!frozenHold && bidSticks && restingBid != null && params.sticky().resetQueue()) {
                // Контроль M2: цена липкая, приоритет очереди сбрасывается.
                execution.cancel(Side.BUY);
                execution.place(Side.BUY, restingBid, params.sizeFor(Side.BUY, pnl.inventory()),
                        window.book(), window.tsMs());
            }
            if (!frozenHold && !askSticks && quoter.shouldRequote(restingAsk, target.ask())) {
                execution.cancel(Side.SELL);
                restingAsk = target.ask();
                if (restingAsk != null) {
                    // продать можно только то, что есть: спот, шортить нечем
                    double size = Math.min(params.sizeFor(Side.SELL, pnl.inventory()), pnl.inventory());
                    if (size > 0) {
                        execution.place(Side.SELL, restingAsk, size, window.book(), window.tsMs());
                        askPlacedMs = window.tsMs();
                        askSkewAtPlace = skewNow;
                    } else {
                        restingAsk = null;
                    }
                }
                requotes++;
            } else if (!frozenHold && askSticks && restingAsk != null && params.sticky().resetQueue()) {
                double size = Math.min(params.sizeFor(Side.SELL, pnl.inventory()), pnl.inventory());
                if (size > 0) {
                    execution.cancel(Side.SELL);
                    execution.place(Side.SELL, restingAsk, size, window.book(), window.tsMs());
                }
            }

            // Пара выставлена заново — замораживаем её до следующего исполнения.
            if (params.frozen().enabled() && !frozenHold) {
                if (frozenArmed) {
                    frozenCycles++;           // это был перевыпуск, а не первая выставка
                }
                frozenArmed = true;
                frozenPlacedMs = window.tsMs();
                frozenRequoteAtMs = Long.MAX_VALUE;
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
                // Политике, чьи цены зависят от собственных сделок, факт исполнения
                // нужен раньше, чем следующий вызов quotes.
                policy.onFill(fill);
            }
            if (!fills.isEmpty()) {
                // Замороженная пара размораживается СОБЫТИЕМ, а не расстоянием:
                // после первого исполнения ждём паузу и выставляем обе стороны
                // заново. min, а не max — отсчёт идёт от ПЕРВОГО исполнения, иначе
                // серия сделок подряд отодвигала бы перевыпуск бесконечно.
                if (params.frozen().enabled()) {
                    frozenRequoteAtMs = Math.min(frozenRequoteAtMs,
                            window.tsMs() + params.frozen().coolOffMs());
                }
                // исполненную сторону надо будет выставить заново
                for (Fill fill : fills) {
                    // Исполнение с любой стороны помечает обе: исполнившаяся
                    // выставляется заново по построению, а противоположную
                    // переставляет правило replaceOnFill — инвентарь изменился,
                    // и её положение больше не отражает нужный скос.
                    bidFilledLast = true;
                    askFilledLast = true;
                    if (fill.side() == Side.BUY && execution.remaining(Side.BUY) <= 0) {
                        restingBid = null;
                    }
                    if (fill.side() == Side.SELL && execution.remaining(Side.SELL) <= 0) {
                        restingAsk = null;
                    }
                }
            }

            fairPeak = Math.max(fairPeak, window.fair());
            maxInventory = Math.max(maxInventory, pnl.inventory());
            inventorySum += pnl.inventory();
            inventorySamples++;
            if (pnl.inventory() >= params.inventoryCap() - 1e-12) {
                atCap++;
                if (Double.isNaN(capAtDropPct) && fairPeak > 0) {
                    capAtDropPct = 100.0 * (window.fair() / fairPeak - 1);
                }
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
                maxInventory, avgInventory, atCap, atZero,
                gateOpenWindows, erSamples == 0 ? Double.NaN : erSum / erSamples,
                stopHits, stoppedWindows, filledQty, marketQty, maxDrawdown,
                fairFirst, fairLast, fairSeries, pnl.markAtStart(fairFirst), capAtDropPct,
                frozenCycles, frozenHeldWindows, execution.stats());
    }
}
