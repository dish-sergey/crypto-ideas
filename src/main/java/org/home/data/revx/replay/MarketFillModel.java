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
        /** Когда очередь была посчитана: от этого момента идёт убыль отменами. */
        long sinceMs;
    }

    /**
     * УБЫЛЬ ОЧЕРЕДИ ОТМЕНАМИ, доля в секунду.
     *
     * <h2>Почему без неё модель врала втрое</h2>
     *
     * Раньше очередь уменьшалась только сделками, и в комментарии стояло: «мы
     * чужих отмен не видим, а предполагать их выгодно нам, значит нельзя».
     * Осторожность оказалась чрезмерной. Сверка с живыми ботами за 17.7 часа
     * 07-08.09.2026: BTC 147 исполнений живьём против 42 у модели, ADA 124
     * против 25, ETH 32 против 14. Втрое меньше.
     *
     * Причина именно в очереди, а не в данных и не в видимости, и это
     * проверено, а не предположено:
     * <ul>
     *   <li>лента полна — 94-100% наших живых сделок находятся в собранных
     *       принтах (при сверке по времени ПРИНТА, а не по времени, когда бот
     *       узнал об исполнении);</li>
     *   <li>глубина ни при чём — 94-100% исполнений случились внутри пяти
     *       видимых уровней.</li>
     * </ul>
     *
     * <h2>Откуда число</h2>
     *
     * Измерено по 150 живым исполнениям: сколько объёма стояло перед нами на
     * момент постановки, сколько из него реально прошло по ленте до исполнения,
     * и за какое время. Разница ушла отменами. Медианы: BTC 0.119, ADA 0.172,
     * ETH 0.271 доли в секунду. То есть очередь тает вдвое за 3-6 секунд.
     *
     * Косвенно сходится с тем, что делаем мы сами: 14 000 замен в сутки против
     * 38 постановок. Каждая наша замена — исчезновение объёма для тех, кто стоит
     * за нами; если соседи ведут себя так же, очередь обязана таять быстро.
     *
     * ⚠️ <b>Оценка смещена вверх.</b> Мерилась только по заявкам, которые
     * ИСПОЛНИЛИСЬ, а исполнились те, у кого очередь растаяла быстрее. Поэтому
     * значение по умолчанию взято НИЖЕ измеренных медиан и подбирается сверкой
     * с живым числом сделок, а не берётся из этого замера напрямую.
     */
    private final double decayPerSecond;

    /**
     * Учитывать ли ПЕРЕХВАТ — исполнение по принту, прошедшему за нашей ценой.
     *
     * Ключ диагностический, {@code -Drevx.fill.intercept=false}. Нужен потому,
     * что перехват — единственное по-настоящему связывающее допущение модели.
     *
     * <h2>Что измерено ({@link FillCheck}, 09.09.2026)</h2>
     *
     * С выключенным перехватом точность 100% на всех парах — исполнение по нашей
     * цене всегда настоящее, — но полнота падает до 19-41%. С включённым полнота
     * 86-100% при точности 85-98%. Собственная точность перехвата:
     *
     * <pre>
     *   пара  даёт исполнений   из них верных
     *   ADA   149 из 191        98%
     *   BTC    98 из 141        94%
     *   ETH    40 из  56        83%
     *   SOL    24 из  37        79%
     * </pre>
     *
     * То есть допущение оправдано: оно даёт от четверти до трёх четвертей всех
     * исполнений модели и ошибается редко.
     *
     * ⚠️ Первый замер дал «точность перехвата 23-36% на SOL и ETH», и это была
     * ошибка ПОВЕРКИ, а не модели: сторона заявки выводилась из тела {@code PUT},
     * где её нет, и слоты продаж приходили покупками выше аска. Снято.
     */
    private final boolean interceptOn =
            Boolean.parseBoolean(System.getProperty("revx.fill.intercept", "true"));



    private final MarketData market;
    private final Map<String, Queue> queues = new HashMap<>();
    private long lastMs = Long.MIN_VALUE;
    /**
     * ОТСЕВ ПО ГЕЙТАМ: почему каждый принт НЕ стал нашим исполнением.
     *
     * <h2>Зачем</h2>
     *
     * Замер 10.09.2026 показал, что дело не в данных: лента полна (все 60 живых
     * исполнений бота A найдены в собранных принтах), а потолок достижимого на
     * окне — 56 принтов из 422 при заявке в 12 б.п. от справедливой цены. Стенд
     * брал 30. То есть половина теряется на СВОИХ гейтах, а какой именно из них
     * съедает — до этих счётчиков было неизвестно, и все шесть предыдущих
     * гипотез приходилось проверять снаружи, отдельными приборами.
     *
     * <h2>Как считается</h2>
     *
     * Ровно ОДНА метка на принт, а не на заявку: иначе один принт, дошедший до
     * трёх наших уровней, попадал бы в три графы сразу и сумма перестала бы
     * сходиться с числом принтов. Метка — по ЛУЧШЕМУ исходу: если хоть одна
     * заявка исполнилась, принт считается взятым; иначе берётся причина отказа
     * у ближайшей к рынку из тех, до кого он дошёл.
     */
    public record Gates(long prints, long noAggressor, long noOrderOnSide, long notReached,
                        long invisible, long queueBlocked, long slotSpent, long noVolumeLeft,
                        long taken, List<Double> missBp, List<Double> spentGapMs) {

        public Gates merge(Gates o) {
            List<Double> m = new ArrayList<>(missBp);
            m.addAll(o.missBp());
            List<Double> gaps = new ArrayList<>(spentGapMs);
            gaps.addAll(o.spentGapMs());
            return new Gates(prints + o.prints(), noAggressor + o.noAggressor(),
                    noOrderOnSide + o.noOrderOnSide(), notReached + o.notReached(),
                    invisible + o.invisible(), queueBlocked + o.queueBlocked(),
                    slotSpent + o.slotSpent(), noVolumeLeft + o.noVolumeLeft(),
                    taken + o.taken(), m, gaps);
        }

        public static Gates empty() {
            return new Gates(0, 0, 0, 0, 0, 0, 0, 0, 0, new ArrayList<>(), new ArrayList<>());
        }

        /** Сколько принтов ДОШЛО до нашей цены — потолок, который стенд мог бы взять. */
        public long reached() {
            return invisible + queueBlocked + slotSpent + noVolumeLeft + taken;
        }

        public String render() {
            if (prints == 0) {
                return "принтов не было";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(java.util.Locale.ROOT,
                    "отсев принтов: всего %d, агрессор неизвестен %d, нашей заявки на этой стороне нет %d, "
                            + "цена не дошла %d", prints, noAggressor, noOrderOnSide, notReached));
            if (!missBp.isEmpty()) {
                List<Double> s = new ArrayList<>(missBp);
                java.util.Collections.sort(s);
                sb.append(String.format(java.util.Locale.ROOT,
                        " (недолёт: медиана %.2f б.п., 10%% %.2f, 90%% %.2f)",
                        s.get(s.size() / 2), s.get(s.size() / 10), s.get(s.size() * 9 / 10)));
            }
            sb.append(String.format(java.util.Locale.ROOT,
                    "; ДОШЛО %d = невидима %d + очередь %d + слот уже выбран %d + объём кончился %d "
                            + "+ ВЗЯТО %d",
                    reached(), invisible, queueBlocked, slotSpent, noVolumeLeft, taken));
            if (!spentGapMs.isEmpty()) {
                List<Double> s = new ArrayList<>(spentGapMs);
                java.util.Collections.sort(s);
                long instant = s.stream().filter(v -> v <= 50).count();
                sb.append(String.format(java.util.Locale.ROOT,
                        " (слот был мёртв: медиана %.0f мс, 10%% %.0f, 90%% %.0f; в пределах 50 мс %d из %d)",
                        s.get(s.size() / 2), s.get(s.size() / 10), s.get(s.size() * 9 / 10),
                        instant, s.size()));
            }
            return sb.toString();
        }
    }

    private long gPrints, gNoAggressor, gNoOrder, gNotReached, gInvisible, gQueueBlocked;
    private long gSlotSpent, gNoVolume, gTaken;
    private final List<Double> gMissBp = new ArrayList<>();
    /**
     * СКОЛЬКО СЛОТ УЖЕ БЫЛ МЁРТВ, когда пришёл принт, в миллисекундах.
     *
     * ⚠️ Без этого числа графа «слот уже выбран» НЕЧИТАЕМА, и вывод из неё
     * получается противоположный правде. Разрыв в 1500 мс значит задержку
     * восстановления — живой бот с секундным тиком успел бы переставить и взял
     * бы этот принт. Разрыв в 0-50 мс значит совсем другое: одна рыночная
     * заявка размела книгу и распалась на несколько принтов подряд. Тогда наш
     * лот выбран первым же из них, и ЖИВОЙ БОТ ТОЖЕ не взял бы остальные —
     * чинить нечего, а «недостающие исполнения» существуют только на бумаге.
     */
    private final List<Double> gSpentGapMs = new ArrayList<>();
    private final Map<String, Long> lastFillMs = new HashMap<>();

    public Gates gates() {
        return new Gates(gPrints, gNoAggressor, gNoOrder, gNotReached,
                gInvisible, gQueueBlocked, gSlotSpent, gNoVolume, gTaken,
                new ArrayList<>(gMissBp), new ArrayList<>(gSpentGapMs));
    }

    private long interceptFills;
    private long queueFills;
    private long invisibleSkips;
    /** Цена нас достала, но очередь не выбрана: сколько раз и во сколько раз она была больше сделки. */
    private long queueBlocks;
    private final List<Double> queueBlockRatio = new ArrayList<>();

    public MarketFillModel(MarketData market) {
        this(market, Double.parseDouble(System.getProperty("revx.queue.decay", "0")));
    }

    /**
     * @param decayPerSecond доля очереди, уходящая отменами за секунду;
     *                       0 — прежнее поведение, очередь неподвижна
     */
    public MarketFillModel(MarketData market, double decayPerSecond) {
        this.market = market;
        this.decayPerSecond = Math.max(0, decayPerSecond);
    }


    /**
     * Сколько объёма стоит перед нашей ценой в ЭТОЙ книге.
     *
     * Всё, что по цене строго лучше нашей, плюс весь наш уровень: приоритет там
     * уже занят теми, кто встал раньше.
     *
     * @return −1, если книги нет и судить не по чему
     */
    /**
     * Видна ли заявка в ЭТОЙ книге: лучше лучшей цены или внутри пяти уровней.
     *
     * ⚠️ Вопрос обязан задаваться заново на каждом тике, по той же причине, что
     * и вопрос про очередь: заявка становится видимой не потому, что мы
     * что-то сделали, а потому что ЦЕНА ПРИХОДИТ К НЕЙ. Пока видимость
     * вычислялась один раз при постановке, заявка, поставленная в семи
     * базисных пунктах от рынка, оставалась «слепой» навсегда — включая тот
     * момент, когда рынок опускался и она становилась лучшим бидом.
     *
     * @return {@code null}, если книги нет и судить не по чему
     */
    private static Boolean visibleInBook(BookView book, boolean buy, double price) {
        if (book == null || book.empty()) {
            return null;
        }
        Side side = buy ? Side.BUY : Side.SELL;
        double best = buy ? book.bestBid() : book.bestAsk();
        double deepest = book.deepestVisible(side);
        boolean better = buy ? price > best : price < best;
        boolean inside = buy ? price >= deepest : price <= deepest;
        return better || inside;
    }

    /**
     * Сколько объёма стоит перед нашей ценой в ЭТОЙ книге.
     *
     * ⚠️ Своё из очереди НЕ вычитается, хотя стоило бы. Снимки книги сняты с
     * площадки, пока на ней работал живой бот, и его собственные заявки в них
     * есть: на 337 живых исполнениях медианная доля нашего объёма на уровне
     * нашей цены — 100% на всех шести парах, то есть уровень обычно наш
     * целиком, а модель принимает его за чужую очередь. Вычитание пробовали
     * 08.09.2026 и убрали: заявка стенда стоит на своей округлённой цене, а не
     * на цене живого двойника, поэтому вычитать нечего — эффект оказался
     * нулевым (BTC 90→90, ADA 58→58). Правило, ослабляющее осторожность модели
     * без выигрыша, не оставляем.
     */
    private Queue q(Resting r) {
        return queues.get(r.id());
    }

    private static double aheadInBook(BookView book, boolean buy, double price) {
        if (book == null || book.empty()) {
            return -1;
        }
        double ahead = 0;
        for (BookView.Level l : buy ? book.bids() : book.asks()) {
            boolean strictlyBetter = buy ? l.price() > price : l.price() < price;
            boolean same = Math.abs(l.price() - price) < 1e-9;
            if (strictlyBetter || same) {
                ahead += l.qty();
            }
        }
        return ahead;
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

    /**
     * Сколько раз цена дошла до заявки, но очередь перед ней не была выбрана.
     *
     * Рядом с {@link #queueFills()} и {@link #interceptFills()} это отвечает на
     * единственный вопрос, который при расхождении с живым и надо задавать:
     * модель не даёт сделку потому, что цена до нас не дошла, или потому, что
     * дошла, а мы посчитали очередь непроходимой?
     */
    public String queueBlockStats() {
        if (queueBlocks == 0) {
            return "очередь не блокировала ни разу";
        }
        List<Double> sorted = new ArrayList<>(queueBlockRatio);
        java.util.Collections.sort(sorted);
        return String.format(java.util.Locale.ROOT,
                "очередь заблокировала %d раз, медиана «очередь / сделка» = %.1f",
                queueBlocks, sorted.get(sorted.size() / 2));
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
        q.sinceMs = order.placedMs();
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
            gPrints++;
            if (t.aggressor() == null) {
                gNoAggressor++;
                continue;              // агрессор неизвестен — исполнения нет
            }
            boolean hitsBuys = t.aggressor() == Side.SELL;
            // ⚠️ ОДНА МЕТКА НА ПРИНТ. Заявок на стороне может быть несколько
            // (уровни), и без этого один принт попадал бы в несколько граф сразу.
            int onSide = 0;
            double nearestMissBp = Double.MAX_VALUE;
            boolean spent = false;          // заявка дошла, но её уже выбрал предыдущий принт
            double spentGap = Double.MAX_VALUE;
            String verdict = null;
            List<Resting> queueAtTrade = new ArrayList<>();
            for (Resting r : resting) {
                if (r.buy() != hitsBuys) {
                    continue;
                }
                onSide++;
                boolean reached = r.buy() ? t.price() <= r.price() : t.price() >= r.price();
                if (reached && left.getOrDefault(r.id(), 0.0) > 1e-15) {
                    queueAtTrade.add(r);
                    continue;
                }
                if (reached) {
                    spent = true;
                    Long fill = lastFillMs.get(r.id());
                    if (fill != null) {
                        spentGap = Math.min(spentGap, (double) (t.tsMs() - fill));
                    }
                }
                // ⚠️ СДЕЛКА ПО ЦЕНЕ ЛУЧШЕ НАШЕЙ ВЫБИРАЕТ НАШУ ОЧЕРЕДЬ.
                //
                // До 08.09.2026 модель уменьшала очередь только сделками,
                // которые до нас ДОШЛИ, — то есть теми, что стоят на нашей цене
                // или за ней. Но очередь перед нашим бидом состоит именно из
                // заявок ПО ЛУЧШЕЙ цене, и съедают её ровно те сделки, которые
                // до нас не дошли. Получалось, что модель ждала, пока очередь
                // выберет тот, кто до неё не дотягивается.
                //
                // Отсюда и вздорные числа диагностики: там, где модель
                // отказывала в сделке, очередь была в среднем в 300-700 раз
                // больше самой сделки (BTC 306, ADA 736, ETH 212) — она просто
                // никогда не уменьшалась. Живьём те же заявки исполнялись.
                if (!reached) {
                    // насколько принт НЕ ДОЛЕТЕЛ до этой заявки, в базисных пунктах
                    double miss = Math.abs(t.price() - r.price()) / Math.abs(r.price()) * 1e4;
                    nearestMissBp = Math.min(nearestMissBp, miss);
                }
                if (!reached && q(r) != null) {
                    Queue qq = q(r);
                    if (qq.ahead > 0) {
                        qq.ahead = Math.max(0, qq.ahead - t.qty());
                    }
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
                    if (verdict == null) {
                        verdict = "volume";
                    }
                    break;
                }
                Queue q = queues.get(r.id());
                if (q == null) {
                    continue;
                }
                BookView bookNow = market.bookAt(t.tsMs());
                Boolean visibleNow = visibleInBook(bookNow, r.buy(), r.price());
                if (!(visibleNow == null ? q.visible : visibleNow)) {
                    invisibleSkips++;
                    if (verdict == null) {
                        verdict = "invisible";
                    }
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
                // ⚠️ ОЧЕРЕДЬ ПЕРЕСЧИТЫВАЕТСЯ ПО ТЕКУЩЕЙ КНИГЕ, а не хранится с
                // момента постановки. Это главная поправка модели, найденная
                // сверкой с живыми ботами 08.09.2026.
                //
                // Очередь перед лимитной заявкой исчезает НЕ от торговли и не от
                // отмен, а потому что ЦЕНА ПРИХОДИТ К НАМ: рынок опускается, наш
                // бид становится лучшим, и всё, что стояло выше, оказывается уже
                // не впереди нас, а на другой стороне.
                //
                // Измерено на 295 живых исполнениях — объём перед нами при
                // постановке против объёма в момент исполнения:
                //   ADA 3385 → 9.2 (в 368 раз), ETH 0.0104 → 0.0004 (в 26 раз).
                // Прежняя модель держала первое число и не выбирала очередь
                // НИКОГДА: диагностика дала «очередью 0, перехватом 77». То есть
                // весь механизм исполнения держался на одном допущении о
                // перехвате, а очередная механика была фактически выключена.
                //
                // Берём МИНИМУМ, а не подстановку: приоритет на своём уровне мы
                // при перестановке действительно теряем, и обнулять очередь
                // нельзя. Но и держать 3385, когда впереди 9, — неправда.
                //
                // ⚠️ И ещё одно, что следует из самого принта, а не из
                // допущения. Раз сделка прошла ПО НАШЕЙ ЦЕНЕ ИЛИ ЗА НЕЙ, значит
                // агрессор дошёл до нашего уровня, а всё, что стояло по цене
                // лучше, к этому моменту уже выбрано или снято — иначе он бы
                // туда и не добрался. Значит впереди нас осталось не больше
                // того, что стоит на НАШЕМ уровне, и накопленную сумму по
                // верхним уровням держать нельзя.
                //
                // Без этого правила модель отказывала в сделке, требуя выбрать
                // очередь, которую только что выбрал сам принт: в отказах
                // очередь была больше сделки в 306 раз на BTC, 736 на ADA и 212
                // на ETH (замер 08.09.2026).
                double aheadNow = bookNow == null || bookNow.empty() ? -1
                        : bookNow.qtyAt(r.buy() ? Side.BUY : Side.SELL, r.price(),
                                Math.abs(r.price()) * 1e-9);
                if (aheadNow >= 0) {
                    q.ahead = Math.min(q.ahead, aheadNow);
                }
                // Убыль отменами — поправка ВТОРОГО порядка. Измерена (BTC 0.119,
                // ADA 0.172, ETH 0.271 доли в секунду), но калибровка дала нулевой
                // отклик: она уменьшала величину, которая и так не доходила до
                // нуля. Оставлена ради полноты, по умолчанию выключена.
                if (decayPerSecond > 0 && q.ahead > 0 && q.sinceMs > 0) {
                    double secs = Math.max(0, (t.tsMs() - q.sinceMs) / 1000.0);
                    q.ahead *= Math.exp(-decayPerSecond * secs);
                    q.sinceMs = t.tsMs();
                }
                if (q.ahead > 0) {
                    double before = q.ahead;
                    q.ahead -= t.qty();
                    if (q.ahead > 0) {
                        queueBlocks++;
                        if (queueBlockRatio.size() < 100_000) {
                            queueBlockRatio.add(before / Math.max(1e-15, t.qty()));
                        }
                        if (verdict == null) {
                            verdict = "queue";
                        }
                        continue;      // до нас очередь ещё не дошла
                    }
                    q.ahead = 0;
                }
                // А вот САМ объём сделки на всех наших заявках общий: делить его
                // надо. Это единственная настоящая конкуренция, и она невелика —
                // 27% сделок мельче двух лотов, остальным места хватает обоим.
                if (volume <= 1e-15) {
                    if (verdict == null) {
                        verdict = "volume";
                    }
                    break;
                }
                double qty = Math.min(left.get(r.id()), volume);
                left.merge(r.id(), -qty, Double::sum);
                volume -= qty;
                boolean intercept = r.buy() ? t.price() < r.price() : t.price() > r.price();
                if (intercept && !interceptOn) {
                    continue;              // диагностический ключ, см. interceptOn
                }
                if (intercept) {
                    interceptFills++;
                } else {
                    queueFills++;
                }
                verdict = "taken";
                lastFillMs.put(r.id(), t.tsMs());
                out.add(new Filled(r.id(), qty, r.price()));
            }
            // ⚠️ Метка ставится ОДИН раз на принт, по лучшему исходу: «взято»
            // бьёт любой отказ, иначе взятый принт попал бы ещё и в отсев.
            if (onSide == 0) {
                gNoOrder++;
            } else if (queueAtTrade.isEmpty() && spent) {
                // ⚠️ ОТДЕЛЬНАЯ ГРАФА, и это не педантизм. «Слот уже выбран» значит,
                // что принт пришёл на нашу цену, пока заявка мертва: её взял
                // предыдущий принт, а новую котировщик ещё не поставил. Это
                // задержка ВОССТАНОВЛЕНИЯ, лечится частотой перевыставления и
                // числом уровней. «Объём кончился» — совсем другое: принт
                // разошёлся по соседним нашим заявкам, и лечить нечего.
                gSlotSpent++;
                if (spentGap < Double.MAX_VALUE && gSpentGapMs.size() < 500_000) {
                    gSpentGapMs.add(spentGap);
                }
            } else if (queueAtTrade.isEmpty()) {
                gNotReached++;
                if (nearestMissBp < Double.MAX_VALUE && gMissBp.size() < 500_000) {
                    gMissBp.add(nearestMissBp);
                }
            } else if ("taken".equals(verdict)) {
                gTaken++;
            } else if ("queue".equals(verdict)) {
                gQueueBlocked++;
            } else if ("invisible".equals(verdict)) {
                gInvisible++;
            } else {
                gNoVolume++;
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "очередь по видимой книге (рабочая)";
    }
}
