package org.home.data.revx.place;

import org.home.data.revx.layout.DesiredOrder;
import org.home.data.revx.sim.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Приведение книги с потолком: ОДНА замена за тик. Так работают живые боты.
 *
 * <h2>Почему потолок нужен</h2>
 *
 * Замена стоит запроса, а площадка даёт их 10 в секунду НА ВЕСЬ СЧЁТ. У бота до
 * шести заявок (три уровня × две стороны), ботов шесть, и на общее движение цены
 * они реагируют в одну и ту же секунду. Замерено 06.09.2026: в среднем 4.3
 * замены в секунду — вроде бы вдвое ниже лимита, — но 68 секунд из 415 пробили
 * десятку, пик 24. За пробой площадка отвечает 429 с паузой, и всё это время бот
 * стоит со старой ценой. То есть залпы стоили ровно того, ради чего замены и
 * делаются.
 *
 * Одна замена на тик у шести ботов даёт 6 запросов в секунду при лимите 10 —
 * граница доказуемая, а не средняя.
 *
 * <h2>Кому достаётся замена</h2>
 *
 * Слоту с НАИБОЛЬШИМ ОТНОСИТЕЛЬНЫМ расхождением, а не первому по порядку.
 * Порядок обхода задан раздачей капитала, и отдать замену по нему значило бы
 * обновлять дальние заявки, которые почти не двигаются, пока ближняя — та, что
 * и приносит исполнения, — висит по устаревшей цене.
 *
 * Относительное, а не абсолютное: иначе биткойн с ценой в 79 000 забирал бы
 * замену у PEPE с ценой 0.0000036 всегда.
 *
 * <h2>Что потолком НЕ ограничено</h2>
 *
 * Постановка и отмена. Пустой слот — состояние дороже устаревшей цены, а
 * постановок и так мало: их сдерживает суточный лимит. Отмена бесплатна и
 * всегда безопасна.
 */
public final class RateLimitedPlacer implements Placer {

    private final int replacesPerTick;
    private final double requoteThreshold;

    public RateLimitedPlacer(double requoteThreshold) {
        this(1, requoteThreshold);
    }

    /**
     * @param replacesPerTick  сколько замен разрешено за тик
     * @param requoteThreshold ниже какого ОТНОСИТЕЛЬНОГО расхождения заявку не
     *                         трогать вовсе. Без порога единственная замена
     *                         достаётся слоту, отставшему на сотую базисного
     *                         пункта, а тот, что отстал на пять, продолжает
     *                         висеть: замер 08.09.2026 показал расхождение с
     *                         эталоном до 4.6% дохода при ТОЙ ЖЕ доле ленты —
     *                         объём исполнений тот же, а цены хуже.
     */
    public RateLimitedPlacer(int replacesPerTick, double requoteThreshold) {
        this.replacesPerTick = Math.max(1, replacesPerTick);
        this.requoteThreshold = Math.max(0, requoteThreshold);
    }

    @Override
    public String name() {
        return "real:" + replacesPerTick + "/тик";
    }

    /**
     * ⚠️ ДВА РАЗНЫХ ПОРЯДКА, и путать их нельзя.
     *
     * Победитель замены выбирается по УРОВНЯМ ПО ВОЗРАСТАНИЮ, а действия
     * выдаются в том порядке, в каком пришли слоты, — то есть в порядке раздачи
     * капитала. Первый порядок решает, кого двигать, при равных расхождениях он
     * же задаёт разрешение спора; второй решает, кому достанутся деньги, когда
     * их не хватает на всё.
     *
     * Совмещение этих порядков и было вторым расхождением при сличении следов
     * 08.09.2026: первые 356 действий совпадали, а на 357-м заменялся другой
     * уровень — цены отличались ровно на шаг сетки.
     */
    @Override
    public List<Action> plan(List<DesiredOrder> desired, List<RestingOrder> resting, long nowMs) {
        RestingOrder winner = pickWinner(desired, resting, nowMs);
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
                continue;                 // пауза после отказа: слот не трогаем
            }
            if (r.empty()) {
                // ⚠️ Постановка выдаётся НА МЕСТЕ слота, а не в конец плана:
                // действия выполняются подряд и каждое видит остаток средств
                // после предыдущего.
                plan.add(Action.place(want.side(), want.level(), want.price(), want.size()));
            } else if (r == winner) {
                plan.add(Action.replace(want.side(), want.level(),
                        want.price(), want.size(), r.venueId()));
            }
        }
        return plan;
    }

    /** Кому достанется единственная замена: по уровням снизу вверх, строго больше. */
    private RestingOrder pickWinner(List<DesiredOrder> desired, List<RestingOrder> resting,
                                    long nowMs) {
        List<RestingOrder> byLevel = new ArrayList<>(resting);
        byLevel.sort((x, y) -> x.level() != y.level()
                ? Integer.compare(x.level(), y.level())
                : Integer.compare(order(x.side()), order(y.side())));
        double best = 0;
        RestingOrder winner = null;
        for (RestingOrder r : byLevel) {
            if (r.empty() || nowMs < r.blockedTill() || !(r.price() > 0)) {
                continue;
            }
            DesiredOrder want = find(desired, r.side(), r.level());
            if (want == null) {
                continue;
            }
            double divergence = Math.abs(want.price() - r.price()) / r.price();
            // Строго больше: при равенстве побеждает тот, кого встретили раньше,
            // то есть уровень ближе к рынку.
            if (divergence > requoteThreshold && divergence > best) {
                best = divergence;
                winner = r;
            }
        }
        return replacesPerTick > 0 ? winner : null;
    }

    private static int order(Side side) {
        return side == Side.BUY ? 0 : 1;
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
