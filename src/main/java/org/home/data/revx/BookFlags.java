package org.home.data.revx;

/**
 * Битовые флаги качества снимка книги (колонка revx_book.flags).
 *
 * Флаг — не «починили», а «зафиксировали»: ТЗ §8 запрещает молча исправлять
 * аномалии, они обязаны дойти до отчёта. Симулятор сам решает, что фильтровать:
 * снимок с CROSSED в расчёт не идёт вообще, со SKEW_EXCEEDED — не идёт в базис,
 * но остаётся в статистике спреда.
 */
public final class BookFlags {

    /** asks пришли не по возрастанию цены (штатное поведение API — см. ниже). */
    public static final int ASKS_REORDERED = 1;
    /** bids пришли не по убыванию цены. */
    public static final int BIDS_REORDERED = 1 << 1;
    /** best_ask <= best_bid — перекрещенная книга, снимок не пишется. */
    public static final int CROSSED = 1 << 2;
    /** Расхождение времён получения двух ног больше порога (ТЗ §3.3). */
    public static final int SKEW_EXCEEDED = 1 << 3;
    /** Уровней меньше запрошенной глубины. */
    public static final int PARTIAL_DEPTH = 1 << 4;
    /** Пустая сторона книги. */
    public static final int EMPTY_SIDE = 1 << 5;

    private BookFlags() {
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }

    public static String describe(int flags) {
        StringBuilder sb = new StringBuilder();
        if (has(flags, ASKS_REORDERED)) sb.append("asks_reordered ");
        if (has(flags, BIDS_REORDERED)) sb.append("bids_reordered ");
        if (has(flags, CROSSED)) sb.append("crossed ");
        if (has(flags, SKEW_EXCEEDED)) sb.append("skew ");
        if (has(flags, PARTIAL_DEPTH)) sb.append("partial ");
        if (has(flags, EMPTY_SIDE)) sb.append("empty_side ");
        return sb.toString().trim();
    }
}
