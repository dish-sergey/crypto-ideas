package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * ВРЕМЯ ПОД ПОЗИЦИЕЙ, СВЕРЕННОЕ ТРЕМЯ СПОСОБАМИ. Команда {@code --revx-hold-check}.
 *
 * <h2>Зачем прибор появился</h2>
 *
 * Отчёт за 11–12.09.2026 построил на одном числе всё: «медиана удержания у бота
 * A — 209 минут, максимум 1239», отсюда риск 52.1 б.п. против захвата 24 и
 * вывод «конструкция не окупает риск ни при каких настройках». Число было
 * получено разовым скриптом, который нигде не сохранился, и воспроизвести его
 * не удалось: прямой пересчёт по журналу бота A даёт медиану 6–54 минуты на
 * всех суточных окнах за 28.08–12.09, а максимум нигде не превышает 948 минут.
 *
 * ⚠️ **Разовый скрипт — не прибор.** Число, которое нельзя пересчитать, нельзя
 * и опровергнуть; оно просто живёт в документах, пока кто-нибудь не усомнится.
 * Поэтому здесь не «пересчёт задним числом», а команда, которую можно запустить
 * на любом журнале и любом окне.
 *
 * <h2>Три способа, и почему их должно быть три</h2>
 *
 * <ol>
 *   <li><b>Прямой замер (FIFO)</b> — как считает {@link FifoLedger}: что куплено
 *       первым, то первым и продано;</li>
 *   <li><b>Прямой замер (LIFO)</b> — обратное правило. Истинное время под
 *       позицией лежит между ними: если они расходятся втрое, величина вообще
 *       не определена однозначно и любой вывод по ней преждевременен;</li>
 *   <li><b>Закон Литтла</b> — {@code средний инвентарь = темп покупок ×
 *       среднее время держания}. Это тождество, а не модель, и оно верно при
 *       любом распределении. Инвентарь берётся НЕ реконструкцией по сделкам, а
 *       прямо из {@code exec_quote.inventory}, то есть тем, что бот видел у себя
 *       на каждом тике. Поэтому третий способ независим от первых двух.</li>
 * </ol>
 *
 * Расхождение первых двух со третьим — единственный способ поймать ошибку
 * сопоставления, а расхождение FIFO с LIFO — её величину.
 *
 * <h2>Три политики по передачам</h2>
 *
 * Затравка и передачи между ботами ({@code status='handover'}) позицию
 * ОТКРЫВАЮТ, но заработком не являются. Прибор считает все три варианта, потому
 * что от выбора зависит ответ:
 * <ul>
 *   <li>{@code все} — передачи считаются обычными сделками;</li>
 *   <li>{@code без пар} — передачи в книгу входят, но пары с их участием из
 *       статистики выкинуты (так делает {@link FifoLedger#holdMinutes});</li>
 *   <li>{@code мимо} — передачи в книгу не входят вовсе.</li>
 * </ul>
 * ⚠️ Ключевая ловушка именно здесь: лот затравки встаёт в ГОЛОВУ очереди FIFO и
 * задерживает каждый настоящий лот за собой ровно на время оборота затравки.
 * Пара с ним из статистики выкидывается, а задержка остаётся.
 */
@Component
@Lazy
public class HoldCheck {

    private static final Logger log = LoggerFactory.getLogger(HoldCheck.class);

    /** Закрытая пара: когда открылась, когда закрылась, была ли передачей. */
    private record Pair(long openedMs, long closedMs, boolean handover) {
        double minutes() {
            return (closedMs - openedMs) / 60_000.0;
        }
    }

    /** Партия в очереди. */
    private static final class Lot {
        final long tsMs;
        double qty;
        final boolean handover;

        Lot(long tsMs, double qty, boolean handover) {
            this.tsMs = tsMs;
            this.qty = qty;
            this.handover = handover;
        }
    }

    private static final double EPS = 1e-15;

    public void run(String journalPath, long fromMs, long toMs,
                    double sigmaBpPerMin, double offsetBp, String out) {
        List<ExecJournal.FillRow> fills;
        try (ExecJournal journal = ExecJournal.readOnly(journalPath)) {
            fills = journal.fills();
        }
        if (fills.isEmpty()) {
            log.warn("в журнале {} нет исполнений", journalPath);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Время под позицией: сверка трёх способов\n\n");
        sb.append("журнал: `").append(journalPath).append("`\n\n");
        sb.append("окно: ").append(Instant.ofEpochMilli(fromMs)).append(" .. ")
                .append(Instant.ofEpochMilli(toMs)).append("\n\n");
        sb.append("⚠️ Книга партий строится с НАЧАЛА журнала, а в статистику идут пары,\n")
                .append("ЗАКРЫВШИЕСЯ в окне: остаток сегодня объясняется покупками произвольной\n")
                .append("давности, и обрезать историю по краю окна нельзя.\n\n");

        sb.append("## 1–2. Прямой замер: FIFO против LIFO\n\n");
        sb.append("| правило | передачи | кругов | медиана | среднее | p90 | максимум |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|\n");
        double fifoMedian = -1;
        double fifoMean = -1;
        for (boolean fifo : new boolean[]{true, false}) {
            for (String policy : new String[]{"все", "без пар", "мимо"}) {
                List<Pair> pairs = match(fills, fifo, "мимо".equals(policy));
                List<Double> held = new ArrayList<>();
                for (Pair p : pairs) {
                    if (p.closedMs() < fromMs || p.closedMs() >= toMs) {
                        continue;
                    }
                    if ("без пар".equals(policy) && p.handover()) {
                        continue;
                    }
                    held.add(p.minutes());
                }
                Collections.sort(held);
                sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s |%n",
                        fifo ? "FIFO" : "LIFO", policy, held.size(),
                        fmt(quantile(held, 0.5)), fmt(mean(held)),
                        fmt(quantile(held, 0.9)), fmt(quantile(held, 1.0))));
                if (fifo && "все".equals(policy)) {
                    fifoMedian = quantile(held, 0.5);
                    fifoMean = mean(held);
                }
            }
        }
        sb.append("\n⚠️ Истинное время под позицией лежит МЕЖДУ FIFO и LIFO. ")
                .append("Расхождение больше чем вдвое означает, что величина не определена\n")
                .append("однозначно, и выводы по ней преждевременны.\n\n");

        sb.append(little(journalPath, fills, fromMs, toMs, fifoMedian, fifoMean));
        sb.append(ratio(sigmaBpPerMin, offsetBp, fifoMedian, fifoMean));

        write(out, sb.toString());
        log.info("\n{}", sb);
    }

    /**
     * Сопоставление ног. FIFO берёт партию из головы очереди, LIFO — из хвоста;
     * всё остальное одинаково, включая разбиение частичных объёмов.
     *
     * Продажа сверх инвентаря открывает ОТРИЦАТЕЛЬНУЮ партию (как в
     * {@link FifoLedger}): спот-бот в шорт уйти не должен, но если позиция
     * разъехалась с площадкой, молча терять сделки хуже, чем увидеть короткую
     * партию в отчёте.
     */
    private static List<Pair> match(List<ExecJournal.FillRow> fills, boolean fifo,
                                    boolean skipHandover) {
        Deque<Lot> open = new ArrayDeque<>();
        List<Pair> out = new ArrayList<>();
        double sign = 0;
        for (ExecJournal.FillRow f : fills) {
            if (skipHandover && f.handover()) {
                continue;
            }
            double left = f.qty();
            double mine = f.buy() ? 1 : -1;
            while (left > EPS && !open.isEmpty() && sign * mine < 0) {
                Lot lot = fifo ? open.peekFirst() : open.peekLast();
                double take = Math.min(left, Math.abs(lot.qty));
                out.add(new Pair(lot.tsMs, f.tsMs(), lot.handover || f.handover()));
                lot.qty -= Math.copySign(take, lot.qty);
                left -= take;
                if (Math.abs(lot.qty) <= EPS) {
                    if (fifo) {
                        open.pollFirst();
                    } else {
                        open.pollLast();
                    }
                    if (open.isEmpty()) {
                        sign = 0;
                    }
                }
            }
            if (left > EPS) {
                open.addLast(new Lot(f.tsMs(), mine * left, f.handover()));
                sign = mine;
            }
        }
        return out;
    }

    /**
     * ЗАКОН ЛИТТЛА: средний инвентарь = темп покупок × среднее время держания.
     *
     * Инвентарь берётся из {@code exec_quote.inventory} — это то, что бот видел
     * у себя на каждом тике, а не реконструкция по сделкам. Поэтому проверка
     * независима от правила сопоставления: если прямой замер разойдётся с
     * Литтлом, ошибка в сопоставлении, а не в данных.
     *
     * Среднее взвешивается ВРЕМЕНЕМ между тиками, а не числом тиков: тики идут
     * неравномерно, и простое среднее по строкам дало бы вес плотным участкам.
     */
    private String little(String journalPath, List<ExecJournal.FillRow> fills,
                          long fromMs, long toMs, double fifoMedian, double fifoMean) {
        double area = 0;
        double emptyMs = 0;
        double span = 0;
        long prev = -1;
        double prevInv = 0;
        // ⚠️ ЛОТ БЕРЁТСЯ ПО ОКНУ, А НЕ ПО ВСЕМУ ЖУРНАЛУ. Он менялся: у бота A
        // 1.255e-05 (лот $1) до 09.09 и 3.6e-05 (лот $3) после. Медиана по всей
        // истории дала бы инвентарь 2.50 лота вместо 0.86 и завысила бы
        // держание по Литтлу втрое — то есть прибор сам изобрёл бы расхождение,
        // которое пришёл искать.
        double lot = medianQty(fills.stream()
                .filter(f -> f.tsMs() >= fromMs && f.tsMs() < toMs).toList());
        if (!(lot > 0)) {
            lot = medianQty(fills);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(journalPath).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, inventory FROM exec_quote WHERE ts_ms >= ? AND ts_ms < ?"
                             + " ORDER BY ts_ms")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong(1);
                    double inv = rs.getDouble(2);
                    if (prev > 0) {
                        // ⚠️ Разрывы больше пяти минут не считаем: бот стоял, а
                        // не держал позицию всё это время. Иначе одна остановка
                        // на ночь перевесит все настоящие тики окна.
                        double dt = Math.min(ts - prev, 300_000);
                        area += prevInv * dt;
                        span += dt;
                        if (prevInv < lot * 0.5) {
                            emptyMs += dt;
                        }
                    }
                    prev = ts;
                    prevInv = inv;
                }
            }
        } catch (Exception e) {
            return "\n## 3. Закон Литтла\n\nне прочитались котировки: " + e.getMessage() + "\n";
        }
        if (span <= 0 || lot <= 0) {
            return "\n## 3. Закон Литтла\n\nнет тиков в окне\n";
        }
        long buys = fills.stream()
                .filter(f -> f.buy() && f.tsMs() >= fromMs && f.tsMs() < toMs).count();
        double meanInvLots = area / span / lot;
        double spanMin = span / 60_000.0;
        double impliedMin = buys > 0 ? meanInvLots * spanMin / buys : -1;

        StringBuilder sb = new StringBuilder("\n## 3. Закон Литтла (независимая сверка)\n\n");
        sb.append(String.format(Locale.ROOT,
                "| величина | значение |%n|---|---:|%n"
                        + "| лот (медиана объёма сделки) | %.3e |%n"
                        + "| средний инвентарь, взвешенный по времени | %.2f лота |%n"
                        + "| доля времени «продать нечего» | %.0f%% |%n"
                        + "| покупок в окне | %d |%n"
                        + "| окно с тиками | %.1f ч |%n"
                        + "| **держание по Литтлу (среднее)** | **%.1f мин** |%n"
                        + "| держание прямым замером (FIFO, среднее) | %.1f мин |%n"
                        + "| держание прямым замером (FIFO, медиана) | %.1f мин |%n",
                lot, meanInvLots, 100 * emptyMs / span, buys, spanMin / 60,
                impliedMin, fifoMean, fifoMedian));
        double rel = fifoMean > 0 && impliedMin > 0 ? fifoMean / impliedMin : -1;
        sb.append(String.format(Locale.ROOT,
                "%nПрямой замер / Литтл = **%.2f**. %s%n",
                rel, rel > 0 && rel > 0.6 && rel < 1.7
                        ? "Сходится: прибор исправен."
                        : "⚠️ РАСХОЖДЕНИЕ. Пока оно не объяснено, числа отношения "
                                + "захват/риск на этом журнале не читаются."));
        sb.append("\n⚠️ Литтл связывает СРЕДНЕЕ держание, а не медиану: у времени под\n")
                .append("позицией правый хвост, поэтому среднее заведомо больше медианы.\n")
                .append("Сравнивать надо со средним, и только потом смотреть на медиану.\n");
        return sb.toString();
    }

    /** Отношение захват/риск при обоих вариантах T — чтобы видеть цену выбора. */
    private static String ratio(double sigmaBpPerMin, double offsetBp,
                                double medianMin, double meanMin) {
        if (!(sigmaBpPerMin > 0) || !(offsetBp > 0)) {
            return "\n(отношение не считается: не заданы --sigma и --offset-bp)\n";
        }
        StringBuilder sb = new StringBuilder("\n## Отношение захват/риск при обоих T\n\n");
        sb.append(String.format(Locale.ROOT,
                "захват = 2δ = %.1f б.п., σ = %.2f б.п./мин%n%n", 2 * offsetBp, sigmaBpPerMin));
        sb.append("| T | риск σ√T | отношение |\n|---|---:|---:|\n");
        for (double[] t : new double[][]{{medianMin, 0}, {meanMin, 1}}) {
            if (!(t[0] > 0)) {
                continue;
            }
            double risk = sigmaBpPerMin * Math.sqrt(t[0]);
            sb.append(String.format(Locale.ROOT, "| %s (%.0f мин) | %.1f | **%.2f** |%n",
                    t[1] == 0 ? "медиана" : "среднее", t[0], risk, 2 * offsetBp / risk));
        }
        sb.append("\n⚠️ Медиана занижает риск: длинные круги отобраны по неблагоприятному\n")
                .append("исходу — круг длинный ИМЕННО потому, что цена ушла и встречная\n")
                .append("заявка не исполнилась. Поэтому σ√T по медиане — верхняя оценка\n")
                .append("отношения, а не его значение.\n");
        return sb.toString();
    }

    private static double medianQty(List<ExecJournal.FillRow> fills) {
        List<Double> q = new ArrayList<>();
        for (ExecJournal.FillRow f : fills) {
            q.add(f.qty());
        }
        Collections.sort(q);
        return quantile(q, 0.5);
    }

    private static double quantile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int i = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
        return sorted.get(i);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return -1;
        }
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s / values.size();
    }

    private static String fmt(double v) {
        return v < 0 ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    private void write(String out, String text) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, text, StandardCharsets.UTF_8);
            log.info("отчёт записан: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("не записался отчёт {}: {}", out, e.getMessage());
        }
    }
}
