package org.home.data.revx.place;

import org.home.data.revx.layout.DesiredOrder;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Приведение книги БЕЗ ПОТОЛКА: всё переставляется в тот же тик.
 *
 * <h2>Для чего это</h2>
 *
 * Это ВЕРХНЯЯ ГРАНИЦА, а не рабочий режим. Живьём его включать нельзя: шесть
 * ботов по шесть заявок дают до 36 замен в секунду при лимите площадки в 10 на
 * весь счёт, и площадка ответит отказами (замерено 06.09.2026: 68 секунд из 415
 * выше десятки, пик 24 — и 429 с паузой до полутора минут).
 *
 * Смысл в другом. Разница между этим приведением и {@link RateLimitedPlacer} на
 * одной и той же расстановке и есть ЦЕНА ЗАДЕРЖКИ ПЕРЕСТАНОВКИ — величина,
 * которую иначе неоткуда взять. Она отвечает, стоит ли бороться за право
 * двигать заявки чаще: просить у площадки лимит, разносить ботов по ключам,
 * сокращать число уровней.
 *
 * ⚠️ Оценка ЗАВЕДОМО ЗАВЫШЕНА и не только из-за лимита. Мгновенная перестановка
 * ещё и теряет очередь: заявка, сдвинутая на тик, встаёт в конец, и в
 * действительности частая перестановка приносит меньше, чем считает модель.
 * Поэтому число из сравнения — потолок выигрыша, а не его обещание.
 */
public final class InstantPlacer implements Placer {

    private final double requoteThreshold;

    /**
     * @param requoteThreshold порог перевыставления. Он нужен и здесь: без него
     *                         заявка двигалась бы на каждое дрожание последнего
     *                         знака, и «мгновенное приведение» мерило бы не
     *                         скорость, а частоту округления. Отличие от
     *                         {@link RateLimitedPlacer} должно быть ровно одно —
     *                         СКОЛЬКО заявок разрешено двинуть за тик.
     */
    public InstantPlacer(double requoteThreshold) {
        this.requoteThreshold = Math.max(0, requoteThreshold);
    }

    @Override
    public String name() {
        return "instant";
    }

    @Override
    public List<Action> plan(List<DesiredOrder> desired, List<RestingOrder> resting, long nowMs) {
        List<Action> plan = new ArrayList<>();
        for (RestingOrder r : resting) {
            DesiredOrder want = find(desired, r.side(), r.level());
            if (want == null) {
                if (!r.empty()) {
                    plan.add(Action.cancel(r.side(), r.level(), r.venueId()));
                }
                continue;
            }
            if (nowMs < r.blockedTill()) {
                // Пауза после отказа соблюдается и здесь: это не потолок темпа, а
                // защита от долбёжки в отказывающую площадку.
                continue;
            }
            if (r.empty()) {
                plan.add(Action.place(want.side(), want.level(), want.price(), want.size()));
            } else if (r.price() > 0
                    && Math.abs(want.price() - r.price()) / r.price() > requoteThreshold) {
                plan.add(Action.replace(want.side(), want.level(), want.price(), want.size(),
                        r.venueId()));
            }
        }
        return plan;
    }

    private static DesiredOrder find(List<DesiredOrder> desired, Side side, int level) {
        for (DesiredOrder d : desired) {
            if (d.side() == side && d.level() == level) {
                return d;
            }
        }
        return null;
    }
}
