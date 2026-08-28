package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель исполнения — самая важная часть стенда (ТЗ §4.3).
 *
 * Заявка исполняется НЕ по факту касания цены, а только когда через её цену
 * прошёл объём, достаточный чтобы выбрать очередь перед ней. Наивное допущение
 * «цена коснулась — меня исполнили» завышает результат в разы, и ТЗ §0 требует
 * при красивой эквити первым делом подозревать именно это.
 *
 * Где модель может трактовать ситуацию в свою пользу или против — выбирается
 * вариант ПРОТИВ нас (ТЗ §4.3):
 *  - очередь перед нами не уменьшается от чужих отмен: мы их не видим;
 *  - сделка с неопределённым агрессором исполнения не вызывает;
 *  - если в одном окне сработали бы обе стороны, исполняется только невыгодная;
 *  - заявка вне видимых уровней не исполняется никогда (ТЗ §4.6 п.7).
 */
public final class ExecutionModel {

    /** Параметры из конфигурации пары; никаких констант в коде (ТЗ §2). */
    public record Limits(double baseStep, double priceTolerance) {

        public static Limits of(double baseStep) {
            return new Limits(baseStep, 1e-9);
        }
    }

    /**
     * Диагностика самой модели (ТЗ §0). Лестница отступа монотонна — чем шире
     * котируем, тем лучше результат, — и первое подозрение обязано падать на
     * допущения об очереди. Отсюда три величины, которые нужно видеть по каждой
     * ступени: где стоит наша цена относительно книги, какая очередь была перед
     * нами в момент исполнения, и какая доля исполнений досталась заявкам,
     * улучшавшим книгу (то есть тем, у кого очередь по построению нулевая).
     *
     * Классификация:
     *  - {@code improving} — цена лучше лучшей в книге, встаём одни на новом уровне;
     *  - {@code joining} — цена совпала с существующим уровнем, встаём в конец очереди;
     *  - {@code invisible} — цена вне пяти видимых уровней, исполниться не можем.
     */
    public static final class Stats {
        private long improvingWindows;
        private long joiningWindows;
        private long invisibleWindows;
        private long improvingFills;
        private long joiningFills;
        private long interceptedFills;
        private final List<Double> queueAtFill = new ArrayList<>();
        private final List<Double> interceptDistanceBp = new ArrayList<>();

        public long improvingWindows() {
            return improvingWindows;
        }

        public long joiningWindows() {
            return joiningWindows;
        }

        public long invisibleWindows() {
            return invisibleWindows;
        }

        public long improvingFills() {
            return improvingFills;
        }

        public long joiningFills() {
            return joiningFills;
        }

        /** Очередь перед заявкой по последнему снимку до исполнения. */
        public List<Double> queueAtFill() {
            return List.copyOf(queueAtFill);
        }

        public double improvingShare() {
            long total = improvingWindows + joiningWindows + invisibleWindows;
            return total == 0 ? Double.NaN : (double) improvingWindows / total;
        }

        public double invisibleShare() {
            long total = improvingWindows + joiningWindows + invisibleWindows;
            return total == 0 ? Double.NaN : (double) invisibleWindows / total;
        }

        public double improvingFillShare() {
            long total = improvingFills + joiningFills;
            return total == 0 ? Double.NaN : (double) improvingFills / total;
        }

        /**
         * Доля исполнений, полученных ПЕРЕХВАТОМ: принт прошёл по цене хуже нашей
         * котировки, и модель считает, что в реальности он достался бы нам.
         *
         * Это и есть единственное реально связывающее допущение стенда. Очередь на
         * этой площадке вырождена (спред 21 б.п. при шаге 0.0013 б.п. — котировка
         * почти всегда создаёт свой уровень), а вот перехват работает всегда, когда
         * мы стоим внутри спреда. Экономически он защитим — продавец, отдавший по
         * дальнему биду, тем более отдал бы по нашему, — но проверить это можно
         * только живыми заявками.
         */
        public double interceptedFillShare() {
            long total = improvingFills + joiningFills;
            return total == 0 ? Double.NaN : (double) interceptedFills / total;
        }

        /** Насколько далеко приходилось «дотягиваться», в б.п. от нашей цены. */
        public List<Double> interceptDistanceBp() {
            return List.copyOf(interceptDistanceBp);
        }
    }

    /** Состояние стоящей заявки. */
    private static final class Resting {
        final Side side;
        final double price;
        final long placedAtMs;
        double remaining;
        double queueAhead;
        double queueAtSnapshot;
        boolean improving;
        boolean visible;
        /** Цены принтов, породивших исполнения в текущем окне — для учёта перехвата. */
        final List<Double> fillTradePrices = new ArrayList<>();

        Resting(Side side, double price, double qty, long placedAtMs) {
            this.side = side;
            this.price = price;
            this.remaining = qty;
            this.placedAtMs = placedAtMs;
        }

        Resting copy() {
            Resting c = new Resting(side, price, remaining, placedAtMs);
            c.queueAhead = queueAhead;
            c.queueAtSnapshot = queueAtSnapshot;
            c.improving = improving;
            c.visible = visible;
            return c;
        }
    }

    private final Limits limits;
    private final Stats stats = new Stats();
    private Resting bid;
    private Resting ask;
    private BookView lastBook;

    public ExecutionModel(Limits limits) {
        this.limits = limits;
    }

    public Stats stats() {
        return stats;
    }

    /**
     * Учесть текущее положение заявок относительно книги. Вызывается движком раз
     * на окно — отдельно от {@code refresh}, чтобы счётчик считал окна, а не
     * пересчёты видимости.
     */
    public void observe() {
        for (Resting order : new Resting[]{bid, ask}) {
            if (order == null) {
                continue;
            }
            if (!order.visible) {
                stats.invisibleWindows++;
            } else if (order.improving) {
                stats.improvingWindows++;
            } else {
                stats.joiningWindows++;
            }
        }
    }

    /**
     * Постановка заявки. Очередь перед нами определяется по видимой книге:
     * встаём в конец существующего уровня, либо создаём новый (очередь пуста),
     * либо оказываемся вне видимой части и тогда исполниться не можем.
     */
    public void place(Side side, double price, double qty, BookView book, long tsMs) {
        this.lastBook = book;
        Resting order = new Resting(side, price, qty, tsMs);
        applyVisibility(order, book);
        if (side == Side.BUY) {
            bid = order;
        } else {
            ask = order;
        }
    }

    public void cancel(Side side) {
        if (side == Side.BUY) {
            bid = null;
        } else {
            ask = null;
        }
    }

    public void cancelAll() {
        bid = null;
        ask = null;
    }

    /** Пересчёт видимости по новому снимку: заявка могла войти в видимую часть. */
    public void refresh(BookView book) {
        // Снимок запоминается: контекст книги в момент исполнения (кто стоит внутри
        // нас) — предмет задания Z9, и восстановить его задним числом нельзя.
        this.lastBook = book;
        if (bid != null) {
            applyVisibility(bid, book);
        }
        if (ask != null) {
            applyVisibility(ask, book);
        }
    }

    public boolean hasOrder(Side side) {
        return (side == Side.BUY ? bid : ask) != null;
    }

    public double remaining(Side side) {
        Resting order = side == Side.BUY ? bid : ask;
        return order == null ? 0 : order.remaining;
    }

    public double queueAhead(Side side) {
        Resting order = side == Side.BUY ? bid : ask;
        return order == null ? Double.NaN : order.queueAhead;
    }

    public boolean visible(Side side) {
        Resting order = side == Side.BUY ? bid : ask;
        return order != null && order.visible;
    }

    /**
     * Прогон окна сделок (одно окно = промежуток между снимками книги).
     *
     * Стороны считаются независимо, а потом применяется правило одновременного
     * срабатывания: если исполнились бы обе, остаётся только невыгодная нам.
     */
    public List<Fill> onWindow(List<MarketTrade> trades, double fairAtWindowEnd) {
        return onWindow(trades, fairAtWindowEnd, fairAtWindowEnd);
    }

    /**
     * @param fairAtQuote  справедливая цена начала окна — по ней принято решение
     *                     о котировке, и только она участвует в правиле
     *                     одновременного срабатывания
     * @param fairAtFill   первая справедливая цена, НАБЛЮДЁННАЯ ПОСЛЕ исполнения
     *                     (начало следующего окна). Захват считается против неё.
     *
     * Почему не против цены котирования. Заявка выставлена по {@code fair(t)} и
     * висит до {@code t + период опроса}; если за это время цена ушла против нас,
     * исполнение достанется нам по устаревшей цене — и это убыток. При базе
     * {@code fair(t)} захват тождественно равен отступу, отрицательных исполнений
     * не бывает вовсе, и модель не способна показать, что нас разобрали. У живой
     * площадки такие сделки составляют 23% оборота (док. 85 §1).
     *
     * Заглядывания вперёд в РЕШЕНИЯ это не вносит: котировка по-прежнему строится
     * по {@code fair(t)}. Следующая цена участвует только в ИЗМЕРЕНИИ результата —
     * ровно как markout, который тоже смотрит вперёд по построению.
     */
    public List<Fill> onWindow(List<MarketTrade> trades, double fairAtQuote, double fairAtFill) {
        Resting bidCopy = bid == null ? null : bid.copy();
        Resting askCopy = ask == null ? null : ask.copy();
        List<Fill> bidFills = simulate(bidCopy, trades, fairAtFill);
        List<Fill> askFills = simulate(askCopy, trades, fairAtFill);
        double fairAtWindowEnd = fairAtQuote;

        if (!bidFills.isEmpty() && !askFills.isEmpty()) {
            // Обе стороны в одном окне — данные дискретны, порядок сделок внутри окна
            // нам неизвестен. Оставляем ту сторону, которая хуже для нас; вторая
            // остаётся ровно в прежнем состоянии, включая непройденную очередь.
            if (value(bidFills, fairAtWindowEnd) <= value(askFills, fairAtWindowEnd)) {
                bid = commit(bidCopy);
                record(bidCopy, bidFills);
                return bidFills;
            }
            ask = commit(askCopy);
            record(askCopy, askFills);
            return askFills;
        }

        bid = commit(bidCopy);
        ask = commit(askCopy);
        record(bidCopy, bidFills);
        record(askCopy, askFills);
        List<Fill> fills = new ArrayList<>(bidFills.size() + askFills.size());
        fills.addAll(bidFills);
        fills.addAll(askFills);
        fills.sort(java.util.Comparator.comparingLong(Fill::tsMs));
        return fills;
    }

    /**
     * Статистика пишется ТОЛЬКО по стороне, чьи исполнения приняты. Считать её
     * внутри {@code simulate} нельзя: стороны прогоняются на копиях, и при
     * срабатывании обеих одна из них выбрасывается — её исполнения не состоялись.
     */
    private void record(Resting order, List<Fill> fills) {
        if (order == null || fills.isEmpty()) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            if (order.improving) {
                stats.improvingFills++;
            } else {
                stats.joiningFills++;
            }
            stats.queueAtFill.add(order.queueAtSnapshot);
            if (i < order.fillTradePrices.size()) {
                // Для покупки принт хуже нашей цены — это принт НИЖЕ неё, для продажи — выше.
                double tradePrice = order.fillTradePrices.get(i);
                double distance = order.side == Side.BUY
                        ? order.price - tradePrice
                        : tradePrice - order.price;
                if (distance > limits.priceTolerance()) {
                    stats.interceptedFills++;
                    stats.interceptDistanceBp.add(distance / order.price * 10_000);
                }
            }
        }
    }

    private Resting commit(Resting simulated) {
        if (simulated == null) {
            return null;
        }
        return simulated.remaining > 0 ? simulated : null;
    }

    /** Насколько исполнения выгодны: положительно = хорошо для нас. */
    private static double value(List<Fill> fills, double fairAtWindowEnd) {
        double sum = 0;
        for (Fill fill : fills) {
            sum += fill.side().sign() * (fairAtWindowEnd - fill.price()) * fill.qty();
        }
        return sum;
    }

    private List<Fill> simulate(Resting order, List<MarketTrade> trades, double fairAtWindowEnd) {
        List<Fill> fills = new ArrayList<>();
        if (order == null || !order.visible) {
            return fills;
        }
        // Список цен принтов обязан соответствовать ИМЕННО этому окну: record()
        // сопоставляет его с fills по индексу.
        order.fillTradePrices.clear();
        for (MarketTrade trade : trades) {
            if (order.remaining <= 0) {
                break;
            }
            if (trade.aggressor() == null) {
                continue;                     // сторона неизвестна — исполнения нет
            }
            if (!hits(order, trade)) {
                continue;
            }
            double volume = trade.qty();
            if (order.queueAhead > 0) {
                double consumed = Math.min(volume, order.queueAhead);
                order.queueAhead -= consumed;
                volume -= consumed;
            }
            if (volume <= 0) {
                continue;                     // объёма хватило только на очередь перед нами
            }
            double filled = roundDown(Math.min(volume, order.remaining));
            if (filled <= 0) {
                continue;                     // меньше шага лота — исполнения нет
            }
            order.remaining -= filled;
            fills.add(new Fill(trade.tsMs(), order.side, order.price, filled, fairAtWindowEnd,
                    Fill.context(lastBook, order.side, order.price)));
            order.fillTradePrices.add(trade.price());
        }
        return fills;
    }

    /**
     * Сделка «бьёт» по нашей заявке, если агрессор идёт против нашей стороны
     * и его цена дошла до нашей или глубже.
     */
    private static boolean hits(Resting order, MarketTrade trade) {
        return order.side == Side.BUY
                ? trade.aggressor() == Side.SELL && trade.price() <= order.price
                : trade.aggressor() == Side.BUY && trade.price() >= order.price;
    }

    private void applyVisibility(Resting order, BookView book) {
        if (book == null || book.empty()) {
            order.visible = false;
            return;
        }
        double best = order.side == Side.BUY ? book.bestBid() : book.bestAsk();
        double deepest = book.deepestVisible(order.side);
        boolean improves = order.side == Side.BUY
                ? order.price > best + limits.priceTolerance()
                : order.price < best - limits.priceTolerance();
        boolean insideVisible = order.side == Side.BUY
                ? order.price >= deepest - limits.priceTolerance()
                : order.price <= deepest + limits.priceTolerance();

        order.visible = improves || insideVisible;
        order.improving = improves;
        if (!order.visible) {
            order.queueAhead = Double.POSITIVE_INFINITY;
            return;
        }
        // встаём в конец существующего уровня; новый уровень = пустая очередь
        order.queueAhead = improves
                ? 0
                : book.qtyAt(order.side, order.price, limits.priceTolerance());
        // Очередь по последнему снимку до исполнения: внутри окна она вычитается
        // прошедшим объёмом, и к моменту исполнения исходное значение теряется.
        order.queueAtSnapshot = order.queueAhead;
    }

    private double roundDown(double qty) {
        double step = limits.baseStep();
        if (step <= 0) {
            return qty;
        }
        return Math.floor(qty / step + 1e-9) * step;
    }
}
