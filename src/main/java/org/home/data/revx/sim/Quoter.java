package org.home.data.revx.sim;

/**
 * Логика котирования (ТЗ §4.2). Все параметры — из конфига.
 *
 * Скос двигает ОБЕ котировки в одну сторону: при полном инвентаре бид уходит
 * вниз и перестаёт исполняться, а аск придвигается к справедливой цене и
 * разгружает позицию.
 *
 * Жёсткие правила: при полном инвентаре бида нет вовсе; при нулевом инвентаре
 * нет аска — спот, шортить нечем, и это физика, а не настройка.
 */
public final class Quoter implements QuotePolicy {

    public record Params(
            double offset,            // d — отступ от справедливой цены, доля
            double size,              // номинал заявки в базовой валюте
            double inventoryCap,      // потолок инвентаря в базовой валюте
            double skewK,             // коэффициент скоса
            double skewTarget,        // ЦЕЛЬ скоса, доля потолка (0 = пустой инвентарь)
            double driftBeta,         // вес дрейфа в скосе (0 = выключено)
            double buySizeRatio,      // доля лота на ПОКУПКУ (1 = симметрично)
            long driftWindowMs,       // окно измерения дрейфа
            double sizeShapeEta,      // непрерывное шейпирование размера покупки
            double driftGateEr,       // порог ER, ниже которого дрейф-скос выключен (0 = гейта нет)
            long erWindowMs,          // окно измерения ER
            long erSampleMs,          // шаг прореживания ряда для ER
            double stopDrawdownPct,   // стоп по просадке, % от номинала потолка (0 = выкл.)
            Sticky sticky,            // липкая котировка (док. 109 §II)
            long stopCoolOffMs,       // сколько не набирать после срабатывания
            double requoteThreshold,  // порог перевыставления, доля цены
            double quoteStep) {       // шаг цены пары

        /** Совместимость: цель — пустой инвентарь, дрейф выключен, набор симметричен. */
        public Params(double offset, double size, double inventoryCap, double skewK,
                      double requoteThreshold, double quoteStep) {
            this(offset, size, inventoryCap, skewK, 0.0, 0.0, 1.0, 0L, 0.0, 0.0, 0L, 0L,
                    0.0, Sticky.OFF, 0L, requoteThreshold, quoteStep);
        }

        public Params(double offset, double size, double inventoryCap, double skewK,
                      double skewTarget, double requoteThreshold, double quoteStep) {
            this(offset, size, inventoryCap, skewK, skewTarget, 0.0, 1.0, 0L, 0.0, 0.0, 0L, 0L,
                    0.0, Sticky.OFF, 0L, requoteThreshold, quoteStep);
        }

        // Точечные изменения параметров живут ЗДЕСЬ, а не в лестницах стенда.
        // Иначе каждое новое поле ломает полдюжины фабрик, разбросанных по
        // SimRunner, и добавляется оно с риском молча потерять соседнее: именно
        // так цель скоса однажды не доехала до лестницы отступа.

        public Params withOffset(double v) {
            return new Params(v, size, inventoryCap, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        /** Потолок вместе с ПРОПОРЦИОНАЛЬНЫМ лотом — см. док. 97 §1. */
        public Params withCap(double v) {
            double factor = inventoryCap > 0 ? v / inventoryCap : 1;
            return new Params(offset, size * factor, v, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withSkewK(double v) {
            return new Params(offset, size, inventoryCap, v, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withSkewTarget(double v) {
            return new Params(offset, size, inventoryCap, skewK, v, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withDriftBeta(double v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, v,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withBuyRatio(double v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, driftBeta,
                    v, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withShapeEta(double v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, v, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        /** Стоп по просадке: порог в долях номинала потолка (док. 107 §5). */
        public Params withSticky(Sticky v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, stopDrawdownPct, v, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withStopDrawdownPct(double v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, driftGateEr, erWindowMs,
                    erSampleMs, v, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        public Params withDriftGateEr(double v) {
            return new Params(offset, size, inventoryCap, skewK, skewTarget, driftBeta,
                    buySizeRatio, driftWindowMs, sizeShapeEta, v, erWindowMs,
                    erSampleMs, stopDrawdownPct, sticky, stopCoolOffMs, requoteThreshold, quoteStep);
        }

        /**
         * Размер заявки стороны: покупаем медленнее, разгружаемся свободно.
         *
         * Ступенька {@code buySizeRatio} — грубая версия; при {@code sizeShapeEta > 0}
         * работает непрерывная, {@code размер = лот · e^{−η·инвентарь/потолок}}.
         * Она не имеет порога вовсе, и это причина её предпочесть: асимметричный
         * набор в одиночку оказался неустойчив — знак альфы на падении менялся
         * между ×0.5 и ×0.25 (док. 99 §4), а чувствительность к порогу обычно и
         * означает, что дело в самом пороге (док. 101 §3.2).
         */
        public double sizeFor(Side side, double inventory) {
            if (side != Side.BUY) {
                return size;
            }
            double shaped = size * buySizeRatio;
            if (sizeShapeEta > 0 && inventoryCap > 0) {
                double filled = Math.max(0, Math.min(1, inventory / inventoryCap));
                shaped *= Math.exp(-sizeShapeEta * filled);
            }
            return shaped;
        }
    }

    /**
     * Липкая котировка: не снимать заявку на каждом тике (задание Z8).
     *
     * Гипотеза задания — значительная часть пошлины создаётся САМИМ
     * переставлением, а не потоком. Три механизма работают одновременно и
     * РАЗНОГО ЗНАКА, поэтому параметры разведены так, чтобы каждый изолировался
     * отдельной лестницей:
     *
     * <ul>
     *   <li>M1, за липкость: пошлина платится один раз на вход, а не на каждый
     *       повторный — изолируется лестницей { outerMult};</li>
     *   <li>M2, за липкость: приоритет очереди. Липкая заявка стоит впереди всех,
     *       кто пришёл позже; переставленная каждую секунду уходит в конец —
     *       изолируется флагом { resetQueue};</li>
     *   <li>M3, ПРОТИВ липкости: опасный снос. У липкой заявки отступ не
     *       фиксирован, и при движении цены бид может оказаться НАД справедливой,
     *       то есть исполниться по худшей возможной цене — изолируется лестницей
     *       { innerMult}.</li>
     * </ul>
     *
     *  outerMult   переставить, если отступ вырос больше { outerMult × d}
     *  innerMult   переставить, если отступ упал ниже { innerMult × d};
     *                    отрицательное значение = терпеть заявку по ту сторону
     *                    справедливой цены
     *  maxAgeMs    жёсткий таймаут заявки
     *  resetQueue  сбрасывать приоритет очереди каждый тик при липкой цене —
     *                    контроль, изолирующий M2
     *  skewDelta   переставить, если скос изменился больше чем на столько
     */
    public record Sticky(boolean enabled, double outerMult, double innerMult,
                         long maxAgeMs, boolean replaceOnFill, double skewDelta,
                         boolean resetQueue) {

        /** Выключено: поведение в точности прежнее. */
        public static final Sticky OFF =
                new Sticky(false, 1, 1, 0, true, Double.POSITIVE_INFINITY, false);

        public Sticky withOuter(double v) {
            return new Sticky(true, v, innerMult, maxAgeMs, replaceOnFill, skewDelta, resetQueue);
        }

        public Sticky withInner(double v) {
            return new Sticky(true, outerMult, v, maxAgeMs, replaceOnFill, skewDelta, resetQueue);
        }

        public Sticky withResetQueue(boolean v) {
            return new Sticky(enabled, outerMult, innerMult, maxAgeMs, replaceOnFill,
                    skewDelta, v);
        }
    }

    /** null в цене = сторона не котируется. */
    public record Quotes(Double bid, Double ask) {

        public boolean hasBid() {
            return bid != null;
        }

        public boolean hasAsk() {
            return ask != null;
        }
    }

    private final Params params;

    public Quoter(Params params) {
        this.params = params;
    }

    @Override
    public Quotes quotes(double fair, double inventory, double drift) {
        if (!(fair > 0)) {
            return new Quotes(null, null);
        }
        double skew = skew(params, inventory, drift);

        Double bid = null;
        Double ask = null;
        if (inventory < params.inventoryCap()) {
            bid = round(fair * (1 - params.offset() - params.skewK() * skew), true);
        }
        if (inventory > 0) {
            ask = round(fair * (1 + params.offset() - params.skewK() * skew), false);
        }
        return new Quotes(bid, ask);
    }

    /**
     * Скос относительно ЦЕЛЕВОГО инвентаря, а не относительно пустого счёта.
     *
     * Скос вычитается из обеих цен, поэтому симметричны они ровно там, где он
     * равен нулю: эта точка и есть цель контроллера. При {@code skewTarget = 0}
     * целью оказывается пустой инвентарь — а на споте это угол, в котором аск
     * выставить нечем, и стратегия становится односторонней. Живой прогон провёл
     * там 58% времени (док. 93 §4).
     *
     * Нормировка на {@code max(target, 1−target)} держит скос в [−1, 1] при любой
     * цели, поэтому коэффициент {@code k} сохраняет смысл «сколько б.п. на краю».
     *
     * <h2>Слагаемое дрейфа</h2>
     *
     * {@code skew = инвентарь/потолок − β·дрейф}.
     *
     * Инвентарная часть буквально означает «набрали на падении — хотим вернуться
     * к нулю», то есть ставку на ВОЗВРАТ движения. А подтверждённый факт проекта,
     * измеренный дважды независимо (S3 и S7), гласит обратное: резкие движения
     * продолжаются. Инвентарная нога торгует против самого прочного эмпирического
     * результата, который у проекта есть, — отсюда систематичность её убытка
     * (док. 98 §2).
     *
     * Слагаемое дрейфа не предсказывает направление и не открывает позицию по
     * сигналу. Оно задаёт СКОРОСТЬ НАБОРА инвентаря — величину, которую всё равно
     * приходится чем-то задавать, и которая сейчас задана неявным допущением о
     * возврате. Это правило риска, а не прогноз.
     *
     * На падении ({@code дрейф < 0}) вклад положителен: обе котировки уезжают
     * вниз, бид перестаёт набирать в дешевеющий актив, аск разгружает охотнее.
     * На росте — наоборот, позиция держится дольше.
     */
    static double skew(Params params, double inventory, double drift) {
        double cap = params.inventoryCap();
        if (!(cap > 0)) {
            return 0;
        }
        double target = params.skewTarget();
        double span = Math.max(target, 1 - target);
        if (!(span > 0)) {
            return 0;
        }
        double skew = (inventory / cap - target) / span - params.driftBeta() * drift;
        return Math.max(-1, Math.min(1, skew));
    }

    /**
     * Вес дрейфа, выведенный из ГЕОМЕТРИИ стратегии, а не подобранный по данным.
     *
     * Насыщение наступает там, где рынок за окно дрейфа прошёл всю нашу ширину
     * котировки {@code 2d}: такое движение уже нельзя считать колебанием вокруг
     * справедливой цены. Отсюда {@code β = 1/(2d)}.
     *
     * Подбор β по результату превратил бы правило риска в подгонку и вернул бы
     * ту же болезнь, от которой проект закрыл детектор режима (док. 98 §3).
     */
    public static double betaFromGeometry(double offset) {
        return offset > 0 ? 1.0 / (2 * offset) : 0;
    }

    /**
     * Перевыставлять только при отклонении больше порога — иначе счётчик
     * перевыставлений уйдёт в тысячи и упрётся в лимит запросов (ТЗ §4.2).
     */
    public boolean shouldRequote(Double current, Double target) {
        if (current == null || target == null) {
            return current != null || target != null;
        }
        return Math.abs(target - current) / current > params.requoteThreshold();
    }

    /** Цены округляются по шагу пары: бид вниз, аск вверх — всегда в сторону от рынка. */
    private Double round(double price, boolean down) {
        double step = params.quoteStep();
        if (step <= 0) {
            return price;
        }
        double steps = price / step;
        return (down ? Math.floor(steps + 1e-9) : Math.ceil(steps - 1e-9)) * step;
    }
}
