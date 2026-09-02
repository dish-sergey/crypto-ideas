package org.home.data.revx.sim;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Откуда берутся цены котировок. Интерфейс существует ради обязательного
 * контроля из ТЗ §4.7: «случайные котировки» — тот же темп присутствия в книге,
 * но цены выбираются случайно вокруг справедливой.
 *
 * Смысл контроля: он отвечает, есть ли ценность в ВЫБОРЕ цен или работает сам
 * факт стояния в книге. Стратегия, не превосходящая этот контроль, отклоняется.
 */
public interface QuotePolicy {

    /**
     * @param drift относительный дрейф справедливой цены за окно наблюдения;
     *              ноль означает «слагаемое дрейфа выключено»
     */
    Quoter.Quotes quotes(double fair, double inventory, double drift);

    /**
     * Исполнение состоялось. Политике это нужно, только если её цены зависят от
     * СОБСТВЕННОЙ истории сделок, а не от одного рынка — как у { GridQuoter},
     * где аск привязан к цене входа. Остальным политикам знать об этом незачем.
     */
    default void onFill(Fill fill) {
    }

    /**
     * Новое окно рынка. Нужно единственной семье политик — контролю C4, который
     * котирует НЕ от корзинной справедливой цены, а от середины стакана своей
     * пары; всем остальным книга не нужна, поэтому метод с пустым телом.
     */
    default void onWindow(BookView book) {
    }

    /** Без дрейфа — историческое поведение. */
    default Quoter.Quotes quotes(double fair, double inventory) {
        return quotes(fair, inventory, 0);
    }

    /**
     * Случайные котировки: отступ равномерен на (0, 2d], то есть в среднем тот же
     * d, но без всякой логики. Генератор — с фиксированным seed из конфига:
     * любая случайность в стенде обязана быть воспроизводимой (ТЗ §7).
     */
    static QuotePolicy random(Quoter.Params params, long seed) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        return (fair, inventory, drift) -> {
            if (!(fair > 0)) {
                return new Quoter.Quotes(null, null);
            }
            // Скос применяется ТОТ ЖЕ, что у стратегии, слагаемое дрейфа включительно.
            // Иначе контроль перестаёт быть контролем: при сильном скосе стратегия
            // сознательно держит вдвое меньший инвентарь, контроль без скоса держит
            // вдвое больший, и на растущем окне он выигрывает по `total` просто потому,
            // что он больше лонг. Сравнение должно отличаться ровно одним — ВЫБОРОМ ЦЕН,
            // поэтому формула скоса берётся из общего места, а не переписывается здесь.
            double shift = params.skewK() * Quoter.skew(params, inventory, drift);

            Double bid = null;
            Double ask = null;
            if (inventory < params.inventoryCap()) {
                bid = fair * (1 - 2 * params.offset() * rng.nextDouble() - shift);
            }
            if (inventory > 0) {
                ask = fair * (1 + 2 * params.offset() * rng.nextDouble() - shift);
            }
            return new Quoter.Quotes(bid, ask);
        };
    }

    /**
     * Второй контроль: **случайные цены на ТЕХ ЖЕ расстояниях**.
     *
     * Зачем он нужен отдельно (док. 127 §9). У {@link #random} случайно не только
     * положение центра, но и само расстояние — оно равномерно на (0, 2d]. Между
     * тем `край × оборот` по расстоянию не плоский, у него есть вершина (док. 113
     * §4), поэтому смесь расстояний вокруг d — это не нейтральная перестановка, а
     * ДРУГАЯ точка лестницы отступа. Проигрыш такому контролю смешан с лестницей
     * и сам по себе не значит ничего.
     *
     * Здесь отличие ровно одно: расстояние ±d и скос — как у стратегии, а центр
     * берётся не из текущей справедливой цены, а из СЛУЧАЙНОЙ недавней. То есть
     * контроль отвечает на единственный вопрос: **помогает ли слежение за
     * справедливой ценой** или достаточно стоять в книге на правильном удалении
     * от чего угодно похожего на цену.
     *
     * ⚠️ **Чего он НЕ отвечает** (док. 132 §1). «Не следить за ценой» и «стоять
     * с устаревшей котировкой» — одно и то же, а цена устаревания уже измерена
     * лестницей задержки: {@code 2.86·√(t/5)} б.п. Поэтому счёт этого контроля
     * предсказуем из одной формулы, и вопрос дока 127 §9 — «добавляют ли что-то
     * СКОС и корзинная справедливая цена» — им не закрывается. На это отвечают
     * {@link #noSkew} и {@link #ownBookMid}, которые работают на ТЕКУЩЕЙ цене.
     *
     * @param lagWindows сколько последних окон составляют «недавнее»; центр
     *                   выбирается равномерно среди них, включая текущее
     */
    static QuotePolicy staleAnchor(Quoter.Params params, long seed, int lagWindows) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        double[] ring = new double[Math.max(1, lagWindows)];
        int[] seen = {0};
        return (fair, inventory, drift) -> {
            if (!(fair > 0)) {
                return new Quoter.Quotes(null, null);
            }
            ring[seen[0] % ring.length] = fair;
            seen[0]++;
            // Пока кольцо не заполнено, случайный индекс берётся только из
            // заполненной части: нули в хвосте дали бы центр в нуле.
            int have = Math.min(seen[0], ring.length);
            double anchor = ring[rng.nextInt(have)];

            double shift = params.skewK() * Quoter.skew(params, inventory, drift);
            Double bid = null;
            Double ask = null;
            if (inventory < params.inventoryCap()) {
                bid = anchor * (1 - params.offset() - shift);
            }
            if (inventory > 0) {
                ask = anchor * (1 + params.offset() - shift);
            }
            return new Quoter.Quotes(bid, ask);
        };
    }

    /**
     * Контроль C3: **та же справедливая цена, тот же отступ, но БЕЗ СКОСА**
     * (док. 132 §1).
     *
     * Скос — единственный «умный» элемент котировщика, и про него уже дважды
     * говорилось, что он регулятор беты, а не источник края (доки 111, 118).
     * Здесь это проверяется прямо: котируем симметрично `fair ± d` и смотрим,
     * что теряется.
     *
     * Устаревания в контроле нет вовсе — цена текущая, — поэтому разница со
     * стратегией не может объясняться свежестью, и в этом всё отличие от
     * {@link #staleAnchor}.
     *
     * Границы инвентаря остаются те же: без них контроль ушёл бы в шорт на споте
     * и сравнивать было бы нечего.
     */
    static QuotePolicy noSkew(Quoter.Params params) {
        return (fair, inventory, drift) -> {
            if (!(fair > 0)) {
                return new Quoter.Quotes(null, null);
            }
            Double bid = inventory < params.inventoryCap() ? fair * (1 - params.offset()) : null;
            Double ask = inventory > 0 ? fair * (1 + params.offset()) : null;
            return new Quoter.Quotes(bid, ask);
        };
    }

    /**
     * Контроль C4: котируем от **середины собственного стакана**, а не от
     * корзинной справедливой цены (док. 132 §1).
     *
     * Зачем. Справедливая цена строится из 23 пар через implied-курс USDC/USD —
     * это самая дорогая часть системы, из-за неё же паузится зеркало (док. 125
     * §3), и с тривиальной альтернативой её ни разу не сравнивали. Если контроль
     * не проигрывает, половину машинерии можно снять.
     *
     * Скос выключен, чтобы отличие было ровно одно — ИСТОЧНИК ЦЕНЫ. Гейт
     * корзины при этом остаётся общим: оба прогона котируют в одних и тех же
     * окнах, иначе сравнивались бы разные выборки времени.
     *
     * Пока книга пустая или перекрещенная, середины нет — сторона не котируется.
     */
    static QuotePolicy ownBookMid(Quoter.Params params) {
        double[] mid = {0};
        return new QuotePolicy() {
            @Override
            public void onWindow(BookView book) {
                mid[0] = book == null || book.empty() ? 0 : (book.bestBid() + book.bestAsk()) / 2;
            }

            @Override
            public Quoter.Quotes quotes(double fair, double inventory, double drift) {
                double centre = mid[0];
                if (!(centre > 0)) {
                    return new Quoter.Quotes(null, null);
                }
                Double bid = inventory < params.inventoryCap()
                        ? centre * (1 - params.offset()) : null;
                Double ask = inventory > 0 ? centre * (1 + params.offset()) : null;
                return new Quoter.Quotes(bid, ask);
            }
        };
    }
}
