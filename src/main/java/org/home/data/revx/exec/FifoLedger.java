package org.home.data.revx.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Книга партий по правилу FIFO: что куплено первым, то первым и продано.
 *
 * <h2>Зачем отдельный класс</h2>
 *
 * У бота до сих пор был только один P&L — разность «касса плюс инвентарь по
 * текущей цене» против затравки. Он верен как итог, но по нему нельзя ответить
 * на вопросы, которые задаёшь, глядя на живого бота: сколько уже ЗАФИКСИРОВАНО,
 * сколько висит НЕРЕАЛИЗОВАННЫМ, по какой цене куплено то, что висит, и сколько
 * это лотов. На Revolut видна общая картина по счёту, а по ботам её нет вовсе —
 * они делят один счёт втроём.
 *
 * <h2>Почему FIFO, а не средняя</h2>
 *
 * Средняя себестоимость (как в {@link org.home.data.revx.sim.CostFloorPolicy})
 * отвечает на вопрос «где граница, ниже которой продавать не хочу». FIFO
 * отвечает на другой: «сколько я на самом деле заработал на закрытых сделках».
 * Для маркет-мейкера это и есть захват спреда, освобождённый от переоценки
 * остатка, — величина, которая не зависит от того, куда сходил рынок.
 *
 * <h2>Короткие партии</h2>
 *
 * Книга симметрична: продажа сверх инвентаря открывает ОТРИЦАТЕЛЬНУЮ партию, и
 * следующая покупка её закрывает. Спот-бот в шорт уйти не должен, но если
 * позиция разъехалась с площадкой, молча терять сделки нельзя — лучше увидеть
 * короткую партию в отчёте.
 */
public final class FifoLedger {

    /** Открытая партия: сколько и по какой цене. Отрицательное {@code qty} = шорт. */
    public record Lot(long tsMs, double qty, double price) {
    }

    /** Закрытая пара «вход — выход». {@code pnl} уже за вычетом комиссий обеих ног. */
    public record Realisation(long tsMs, double qty, double entry, double exit, double pnl) {
    }

    /** Текущее состояние остатка. */
    public record Position(double qty, double cost, int lots) {

        /** Средняя цена входа по остатку; 0, если остатка нет. */
        public double averagePrice() {
            return Math.abs(qty) > 1e-15 ? cost / qty : 0;
        }

        /** Переоценка остатка по текущей цене. */
        public double unrealised(double fair) {
            return fair > 0 ? qty * fair - cost : 0;
        }
    }

    private static final double EPS = 1e-15;

    private final Deque<Lot> open = new ArrayDeque<>();
    private final List<Realisation> closed = new ArrayList<>();
    private double fees;

    /**
     * Учесть исполнение.
     *
     * @param qty  всегда положительное количество; сторону задаёт {@code buy}
     * @param fee  комиссия сделки в валюте котировки; вычитается из результата
     */
    public void add(long tsMs, boolean buy, double qty, double price, double fee) {
        fees += fee;
        double left = qty;
        double sign = buy ? 1 : -1;
        // Сначала гасим ПРОТИВОПОЛОЖНЫЕ партии — это и есть FIFO-закрытие.
        while (left > EPS && !open.isEmpty() && Math.signum(open.peekFirst().qty()) == -sign) {
            Lot head = open.peekFirst();
            double take = Math.min(left, Math.abs(head.qty()));
            // Прибыль длинной партии: (выход − вход)·объём. Для короткой знак
            // переворачивается сам, потому что head.qty() отрицателен.
            double pnl = Math.signum(head.qty()) * (price - head.price()) * take;
            closed.add(new Realisation(tsMs, take,
                    head.price(), price, pnl));
            left -= take;
            double rest = Math.abs(head.qty()) - take;
            open.removeFirst();
            if (rest > EPS) {
                open.addFirst(new Lot(head.tsMs(), Math.signum(head.qty()) * rest, head.price()));
            }
        }
        if (left > EPS) {
            open.addLast(new Lot(tsMs, sign * left, price));
        }
    }

    /** Остаток: количество, вложенная стоимость и число открытых партий. */
    public Position position() {
        double qty = 0;
        double cost = 0;
        for (Lot lot : open) {
            qty += lot.qty();
            cost += lot.qty() * lot.price();
        }
        return new Position(qty, cost, open.size());
    }

    /**
     * Зафиксированный результат по сделкам, ЗАКРЫТЫМ в окне.
     *
     * ⚠️ Партия могла быть куплена задолго до начала окна: реализация датируется
     * моментом ЗАКРЫТИЯ. Иначе «реализовано за 3 часа» пришлось бы считать по
     * покупкам, а они прибыли ещё не дали.
     */
    public double realisedSince(long fromMs) {
        double sum = 0;
        for (Realisation r : closed) {
            if (r.tsMs() >= fromMs) {
                sum += r.pnl();
            }
        }
        return sum;
    }

    /** Сколько пар закрылось в окне — знаменатель для «на сделку». */
    public int closedSince(long fromMs) {
        int n = 0;
        for (Realisation r : closed) {
            if (r.tsMs() >= fromMs) {
                n++;
            }
        }
        return n;
    }

    /** Объём, закрытый в окне, в базовой валюте. */
    public double closedQtySince(long fromMs) {
        double sum = 0;
        for (Realisation r : closed) {
            if (r.tsMs() >= fromMs) {
                sum += r.qty();
            }
        }
        return sum;
    }

    public double fees() {
        return fees;
    }

    public List<Lot> openLots() {
        return List.copyOf(open);
    }

    public List<Realisation> realisations() {
        return List.copyOf(closed);
    }
}
