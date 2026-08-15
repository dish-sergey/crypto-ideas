package org.home.data.trade;

import java.util.List;

/** Абстракция фида разлоков для оркестратора (реализуется {@link UnlockFeed}; в тестах — мок). */
public interface EventFeed {
    /** Будущие клифф-разлоки ≥3% на Kraken-инструментах (день > today). */
    List<UnlockEvent> upcoming(long todayEpochDay) throws Exception;
    /** События с днём входа сегодня: unlockDay == today + entryLead. */
    List<UnlockEvent> dueForEntry(long todayEpochDay, int entryLead) throws Exception;

    /**
     * Все события с датой разлока в окне (today, today+lead] (doc 59 §4): ловит и обычный вход за 5 дней,
     * и УСКОРЕННЫЕ разлоки, впрыгнувшие в окно раньше (событие, которого вчера в окне не было). Дедуп по
     * открытым/ожидающим — на стороне оркестратора, поэтому каждое событие рассматривается один раз.
     */
    default List<UnlockEvent> enterableWithin(long todayEpochDay, int lead) throws Exception {
        java.util.List<UnlockEvent> o = new java.util.ArrayList<>();
        for (UnlockEvent e : upcoming(todayEpochDay)) if (e.unlockDay() <= todayEpochDay + lead) o.add(e);
        return o;
    }
}
