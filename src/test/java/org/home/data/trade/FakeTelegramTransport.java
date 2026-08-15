package org.home.data.trade;

import java.util.ArrayList;
import java.util.List;

/** Тестовый транспорт: копит вызовы (method+body), ничего не шлёт. getUpdates в тестах не используется —
 *  разбор гоняется напрямую через handleUpdates. */
class FakeTelegramTransport implements TelegramTransport {

    record Call(String method, String body) {}

    final List<Call> calls = new ArrayList<>();

    @Override public String call(String method, String body) { calls.add(new Call(method, body)); return "{}"; }

    List<String> methods() { return calls.stream().map(Call::method).toList(); }
    Call last() { return calls.isEmpty() ? null : calls.get(calls.size() - 1); }
    Call lastOf(String method) {
        Call r = null;
        for (Call c : calls) if (c.method().equals(method)) r = c;
        return r;
    }
    long countOf(String method) { return calls.stream().filter(c -> c.method().equals(method)).count(); }
}
