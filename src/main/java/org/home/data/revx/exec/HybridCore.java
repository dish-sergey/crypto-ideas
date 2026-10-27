package org.home.data.revx.exec;

import org.home.data.revx.sim.BookView;

import java.util.List;

/**
 * ОПОРА «СМЕСЬ + ГЛУБИНА + БИНАНС» — один расчёт для стенда и живого бота
 * (06.10.2026). Стенд ({@code replay.HybridFair}) и живой
 * ({@link LiveHybridFair}) обязаны считать одно и то же, иначе сверка живого с
 * симуляцией сравнивала бы две разные опоры.
 *
 * <pre>
 *   уровень = w · прежняя опора + (1 − w) · середина своей книги по глубине k лотов
 *   опора   = EMA_τ(уровень) · B / EMA_τ(B),   B — цена Бинанса
 * </pre>
 * Середина по глубине — между ценами, до которых на каждой стороне набирается
 * k лотов; не хватило глубины — середина верхушки. Котировать можно, если своя
 * книга не шире {@code maxSpreadBp}, либо если это разрешает прежняя опора.
 *
 * Лучшее на стенде x10 (08.09–02.10, реальные затыки): w = 0.25, k = 10, τ = 180 с —
 * сумма +41% к базе, плюс и на росте, и на падении.
 */
public final class HybridCore {

    /** {@code spreadBp} — ширина своей книги по верху, б.п. (для честной причины паузы). */
    public record Out(double fair, boolean quotable, boolean ownGate, double spreadBp) {
    }

    private final double mix;
    private final double depthK;
    private final double tauMs;
    private final double maxSpreadBp;
    /**
     * ПОВОДОК К БИНАНСУ (06.10.2026, идея владельца): своя книга двигает опору не
     * дальше ±{@code clampBp} от «Бинанс × обычный базис», где базис — EMA за
     * {@code basisTauMs} от ln(уровень / Бинанс). Повод — ETH 06.10 18:15–18:19:
     * книга площадки застыла на +25…+29 б.п. к Бинансу при обычном базисе +8…+10,
     * опора ушла от базиса на +7.9 б.п., и обе покупки c и d легли на ~10 б.п.
     * выше рынка. 0 — поводка нет (как было до 06.10).
     */
    private final double clampBp;
    /**
     * ПОВОДОК ПО ВОЛАТИЛЬНОСТИ (16.10.2026, идея владельца): когда размах Бинанса за
     * {@code stormSec} не меньше {@code stormRangeBp}, поводок — {@code stormClampBp}
     * вместо {@code clampBp}. Повод — стенд w24: в тихом рынке свободный поводок (7 и
     * больше) даёт сделки лучше, в бурю (размах ≥ 80 б.п. за 5 мин) лучше тугой
     * (SOL +0.20 ¢/ч при 0.25 против −1.20 при 5). 0 — выключено.
     */
    private final double stormClampBp;
    private final double stormRangeBp;
    private final long stormMs;
    /** Монотонные очереди по секундным точкам Бинанса: {сек, цена}. */
    private final java.util.ArrayDeque<double[]> rangeMax = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<double[]> rangeMin = new java.util.ArrayDeque<>();
    private long rangeFirstSec = Long.MIN_VALUE;
    private long rangeLastSec = Long.MIN_VALUE;
    private long stormSteps;
    private final double basisTauMs;
    private double emaM = Double.NaN;
    private double emaB = Double.NaN;
    private double basisBp = Double.NaN;
    private long prev;
    private long depthUsed;
    private long depthShort;
    private long steps;
    private long clamped;
    private long noOldSteps;

    /** Опора последнего шага ДО поводка (своя книга без ограничения) и «Бинанс × обычный базис»; NaN — нет. */
    private double rawFair = Double.NaN;
    private double anchor = Double.NaN;

    /**
     * ДАЛЬНЯЯ ИЗ ДВУХ (идея владельца 26.10.2026): цены, от которых ставить бид и аск, если брать
     * дальнюю от рынка из «своей книги без поводка» и «Бинанса × базис»: {меньшая, большая}.
     * null — базиса ещё нет (он набирается, только когда задан поводок).
     */
    public double[] sideRefs() {
        if (!(rawFair > 0) || !(anchor > 0)) {
            return null;
        }
        return new double[]{Math.min(rawFair, anchor), Math.max(rawFair, anchor)};
    }

    /**
     * Обе опоры как есть: {своя книга без поводка, Бинанс × обычный базис} (27.10.2026, док. 216 — пишутся в
     * журнал, чтобы «дальнюю из двух» и поводок можно было разбирать по журналу). null — базиса ещё нет.
     */
    public double[] rawRefs() {
        if (!(rawFair > 0) || !(anchor > 0)) {
            return null;
        }
        return new double[]{rawFair, anchor};
    }

    /** Шагов без прежней опоры (уровень целиком по своей книге). */
    public long noOldSteps() {
        return noOldSteps;
    }

    public HybridCore(double mix, double depthK, double tauSec, double maxSpreadBp) {
        this.mix = mix;
        this.depthK = depthK;
        this.tauMs = tauSec * 1000;
        this.maxSpreadBp = maxSpreadBp;
        this.clampBp = Double.parseDouble(System.getProperty("revx.fair.hybrid-clamp-bp", "0"));
        this.basisTauMs = Double.parseDouble(System.getProperty("revx.fair.hybrid-basis-tau-sec", "1800")) * 1000;
        this.stormClampBp = Double.parseDouble(System.getProperty("revx.fair.hybrid-clamp-storm-bp", "0"));
        this.stormRangeBp = Double.parseDouble(System.getProperty("revx.fair.hybrid-clamp-storm-range-bp", "80"));
        this.stormMs = Long.getLong("revx.fair.hybrid-clamp-storm-sec", 300L) * 1000;
    }

    /** Шагов, на которых действовал поводок бури. */
    public long stormSteps() {
        return stormSteps;
    }

    public double stormClampBp() {
        return stormClampBp;
    }

    public double stormRangeBp() {
        return stormRangeBp;
    }

    /**
     * Размах Бинанса за {@link #stormMs}, б.п.; NaN — окно набрано меньше чем наполовину.
     * Одна точка в секунду (первая цена секунды), очереди монотонные — шаг O(1).
     */
    double bnbRangeBp(long tsMs, double bnb) {
        long sec = Math.floorDiv(tsMs, 1000);
        if (sec != rangeLastSec) {
            if (rangeLastSec != Long.MIN_VALUE && sec - rangeLastSec > stormMs / 1000) {
                rangeMax.clear();           // дыра длиннее окна — набираем заново
                rangeMin.clear();
                rangeFirstSec = Long.MIN_VALUE;
            }
            rangeLastSec = sec;
            if (rangeFirstSec == Long.MIN_VALUE) {
                rangeFirstSec = sec;
            }
            while (!rangeMax.isEmpty() && rangeMax.peekLast()[1] <= bnb) {
                rangeMax.pollLast();
            }
            rangeMax.addLast(new double[]{sec, bnb});
            while (!rangeMin.isEmpty() && rangeMin.peekLast()[1] >= bnb) {
                rangeMin.pollLast();
            }
            rangeMin.addLast(new double[]{sec, bnb});
        }
        long from = sec - stormMs / 1000;
        while (!rangeMax.isEmpty() && rangeMax.peekFirst()[0] <= from) {
            rangeMax.pollFirst();
        }
        while (!rangeMin.isEmpty() && rangeMin.peekFirst()[0] <= from) {
            rangeMin.pollFirst();
        }
        if (rangeMax.isEmpty() || sec - rangeFirstSec < stormMs / 2000) {
            return Double.NaN;
        }
        double hi = rangeMax.peekFirst()[1], lo = rangeMin.peekFirst()[1];
        return (hi - lo) / ((hi + lo) / 2) * 1e4;
    }

    /** Шагов с поводком (счётчик растёт, только если поводок задан). */
    public long steps() {
        return steps;
    }

    /** Из них опору удержал поводок. */
    public long clamped() {
        return clamped;
    }

    /** Текущий обычный базис уровня к Бинансу, б.п.; NaN — ещё не набран. */
    public double basisBp() {
        return basisBp;
    }

    /** Доля шагов, на которых поводок к Бинансу удержал опору. */
    public double clampedShare() {
        return steps == 0 ? 0 : (double) clamped / steps;
    }

    public double maxSpreadBp() {
        return maxSpreadBp;
    }

    public double clampBp() {
        return clampBp;
    }

    public long depthUsed() {
        return depthUsed;
    }

    public long depthShort() {
        return depthShort;
    }

    /** Цена, на которой накопленный объём стороны достигает {@code qty}; NaN — глубины не хватило. */
    public static double depthPrice(List<BookView.Level> side, double qty) {
        double acc = 0;
        for (BookView.Level l : side) {
            acc += l.qty();
            if (acc >= qty) {
                return l.price();
            }
        }
        return Double.NaN;
    }

    /**
     * АВАРИЙНЫЙ ШАГ БЕЗ БИНАНСА: только уровень (смесь прежней опоры и своей книги по
     * глубине), сглаженный тем же τ; множитель Бинанса не применяется. Сглаженное
     * состояние продолжается, чтобы при возврате Бинанса опора не прыгнула.
     * {@code null} — книги или прежней опоры нет.
     */
    public Double levelOnly(long tsMs, double oldFair, BookView book, double lotQty) {
        if (book == null || book.empty() || !(Double.isFinite(oldFair) && oldFair > 0)) {
            return null;
        }
        double bookMid = (book.bestBid() + book.bestAsk()) / 2;
        if (depthK > 0 && lotQty > 0) {
            double bd = depthPrice(book.bids(), depthK * lotQty);
            double ad = depthPrice(book.asks(), depthK * lotQty);
            if (Double.isFinite(bd) && Double.isFinite(ad)) {
                bookMid = (bd + ad) / 2;
            }
        }
        double level = mix * oldFair + (1 - mix) * bookMid;
        if (Double.isNaN(emaM) || tauMs <= 0) {
            emaM = level;
        } else {
            emaM += (1 - Math.exp(-(tsMs - prev) / tauMs)) * (level - emaM);
        }
        prev = tsMs;
        emaB = Double.NaN;               // Бинанс вернётся — его среднее начнётся заново
        return emaM;
    }

    /**
     * Один шаг. {@code null} — посчитать нельзя (нет прежней опоры, книги или цены
     * Бинанса), и вызывающий оставляет прежнюю опору как есть.
     */
    public Out step(long tsMs, double oldFair, boolean oldQuotable, BookView book, double lotQty,
                    double bnb) {
        if (book == null || book.empty() || !(bnb > 0)) {
            return null;
        }
        // 🔑 НЕТ ПРЕЖНЕЙ ОПОРЫ — НЕ СБОЙ (07.10.2026). Корзина пар пропадает, когда
        // курс USDC/USD ненадёжен: в ночь сквиза это 20 раз у шести ботов, и каждый
        // раз бот уходил в аварийный режим на две минуты, хотя своя книга и Бинанс
        // были в порядке. Тогда уровень — целиком своя книга (смесь 0; на стенде
        // x10 такая опора даёт 168 против 179 у смеси 0.25), а котировать можно по
        // гейту своей книги.
        boolean noOld = !(Double.isFinite(oldFair) && oldFair > 0);
        if (noOld) {
            noOldSteps++;
        }
        double topMid = (book.bestBid() + book.bestAsk()) / 2;
        double spreadBp = (book.bestAsk() - book.bestBid()) / topMid * 1e4;
        double bookMid = topMid;
        if (depthK > 0 && lotQty > 0) {
            double bd = depthPrice(book.bids(), depthK * lotQty);
            double ad = depthPrice(book.asks(), depthK * lotQty);
            if (Double.isFinite(bd) && Double.isFinite(ad)) {
                bookMid = (bd + ad) / 2;
                depthUsed++;
            } else {
                depthShort++;
            }
        }
        double level = noOld ? bookMid : mix * oldFair + (1 - mix) * bookMid;
        if (Double.isNaN(emaM) || tauMs <= 0) {
            emaM = level;
            emaB = bnb;
        } else if (Double.isNaN(emaB)) {
            // После аварийного режима: уровень продолжается, среднее Бинанса — заново.
            emaM += (1 - Math.exp(-(tsMs - prev) / tauMs)) * (level - emaM);
            emaB = bnb;
        } else {
            double a = 1 - Math.exp(-(tsMs - prev) / tauMs);
            emaM += a * (level - emaM);
            emaB += a * (bnb - emaB);
        }
        double fair = emaM * bnb / emaB;
        double clampNow = clampBp;
        if (stormClampBp > 0) {
            double r = bnbRangeBp(tsMs, bnb);
            if (r >= stormRangeBp) {
                clampNow = stormClampBp;
                stormSteps++;
            }
        }
        if (clampBp > 0 || stormClampBp > 0) {
            // Базис набирается всегда, когда поводок вообще возможен: поводку бури
            // при выключенном обычном нужен базис, накопленный в тихом рынке.
            double x = Math.log(level / bnb) * 1e4;
            if (Double.isNaN(basisBp)) {
                basisBp = x;
            } else {
                basisBp += (1 - Math.exp(-(tsMs - prev) / basisTauMs)) * (x - basisBp);
            }
        }
        rawFair = fair;
        anchor = Double.isNaN(basisBp) ? Double.NaN : bnb * Math.exp(basisBp / 1e4);
        if (clampNow > 0) {
            double dev = Math.log(fair / bnb) * 1e4 - basisBp;
            steps++;
            if (Math.abs(dev) > clampNow) {
                clamped++;
                fair = bnb * Math.exp((basisBp + Math.signum(dev) * clampNow) / 1e4);
            }
        }
        prev = tsMs;
        boolean ok = spreadBp <= maxSpreadBp;
        boolean oldOk = !noOld && oldQuotable;
        return new Out(fair, ok || oldOk, ok && !oldOk, spreadBp);
    }
}
