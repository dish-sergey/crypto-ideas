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

    Quoter.Quotes quotes(double fair, double inventory);

    /**
     * Случайные котировки: отступ равномерен на (0, 2d], то есть в среднем тот же
     * d, но без всякой логики. Генератор — с фиксированным seed из конфига:
     * любая случайность в стенде обязана быть воспроизводимой (ТЗ §7).
     */
    static QuotePolicy random(Quoter.Params params, long seed) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        return (fair, inventory) -> {
            if (!(fair > 0)) {
                return new Quoter.Quotes(null, null);
            }
            // Скос применяется ТОТ ЖЕ, что у стратегии. Иначе контроль перестаёт быть
            // контролем: при сильном скосе стратегия сознательно держит вдвое меньший
            // инвентарь, контроль без скоса держит вдвое больший, и на растущем окне
            // он выигрывает по `total` просто потому, что он больше лонг. Сравнение
            // должно отличаться ровно одним — ВЫБОРОМ ЦЕН.
            double skew = params.inventoryCap() > 0 ? inventory / params.inventoryCap() : 0;
            skew = Math.max(-1, Math.min(1, skew));
            double shift = params.skewK() * skew;

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
}
