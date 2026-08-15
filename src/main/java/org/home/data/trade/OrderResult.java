package org.home.data.trade;

/**
 * Результат размещения ордера. Отклонение — это статус REJECTED, а не исключение
 * (исключение зарезервировано за разрывом соединения).
 */
public record OrderResult(Status status, String orderId, double fillPx, double fillQty, String error) {

    public enum Status { FILLED, REJECTED, ACCEPTED }

    public static OrderResult filled(String orderId, double fillPx, double fillQty) {
        return new OrderResult(Status.FILLED, orderId, fillPx, fillQty, null);
    }
    public static OrderResult rejected(String error) {
        return new OrderResult(Status.REJECTED, null, 0, 0, error);
    }
    public boolean isFilled() { return status == Status.FILLED; }
}
