package org.home.data.detector;

/**
 * Гистерезис-FSM режима (док. 01 §2) — ключевая защита от пилы. Переходы:
 *   Вход в BULL:   score > +0.30 держится >= 10 дней
 *   Выход из BULL: score < +0.10  -> TRANSITION
 *   Вход в BEAR:   score < -0.30 держится >= 10 дней
 *   Выход из BEAR: score > -0.10  -> TRANSITION
 *   RANGE:         |score| < 0.30 стабильно >= 15 дней (из TRANSITION)
 *   TRANSITION:    после смены режима, пока не выполнено условие входа/стабилизации
 * Начальное состояние — TRANSITION (холодный старт разрешается по стрикам).
 */
public class HysteresisFsm {

    public enum State { BULL, RANGE, BEAR, TRANSITION }

    private static final int ENTER_DAYS = 10;
    private static final int RANGE_DAYS = 15;

    private State state = State.TRANSITION;
    private int daysInState = 0;
    private int bullStreak;
    private int bearStreak;
    private int rangeStreak;

    public State step(double score) {
        bullStreak = score > 0.30 ? bullStreak + 1 : 0;
        bearStreak = score < -0.30 ? bearStreak + 1 : 0;
        rangeStreak = Math.abs(score) < 0.30 ? rangeStreak + 1 : 0;

        State next = switch (state) {
            case BULL -> score < 0.10 ? State.TRANSITION : State.BULL;
            case BEAR -> score > -0.10 ? State.TRANSITION : State.BEAR;
            case RANGE -> bullStreak >= ENTER_DAYS ? State.BULL
                    : bearStreak >= ENTER_DAYS ? State.BEAR : State.RANGE;
            case TRANSITION -> bullStreak >= ENTER_DAYS ? State.BULL
                    : bearStreak >= ENTER_DAYS ? State.BEAR
                    : rangeStreak >= RANGE_DAYS ? State.RANGE : State.TRANSITION;
        };

        if (next != state) {
            state = next;
            daysInState = 1;
        } else {
            daysInState++;
        }
        return state;
    }

    public State state() {
        return state;
    }

    public int daysInState() {
        return daysInState;
    }
}
