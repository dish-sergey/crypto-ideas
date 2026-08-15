package org.home.data.trade;

import java.util.List;

/** Абстракция фида разлоков для оркестратора (реализуется {@link UnlockFeed}; в тестах — мок). */
public interface EventFeed {
    /** Будущие клифф-разлоки ≥3% на Kraken-инструментах (день > today). */
    List<UnlockEvent> upcoming(long todayEpochDay) throws Exception;
    /** События с днём входа сегодня: unlockDay == today + entryLead. */
    List<UnlockEvent> dueForEntry(long todayEpochDay, int entryLead) throws Exception;
}
