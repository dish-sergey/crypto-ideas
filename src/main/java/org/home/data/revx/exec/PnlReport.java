package org.home.data.revx.exec;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * <h2>Одна книга, а не две</h2>
 *
 * ⚠️ До 04.09.2026 торговая статистика считалась по ОТДЕЛЬНОЙ книге, из которой
 * передачи и затравку выбрасывали. Книга от этого вставала в шорт ровно на
 * размер подаренного (у бота A — на −0.00026055 BTC, 20.8 лота), и «реализовано»
 * превращалось в ставку на направление рынка: обещание «от движения рынка не
 * зависит» не выполнялось. Теперь книга ОДНА, передачи в ней участвуют
 * (позицию они действительно открывают), а разделение идёт по происхождению
 * ПАРЫ — см. {@link FifoLedger}.
 *
 * <h2>Окно «с запуска»</h2>
 *
 * Главная строка — не «за всё время», а от последнего старта процесса: только
 * так видно, торгует ли новая версия лучше прежней. Скользящие окна оставлены
 * рядом, но они ВЛОЖЕННЫЕ (все «от сейчас назад»), и читать их как
 * непересекающиеся периоды нельзя.
 *
 * ⚠️ Комиссии стоят ОТДЕЛЬНОЙ строкой, а не вычитаются из реализованного. У нас
 * мейкерский тариф 0%, и любое ненулевое значение — сигнал, что тариф изменился
 * (док. 91). Спрятав их внутрь, мы бы это заметили не сразу.
 */
public final class PnlReport {

    /** Скользящие окна, за которые интересно смотреть. Часы. */
    private static final int[] WINDOWS = {3, 12, 24, 72, 168};
    private static final String[] LABELS = {"3 ч", "12 ч", "24 ч", "3 дня", "7 дней"};

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneOffset.UTC);

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
        double book = build(journal).position().qty();
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

    /**
     * Единственная книга партий: затравка, передачи и сделки в одном порядке.
     * Затравка и передачи помечены — по этой метке отчёт и разделяет пары.
     */
    private static FifoLedger build(ExecJournal journal) {
        FifoLedger ledger = new FifoLedger();
        ExecJournal.FillRow seed = journal.seed();
        if (seed != null) {
            ledger.add(seed.tsMs(), true, seed.qty(), seed.price(), 0, true);
        }
        for (ExecJournal.FillRow f : journal.fills()) {
            ledger.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee(), f.handover());
        }
        return ledger;
    }

    public static String render(ExecJournal journal, double fair, double lotSize, String base) {
        List<ExecJournal.FillRow> fills = journal.fills();
        ExecJournal.FillRow seed = journal.seed();
        FifoLedger ledger = build(journal);

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

        long now = System.currentTimeMillis();
        ExecJournal.Boot boot = journal.lastBoot();

        // ⚠️ Колонка считает ЗАКРЫТЫЕ ПАРЫ, а не исполнения. Пока называлась
        // «сделок», свежий бот показывал «0 сделок» после двух настоящих
        // покупок — читалось как «ничего не делал», хотя круг просто ещё не
        // замкнулся. Число исполнений печатается отдельной строкой ниже.
        sb.append("Окно      |    пар | реализовано |   на пару |     оборот | передачи\n");
        if (boot != null) {
            sb.append(row("С ЗАПУСКА", ledger, boot.tsMs()));
        }
        for (int i = 0; i < WINDOWS.length; i++) {
            sb.append(row(LABELS[i], ledger, now - WINDOWS[i] * 3600_000L));
        }
        sb.append(row("всего", ledger, 0));

        if (boot != null) {
            Duration up = Duration.ofMillis(Math.max(0, now - boot.tsMs()));
            sb.append(String.format(Locale.ROOT, "%nВерсия запущена %s UTC (%d ч %d мин назад)%n",
                    STAMP.format(Instant.ofEpochMilli(boot.tsMs())), up.toHours(),
                    up.toMinutesPart()));
            // В detail после «|» едет машинная часть для повтора — человеку она
            // не нужна и только загромождает ответ в чате.
            String human = org.home.data.revx.replay.BootParams.human(boot.detail());
            if (!human.isBlank()) {
                sb.append("  ").append(human).append('\n');
            }
            sb.append("Сравнивать версии между собой — по строке «С ЗАПУСКА»: остальные\n");
            sb.append("окна вложенные и захватывают торговлю прежних версий.\n");
        } else {
            sb.append("\nТочки запуска в журнале нет — строка «с запуска» появится\n");
            sb.append("после ближайшего перезапуска.\n");
        }

        long handovers = fills.stream().filter(ExecJournal.FillRow::handover).count();
        sb.append("\nВсего исполнений в журнале: ").append(fills.size())
                .append(", из них передач: ").append(handovers);
        sb.append("\n\nПара — замкнутый круг «купил и продал». Свежекупленный лот пары");
        sb.append("\nещё не образует: он виден в инвентаре и в нереализованном.");
        sb.append("\n\nРеализовано — по парам, где ОБЕ ноги торговые: захват спреда,");
        sb.append("\nот движения рынка не зависит. Передачи — пары, где хоть одна нога");
        sb.append("\nпередача или затравка: это не заработок бота.");
        sb.append("\nНереализовано — переоценка остатка, это ставка на рынок.");
        return sb.toString();
    }

    private static String row(String label, FifoLedger ledger, long from) {
        int closed = ledger.tradingClosedSince(from);
        double realised = ledger.tradingRealisedSince(from);
        double qty = ledger.tradingClosedQtySince(from);
        double handover = ledger.handoverRealisedSince(from);
        return String.format(Locale.ROOT, "%-9s | %6d | %+11.4f | %+9.5f | %.8f | %+8.4f%n",
                label, closed, realised, closed > 0 ? realised / closed : 0, qty, handover);
    }
}
