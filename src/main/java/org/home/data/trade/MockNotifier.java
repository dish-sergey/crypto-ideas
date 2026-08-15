package org.home.data.trade;

import java.util.ArrayList;
import java.util.List;

/** Тестовый нотифаер: копит пуши в памяти вместо отправки в Telegram. Используется и в сухом прогоне. */
public class MockNotifier implements Notifier {

    private final List<Alert> pushes = new ArrayList<>();

    @Override public void push(Alert alert) { pushes.add(alert); }

    public List<Alert> pushes() { return List.copyOf(pushes); }
    public int count() { return pushes.size(); }
    public long count(Alert.Level level) { return pushes.stream().filter(a -> a.level() == level).count(); }
    public Alert last() { return pushes.isEmpty() ? null : pushes.get(pushes.size() - 1); }
    public void clear() { pushes.clear(); }
}
