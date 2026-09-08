package org.home.data.revx.layout;

import org.home.data.revx.place.InstantPlacer;
import org.home.data.revx.place.Placer;
import org.home.data.revx.place.RateLimitedPlacer;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;

/**
 * Выбор сменных модулей по имени. Одна точка, чтобы стенд и живой исполнитель
 * собирали одно и то же.
 *
 * <h2>Формат</h2>
 *
 * {@code расстановка/приведение}, например {@code v1/real} или {@code v1/instant}.
 * Пустая строка означает ВСТРОЕННЫЙ путь — тот, что работал до появления
 * модулей. Он остаётся умолчанием намеренно: пока сменная версия не доказала
 * совпадения с ним на прогоне, менять поведение живых ботов не на чем.
 *
 * <h2>Что с чем сравнивать</h2>
 *
 * <ul>
 *   <li>{@code v1/real} против встроенного — проверка, что перенос ничего не
 *       изменил. Числа обязаны совпасть;</li>
 *   <li>{@code v1/instant} против {@code v1/real} — ЦЕНА ЗАДЕРЖКИ перестановки.
 *       Одна расстановка, разная скорость приведения;</li>
 *   <li>{@code vN/real} против {@code v1/real} — собственно опыт с расстановкой.</li>
 * </ul>
 *
 * ⚠️ {@code instant} — только для стенда. Живьём он даёт до 36 замен в секунду
 * при лимите площадки в 10 на весь счёт.
 */
public final class Modules {

    /** Что выбрано; пусто — встроенный путь. */
    public record Choice(OrderLayout layout, Placer placer) {

        public boolean builtIn() {
            return layout == null || placer == null;
        }

        public String describe() {
            return builtIn() ? "встроенный" : layout.name() + "/" + placer.name();
        }
    }

    private Modules() {
    }

    /**
     * @param spec        {@code расстановка/приведение}; пусто — встроенный путь
     * @param params      параметры котировщика
     * @param policy      источник базовых цен
     * @param levels      сколько заявок на сторону
     * @param levelStep   расстояние между уровнями в долях цены
     * @param innerFirst  раздавать капитал от ближнего уровня
     * @param spreadToK   доля отступа, отдаваемая ширине опоры
     * @param spreadMaxPct выше этой ширины опоры не котируем
     * @param budgetWiden во сколько раз раздвинуть отступ при полном дефиците
     */
    public static Choice of(String spec, Quoter.Params params, QuotePolicy policy,
                            int levels, double levelStep, boolean innerFirst,
                            double spreadToK, double spreadMaxPct, double budgetWiden) {
        if (spec == null || spec.isBlank()) {
            return new Choice(null, null);
        }
        String[] parts = spec.trim().toLowerCase(java.util.Locale.ROOT).split("/");
        String layoutName = parts[0];
        String placerName = parts.length > 1 ? parts[1] : "real";

        // «v2:30» — шаг сетки 30% отступа; «v2:30x2» — то же, но не ближе двух
        // тиков друг к другу. Пол по тикам нужен, потому что доля отступа бывает
        // мельче шага цены: у ENA тик 6.01 б.п., и 20% от отступа в 26 — это
        // 5.2 б.п., то есть три уровня лягут на одну цену.
        double stepPct = 0.3;
        int minTicks = 1;
        if (layoutName.startsWith("v2:") || layoutName.startsWith("v3:")) {
            String kind = layoutName.substring(0, 2);
            String spec2 = layoutName.substring(3);
            int x = spec2.indexOf('x');
            if (x > 0) {
                minTicks = Integer.parseInt(spec2.substring(x + 1));
                spec2 = spec2.substring(0, x);
            }
            stepPct = Integer.parseInt(spec2) / 100.0;
            layoutName = kind;
        }
        final double pct = stepPct;
        final int ticks = minTicks;
        OrderLayout layout = switch (layoutName) {
            case "v1" -> new LayoutV1(params, policy, levels, levelStep, innerFirst,
                    spreadToK, spreadMaxPct, budgetWiden);
            case "v2" -> new LayoutV2(params, policy, levels, pct, ticks, innerFirst,
                    spreadToK, spreadMaxPct, budgetWiden);
            case "v3" -> new LayoutV3(params, policy, levels, pct, ticks, innerFirst,
                    spreadToK, spreadMaxPct, budgetWiden);
            default -> throw new IllegalArgumentException("неизвестная расстановка: "
                    + layoutName + " (есть: v1, v2:N[xM], v3:N[xM])");
        };
        // «real:3» — три замены за тик. Число нужно затем, что потолок в одну
        // замену и есть главное ограничение: при периоде тика в секунду шесть
        // слотов физически не обновляются чаще раза в шесть секунд, а замер по
        // суткам ADA дал 11-21 секунду на слот при цели, считаемой ежесекундно.
        int perTick = 1;
        if (placerName.startsWith("real:")) {
            perTick = Integer.parseInt(placerName.substring(5));
            placerName = "real";
        }
        Placer placer = switch (placerName) {
            case "real" -> new RateLimitedPlacer(perTick, params.requoteThreshold());
            case "instant" -> new InstantPlacer(params.requoteThreshold());
            default -> throw new IllegalArgumentException(
                    "неизвестное приведение: " + placerName + " (есть: real, real:N, instant)");
        };
        return new Choice(layout, placer);
    }
}
