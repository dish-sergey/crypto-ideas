package org.home.data.trade;

/** Разрыв соединения с биржей. StopEngine ловит и повторяет попытку закрытия после реконнекта (§4.2). */
public class ExchangeDisconnectedException extends Exception {
    public ExchangeDisconnectedException(String message) { super(message); }
}
