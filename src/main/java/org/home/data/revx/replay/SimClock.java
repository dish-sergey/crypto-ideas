package org.home.data.revx.replay;

import org.home.data.revx.exec.Clock;

/**
 * Часы повтора: время идёт ровно настолько, насколько его просят проспать.
 *
 * Ни одного обращения к настоящему времени — иначе прогон перестаёт совпадать
 * сам с собой. Сутки живого бота (61 268 тиков) прокручиваются за секунды.
 */
public final class SimClock implements Clock {

    private long now;
    private long stopAtMs = Long.MAX_VALUE;
    private Runnable onStop;

    public SimClock(long startMs) {
        this.now = startMs;
    }

    /**
     * Что сделать, когда время дойдёт до конца записи.
     *
     * Цикл котирования крутится, пока жив, и остановить его снаружи нечем:
     * часы двигает он сам, изнутри. Поэтому конец записи ловится здесь — в
     * единственной точке, где повтор гарантированно получает управление.
     */
    public void stopAt(long ms, Runnable action) {
        this.stopAtMs = ms;
        this.onStop = action;
    }

    @Override
    public long now() {
        return now;
    }

    /**
     * Расписание тиков живого бота.
     *
     * ⚠️ Ритм тиков — это ДАННЫЕ, а не константа. Живой бот просит поспать
     * «период минус то, что уже потрачено», и на каждом тике тратит разное:
     * сеть отвечает то за 60 мс, то за 900. В записи отметки идут неровно, и
     * повтор, шагающий ровно по секунде, попадает в них случайно — при первой
     * попытке совпало 94 тика из 61 593. Поэтому часы прыгают на СЛЕДУЮЩУЮ
     * записанную отметку, а не на период.
     */
    public void followSchedule(long[] schedule) {
        this.schedule = schedule;
    }

    private long[] schedule;
    private int cursor;

    @Override
    public void sleep(long ms) {
        if (schedule != null) {
            while (cursor < schedule.length && schedule[cursor] <= now) {
                cursor++;
            }
            if (cursor < schedule.length) {
                now = schedule[cursor++];
            } else if (ms > 0) {
                now += ms;
            }
        } else if (ms > 0) {
            now += ms;
        }
        if (now >= stopAtMs && onStop != null) {
            Runnable action = onStop;
            onStop = null;                 // ровно один раз
            action.run();
        }
    }

    /**
     * Передвинуть часы на заданный момент.
     *
     * ⚠️ Только вперёд. Повтор идёт по записанным отметкам времени, и шаг назад
     * означал бы, что мы вышли за пределы записи — тихо продолжать в такой
     * ситуации нельзя, иначе сверка покажет расхождение там, где на самом деле
     * сломан сам прогон.
     */
    public void moveTo(long ms) {
        if (ms < now) {
            throw new IllegalStateException("часы повтора назад не идут: " + now + " → " + ms);
        }
        now = ms;
    }
}
