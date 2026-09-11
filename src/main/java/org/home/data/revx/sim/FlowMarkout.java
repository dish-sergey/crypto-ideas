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

        TreeMap<Long, Double> imb = imbalance(standDbPath, base + "/USDC", from, to);
        MarketData md = MarketData.load(standDbPath, base + "/USDC", from, to);

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
            Map.Entry<Long, Double> f = fair.floorEntry(t.tsMs());
            if (f == null || f.getValue() <= 0) {
                continue;
            }
            int agg = t.aggressor() == Side.BUY ? 1 : -1;
            double dist = 1e4 * agg * (t.price() - f.getValue()) / f.getValue();
            Map.Entry<Long, Double> im = imb.floorEntry(t.tsMs());
            prints.add(new Print(t.tsMs(), t.price(), t.qty(), agg, dist, f.getValue(),
                    prevTs == 0 ? -1 : t.tsMs() - prevTs,
                    burstSize.getOrDefault(t.tsMs(), 1),
                    im == null ? Double.NaN : im.getValue()));
            prevTs = t.tsMs();
        }
        if (prints.size() < 50) {
            return "\n" + base + ": принтов со стороной всего " + prints.size() + "\n";
        }

        StringBuilder sb = new StringBuilder("\n--- " + base + " ---\n");
        sb.append(String.format(Locale.ROOT,
                "принтов со стороной %d (%.0f в сутки), тиков справедливой цены %d%n",
                prints.size(), prints.size() / days, fair.size()));

        // Распределение расстояний — чтобы видеть, где вообще есть поток.
        List<Double> d = prints.stream().map(Print::distBp).sorted().toList();
        sb.append(String.format(Locale.ROOT,
                "расстояние от справедливой цены, б.п.: медиана %.2f (10%% %.2f, 90%% %.2f)%n",
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

    /**
     * Перекос лучшего уровня книги — кандидат номер один в предикторы
     * (микроцена Stoikov). Берётся по ноге USDC: котируем мы в ней.
     */
    private static TreeMap<Long, Double> imbalance(String dbPath, String symbol,
                                                   long from, long to) {
        TreeMap<Long, Double> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT t_recv_ms, bq1, aq1 FROM revx_book WHERE symbol = '" + symbol
                             + "' AND t_recv_ms >= " + from + " AND t_recv_ms <= " + to
                             + " AND bq1 > 0 AND aq1 > 0 ORDER BY t_recv_ms")) {
            while (rs.next()) {
                double bq = rs.getDouble(2);
                double aq = rs.getDouble(3);
                out.put(rs.getLong(1), bq / (bq + aq));
            }
        } catch (Exception e) {
            log.warn("перекос книги {}: {}", symbol, e.toString());
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
