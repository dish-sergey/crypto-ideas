package org.home.data.theory.alloc;

/**
 * Аллокатор (ТЗ 65 §4): на вход — история до дня t включительно, на выход —
 * веса на день t+1. Использование данных дня t+1 и позже — дефект (см.
 * {@link History}).
 */
public interface Allocator {

    String id();

    /** Сброс состояния перед прогоном. {@code n} — число стратегий пула. */
    default void reset(int n) {
    }

    /**
     * Веса на день {@code h.today() + 1}. Сумма — 1 (нормировка проверяется
     * движком с точностью до эпсилон, §7.2).
     */
    double[] weights(History h);

    /**
     * Смотрит ли аллокатор в будущее намеренно ({@code BEST_FIXED},
     * {@code BEST_SINGLE}). Такие идут в отчёте отдельной секцией как ориентиры,
     * а не как стратегии (§4.1).
     */
    default boolean hindsight() {
        return false;
    }
}
