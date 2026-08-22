package org.home.data.trade;

import java.util.Set;

/**
 * Персистентность eventId, которые больше не предлагаем (сыгранные/отклонённые/просроченные). Нужна, чтобы
 * рестарт не забыл, что событие уже торговалось, и не переоткрыл его после стопа. Реальная реализация —
 * {@link FileTradedStore}; в тестах {@link #NONE}.
 */
public interface TradedStore {

    void record(String eventId);
    Set<String> load();

    TradedStore NONE = new TradedStore() {
        public void record(String eventId) { }
        public Set<String> load() { return Set.of(); }
    };
}
