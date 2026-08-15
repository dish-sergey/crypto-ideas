package org.home.data.trade;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Approval Gate (doc 54 §8, 07-approval-gate): каждое событие уходит на ручное подтверждение,
 * без него открывающий ордер не отправляется. При ~5 событиях/мес это редкий путь кода —
 * тем важнее прогнать его в фазе 0 десятки раз (doc 57 §4.5): ни одного ордера в обход.
 */
public class ApprovalGate {
    private final Set<String> pending = new LinkedHashSet<>();
    private final Set<String> approved = new LinkedHashSet<>();

    /** Событие поступило и ждёт подтверждения. */
    public void submit(String eventId) { pending.add(eventId); }

    /** Подтвердить событие. true — если оно было в ожидании. */
    public boolean approve(String eventId) {
        if (pending.remove(eventId)) { approved.add(eventId); return true; }
        return false;
    }

    /** Отклонить событие (кнопка «Отклонить»). true — если оно было в ожидании. */
    public boolean reject(String eventId) { return pending.remove(eventId); }

    public boolean isApproved(String eventId) { return approved.contains(eventId); }
    public Set<String> pending() { return Set.copyOf(pending); }
}
