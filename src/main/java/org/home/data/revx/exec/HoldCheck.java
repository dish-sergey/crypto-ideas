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

        // 🔑 ФАКТЫ ЛИТТЛА СЧИТАЮТСЯ ДО ТАБЛИЦЫ и приписываются к КАЖДОЙ строке
        // (док. 154 §III). Внутри одного отчёта 153 жили три разных T по одному
        // боту — 30.8, 75 и 109 минут, — и ни в одной строке не было видно, что
        // две из них несовместимы с долей пустоты в 67%. Теперь несовместимость
        // не может проехать незамеченной: рядом с каждым T стоит инвентарь,
        // который из него следует, и инвентарь, который бот видел у себя.
        Little facts = littleFacts(journalPath, fills, fromMs, toMs);
        sb.append("## 1–2. Прямой замер: FIFO против LIFO\n\n");
        sb.append("| правило | передачи | кругов | медиана | среднее | p90 | максимум |")
                .append(" инвентарь по Литтлу | факт | сходится |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
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
                // Литтл: средний инвентарь = ТЕМП ПОКУПОК × среднее держание.
                //
                // ⚠️ Темп берётся по покупкам в окне, а не по числу закрытых
                // кругов: круг мог открыться до начала окна, и тогда его лот
                // приехал в инвентарь раньше. Разница между этими двумя
                // счётчиками сама по себе диагностична — она значит, что за
                // окно бот распродал больше, чем купил, или наоборот.
                double impliedLots = facts == null || facts.spanMin() <= 0 ? Double.NaN
                        : facts.buys() / facts.spanMin() * mean(held);
                String verdict = facts == null || Double.isNaN(impliedLots)
                        || !(facts.meanInvLots() > 0) ? "—"
                        : ratioOf(impliedLots, facts.meanInvLots()) < 1.5 ? "да"
                                : "⚠️ нет";
                sb.append(String.format(Locale.ROOT,
                        "| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s |%n",
                        fifo ? "FIFO" : "LIFO", policy, held.size(),
                        fmt(quantile(held, 0.5)), fmt(mean(held)),
                        fmt(quantile(held, 0.9)), fmt(quantile(held, 1.0)),
                        Double.isNaN(impliedLots) ? "—"
                                : String.format(Locale.ROOT, "%.2f", impliedLots),
                        facts == null ? "—"
                                : String.format(Locale.ROOT, "%.2f", facts.meanInvLots()),
                        verdict));
                if (fifo && "все".equals(policy)) {
                    fifoMedian = quantile(held, 0.5);
                    fifoMean = mean(held);
                }
            }
        }
        sb.append("\n⚠️ Истинное время под позицией лежит МЕЖДУ FIFO и LIFO. ")
                .append("Расхождение больше чем вдвое означает, что величина не определена\n")
                .append("однозначно, и выводы по ней преждевременны.\n\n");
        if (facts != null) {
            sb.append(String.format(Locale.ROOT,
                    "🔑 Проверка Литтла приписана к КАЖДОЙ строке: «инвентарь по Литтлу» —"
                            + " это%nсколько лотов должно лежать при таком T и темпе покупок"
                            + " (%d покупок за %.1f ч),%n«факт» — сколько бот видел у себя в"
                            + " `exec_quote` (%.2f лота, пусто %.0f%% времени).%nСтрока с"
                            + " пометкой «⚠️ нет» означает, что её T несовместимо с"
                            + " наблюдённым%nинвентарём — числа из такой строки в документы"
                            + " не переносить.%n%n",
                    facts.buys(), facts.spanMin() / 60, facts.meanInvLots(),
                    100 * facts.emptyShare()));
        }

        sb.append(little(journalPath, fills, fromMs, toMs, fifoMedian, fifoMean));
        sb.append(ratio(sigmaBpPerMin, offsetBp, fifoMedian, fifoMean));
        sb.append(conditional(journalPath, fills, fromMs, toMs, sigmaBpPerMin));
        sb.append(skew(journalPath, fromMs, toMs));
        sb.append(regime(journalPath, fills, fromMs, toMs, offsetBp));
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
        return match(fills, fifo, skipHandover, new ArrayDeque<>());
    }

    /** То же, но оставшиеся открытыми партии остаются в {@code open} для вызывающего. */
    private static List<Pair> match(List<ExecJournal.FillRow> fills, boolean fifo,
                                    boolean skipHandover, Deque<Lot> open) {
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
    /**
     * Факты закона Литтла по окну: лот, средний инвентарь, доля пустоты, темп
     * покупок и длина окна с тиками.
     *
     * Считаются ОДИН раз и подставляются и в таблицу прямого замера, и в раздел
     * Литтла: два разных подсчёта одного и того же — это ровно тот способ,
     * которым в отчёт попадают три несовместимых числа.
     */
    private record Little(double lot, double meanInvLots, double emptyShare, double spanMin,
                          long buys) {
    }

    private Little littleFacts(String journalPath, List<ExecJournal.FillRow> fills,
                               long fromMs, long toMs) {
        double area = 0;
        double emptyMs = 0;
        double span = 0;
        long prev = -1;
        double prevInv = 0;
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
            return null;
        }
        if (span <= 0 || !(lot > 0)) {
            return null;
        }
        long buys = fills.stream()
                .filter(f -> f.buy() && f.tsMs() >= fromMs && f.tsMs() < toMs).count();
        return new Little(lot, area / span / lot, emptyMs / span, span / 60_000.0, buys);
    }

    /** Отношение большего к меньшему — во сколько раз числа расходятся. */
    private static double ratioOf(double a, double b) {
        double lo = Math.min(Math.abs(a), Math.abs(b));
        double hi = Math.max(Math.abs(a), Math.abs(b));
        return lo <= 1e-9 ? Double.POSITIVE_INFINITY : hi / lo;
    }

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

    /** Корзины времени держания, минуты: верхние границы. */
    private static final double[] HOLD_BUCKETS = {5, 15, 30, 60, 120, Double.POSITIVE_INFINITY};

    /**
     * УСЛОВНЫЙ РИСК: {@code E[Δfair | T]} и {@code SD[Δfair | T]} вместо {@code σ√T}
     * (док. 152, пункты 1.2 и 1.1).
     *
     * <h2>Почему σ√T — не тот риск</h2>
     *
     * {@code σ√T} — разброс цены за ЗАДАННОЕ время. А время держания задаёт
     * рынок: круг длинный именно потому, что цена ушла от нас и встречная заявка
     * не исполняется. Значит, длинные круги отобраны по неблагоприятному исходу,
     * и у них должно быть два отличия от диффузии: СРЕДНЕЕ сдвига не ноль (снос
     * против позиции), а разброс больше {@code σ√T}.
     *
     * <h2>Как считается</h2>
     *
     * Каждый круг FIFO (без передач, закрытый в окне) раскладывается тождественно:
     * <pre>
     * результат = вход (fair_откр − цена входа) + СНОС (fair_закр − fair_откр) + выход (цена выхода − fair_закр)
     * </pre>
     * все три — в б.п. от цены входа, со знаком позиции. Первое и третье — захват
     * на ногах, среднее — ценовой риск круга. Сумма сходится с результатом круга
     * до ошибки округления; расхождение печатается как контроль разбора.
     *
     * ⚠️ {@code fair} берётся из журнала на момент ИСПОЛНЕНИЯ по отметке бота, а
     * узнаёт бот об исполнении на 2–5 с позже. На кругах от минуты это шум, на
     * самых коротких — смещение в сторону отбора. Поэтому захват на ногах здесь
     * чуть занижен, а снос чуть завышен, и в первой корзине это видно сильнее.
     *
     * <h2>Отношение за сутки (1.1)</h2>
     *
     * Отношение на круг порога 1 не несёт: игра повторяется. Честная суточная
     * версия — {@code среднее / СКО × √(кругов в сутки)}, где СКО ФАКТИЧЕСКОЕ,
     * с отбором внутри. Это переоценка, если круги коррелируют (идут подряд в
     * одну сторону в сносе), поэтому рядом печатается и прямое суточное — по
     * суммам дней, — пока дней мало, оно только для знака.
     */
    private String conditional(String journalPath, List<ExecJournal.FillRow> fills,
                               long fromMs, long toMs, double sigmaParam) {
        List<Pair> pairs = new ArrayList<>();
        for (Pair p : match(fills, true, false)) {
            if (p.closedMs() >= fromMs && p.closedMs() < toMs && !p.handover() && p.entry() > 0) {
                pairs.add(p);
            }
        }
        String head = "\n## Условный риск: E[Δfair | T] и SD[Δfair | T] вместо σ√T\n\n";
        if (pairs.size() < 10) {
            return head + "кругов в окне меньше десяти — считать нечего\n";
        }
        TreeMap<Long, Double> fair = new TreeMap<>();
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        long firstOpen = pairs.stream().mapToLong(Pair::openedMs).min().orElse(fromMs);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(journalPath).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, fair FROM exec_quote WHERE fair > 0 AND ts_ms >= ? AND ts_ms < ?"
                             + " ORDER BY ts_ms")) {
            ps.setLong(1, Math.min(firstOpen, fromMs) - 600_000);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong(1);
                    fair.put(ts, rs.getDouble(2));
                    if (ts >= fromMs) {
                        byMinute.put(ts / 60_000, rs.getDouble(2));
                    }
                }
            }
        } catch (Exception e) {
            return head + "не прочитались котировки: " + e.getMessage() + "\n";
        }
        // σ минутных приращений по окну: последняя fair каждой минуты, только
        // соседние минуты — разрыв в журнале не должен сойти за одно большое движение.
        List<Double> steps = new ArrayList<>();
        Map.Entry<Long, Double> prev = null;
        for (Map.Entry<Long, Double> e : byMinute.entrySet()) {
            if (prev != null && e.getKey() - prev.getKey() == 1) {
                steps.add((e.getValue() - prev.getValue()) / prev.getValue() * 10_000);
            }
            prev = e;
        }
        double sigmaMeasured = sd(steps);
        double sigma = sigmaParam > 0 ? sigmaParam : sigmaMeasured;

        int nb = HOLD_BUCKETS.length;
        List<List<double[]>> buckets = new ArrayList<>();
        for (int i = 0; i < nb; i++) {
            buckets.add(new ArrayList<>());
        }
        double maxResidual = 0;
        int skipped = 0;
        for (Pair p : pairs) {
            Map.Entry<Long, Double> fo = fair.floorEntry(p.openedMs());
            Map.Entry<Long, Double> fc = fair.floorEntry(p.closedMs());
            // Тик старше минуты — это не цена в момент исполнения, а дыра в журнале.
            if (fo == null || fc == null || p.openedMs() - fo.getKey() > 60_000
                    || p.closedMs() - fc.getKey() > 60_000) {
                skipped++;
                continue;
            }
            double sign = p.buyFirst() ? 1 : -1;
            double in = sign * (fo.getValue() - p.entry()) / p.entry() * 10_000;
            double drift = sign * (fc.getValue() - fo.getValue()) / p.entry() * 10_000;
            double out = sign * (p.exit() - fc.getValue()) / p.entry() * 10_000;
            maxResidual = Math.max(maxResidual, Math.abs(in + drift + out - p.bp()));
            int b = 0;
            while (p.minutes() > HOLD_BUCKETS[b]) {
                b++;
            }
            buckets.get(b).add(new double[]{p.minutes(), in, drift, out, p.bp(), p.closedMs()});
        }

        StringBuilder sb = new StringBuilder(head);
        sb.append(String.format(Locale.ROOT,
                "σ минутная по окну: **%.2f б.п./мин** (%d приращений)%s%n%n",
                sigmaMeasured, steps.size(),
                sigmaParam > 0 ? String.format(Locale.ROOT, "; в σ√T идёт заданная --sigma %.2f", sigmaParam)
                        : "; она же идёт в σ√T"));
        sb.append("| T | кругов | эпизодов | T средн. | вход | **E[снос]** | рынок за T | **отбор** | **SD[снос]** | SD рынка за T | σ√T | SD/SD рынка | выход | круг | Σ круг |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        double lo = 0;
        List<Double> allDrift = new ArrayList<>();
        List<Double> allRound = new ArrayList<>();
        for (int b = 0; b < nb; b++) {
            List<double[]> rows = buckets.get(b);
            String label = Double.isInfinite(HOLD_BUCKETS[b])
                    ? String.format(Locale.ROOT, "> %.0f", lo)
                    : String.format(Locale.ROOT, "%.0f–%.0f", lo, HOLD_BUCKETS[b]);
            lo = HOLD_BUCKETS[b];
            if (rows.isEmpty()) {
                continue;
            }
            List<Double> t = col(rows, 0);
            List<Double> drift = col(rows, 2);
            List<Double> round = col(rows, 4);
            allDrift.addAll(drift);
            allRound.addAll(round);
            // σ√T по СРЕДНЕМУ корню, а не по корню среднего: у диффузии дисперсия
            // сдвига линейна по T, значит ожидаемое SD корзины — √(σ²·E[T]).
            double diffusion = sigma * Math.sqrt(mean(t));
            double sdDrift = sd(drift);
            // КОНТРОЛЬ: тот же сдвиг fair за то же время, но от КАЖДОЙ минуты окна,
            // без отбора. Все круги спот-бота начинаются покупкой, поэтому падение
            // рынка за окно даёт отрицательный снос любому кругу — это не отбор.
            // И вторая половина: fair шумит сильнее книги и возвращается к
            // среднему, так что σ√T по минутной σ завышает разброс на длинных T.
            double[] market = unconditional(byMinute, (int) Math.max(1, Math.round(mean(t))));
            sb.append(String.format(Locale.ROOT,
                    "| %s мин | %d | %d | %.1f | %+.1f | **%+.1f** | %+.1f | **%+.1f** | **%s** | %.1f | %.1f | %s | %+.1f | %+.1f | %+.0f |%n",
                    label, rows.size(), episodes(col(rows, 5)), mean(t),
                    meanSigned(col(rows, 1)), meanSigned(drift),
                    market[0], meanSigned(drift) - market[0],
                    rows.size() > 1 ? String.format(Locale.ROOT, "%.1f", sdDrift) : "—",
                    market[1], diffusion,
                    rows.size() > 2 && market[1] > 0
                            ? String.format(Locale.ROOT, "%.2f", sdDrift / market[1]) : "—",
                    meanSigned(col(rows, 3)), meanSigned(round), sumOf(round)));
        }
        // ⚠️ ВЫЖИВШИЕ. В таблицу попадают только ЗАКРЫТЫЕ круги, а круг закрывается,
        // когда цена вернулась к аску. Лот, от которого цена ушла и не вернулась,
        // так и висит открытым — и выпадает из статистики ровно потому, что он
        // худший. Поэтому открытые на конец окна партии досчитываются отдельно,
        // по fair на конец окна.
        Deque<Lot> left = new ArrayDeque<>();
        match(fills.stream().filter(f -> f.tsMs() < toMs).toList(), true, false, left);
        Map.Entry<Long, Double> fEnd = fair.floorEntry(toMs);
        List<Double> openDrift = new ArrayList<>();
        List<Double> openAge = new ArrayList<>();
        int openHandover = 0;
        for (Lot lot : left) {
            Map.Entry<Long, Double> fo = fair.floorEntry(lot.tsMs);
            if (lot.qty <= 0 || fEnd == null) {
                continue;
            }
            if (lot.handover) {
                // цена передачи — не наша сделка, fair на момент передачи честнее
                openHandover++;
            }
            double base = lot.handover && fo != null ? fo.getValue() : lot.price;
            double lots = lot.qty / Math.max(EPS, medianQty(fills));
            // вес — число лотов: партия бывает остатком частичного исполнения
            for (int i = 0; i < Math.max(1, Math.round(lots)); i++) {
                openDrift.add((fEnd.getValue() - base) / base * 10_000);
                openAge.add((toMs - lot.tsMs) / 60_000.0);
            }
        }
        if (!openDrift.isEmpty()) {
            sb.append(String.format(Locale.ROOT,
                    "| **не закрыт к концу окна** | %d лот. | — | %.0f | — | **%+.1f** | — | — | %s | — | — | — | — | "
                            + "%+.1f (по fair) | %+.0f |%n",
                    openDrift.size(), meanSigned(openAge), meanSigned(openDrift),
                    openDrift.size() > 1 ? String.format(Locale.ROOT, "%.1f", sd(openDrift)) : "—",
                    meanSigned(openDrift), sumOf(openDrift)));
            if (openHandover > 0) {
                sb.append(String.format(Locale.ROOT,
                        "%n(среди открытых %d переданных партий — для них отсчёт от fair на момент передачи)%n",
                        openHandover));
            }
        }
        sb.append(String.format(Locale.ROOT,
                "%nконтроль разбора: |вход + снос + выход − круг| ≤ %.3f б.п.; "
                        + "пропущено кругов без тика fair рядом: %d%n", maxResidual, skipped));
        sb.append("\nЗнак у всех граф — со стороны позиции: плюс = в нашу пользу. «Вход» и\n")
                .append("«выход» — захват на ногах относительно fair в момент исполнения,\n")
                .append("«снос» — сколько fair ушла за время держания.\n");
        sb.append("\n«Рынок за T» и «SD рынка за T» — сдвиг fair за то же время от каждой\n")
                .append("минуты окна, без отбора; «отбор» = E[снос] − рынок за T.\n");
        sb.append("\n⚠️ «Эпизодов» — круги, закрывшиеся в пределах 15 минут друг от друга,\n")
                .append("считаются одним. Одно резкое движение закрывает несколько кругов разом,\n")
                .append("и выборка в 27 кругов из трёх эпизодов — это три наблюдения, а не 27.\n");
        sb.append("\n🔑 Если время держания не зависит от исхода, то отбор ≈ 0 и SD/SD рынка ≈ 1\n")
                .append("во всех корзинах. Отбор по неблагоприятному исходу виден как отбор < 0\n")
                .append("и SD/SD рынка > 1. ⚠️ σ√T сравнивать со SD рынка: если σ√T заметно\n")
                .append("больше, fair на этом горизонте возвращается к среднему, и мерка σ√T\n")
                .append("сама по себе завышена — независимо от всякого отбора.\n");

        // 1.1: отношение за сутки.
        // Сутки — по минутам, где fair ЕСТЬ, а не по длине окна: журнал может
        // кончаться раньше окна, и темп кругов тогда занижен.
        double days = Math.max(1, byMinute.size()) / 1440.0;
        double perDay = allRound.size() / days;
        double m = meanSigned(allRound);
        double s = sd(allRound);
        // Прямая суточная сумма: день по времени закрытия круга.
        TreeMap<Long, Double> daily = new TreeMap<>();
        for (Pair p : pairs) {
            daily.merge(p.closedMs() / 86_400_000, p.bp(), Double::sum);
        }
        List<Double> dayValues = new ArrayList<>(daily.values());
        sb.append("\n### Отношение за сутки (док. 152, п. 1.1)\n\n");
        sb.append(String.format(Locale.ROOT,
                "| величина | значение |%n|---|---:|%n"
                        + "| кругов в сутки | %.1f |%n"
                        + "| средний круг / СКО круга (фактическое, с отбором) | %+.2f / %.1f б.п. |%n"
                        + "| на круг: среднее / СКО | %+.3f |%n"
                        + "| **за сутки: × √(кругов в сутки)** | **%+.2f** |%n"
                        + "| прямо по суммам дней: среднее / СКО (%d дн.) | %s |%n",
                perDay, m, s, s > 0 ? m / s : 0, s > 0 ? m / s * Math.sqrt(perDay) : 0,
                dayValues.size(),
                dayValues.size() >= 3 && sd(dayValues) > 0
                        ? String.format(Locale.ROOT, "%+.2f", meanSigned(dayValues) / sd(dayValues))
                        : "— (меньше трёх дней)"));
        sb.append("\n⚠️ Здесь в числителе ФАКТИЧЕСКИЙ средний круг, а не захват 2δ: снос уже\n")
                .append("внутри. Поэтому величина может быть отрицательной — в отличие от\n")
                .append("2δ/σ√T, которая положительна по построению и знака дохода не видит.\n")
                .append("Корень из числа кругов предполагает их независимость; серия кругов,\n")
                .append("проигравших один и тот же снос, делает оценку завышенной.\n");
        return sb.toString();
    }

    /**
     * Среднее и СКО сдвига fair за {@code h} минут от каждой минуты окна, б.п.
     * Пары, у которых конечной минуты нет (разрыв журнала), пропускаются, а не
     * подменяются ближайшей: иначе разрыв сошёл бы за движение другой длины.
     */
    /** Число эпизодов: моменты закрытия, разнесённые больше чем на 15 минут. */
    static int episodes(List<Double> closedMs) {
        List<Double> sorted = new ArrayList<>(closedMs);
        Collections.sort(sorted);
        int n = 0;
        double last = Double.NEGATIVE_INFINITY;
        for (double t : sorted) {
            if (t - last > 15 * 60_000) {
                n++;
            }
            last = t;
        }
        return n;
    }

    private static double[] unconditional(TreeMap<Long, Double> byMinute, int h) {
        List<Double> d = new ArrayList<>();
        for (Map.Entry<Long, Double> e : byMinute.entrySet()) {
            Double end = byMinute.get(e.getKey() + h);
            if (end != null && e.getValue() > 0) {
                d.add((end - e.getValue()) / e.getValue() * 10_000);
            }
        }
        return new double[]{meanSigned(d), sd(d)};
    }

    private static List<Double> col(List<double[]> rows, int i) {
        List<Double> out = new ArrayList<>(rows.size());
        for (double[] r : rows) {
            out.add(r[i]);
        }
        return out;
    }

    /** Среднее, допускающее отрицательные значения (у {@link #mean} −1 значит «пусто»). */
    private static double meanSigned(List<Double> values) {
        return values.isEmpty() ? 0 : sumOf(values) / values.size();
    }

    private static double sumOf(List<Double> values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s;
    }

    private static double sd(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double m = meanSigned(values);
        double v = 0;
        for (double x : values) {
            v += (x - m) * (x - m);
        }
        return Math.sqrt(v / (values.size() - 1));
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
     * РЕЖИМ РЫНКА: тихий против информативного.
     *
     * <h2>Вопрос</h2>
     *
     * Котировщик реагирует на «кто-то зашёл и купил немного» и на «пошёл
     * информированный поток» одинаково: заявка стоит где стояла. Если результат
     * в этих двух режимах разный по ЗНАКУ, то настраивать надо не отступ, а
     * участие: в одном режиме торговать, в другом уходить.
     *
     * <h2>Как режим определяется</h2>
     *
     * По реализованной волатильности ЧАСА: СКО минутных приращений справедливой
     * цены внутри часа. Порог адаптивный — {@code MULT} медиан по суткам, а не
     * фиксированное число: у пар разная σ, и константа означала бы у BTC одно, а
     * у SOL другое.
     *
     * ⚠️ Круг относится к режиму того часа, в котором он ЗАКРЫЛСЯ. Круги,
     * пережившие смену режима, попадают в последний — это огрубление, и на
     * длинных кругах оно смазывает границу.
     *
     * <h2>Что уже измерено (11–12.09.2026, задача A32)</h2>
     *
     * Бот A, четверо суток: в тихом режиме 155 кругов дали <b>+3.35 б.п.</b> в
     * среднем, в информативном 87 кругов — <b>−17.4</b>. Подтверждается на трёх
     * сутках из четырёх, причём двое из них (08 и 09.09) без макро-события.
     *
     * 🔑 И побочное, которое важнее: в информативном режиме закрывается
     * БОЛЬШИНСТВО кругов (у бота A 43 из 50 за 11.09), хотя он занимает 4 часа
     * из 24. Волатильность двигает справедливую цену, цена доходит до наших
     * заявок, исполнений становится больше — то есть бот сам стягивает свою
     * торговлю в худший режим. Отбор работает не только на сделке, но и на
     * выборе момента.
     */
    private String regime(String journalPath, List<ExecJournal.FillRow> fills,
                          long fromMs, long toMs, double offsetBp) {
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(journalPath).toAbsolutePath() + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms / 60000, avg(fair) FROM exec_quote WHERE fair > 0"
                             + " AND ts_ms >= ? AND ts_ms < ? GROUP BY 1 ORDER BY 1")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byMinute.put(rs.getLong(1), rs.getDouble(2));
                }
            }
        } catch (Exception e) {
            return "\n## Режим рынка\n\nне прочитались котировки: " + e.getMessage() + "\n";
        }
        if (byMinute.size() < 120) {
            return "\n## Режим рынка\n\nминут в окне меньше двух часов — считать нечего\n";
        }
        // СКО минутных приращений внутри каждого часа.
        Map<Long, List<Double>> byHour = new java.util.TreeMap<>();
        Long prevKey = null;
        double prevValue = 0;
        for (Map.Entry<Long, Double> e : byMinute.entrySet()) {
            if (prevKey != null && e.getKey() - prevKey == 1 && prevValue > 0) {
                byHour.computeIfAbsent(e.getKey() / 60, k -> new ArrayList<>())
                        .add((e.getValue() - prevValue) / prevValue * 10_000);
            }
            prevKey = e.getKey();
            prevValue = e.getValue();
        }
        Map<Long, Double> sigma = new java.util.TreeMap<>();
        for (Map.Entry<Long, List<Double>> e : byHour.entrySet()) {
            if (e.getValue().size() < 20) {
                continue;
            }
            double mean = 0;
            for (double v : e.getValue()) {
                mean += v;
            }
            mean /= e.getValue().size();
            double var = 0;
            for (double v : e.getValue()) {
                var += (v - mean) * (v - mean);
            }
            sigma.put(e.getKey(), Math.sqrt(var / e.getValue().size()));
        }
        if (sigma.size() < 4) {
            return "\n## Режим рынка\n\nчасов с котировками меньше четырёх\n";
        }
        List<Double> sorted = new ArrayList<>(sigma.values());
        Collections.sort(sorted);
        double median = quantile(sorted, 0.5);
        double threshold = REGIME_MULT * median;

        // ⚠️ ТРИ РАЗМЕТКИ, И ОНИ ДАЮТ РАЗНЫЕ ОТВЕТЫ.
        //
        // Круг живёт во времени и может начаться в одном режиме, а кончиться в
        // другом. Разметка по часу ЗАКРЫТИЯ приписывает убыток моменту, когда он
        // ЗАФИКСИРОВАН, а не когда создан: продали в шторм — записали убыток в
        // шторм. Разметка по часу ПОКУПКИ отвечает на другой вопрос — «стоило ли
        // набирать в этот момент».
        //
        // 🔑 Честная третья: круги, у которых ОБА конца в одном режиме. Только
        // они не влияют на соседний период и только по ним можно говорить «в
        // таком режиме конструкция зарабатывает столько-то». Остальные —
        // переходные, и их надо показывать отдельно, а не растворять в обеих
        // половинах.
        String[] modes = {"по закрытию", "по покупке", "ЦЕЛИКОМ внутри"};
        double[][] sum = new double[3][2];
        double[][] hold = new double[3][2];
        double[][] sig = new double[3][2];
        int[][] cnt = new int[3][2];
        int spanning = 0;
        for (Pair p : match(fills, true, false)) {
            if (p.closedMs() < fromMs || p.closedMs() >= toMs || p.handover()) {
                continue;
            }
            Double sc = sigma.get(p.closedMs() / 3_600_000);
            Double so = sigma.get(p.openedMs() / 3_600_000);
            if (sc == null) {
                continue;
            }
            int rc = sc >= threshold ? 1 : 0;
            add(sum[0], hold[0], sig[0], cnt[0], rc, p, sc);
            if (so == null) {
                continue;
            }
            int ro = so >= threshold ? 1 : 0;
            add(sum[1], hold[1], sig[1], cnt[1], ro, p, so);
            if (ro == rc) {
                add(sum[2], hold[2], sig[2], cnt[2], rc, p, sc);
            } else {
                spanning++;
            }
        }
        StringBuilder sb = new StringBuilder("\n## Режим рынка: тихий против информативного\n\n");
        sb.append(String.format(Locale.ROOT,
                "порог: %.1f × медианная часовая σ (%.2f) = **%.2f б.п./мин**; "
                        + "часов в окне %d, из них информативных %d%n%n",
                REGIME_MULT, median, threshold, sigma.size(),
                sigma.values().stream().filter(v -> v >= threshold).count()));
        sb.append("| разметка | режим | кругов | средний круг | всего | σ | держание | ЗАХВ/РИСК |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (int m = 0; m < 3; m++) {
            for (int r = 0; r < 2; r++) {
                if (cnt[m][r] == 0) {
                    continue;
                }
                double s = sig[m][r] / cnt[m][r];
                double t = hold[m][r] / cnt[m][r];
                double risk = s * Math.sqrt(t);
                sb.append(String.format(Locale.ROOT,
                        "| %s | %s | %d | %+.1f | %+.1f | %.2f | %.0f мин | %s |%n",
                        modes[m], r == 0 ? "тихий" : "информативный", cnt[m][r],
                        sum[m][r] / cnt[m][r], sum[m][r], s, t,
                        offsetBp > 0 && risk > 0
                                ? String.format(Locale.ROOT, "%.2f", 2 * offsetBp / risk) : "—"));
            }
        }
        if (cnt[0][0] > 0 && cnt[0][1] > 0) {
            sb.append(String.format(Locale.ROOT,
                    "%nдоля кругов, закрывшихся в информативном режиме: **%.0f%%** "
                            + "при его доле во времени %.0f%%%n",
                    100.0 * cnt[0][1] / (cnt[0][0] + cnt[0][1]),
                    100.0 * sigma.values().stream().filter(v -> v >= threshold).count()
                            / sigma.size()));
        }
        sb.append(String.format(Locale.ROOT,
                "переходных кругов (начались в одном режиме, кончились в другом): **%d**%n",
                spanning));
        sb.append("\n🔑 Читать надо строку «ЦЕЛИКОМ внутри»: только у этих кругов оба конца\n")
                .append("в одном режиме, и только они не влияют на соседний период. Разметка\n")
                .append("«по закрытию» приписывает убыток моменту ФИКСАЦИИ, а не создания;\n")
                .append("«по покупке» отвечает на другой вопрос — стоило ли набирать тогда.\n")
                .append("Расхождение этих трёх строк и есть мера того, насколько разделение\n")
                .append("по режиму вообще осмысленно на данном окне.\n");
        return sb.toString();
    }

    /** Накопитель одной клетки разметки. */
    private static void add(double[] sum, double[] hold, double[] sig, int[] cnt,
                            int regime, Pair p, double sigma) {
        sum[regime] += p.bp();
        hold[regime] += p.minutes();
        sig[regime] += sigma;
        cnt[regime]++;
    }

    /** Во сколько раз часовая σ должна превысить медианную, чтобы час считался информативным. */
    private static final double REGIME_MULT =
            Double.parseDouble(System.getProperty("revx.regime-mult", "2.0"));


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
