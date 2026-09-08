package org.home.data.revx.place;

import org.home.data.revx.sim.Side;

/**
 * Что сделать с одним слотом, чтобы приблизить книгу к желаемой.
 *
 * Это ПЛАН, а не действие: {@code Placer} — чистая функция, она ничего не
 * отправляет. Отправляет исполнитель, и он же ведёт журнал, тратит бюджет и
 * обрабатывает отказы. Разделение нужно затем, чтобы способ приведения можно
 * было менять и сравнивать, не трогая ни учёт, ни предохранители.
 */
public record Action(Kind kind, Side side, int level, double price, double size, String venueId) {

    public enum Kind {
        /** Заявки в слоте нет — поставить. Тратит суточный лимит. */
        PLACE,
        /** Заявка есть, но не на месте — переставить. Суточного лимита не тратит. */
        REPLACE,
        /** Заявка есть, а быть не должна — снять. */
        CANCEL
    }

    public static Action place(Side side, int level, double price, double size) {
        return new Action(Kind.PLACE, side, level, price, size, null);
    }

    public static Action replace(Side side, int level, double price, double size, String id) {
        return new Action(Kind.REPLACE, side, level, price, size, id);
    }

    public static Action cancel(Side side, int level, String id) {
        return new Action(Kind.CANCEL, side, level, 0, 0, id);
    }
}
