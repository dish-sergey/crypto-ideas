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
        private final List<Double> queueAtFill = new ArrayList<>();

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
        Resting bidCopy = bid == null ? null : bid.copy();
        Resting askCopy = ask == null ? null : ask.copy();
        List<Fill> bidFills = simulate(bidCopy, trades, fairAtWindowEnd);
        List<Fill> askFills = simulate(askCopy, trades, fairAtWindowEnd);

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
            fills.add(new Fill(trade.tsMs(), order.side, order.price, filled, fairAtWindowEnd));
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
