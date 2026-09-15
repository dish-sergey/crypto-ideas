package org.home.data.revx.exec;

import org.home.data.revx.replay.BootParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ОСЬ ЛЕСТНИЦЫ: НАСТРОЕННЫЙ ОТСТУП ПРОТИВ ЭФФЕКТИВНОГО. Команда
 * {@code --revx-offset-axis --journals=a=путь,b=путь,…}.
 *
 * <h2>Зачем прибор появился (док. 154 §V, блок 2)</h2>
 *
 * Настройка задаёт отступ, но до книги доезжает не он: по дороге цену двигает
 * скос по инвентарю, раздвижение по бюджету и по ширине опоры, зажим по книге и
 * тику. Разбор 154 утверждает, что из-за этого <b>ось лестницы подписана
 * неверно</b>: диапазон настроек 8…16 б.п. проходится эффективными 6.8…12.5,
 * то есть сжат примерно на четверть и нелинейно. Если так, то плоские лестницы,
 * уезжающий оптимум и «κ гуляет вдвое» — три следствия одной ошибки оси.
 *
 * Проверить это можно только на живых журналах: в них есть и настройка (событие
 * {@code boot}), и то, что бот реально котировал ({@code exec_quote.bid/ask}
 * против {@code exec_quote.fair} в каждом тике).
 *
 * <h2>Что считается</h2>
 *
 * <ol>
 *   <li><b>Отрезки постоянной настройки.</b> Журнал режется по событиям
 *       {@code boot}: между двумя загрузками настройки не меняются. Каждый
 *       отрезок даёт одну точку «настройка → эффективный отступ»;</li>
 *   <li><b>Сжатие оси.</b> Регрессия эффективного на настроенный по отрезкам,
 *       взвешенная по числу тиков. Наклон 1.0 значит «ось честная», наклон
 *       0.75 — «диапазон сжат на четверть», как и утверждает 154;</li>
 *   <li><b>По сторонам отдельно и с условием на инвентарь</b> (154 §V.1). Аск
 *       существует только когда инвентарь есть, а в этих состояниях скос по
 *       модулю меньше: средние по биду и по аску взяты по РАЗНЫМ подмножествам
 *       состояний и потому несопоставимы. Печатается разрез по состоянию
 *       инвентаря, где обе стороны считаются на ОДНИХ И ТЕХ ЖЕ тиках;</li>
 *   <li><b>κ по эффективному отступу.</b> Время стояния разбивается по корзинам
 *       эффективного отступа, исполнения раскладываются по тем же корзинам, и
 *       {@code λ(δ) = сделок / суток стояния}. Наклон {@code ln λ} по δ и есть
 *       κ — впервые измеренная на ЖИВЫХ заявках, а не лестницей прогонов.</li>
 * </ol>
 *
 * <h2>⚠️ Чем эта κ отличается от лестничной, и почему на биде её нет</h2>
 *
 * Лестница меняет отступ ПРЕДНАМЕРЕННО и меряет отклик потока. Здесь отступ
 * гуляет САМ — его двигает скос, то есть инвентарь, то есть недавние
 * исполнения. На биде это ломает оценку полностью: пустой инвентарь значит, что
 * в последние часы покупалось плохо (рынок уходил вверх), и скос подтягивает
 * бид ровно в том режиме, где покупок мало. Расстоянием и потоком управляет
 * ОДНА причина, поэтому наклон выходит плоский и даже обратный — что и
 * измерено (бот A: κ_бид = −0.06 при κ_аск = +0.39 на том же окне).
 *
 * На аске подмена слабее, и там наклон сошёлся с лестничным (0.39 против
 * 0.385). Это не «живая κ», а проверка: величина, померенная двумя совершенно
 * разными приборами, совпала.
 *
 * <h2>Цена заявки — из тел запросов</h2>
 *
 * В {@code exec_quote} лежит ЦЕЛЬ котировщика: она пересчитывается каждый тик и
 * всегда стоит в своих 12 б.п. от свежей справедливой цены. А заявка стоит там,
 * куда её довёз последний replace, и исполняется ровно тогда, когда цена к ней
 * подошла — то есть на МАЛЕНЬКОМ расстоянии от новой справедливой цены. Считать
 * время по цели, а исполнения по цене сделки нельзя: первая версия так и делала
 * и дала 40 покупок в корзине, где цель простояла полтора часа за две недели.
 *
 * <h2>⚠️ Исполнение раскладывается по ЦЕНЕ СДЕЛКИ, а не по цели тика</h2>
 *
 * У многоуровневого бота в книге стоит несколько заявок, а в {@code exec_quote}
 * записана только базовая (ближняя). Если раскладывать исполнения по цели тика,
 * все сделки дальних уровней приписались бы ближнему отступу, и κ получилась бы
 * из воздуха. Поэтому расстояние исполнения считается по ФАКТИЧЕСКОЙ цене
 * сделки относительно справедливой цены в тот момент.
 */
public final class OffsetAxis {

    private static final Logger log = LoggerFactory.getLogger(OffsetAxis.class);

    /**
     * Насколько бот узнаёт об исполнении позже площадки, мс.
     *
     * Отметка {@code exec_fill.ts_ms} — это когда бот УЗНАЛ (замер 08.09.2026:
     * 2.4–5.0 с, максимум 37). Справедливую цену для расстояния надо брать на
     * момент самой сделки, иначе у быстрых движений расстояние померяет
     * задержку опроса. Точное время есть в {@code updated_date} ответа
     * площадки, но тела запросов в тонком срезе журнала не едут, поэтому здесь
     * — постоянная поправка. Печатается и вариант без неё: если числа сильно
     * разойдутся, значит поправка существенна и нужен разбор тел.
     */
    private static final long FILL_LAG_MS = 3_000;

    /** Ширина корзины эффективного отступа, б.п. */
    private static final double BUCKET_BP = 1.0;

    private OffsetAxis() {
    }

    /** Один отрезок постоянной настройки одного бота. */
    private record Segment(String bot, String symbol, long fromMs, long toMs, BootParams params) {
    }

    /** Итоги по отрезку. */
    private record Stat(Segment seg, long ticks, double hours, double bidMedian, double askMedian,
                        double bidMean, double askMean, double askShare, double meanInvLots,
                        double emptyShare, double buysPerDay, double sellsPerDay, double lot) {
    }

    public static void run(String journals, String placed, String fromIso, String toIso, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0 : Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE : Instant.parse(toIso).toEpochMilli();
        Map<String, String> placedBy = new LinkedHashMap<>();
        for (String p : (placed == null ? "" : placed).split(",")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) {
                placedBy.put(kv[0].trim(), kv[1].trim());
            }
        }

        StringBuilder sb = new StringBuilder("# Ось лестницы: настройка против эффективного отступа\n\n");
        sb.append("окно: ").append(from == 0 ? "с начала журналов" : Instant.ofEpochMilli(from))
                .append(" .. ").append(to == Long.MAX_VALUE ? "конец" : Instant.ofEpochMilli(to))
                .append("\n\n");

        List<Stat> stats = new ArrayList<>();
        Map<String, List<Kappa>> kappaByPair = new LinkedHashMap<>();
        StringBuilder states = new StringBuilder();
        for (String pair : journals.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String bot = kv[0].trim();
            String path = kv[1].trim();
            try {
                List<Segment> segs = segments(bot, path, from, to);
                for (Segment s : segs) {
                    Stat st = measure(path, s);
                    if (st != null && st.ticks() > 1000) {
                        stats.add(st);
                    }
                }
                states.append(byInventory(bot, path, segs));
                Kappa k = kappa(bot, path, placedBy.get(bot), segs);
                if (k != null) {
                    kappaByPair.computeIfAbsent(k.symbol(), x -> new ArrayList<>()).add(k);
                }
            } catch (Exception e) {
                sb.append("⚠️ ").append(bot).append(": ").append(e).append('\n');
                log.warn("ось лестницы {}: {}", bot, e.toString());
            }
        }
        if (stats.isEmpty()) {
            sb.append("ни одного отрезка с настройками не нашлось\n");
            write(out, sb.toString());
            return;
        }

        sb.append("## 1. Отрезки постоянной настройки\n\n");
        sb.append("| бот | пара | ур. | с | по | настр. δ | цель | эфф. бид | эфф. аск |")
                .append(" аск есть | инв., лотов | пусто | покуп/сут | прод/сут | тиков |\n");
        sb.append("|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Stat s : stats) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %d | %s | %s | %.1f | %.0f%% | **%.2f** | **%.2f** | %.0f%% |"
                            + " %.2f | %.0f%% | %.1f | %.1f | %d |%n",
                    s.seg().bot(), s.seg().symbol().replace("/USDC", ""), s.seg().params().levels(),
                    day(s.seg().fromMs()), day(s.seg().toMs()),
                    s.seg().params().offset() * 10_000, s.seg().params().skewTarget() * 100,
                    s.bidMedian(), s.askMedian(), 100 * s.askShare(), s.meanInvLots(),
                    100 * s.emptyShare(), s.buysPerDay(), s.sellsPerDay(), s.ticks()));
        }
        sb.append("\n«эфф. бид/аск» — медиана расстояния ЦЕЛИ котировщика от справедливой\n")
                .append("цены по тикам отрезка. Настройка — то, что записано в `boot`.\n");

        sb.append(compression(stats));
        sb.append("\n## 3. Эффективный отступ по состоянию инвентаря (154 §V.1)\n\n");
        sb.append(states);
        sb.append(kappaSection(kappaByPair));

        write(out, sb.toString());
        log.info("\n{}", sb);
    }

    /**
     * СЖАТИЕ ОСИ: регрессия эффективного отступа на настроенный.
     *
     * ⚠️ Точки здесь — отрезки РАЗНЫХ ботов на разных парах и в разное время,
     * поэтому в наклон попадает не только сжатие, но и разница пар и режимов.
     * Это не оценка «эффекта настройки при прочих равных», а ответ на вопрос
     * «совпадает ли подпись оси с тем, что происходило в книге». Чтобы отделить
     * одно от другого, наклон печатается ещё и внутри пары.
     */
    private static String compression(List<Stat> stats) {
        StringBuilder sb = new StringBuilder("\n## 2. Сжатие оси\n\n");
        sb.append("| выборка | точек | наклон бида | наклон аска | наклон полусуммы |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        sb.append(row("все отрезки", stats));
        Map<String, List<Stat>> byPair = new LinkedHashMap<>();
        for (Stat s : stats) {
            byPair.computeIfAbsent(s.seg().symbol(), k -> new ArrayList<>()).add(s);
        }
        for (var e : byPair.entrySet()) {
            sb.append(row(e.getKey().replace("/USDC", ""), e.getValue()));
        }
        sb.append("\nНаклон 1.00 — ось честная: прибавили базисный пункт настройки, получили\n")
                .append("базисный пункт расстояния. Наклон меньше единицы и есть сжатие:\n")
                .append("лестница проходит меньший диапазон, чем написано на её оси.\n");
        sb.append("⚠️ Полусумма сторон — это то, чем управляет отступ: скос двигает бид и\n")
                .append("аск в ПРОТИВОПОЛОЖНЫЕ стороны и в полусумме гасится. Если сжат\n")
                .append("именно он, дело не в скосе, а в раздвижениях и зажиме.\n");
        return sb.toString();
    }

    private static String row(String name, List<Stat> s) {
        if (s.size() < 2) {
            return String.format(Locale.ROOT, "| %s | %d | — | — | — |%n", name, s.size());
        }
        List<double[]> bid = new ArrayList<>();
        List<double[]> ask = new ArrayList<>();
        List<double[]> both = new ArrayList<>();
        for (Stat x : s) {
            double set = x.seg().params().offset() * 10_000;
            bid.add(new double[]{set, x.bidMedian(), x.ticks()});
            ask.add(new double[]{set, x.askMedian(), x.ticks()});
            both.add(new double[]{set, (x.bidMedian() + x.askMedian()) / 2, x.ticks()});
        }
        return String.format(Locale.ROOT, "| %s | %d | %s | %s | %s |%n", name, s.size(),
                slope(bid), slope(ask), slope(both));
    }

    /** Взвешенный наклон МНК; вес — число тиков отрезка. */
    private static String slope(List<double[]> pts) {
        double sw = 0;
        double sx = 0;
        double sy = 0;
        for (double[] p : pts) {
            sw += p[2];
            sx += p[0] * p[2];
            sy += p[1] * p[2];
        }
        double mx = sx / sw;
        double my = sy / sw;
        double num = 0;
        double den = 0;
        for (double[] p : pts) {
            num += p[2] * (p[0] - mx) * (p[1] - my);
            den += p[2] * (p[0] - mx) * (p[0] - mx);
        }
        if (den <= 1e-9) {
            return "— (одна настройка)";
        }
        return String.format(Locale.ROOT, "%.2f", num / den);
    }

    /**
     * РАЗРЕЗ ПО СОСТОЯНИЮ ИНВЕНТАРЯ. Обе стороны считаются по ОДНИМ И ТЕМ ЖЕ
     * тикам внутри состояния, иначе средние несопоставимы (154 §V.1).
     */
    private static String byInventory(String bot, String path, List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segs) {
            double cap = s.params().inventoryCap();
            double target = s.params().skewTarget();
            if (!(cap > 0)) {
                continue;
            }
            // Состояния: пусто / ниже цели / около цели / выше цели.
            String[] names = {"пусто", "ниже цели", "около цели", "выше цели"};
            List<List<Double>> bids = new ArrayList<>();
            List<List<Double>> asks = new ArrayList<>();
            long[] ticks = new long[4];
            for (int i = 0; i < 4; i++) {
                bids.add(new ArrayList<>());
                asks.add(new ArrayList<>());
            }
            long total = 0;
            try (Connection c = ro(path);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT inventory, fair, bid, ask FROM exec_quote"
                                 + " WHERE ts_ms >= ? AND ts_ms < ? AND quotable = 1")) {
                ps.setLong(1, s.fromMs());
                ps.setLong(2, s.toMs());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double inv = rs.getDouble(1) / cap;
                        double fair = rs.getDouble(2);
                        double bid = rs.getDouble(3);
                        double ask = rs.getDouble(4);
                        if (!(fair > 0)) {
                            continue;
                        }
                        int st = inv < 0.02 ? 0
                                : inv < target * 0.8 ? 1
                                : inv < target * 1.2 ? 2 : 3;
                        ticks[st]++;
                        total++;
                        if (bid > 0) {
                            bids.get(st).add((fair - bid) / fair * 10_000);
                        }
                        if (ask > 0) {
                            asks.get(st).add((ask - fair) / fair * 10_000);
                        }
                    }
                }
            } catch (Exception e) {
                return "⚠️ " + bot + ": " + e + "\n";
            }
            if (total < 1000) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "**%s %s, настройка %.1f б.п., цель %.0f%%** "
                            + "(%s .. %s)%n%n", bot, s.symbol().replace("/USDC", ""),
                    s.params().offset() * 10_000, s.params().skewTarget() * 100,
                    day(s.fromMs()), day(s.toMs())));
            sb.append("| состояние | доля тиков | эфф. бид | эфф. аск | аск есть |\n");
            sb.append("|---|---:|---:|---:|---:|\n");
            for (int i = 0; i < 4; i++) {
                if (ticks[i] == 0) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "| %s | %.0f%% | %s | %s | %.0f%% |%n",
                        names[i], 100.0 * ticks[i] / total, med(bids.get(i)), med(asks.get(i)),
                        100.0 * asks.get(i).size() / ticks[i]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String med(List<Double> v) {
        if (v.isEmpty()) {
            return "—";
        }
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return String.format(Locale.ROOT, "%.2f", s.get(s.size() / 2));
    }

    /** Оценка κ по эффективному отступу для одного бота. */
    private record Kappa(String bot, String symbol, double kappaBid, double kappaAsk,
                         int bucketsBid, int bucketsAsk, boolean realPrices, String table) {
    }

    private static String kappaSection(Map<String, List<Kappa>> byPair) {
        StringBuilder sb = new StringBuilder("\n## 4. κ по ЭФФЕКТИВНОМУ отступу (живые заявки)\n\n");
        if (byPair.isEmpty()) {
            return sb.append("нет отрезков с достаточным числом исполнений\n").toString();
        }
        sb.append("| пара | бот | цена заявки | κ бид | корзин | κ аск | корзин |\n")
                .append("|---|---|---|---:|---:|---:|---:|\n");
        for (var e : byPair.entrySet()) {
            for (Kappa k : e.getValue()) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %s | %d |%n",
                        e.getKey().replace("/USDC", ""), k.bot(),
                        k.realPrices() ? "из тел запросов" : "⚠️ восстановлена",
                        k.kappaBid() > -900 ? String.format(Locale.ROOT, "%.3f", k.kappaBid()) : "—",
                        k.bucketsBid(),
                        k.kappaAsk() > -900 ? String.format(Locale.ROOT, "%.3f", k.kappaAsk()) : "—",
                        k.bucketsAsk()));
            }
        }
        sb.append("\nκ = наклон `ln λ` по эффективному отступу: во сколько раз падает поток\n")
                .append("исполнений на каждый базисный пункт расстояния. Лестница прогонов\n")
                .append("на BTC давала 0.385 (04–06.09, лот $3).\n");
        sb.append("\n⚠️ ЗДЕСЬ ОТСТУП ГУЛЯЕТ НЕ ПО НАШЕЙ ВОЛЕ, а вслед за инвентарём, и на\n")
                .append("биде это ломает оценку. Инвентарь пуст — значит в последние часы\n")
                .append("покупалось плохо (рынок уходил вверх), и скос ПОДТЯГИВАЕТ бид ровно\n")
                .append("в том режиме, где покупок мало. Расстояние и поток двигает одна и та\n")
                .append("же причина, поэтому наклон получается плоским и даже обратным.\n")
                .append("На аске подмена слабее, и там наклон сходится с лестничным.\n")
                .append("🔑 Читать эту таблицу как κ можно только по АСКУ; бид здесь —\n")
                .append("демонстрация того, что своя же обратная связь измерению мешает.\n");
        for (var e : byPair.entrySet()) {
            for (Kappa k : e.getValue()) {
                sb.append(k.table());
            }
        }
        return sb.toString();
    }

    /**
     * 🔑 ЧИСЛИТЕЛЬ И ЗНАМЕНАТЕЛЬ ОБЯЗАНЫ МЕРИТЬ ОДИН И ТОТ ЖЕ ОБЪЕКТ.
     *
     * Первая версия считала время по ЦЕЛИ котировщика, а исполнения — по
     * ФАКТИЧЕСКОЙ цене сделки, и получилась бессмыслица: у бота A 40 покупок
     * пришло в корзину 6 б.п., где цель простояла полтора часа за две недели.
     * Причина не в данных: цель пересчитывается каждый тик и всегда стоит в
     * своих 12 б.п. от свежей справедливой цены, а ЗАЯВКА стоит там, куда её
     * довёз последний replace, — и исполняется она ровно тогда, когда цена к
     * ней подошла, то есть на маленьком расстоянии от НОВОЙ справедливой цены.
     * Хазард {@code λ(δ)} требует, чтобы обе половины считались по цене
     * стоящей заявки.
     *
     * Поэтому цена стоящей заявки берётся ИЗ ТЕЛ ЗАПРОСОВ: {@code --placed=}
     * указывает на срез {@code exec_request}, где у каждой постановки и замены
     * оставлены только отметка времени, статус и цена. Последняя УСПЕШНАЯ цена
     * и есть то, что стоит в книге, пока её не сменит следующая.
     *
     * ⚠️ Сторона в теле {@code PUT} отсутствует (она наследуется по цепочке
     * замен, см. ТЗ и разбор 09.09.2026) и восстанавливается по цене: заявка
     * ниже справедливой цены — бид, выше — аск. Пересечения не бывает: отступы
     * держатся в 6–25 б.п., а {@code post_only} не дал бы заявке уйти за рынок.
     *
     * ⚠️ Без {@code --placed} прибор откатывается к восстановлению по ряду
     * целей с порогом перевыставления {@code requote-threshold}. Это заметно
     * хуже: реальный бот делает одну замену за тик на обе стороны и стоит в
     * паузах после отказов, поэтому заявка отстаёт от цели сильнее, чем
     * получается у правила.
     *
     * ⚠️ Считается только по отрезкам с ОДНИМ уровнем: у многоуровневого бота в
     * книге стоят три заявки, а какая из них какая — по цене уже не разобрать.
     */
    private static final double REQUOTE_THRESHOLD = 0.00005;

    private static Kappa kappa(String bot, String path, String placedPath, List<Segment> segs) {
        if (segs.isEmpty()) {
            return null;
        }
        // Время стояния по корзинам эффективного отступа, миллисекунды.
        TreeMap<Integer, Double> timeBid = new TreeMap<>();
        TreeMap<Integer, Double> timeAsk = new TreeMap<>();
        TreeMap<Integer, Integer> fillBid = new TreeMap<>();
        TreeMap<Integer, Integer> fillAsk = new TreeMap<>();
        TreeMap<Long, double[]> fairByTs = new TreeMap<>();
        String symbol = segs.get(segs.size() - 1).symbol();
        long from = segs.get(0).fromMs();
        long to = segs.get(segs.size() - 1).toMs();
        // Отрезки с одним уровнем: только по ним считается время стояния.
        List<long[]> single = new ArrayList<>();
        for (Segment s : segs) {
            if (s.params().levels() == 1) {
                single.add(new long[]{s.fromMs(), s.toMs()});
            }
        }
        if (single.isEmpty()) {
            return null;
        }
        // Цены РЕАЛЬНО поставленных заявок, если срез тел запросов передан.
        TreeMap<Long, Double> placed = placedPath == null ? null : placedPrices(placedPath);
        boolean real = placed != null && !placed.isEmpty();
        try (Connection c = ro(path)) {
            for (long[] win : single) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT ts_ms, fair, bid, ask FROM exec_quote WHERE ts_ms >= ? AND ts_ms < ?"
                                + " AND quotable = 1 ORDER BY ts_ms")) {
                    ps.setLong(1, win[0]);
                    ps.setLong(2, win[1]);
                    long prev = -1;
                    double prevFair = 0;
                    double restBid = 0;
                    double restAsk = 0;
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long ts = rs.getLong(1);
                            double fair = rs.getDouble(2);
                            double bid = rs.getDouble(3);
                            double ask = rs.getDouble(4);
                            if (fair > 0) {
                                fairByTs.put(ts, new double[]{fair});
                            }
                            // Время ПРЕДЫДУЩЕГО состояния: заявка стояла на
                            // restBid/restAsk, пока не наступил этот тик.
                            if (prev > 0 && prevFair > 0) {
                                // ⚠️ Разрывы больше пяти минут — это простой бота,
                                // а не стояние заявки: иначе одна ночная
                                // остановка перевесит все настоящие тики окна.
                                double dt = Math.min(ts - prev, 300_000);
                                if (restBid > 0) {
                                    timeBid.merge(bucket((prevFair - restBid) / prevFair * 1e4),
                                            dt, Double::sum);
                                }
                                if (restAsk > 0) {
                                    timeAsk.merge(bucket((restAsk - prevFair) / prevFair * 1e4),
                                            dt, Double::sum);
                                }
                            }
                            if (real) {
                                // Все заявки, поставленные между прошлым и этим
                                // тиком: сторона — по положению относительно
                                // справедливой цены того же момента.
                                for (var e : placed.subMap(prev <= 0 ? ts - 1 : prev, false, ts, true)
                                        .entrySet()) {
                                    double p = e.getValue();
                                    if (!(p > 0) || !(fair > 0)) {
                                        continue;
                                    }
                                    if (p < fair) {
                                        restBid = p;
                                    } else {
                                        restAsk = p;
                                    }
                                }
                            } else {
                                // Перестановка по тому же правилу, что у бота.
                                restBid = moved(restBid, bid);
                                restAsk = moved(restAsk, ask);
                            }
                            // Сторона без котировки — в книге её нет вовсе.
                            if (!(bid > 0)) {
                                restBid = 0;
                            }
                            if (!(ask > 0)) {
                                restAsk = 0;
                            }
                            prev = ts;
                            prevFair = fair;
                        }
                    }
                }
            }
            for (long[] win : single) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT ts_ms, side, price, status FROM exec_fill"
                                + " WHERE ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
                    ps.setLong(1, win[0]);
                    ps.setLong(2, win[1]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long ts = rs.getLong(1) - FILL_LAG_MS;
                            String side = rs.getString(2);
                            double price = rs.getDouble(3);
                            String status = rs.getString(4);
                            if (price <= 0 || "handover".equals(status)) {
                                continue;
                            }
                            var e = fairByTs.floorEntry(ts);
                            if (e == null || ts - e.getKey() > 60_000) {
                                continue;
                            }
                            double fair = e.getValue()[0];
                            boolean buy = side != null
                                    && side.toLowerCase(Locale.ROOT).startsWith("b");
                            double dist = buy ? (fair - price) / fair * 1e4
                                    : (price - fair) / fair * 1e4;
                            (buy ? fillBid : fillAsk).merge(bucket(dist), 1, Integer::sum);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("κ по эффективному отступу {}: {}", bot, ex.toString());
            return null;
        }
        double[] kb = fit(timeBid, fillBid);
        double[] ka = fit(timeAsk, fillAsk);
        StringBuilder t = new StringBuilder(String.format(Locale.ROOT,
                "%n**%s (%s)** — время стояния и исполнения по корзинам эффективного отступа%n%n",
                bot, symbol.replace("/USDC", "")));
        t.append("| δ эфф., б.п. | часов на биде | покупок | λ бид, /сут | часов на аске |")
                .append(" продаж | λ аск, /сут |\n|---:|---:|---:|---:|---:|---:|---:|\n");
        TreeMap<Integer, Boolean> keys = new TreeMap<>();
        timeBid.keySet().forEach(k -> keys.put(k, true));
        timeAsk.keySet().forEach(k -> keys.put(k, true));
        for (int k : keys.keySet()) {
            double hb = timeBid.getOrDefault(k, 0.0) / 3_600_000.0;
            double ha = timeAsk.getOrDefault(k, 0.0) / 3_600_000.0;
            int fb = fillBid.getOrDefault(k, 0);
            int fa = fillAsk.getOrDefault(k, 0);
            if (hb < 0.5 && ha < 0.5 && fb == 0 && fa == 0) {
                continue;
            }
            t.append(String.format(Locale.ROOT, "| %.0f | %.1f | %d | %s | %.1f | %d | %s |%n",
                    k * BUCKET_BP, hb, fb, rate(fb, hb), ha, fa, rate(fa, ha)));
        }
        return new Kappa(bot, symbol, kb[0], ka[0], (int) kb[1], (int) ka[1], real, t.toString());
    }

    /** Цены УСПЕШНЫХ постановок и замен из среза тел запросов. */
    private static TreeMap<Long, Double> placedPrices(String path) {
        TreeMap<Long, Double> out = new TreeMap<>();
        try (Connection c = ro(path);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, price FROM placed WHERE status IN (200, 201) AND price > 0"
                             + " ORDER BY ts_ms");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(1), rs.getDouble(2));
            }
        } catch (Exception e) {
            log.warn("цены поставленных заявок {}: {}", path, e.toString());
        }
        return out;
    }

    /**
     * Куда переедет заявка к следующему тику: правило {@code Quoter.shouldRequote}.
     *
     * @param resting цена, стоящая сейчас (0 — заявки нет)
     * @param target  цель котировщика на этом тике (0 — сторона подавлена)
     */
    static double moved(double resting, double target) {
        if (!(target > 0)) {
            return 0;                      // сторону не котируют — в книге пусто
        }
        if (!(resting > 0)) {
            return target;                 // слот пуст: ставим по текущей цели
        }
        return Math.abs(target - resting) / resting > REQUOTE_THRESHOLD ? target : resting;
    }

    private static String rate(int fills, double hours) {
        return hours < 0.5 ? "—" : String.format(Locale.ROOT, "%.1f", fills * 24.0 / hours);
    }

    /**
     * Взвешенная регрессия {@code ln λ} по корзинам; вес — число исполнений.
     *
     * @return {@code {κ, число корзин}}; {@code κ = −999}, если корзин мало
     */
    static double[] fit(TreeMap<Integer, Double> time, TreeMap<Integer, Integer> fills) {
        List<double[]> pts = new ArrayList<>();
        for (var e : time.entrySet()) {
            int n = fills.getOrDefault(e.getKey(), 0);
            double days = e.getValue() / 86_400_000.0;
            if (n < 5 || days < 0.02) {
                continue;
            }
            pts.add(new double[]{e.getKey() * BUCKET_BP, Math.log(n / days), n});
        }
        if (pts.size() < 3) {
            return new double[]{-999, pts.size()};
        }
        double sw = 0;
        double sx = 0;
        double sy = 0;
        for (double[] p : pts) {
            sw += p[2];
            sx += p[0] * p[2];
            sy += p[1] * p[2];
        }
        double mx = sx / sw;
        double my = sy / sw;
        double num = 0;
        double den = 0;
        for (double[] p : pts) {
            num += p[2] * (p[0] - mx) * (p[1] - my);
            den += p[2] * (p[0] - mx) * (p[0] - mx);
        }
        return new double[]{den <= 1e-9 ? -999 : -num / den, pts.size()};
    }

    static int bucket(double bp) {
        return (int) Math.floor(bp / BUCKET_BP);
    }

    /** Отрезки постоянной настройки: между событиями {@code boot}. */
    private static List<Segment> segments(String bot, String path, long from, long to) {
        List<Segment> out = new ArrayList<>();
        List<long[]> bootTs = new ArrayList<>();
        List<BootParams> params = new ArrayList<>();
        try (Connection c = ro(path);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts_ms, detail FROM exec_event WHERE kind = 'boot' ORDER BY ts_ms")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BootParams bp = BootParams.parse(rs.getString(2));
                    if (bp == null) {
                        continue;
                    }
                    // Перезапуск с ТЕМИ ЖЕ настройками отрезок не режет: иначе
                    // одни сутки разбились бы на десяток кусков по три часа, и
                    // в каждом не хватило бы исполнений.
                    if (!params.isEmpty() && same(params.get(params.size() - 1), bp)) {
                        continue;
                    }
                    bootTs.add(new long[]{rs.getLong(1)});
                    params.add(bp);
                }
            }
            long last = 0;
            try (PreparedStatement ps2 = c.prepareStatement("SELECT MAX(ts_ms) FROM exec_quote");
                 ResultSet rs = ps2.executeQuery()) {
                if (rs.next()) {
                    last = rs.getLong(1);
                }
            }
            for (int i = 0; i < params.size(); i++) {
                long a = Math.max(bootTs.get(i)[0], from);
                long b = Math.min(i + 1 < params.size() ? bootTs.get(i + 1)[0] : last, to);
                if (b > a) {
                    out.add(new Segment(bot, params.get(i).symbol(), a, b, params.get(i)));
                }
            }
        } catch (Exception e) {
            log.warn("отрезки {}: {}", bot, e.toString());
        }
        return out;
    }

    /** Настройки, задающие цену заявки. Остальные различия отрезок не режут. */
    private static boolean same(BootParams a, BootParams b) {
        return a.offset() == b.offset() && a.skewK() == b.skewK()
                && a.skewTarget() == b.skewTarget() && a.levels() == b.levels()
                && a.levelStep() == b.levelStep() && a.inventoryCap() == b.inventoryCap()
                && a.size() == b.size() && a.symbol().equals(b.symbol());
    }

    private static Stat measure(String path, Segment s) {
        List<Double> bid = new ArrayList<>();
        List<Double> ask = new ArrayList<>();
        long ticks = 0;
        double area = 0;
        double span = 0;
        double emptyMs = 0;
        long prev = -1;
        double prevInv = 0;
        double lot = s.params().size();
        try (Connection c = ro(path)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, fair, bid, ask, inventory, quotable FROM exec_quote"
                            + " WHERE ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms")) {
                ps.setLong(1, s.fromMs());
                ps.setLong(2, s.toMs());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long ts = rs.getLong(1);
                        double fair = rs.getDouble(2);
                        double b = rs.getDouble(3);
                        double a = rs.getDouble(4);
                        double inv = rs.getDouble(5);
                        boolean quotable = rs.getInt(6) == 1;
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
                        if (!quotable || !(fair > 0)) {
                            continue;
                        }
                        ticks++;
                        if (b > 0) {
                            bid.add((fair - b) / fair * 10_000);
                        }
                        if (a > 0) {
                            ask.add((a - fair) / fair * 10_000);
                        }
                    }
                }
            }
            long buys = 0;
            long sells = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT side, COUNT(*) FROM exec_fill WHERE ts_ms >= ? AND ts_ms < ?"
                            + " AND (status IS NULL OR status <> 'handover') GROUP BY side")) {
                ps.setLong(1, s.fromMs());
                ps.setLong(2, s.toMs());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String side = rs.getString(1);
                        if (side != null && side.toLowerCase(Locale.ROOT).startsWith("b")) {
                            buys = rs.getLong(2);
                        } else {
                            sells = rs.getLong(2);
                        }
                    }
                }
            }
            if (ticks == 0 || span <= 0) {
                return null;
            }
            double days = span / 86_400_000.0;
            Collections.sort(bid);
            Collections.sort(ask);
            return new Stat(s, ticks, span / 3_600_000.0,
                    bid.isEmpty() ? Double.NaN : bid.get(bid.size() / 2),
                    ask.isEmpty() ? Double.NaN : ask.get(ask.size() / 2),
                    mean(bid), mean(ask), (double) ask.size() / ticks,
                    lot > 0 ? area / span / lot : Double.NaN, emptyMs / span,
                    buys / days, sells / days, lot);
        } catch (Exception e) {
            log.warn("отрезок {}: {}", s.bot(), e.toString());
            return null;
        }
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.isEmpty() ? Double.NaN : s / v.size();
    }

    private static Connection ro(String path) throws java.sql.SQLException {
        return DriverManager.getConnection("jdbc:sqlite:file:"
                + Path.of(path).toAbsolutePath() + "?mode=ro");
    }

    private static String day(long ms) {
        return Instant.ofEpochMilli(ms).toString().substring(5, 16).replace('T', ' ');
    }

    private static void write(String out, String text) {
        if (out == null || out.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(out);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, text, StandardCharsets.UTF_8);
            log.warn("ось лестницы записана в {}", p.toAbsolutePath());
        } catch (Exception e) {
            log.warn("не записалось в {}: {}", out, e.toString());
        }
    }
}
