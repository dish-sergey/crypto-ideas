package org.home.data.revx.sim;

import org.home.data.revx.replay.MarketData;
import org.home.data.revx.replay.ReplayFair;
import org.home.data.revx.replay.StandFair;
import org.home.data.revx.replay.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * КРИВАЯ НЕБЛАГОПРИЯТНОГО ОТБОРА ПО ЛЕНТЕ, БЕЗ ЕДИНОЙ СДЕЛКИ.
 *
 * <h2>Зачем</h2>
 *
 * Неблагоприятный отбор — свойство ПОТОКА, а не нашей заявки. Значит его можно
 * мерить по каждому принту ленты, не торгуя. Выигрыш в данных десятикратный: на
 * BTC наших исполнений 88 в сутки, а принтов на ленте 964.
 *
 * Главное же в том, ЧТО получается на выходе. Наши замеры дают одно число —
 * markout на боевой настройке. Здесь получается ВСЯ КРИВАЯ {@code c(δ)} разом, а
 * без неё формула оптимума {@code δ* = c + 1/κ} неприменима: в ней {@code c} —
 * функция от {@code δ}, а не константа.
 *
 * <h2>Что считается</h2>
 *
 * Для каждого принта:
 * <pre>
 *   δ       = насколько далеко принт ушёл от справедливой цены, б.п., СО ЗНАКОМ
 *             стороны: δ &gt; 0 значит «дотянулся бы до нашей заявки на δ»
 *   m(H)    = агрессор × (fair(t+H) − fair(t)) / fair(t), б.п.
 *   c(δ)    = среднее m по принтам с расстоянием ≥ δ
 *   λ(δ)    = сколько таких принтов в сутки
 * </pre>
 *
 * ⚠️ <b>База markout — справедливая цена в момент принта, а НЕ цена принта.</b>
 * Та же причина, по которой это сделано в {@link Markout}: при базе «цена
 * принта» в markout попадает захват спреда, и сумма «захват + markout» считает
 * захват дважды. Здесь эта ошибка была бы особенно коварной, потому что цена
 * принта и есть δ — величина, по которой мы группируем.
 *
 * ⚠️ <b>Знак.</b> Агрессор покупает — значит пассивная сторона (мы) продала.
 * Отбор равен тому, насколько цена ушла В ПОЛЬЗУ АГРЕССОРА после принта, то есть
 * {@code c > 0} — это наш убыток. Так же он и печатается: положительное
 * {@code c} означает «столько базисных пунктов у нас забирают».
 *
 * <h2>κ из той же ленты</h2>
 *
 * {@code λ(δ)} падает с расстоянием примерно экспоненциально, и наклон
 * {@code ln λ} по {@code δ} и есть {@code −κ}. Это ровно то, что мы мерили
 * лестницей прогонов, но без единого прогона и на всём диапазоне сразу.
 * Оценивается два раза: на всём окне и скользящим окном — потому что κ есть
 * свойство рынка на отрезке, а не константа алгоритма.
 */
public final class FlowMarkout {

    private static final Logger log = LoggerFactory.getLogger(FlowMarkout.class);

    /** Ступени расстояния, на которых считается кривая, б.п. */
    private static final double[] GRID = {2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28};

    /** Горизонты markout. 60 с — боевой, остальные показывают, устаканивается ли отбор. */
    private static final long[] HORIZONS = {10_000, 60_000, 300_000};

    /** Один принт со всем, что о нём известно. */
    record Print(long tsMs, double price, double qty, int aggressor,
                         double distBp, double fair, long gapMs, int burst, double imbalance) {
    }

    private FlowMarkout() {
    }

    public static void run(String standDbPath, String bases, String fromIso, String toIso,
                           java.util.Collection<String> memecoins, FairPrice.Limits limits,
                           long maxSkewMs) {
        long from = java.time.Instant.parse(fromIso).toEpochMilli();
        long to = java.time.Instant.parse(toIso).toEpochMilli();
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== КРИВАЯ ОТБОРА ПО ЛЕНТЕ (без торговли) ===\n");
        sb.append("окно: ").append(fromIso).append(" .. ").append(toIso).append('\n');
        double days = (to - from) / 86_400_000.0;
        sb.append(String.format(Locale.ROOT, "суток: %.2f%n", days));

        for (String base : bases.split(",")) {
            base = base.trim();
            if (base.isEmpty()) {
                continue;
            }
            try {
                sb.append(one(standDbPath, base, from, to, days, memecoins, limits, maxSkewMs));
            } catch (Exception e) {
                sb.append("\n").append(base).append(": ").append(e).append('\n');
                log.warn("кривая отбора {}: {}", base, e.toString());
            }
        }
        log.warn("\n{}", sb);
    }

    private static String one(String standDbPath, String base, long from, long to, double days,
                              java.util.Collection<String> memecoins, FairPrice.Limits limits,
                              long maxSkewMs) {
        // Справедливая цена — та же, от которой котирует бот: иначе кривая
        // описывала бы отбор относительно опоры, которой мы не пользуемся.
        StandFair fairSrc = new StandFair(standDbPath, base, limits, memecoins, maxSkewMs,
                new SimClock(from), from, to);
        TreeMap<Long, Double> fair = new TreeMap<>();
        for (ReplayFair.Tick t : fairSrc.toTicks(base)) {
            if (t.quotable() && t.fair() > 0) {
                fair.put(t.tsMs(), t.fair());
            }
        }
        if (fair.size() < 100) {
            return "\n" + base + ": справедливой цены почти нет (" + fair.size() + " тиков)\n";
        }

        TreeMap<Long, Top> book = book(standDbPath, base + "/USDC", from, to);
        MarketData md = MarketData.load(standDbPath, base + "/USDC", from, to);

        StringBuilder sb = new StringBuilder("\n--- " + base + " ---\n");
        sb.append(String.format(Locale.ROOT,
                "тиков справедливой цены %d, снимков книги %d%n", fair.size(), book.size()));
        // ⚠️ Разреженная опора делает кривую бессмысленной, и молча. На окне
        // 25-27.08.2026 справедливой цены нашлось 9905 тиков за двое суток — один
        // в 17 секунд, — и «расстояние принта» стало мерить её устаревание:
        // медиана 9.75 при хвостах ±50..84 б.п. против ±13 на здоровом окне.
        if (fair.size() / days < 20_000) {
            sb.append("⚠️ опора разрежена (").append(String.format(Locale.ROOT, "%.0f", fair.size() / days))
                    .append(" тиков в сутки) — расстояние будет мерить её устаревание, не поток\n");
        }

        sb.append(anchors(md, fair, book));

        Ref ref = Ref.valueOf(System.getProperty("revx.flow.ref", "FAIR").toUpperCase(Locale.ROOT));
        sb.append("опора расчёта: ").append(ref).append('\n');

        List<Print> prints = new ArrayList<>();
        long prevTs = 0;
        Map<Long, Integer> burstSize = new LinkedHashMap<>();
        for (MarketTrade t : md.trades()) {
            burstSize.merge(t.tsMs(), 1, Integer::sum);
        }
        for (MarketTrade t : md.trades()) {
            if (t.aggressor() == null) {
                continue;                    // сторону не выводим — см. ТЗ §4.3
            }
            Double anchor = anchor(ref, t.tsMs(), fair, book);
            if (anchor == null || anchor <= 0) {
                continue;
            }
            int agg = t.aggressor() == Side.BUY ? 1 : -1;
            double dist = 1e4 * agg * (t.price() - anchor) / anchor;
            Map.Entry<Long, Top> bk = book.floorEntry(t.tsMs());
            prints.add(new Print(t.tsMs(), t.price(), t.qty(), agg, dist, anchor,
                    prevTs == 0 ? -1 : t.tsMs() - prevTs,
                    burstSize.getOrDefault(t.tsMs(), 1),
                    bk == null ? Double.NaN : bk.getValue().imbalance()));
            prevTs = t.tsMs();
        }
        if (prints.size() < 50) {
            return sb.append("принтов со стороной всего ").append(prints.size()).append('\n').toString();
        }

        sb.append(String.format(Locale.ROOT,
                "принтов со стороной %d (%.0f в сутки)%n", prints.size(), prints.size() / days));

        // Распределение расстояний — чтобы видеть, где вообще есть поток.
        List<Double> d = prints.stream().map(Print::distBp).sorted().toList();
        sb.append(String.format(Locale.ROOT,
                "расстояние от опоры, б.п.: медиана %.2f (10%% %.2f, 90%% %.2f)%n",
                q(d, 0.5), q(d, 0.10), q(d, 0.90)));

        sb.append("\nКРИВАЯ: сколько СОБЫТИЙ дотягивается до δ и что они с нами делают\n");
        sb.append("  δ,б.п. | событий | в сутки |  c(10с) |  c(60с) | c(300с) | доля покупок\n");
        Map<Double, Double> lambda = new LinkedHashMap<>();
        for (double dist : GRID) {
            List<Print> reach = events(prints, dist);
            if (reach.isEmpty()) {
                continue;
            }
            lambda.put(dist, reach.size() / days);
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT,
                    "  %6.0f | %7d | %7.1f", dist, reach.size(), reach.size() / days));
            for (long h : HORIZONS) {
                Double c = cost(reach, fair, h);
                row.append(c == null ? String.format(Locale.ROOT, " | %7s", "—")
                        : String.format(Locale.ROOT, " | %+7.2f", c));
            }
            long buys = reach.stream().filter(p -> p.aggressor() > 0).count();
            row.append(String.format(Locale.ROOT, " | %11.0f%%", 100.0 * buys / reach.size()));
            sb.append(row).append('\n');
        }
        sb.append("⚠️ c > 0 — столько б.п. у нас ЗАБИРАЮТ: цена уходит в пользу агрессора.\n");
        sb.append("«доля покупок» — предохранитель от беты: сильный перекос значит, что\n");
        sb.append("кривая меряет направление рынка, а не отбор.\n");

        // ⚠️ Безусловный контроль обязан считаться по ТОЙ ЖЕ опоре, от которой
        // считаются круги: иначе из круга по одной цене вычитается рынок по
        // другой, и разность содержит их расхождение.
        TreeMap<Long, Double> anchorSeries = new TreeMap<>();
        if (ref == Ref.FAIR) {
            anchorSeries = fair;
        } else {
            for (Long ts : book.keySet()) {
                Double a = anchor(ref, ts, fair, book);
                if (a != null && a > 0) {
                    anchorSeries.put(ts, a);
                }
            }
        }
        sb.append(settingMetric(prints, days, anchorSeries, lambda));
        sb.append(costByHorizon(prints, fair));
        sb.append(unloadWait(prints, days, fair));
        sb.append(afterSweep(prints, days, fair));
        sb.append(drawdownHint(prints, fair));
        sb.append(drawdownBySpeed(prints, fair));
        sb.append(reversalBothSides(prints, fair));
        sb.append(payPerRisk(prints, days, fair, halfSpread(book), book));
        sb.append(kappa(lambda, days));
        sb.append(rollingKappa(prints, days, from, to));
        sb.append(slices(prints, fair));
        return sb.toString();
    }

    /**
     * ⚠️ СОБЫТИЯ, А НЕ ПРИНТЫ. Ключевое место всего прибора.
     *
     * Одна рыночная заявка, разметающая книгу, приходит на ленту НЕСКОЛЬКИМИ
     * принтами с одной отметкой времени и разными ценами. Наша заявка на δ
     * исполняется при этом ОДИН раз — первым принтом, дотянувшимся до δ. Если
     * считать все принты, дальние уровни свипа попадают в ведро по нескольку
     * раз, а markout у них самый большой: кривая {@code c(δ)} задирается вверх
     * ровно там, где мы по ней выбираем отступ.
     *
     * Насколько это важно, видно из первого прогона: без схлопывания край
     * {@code δ − c(δ)} выходил отрицательным на ВСЕХ ступенях и всех трёх парах,
     * что противоречит живому замеру края (+3.9 у BTC, +5.5 у ETH, +16.9 у SOL).
     *
     * Порог 50 мс — не круглое число: замер 10.09.2026 показал, что 20 из 21
     * принта «мёртвого слота» приходят в пределах 50 мс, то есть это и есть
     * характерная длительность одной пачки.
     */
    private static final long BURST_MS = 50;

    static List<Print> events(List<Print> prints, double dist) {
        List<Print> out = new ArrayList<>();
        long lastTs = Long.MIN_VALUE;
        int lastSide = 0;
        for (Print p : prints) {
            if (p.distBp() < dist) {
                continue;
            }
            // Пачкой считаем последовательные принты одной стороны, идущие
            // вплотную: разворот стороны — это уже другое событие.
            if (p.tsMs() - lastTs <= BURST_MS && p.aggressor() == lastSide) {
                continue;
            }
            out.add(p);
            lastTs = p.tsMs();
            lastSide = p.aggressor();
        }
        return out;
    }

    /**
     * Стоимость отбора на горизонте: насколько цена ушла В ПОЛЬЗУ АГРЕССОРА.
     *
     * Принты, у которых горизонт выходит за данные, отбрасываются целиком —
     * иначе хвост выборки молча смещает результат в сторону тихого конца окна.
     */
    private static Double cost(List<Print> prints, TreeMap<Long, Double> fair, long horizonMs) {
        long last = fair.lastKey();
        double sum = 0;
        int n = 0;
        for (Print p : prints) {
            if (p.tsMs() + horizonMs > last) {
                continue;
            }
            Map.Entry<Long, Double> f = fair.floorEntry(p.tsMs() + horizonMs);
            if (f == null || f.getValue() <= 0) {
                continue;
            }
            sum += 1e4 * p.aggressor() * (f.getValue() - p.fair()) / p.fair();
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /**
     * 🔑 МЕРКА НАСТРОЙКИ ПО ЛЕНТЕ — синтетические круги (док. 154 §I, блок 1).
     *
     * <h2>Вопрос, на который она отвечает</h2>
     *
     * Нужна величина, которая (а) не зависит от попавшейся траектории и (б)
     * видит средний снос. Суточное отношение по живым кругам снос видит, но на
     * трёх сутках его разброс шире эффекта; {@code 2δ/σ√T} от траектории не
     * зависит, но сноса не видит по построению (задача A40).
     *
     * Разбор 154 указывает выход: траектория сидит ровно в ОДНОМ слагаемом
     * тождества {@code круг = вход + снос + выход}, а именно в рыночном ходе за
     * время круга. Его надо вычесть безусловным контролем — тем же, что уже
     * считает {@code --revx-hold-check}, — а не пытаться усреднить.
     *
     * <h2>Как строится круг БЕЗ ТОРГОВЛИ</h2>
     *
     * По ленте, для каждого δ:
     * <pre>
     *   пусто  + событие, дотянувшееся до δ со стороны ПРОДАВЦА → купили
     *   в лонге + событие, дотянувшееся до δ со стороны ПОКУПАТЕЛЯ → продали
     *   круг = 2δ + (fair_закр − fair_откр)/fair_откр
     * </pre>
     * То есть это ровно тот же круг, что у живого бота, только исполнения
     * берутся из ленты, а не из наших сделок. Выигрыш — в данных: на BTC
     * принтов в сутки в десять раз больше, чем наших исполнений, и мерить можно
     * настройку, которая никогда не стояла.
     *
     * <h2>Что печатается</h2>
     *
     * <ul>
     *   <li>{@code λ} — кругов в сутки: сколько раз настройка успевает
     *       обернуться. Считается по ПАРАМ событий, а не по принтам;</li>
     *   <li>средний круг — сырой, вместе с рынком;</li>
     *   <li>{@code рынок} — безусловный контроль: средний ход опоры за ТО ЖЕ
     *       время, посчитанный от каждой минуты окна. Это и есть траектория;</li>
     *   <li>{@code 2δ − c} — круг за вычетом рынка, то есть захват минус отбор.
     *       Величина из §I, которая от траектории не зависит;</li>
     *   <li>СКО круга и суточное отношение {@code √λ·(2δ − c)/СКО}.</li>
     * </ul>
     *
     * <h2>⚠️ Чего она не знает</h2>
     *
     * Очереди, видимости и того, что лот у нас один: предполагается, что каждое
     * дотянувшееся событие — наше исполнение. Это ВЕРХНЯЯ оценка λ, и потому
     * верхняя оценка отношения. Зато она одинаково верхняя для всех δ, а
     * сравниваем мы настройки между собой.
     *
     * ⚠️ И второе: круг здесь закрывается ПЕРВЫМ встречным событием, то есть
     * без скоса, потолка и уровней. Это мерка ПЛОЩАДКИ при данной настройке
     * отступа, а не мерка бота целиком.
     */
    /**
     * Сколько кругов нужно ступени, чтобы её можно было читать.
     *
     * Тридцать — не круглое число, а порог, ниже которого одна многочасовая
     * позиция решает всю строку: у SOL на δ = 28 кругов тринадцать, и один из
     * них с ходом опоры в 80 б.п. выносит эту ступень на первое место по
     * отношению. Ступени ниже порога печатаются с пометкой «?» и в выбор
     * оптимума не идут.
     */
    private static final int MIN_ROUNDS = 30;

    private static String settingMetric(List<Print> prints, double days,
                                        TreeMap<Long, Double> fair, Map<Double, Double> lambda) {
        TreeMap<Long, Double> byMinute = new TreeMap<>();
        for (Map.Entry<Long, Double> e : fair.entrySet()) {
            byMinute.putIfAbsent(e.getKey() / 60_000, e.getValue());
        }
        if (byMinute.size() < 60) {
            return "\nМЕРКА НАСТРОЙКИ: опоры меньше часа — не считаю\n";
        }
        StringBuilder sb = new StringBuilder(
                "\n🔑 МЕРКА НАСТРОЙКИ: синтетические круги по ленте (док. 154 §I)\n");
        sb.append("  δ,б.п. | кругов/сут | круг сырой | рынок | ЗА ВЫЧЕТОМ РЫНКА | c |  СКО |"
                + " T медиана | в позиции | б.п./сут | ОТНОШЕНИЕ/сут\n");
        Map<Double, double[]> byDelta = new LinkedHashMap<>();
        for (double dist : GRID) {
            List<Round> rounds = rounds(prints, dist, fair);
            if (rounds.size() < 10) {
                continue;
            }
            List<Double> raw = new ArrayList<>();
            List<Double> net = new ArrayList<>();
            List<Double> hold = new ArrayList<>();
            double marketSum = 0;
            for (Round r : rounds) {
                double mins = (r.closeMs() - r.openMs()) / 60_000.0;
                double market = marketDrift(byMinute, (int) Math.max(1, Math.round(mins)));
                raw.add(r.valueBp());
                net.add(r.valueBp() - market);
                hold.add(mins);
                marketSum += market;
            }
            double lam = rounds.size() / days;
            double mean = mean(net);
            double sd = sd(net);
            double ratio = sd > 0 ? mean / sd * Math.sqrt(lam) : Double.NaN;
            List<Double> sorted = new ArrayList<>(hold);
            java.util.Collections.sort(sorted);
            // Доля времени под позицией — закон Литтла на этих же кругах:
            // средний инвентарь (у нас он 0 или 1 лот) = λ × держание.
            double inPosition = Math.min(1, lam * mean(hold) / 1440);
            // ⚠️ Ступень с горсткой кругов читать нельзя, и молчать об этом
            // тоже: у SOL на δ = 28 кругов всего тринадцать, среди них один
            // многочасовой с ходом в 80 б.п., — и по «лучшему отношению» такая
            // строка выигрывает у всей таблицы.
            boolean thin = rounds.size() < MIN_ROUNDS;
            sb.append(String.format(Locale.ROOT,
                    "  %6.0f | %10.1f | %+10.2f | %+5.2f | %+16.2f | %+5.2f | %4.1f | %9.0f м |"
                            + " %8.0f%% | %+8.1f | %13.2f%s%n",
                    dist, lam, mean(raw), marketSum / rounds.size(), mean, 2 * dist - mean,
                    sd, q(sorted, 0.5), 100 * inPosition, lam * mean, ratio,
                    thin ? " ? (кругов " + rounds.size() + ")" : ""));
            byDelta.put(dist, new double[]{lam, mean, sd, ratio, 2 * dist - mean, rounds.size()});
        }
        if (byDelta.isEmpty()) {
            return sb.append("  кругов не набралось ни на одной ступени\n").toString();
        }
        sb.append("«круг сырой» = 2δ + ход опоры за круг; «рынок» — тот же ход, но от КАЖДОЙ\n");
        sb.append("минуты окна (безусловный контроль). Их разность и есть величина §I: она\n");
        sb.append("не зависит от того, росло ли в эти сутки. c = 2δ − (круг за вычетом рынка).\n");
        sb.append("⚠️ ВЕРХНЯЯ оценка: считается, что каждое дотянувшееся событие — наше\n");
        sb.append("исполнение. Очередь, видимость и единственный лот могут только ухудшить.\n");
        sb.append(optimum(byDelta, lambda, days));
        // Гейты считаются на той ступени, где кругов больше всего: там у
        // сравнения вариантов наибольшая разрешающая способность.
        double best = byDelta.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue()[0]))
                .map(Map.Entry::getKey).orElse(6.0);
        sb.append(gates(prints, fair, byMinute, Math.max(best, 6), days));
        return sb.toString();
    }

    /** Синтетический круг: открылся, закрылся, сколько дал в б.п. */
    record Round(long openMs, long closeMs, double valueBp) {
    }

    /**
     * ГЕЙТЫ ПО СОСТОЯНИЮ ПОТОКА — блок 3 разбора 154, меркой из блока 1.
     *
     * <h2>Почему именно поток, а не календарь</h2>
     *
     * Гейт по волатильности часа проверен обходом и не окупился (задача A32).
     * Разбор 154 объясняет, почему: волатильность — СЛАБЫЙ посредник между
     * причиной и следствием. Сильные предикторы у нас уже измерены разрезами
     * той же кривой — пауза с прошлого принта (разброс {@code c} в 6.6 раза) и
     * перекос книги.
     *
     * <h2>⚠️ Свип гейтом быть НЕ МОЖЕТ</h2>
     *
     * Разрез «свип против одиночного принта» показывает большую разницу в
     * {@code c}, но пользоваться ею нельзя: свип — это и есть событие, которое
     * нас исполняет. Узнать, что принт окажется частью пачки, можно только
     * когда пачка уже пришла, то есть ПОСЛЕ сделки. Гейтом может быть лишь то,
     * что известно ДО неё: пауза с прошлого принта и перекос книги.
     *
     * <h2>⚠️ Порог выбирается на ПЕРВОЙ половине окна, оценка — на второй</h2>
     *
     * Иначе гейт выбирается и проверяется на одних данных, и любой шум
     * превращается в выигрыш. Разделение грубое (пополам), но оно отделяет
     * «правило работает» от «правило подогнано».
     */
    private static String gates(List<Print> prints, TreeMap<Long, Double> fair,
                                TreeMap<Long, Double> byMinute, double dist, double days) {
        if (prints.size() < 200) {
            return "";
        }
        long mid = prints.get(prints.size() / 2).tsMs();
        List<Print> first = prints.stream().filter(p -> p.tsMs() < mid).toList();
        List<Print> second = prints.stream().filter(p -> p.tsMs() >= mid).toList();
        double halfDays = days / 2;
        List<Print> ev = events(first, dist);
        List<Print> buys = ev.stream().filter(p -> p.aggressor() > 0).toList();
        List<Print> sells = ev.stream().filter(p -> p.aggressor() < 0).toList();
        if (sells.size() < 30 || buys.size() < 30) {
            return "";
        }
        // Пороги: медиана величины на первой половине и та её сторона, где
        // отбор МЕНЬШЕ. Сторона выбирается данными, а не нашим ожиданием.
        double gapCut = median(sells.stream().filter(p -> p.gapMs() >= 0)
                .map(p -> (double) p.gapMs()).toList());
        boolean gapLowBetter = better(sells, fair, p -> p.gapMs() >= 0 && p.gapMs() <= gapCut,
                p -> p.gapMs() > gapCut);
        double imbCutBuy = median(buys.stream().filter(p -> !Double.isNaN(p.imbalance()))
                .map(Print::imbalance).toList());
        double imbCutSell = median(sells.stream().filter(p -> !Double.isNaN(p.imbalance()))
                .map(Print::imbalance).toList());
        boolean imbLowBetterSell = better(sells, fair, p -> p.imbalance() <= imbCutSell,
                p -> p.imbalance() > imbCutSell);
        boolean imbLowBetterBuy = better(buys, fair, p -> p.imbalance() <= imbCutBuy,
                p -> p.imbalance() > imbCutBuy);

        java.util.function.Predicate<Print> all = p -> true;
        java.util.function.Predicate<Print> byGap = p -> p.gapMs() < 0
                || (gapLowBetter ? p.gapMs() <= gapCut : p.gapMs() > gapCut);
        java.util.function.Predicate<Print> imbSell = p -> Double.isNaN(p.imbalance())
                || (imbLowBetterSell ? p.imbalance() <= imbCutSell : p.imbalance() > imbCutSell);
        java.util.function.Predicate<Print> imbBuy = p -> Double.isNaN(p.imbalance())
                || (imbLowBetterBuy ? p.imbalance() <= imbCutBuy : p.imbalance() > imbCutBuy);

        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%nГЕЙТЫ ПО СОСТОЯНИЮ ПОТОКА при δ = %.0f б.п. (док. 154 §VI)%n", dist));
        sb.append(String.format(Locale.ROOT,
                "порог паузы %.0f мс (лучше %s), перекоса: покупки %.2f (%s), продажи %.2f (%s)"
                        + " — выбраны на ПЕРВОЙ половине окна%n",
                gapCut, gapLowBetter ? "короткая" : "длинная",
                imbCutBuy, imbLowBetterBuy ? "ниже" : "выше",
                imbCutSell, imbLowBetterSell ? "ниже" : "выше"));
        sb.append("  вариант | кругов/сут | круг за вычетом рынка | СКО | ОТНОШЕНИЕ/сут\n");
        sb.append(gateRow(second, fair, byMinute, dist, halfDays, "база (вторая половина)",
                all, all));
        sb.append(gateRow(second, fair, byMinute, dist, halfDays, "гейт по паузе (вход)",
                byGap, all));
        sb.append(gateRow(second, fair, byMinute, dist, halfDays, "гейт по перекосу (вход)",
                imbSell, all));
        sb.append(gateRow(second, fair, byMinute, dist, halfDays,
                "односторонняя котировка (вход и выход по перекосу)", imbSell, imbBuy));
        sb.append(gateRow(second, fair, byMinute, dist, halfDays, "пауза + перекос на входе",
                p -> byGap.test(p) && imbSell.test(p), all));
        sb.append("⚠️ Гейт меняет и λ, и c сразу, поэтому сравнивать варианты можно только\n");
        sb.append("суточным отношением: по доходу они неразделимы.\n");
        sb.append("⚠️ «Не котировать» — это не бесплатно: круг, который не открылся, не\n");
        sb.append("открылся и в хорошем состоянии тоже. Цена видна в графе «кругов/сут».\n");
        return sb.toString();
    }

    /** Строка таблицы гейтов: круги с фильтрами на вход и выход. */
    private static String gateRow(List<Print> prints, TreeMap<Long, Double> fair,
                                  TreeMap<Long, Double> byMinute, double dist, double days,
                                  String name, java.util.function.Predicate<Print> open,
                                  java.util.function.Predicate<Print> close) {
        List<Round> rounds = rounds(prints, dist, open, close);
        if (rounds.size() < 5) {
            return String.format(Locale.ROOT, "  %-50s | кругов мало (%d)%n", name, rounds.size());
        }
        List<Double> net = new ArrayList<>();
        for (Round r : rounds) {
            double mins = (r.closeMs() - r.openMs()) / 60_000.0;
            net.add(r.valueBp() - marketDrift(byMinute, (int) Math.max(1, Math.round(mins))));
        }
        double lam = rounds.size() / days;
        double sd = sd(net);
        return String.format(Locale.ROOT, "  %-50s | %10.1f | %21.2f | %4.1f | %13.2f%n",
                name, lam, mean(net), sd, sd > 0 ? mean(net) / sd * Math.sqrt(lam) : Double.NaN);
    }

    /** Какая половина разреза лучше: где отбор {@code c} меньше. */
    private static boolean better(List<Print> side, TreeMap<Long, Double> fair,
                                  java.util.function.Predicate<Print> low,
                                  java.util.function.Predicate<Print> high) {
        Double cl = cost(side.stream().filter(low::test).toList(), fair, 900_000);
        Double ch = cost(side.stream().filter(high::test).toList(), fair, 900_000);
        return cl == null || ch == null || cl <= ch;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    /**
     * Круги из ленты: покупка по первому событию, дотянувшемуся до δ со стороны
     * продавца, продажа — по первому встречному со стороны покупателя.
     *
     * ⚠️ Одновременно держится РОВНО ОДИН лот. Это сделано намеренно: величина
     * должна мерить площадку при данном отступе, а не нашу раскладку капитала.
     * Сетка, потолок и скос меняют и λ, и распределение T, и сравнивать их надо
     * обходом, где всё это есть.
     */
    static List<Round> rounds(List<Print> prints, double dist,
                                      TreeMap<Long, Double> fair) {
        return rounds(prints, dist, p -> true, p -> true);
    }

    /**
     * То же, но с фильтрами: {@code open} решает, котируем ли мы бид в этом
     * состоянии, {@code close} — аск. Это и есть гейт по состоянию потока
     * (блок 3 разбора 154): отказ от котировки не создаёт круга вовсе.
     */
    static List<Round> rounds(List<Print> prints, double dist,
                                      java.util.function.Predicate<Print> open,
                                      java.util.function.Predicate<Print> close) {
        List<Round> out = new ArrayList<>();
        long openMs = 0;
        double openFair = 0;
        for (Print p : events(prints, dist)) {
            if (openFair == 0) {
                if (p.aggressor() < 0 && p.fair() > 0 && open.test(p)) {   // продавец бьёт наш бид
                    openMs = p.tsMs();
                    openFair = p.fair();
                }
            } else if (p.aggressor() > 0 && p.fair() > 0 && close.test(p)) { // покупатель бьёт аск
                out.add(new Round(openMs, p.tsMs(),
                        2 * dist + 1e4 * (p.fair() - openFair) / openFair));
                openFair = 0;
            }
        }
        return out;
    }

    /**
     * БЕЗУСЛОВНЫЙ КОНТРОЛЬ: средний ход опоры за {@code h} минут, посчитанный от
     * КАЖДОЙ минуты окна.
     *
     * Без него отрицательный снос ничего не значит: спот-бот всегда начинает с
     * покупки, и падающий рынок даёт минус любому кругу независимо от настройки.
     */
    static double marketDrift(TreeMap<Long, Double> byMinute, int h) {
        double sum = 0;
        int n = 0;
        for (Map.Entry<Long, Double> e : byMinute.entrySet()) {
            Double later = byMinute.get(e.getKey() + h);
            if (later == null || e.getValue() <= 0) {
                continue;
            }
            sum += 1e4 * (later - e.getValue()) / e.getValue();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * ОПТИМАЛЬНЫЙ ОТСТУП по формулам 154 §I.1.
     *
     * При {@code λ = A·e^{−κδ}} максимум дохода {@code λ·(2δ − c)} достигается
     * при {@code 2δ − c = 2/κ}, максимум отношения {@code √λ·(2δ − c)} — при
     * {@code 2δ − c = 4/κ}. Отсюда {@code δ* = c/2 + 1/κ} и {@code c/2 + 2/κ}:
     * риск-взвешенный оптимум ровно на {@code 1/κ} ШИРЕ доходного, и это та
     * самая поправка за разброс, которую мы искали руками.
     *
     * ⚠️ {@code c} сама зависит от δ, поэтому решается неподвижной точкой: берём
     * {@code c} на текущем δ, получаем новое δ, повторяем. Расходится — значит
     * формула на этих данных неприменима, и это честнее, чем печатать первое
     * приближение.
     */
    private static String optimum(Map<Double, double[]> byDelta, Map<Double, Double> lambda,
                                  double days) {
        TreeMap<Double, Double> c = new TreeMap<>();
        for (var e : byDelta.entrySet()) {
            c.put(e.getKey(), e.getValue()[4]);
        }
        // κ по тем же кругам: наклон ln(кругов в сутки) по δ.
        List<double[]> pts = new ArrayList<>();
        for (var e : byDelta.entrySet()) {
            if (e.getValue()[0] > 0) {
                pts.add(new double[]{e.getKey(), Math.log(e.getValue()[0])});
            }
        }
        if (pts.size() < 3) {
            return "  δ*: ступеней мало\n";
        }
        double mx = pts.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double my = pts.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double num = 0;
        double den = 0;
        for (double[] p : pts) {
            num += (p[0] - mx) * (p[1] - my);
            den += (p[0] - mx) * (p[0] - mx);
        }
        double kappa = den == 0 ? 0 : -num / den;
        if (!(kappa > 0)) {
            return "  δ*: κ по кругам неположительна — формула неприменима\n";
        }
        // 🔑 ПРЯМОЙ ОТВЕТ — по таблице, а не по формуле. Формула нужна там, где
        // между ступенями надо интерполировать; сама таблица уже содержит и
        // доход, и отношение на каждой измеренной ступени.
        double bestIncome = 0;
        double bestIncomeVal = Double.NEGATIVE_INFINITY;
        double bestRatio = 0;
        double bestRatioVal = Double.NEGATIVE_INFINITY;
        for (var e : byDelta.entrySet()) {
            if (e.getValue()[5] < MIN_ROUNDS) {
                continue;                  // ступень с горсткой кругов в выбор не идёт
            }
            double income = e.getValue()[0] * e.getValue()[1];
            if (income > bestIncomeVal) {
                bestIncomeVal = income;
                bestIncome = e.getKey();
            }
            if (e.getValue()[3] > bestRatioVal) {
                bestRatioVal = e.getValue()[3];
                bestRatio = e.getKey();
            }
        }
        if (bestIncomeVal == Double.NEGATIVE_INFINITY) {
            return "  ПО ТАБЛИЦЕ: ни на одной ступени не набралось " + MIN_ROUNDS + " кругов\n";
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "  ПО ТАБЛИЦЕ (только ступени от %d кругов): лучший доход при δ = %.0f"
                        + " (%.1f б.п./сут), лучшее отношение при δ = %.0f (%.2f)%n",
                MIN_ROUNDS, bestIncome, bestIncomeVal, bestRatio, bestRatioVal));
        sb.append(String.format(Locale.ROOT,
                "  κ по КРУГАМ: %.3f на б.п. (1/κ = %.2f б.п.)%n", kappa, 1 / kappa));
        for (int k = 0; k < 2; k++) {
            double d = 10;
            boolean ok = true;
            for (int i = 0; i < 20; i++) {
                double cd = interp(c, d);
                double next = cd / 2 + (k + 1) / kappa;
                if (!(next > 0) || next > 100) {
                    ok = false;
                    break;
                }
                if (Math.abs(next - d) < 0.05) {
                    d = next;
                    break;
                }
                d = next;
            }
            sb.append(String.format(Locale.ROOT, "  δ* по %s: %s%n",
                    k == 0 ? "ДОХОДУ (c/2 + 1/κ)" : "ОТНОШЕНИЮ (c/2 + 2/κ)",
                    ok ? String.format(Locale.ROOT, "%.1f б.п. (c там %.1f)", d, interp(c, d))
                            : "не сошлось"));
        }
        sb.append("  ⚠️ Формула 154 §I.1 выведена при c, НЕ ЗАВИСЯЩЕЙ от δ. На наших данных\n");
        sb.append("  c растёт с δ (BTC 7.6 при δ=2 против 15.6 при δ=12), и тогда у\n");
        sb.append("  неподвижной точки δ = c(δ)/2 + m/κ решений бывает несколько, а\n");
        sb.append("  «риск-взвешенный шире доходного на 1/κ» перестаёт выполняться.\n");
        sb.append("  Читать надо строку ПО ТАБЛИЦЕ: она без допущений.\n");
        return sb.toString();
    }

    /** Линейная интерполяция {@code c(δ)} по посчитанным ступеням. */
    private static double interp(TreeMap<Double, Double> c, double d) {
        var lo = c.floorEntry(d);
        var hi = c.ceilingEntry(d);
        if (lo == null) {
            return hi == null ? 0 : hi.getValue();
        }
        if (hi == null || hi.getKey().equals(lo.getKey())) {
            return lo.getValue();
        }
        double w = (d - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() * (1 - w) + hi.getValue() * w;
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.isEmpty() ? 0 : s / v.size();
    }

    private static double sd(List<Double> v) {
        if (v.size() < 2) {
            return 0;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.size() - 1));
    }

    /** Горизонты для {@code c(δ, H)}: от боевой минуты до двух часов держания. */
    private static final long[] HOLD_HORIZONS = {
            60_000, 300_000, 900_000, 1_800_000, 3_600_000, 7_200_000};

    private static final String[] HOLD_LABELS = {"1м", "5м", "15м", "30м", "1ч", "2ч"};

    /**
     * ОТБОР НА ГОРИЗОНТЕ РЕАЛЬНОГО ДЕРЖАНИЯ — {@code c(δ, H)}.
     *
     * <h2>Зачем</h2>
     *
     * Кривая {@code c(δ)} мерилась на 60 секундах, а позиция живёт десятки минут
     * (замер A27: среднее держание живых ботов 19–187 минут). На минутном
     * горизонте край положителен; на горизонте, где круг реально закрывается,
     * его никто не мерил. Разрыв между «ожидаемым по кривой» и фактическим
     * результатом лежит ровно там, где кончалось измерение.
     *
     * <h2>⚠️ Почему нельзя просто увеличить горизонт</h2>
     *
     * {@code c} считается как {@code сторона_агрессора · Δfair}. На минуте это
     * почти чистый отбор: цена за минуту далеко не уходит. На двух часах в ту же
     * величину входит СНОС РЫНКА, и если поток перекошен (покупок больше, чем
     * продаж), {@code c} померяет направление рынка, а не то, что у нас забирают.
     * Прямое увеличение горизонта дало бы красивую растущую кривую, которая
     * означала бы «биткойн рос», а не «нас отбирают».
     *
     * <h2>Контроль</h2>
     *
     * Поэтому {@code c} считается ОТДЕЛЬНО по сторонам агрессора и раскладывается:
     * <pre>
     *   отбор = (c_покупки + c_продажи) / 2     — одинаково против нас с обеих сторон
     *   снос  = (c_покупки − c_продажи) / 2     — просто движение рынка за H
     * </pre>
     * Если отбор настоящий, обе половины положительны. Если это снос, они
     * противоположны по знаку и гасятся. Величина, которую можно класть в
     * арифметику круга, — только «отбор».
     *
     * ⚠️ События, у которых горизонт выходит за конец данных, отбрасываются
     * целиком, поэтому на двух часах выборка заметно меньше — колонка «событий»
     * печатается, чтобы это было видно, а не угадывалось.
     */
    private static String costByHorizon(List<Print> prints, TreeMap<Long, Double> fair) {
        StringBuilder sb = new StringBuilder(
                "\nОТБОР НА ГОРИЗОНТЕ ДЕРЖАНИЯ: c(δ, H), б.п.\n"
                        + "(взвешено по потоку, как считалось всегда; очищенная от сноса\n"
                        + "величина — в таблице КОНТРОЛЬ СНОСА ниже, графа ОТБОР)\n");
        sb.append("  δ,б.п.");
        for (String label : HOLD_LABELS) {
            sb.append(String.format(Locale.ROOT, " | %6s", label));
        }
        sb.append(" | событий на 2ч\n");
        for (double dist : GRID) {
            List<Print> reach = events(prints, dist);
            if (reach.size() < 30) {
                continue;
            }
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %6.0f", dist));
            for (long h : HOLD_HORIZONS) {
                Double c = cost(reach, fair, h);
                row.append(c == null ? String.format(Locale.ROOT, " | %6s", "—")
                        : String.format(Locale.ROOT, " |%+6.2f%s", c, mark(reach, fair, h)));
            }
            row.append(String.format(Locale.ROOT, " | %13d", usable(reach, fair, 7_200_000)));
            sb.append(row).append('\n');
        }
        sb.append("«?» — клетка НЕГОДНА: снос рынка за H больше измеряемой величины.\n");

        sb.append("\nКОНТРОЛЬ СНОСА: та же величина, разложенная по сторонам агрессора\n");
        sb.append("  δ,б.п. |     H | c(покупки) | c(продажи) |  ОТБОР |   снос | покуп/прод\n");
        for (double dist : new double[]{6, 8, 10, 12, 14}) {
            List<Print> reach = events(prints, dist);
            if (reach.size() < 30) {
                continue;
            }
            List<Print> buys = reach.stream().filter(p -> p.aggressor() > 0).toList();
            List<Print> sells = reach.stream().filter(p -> p.aggressor() < 0).toList();
            for (int i = 0; i < HOLD_HORIZONS.length; i++) {
                Double cb = cost(buys, fair, HOLD_HORIZONS[i]);
                Double cs = cost(sells, fair, HOLD_HORIZONS[i]);
                if (cb == null || cs == null) {
                    continue;
                }
                double pick = (cb + cs) / 2;
                double drift = (cb - cs) / 2;
                sb.append(String.format(Locale.ROOT,
                        "  %6.0f | %5s | %+10.2f | %+10.2f | %+6.2f | %+6.2f%s | %5d/%d%n",
                        dist, HOLD_LABELS[i], cb, cs, pick, drift,
                        Math.abs(drift) > Math.abs(pick) ? " ⚠️" : "  ",
                        buys.size(), sells.size()));
            }
        }
        sb.append("⚠️ ОТБОР — то, что забирают с обеих сторон одинаково; снос — движение\n");
        sb.append("рынка за H, которое к нам отношения не имеет и на длинном окне гасится.\n");
        sb.append("В арифметику круга кладётся ОТБОР, а не c.\n");

        sb.append("\nКРАЙ НА ГОРИЗОНТЕ: δ − отбор(δ, H), б.п. (положительно = круг в плюсе)\n");
        sb.append("  δ,б.п.");
        for (String label : HOLD_LABELS) {
            sb.append(String.format(Locale.ROOT, " | %6s", label));
        }
        sb.append('\n');
        for (double dist : GRID) {
            List<Print> reach = events(prints, dist);
            if (reach.size() < 30) {
                continue;
            }
            List<Print> buys = reach.stream().filter(p -> p.aggressor() > 0).toList();
            List<Print> sells = reach.stream().filter(p -> p.aggressor() < 0).toList();
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %6.0f", dist));
            for (long h : HOLD_HORIZONS) {
                Double cb = cost(buys, fair, h);
                Double cs = cost(sells, fair, h);
                row.append(cb == null || cs == null ? String.format(Locale.ROOT, " | %6s", "—")
                        : String.format(Locale.ROOT, " |%+6.2f%s", dist - (cb + cs) / 2,
                                Math.abs(cb - cs) > Math.abs(cb + cs) ? "?" : " "));
            }
            sb.append(row).append('\n');
        }
        sb.append("⚠️ Это край ОДНОЙ ноги. Круг берёт обе, но и отбор платится дважды,\n");
        sb.append("поэтому знак у круга тот же, что здесь.\n");
        sb.append("\n🔑 ЧИТАТЬ ТОЛЬКО КЛЕТКИ БЕЗ «?». Замер 12.09.2026 на трёх парах и двух\n");
        sb.append("окнах: до 15 минут годны почти все клетки, после 30 минут — почти ни\n");
        sb.append("одной. Снос за два часа доходил до −27 б.п. при измеряемой величине в 8,\n");
        sb.append("то есть дальше получаса величина не «большая», а несуществующая.\n");
        return sb.toString();
    }


    /**
     * ПОДСКАЗЫВАЕТ ЛИ ПРОСАДКА: если цена ушла на X б.п. против позиции, ждать
     * или выходить?
     *
     * <h2>Вопрос</h2>
     *
     * Выход тейкером и придвижение аска — оба стоят денег, и оба применялись бы
     * «по возрасту». Но у рынка может быть подсказка получше возраста: насколько
     * цена уже ушла. Если после просадки в 100 б.п. она в среднем ВОЗВРАЩАЕТСЯ,
     * ждать правильно и выходить нельзя; если продолжает уходить — наоборот.
     *
     * <h2>Как считается</h2>
     *
     * Каждое событие, которое дотянулось бы до нашего бида, считается входом
     * (цена входа — справедливая в этот момент). Дальше ряд справедливой цены
     * идёт вперёд, и ищется ПЕРВЫЙ момент, когда позиция оказалась под водой на
     * {@code X} б.п. От этого момента меряется дальнейшее движение через
     * 5/15/30/60 минут и доля случаев, когда цена вернулась к цене входа.
     *
     * Знак: ПЛЮС — цена пошла обратно, просадка отыгрывается, ждать выгодно;
     * МИНУС — уходит дальше, и любая задержка с выходом дорожает.
     *
     * ⚠️ Это условная величина, и условие ОТБИРАЕТ наблюдения: до просадки в 100
     * б.п. доживают только те эпизоды, где цена уже ушла далеко. Поэтому доля
     * «дожили» печатается рядом — на глубоких порогах она мала, и числа там
     * держатся на десятках случаев.
     *
     * ⚠️ Вход берётся по справедливой цене, а не по цене нашей заявки: отступ
     * сдвинул бы все пороги на константу и ничего не изменил бы в форме.
     */
    private static String drawdownHint(List<Print> prints, TreeMap<Long, Double> fair) {
        List<Print> entries = prints.stream().filter(p -> p.aggressor() < 0).toList();
        if (entries.size() < 100) {
            return "";
        }
        long last = fair.lastKey();
        double[] levels = {5, 10, 20, 40, 80, 160};
        long[] horizons = {300_000, 900_000, 1_800_000, 3_600_000};
        String[] labels = {"5м", "15м", "30м", "1ч"};
        StringBuilder sb = new StringBuilder(
                "\nПОДСКАЗЫВАЕТ ЛИ ПРОСАДКА: что делает цена ПОСЛЕ того, как ушла на X\n");
        sb.append("(вход = покупка по справедливой цене; плюс = возврат, минус = уходит дальше)\n");
        sb.append("  просадка | дожили |");
        for (String l : labels) {
            sb.append(String.format(Locale.ROOT, " %7s |", l));
        }
        sb.append(" вернулась к входу за 1ч\n");
        for (double level : levels) {
            int reached = 0;
            double[] sum = new double[horizons.length];
            int[] n = new int[horizons.length];
            int back = 0;
            int backDenom = 0;
            for (Print e : entries) {
                if (!(e.fair() > 0)) {
                    continue;
                }
                // первый момент, когда цена ниже входа на level б.п.
                Long hit = null;
                double hitPrice = 0;
                for (Map.Entry<Long, Double> f : fair.tailMap(e.tsMs()).entrySet()) {
                    if (f.getKey() > e.tsMs() + 3_600_000) {
                        break;                       // час на то, чтобы просесть
                    }
                    if (f.getValue() > 0
                            && (e.fair() - f.getValue()) / e.fair() * 10_000 >= level) {
                        hit = f.getKey();
                        hitPrice = f.getValue();
                        break;
                    }
                }
                if (hit == null) {
                    continue;
                }
                reached++;
                for (int h = 0; h < horizons.length; h++) {
                    if (hit + horizons[h] > last) {
                        continue;
                    }
                    Map.Entry<Long, Double> f = fair.floorEntry(hit + horizons[h]);
                    if (f == null || f.getValue() <= 0) {
                        continue;
                    }
                    sum[h] += (f.getValue() - hitPrice) / hitPrice * 10_000;
                    n[h]++;
                }
                if (hit + 3_600_000 <= last) {
                    backDenom++;
                    for (Map.Entry<Long, Double> f : fair.tailMap(hit).entrySet()) {
                        if (f.getKey() > hit + 3_600_000) {
                            break;
                        }
                        if (f.getValue() >= e.fair()) {
                            back++;
                            break;
                        }
                    }
                }
            }
            if (reached < 20) {
                continue;
            }
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT,
                    "  %8.0f | %5.0f%% |", level, 100.0 * reached / entries.size()));
            for (int h = 0; h < horizons.length; h++) {
                row.append(n[h] == 0 ? String.format(Locale.ROOT, " %7s |", "—")
                        : String.format(Locale.ROOT, " %+7.2f |", sum[h] / n[h]));
            }
            row.append(String.format(Locale.ROOT, " %17s",
                    backDenom == 0 ? "—" : String.format(Locale.ROOT, "%.0f%% (%d)",
                            100.0 * back / backDenom, backDenom)));
            sb.append(row).append('\n');
        }
        sb.append("⚠️ Условие ОТБИРАЕТ наблюдения: до глубокой просадки доживают только\n");
        sb.append("эпизоды, где цена уже ушла. Графа «дожили» показывает, насколько\n");
        sb.append("редок каждый порог.\n");
        return sb.toString();
    }

    /**
     * ТА ЖЕ ПРОСАДКА, НО РАЗЛОЖЕННАЯ ПО СКОРОСТИ.
     *
     * ⚠️ Вопрос владельца, снимающий двусмысленность предыдущей таблицы: «ушла на
     * 80 б.п.» — это ОДНИМ свипом или сползанием за час? Пути разные, и если
     * отскок живёт только в быстром, то это переоценка свипа, то есть
     * измеримая точка разворота. Если в медленном — это возврат тренда, и
     * торговать его нечем.
     *
     * Скорость = сколько минут прошло от входа до момента, когда просадка
     * впервые достигла порога. Полосы: до минуты (по сути один свип или пачка),
     * 1–15 минут, дольше 15.
     */

    /**
     * 🔑 РАЗВОРОТ, ПОДТВЕРЖДЁННЫЙ ОБЕИМИ СТОРОНАМИ.
     *
     * ⚠️ Односторонняя таблица врёт, и это проверено на живом примере. Быстрое
     * падение BTC на 20 б.п. в августе давало «разворот +60.47 за час» — цифра,
     * которая больше самого падения. Контроль по РОСТУ показал −11.23: после
     * быстрого роста цена тоже шла вверх. Значит обе стороны двигал общий СНОС
     * рынка, а разворота на этом ходе нет вовсе.
     *
     * Раскладка та же, что у {@code c(δ, H)}:
     * <pre>
     *   разворот = (R_падение + R_рост) / 2     против нас с обеих сторон
     *   снос     = (R_падение − R_рост) / 2     общее движение рынка
     * </pre>
     * где {@code R} — ход ПРОТИВ первоначального направления. Разворот настоящий
     * только когда обе половины положительны; знак у одной и ноль у другой
     * означает, что мы померили направление рынка.
     */
    private static String reversalBothSides(List<Print> prints, TreeMap<Long, Double> fair) {
        double[] levels = {20, 40, 80};
        double[] speedEdges = {1, 15, 1e9};
        String[] speedNames = {"до 1 мин", "1–15 мин", "больше 15"};
        StringBuilder sb = new StringBuilder(
                "\nРАЗВОРОТ, ПОДТВЕРЖДЁННЫЙ ОБЕИМИ СТОРОНАМИ (за час)\n");
        sb.append("  ход,б.п. |   скорость | падение | рост | РАЗВОРОТ | снос\n");
        for (double level : levels) {
            for (int b = 0; b < speedNames.length; b++) {
                double lo = b == 0 ? 0 : speedEdges[b - 1];
                double hi = speedEdges[b];
                double[] down = reversalAt(prints, fair, -1, level, lo, hi);
                double[] up = reversalAt(prints, fair, 1, level, lo, hi);
                if (down[1] < 20 || up[1] < 20) {
                    continue;
                }
                double rev = (down[0] + up[0]) / 2;
                double drift = (down[0] - up[0]) / 2;
                sb.append(String.format(Locale.ROOT,
                        "  %8.0f | %10s | %+7.2f | %+5.2f | %+8.2f | %+5.2f%s%n",
                        level, speedNames[b], down[0], up[0], rev, drift,
                        down[0] > 0 && up[0] > 0 ? "  ✓" : "  ⚠️ одной стороной"));
            }
        }
        sb.append("⚠️ Читать только строки с ✓: там разворот подтверждён обеими сторонами.\n");
        sb.append("Остальные означают снос рынка, а не свойство хода.\n");
        return sb.toString();
    }

    /** {@code {средний ход против направления за час, число случаев}}. */
    private static double[] reversalAt(List<Print> prints, TreeMap<Long, Double> fair,
                                       int side, double level, double loMin, double hiMin) {
        long last = fair.lastKey();
        double sum = 0;
        int n = 0;
        int cnt = 0;
        for (Print e : prints) {
            if (e.aggressor() != side || !(e.fair() > 0)) {
                continue;
            }
            Long hit = null;
            double hitPrice = 0;
            for (Map.Entry<Long, Double> f : fair.tailMap(e.tsMs()).entrySet()) {
                if (f.getKey() > e.tsMs() + 3_600_000) {
                    break;
                }
                if (f.getValue() > 0
                        && side * (f.getValue() - e.fair()) / e.fair() * 10_000 >= level) {
                    hit = f.getKey();
                    hitPrice = f.getValue();
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            double minutes = (hit - e.tsMs()) / 60_000.0;
            if (minutes < loMin || minutes >= hiMin) {
                continue;
            }
            cnt++;
            if (hit + 3_600_000 > last) {
                continue;
            }
            Map.Entry<Long, Double> f = fair.floorEntry(hit + 3_600_000);
            if (f == null || f.getValue() <= 0) {
                continue;
            }
            sum += -side * (f.getValue() - hitPrice) / hitPrice * 10_000;
            n++;
        }
        return new double[]{n == 0 ? 0 : sum / n, cnt};
    }
    private static String drawdownBySpeed(List<Print> prints, TreeMap<Long, Double> fair) {
        return speedTable(prints, fair, -1) + speedTable(prints, fair, 1);
    }

    private static String speedTable(List<Print> prints, TreeMap<Long, Double> fair, int side) {
        List<Print> entries = prints.stream().filter(p -> p.aggressor() == side).toList();
        if (entries.size() < 100) {
            return "";
        }
        long last = fair.lastKey();
        double[] levels = {20, 40, 80};
        double[] speedEdges = {0, 1, 15, 1e9};
        String[] speedNames = {"до 1 мин", "1–15 мин", "больше 15"};
        StringBuilder sb = new StringBuilder("\nХОД ПО СКОРОСТИ — "
                + (side < 0 ? "ПАДЕНИЕ (бьют наш бид)" : "РОСТ (КОНТРОЛЬ)")
                + ": одним свипом или сползанием\n");
        sb.append("  ход,б.п. |   скорость | случаев |    +15м |     +1ч   (плюс = разворот)\n");
        for (double level : levels) {
            double[] sum15 = new double[3];
            double[] sum60 = new double[3];
            int[] n15 = new int[3];
            int[] n60 = new int[3];
            int[] cnt = new int[3];
            for (Print e : entries) {
                if (!(e.fair() > 0)) {
                    continue;
                }
                Long hit = null;
                double hitPrice = 0;
                for (Map.Entry<Long, Double> f : fair.tailMap(e.tsMs()).entrySet()) {
                    if (f.getKey() > e.tsMs() + 3_600_000) {
                        break;
                    }
                    if (f.getValue() > 0
                            && side * (f.getValue() - e.fair()) / e.fair() * 10_000 >= level) {
                        hit = f.getKey();
                        hitPrice = f.getValue();
                        break;
                    }
                }
                if (hit == null) {
                    continue;
                }
                double minutes = (hit - e.tsMs()) / 60_000.0;
                int band = minutes < speedEdges[1] ? 0 : minutes < speedEdges[2] ? 1 : 2;
                cnt[band]++;
                if (hit + 900_000 <= last) {
                    Map.Entry<Long, Double> f = fair.floorEntry(hit + 900_000);
                    if (f != null && f.getValue() > 0) {
                        sum15[band] += -side * (f.getValue() - hitPrice) / hitPrice * 10_000;
                        n15[band]++;
                    }
                }
                if (hit + 3_600_000 <= last) {
                    Map.Entry<Long, Double> f = fair.floorEntry(hit + 3_600_000);
                    if (f != null && f.getValue() > 0) {
                        sum60[band] += -side * (f.getValue() - hitPrice) / hitPrice * 10_000;
                        n60[band]++;
                    }
                }
            }
            for (int b = 0; b < 3; b++) {
                if (cnt[b] < 20) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT,
                        "  %8.0f | %10s | %7d | %+7.2f | %+7.2f%n",
                        level, speedNames[b], cnt[b],
                        n15[b] == 0 ? 0 : sum15[b] / n15[b],
                        n60[b] == 0 ? 0 : sum60[b] / n60[b]));
            }
        }
        sb.append("⚠️ «до 1 мин» — просадка набрана свипом или пачкой, то есть ОДНИМ\n");
        sb.append("движением; «больше 15» — сползание. Если отскок есть только в первой\n");
        sb.append("полосе, это переоценка свипа и точка разворота; если во второй —\n");
        sb.append("просто возврат тренда, и торговать его нечем.\n");
        return sb.toString();
    }
    /** Свип как целое: одна рыночная заявка, разложенная на несколько принтов. */
    private record Sweep(long tsMs, int side, double depthBp, double qty, int prints,
                         double fair) {
    }

    /**
     * ПОСЛЕ СВИПА: продолжение или возврат — и есть ли порог, где меняется знак.
     *
     * <h2>Вопрос</h2>
     *
     * «Кто-то зашёл и купил немного» и «пошёл информированный поток вниз» — для
     * котировщика это два разных события, а он реагирует на них одинаково: бид
     * стоит где стоял и ловит обе. Если мелкие свипы возвращаются, а крупные
     * продолжаются, то существует ПОРОГ, выше которого нашу логику надо
     * переворачивать: не подбирать, а сдавать инвентарь и отходить.
     *
     * <h2>Как считается</h2>
     *
     * Принты собираются обратно в свипы (одна отметка времени плюс окно
     * {@link #BURST_MS}, одна сторона агрессора), у свипа берётся ГЛУБИНА —
     * максимальное расстояние, на которое он утащил цену от справедливой. Дальше
     * для каждой полосы глубины считается, куда ушла справедливая цена через
     * 1/5/15/30 минут — <b>в пользу агрессора</b>.
     *
     * Знак читается так:
     * <ul>
     *   <li>плюс — цена ПРОДОЛЖИЛА движение свипа, то есть он нёс информацию, и
     *       наша заявка на его пути была подобрана правильно им, а не нами;</li>
     *   <li>минус — цена ВЕРНУЛАСЬ, свип был шумом, и подбирать его выгодно.</li>
     * </ul>
     *
     * ⚠️ Стороны считаются ОТДЕЛЬНО, потому что общая величина на перекошенном
     * потоке мерит снос рынка, а не свип (та же ловушка, что в {@code c(δ, H)}).
     * Совпадение знака у обеих сторон — признак настоящего продолжения;
     * противоположные знаки означают, что мы снова померяли направление рынка.
     */
    private static String afterSweep(List<Print> prints, double days,
                                     TreeMap<Long, Double> fair) {
        List<Sweep> sweeps = new ArrayList<>();
        int i = 0;
        while (i < prints.size()) {
            Print p = prints.get(i);
            double depth = p.distBp();
            double qty = p.qty();
            int n = 1;
            int j = i + 1;
            while (j < prints.size() && prints.get(j).aggressor() == p.aggressor()
                    && prints.get(j).tsMs() - p.tsMs() <= BURST_MS) {
                depth = Math.max(depth, prints.get(j).distBp());
                qty += prints.get(j).qty();
                n++;
                j++;
            }
            sweeps.add(new Sweep(p.tsMs(), p.aggressor(), depth, qty, n, p.fair()));
            i = j;
        }
        long last = fair.lastKey();
        long[] horizons = {60_000, 300_000, 900_000, 1_800_000};
        String[] labels = {"1м", "5м", "15м", "30м"};
        double[] bands = {0, 4, 8, 12, 20, 1e9};
        String[] bandNames = {"0–4", "4–8", "8–12", "12–20", "20+"};

        StringBuilder sb = new StringBuilder(
                "\nПОСЛЕ СВИПА: продолжение или возврат (Δfair В ПОЛЬЗУ АГРЕССОРА, б.п.)\n");
        sb.append(String.format(Locale.ROOT, "свипов всего %d (%.0f в сутки)%n",
                sweeps.size(), sweeps.size() / days));
        for (int side : new int[]{-1, 1}) {
            sb.append(side < 0 ? "\n  СВИПЫ ВНИЗ (агрессор продаёт — бьёт по нашему биду)\n"
                    : "\n  СВИПЫ ВВЕРХ (агрессор покупает — бьёт по нашему аску)\n");
            sb.append("  глубина | свипов | принтов |     1м |     5м |    15м |    30м\n");
            for (int b = 0; b + 1 < bands.length; b++) {
                final double lo = bands[b];
                final double hi = bands[b + 1];
                List<Sweep> in = sweeps.stream()
                        .filter(s -> s.side() == side && s.depthBp() >= lo && s.depthBp() < hi)
                        .toList();
                if (in.size() < 20) {
                    continue;
                }
                double medPrints = in.stream().mapToDouble(Sweep::prints).sum() / in.size();
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT,
                        "  %7s | %6d | %7.2f", bandNames[b], in.size(), medPrints));
                for (long h : horizons) {
                    double sum = 0;
                    int n = 0;
                    for (Sweep s : in) {
                        if (s.tsMs() + h > last || !(s.fair() > 0)) {
                            continue;
                        }
                        Map.Entry<Long, Double> f = fair.floorEntry(s.tsMs() + h);
                        if (f == null || f.getValue() <= 0) {
                            continue;
                        }
                        sum += 1e4 * s.side() * (f.getValue() - s.fair()) / s.fair();
                        n++;
                    }
                    row.append(n == 0 ? String.format(Locale.ROOT, " | %6s", "—")
                            : String.format(Locale.ROOT, " | %+6.2f", sum / n));
                }
                sb.append(row).append('\n');
            }
        }
        sb.append("\n⚠️ ПЛЮС = цена продолжила движение свипа (он нёс информацию, наша\n");
        sb.append("заявка на его пути подобрана им, а не нами). МИНУС = вернулась,\n");
        sb.append("свип был шумом, и подбирать его выгодно.\n");
        sb.append("⚠️ Стороны считаются отдельно: на перекошенном потоке общая величина\n");
        sb.append("мерит снос рынка, а не свип. Доверять можно только тому, что\n");
        sb.append("подтверждается ОБЕИМИ сторонами.\n");
        return sb.toString();
    }

    /**
     * СКОЛЬКО ЖДАТЬ РАЗГРУЗКИ, ЕСЛИ ПРИДВИНУТЬ АСК К СПРАВЕДЛИВОЙ ЦЕНЕ.
     *
     * <h2>Вопрос</h2>
     *
     * Тейкерский выход стоит полуспред плюс комиссию — у нас 16.4 б.п. Но между
     * «ждать на своём отступе» и «бить по рынку» есть третье: ПРИДВИНУТЬ аск к
     * справедливой цене и остаться мейкером. Тогда комиссия нулевая, захват
     * падает до {@code δ}, а платой становится ожидание.
     *
     * <h2>Как считается</h2>
     *
     * По ленте: {@code λ(δ)} — сколько АГРЕССИВНЫХ ПОКУПОК в сутки дотягивается
     * до расстояния {@code δ} над справедливой ценой (это те, кто снял бы наш
     * аск), а {@code 1/λ} — среднее ожидание. Риск ожидания — {@code σ√T} по
     * той же волатильности, что и везде.
     *
     * Решение принимается сравнением:
     * <pre>
     *   придвинуть на δ:  +δ − σ√T(δ)      (мейкер, ждём)
     *   ударить тейкером: −полуспред − комиссия   (мгновенно)
     * </pre>
     *
     * ⚠️ <b>Верхняя оценка скорости.</b> {@code λ(δ)} считается по принтам,
     * которые СЛУЧИЛИСЬ. Встав со своим аском на {@code δ} от справедливой цены,
     * мы бы оказались лучшим аском, и часть этого потока действительно досталась
     * бы нам — но другие маркет-мейкеры переставились бы внутрь, а часть
     * агрессоров пришла бы по другой цене. Ждать придётся не меньше, чем здесь
     * написано, а сколько именно — вопрос обхода.
     *
     * ⚠️ И второе: {@code δ = 0} означает аск ВНУТРИ спреда, на справедливой
     * цене. Полуспред у нас 7.4 б.п., то есть такая заявка стоит на семь
     * базисных пунктов лучше рынка — это раздача, и {@code λ(0)} по ленте её
     * привлекательность недооценивает ровно настолько же, насколько
     * переоценивает по предыдущему замечанию.
     */
    private static String unloadWait(List<Print> prints, double days,
                                     TreeMap<Long, Double> fair) {
        double sigma = volBpPerMin(fair);
        if (!(sigma > 0)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "\nРАЗГРУЗКА МЕЙКЕРОМ: за сколько снимут аск на расстоянии δ\n");
        sb.append(String.format(Locale.ROOT, "(σ = %.2f б.п./мин)%n", sigma));
        sb.append("  δ,б.п. | съёмов/сут | ожидание | риск σ√T | захват | δ − риск\n");
        for (double dist : new double[]{0, 1, 2, 3, 4, 6, 8, 10, 12}) {
            List<Print> ev = events(prints, dist);
            long takers = ev.stream().filter(p -> p.aggressor() > 0).count();
            if (takers < 5) {
                continue;
            }
            double perDay = takers / days;
            double waitMin = 1440.0 / perDay;
            double risk = sigma * Math.sqrt(waitMin);
            sb.append(String.format(Locale.ROOT,
                    "  %6.0f | %10.1f | %5.0f мин | %8.1f | %6.0f | %+8.1f%n",
                    dist, perDay, waitMin, risk, dist, dist - risk));
        }
        sb.append("⚠️ «съёмов/сут» — агрессивные ПОКУПКИ, дотянувшиеся до δ над справедливой\n");
        sb.append("ценой: те, кто снял бы наш аск. Ожидание = 1/λ, среднее.\n");
        sb.append("⚠️ Это ВЕРХНЯЯ оценка скорости: поток считан по принтам, которые уже\n");
        sb.append("случились, а встань мы внутрь спреда — другие мейкеры переставились бы.\n");
        return sb.toString();
    }

    /** «?» у клетки, где снос рынка за H перевесил измеряемый отбор. */
    private static String mark(List<Print> reach, TreeMap<Long, Double> fair, long h) {
        Double cb = cost(reach.stream().filter(p -> p.aggressor() > 0).toList(), fair, h);
        Double cs = cost(reach.stream().filter(p -> p.aggressor() < 0).toList(), fair, h);
        if (cb == null || cs == null) {
            return " ";
        }
        return Math.abs(cb - cs) > Math.abs(cb + cs) ? "?" : " ";
    }

    /** Сколько событий доживает до горизонта внутри данных — знаменатель выборки. */
    private static int usable(List<Print> prints, TreeMap<Long, Double> fair, long horizonMs) {
        long last = fair.lastKey();
        int n = 0;
        for (Print p : prints) {
            if (p.tsMs() + horizonMs <= last) {
                n++;
            }
        }
        return n;
    }

    /**
     * ПЛАТЯТ ЛИ ЗА РИСК — ПО ЛЕНТЕ, без единой сделки.
     *
     * <h2>Чем это лучше замера по нашим кругам</h2>
     *
     * Отношение {@code 2δ / σ√T} мы до сих пор считали по ЗАКРЫТЫМ парам живого
     * бота. У такого замера три беды, и все уходят здесь:
     * <ul>
     *   <li>он зависит от нашего инвентаря, скоса и потолка — то есть меряет не
     *       площадку, а конкретную настройку;</li>
     *   <li>позиции, которые ВИСЯТ до сих пор, в него не попадают, а они и есть
     *       худшие: время под риском systematically занижено;</li>
     *   <li>наблюдений мало — у BTC 88 кругов в сутки против 925 принтов.</li>
     * </ul>
     *
     * <h2>Как считается</h2>
     *
     * Круг требует исполнения на ОБЕИХ сторонах: сначала одна нога, потом
     * противоположная. Ожидание каждой — величина, обратная частоте событий,
     * дотянувшихся до δ с этой стороны:
     *
     * <pre>
     *   T(δ) = 1/λ_бид(δ) + 1/λ_аск(δ)
     *   риск = σ√T,  захват = 2δ,  отношение = 2δ / σ√T
     * </pre>
     *
     * ⚠️ Стороны считаются ОТДЕЛЬНО намеренно. Если поток перекошен, одна нога
     * ждёт дольше другой, и складывать надо именно два разных ожидания, а не
     * удваивать одно. Замер 12.09.2026 показал, что от несмещённой середины книги
     * поток симметричен (45–58%), но от НАШЕЙ опоры перекос доходил до 85% — то
     * есть асимметрию создаёт смещение опоры, и она реальна для нас.
     *
     * ⚠️ Это ВЕРХНЯЯ оценка качества: предполагается, что до нашей заявки
     * доходит каждое событие, дотянувшееся до δ. Очередь, видимость и то, что
     * лот у нас один, могут только ухудшить.
     */
    private static String payPerRisk(List<Print> prints, double days,
                                     TreeMap<Long, Double> fair, double halfSpreadBp,
                                     TreeMap<Long, Top> book) {
        double sigma = volBpPerMin(fair);
        if (!(sigma > 0)) {
            return "\nотношение по ленте: волатильности не хватило данных\n";
        }
        StringBuilder sb = new StringBuilder(
                "\nПЛАТЯТ ЛИ ЗА РИСК ПО ЛЕНТЕ (σ = " + String.format(Locale.ROOT, "%.2f", sigma)
                        + " б.п./мин)\n");
        sb.append("  δ,б.п. | бид/сут | аск/сут | цикл | ЗАХВ/РИСК цикл | держание 1/λ_аск | "
                + "ЗАХВ/РИСК держ. | T_держ/T_диф | за сутки\n");
        for (double dist : GRID) {
            List<Print> ev = events(prints, dist);
            long bid = ev.stream().filter(p -> p.aggressor() < 0).count();   // продавец бьёт наш бид
            long ask = ev.stream().filter(p -> p.aggressor() > 0).count();
            if (bid < 3 || ask < 3) {
                continue;
            }
            double lamBid = bid / days;
            double lamAsk = ask / days;
            double tMin = (1 / lamBid + 1 / lamAsk) * 1440;                  // сутки → минуты
            double holdMin = 1440 / lamAsk;
            double cap = 2 * dist;
            double perCycle = cap / (sigma * Math.sqrt(tMin));
            double perHold = cap / (sigma * Math.sqrt(holdMin));
            // Диффузионное время δ: сколько в среднем идти цене, чтобы пройти δ.
            double tDiff = (dist / sigma) * (dist / sigma);
            sb.append(String.format(Locale.ROOT,
                    "  %6.0f | %7.1f | %7.1f | %4.0f м | %14.2f | %14.0f м | %15.2f%s | %12.1f | %8.2f%n",
                    dist, lamBid, lamAsk, tMin, perCycle, holdMin, perHold,
                    perHold < 1 ? " ⚠️" : "  ", holdMin / tDiff,
                    // кругов в сутки ограничены ЦИКЛОМ, а риск круга несёт нога держания
                    perHold * Math.sqrt(1440 / tMin)));
        }
        sb.append("⚠️ Цикл = 1/λ_бид + 1/λ_аск — сколько идёт круг, и он задаёт число кругов в\n");
        sb.append("сутки. Но ПОД РИСКОМ только нога держания 1/λ_аск: пока ждём покупку, позиции\n");
        sb.append("нет (док. 152, §II). Поэтому риск круга — по держанию, темп — по циклу.\n");
        sb.append("T_держ/T_диф: во сколько раз ждём дольше, чем цена проходит δ; отношение\n");
        sb.append("держания = 2/√(T_держ/T_диф).\n");
        sb.append("⚠️⚠️ ВСЕ ЭТИ ОТНОШЕНИЯ ЗНАКА ДОХОДА НЕ ВИДЯТ: σ√T считает риск круга\n");
        sb.append("диффузией, а замер по живым кругам (`--revx-hold-check`, «Условный риск»,\n");
        sb.append("13.09.2026) нашёл, что убыток сидит в СРЕДНЕМ сносе против позиции (отбор\n");
        sb.append("−10…−25 б.п. на кругах 5–120 мин), а не в разбросе. Верхняя оценка качества.\n");
        sb.append("Это ВЕРХНЯЯ оценка — предполагает, что до нас доходит каждое событие.\n");
        sb.append(withQueue(prints, days, sigma, book));
        sb.append(skewSweep(prints, days, sigma));
        sb.append(askSchedule(prints, days, sigma, halfSpreadBp, fair));
        return sb.toString();
    }

    /**
     * СМЕЩЕНИЕ ОПОРЫ = РАЗНЫЕ ОТСТУПЫ НА СТОРОНАХ, и здесь оно считается по риску.
     *
     * <h2>Почему это одно и то же</h2>
     *
     * Сдвинуть опору вниз на {@code b} — значит поставить бид на {@code δ+b} от
     * середины книги, а аск на {@code δ−b}. Никакой другой разницы нет: цена
     * заявки определяется опорой и отступом, и их сумма — единственное, что
     * видит рынок. Поэтому перебор по паре отступов отвечает на вопрос про
     * смещение полностью.
     *
     * <h2>Почему по риску, а не по доходу</h2>
     *
     * По доходу смещение уже мерили (задача A13): на падающем окне оно помогает,
     * на растущем мешает, сумма по двум режимам — ровно ноль. То есть доход
     * показывает направленную ставку и ничего не говорит о том, лучше ли стала
     * конструкция. Отношение захвата к риску от траектории не зависит и отвечает
     * именно на это.
     *
     * ⚠️ Ожидание круга — СУММА двух ожиданий, и в ней командует бо́льшее
     * слагаемое. Смещение делает одну ногу быстрее, другую медленнее, и
     * медленная съедает выигрыш. Ожидать выигрыша от асимметрии поэтому не
     * приходится — но проверить надо, интуиция в этом проекте подводила не раз.
     */
    /**
     * РАСПИСАНИЕ АСКА: чем платить за сокращение времени под позицией.
     *
     * <h2>Одна задача вместо трёх</h2>
     *
     * Принудительная разгрузка по таймеру, лестница уровней и затухающий отступ
     * выглядят разными идеями, а на деле это одно: КАК менять цену продажи по
     * мере старения позиции. Все три сводятся к расписанию {@code δ(t)}, и
     * сравнивать их надо одной меркой.
     *
     * <h2>Как считается</h2>
     *
     * Из ленты известна интенсивность исполнения на каждом расстоянии — это та
     * же {@code λ(δ)}, что и в таблице выше. Для расписания {@code δ(t)}
     * вероятность дожить до момента {@code t} равна {@code exp(−∫λ(δ(s))ds)},
     * и отсюда численно берутся ожидаемое время до продажи и ожидаемый захват В
     * МОМЕНТ исполнения — а он у затухающего расписания меньше стартового.
     *
     * ⚠️ Захват круга считается как {@code δ_бид + E[δ_аск]}: покупка прошла по
     * своей цене, продажа — по той, до которой расписание успело дойти.
     *
     * ⚠️ Модель не знает про цену, только про время. Она отвечает на вопрос
     * «сколько ждать и сколько взять», а не «куда пойдёт рынок» — и это верно:
     * риск уже учтён через σ√T.
     */
    private static String askSchedule(List<Print> prints, double days, double sigma,
                                      double halfSpreadBp, TreeMap<Long, Double> fair) {
        // λ_аск(δ) по сетке: сколько событий в сутки дотягивается до δ со
        // стороны покупателя (он бьёт наш аск).
        TreeMap<Double, Double> lam = new TreeMap<>();
        for (double d : GRID) {
            long n = events(prints, d).stream().filter(p -> p.aggressor() > 0).count();
            if (n >= 3) {
                lam.put(d, n / days);
            }
        }
        if (lam.size() < 3) {
            return "\n  расписание аска: данных мало\n";
        }
        double dBid = 8;
        long nb = events(prints, dBid).stream().filter(p -> p.aggressor() < 0).count();
        if (nb < 3) {
            return "\n  расписание аска: покупок на 8 б.п. мало\n";
        }
        double tBid = days / nb * 1440;

        StringBuilder sb = new StringBuilder(
                "\n  РАСПИСАНИЕ АСКА при биде на 8 б.п. (ожидание покупки "
                        + String.format(Locale.ROOT, "%.0f", tBid) + " мин)\n");
        sb.append("  политика                    | ждём аск | захват | всего T | риск | ЗАХВ/РИСК\n");
        record Policy(String name, double start, double floorBp, double halfLifeMin) {
        }
        List<Policy> policies = new ArrayList<>(List.of(
                new Policy("постоянный 8 б.п.", 8, 8, 0),
                new Policy("постоянный 12 б.п.", 12, 12, 0),
                new Policy("затухание 12→8, полураспад 30м", 12, 8, 30),
                new Policy("затухание 12→4, полураспад 30м", 12, 4, 30),
                new Policy("затухание 12→2, полураспад 30м", 12, 2, 30),
                new Policy("затухание 12→2, полураспад 10м", 12, 2, 10),
                new Policy("затухание 8→2, полураспад 15м", 8, 2, 15)));
        for (Policy p : policies) {
            double survive = 1.0;
            double eT = 0;
            double eCap = 0;
            double p90 = -1;                         // когда закрыто девять из десяти
            double step = 0.5;                       // минуты
            for (double t = 0; t < 4000 && survive > 1e-4; t += step) {
                double d = p.halfLifeMin() <= 0 ? p.start()
                        : p.floorBp() + (p.start() - p.floorBp())
                                * Math.pow(0.5, t / p.halfLifeMin());
                double rate = lambdaAt(lam, d) / 1440.0;     // в минуту
                double pFill = survive * (1 - Math.exp(-rate * step));
                eT += pFill * (t + step / 2);
                eCap += pFill * d;
                survive -= pFill;
                if (p90 < 0 && survive <= 0.10) {
                    p90 = t + step;
                }
            }
            if (survive > 0.02) {
                sb.append(String.format(Locale.ROOT, "  %-27s | не закрывается (%.0f%% висит)%n",
                        p.name(), 100 * survive));
                continue;
            }
            double closed = 1 - survive;
            double askT = eT / closed;
            double askCap = eCap / closed;
            double totT = tBid + askT;
            double risk = sigma * Math.sqrt(totT);
            double cap = dBid + askCap;
            // ⚠️ ХВОСТ ВАЖНЕЕ СРЕДНЕГО. У бота A шесть худших кругов из 62 отняли
            // втрое больше, чем заработали остальные 56 (задача A15). Политика,
            // у которой одинаковое среднее время, но обрезанный хвост, лучше — а
            // по отношению, посчитанному через E[T], этого не видно.
            double tail = p90 < 0 ? 4000 : p90;
            double riskTail = sigma * Math.sqrt(tBid + tail);
            sb.append(String.format(Locale.ROOT,
                    "  %-27s | %6.0f м | %6.1f | %6.0f м | %4.1f | %9.2f | %7.0f м | %6.2f%s%n",
                    p.name(), askT, cap, totT, risk, cap / risk, tail, cap / riskTail,
                    cap / risk >= 1 ? "  ✓" : ""));
        }
        sb.append("  ⚠️ захват круга = бид 8 + средний аск В МОМЕНТ исполнения.\n");
        sb.append("  Затухание платит захватом за время: вопрос в том, что дешевле.\n");
        sb.append(ladder(lam, tBid, dBid, sigma, prints, days, fair));
        sb.append(takerExit(lam, tBid, dBid, sigma, halfSpreadBp));
        return sb.toString();
    }

    /**
     * ЛЕСТНИЦА УРОВНЕЙ против одного уровня.
     *
     * <h2>Что сравнивается</h2>
     *
     * Один и тот же капитал либо стоит одной заявкой на расстоянии δ, либо
     * разложен по нескольким уровням вокруг δ. Каждая доля ждёт своего
     * исполнения: ближний уровень исполняется быстро и берёт мало, дальний
     * наоборот. Средние по долям и дают время и захват лестницы.
     *
     * <h2>Чего ждать заранее</h2>
     *
     * ⚠️ {@code λ} падает с расстоянием экспоненциально, значит ожидание
     * {@code 1/λ} растёт экспоненциально, то есть ВЫПУКЛО. По неравенству
     * Йенсена среднее от выпуклой функции больше функции от среднего — поэтому
     * у лестницы среднее ожидание ДЛИННЕЕ, чем у одиночной заявки на том же
     * среднем расстоянии. Чистого выигрыша во времени быть не должно.
     *
     * Польза лестницы, если она есть, — не в среднем, а в РАЗБРОСЕ: ближние
     * уровни закрываются быстро и не дают позиции состариться целиком. Поэтому
     * рядом со средним печатается и хвост.
     */
    private static String ladder(TreeMap<Double, Double> lam, double tBid, double dBid,
                                 double sigma, List<Print> prints, double days,
                                 TreeMap<Long, Double> fairSeries) {
        record Rung(String name, double[] levels) {
        }
        List<Rung> rungs = List.of(
                new Rung("1 уровень: 8", new double[]{8}),
                new Rung("1 уровень: 10", new double[]{10}),
                new Rung("3 равномерно: 6, 8, 10", new double[]{6, 8, 10}),
                new Rung("3 геометр. x1.3", new double[]{6, 7.8, 10.14}),
                new Rung("3 геометр. x1.5", new double[]{5, 7.5, 11.25}),
                new Rung("5 равномерно: 4..12", new double[]{4, 6, 8, 10, 12}),
                new Rung("5 геометр. x1.25", new double[]{4, 5, 6.25, 7.81, 9.77}),
                new Rung("5 геометр. x1.4", new double[]{4, 5.6, 7.84, 10.98, 15.37}));
        StringBuilder sb = new StringBuilder(
                "\n  ЛЕСТНИЦА АСКА (тот же капитал, разложенный по уровням)\n");
        sb.append("  раскладка                   | ждём аск | захват | всего T | риск | ЗАХВ/РИСК"
                + " | худший ур.\n");
        for (Rung r : rungs) {
            double sumT = 0;
            double sumCap = 0;
            double worstT = 0;
            for (double d : r.levels()) {
                double t = 1440.0 / lambdaAt(lam, d);
                sumT += t;
                sumCap += d;
                worstT = Math.max(worstT, t);
            }
            int n = r.levels().length;
            double askT = sumT / n;
            double cap = dBid + sumCap / n;
            double totT = tBid + askT;
            double risk = sigma * Math.sqrt(totT);
            sb.append(String.format(Locale.ROOT,
                    "  %-27s | %6.0f м | %6.1f | %6.0f м | %4.1f | %9.2f | %7.0f м%n",
                    r.name(), askT, cap, totT, risk, cap / risk, worstT));
        }
        sb.append("  ⚠️ «худший ур.» — ожидание самого дальнего уровня: он и создаёт хвост,\n");
        sb.append("  потому что его доля позиции стареет дольше всех.\n");
        sb.append(sweepThroughput(prints, days, fairSeries));
        return sb.toString();
    }

    /**
     * 🔑 ГДЕ ЛЕСТНИЦА ВЫИГРЫВАЕТ: СВИПЫ.
     *
     * <h2>Чего не видит расчёт выше</h2>
     *
     * В {@link #ladder} уровни считаются независимыми пуассоновскими событиями,
     * и по выпуклости лестница обязана проигрывать. Но исполнение у нас идёт
     * СВИПАМИ: одна рыночная заявка разметает несколько уровней разом. Одиночная
     * заявка возьмёт с такого события ОДИН лот, а лестница — все уровни, до
     * которых свип дотянулся.
     *
     * Это и есть «бонус», про который спрашивает владелец, и он НЕ случайность:
     * он считается из распределения глубины событий, которое уже измерено.
     *
     * <h2>Что печатается</h2>
     *
     * Для каждой раскладки — сколько лотов в сутки она снимет и какой захват
     * соберёт, если считать, что событие глубиной {@code d} исполняет ВСЕ уровни
     * с {@code δ ≤ d}.
     *
     * ⚠️ Это верхняя оценка пропускной способности: она требует, чтобы на каждом
     * уровне лежал лот, то есть чтобы инвентаря и потолка хватало. Когда потолок
     * связывает, лестница вырождается обратно в одиночную заявку.
     *
     * ⚠️ И это ДРУГОЙ вопрос, чем отношение захват/риск. Там сравнивался один
     * круг, здесь — оборот за сутки. Лестница может проигрывать по риску на лот и
     * выигрывать по обороту; что важнее, зависит от того, упёрты ли мы в потолок
     * инвентаря или в бюджет постановок.
     */
    private static String sweepThroughput(List<Print> prints, double days,
                                          TreeMap<Long, Double> fairSeries) {
        record Rung(String name, double[] levels) {
        }
        // ⚠️ РАВНОМЕРНЫЕ против ГЕОМЕТРИЧЕСКИХ. Владелец предложил ставить каждый
        // следующий уровень на n процентов дальше предыдущего. Смысл в том, что
        // λ спадает с расстоянием ЭКСПОНЕНЦИАЛЬНО: равномерный шаг даёт уровни,
        // различающиеся по частоте исполнения в разы, то есть ближний работает
        // за всех, а дальний почти мёртв. Геометрический шаг сгущает уровни там,
        // где поток есть, и разрежает там, где его нет.
        List<Rung> rungs = List.of(
                new Rung("1 уровень: 8", new double[]{8}),
                new Rung("1 уровень: 10", new double[]{10}),
                new Rung("3 равномерно: 6, 8, 10", new double[]{6, 8, 10}),
                new Rung("3 геометр. ×1.3: 6, 7.8, 10.1", new double[]{6, 7.8, 10.14}),
                new Rung("3 геометр. ×1.5: 5, 7.5, 11.3", new double[]{5, 7.5, 11.25}),
                new Rung("5 равномерно: 4..12 шагом 2", new double[]{4, 6, 8, 10, 12}),
                new Rung("5 геометр. ×1.25: 4..9.8", new double[]{4, 5, 6.25, 7.81, 9.77}),
                new Rung("5 геометр. ×1.4: 4..15.4", new double[]{4, 5.6, 7.84, 10.98, 15.37}));
        // События по стороне аска (покупатель бьёт наш аск), схлопнутые в пачки.
        List<Print> ev = events(prints, 2).stream().filter(p -> p.aggressor() > 0).toList();
        if (ev.size() < 20) {
            return "\n  пропускная способность: событий мало\n";
        }
        StringBuilder sb = new StringBuilder(
                "\n  ПРОПУСКНАЯ СПОСОБНОСТЬ НА СВИПАХ (событие глубиной d берёт все уровни ≤ d)\n");
        // ⚠️ ГОЛЫЙ ЗАХВАТ ОБМАНЫВАЕТ, поэтому считается и ЧИСТЫЙ результат.
        // Глубокие свипы дают больший захват, но они же самые токсичные: у BTC
        // c(60с) на 14 б.п. равен +26 б.п. против +5.5 на шестёрке. Дальний
        // уровень берёт больше спреда и больше отдаёт обратно, и по одному
        // захвату решать нельзя.
        //
        // Профиль размера — вопрос владельца: может, дальним уровням давать
        // лот побольше? Довод за: они берут больше спреда. Довод против: они
        // нагружают нас там, где поток токсичнее всего. Считаем оба.
        sb.append("  раскладка                   | профиль | лотов/сут | захват/сут"
                + " | ЧИСТО/сут | лотов на событие\n");
        String[] profNames = {"ровно", "растут", "убывают"};
        Map<Double, Double> costCache = new LinkedHashMap<>();
        for (Rung r : rungs) {
            for (int pi = 0; pi < profNames.length; pi++) {
                if (r.levels().length == 1 && pi > 0) {
                    continue;                     // у одного уровня профиля нет
                }
                double[] w = weights(r.levels().length, pi);
                double lots = 0;
                double cap = 0;
                double net = 0;
                for (Print p : ev) {
                    for (int i = 0; i < r.levels().length; i++) {
                        double d = r.levels()[i];
                        if (p.distBp() >= d) {
                            double c = costCache.computeIfAbsent(d, k -> costAt(ev, k, fairSeries));
                            lots += w[i];
                            cap += w[i] * d;
                            net += w[i] * (d - c);
                        }
                    }
                }
                if (lots == 0) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT,
                        "  %-27s | %-7s | %9.1f | %10.0f | %9.0f | %16.2f%n",
                        pi == 0 ? r.name() : "", profNames[pi], lots / days, cap / days,
                        net / days, lots / ev.size()));
            }
        }
        sb.append("  ⚠️ верхняя оценка: требует, чтобы на каждом уровне лежал лот. При упёртом\n");
        sb.append("  потолке инвентаря лестница вырождается в одиночную заявку.\n");
        return sb.toString();
    }

    /**
     * ТЕЙКЕРСКИЙ ВЫХОД ПО ТАЙМЕРУ: платим спред и комиссию, но обрезаем хвост.
     *
     * <h2>Чем он отличается от всего остального</h2>
     *
     * Все прочие политики подчиняются {@code λ(δ)} — они ЖДУТ. Тейкерский выход
     * не ждёт вовсе: он пересекает спред и закрывает позицию немедленно. Это
     * единственный инструмент, который ставит жёсткий потолок на время под
     * позицией, а значит и на {@code σ√T}.
     *
     * <h2>Цена</h2>
     *
     * Мы купили на {@code δ_бид} ниже середины. Продаём по лучшему биду, то есть
     * на полуспред ниже середины, и платим комиссию тейкера. Итог круга:
     * {@code δ_бид − полуспред − комиссия}, и это ОТРИЦАТЕЛЬНО при наших числах.
     *
     * ⚠️ Комиссия тейкера ~9 б.п. со слов владельца; в наших данных её нет,
     * потому что мы никогда не тейкали (задача A17). Проверить по тарифам.
     */
    private static String takerExit(TreeMap<Double, Double> lam, double tBid, double dBid,
                                    double sigma, double halfSpreadBp) {
        double fee = 9.0;
        double dAsk = 8;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%n  ТЕЙКЕРСКИЙ ВЫХОД ПО ТАЙМЕРУ (полуспред %.1f б.п. + комиссия %.0f)%n",
                halfSpreadBp, fee));
        sb.append("  таймер | успели мейкером | захват | всего T | риск | ЗАХВ/РИСК\n");
        double rate = lambdaAt(lam, dAsk) / 1440.0;
        double takerCap = dBid - halfSpreadBp - fee;
        for (double tMax : new double[]{5, 10, 20, 30, 60, 120, 1e9}) {
            double pMaker = 1 - Math.exp(-rate * tMax);
            // Среднее время исполнения мейкером ПРИ УСЛОВИИ, что успели.
            double eTmaker = rate > 0
                    ? (1 - Math.exp(-rate * tMax) * (1 + rate * tMax)) / (rate * pMaker)
                    : tMax;
            double askT = pMaker * eTmaker + (1 - pMaker) * tMax;
            double cap = pMaker * (dBid + dAsk) + (1 - pMaker) * takerCap;
            double totT = tBid + askT;
            double risk = sigma * Math.sqrt(totT);
            sb.append(String.format(Locale.ROOT,
                    "  %6s | %14.0f%% | %6.1f | %6.0f м | %4.1f | %9.2f%n",
                    tMax > 1e8 ? "нет" : String.format(Locale.ROOT, "%.0f м", tMax),
                    100 * pMaker, cap, totT, risk, cap / risk));
        }
        sb.append("  ⚠️ тейкерский круг даёт ОТРИЦАТЕЛЬНЫЙ захват "
                + String.format(Locale.ROOT, "%.1f", takerCap) + " б.п.:\n");
        sb.append("  мы платим спред и комиссию за то, чтобы не ждать.\n");
        return sb.toString();
    }

    /**
     * Колонки глубины, если они есть в этой базе.
     *
     * ⚠️ Собранная база их может НЕ иметь: {@code StandAssembler} переносит
     * ПЕРЕСЕЧЕНИЕ колонок, и если основа сборки старше 10.09.2026, глубина
     * теряется целиком. Прибор обязан пережить это, а не падать: очередь тогда
     * считается по лучшему уровню, что для тача почти не хуже — там и стоит
     * основной объём.
     */
    private static String deepCols(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(revx_book)")) {
            while (rs.next()) {
                if ("deep_bids".equals(rs.getString("name"))) {
                    return "deep_bids, deep_asks";
                }
            }
        } catch (Exception ignored) {
            // нет доступа к схеме — считаем, что глубины нет
        }
        return "NULL AS deep_bids, NULL AS deep_asks";
    }

    /** Медианный полуспред книги, б.п. — цена пересечения для тейкера. */
    private static double halfSpread(TreeMap<Long, Top> book) {
        List<Double> v = new ArrayList<>();
        for (Top t : book.values()) {
            double mid = t.mid();
            if (mid > 0 && t.ask() > t.bid()) {
                v.add(1e4 * (t.ask() - t.bid()) / 2 / mid);
            }
        }
        if (v.isEmpty()) {
            return 0;
        }
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /**
     * Доли капитала по уровням: ровно, растущие к дальним, убывающие.
     *
     * Сумма всегда единица — сравнивается ФОРМА раскладки при одном капитале, а
     * не размер. Множитель 1.5 взят как заметный, но не крайний: при большем
     * профиль вырождается в «весь капитал на один уровень», а это уже не
     * лестница.
     */
    static double[] weights(int n, int profile) {
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            w[i] = switch (profile) {
                case 1 -> Math.pow(1.5, i);       // растут к дальним уровням
                case 2 -> Math.pow(1.5, n - 1 - i); // убывают
                default -> 1;
            };
            sum += w[i];
        }
        for (int i = 0; i < n; i++) {
            w[i] /= sum;
        }
        return w;
    }

    /**
     * Стоимость отбора на расстоянии δ, горизонт 60 с — по событиям, дошедшим
     * до δ. Та же величина, что в главной кривой, только запрашиваемая точечно.
     */
    private static double costAt(List<Print> ev, double dist, TreeMap<Long, Double> fair) {
        List<Print> reach = ev.stream().filter(p -> p.distBp() >= dist).toList();
        Double c = reach.isEmpty() ? null : cost(reach, fair, 60_000);
        return c == null ? 0 : c;
    }

    /** {@code λ} на произвольном δ — логарифмическая интерполяция по сетке. */
    private static double lambdaAt(TreeMap<Double, Double> lam, double d) {
        Map.Entry<Double, Double> lo = lam.floorEntry(d);
        Map.Entry<Double, Double> hi = lam.ceilingEntry(d);
        if (lo == null) {
            return lam.firstEntry().getValue();      // ближе первой ступени — берём её
        }
        if (hi == null) {
            return lam.lastEntry().getValue();
        }
        if (lo.getKey().equals(hi.getKey())) {
            return lo.getValue();
        }
        // λ падает с расстоянием примерно экспоненциально, поэтому интерполяция
        // идёт по логарифму: линейная занижала бы середину интервала.
        double w = (d - lo.getKey()) / (hi.getKey() - lo.getKey());
        return Math.exp(Math.log(lo.getValue()) * (1 - w) + Math.log(hi.getValue()) * w);
    }

    private static String skewSweep(List<Print> prints, double days, double sigma) {
        double[] biases = {0, 1, 2, 3, 4, 6};
        double[] bases = {6, 8, 10, 12};
        StringBuilder sb = new StringBuilder(
                "\n  СМЕЩЕНИЕ ОПОРЫ: бид на δ+b, аск на δ−b (b вниз), отношение захв/риск\n");
        sb.append("  база δ |");
        for (double b : biases) {
            sb.append(String.format(Locale.ROOT, " b=%-4.0f|", b));
        }
        sb.append('\n');
        for (double base : bases) {
            sb.append(String.format(Locale.ROOT, "  %6.0f |", base));
            for (double b : biases) {
                double dBid = base + b;
                double dAsk = base - b;
                if (dAsk < 1) {
                    sb.append("   —   |");
                    continue;
                }
                long nb = events(prints, dBid).stream().filter(p -> p.aggressor() < 0).count();
                long na = events(prints, dAsk).stream().filter(p -> p.aggressor() > 0).count();
                if (nb < 3 || na < 3) {
                    sb.append("   —   |");
                    continue;
                }
                double tMin = (days / nb + days / na) * 1440;
                double risk = sigma * Math.sqrt(tMin);
                // Захват круга — сумма обоих отступов: сколько мы взяли на
                // покупке плюс сколько на продаже. Смещение её не меняет.
                double cap = dBid + dAsk;
                sb.append(String.format(Locale.ROOT, " %5.2f |", risk > 0 ? cap / risk : 0));
            }
            sb.append('\n');
        }
        sb.append("  ⚠️ захват круга (δ_бид + δ_аск) от смещения НЕ зависит — меняется только\n");
        sb.append("  ожидание. Если лучший столбец b=0, асимметрия бесполезна и по риску тоже.\n");
        return sb.toString();
    }

    /**
     * 🔑 ПОПРАВКА НА ОЧЕРЕДЬ — пункт 4.2 документа 151.
     *
     * <h2>Зачем</h2>
     *
     * Всё, что считалось выше, предполагает, что до нашей заявки доходит КАЖДОЕ
     * событие, дотянувшееся до δ. Это заведомо неверно у тача: там перед нами
     * стоит чужой объём, и событие сначала выбирает его. Причём ошибка растёт
     * ровно туда, где мы хотели бы котировать — чем ближе к середине, тем толще
     * книга.
     *
     * Документ 151 предлагал взять модель очереди из hftbacktest. Здесь она
     * проще и считается по нашим же данным: у каждого события известен объём, у
     * книги — сколько лежит на нашем расстоянии. Заявка исполняется, если объём
     * события превысил очередь впереди.
     *
     * <h2>Ограничения, которые надо помнить</h2>
     *
     * ⚠️ Очередь берётся из снимка ПЕРЕД событием, а за время жизни заявки она
     * меняется — кто-то отменяется, кто-то встаёт. Наша оценка поэтому
     * ПЕССИМИСТИЧНА для долго стоящей заявки: чужие отмены её продвигают, а мы
     * этого не видим. Обратная ошибка к прежней, и обе границы теперь есть.
     *
     * ⚠️ Мы считаем, что встали В КОНЕЦ очереди. Реально заявка, простоявшая
     * час, стоит уже не в конце.
     */
    private static String withQueue(List<Print> prints, double days, double sigma,
                                    TreeMap<Long, Top> book) {
        if (book.isEmpty()) {
            return "";
        }
        double lot = Double.parseDouble(System.getProperty("revx.flow.lot-base", "0.00003765"));
        StringBuilder sb = new StringBuilder(
                "\n  С ПОПРАВКОЙ НА ОЧЕРЕДЬ (лот " + lot + " базовой, встаём в конец)\n");
        sb.append("  δ,б.п. | событий | из них дошло до нас | доля | ожидание | ЗАХВ/РИСК\n");
        for (double dist : GRID) {
            List<Print> ev = events(prints, dist);
            if (ev.size() < 10) {
                continue;
            }
            int fillsBid = 0;
            int fillsAsk = 0;
            int nb = 0;
            int na = 0;
            for (Print p : ev) {
                boolean bidSide = p.aggressor() < 0;
                if (bidSide) {
                    nb++;
                } else {
                    na++;
                }
                Map.Entry<Long, Top> b = book.floorEntry(p.tsMs());
                if (b == null) {
                    continue;
                }
                double queue = b.getValue().queueAt(dist, bidSide);
                // Событие выбирает очередь, потом нас. Наш лот исполнен хотя бы
                // частично, если объёма хватило перешагнуть очередь.
                if (p.qty() > queue) {
                    if (bidSide) {
                        fillsBid++;
                    } else {
                        fillsAsk++;
                    }
                }
            }
            if (fillsBid < 2 || fillsAsk < 2) {
                sb.append(String.format(Locale.ROOT,
                        "  %6.0f | %7d | %19s | %4.0f%% | %8s | %9s%n",
                        dist, ev.size(), fillsBid + "/" + fillsAsk,
                        100.0 * (fillsBid + fillsAsk) / ev.size(), "—", "—"));
                continue;
            }
            double tMin = (days / fillsBid + days / fillsAsk) * 1440;
            double risk = sigma * Math.sqrt(tMin);
            double cap = 2 * dist;
            sb.append(String.format(Locale.ROOT,
                    "  %6.0f | %7d | %19s | %4.0f%% | %6.0f м | %9.2f%n",
                    dist, ev.size(), fillsBid + "/" + fillsAsk,
                    100.0 * (fillsBid + fillsAsk) / ev.size(), tMin, cap / risk));
        }
        sb.append("  ⚠️ очередь из снимка ПЕРЕД событием; чужие отмены её сокращают, а мы их\n");
        sb.append("  не видим — значит это НИЖНЯЯ граница. Вместе с таблицей выше получается вилка.\n");
        return sb.toString();
    }

    /** СКО минутных приращений опоры, б.п. — та же величина, что в обходе. */
    private static double volBpPerMin(TreeMap<Long, Double> fair) {
        TreeMap<Long, Double> byMin = new TreeMap<>();
        for (Map.Entry<Long, Double> e : fair.entrySet()) {
            byMin.putIfAbsent(e.getKey() / 60_000, e.getValue());
        }
        List<Double> d = new ArrayList<>();
        Long pk = null;
        double pv = 0;
        for (Map.Entry<Long, Double> e : byMin.entrySet()) {
            if (pk != null && e.getKey() - pk == 1 && pv > 0) {
                d.add(1e4 * (e.getValue() - pv) / pv);
            }
            pk = e.getKey();
            pv = e.getValue();
        }
        if (d.size() < 10) {
            return 0;
        }
        double m = d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(d.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / d.size());
    }

    /**
     * κ наклоном {@code ln λ} по δ — то же, что мерила лестница прогонов, но по
     * ленте и на всём диапазоне сразу.
     *
     * ⚠️ Регрессия берёт только те ступени, где принтов достаточно: на дальнем
     * конце λ падает до единиц, логарифм там шумит сильнее самого наклона, и
     * пара таких точек уводит оценку куда угодно.
     */
    private static String kappa(Map<Double, Double> lambda, double days) {
        List<Map.Entry<Double, Double>> pts = lambda.entrySet().stream()
                .filter(e -> e.getValue() * days >= 20)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (pts.size() < 3) {
            return "\nκ: ступеней с достаточным потоком меньше трёх — не считаю\n";
        }
        double mx = pts.stream().mapToDouble(Map.Entry::getKey).average().orElse(0);
        double my = pts.stream().mapToDouble(e -> Math.log(e.getValue())).average().orElse(0);
        double num = 0;
        double den = 0;
        for (var e : pts) {
            double dx = e.getKey() - mx;
            num += dx * (Math.log(e.getValue()) - my);
            den += dx * dx;
        }
        double slope = den == 0 ? 0 : num / den;
        double k = -slope;
        return String.format(Locale.ROOT,
                "%nκ по ленте: %.3f на б.п. (1/κ = %.2f б.п.), по %d ступеням %.0f..%.0f%n"
                        + "  при markout c: оптимальный отступ δ* = c + 1/κ%n",
                k, k > 0 ? 1 / k : 0, pts.size(),
                pts.get(0).getKey(), pts.get(pts.size() - 1).getKey());
    }

    /**
     * κ скользящим окном. Смысл не в средней величине, а в РАЗБРОСЕ: если κ гуляет
     * вдвое, то и оптимальный отступ гуляет на пару базисных пунктов, и постоянная
     * настройка не может быть оптимальной ни в одном режиме.
     */
    private static String rollingKappa(List<Print> prints, double days, long from, long to) {
        long window = 3_600_000L;
        List<Double> ks = new ArrayList<>();
        for (long t = from; t + window <= to; t += window) {
            final long lo = t;
            final long hi = t + window;
            List<Print> in = prints.stream()
                    .filter(p -> p.tsMs() >= lo && p.tsMs() < hi).toList();
            if (in.size() < 60) {
                continue;
            }
            Map<Double, Double> lam = new LinkedHashMap<>();
            for (double dist : GRID) {
                long n = in.stream().filter(p -> p.distBp() >= dist).count();
                if (n >= 10) {
                    lam.put(dist, (double) n);
                }
            }
            if (lam.size() < 3) {
                continue;
            }
            double mx = lam.keySet().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double my = lam.values().stream().mapToDouble(Math::log).average().orElse(0);
            double num = 0;
            double den = 0;
            for (var e : lam.entrySet()) {
                double dx = e.getKey() - mx;
                num += dx * (Math.log(e.getValue()) - my);
                den += dx * dx;
            }
            if (den > 0) {
                ks.add(-num / den);
            }
        }
        if (ks.size() < 3) {
            return "κ скользящим окном: часов с достаточным потоком меньше трёх\n";
        }
        List<Double> s = ks.stream().sorted().toList();
        return String.format(Locale.ROOT,
                "κ скользящим окном (час): медиана %.3f, 10%% %.3f, 90%% %.3f, часов %d%n"
                        + "  разброс 1/κ: %.1f .. %.1f б.п. — на столько гуляет оптимум%n",
                q(s, 0.5), q(s, 0.10), q(s, 0.90), s.size(),
                q(s, 0.90) > 0 ? 1 / q(s, 0.90) : 0, q(s, 0.10) > 0 ? 1 / q(s, 0.10) : 0);
    }

    /**
     * Разрезы кривой: откуда берётся отбор.
     *
     * Каждый разрез отвечает на отдельную гипотезу об источнике: крупный принт —
     * информированный; короткий промежуток — каскад; перекос книги —
     * предсказуемый поток; пачка по одной отметке времени — свип.
     */
    private static String slices(List<Print> prints, TreeMap<Long, Double> fair) {
        StringBuilder sb = new StringBuilder("\nРАЗРЕЗЫ: откуда берётся отбор (c(60с), принты дальше 6 б.п.)\n");
        List<Print> far = events(prints, 6);
        if (far.size() < 40) {
            return sb.append("  принтов дальше 6 б.п. меньше сорока — разрезы не считаю\n").toString();
        }
        sb.append(cut(far, fair, "размер принта", Print::qty));
        sb.append(cut(far, fair, "пауза с прошлого принта, мс",
                p -> p.gapMs() < 0 ? Double.NaN : p.gapMs()));
        sb.append(cut(far, fair, "перекос книги (бид/(бид+аск))", Print::imbalance));
        // ⚠️ РЕШАЮЩИЙ РАЗРЕЗ: направление или токсичность.
        //
        // Если перекос — направленный сигнал, отбор у ПОКУПОК и ПРОДАЖ обязан
        // зависеть от него в ПРОТИВОПОЛОЖНЫЕ стороны (книга с тяжёлым бидом →
        // цена вверх → дорого тем, кто продал). Если же обе стороны страдают
        // одинаково, перекос меряет не направление, а токсичность потока — и
        // тогда сдвигать им опору бессмысленно, а раздвигать отступ или вовсе
        // не котировать — осмысленно.
        sb.append(cut(far.stream().filter(p -> p.aggressor() > 0).toList(), fair,
                "  тот же перекос, ТОЛЬКО покупки", Print::imbalance));
        sb.append(cut(far.stream().filter(p -> p.aggressor() < 0).toList(), fair,
                "  тот же перекос, ТОЛЬКО продажи", Print::imbalance));
        List<Print> sweep = far.stream().filter(p -> p.burst() > 1).toList();
        List<Print> single = far.stream().filter(p -> p.burst() == 1).toList();
        Double cs = sweep.isEmpty() ? null : cost(sweep, fair, 60_000);
        Double cg = single.isEmpty() ? null : cost(single, fair, 60_000);
        sb.append(String.format(Locale.ROOT,
                "  свип (пачка по одной отметке): принтов %d, c = %s%n"
                        + "  одиночный принт:              принтов %d, c = %s%n",
                sweep.size(), cs == null ? "—" : String.format(Locale.ROOT, "%+.2f", cs),
                single.size(), cg == null ? "—" : String.format(Locale.ROOT, "%+.2f", cg)));
        return sb.toString();
    }

    /** Разрез по квартилям одной величины. */
    private static String cut(List<Print> prints, TreeMap<Long, Double> fair, String name,
                              java.util.function.ToDoubleFunction<Print> key) {
        List<Print> ok = prints.stream()
                .filter(p -> !Double.isNaN(key.applyAsDouble(p)))
                .sorted(Comparator.comparingDouble(key)).toList();
        if (ok.size() < 40) {
            return "  " + name + ": данных мало\n";
        }
        StringBuilder sb = new StringBuilder("  " + name + ": ");
        int q = ok.size() / 4;
        for (int i = 0; i < 4; i++) {
            List<Print> part = ok.subList(i * q, i == 3 ? ok.size() : (i + 1) * q);
            Double c = cost(part, fair, 60_000);
            sb.append(String.format(Locale.ROOT, "Q%d %s  ", i + 1,
                    c == null ? "—" : String.format(Locale.ROOT, "%+.2f", c)));
        }
        return sb.append('\n').toString();
    }

    private static Double anchor(Ref ref, long ts, TreeMap<Long, Double> fair,
                                 TreeMap<Long, Top> book) {
        if (ref == Ref.FAIR) {
            Map.Entry<Long, Double> f = fair.floorEntry(ts);
            return f == null ? null : f.getValue();
        }
        Map.Entry<Long, Top> b = book.floorEntry(ts);
        if (b == null) {
            return null;
        }
        return switch (ref) {
            case MID -> b.getValue().mid();
            case MICRO -> b.getValue().micro();
            case DEEP -> {
                double d = b.getValue().deepMid(DEEP_USD);
                yield Double.isNaN(d) ? null : d;
            }
            default -> b.getValue().mid();
        };
    }

    /**
     * Сколько денег набирать с каждой стороны для глубокой середины.
     *
     * Умолчание — $5000: у BTC это примерно полоса в 10 б.п. (лучший уровень
     * держит ~$2500, до 10 б.п. набирается ~$7500), у ETH и SOL книга тоньше.
     * Сравнение нескольких величин печатается в таблице опор, и выбирать надо
     * по ней, а не по этому умолчанию.
     */
    private static final double DEEP_USD =
            Double.parseDouble(System.getProperty("revx.flow.deep-usd", "5000"));

    /** Величины глубины для сравнения опор, USD. */
    private static final double[] DEEP_GRID = {1500, 5000, 15000, 50000};

    /**
     * СРАВНЕНИЕ ОПОР — главный вывод прибора для задачи «улучшить опору».
     *
     * Опора оценивается двумя мерками, и обе не требуют торговли:
     * <ul>
     *   <li><b>перекос сторон</b> — доля принтов, оказавшихся ВЫШЕ опоры. У
     *       несмещённой опоры она около половины: покупки и продажи уходят от
     *       неё одинаково. Отклонение от 50% — прямая мера смещения, и оно
     *       асимметрично искажает расстояние, по которому мы выбираем отступ;</li>
     *   <li><b>ошибка прогноза</b> — насколько опора предсказывает середину книги
     *       через 60 с. Это тот самый критерий, который предлагает док. 151:
     *       markout по определению есть ошибка прогноза, обусловленная тем, что
     *       нас исполнили, поэтому опора с меньшей ошибкой обязана давать
     *       меньший отбор.</li>
     * </ul>
     */
    private static String anchors(MarketData md, TreeMap<Long, Double> fair,
                                  TreeMap<Long, Top> book) {
        if (book.size() < 100) {
            return "сравнение опор: книги мало\n";
        }
        StringBuilder sb = new StringBuilder(
                "\nСРАВНЕНИЕ ОПОР (чем ниже ошибка прогноза и чем ближе перекос к 50%, тем лучше)\n");
        sb.append("  опора | принтов выше опоры | ошибка прогноза | без сдвига, б.п.\n");
        for (Ref ref : Ref.values()) {
            int above = 0;
            int n = 0;
            for (MarketTrade t : md.trades()) {
                Double a = anchor(ref, t.tsMs(), fair, book);
                if (a == null || a <= 0) {
                    continue;
                }
                n++;
                if (t.price() > a) {
                    above++;
                }
            }
            // Ошибка прогноза: опора(t) против середины(t+60с), по снимкам книги.
            List<Double> errs = new ArrayList<>();
            long last = book.lastKey();
            for (Map.Entry<Long, Top> e : book.entrySet()) {
                if (e.getKey() + 60_000 > last) {
                    break;
                }
                Double a = anchor(ref, e.getKey(), fair, book);
                Map.Entry<Long, Top> fut = book.floorEntry(e.getKey() + 60_000);
                if (a == null || a <= 0 || fut == null) {
                    continue;
                }
                errs.add(1e4 * (a - fut.getValue().mid()) / fut.getValue().mid());
            }
            double bias = errs.isEmpty() ? 0 : mean(errs);
            double raw = 0;
            double net = 0;
            for (double x : errs) {
                raw += Math.abs(x);
                net += Math.abs(x - bias);
            }
            sb.append(String.format(Locale.ROOT, "  %-5s | %17.1f%% | %14.2f | %13.2f%n",
                    ref, n == 0 ? 0 : 100.0 * above / n,
                    errs.isEmpty() ? 0 : raw / errs.size(),
                    errs.isEmpty() ? 0 : net / errs.size()));
        }
        sb.append(deepAnchors(md, book));
        sb.append(betaSweep(book));
        return sb.toString();
    }

    /**
     * ГЛУБОКАЯ СЕРЕДИНА как опора — блок 4 разбора 154.
     *
     * Меряется тремя величинами, и все три без единой сделки:
     * <ul>
     *   <li><b>ошибка прогноза</b> середины через 60 с — тот же критерий, что у
     *       остальных опор;</li>
     *   <li><b>собственный шум</b>: СКО минутных приращений самой опоры. Ради
     *       него всё и затевается — заявка переставляется вслед за шумом опоры,
     *       а переставлять её на движении, которого в книге нет, бессмысленно
     *       и стоит постановок;</li>
     *   <li><b>доля снимков, где опора определена</b>: на тонкой книге нужных
     *       денег может не набраться, и тогда опоры просто нет.</li>
     * </ul>
     *
     * ⚠️ Глубина есть только с 10.09.2026 — до неё собирались пять уровней, и
     * на старых окнах таблица будет пустой. Это не поломка.
     */
    private static String deepAnchors(MarketData md, TreeMap<Long, Top> book) {
        StringBuilder sb = new StringBuilder(
                "\n  ГЛУБОКАЯ СЕРЕДИНА (док. 154 §IV): опора по цене, где набирается D денег\n");
        sb.append("     D, USD | определена | принтов выше | ошибка 60 с | без сдвига |"
                + " свой шум, б.п./мин | сдвиг к середине\n");
        long last = book.lastKey();
        // Шум самой середины книги — база для сравнения.
        double[] midErr = forecastErr(book, Top::mid, last);
        sb.append(String.format(Locale.ROOT,
                "  %10s | %10s | %12s | %11.2f | %10.2f | %18.2f | %16s%n",
                "середина", "—", "—", midErr[0], midErr[1], noise(book, Top::mid), "—"));
        for (double usd : DEEP_GRID) {
            java.util.function.ToDoubleFunction<Top> f = t -> t.deepMid(usd);
            int defined = 0;
            for (Top t : book.values()) {
                if (!Double.isNaN(t.deepMid(usd))) {
                    defined++;
                }
            }
            if (defined < book.size() / 10) {
                sb.append(String.format(Locale.ROOT, "  %10.0f | %9.0f%% | книга тоньше%n",
                        usd, 100.0 * defined / book.size()));
                continue;
            }
            int above = 0;
            int n = 0;
            for (MarketTrade t : md.trades()) {
                Map.Entry<Long, Top> b = book.floorEntry(t.tsMs());
                if (b == null) {
                    continue;
                }
                double a = b.getValue().deepMid(usd);
                if (Double.isNaN(a) || a <= 0) {
                    continue;
                }
                n++;
                if (t.price() > a) {
                    above++;
                }
            }
            double shift = 0;
            int k = 0;
            for (Top t : book.values()) {
                double a = t.deepMid(usd);
                if (!Double.isNaN(a) && t.mid() > 0) {
                    shift += 1e4 * (a - t.mid()) / t.mid();
                    k++;
                }
            }
            double[] err = forecastErr(book, f, last);
            sb.append(String.format(Locale.ROOT,
                    "  %10.0f | %9.0f%% | %11.1f%% | %11.2f | %10.2f | %18.2f | %+15.2f%n",
                    usd, 100.0 * defined / book.size(), n == 0 ? 0 : 100.0 * above / n,
                    err[0], err[1], noise(book, f), k == 0 ? 0 : shift / k));
            // Та же глубина, но средневзвешенной ценой: без ступенек уровня.
            java.util.function.ToDoubleFunction<Top> g = t -> t.deepVwapMid(usd);
            double[] verr = forecastErr(book, g, last);
            sb.append(String.format(Locale.ROOT,
                    "  %10s | %10s | %12s | %11.2f | %10.2f | %18.2f | %15s%n",
                    "  ↳ ср.взв.", "", "", verr[0], verr[1], noise(book, g), ""));
        }
        sb.append("  ⚠️ «Свой шум» — СКО минутных приращений САМОЙ опоры. Наша межплощадочная\n");
        sb.append("  fair шумит в 2.4–3.9 раза сильнее середины книги, и это прямой расход\n");
        sb.append("  постановок. Глубокая середина обязана шуметь МЕНЬШЕ середины: в этом\n");
        sb.append("  вся мысль. Если не меньше — механизм пуст.\n");
        return sb.toString();
    }

    /**
     * Ошибка прогноза середины через 60 с: {@code {сырая, без сдвига}}, б.п.
     *
     * ⚠️ Две величины, и смешивать их нельзя. Сырая содержит систематический
     * сдвиг опоры — он чинится вычитанием константы и потому дёшев. «Без
     * сдвига» — это шум, и он не чинится ничем. Опора со сдвигом 4 б.п. и
     * нулевым шумом лучше опоры без сдвига и с шумом 4 б.п., хотя сырая мерка у
     * них одинаковая.
     */
    private static double[] forecastErr(TreeMap<Long, Top> book,
                                        java.util.function.ToDoubleFunction<Top> f, long last) {
        List<Double> errs = new ArrayList<>();
        for (Map.Entry<Long, Top> e : book.entrySet()) {
            if (e.getKey() + 60_000 > last) {
                break;
            }
            double a = f.applyAsDouble(e.getValue());
            Map.Entry<Long, Top> fut = book.floorEntry(e.getKey() + 60_000);
            if (Double.isNaN(a) || a <= 0 || fut == null || fut.getValue().mid() <= 0) {
                continue;
            }
            errs.add(1e4 * (a - fut.getValue().mid()) / fut.getValue().mid());
        }
        if (errs.isEmpty()) {
            return new double[]{0, 0};
        }
        double raw = 0;
        double bias = mean(errs);
        double net = 0;
        for (double x : errs) {
            raw += Math.abs(x);
            net += Math.abs(x - bias);
        }
        return new double[]{raw / errs.size(), net / errs.size()};
    }

    /** СКО минутных приращений опоры, б.п./мин. */
    private static double noise(TreeMap<Long, Top> book,
                                java.util.function.ToDoubleFunction<Top> f) {
        TreeMap<Long, Double> byMin = new TreeMap<>();
        for (Map.Entry<Long, Top> e : book.entrySet()) {
            double v = f.applyAsDouble(e.getValue());
            if (!Double.isNaN(v) && v > 0) {
                byMin.putIfAbsent(e.getKey() / 60_000, v);
            }
        }
        List<Double> d = new ArrayList<>();
        Long pk = null;
        double pv = 0;
        for (Map.Entry<Long, Double> e : byMin.entrySet()) {
            if (pk != null && e.getKey() - pk == 1 && pv > 0) {
                d.add(1e4 * (e.getValue() - pv) / pv);
            }
            pk = e.getKey();
            pv = e.getValue();
        }
        if (d.size() < 10) {
            return 0;
        }
        double m = d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(d.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / d.size());
    }

    /**
     * ВЕС ПЕРЕКОСА: сколько микроцены добавлять к середине.
     *
     * Полная микроцена Stoikov сдвигает опору на полуспред, умноженный на
     * перекос: у BTC это до ±6.9 б.п. при том, что сама середина за минуту
     * проходит 1.3. Неудивительно, что в полную величину она проигрывает простой
     * середине — она добавляет больше шума, чем сигнала.
     *
     * Но перекос отбор ПРЕДСКАЗЫВАЕТ (разрезы кривой: у BTC 11.22 против 2.72
     * между крайними квартилями). Значит вопрос не «брать или не брать», а «с
     * каким весом», и вес подбирается по тому же критерию — ошибке прогноза
     * середины через 60 с.
     *
     * ⚠️ Печатаются ДВЕ ошибки. Сырая содержит систематический сдвиг опоры,
     * который чинится вычитанием константы и потому дёшев. Ошибка без сдвига —
     * это шум, и он не чинится ничем. Смешивать их нельзя: опора со сдвигом
     * 4 б.п. и нулевым шумом лучше опоры без сдвига и с шумом 4 б.п., хотя сырая
     * мерка у них одинаковая.
     */
    private static String betaSweep(TreeMap<Long, Top> book) {
        double[] betas = {0, 0.05, 0.10, 0.20, 0.35, 0.50, 1.0};
        StringBuilder sb = new StringBuilder(
                "\n  вес перекоса β: середина + β·(микроцена − середина), прогноз через 60 с\n");
        sb.append("     β | ошибка, б.п. | она же без систематического сдвига\n");
        long last = book.lastKey();
        for (double b : betas) {
            double sum = 0;
            double sumSigned = 0;
            int n = 0;
            List<Double> signed = new ArrayList<>();
            for (Map.Entry<Long, Top> e : book.entrySet()) {
                if (e.getKey() + 60_000 > last) {
                    break;
                }
                Map.Entry<Long, Top> fut = book.floorEntry(e.getKey() + 60_000);
                if (fut == null) {
                    continue;
                }
                Top t = e.getValue();
                double a = t.mid() + b * (t.micro() - t.mid());
                double e60 = 1e4 * (a - fut.getValue().mid()) / fut.getValue().mid();
                sum += Math.abs(e60);
                sumSigned += e60;
                signed.add(e60);
                n++;
            }
            if (n == 0) {
                continue;
            }
            double bias = sumSigned / n;
            double deb = 0;
            for (double v : signed) {
                deb += Math.abs(v - bias);
            }
            sb.append(String.format(Locale.ROOT, "  %5.2f | %12.3f | %34.3f%n",
                    b, sum / n, deb / n));
        }
        return sb.toString();
    }

    /** Лучший уровень книги: бид, аск и объёмы на них. */
    record Top(double bid, double ask, double bq, double aq, String deepBids, String deepAsks) {

        Top(double bid, double ask, double bq, double aq) {
            this(bid, ask, bq, aq, null, null);
        }

        /**
         * Сколько СТОИТ В КНИГЕ на расстоянии δ от середины, в базовой валюте.
         *
         * Это и есть очередь впереди нас: заявка, поставленная на уровень, где
         * уже лежит чужой объём, исполнится только после него. Чем ближе к
         * середине, тем очередь толще — поэтому оценка «до нас доходит каждое
         * событие» вреднее всего именно у тача.
         */
        double queueAt(double distBp, boolean bidSide) {
            double m = mid();
            if (!(m > 0)) {
                return 0;
            }
            double want = bidSide ? m * (1 - distBp / 1e4) : m * (1 + distBp / 1e4);
            double sum = bidSide ? (bid >= want ? bq : 0) : (ask <= want ? aq : 0);
            String deep = bidSide ? deepBids : deepAsks;
            if (deep == null || deep.isEmpty()) {
                return sum;
            }
            for (String s : deep.split(",")) {
                int i = s.indexOf(58);
                if (i <= 0) {
                    continue;
                }
                try {
                    double p = Double.parseDouble(s.substring(0, i));
                    double q = Double.parseDouble(s.substring(i + 1));
                    if (bidSide ? p >= want : p <= want) {
                        sum += q;
                    }
                } catch (NumberFormatException ignored) {
                    // мусорная запись уровня; пропускаем, а не роняем разбор
                }
            }
            return sum;
        }

        double mid() {
            return (bid + ask) / 2;
        }

        /**
         * ГЛУБОКАЯ СЕРЕДИНА: полусумма цен, на которых с каждой стороны
         * набирается {@code usd} денег (док. 154 §IV).
         *
         * <h2>Зачем она</h2>
         *
         * Опыт с заморозкой опоры (FROZEN) проверял «невосприимчивость к
         * собственному потоку» вместе с несвежестью и проиграл на второй.
         * Глубокая середина даёт первое без второго: она пересчитывается каждый
         * тик, но мелкий свип, съедающий лучший уровень, её почти не двигает —
         * объём {@code usd} набирается на тех же уровнях, что и до свипа.
         *
         * ⚠️ Возвращает {@code NaN}, если денег в книге меньше {@code usd}: это
         * не ноль и не середина, и подменять его тихо нельзя — на тонкой книге
         * такая опора просто не определена.
         */
        double deepMid(double usd) {
            double b = deepSide(usd, true);
            double a = deepSide(usd, false);
            return b > 0 && a > 0 ? (b + a) / 2 : Double.NaN;
        }

        /**
         * ГЛУБОКАЯ СЕРЕДИНА, СГЛАЖЕННАЯ: полусумма средневзвешенных цен, по
         * которым с каждой стороны исполнится {@code usd} денег.
         *
         * ⚠️ Разница с {@link #deepMid} не косметическая. Та возвращает цену
         * УРОВНЯ, то есть ступеньку: опора прыгает на целый тик, когда объёма на
         * уровне перестаёт хватать, и добавляет шум самим способом счёта.
         * Средневзвешенная цена меняется непрерывно и потому честнее проверяет
         * мысль 154 §IV: «опора, которой мелкий свип безразличен».
         */
        double deepVwapMid(double usd) {
            double b = vwapSide(usd, true);
            double a = vwapSide(usd, false);
            return b > 0 && a > 0 ? (b + a) / 2 : Double.NaN;
        }

        private double vwapSide(double usd, boolean bidSide) {
            double best = bidSide ? bid : ask;
            double money = 0;
            double qty = 0;
            double q0 = bidSide ? bq : aq;
            double take = Math.min(q0, usd / best);
            money += take * best;
            qty += take;
            if (money >= usd - 1e-9) {
                return money / qty;
            }
            for (double[] l : levels(bidSide, best)) {
                double need = (usd - money) / l[0];
                double t = Math.min(l[1], need);
                money += t * l[0];
                qty += t;
                if (money >= usd - 1e-9) {
                    return money / qty;
                }
            }
            return 0;                      // денег в книге меньше, чем просят
        }

        /** Уровни глубины этой стороны, отсортированные от рынка. */
        private List<double[]> levels(boolean bidSide, double best) {
            List<double[]> levels = new ArrayList<>();
            String deep = bidSide ? deepBids : deepAsks;
            if (deep == null || deep.isEmpty()) {
                return levels;
            }
            for (String s : deep.split(",")) {
                int i = s.indexOf(58);
                if (i <= 0) {
                    continue;
                }
                try {
                    double p = Double.parseDouble(s.substring(0, i));
                    double q = Double.parseDouble(s.substring(i + 1));
                    if (p > 0 && q > 0 && (bidSide ? p < best : p > best)) {
                        levels.add(new double[]{p, q});
                    }
                } catch (NumberFormatException ignored) {
                    // мусорная запись уровня; пропускаем, а не роняем разбор
                }
            }
            levels.sort((x, y) -> bidSide ? Double.compare(y[0], x[0]) : Double.compare(x[0], y[0]));
            return levels;
        }

        /** Цена, на которой с этой стороны накопится {@code usd} денег. */
        private double deepSide(double usd, boolean bidSide) {
            double best = bidSide ? bid : ask;
            double sum = (bidSide ? bq : aq) * best;
            if (sum >= usd) {
                return best;
            }
            // ⚠️ Уровни СОРТИРУЕМ САМИ. Площадка отдаёт аски в убывающем
            // порядке (проверено на 324 снимках из 324), и наивный проход по
            // строке начинал бы с худшего уровня — глубокая середина тогда
            // считалась бы по краю книги, а не по её началу.
            for (double[] l : levels(bidSide, best)) {
                sum += l[0] * l[1];
                if (sum >= usd) {
                    return l[0];
                }
            }
            return 0;                      // денег в книге меньше, чем просят
        }

        /**
         * Микроцена: середина, взвешенная ОБРАТНО объёмам.
         *
         * Мысль Stoikov в одну строку: если на биде стоит втрое больше, чем на
         * аске, то аск сметут раньше, и «настоящая» цена ближе к аску, а не
         * посередине. Вес именно перекрёстный — {@code bid·aq + ask·bq}, не
         * наоборот; перепутанный знак превращает лучший известный предиктор в
         * худший, поэтому он закреплён тестом.
         */
        double micro() {
            double s = bq + aq;
            return s <= 0 ? mid() : (bid * aq + ask * bq) / s;
        }

        double imbalance() {
            double s = bq + aq;
            return s <= 0 ? Double.NaN : bq / s;
        }
    }

    /**
     * Опора, от которой меряется и расстояние принта, и markout.
     *
     * ⚠️ Опора — не деталь отчёта, а предмет измерения. Она решает ДВЕ вещи
     * сразу: где стоит наша заявка (мы котируем от неё) и как мы считаем, куда
     * ушла цена. Смещённая опора искажает расстояние АСИММЕТРИЧНО — покупки
     * кажутся дальше, продажи ближе, — и это видно по доле покупок на дальних
     * ступенях. На окне 29–31.08.2026 у BTC она доходила до 82% при нетто-ходе
     * рынка −0.22%, чего направление объяснить не может.
     */
    enum Ref {
        /** Межплощадочная справедливая цена — та, от которой котирует бот. */
        FAIR,
        /** Середина собственной книги пары. */
        MID,
        /** Микроцена: середина, взвешенная перекосом объёмов. */
        MICRO,
        /** Глубокая середина: цены, на которых набирается заданный объём денег. */
        DEEP
    }

    /**
     * Лучший уровень книги по ноге USDC: котируем мы в ней, значит и опора
     * должна считаться по ней.
     */
    private static TreeMap<Long, Top> book(String dbPath, String symbol, long from, long to) {
        TreeMap<Long, Top> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT t_recv_ms, bp1, ap1, bq1, aq1, " + deepCols(c)
                             + " FROM revx_book WHERE symbol = '"
                             + symbol + "' AND t_recv_ms >= " + from + " AND t_recv_ms <= " + to
                             + " AND bp1 > 0 AND ap1 > 0 AND bq1 > 0 AND aq1 > 0"
                             + " ORDER BY t_recv_ms")) {
            while (rs.next()) {
                out.put(rs.getLong(1), new Top(rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getString(6), rs.getString(7)));
            }
        } catch (Exception e) {
            log.warn("книга {}: {}", symbol, e.toString());
        }
        return out;
    }

    private static double q(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
