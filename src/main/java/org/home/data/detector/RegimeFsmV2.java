package org.home.data.detector;

/**
 * Классификатор состояния детектора v2 (док. 01-v2 §3). В отличие от v1 не
 * складывает всё в скаляр: на вход идут три ортогональные оси — направление D,
 * трендовость T, стресс S — и состояние собирается явными правилами сверху вниз,
 * первое сработавшее выигрывает:
 * <pre>
 *   1. S >= 0.80                       -> CRASH  (приоритет, обходит dwell-lock)
 *   2. дней_в_состоянии < 15           -> текущее (dwell-time lock)
 *   3. T < 0.40                        -> RANGE
 *   4. T >= 0.40 и D > +0.20           -> BULL
 *   5. T >= 0.40 и D < -0.20           -> BEAR
 *   6. иначе                           -> TRANSITION
 * </pre>
 * CRASH — единственное состояние с приоритетом над dwell-lock: обвал должен
 * распознаваться за часы, а не за 15 дней. Выход из CRASH: S &lt; 0.50 три дня
 * подряд, после чего состояние пересчитывается с нуля и dwell-таймер обнуляется.
 *
 * Пороги фиксированы до бэктеста (док. 01-v2 §6, правило 1). Dwell-lock заменяет
 * двойной гистерезис v1 ({@link HysteresisFsm}) — одно правило вместо трёх
 * взаимно противоречащих.
 */
public class RegimeFsmV2 {

    public enum State { BULL, BEAR, RANGE, TRANSITION, CRASH }

    static final double S_CRASH = 0.80;       // вход в CRASH
    static final double S_CRASH_EXIT = 0.50;  // порог «спокойного» дня для выхода
    static final int CRASH_EXIT_DAYS = 3;     // спокойных дней подряд для выхода
    static final int DWELL = 15;              // минимум дней в состоянии
    static final double T_RANGE = 0.40;       // ниже — RANGE (нет тренда)
    static final double D_BULL = 0.20;        // выше — бычье направление
    static final double D_BEAR = -0.20;       // ниже — медвежье направление

    // Холодный старт: TRANSITION с разблокированным dwell, чтобы первая же
    // классификация на прогреве не была залочена.
    private State state = State.TRANSITION;
    private int daysInState = DWELL;
    private int calmStreak;

    public State step(double d, double t, double s) {
        if (state == State.CRASH) {
            calmStreak = s < S_CRASH_EXIT ? calmStreak + 1 : 0;
            if (calmStreak >= CRASH_EXIT_DAYS) {
                enter(classifyFresh(d, t));   // пересчёт с нуля, dwell обнуляется
                calmStreak = 0;
            } else {
                daysInState++;
            }
            return state;
        }
        if (s >= S_CRASH) {
            enter(State.CRASH);
            calmStreak = 0;
            return state;
        }
        if (daysInState < DWELL) {
            daysInState++;
            return state;                     // dwell-lock
        }
        State target = classifyFresh(d, t);
        if (target != state) {
            enter(target);
        } else {
            daysInState++;
        }
        return state;
    }

    /** Правила 3–6 без dwell-lock и CRASH: только T и D. */
    static State classifyFresh(double d, double t) {
        if (t < T_RANGE) {
            return State.RANGE;
        }
        if (d > D_BULL) {
            return State.BULL;
        }
        if (d < D_BEAR) {
            return State.BEAR;
        }
        return State.TRANSITION;
    }

    private void enter(State s) {
        state = s;
        daysInState = 1;
    }

    public State state() {
        return state;
    }

    public int daysInState() {
        return daysInState;
    }
}
