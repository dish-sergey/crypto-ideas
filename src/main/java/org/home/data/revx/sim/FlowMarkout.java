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

        sb.append(payPerRisk(prints, days, fair, halfSpread(book)));
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
                                     TreeMap<Long, Double> fair, double halfSpreadBp) {
        double sigma = volBpPerMin(fair);
        if (!(sigma > 0)) {
            return "\nотношение по ленте: волатильности не хватило данных\n";
        }
        StringBuilder sb = new StringBuilder(
                "\nПЛАТЯТ ЛИ ЗА РИСК ПО ЛЕНТЕ (σ = " + String.format(Locale.ROOT, "%.2f", sigma)
                        + " б.п./мин)\n");
        sb.append("  δ,б.п. | бид/сут | аск/сут | ожидание круга | риск σ√T | захват | ЗАХВ/РИСК\n");
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
            double risk = sigma * Math.sqrt(tMin);
            double cap = 2 * dist;
            sb.append(String.format(Locale.ROOT,
                    "  %6.0f | %7.1f | %7.1f | %11.0f мин | %8.1f | %6.1f | %9.2f%s%n",
                    dist, lamBid, lamAsk, tMin, risk, cap, cap / risk,
                    cap / risk < 1 ? "  ⚠️" : ""));
        }
        sb.append("⚠️ ожидание круга = 1/λ_бид + 1/λ_аск: нужны обе ноги.\n");
        sb.append("Это ВЕРХНЯЯ оценка — предполагает, что до нас доходит каждое событие.\n");
        sb.append(skewSweep(prints, days, sigma));
        sb.append(askSchedule(prints, days, sigma, halfSpreadBp));
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
                                      double halfSpreadBp) {
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
        sb.append(ladder(lam, tBid, dBid, sigma, prints, days));
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
                                 double sigma, List<Print> prints, double days) {
        record Rung(String name, double[] levels) {
        }
        List<Rung> rungs = List.of(
                new Rung("1 уровень: 8", new double[]{8}),
                new Rung("1 уровень: 10", new double[]{10}),
                new Rung("2 уровня: 6, 10", new double[]{6, 10}),
                new Rung("3 уровня: 6, 8, 10", new double[]{6, 8, 10}),
                new Rung("3 уровня: 4, 8, 12", new double[]{4, 8, 12}),
                new Rung("3 уровня: 6, 10, 14", new double[]{6, 10, 14}),
                new Rung("5 уровней: 4..12 шагом 2", new double[]{4, 6, 8, 10, 12}));
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
        sb.append(sweepThroughput(prints, days));
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
    private static String sweepThroughput(List<Print> prints, double days) {
        record Rung(String name, double[] levels) {
        }
        List<Rung> rungs = List.of(
                new Rung("1 уровень: 8", new double[]{8}),
                new Rung("1 уровень: 10", new double[]{10}),
                new Rung("2 уровня: 6, 10", new double[]{6, 10}),
                new Rung("3 уровня: 6, 8, 10", new double[]{6, 8, 10}),
                new Rung("3 уровня: 6, 10, 14", new double[]{6, 10, 14}),
                new Rung("5 уровней: 4..12 шагом 2", new double[]{4, 6, 8, 10, 12}));
        // События по стороне аска (покупатель бьёт наш аск), схлопнутые в пачки.
        List<Print> ev = events(prints, 2).stream().filter(p -> p.aggressor() > 0).toList();
        if (ev.size() < 20) {
            return "\n  пропускная способность: событий мало\n";
        }
        StringBuilder sb = new StringBuilder(
                "\n  ПРОПУСКНАЯ СПОСОБНОСТЬ НА СВИПАХ (событие глубиной d берёт все уровни ≤ d)\n");
        sb.append("  раскладка                   | лотов/сут | захват/сут, б.п. | лотов на событие\n");
        for (Rung r : rungs) {
            double lots = 0;
            double cap = 0;
            for (Print p : ev) {
                for (double d : r.levels()) {
                    if (p.distBp() >= d) {
                        lots++;
                        cap += d;
                    }
                }
            }
            if (lots == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT,
                    "  %-27s | %9.1f | %16.0f | %16.2f%n",
                    r.name(), lots / days, cap / days, lots / ev.size()));
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
        return ref == Ref.MID ? b.getValue().mid() : b.getValue().micro();
    }

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
        sb.append("  опора | принтов выше опоры | ошибка прогноза середины через 60 с, б.п.\n");
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
            // Ошибка прогноза: |опора(t) − середина(t+60с)|, по снимкам книги.
            double err = 0;
            int m = 0;
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
                err += Math.abs(1e4 * (a - fut.getValue().mid()) / fut.getValue().mid());
                m++;
            }
            sb.append(String.format(Locale.ROOT, "  %-5s | %17.1f%% | %38.2f%n",
                    ref, n == 0 ? 0 : 100.0 * above / n, m == 0 ? 0 : err / m));
        }
        sb.append(betaSweep(book));
        return sb.toString();
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
    record Top(double bid, double ask, double bq, double aq) {

        double mid() {
            return (bid + ask) / 2;
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
        MICRO
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
                     "SELECT t_recv_ms, bp1, ap1, bq1, aq1 FROM revx_book WHERE symbol = '"
                             + symbol + "' AND t_recv_ms >= " + from + " AND t_recv_ms <= " + to
                             + " AND bp1 > 0 AND ap1 > 0 AND bq1 > 0 AND aq1 > 0"
                             + " ORDER BY t_recv_ms")) {
            while (rs.next()) {
                out.put(rs.getLong(1), new Top(rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5)));
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
