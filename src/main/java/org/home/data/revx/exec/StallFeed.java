package org.home.data.revx.exec;

import java.util.List;

/**
 * Расписание затыков площадки, увиденное ЗОНДОМ — не ботом.
 *
 * Бот в разведённую минуту ничего не шлёт и затыков в ней не видит; зонд
 * ({@link StallProbe}) торгует во все минуты и видит всегда. Живьём источник —
 * {@code stall_probe} в {@code venue.db} через {@link VenueReads}, на стенде —
 * расписание затыков самого {@code SimVenue}.
 */
public interface StallFeed {

    /** Моменты затыков начиная с {@code sinceMs}. */
    List<Long> stallTimes(long sinceMs);

    /**
     * Сколько зонд наблюдает без перерыва к моменту {@code nowMs}, мс; 0 — зонда
     * нет или он молчит. Пока наблюдений мало, бот не доверяет им одним.
     */
    long observedMs(long nowMs);
}
