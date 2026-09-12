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
import java.util.Map;
import java.util.TreeMap;

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

    /**
     * Закрытая пара: когда открылась, когда закрылась, была ли передачей.
     *
     * @param entry цена входной ноги, {@code exit} — выходной; нужны, чтобы
     *        считать результат круга в базисных пунктах, а не только время
     * @param buyFirst вход был покупкой (обычный спотовый круг)
     */
    private record Pair(long openedMs, long closedMs, boolean handover,
                        double entry, double exit, boolean buyFirst) {
        double minutes() {
            return (closedMs - openedMs) / 60_000.0;
        }

        /** Результат круга, б.п.: сколько заработано на паре ног. */
        double bp() {
            if (!(entry > 0) || !(exit > 0)) {
                return 0;
            }
            return (buyFirst ? (exit - entry) / entry : (entry - exit) / entry) * 10_000;
        }
    }

    /** Партия в очереди. */
    private static final class Lot {
        final long tsMs;
        double qty;
        final boolean handover;
        final double price;

        Lot(long tsMs, double qty, boolean handover, double price) {
            this.tsMs = tsMs;
            this.qty = qty;
            this.handover = handover;
            this.price = price;
        }
    }

    private static final double EPS = 1e-15;

    public void run(String journalPath, long fromMs, long toMs,
                    double sigmaBpPerMin, double offsetBp, double takerCostBp, String out) {
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
        sb.append(skew(journalPath, fromMs, toMs));
        sb.append(takerExit(journalPath, fills, fromMs, toMs, takerCostBp));

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
                out.add(new Pair(lot.tsMs, f.tsMs(), lot.handover || f.handover(),
                        lot.price, f.price(), lot.qty > 0));
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
                open.addLast(new Lot(f.tsMs(), mine * left, f.handover(), f.price()));
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

    /**
     * КУДА СКОС ДВИГАЕТ ЦЕНУ НА САМОМ ДЕЛЕ — по живым тикам, а не по замыслу.
     *
     * Формула ({@code Quoter.skew} и {@code Quoter.quote}):
     * <pre>
     * skew = (инвентарь/потолок − цель) / max(цель, 1−цель),  зажат в [−1, 1]
     * бид  = fair · (1 − отступ − k·skew)      → расстояние бида = отступ + k·skew
     * аск  = fair · (1 + отступ − k·skew)      → расстояние аска = отступ − k·skew
     * </pre>
     *
     * ⚠️ Скос вычитается из ОБЕИХ цен, поэтому НИЖЕ цели он работает не
     * разгрузкой, а ПРИМАНКОЙ: {@code skew} отрицателен, бид подтягивается к
     * справедливой цене, аск от неё отодвигается. Задуман он был как страховка
     * от переполнения, а бо́льшую часть времени бот находится ниже цели, и
     * действие у него ровно обратное задуманному.
     *
     * Цена вопроса зависит от цели квадратично через знаменатель: при цели 0.3
     * пустой бот даёт {@code skew = −0.43}, при цели 0.5 — {@code −1.0}, то есть
     * притягивание вдвое сильнее. Это важно, потому что боты d, e, f работают
     * с целью 0.5 и самыми узкими отступами (6–8 б.п.).
     *
     * Графа «бид выше fair» — доля тиков, где {@code отступ + k·skew < 0}, то
     * есть котировщик целится покупать ДОРОЖЕ справедливой цены, и от сделки
     * по такой цене спасает только зажим по книге.
     */
    private String skew(String journalPath, long fromMs, long toMs) {
        org.home.data.revx.replay.BootParams bp;
        try (ExecJournal j = ExecJournal.readOnly(journalPath)) {
            ExecJournal.Boot boot = j.lastBoot();
            bp = boot == null ? null : org.home.data.revx.replay.BootParams.parse(boot.detail());
        }
        if (bp == null || !(bp.inventoryCap() > 0)) {
            return "\n## Скос\n\nв журнале нет события `boot` с машинной частью — настройки неизвестны\n";
        }
        double cap = bp.inventoryCap();
        double target = bp.skewTarget();
        double k = bp.skewK();
        double offBp = bp.offset() * 10_000;
        double span = Math.max(target, 1 - target);

        long ticks = 0;
        long below = 0;
        long crossing = 0;
        double shiftSum = 0;
        List<Double> shifts = new ArrayList<>();
        // ⚠️ ФАКТИЧЕСКИЕ ЦЕНЫ, А НЕ ВЫВЕДЕННЫЕ ИЗ ФОРМУЛЫ. Бот пишет в каждый
        // тик и справедливую цену, и то, что он реально выставил, — значит
        // арифметику скоса можно не выводить, а СВЕРИТЬ. Если посчитанное
        // расстояние разойдётся с записанным, ошибка в разборе, а не в боте.
        // ⚠️ СРАВНИВАТЬ НАДО ПО ОДНИМ И ТЕМ ЖЕ ТИКАМ. Аск выставлен не всегда:
        // при пустом инвентаре продавать нечего, и строки с ценой аска — это
        // ровно те тики, где инвентарь ЕСТЬ, то есть где скос заведомо меньше
        // по модулю. Считать формулу по всем тикам, а журнал по этой выборке
        // значит сравнивать разные условия: у бота A аск есть в 42693 тиках из
        // 83507, и «расхождение» 1.6 б.п. целиком объясняется отбором.
        List<Double> bidBp = new ArrayList<>();
        List<Double> askBp = new ArrayList<>();
        List<Double> bidModel = new ArrayList<>();
        List<Double> askModel = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(journalPath).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT inventory, fair, bid, ask FROM exec_quote"
                             + " WHERE ts_ms >= ? AND ts_ms < ?")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double fair = rs.getDouble(2);
                    double bid = rs.getDouble(3);
                    double ask = rs.getDouble(4);
                    double s = Math.max(-1, Math.min(1, (rs.getDouble(1) / cap - target) / span));
                    if (fair > 0 && bid > 0) {
                        bidBp.add((fair - bid) / fair * 10_000);
                        bidModel.add(offBp + k * s * 10_000);
                    }
                    if (fair > 0 && ask > 0) {
                        askBp.add((ask - fair) / fair * 10_000);
                        askModel.add(offBp - k * s * 10_000);
                    }
                    // Сдвиг ОБЕИХ цен: скос вычитается из них, поэтому знак
                    // здесь обратный знаку skew. Плюс = цены вверх = набор.
                    double shiftBp = -k * s * 10_000;
                    ticks++;
                    shiftSum += shiftBp;
                    shifts.add(shiftBp);
                    if (s < 0) {
                        below++;
                    }
                    if (offBp - shiftBp < 0) {
                        crossing++;
                    }
                }
            }
        } catch (Exception e) {
            return "\n## Скос\n\nне прочитались котировки: " + e.getMessage() + "\n";
        }
        if (ticks == 0) {
            return "\n## Скос\n\nнет тиков в окне\n";
        }
        Collections.sort(shifts);
        StringBuilder sb = new StringBuilder("\n## Скос: куда он двигает цену на живых тиках\n\n");
        sb.append(String.format(Locale.ROOT,
                "настройка: отступ %.1f б.п., k = %.4f, цель %.0f%% потолка "
                        + "(знаменатель %.2f)%n%n", offBp, k, target * 100, span));
        sb.append(String.format(Locale.ROOT,
                "| величина | значение |%n|---|---:|%n"
                        + "| тиков в окне | %d |%n"
                        + "| **доля времени НИЖЕ цели (скос = приманка)** | **%.0f%%** |%n"
                        + "| сдвиг обеих цен: медиана | %+.2f б.п. |%n"
                        + "| сдвиг: 10%% / 90%% | %+.2f / %+.2f б.п. |%n"
                        + "| среднее расстояние бида от fair | %.2f б.п. |%n"
                        + "| среднее расстояние аска от fair | %.2f б.п. |%n"
                        + "| ⚠️ бид целится ВЫШЕ справедливой цены | %.1f%% тиков |%n",
                ticks, 100.0 * below / ticks,
                quantile(shifts, 0.5), quantile(shifts, 0.1), quantile(shifts, 0.9),
                offBp - shiftSum / ticks, offBp + shiftSum / ticks,
                100.0 * crossing / ticks));
        sb.append("\nПлюс в сдвиге = обе цены идут ВВЕРХ, то есть бот НАБИРАЕТ: бид ближе к\n")
                .append("справедливой цене, аск дальше. Минус = разгрузка. Задуман скос ради\n")
                .append("второго, а живёт бот преимущественно в первом.\n");

        Collections.sort(bidBp);
        Collections.sort(askBp);
        Collections.sort(bidModel);
        Collections.sort(askModel);
        sb.append("\n**Сверка с фактически выставленными ценами** — по ОДНИМ И ТЕМ ЖЕ тикам:\n\n");
        sb.append(String.format(Locale.ROOT,
                "| сторона | формула (медиана) | журнал (медиана) | тиков с ценой | доля тиков |%n"
                        + "|---|---:|---:|---:|---:|%n"
                        + "| бид | %s б.п. | **%s** | %d | %.0f%% |%n"
                        + "| аск | %s б.п. | **%s** | %d | %.0f%% |%n",
                fmt(quantile(bidModel, 0.5)), fmt(quantile(bidBp, 0.5)), bidBp.size(),
                100.0 * bidBp.size() / ticks,
                fmt(quantile(askModel, 0.5)), fmt(quantile(askBp, 0.5)), askBp.size(),
                100.0 * askBp.size() / ticks));
        sb.append("\n⚠️ Доля тиков с аском — это и есть «половина конструкции работает»:\n")
                .append("при пустом инвентаре продавать нечего, аска в книге НЕТ, и все\n")
                .append("средние по аску посчитаны на выборке, где инвентарь есть.\n")
                .append("\nОстаточное расхождение формулы с журналом — зажим по книге и по тику\n")
                .append("плюс порог перевыставления: формула отвечает «куда бот целился»,\n")
                .append("журнал — «что стояло в книге».\n");
        return sb.toString();
    }

    /**
     * ТЕЙКЕРСКИЙ ВЫХОД ПО ВОЗРАСТУ КРУГА, оценённый по хвосту, а не по среднему.
     *
     * <h2>Зачем отдельная мерка</h2>
     *
     * Отношение {@code 2δ/σ√T} эту конструкцию одобрить НЕ МОЖЕТ по построению,
     * и это не мнение, а арифметика. Выход тейкером меняет ЧИСЛИТЕЛЬ (захват
     * превращается в минус полуспред минус комиссия), а убыток, от которого он
     * страхует, сидит в ЗНАМЕНАТЕЛЕ — в {@code σ√T}, где его уменьшение ничего
     * не даёт. Поэтому прежний вывод «отношение растёт монотонно с таймером,
     * оптимум — не выходить вовсе» был свойством мерки, а не конструкции.
     *
     * Здесь считается то, ради чего выход и нужен: <b>условный убыток худших
     * 10% кругов (CVaR)</b> и суммарный результат.
     *
     * <h2>Как считается</h2>
     *
     * Каждый круг, проживший дольше возраста {@code A}, закрывается заново — по
     * справедливой цене в момент {@code вход + A} минус тейкерская пошлина
     * (полуспред плюс комиссия). Круги короче {@code A} не трогаются.
     *
     * ⚠️ <b>Это верхняя оценка пользы.</b> Замена делается ЗАДНИМ ЧИСЛОМ на
     * фактической истории: живой бот, закрывшись в момент {@code A}, освободил
     * бы лот, поставил следующую заявку и дальше пошёл бы по другой траектории.
     * Мы этого не моделируем. Читать можно ЗНАК и порядок, решать — обходом.
     */
    private String takerExit(String journalPath, List<ExecJournal.FillRow> fills,
                             long fromMs, long toMs, double takerCostBp) {
        List<Pair> pairs = new ArrayList<>();
        for (Object o : match(fills, true, false)) {
            Pair p = (Pair) o;
            if (p.closedMs() >= fromMs && p.closedMs() < toMs && !p.handover()) {
                pairs.add(p);
            }
        }
        if (pairs.size() < 10) {
            return "\n## Тейкерский выход\n\nкругов в окне меньше десяти — считать нечего\n";
        }
        TreeMap<Long, Double> fair = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(journalPath).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 ORDER BY ts_ms");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fair.put(rs.getLong(1), rs.getDouble(2));
            }
        } catch (Exception e) {
            return "\n## Тейкерский выход\n\nне прочитались котировки: " + e.getMessage() + "\n";
        }
        if (fair.isEmpty()) {
            return "\n## Тейкерский выход\n\nнет справедливых цен в журнале\n";
        }

        StringBuilder sb = new StringBuilder("\n## Тейкерский выход по возрасту круга\n\n");
        sb.append(String.format(Locale.ROOT,
                "тейкерская пошлина: %.1f б.п. (полуспред + комиссия)%n%n", takerCostBp));
        sb.append("| возраст | тронуто кругов | средний круг | CVaR худших 10% | худший |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        double[] ages = {Double.POSITIVE_INFINITY, 120, 60, 30, 15, 5};
        for (double ageMin : ages) {
            List<Double> results = new ArrayList<>();
            int touched = 0;
            for (Pair p : pairs) {
                Map.Entry<Long, Double> at = p.minutes() <= ageMin ? null
                        : fair.floorEntry(p.openedMs() + (long) (ageMin * 60_000));
                if (at == null || at.getValue() <= 0 || !(p.entry() > 0)) {
                    results.add(p.bp());
                    continue;
                }
                double moveBp = (p.buyFirst() ? at.getValue() - p.entry()
                        : p.entry() - at.getValue()) / p.entry() * 10_000;
                results.add(moveBp - takerCostBp);
                touched++;
            }
            Collections.sort(results);
            double sum = 0;
            for (double v : results) {
                sum += v;
            }
            int tail = Math.max(1, results.size() / 10);
            double cvar = 0;
            for (int i = 0; i < tail; i++) {
                cvar += results.get(i);
            }
            sb.append(String.format(Locale.ROOT, "| %s | %d | %+.2f | %+.2f | %+.1f |%n",
                    Double.isInfinite(ageMin) ? "**без выхода**"
                            : String.format(Locale.ROOT, "%.0f мин", ageMin),
                    touched, sum / results.size(), cvar / tail, results.get(0)));
        }
        sb.append(String.format(Locale.ROOT, "%nкругов в окне: %d%n", pairs.size()));
        if (pairs.size() < 30) {
            sb.append(String.format(Locale.ROOT,
                    "%n⚠️ КОЛОНКУ CVaR ЧИТАТЬ НЕЛЬЗЯ: худшие 10%% — это %d круг(а),%n"
                            + "то есть не условное среднее хвоста, а просто минимум.%n"
                            + "Нужно хотя бы тридцать кругов в окне.%n",
                    Math.max(1, pairs.size() / 10)));
        }
        sb.append("\n⚠️ Оценка ВЕРХНЯЯ: замена делается задним числом на фактической\n")
                .append("истории, а живой бот после раннего закрытия пошёл бы по другой\n")
                .append("траектории. Читать знак и порядок, решать обходом.\n");
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
