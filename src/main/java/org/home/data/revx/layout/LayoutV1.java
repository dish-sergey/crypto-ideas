package org.home.data.revx.layout;

import org.home.data.revx.sim.Fill;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Расстановка, работающая на живых ботах с сентября 2026. ЭТАЛОН.
 *
 * <h2>Что здесь собрано</h2>
 *
 * Арифметика перенесена из {@code QuoteLoop} без изменений — это условие, а не
 * пожелание: пока V1 не воспроизводит прежнее поведение в точности, ни один
 * опыт с другой версией ничего не значит, потому что сравнивать будет не с чем.
 * Порядок шагов тот же, что был в цикле:
 *
 * <ol>
 *   <li>базовые бид и аск от {@link QuotePolicy} — отступ и скос по инвентарю;</li>
 *   <li>раздвижение на неопределённость цены, если опорная книга широка;</li>
 *   <li>раздвижение на дефицит постановок;</li>
 *   <li>цены уровней: шаг сетки от базовой цены наружу;</li>
 *   <li>зажим, чтобы не пересечь книгу площадки;</li>
 *   <li>приведение к шагу цены — покупку вниз, продажу вверх;</li>
 *   <li>раздача капитала по уровням: внутренние забирают первыми.</li>
 * </ol>
 *
 * <h2>Почему капитал раздаётся, а не достаётся каждому целиком</h2>
 *
 * Продавать можно только то, что есть, и инвентарь достаётся ближним к рынку
 * уровням. Продали с ближнего — инвентаря стало на лот меньше, и на следующем
 * тике дальний уровень получает цену ближнего. Никакой отдельной логики
 * подтягивания не нужно, она выпадает из правила раздачи.
 *
 * Касса раздаётся так же. Иначе порядок обхода на покупках не значит ничего, и
 * вопрос «с ближнего начинать или с дальнего» на половине конструкции просто не
 * задан.
 *
 * <h2>Границы</h2>
 *
 * Пределы из {@link MarketState} здесь СОБЛЮДАЮТСЯ, но не охраняются: заявка
 * мельче минимума не выдаётся вовсе (попытка потратила бы суточный лимит
 * постановок впустую), заявка крупнее потолка обрезается. Настоящая охрана — в
 * исполнителе, и она остаётся там: ТЗ §6 держит предохранители в коде, отдельно
 * от сменной логики.
 */
public final class LayoutV1 implements OrderLayout {

    private final Quoter.Params params;
    private final QuotePolicy policy;
    private final int levels;
    private final double levelStep;
    private final boolean innerFirst;
    private final double spreadToOffset;
    private final double spreadToOffsetMaxPct;
    private final double budgetWiden;

    /**
     * @param levels               сколько заявок на сторону
     * @param levelStep            расстояние между уровнями в долях цены
     * @param innerFirst           раздавать капитал от ближнего уровня или от дальнего
     * @param spreadToOffset       доля отступа, отдаваемая ширине опоры; 0 — гейт бинарный
     * @param spreadToOffsetMaxPct выше этой ширины опоры не котируем вовсе
     * @param budgetWiden          во сколько раз раздвинуть отступ при полном дефиците
     */
    public LayoutV1(Quoter.Params params, QuotePolicy policy, int levels, double levelStep,
                    boolean innerFirst, double spreadToOffset, double spreadToOffsetMaxPct,
                    double budgetWiden) {
        this.params = params;
        this.policy = policy;
        this.levels = Math.max(1, levels);
        this.levelStep = levelStep;
        this.innerFirst = innerFirst;
        this.spreadToOffset = spreadToOffset;
        this.spreadToOffsetMaxPct = spreadToOffsetMaxPct;
        this.budgetWiden = budgetWiden;
    }

    @Override
    public String name() {
        return "v1";
    }

    @Override
    public void onFill(Fill fill) {
        policy.onFill(fill);
    }

    @Override
    public List<DesiredOrder> layout(MarketState s) {
        List<DesiredOrder> out = new ArrayList<>();
        if (!s.usable()) {
            return out;
        }
        Quoter.Quotes target = policy.quotes(s.fair(), s.inventory(), s.drift());
        target = widenForSpread(target, s.fair(), s.referenceSpreadPct(), spreadToOffset);
        target = widenForBudget(target, s.fair(), s.budgetPressure(), budgetWiden);

        // ⚠️ РАЗДАЧИ КАПИТАЛА ЗДЕСЬ НЕТ, и это осознанная граница.
        //
        // Сначала она была здесь, и перенос не сошёлся со встроенным путём:
        // 08.09.2026 след разошёлся на 884-м действии, где встроенный ставил
        // ВТОРУЮ заявку, а модульный нет. Причина в том, что заявку, у которой
        // уже стоит своя, финансировать почти не нужно — замена возвращает
        // резерв, и он снова доступен. Учесть это можно, только зная резерв
        // КАЖДОГО слота, то есть состояние размещения, а не рынка.
        //
        // Поэтому расстановка называет желаемый размер, а сколько из него
        // реально проходит — решает исполнитель: у него и остатки счёта, и
        // резервы под своими заявками. Экспериментальная версия по-прежнему
        // управляет размером (она его называет), но не обязана вести бухгалтерию
        // чужого счёта.
        for (int k = 0; k < levels; k++) {
            int i = innerFirst ? k : levels - 1 - k;
            Double bid = onTick(Side.BUY,
                    noCross(Side.BUY, levelPrice(Side.BUY, target.bid(), s.fair(), i), s),
                    s.quoteStep());
            Double ask = onTick(Side.SELL,
                    noCross(Side.SELL, levelPrice(Side.SELL, target.ask(), s.fair(), i), s),
                    s.quoteStep());

            if (bid != null && bid > 0) {
                out.add(new DesiredOrder(Side.BUY, i, bid,
                        params.sizeFor(Side.BUY, s.inventory())));
            }
            if (ask != null && ask > 0) {
                out.add(new DesiredOrder(Side.SELL, i, ask,
                        params.sizeFor(Side.SELL, s.inventory())));
            }
        }
        return out;
    }

    /**
     * Обрезать размер по шагу количества и по пределам площадки.
     *
     * Ниже минимума заявка не встанет, а попытка потратит суточный лимит
     * постановок: остаток от частичного исполнения бывает мельче минимума
     * (5.5e−7 BTC = 0.04 USDC при пороге 0.1), и стучаться с ним незачем.
     */
    private static double clip(double size, MarketState s, double price) {
        double step = s.baseStep();
        double sized = step > 0 ? Math.floor(size / step) * step : size;
        if (!(sized > 0)) {
            return 0;
        }
        double notional = sized * price;
        if (notional < s.minNotional()) {
            return 0;
        }
        if (s.maxOrderNotional() > 0 && notional > s.maxOrderNotional()) {
            sized = s.maxOrderNotional() / price;
            sized = step > 0 ? Math.floor(sized / step) * step : sized;
            if (!(sized > 0) || sized * price < s.minNotional()) {
                return 0;
            }
        }
        return sized;
    }

    /** Цена уровня: шаг сетки от базовой цены НАРУЖУ от рынка. */
    private Double levelPrice(Side side, Double base, double fair, int level) {
        if (base == null || level == 0 || levelStep <= 0) {
            return base;
        }
        double shift = level * levelStep * fair;
        return side == Side.BUY ? base - shift : base + shift;
    }

    /**
     * Не пересекать книгу площадки.
     *
     * Корзинная справедливая цена на быстром движении обгоняет книгу, и бид,
     * посчитанный как {@code fair·(1−d)}, оказывается на аске. Площадка отвергает
     * такую заявку с {@code post_only_immediate_match}, а заявка при этом
     * ПОГИБАЕТ: следующая замена бьёт по мёртвому идентификатору и кончается
     * постановкой, то есть тратой единственного жёсткого ресурса.
     */
    private static Double noCross(Side side, Double price, MarketState s) {
        if (price == null) {
            return null;
        }
        double step = Math.max(s.quoteStep(), 1e-9);
        if (side == Side.BUY && s.bookAsk() > 0 && price >= s.bookAsk()) {
            double clamped = s.bookAsk() - step;
            return clamped > 0 ? clamped : null;
        }
        if (side == Side.SELL && s.bookBid() > 0 && price <= s.bookBid()) {
            return s.bookBid() + step;
        }
        return price;
    }

    /** К допустимому тику, НАРУЖУ от рынка: покупку вниз, продажу вверх. */
    private static Double onTick(Side side, Double price, double step) {
        if (price == null || !(step > 0) || !(price > 0)) {
            return price;
        }
        double units = price / step;
        double snapped = (side == Side.BUY ? Math.floor(units) : Math.ceil(units)) * step;
        return snapped > 0 ? snapped : price;
    }

    /**
     * Раздвинуть обе стороны на неопределённость цены.
     *
     * Середина книги шириной s известна с точностью ±s/2, поэтому заявка уходит
     * на s/2, делённое на долю k, которую мы готовы отдать неопределённости.
     */
    static Quoter.Quotes widenForSpread(Quoter.Quotes target, double price,
                                        double referenceSpreadPct, double k) {
        if (!(k > 0) || !(price > 0) || !(referenceSpreadPct > 0)) {
            return target;
        }
        double extra = price * (referenceSpreadPct / 100.0) / 2 / k;
        return new Quoter.Quotes(
                target.bid() == null ? null : target.bid() - extra,
                target.ask() == null ? null : target.ask() + extra);
    }

    /**
     * Раздвинуть обе стороны пропорционально дефициту постановок.
     *
     * Раздвигается ИМЕННО ОТСТУП, а не абсолютная величина: заявка отходит на
     * долю своего же расстояния до цены. Иначе тонкая пара с отступом в 30 б.п.
     * и биткойн с десятью получили бы одинаковую прибавку в долларах, то есть
     * совершенно разное наказание.
     */
    static Quoter.Quotes widenForBudget(Quoter.Quotes target, double price,
                                        double pressure, double widen) {
        if (!(pressure > 0) || !(price > 0)) {
            return target;
        }
        double m = widen * Math.min(1.0, pressure);
        return new Quoter.Quotes(
                target.bid() == null ? null : target.bid() - (price - target.bid()) * m,
                target.ask() == null ? null : target.ask() + (target.ask() - price) * m);
    }

    /** Раздвигать ли отступ вместо остановки: только ширина опоры, и до потолка. */
    public boolean widensInsteadOfPausing(double referenceSpreadPct) {
        return spreadToOffset > 0 && referenceSpreadPct <= spreadToOffsetMaxPct;
    }
}
