package org.home.data.trade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Детерминированный мок биржи для фазы 0 (doc 57 §6): песочница Kraken выведена, проверяем
 * стоп-логику и отмену разлока на синтетике. Ценами управляем через {@link #tick}; сбои внедряем
 * флагами. Позиции — источник истины (как у настоящего адаптера), StopEngine восстанавливается из них.
 */
public class MockExchange implements ExchangeAdapter {

    private final Map<String, Double> marks = new LinkedHashMap<>();
    private final Map<String, Position> pos = new LinkedHashMap<>();
    private double balance;

    // --- внедрение сбоев (§4.2) ---
    private boolean disconnected = false;      // разрыв соединения: методы бросают исключение
    private int rejectCloses = 0;              // отклонять следующие N закрывающих ордеров
    private double closeSlippage = 0.0;        // проскальзывание закрытия сверх mark (гэп): fill = mark*(1+slip)

    public MockExchange(double balance) { this.balance = balance; }

    // ---- управление сценарием (только в тестах/фазе 0) ----
    public void tick(String symbol, double price) { marks.put(symbol, price); }
    public void disconnect() { disconnected = true; }
    public void reconnect() { disconnected = false; }
    public void rejectNextCloses(int n) { rejectCloses = n; }
    public void setCloseSlippage(double slip) { closeSlippage = slip; }
    /** Позиция закрыта вне системы (ручное закрытие / ADL). Проверка «не закрывать повторно». */
    public void closeExternally(String symbol) { pos.remove(symbol); }

    private void checkConn() throws ExchangeDisconnectedException {
        if (disconnected) throw new ExchangeDisconnectedException("mock disconnected");
    }

    @Override public double mark(String symbol) throws ExchangeDisconnectedException {
        checkConn(); return marks.getOrDefault(symbol, 0.0);
    }

    @Override public OrderResult openShort(String symbol, double qty) throws ExchangeDisconnectedException {
        checkConn();
        double px = marks.getOrDefault(symbol, 0.0);
        if (px <= 0 || qty <= 0) return OrderResult.rejected("bad price/qty");
        pos.put(symbol, new Position(symbol, Side.SHORT, qty, px));
        return OrderResult.filled("mock-open-" + symbol + "-" + System.nanoTime(), px, qty);
    }

    @Override public OrderResult closeShort(String symbol, double qty) throws ExchangeDisconnectedException {
        checkConn();
        if (rejectCloses > 0) { rejectCloses--; return OrderResult.rejected("temporary reject"); }
        Position p = pos.get(symbol);
        if (p == null) return OrderResult.rejected("no position");
        double fill = marks.getOrDefault(symbol, p.entryPx()) * (1 + closeSlippage);
        pos.remove(symbol);
        return OrderResult.filled("mock-close-" + symbol + "-" + System.nanoTime(), fill, qty);
    }

    @Override public List<Position> positions() throws ExchangeDisconnectedException {
        checkConn(); return new ArrayList<>(pos.values());
    }

    @Override public double balance() throws ExchangeDisconnectedException { checkConn(); return balance; }
}
