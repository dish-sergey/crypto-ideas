package org.home.data.revx.exec;

import java.util.List;
import java.util.Locale;

/**
 * Отчёт о результате бота: что уже зафиксировано, что висит, и по какой цене.
 *
 * <h2>Зачем</h2>
 *
 * На Revolut видна общая картина по СЧЁТУ, а ботов на нём три, и по каждому в
 * отдельности не понятно ничего. Прежний {@code /stats} показывал число
 * исполнений и инвентарь — этого мало: нельзя отличить «заработал и держит
 * купленное» от «потерял, но переоценка вытянула».
 *
 * <h2>Что считается и как</h2>
 *
 * Партии закрываются по {@link FifoLedger} — что куплено первым, то первым и
 * продано. Отсюда два разных числа:
 *
 * <ul>
 *   <li><b>реализовано</b> — по ЗАКРЫТЫМ парам. Не зависит от того, куда сходил
 *       рынок, и потому это и есть измеренный захват спреда;</li>
 *   <li><b>нереализовано</b> — переоценка остатка по текущей цене. Это ставка на
 *       рынок, а не результат торговли, и складывать их в одно число нельзя.</li>
 * </ul>
 *
 * Реализация датируется моментом ЗАКРЫТИЯ, а не покупки: иначе «за три часа»
 * считалось бы по входам, которые прибыли ещё не дали.
 *
 * ⚠️ Комиссии стоят ОТДЕЛЬНОЙ строкой, а не вычитаются из реализованного. У нас
 * мейкерский тариф 0%, и любое ненулевое значение — сигнал, что тариф изменился
 * (док. 91). Спрятав их внутрь, мы бы это заметили не сразу.
 */
public final class PnlReport {

    /** Окна, за которые интересно смотреть. Часы. */
    private static final int[] WINDOWS = {3, 12, 24, 72, 168};
    private static final String[] LABELS = {"3 ч", "12 ч", "24 ч", "3 дня", "7 дней"};

    private PnlReport() {
    }

    /**
     * @param tracked позиция, которую бот ведёт САМ (по своим исполнениям).
     *                Печатается рядом с книгой партий не для красоты: 04.09.2026
     *                у бота A журнал дал −0.00010055 BTC при затравке 0, а
     *                собственный счётчик показывал +0.000075 — расхождение
     *                0.000175, то есть четырнадцать лотов. Счёт по одному
     *                источнику этого не покажет никогда.
     */
    public static String render(ExecJournal journal, double fair, double lotSize, String base,
                                double tracked) {
        String body = render(journal, fair, lotSize, base);
        FifoLedger ledger = new FifoLedger();
        ExecJournal.FillRow seed = journal.seed();
        if (seed != null) {
            ledger.add(seed.tsMs(), true, seed.qty(), seed.price(), 0);
        }
        for (ExecJournal.FillRow f : journal.fills()) {
            ledger.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee());
        }
        double book = ledger.position().qty();
        double diff = tracked - book;
        StringBuilder sb = new StringBuilder(body);
        sb.append(String.format(Locale.ROOT, "%n%nСверка:%n"));
        sb.append(String.format(Locale.ROOT, "  по журналу сделок: %.8f%n", book));
        sb.append(String.format(Locale.ROOT, "  свой счётчик бота: %.8f%n", tracked));
        if (Math.abs(diff) > (lotSize > 0 ? lotSize / 2 : 1e-12)) {
            sb.append(String.format(Locale.ROOT,
                    "  ⚠ РАСХОЖДЕНИЕ %+.8f (%.1f лота)%n", diff,
                    lotSize > 0 ? diff / lotSize : 0));
            sb.append("  Счёт трёх ботов идёт по ОДНОМУ счёту площадки: заявка одного\n");
            sb.append("  может исполниться против остатка, набранного другим. Позиции\n");
            sb.append("  ботов — бухгалтерская условность, и они умеют разъезжаться.\n");
        } else {
            sb.append("  расхождения нет\n");
        }
        return sb.toString();
    }

    public static String render(ExecJournal journal, double fair, double lotSize, String base) {
        FifoLedger ledger = new FifoLedger();
        ExecJournal.FillRow seed = journal.seed();
        if (seed != null) {
            ledger.add(seed.tsMs(), true, seed.qty(), seed.price(), 0);
        }
        List<ExecJournal.FillRow> fills = journal.fills();
        for (ExecJournal.FillRow f : fills) {
            ledger.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee());
        }

        FifoLedger.Position pos = ledger.position();
        double avg = pos.averagePrice();
        double unrealised = pos.unrealised(fair);
        double lots = lotSize > 0 ? pos.qty() / lotSize : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("P&L (FIFO: что куплено первым, продано первым)\n\n");
        sb.append(String.format(Locale.ROOT, "Инвентарь: %.8f %s = %.1f лота%n",
                pos.qty(), base, lots));
        sb.append(String.format(Locale.ROOT, "Средняя цена входа: %.2f%n", avg));
        sb.append(String.format(Locale.ROOT, "Текущая цена: %.2f (%+.3f%%)%n",
                fair, avg > 0 && fair > 0 ? (fair / avg - 1) * 100 : 0));
        sb.append(String.format(Locale.ROOT, "Открытых партий: %d%n", pos.lots()));
        sb.append(String.format(Locale.ROOT, "**Нереализовано: %+.4f USDC**%n", unrealised));
        if (seed != null) {
            sb.append(String.format(Locale.ROOT,
                    "  (затравка %.8f по %.2f — цена условная, взята рыночной на момент запуска)%n",
                    seed.qty(), seed.price()));
        }
        sb.append(String.format(Locale.ROOT, "Комиссии за всё время: %.4f%n%n", ledger.fees()));

        // Вторая книга — ТОЛЬКО по торговым сделкам. Передачи инвентаря между
        // ботами в ней не участвуют: иначе каждый перезапуск впрыскивает в
        // «реализовано» и в «на сделку» фальшивое исполнение по справедливой
        // цене, и сравнивать ботов между собой станет нечем.
        FifoLedger trading = new FifoLedger();
        for (ExecJournal.FillRow f : fills) {
            if (!f.handover()) {
                trading.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee());
            }
        }

        sb.append("Окно   | сделок | реализовано | на сделку | оборот | передачи\n");
        long now = System.currentTimeMillis();
        for (int i = 0; i < WINDOWS.length; i++) {
            long from = now - WINDOWS[i] * 3600_000L;
            int closed = trading.closedSince(from);
            double realised = trading.realisedSince(from);
            double qty = trading.closedQtySince(from);
            // Передачи — разница между полной книгой и торговой.
            double handover = ledger.realisedSince(from) - realised;
            sb.append(String.format(Locale.ROOT, "%-6s | %6d | %+11.4f | %+9.5f | %.8f | %+8.4f%n",
                    LABELS[i], closed, realised,
                    closed > 0 ? realised / closed : 0, qty, handover));
        }

        long handovers = fills.stream().filter(ExecJournal.FillRow::handover).count();
        sb.append("\nВсего исполнений в журнале: ").append(fills.size())
                .append(", из них передач: ").append(handovers);
        sb.append("\n\nРеализовано — по ЗАКРЫТЫМ парам, от движения рынка не зависит.");
        sb.append("\nНереализовано — переоценка остатка, это ставка на рынок, а не заработок.");
        return sb.toString();
    }
}
