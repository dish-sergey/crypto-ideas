package org.home.data.detector;

/**
 * Классификатор состояния детектора v3 (док. 01-detektor-rezhima-v3 §3). Ревизия
 * {@link RegimeFsmV2}: состояние CRASH и ось S удалены (диагностика 18-v2 фаза A
 * показала, что CRASH строго хуже отсутствия при идентичной просадке). Четыре
 * состояния, dwell-time lock <b>без исключений</b> (обход dwell был основным
 * механизмом ущерба CRASH, A5). Правила сверху вниз, первое сработавшее выигрывает:
 * <pre>
 *   1. дней_в_текущем_состоянии < 15   → текущее (dwell-time lock)
 *   2. T < 0.40                        → RANGE
 *   3. T ≥ 0.40 и D > +0.20            → BULL
 *   4. T ≥ 0.40 и D < −0.20            → BEAR
 *   5. иначе                           → TRANSITION
 * </pre>
 * Пороги T, D, dwell перенесены из v2 <b>без изменений</b> и не перекалибровались
 * после удаления CRASH (док. v3 §6.1 — защита от подгонки).
 */
public class RegimeFsmV3 {

    public enum State { BULL, BEAR, RANGE, TRANSITION }

    static final int DWELL = 15;
    static final double T_RANGE = 0.40;
    static final double D_BULL = 0.20;
    static final double D_BEAR = -0.20;

    // Холодный старт: TRANSITION с разблокированным dwell.
    private State state = State.TRANSITION;
    private int daysInState = DWELL;

    public State step(double d, double t) {
        if (daysInState < DWELL) {
            daysInState++;
            return state;
        }
        State target = classify(d, t);
        if (target != state) {
            state = target;
            daysInState = 1;
        } else {
            daysInState++;
        }
        return state;
    }

    static State classify(double d, double t) {
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

    public State state() {
        return state;
    }

    public int daysInState() {
        return daysInState;
    }
}
