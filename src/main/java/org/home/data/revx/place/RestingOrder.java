package org.home.data.revx.place;

import org.home.data.revx.sim.Side;

/**
 * Заявка, которая СЕЙЧАС стоит в книге — так, как её помнит бот.
 *
 * @param side        сторона
 * @param level       номер слота, 0 — ближний к цене
 * @param venueId     идентификатор у площадки; {@code null} — слот пуст
 * @param price       цена, по которой она стоит
 * @param size        количество
 * @param blockedTill до этого момента заявку трогать нельзя (пауза после отказа)
 */
public record RestingOrder(Side side, int level, String venueId, double price, double size,
                           long blockedTill) {

    public boolean empty() {
        return venueId == null;
    }
}
