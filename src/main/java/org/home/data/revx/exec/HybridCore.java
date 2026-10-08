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
        if (clampBp > 0) {
            double x = Math.log(level / bnb) * 1e4;
            if (Double.isNaN(basisBp)) {
                basisBp = x;
            } else {
                basisBp += (1 - Math.exp(-(tsMs - prev) / basisTauMs)) * (x - basisBp);
            }
            double dev = Math.log(fair / bnb) * 1e4 - basisBp;
            steps++;
            if (Math.abs(dev) > clampBp) {
                clamped++;
                fair = bnb * Math.exp((basisBp + Math.signum(dev) * clampBp) / 1e4);
            }
        }
        prev = tsMs;
        boolean ok = spreadBp <= maxSpreadBp;
        boolean oldOk = !noOld && oldQuotable;
        return new Out(fair, ok || oldOk, ok && !oldOk, spreadBp);
    }
}
