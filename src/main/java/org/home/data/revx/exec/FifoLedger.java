package org.home.data.revx.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Книга партий по правилу FIFO: что куплено первым, то первым и продано.
 *
 * <h2>Зачем отдельный класс</h2>
 *
 * У бота до сих пор был только один P&amp;L — разность «касса плюс инвентарь по
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
 *
 * <h2>Передачи внутри книги, а не мимо неё</h2>
 *
 * ⚠️ 04.09.2026 передачи инвентаря между ботами и затравку ИЗ книги выкидывали —
 * чтобы перезапуск не впрыскивал в статистику фальшивое исполнение. Лекарство
 * оказалось хуже болезни: у бота A подаренные +0.00026055 BTC в книгу не попали,
 * зато их продажи попали, и книга встала в шорт ровно на −0.00026055. Ни одной
 * честной пары «купил-продал» в ней не осталось — каждое «закрытие» было
 * покрытием фантомного шорта, и обещание «реализовано от движения рынка не
 * зависит» стало ложью: +0.2587 за три часа оказались падением BTC на 2.48%
 * против этого шорта, а не захватом спреда.
 *
 * Поэтому передача теперь кладётся В книгу (позицию она действительно
 * открывает), но партия ПОМНИТ своё происхождение. Пара считается «передачей»,
 * если хоть одна её нога — передача; такие пары в торговую статистику не
 * входят, но и книгу больше не перекашивают.
 */
public final class FifoLedger {

    /**
     * Открытая партия: сколько и по какой цене. Отрицательное {@code qty} = шорт.
     *
     * @param handover партия открыта передачей или затравкой, а не сделкой с рынком
     */
    public record Lot(long tsMs, double qty, double price, boolean handover) {
    }

    /**
     * Закрытая пара «вход — выход». {@code pnl} уже за вычетом комиссий обеих ног.
     *
     * @param handover хоть одна нога пары — передача: заработком бота это не является
     */
    public record Realisation(long tsMs, double qty, double entry, double exit, double pnl,
                              boolean handover) {
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

    /** Учесть рыночное исполнение. */
    public void add(long tsMs, boolean buy, double qty, double price, double fee) {
        add(tsMs, buy, qty, price, fee, false);
    }

    /**
     * Учесть исполнение.
     *
     * @param qty      всегда положительное количество; сторону задаёт {@code buy}
     * @param fee      комиссия сделки в валюте котировки; вычитается из результата
     * @param handover это передача инвентаря или затравка, а не сделка с рынком
     */
    public void add(long tsMs, boolean buy, double qty, double price, double fee,
                    boolean handover) {
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
            closed.add(new Realisation(tsMs, take, head.price(), price, pnl,
                    handover || head.handover()));
            left -= take;
            double rest = Math.abs(head.qty()) - take;
            open.removeFirst();
            if (rest > EPS) {
                open.addFirst(new Lot(head.tsMs(), Math.signum(head.qty()) * rest, head.price(),
                        head.handover()));
            }
        }
        if (left > EPS) {
            open.addLast(new Lot(tsMs, sign * left, price, handover));
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
     * Зафиксированный результат по парам, ЗАКРЫТЫМ в окне.
     *
     * ⚠️ Партия могла быть куплена задолго до начала окна: реализация датируется
     * моментом ЗАКРЫТИЯ. Иначе «реализовано за 3 часа» пришлось бы считать по
     * покупкам, а они прибыли ещё не дали.
     */
    public double realisedSince(long fromMs) {
        return sum(fromMs, r -> true, Realisation::pnl);
    }

    /** Результат по парам, где ОБЕ ноги — сделки с рынком. Это и есть захват спреда. */
    public double tradingRealisedSince(long fromMs) {
        return sum(fromMs, r -> !r.handover(), Realisation::pnl);
    }

    /** Результат по парам, где хоть одна нога — передача или затравка. */
    public double handoverRealisedSince(long fromMs) {
        return sum(fromMs, Realisation::handover, Realisation::pnl);
    }

    /** Сколько пар закрылось в окне — знаменатель для «на сделку». */
    public int closedSince(long fromMs) {
        return (int) Math.round(sum(fromMs, r -> true, r -> 1));
    }

    /** Сколько ЧИСТО ТОРГОВЫХ пар закрылось в окне. */
    public int tradingClosedSince(long fromMs) {
        return (int) Math.round(sum(fromMs, r -> !r.handover(), r -> 1));
    }

    /** Объём, закрытый в окне, в базовой валюте. */
    public double closedQtySince(long fromMs) {
        return sum(fromMs, r -> true, Realisation::qty);
    }

    /** Объём чисто торговых пар, закрытых в окне. */
    public double tradingClosedQtySince(long fromMs) {
        return sum(fromMs, r -> !r.handover(), Realisation::qty);
    }

    private double sum(long fromMs, Predicate<Realisation> keep, ToDoubleFunction<Realisation> f) {
        double total = 0;
        for (Realisation r : closed) {
            if (r.tsMs() >= fromMs && keep.test(r)) {
                total += f.applyAsDouble(r);
            }
        }
        return total;
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
