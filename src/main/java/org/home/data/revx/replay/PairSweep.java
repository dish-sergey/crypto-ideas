package org.home.data.revx.replay;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.exec.StandReader;
import org.home.data.revx.sim.FairPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Обход ВСЕЙ вселенной: каждая пара, каждые сутки записи, лестница отступов.
 *
 * <h2>Зачем</h2>
 *
 * Живые боты стоят на двух парах из двадцати трёх, и выбор этих двух опирался
 * на то, где бот УЖЕ работал, — то есть на историю, а не на измерение. Суточный
 * лимит в тысячу постановок общий на счёт, и тратить его надо там, где он
 * приносит больше. Ответ на «где» и считает этот обход.
 *
 * <h2>Что здесь считается честно, а что нет</h2>
 *
 * ⚠️ <b>Разрешение записи у пар РАЗНОЕ.</b> BTC, ETH и SOL опрашиваются раз в
 * секунду (86 тыс. снимков в сутки), остальные двадцать — раз в шесть (14 тыс.).
 * Для медленных пар бот в прогоне переставляет заявку реже, чем смог бы вживую,
 * и дольше стоит с устаревшей ценой. Это смещение в МИНУС: числа по ним —
 * нижняя оценка, а не верхняя.
 *
 * ⚠️ <b>Инвентарь обнуляется на границе суток.</b> Каждый день считается
 * отдельным прогоном, иначе один срез книг не поместился бы в память. Поэтому
 * позиция, унесённая через полночь, здесь не переносится, а закрывается по
 * последней цене дня.
 *
 * ⚠️ <b>Отступы гоняются ПОРОЗНЬ.</b> Соблазн посадить всю лестницу в один
 * прогон велик и меняет ответ: боты в {@link Forecast} делят одну книгу, и
 * очередь делит между ними один и тот же объём. Тогда каждая ступень видит
 * меньше потока, чем увидела бы одна, и лестница выходит заниженной тем
 * сильнее, чем она длиннее.
 *
 * <h2>Вилка вместо числа</h2>
 *
 * Как и везде в прогнозе, считаются две модели исполнения: очередь (рабочая,
 * нижняя) и касание (верхняя граница). Прогноз без вилки не отдаётся.
 */
public final class PairSweep {

    private static final Logger log = LoggerFactory.getLogger(PairSweep.class);

    private static final long DAY_MS = 86_400_000L;

    /** Меньше этого числа снимков за сутки — сбор стоял, сутки не в счёт. */
    private static final int MIN_SNAPSHOTS = 2_000;

    /**
     * Ступень перебора: отступ И размер лота.
     *
     * Лот здесь не для полноты. Глубина у пар различается в шестьдесят раз
     * (BTC $1.28 млн в сутки против PEPE $21 тыс.), а доход на постановку
     * растёт с размером заявки — значит «лучшая пара» на лоте $1 и на лоте $25
     * это, вообще говоря, разные пары.
     */
    private record Variant(double offBp, double lotUsd) implements Comparable<Variant> {
        @Override
        public int compareTo(Variant o) {
            int c = Double.compare(lotUsd, o.lotUsd);
            return c != 0 ? c : Double.compare(offBp, o.offBp);
        }

        String label() {
            return lotUsd == 1 ? String.format(Locale.ROOT, "%.0f б.п.", offBp)
                    : String.format(Locale.ROOT, "$%.0f/%.0f", lotUsd, offBp);
        }
    }

    /** Накопитель по одной паре и одной ступени. */
    private static final class Cell {
        /**
         * Сделок на самой площадке за то же окно — знаменатель доли участия.
         *
         * ⚠️ Без него прогноз на тонкой паре нечем проверить на здравый смысл.
         * У PEPE на площадке 34 сделки в сутки, и модель очереди легко выдаёт
         * столько же исполнений: получается маркет-мейкер, забирающий всю
         * ленту. Такого не бывает, и колонка это показывает сразу.
         */
        int marketTrades;
        double lotUsd;
        double realisedMarket;
        double realisedTouch;
        int fills;
        int buys;
        int sells;
        long placements;
        double days;
        double lot;
        /** Потолок инвентаря в базовой валюте — знаменатель годовых. */
        double cap;
        double inventoryLots;
        int daysHeld;
        double price;
        final List<Forecast.Day> byDay = new ArrayList<>();
    }

    private PairSweep() {
    }

    public static void run(String standDbPath, RevxConfig cfg, String fromIso, String toIso,
                           int levels, double levelStepBp, boolean innerFirst,
                           double[] offsetsBp) {
        run(standDbPath, cfg, fromIso, toIso, levels, levelStepBp, innerFirst, offsetsBp,
                null, new double[]{1}, 1, 0, 0);
    }

    /**
     * @param only только эти пары ({@code null} — вся вселенная). Нужно для
     *             ДОСЧЁТА: первый обход упёрся ступенью оптимума в край
     *             лестницы у восемнадцати пар из двадцати трёх, то есть показал
     *             не оптимум, а границу перебора. Гонять ради этого всю
     *             вселенную заново — восемь часов на одном ядре, которое делят
     *             живые боты.
     * @param lotsUsd размеры лота в долларах. Потолок инвентаря всегда двадцать
     *             лотов, то есть с лотом растёт и вложенный капитал — поэтому
     *             сравнивать ступени надо по ГОДОВЫМ на капитал, а не по доходу
     *             за окно: доход за окно вырастет и от того, что денег стало
     *             больше.
     */
    public static void run(String standDbPath, RevxConfig cfg, String fromIso, String toIso,
                           int levels, double levelStepBp, boolean innerFirst,
                           double[] offsetsBp, java.util.Set<String> only, double[] lotsUsd,
                           int thin, double dynK, double capUsd) {
        long from = java.time.Instant.parse(fromIso).toEpochMilli();
        long to = java.time.Instant.parse(toIso).toEpochMilli();
        // Подмена курса — только для опыта, см. FairPrice. Ноль = считать медианой.
        double fixedRate = Double.parseDouble(
                System.getProperty("revx.fair.fixed-rate", "0"));
        if (fixedRate > 0) {
            log.warn("⚠️ ОПЫТ: курс USDC/USD подменён на {} — гейты считаются "
                    + "по-прежнему, подменён только делитель цены", fixedRate);
        }
        FairPrice.Limits limits = new FairPrice.Limits(cfg.fairMinPairs(),
                cfg.fairMaxDispersionPct(), cfg.fairMaxReferenceSpreadPct(),
                cfg.fairMaxResidualPct(), fixedRate);

        // пара → отступ → накопитель
        Map<String, Map<Variant, Cell>> grid = new TreeMap<>();
        Map<String, StandReader.PairSpec> specs = new LinkedHashMap<>();
        Map<String, Integer> skipped = new TreeMap<>();

        // ⚠️ ПАРАЛЛЕЛЬНО ПО СУТКАМ, а не по парам. Дни независимы по построению:
        // инвентарь и так обнуляется на границе суток, потому что один срез книг
        // всей вселенной в память не помещается. Пары же внутри дня делят один
        // срез справедливой цены, и растаскивать их по потокам значило бы читать
        // книги по разу на пару.
        //
        // Потоков не больше числа ядер и не больше числа суток: каждый держит
        // свой срез книг (~200 МБ на сутки), и лишние потоки покупают память без
        // выигрыша.
        // ⚠️ ОКНО КОРОЧЕ СУТОК РАНЬШЕ ДАВАЛО МОЛЧА НОЛЬ. Деление было
        // целочисленным, и на окне в 11 часов получалось «обход: 0 суток» —
        // отчёт печатал пустую таблицу, неотличимую от «настройка не торгует».
        // Поймано 09.09.2026 на проверке модели вне выборки.
        //
        // Последний кусок теперь считается неполными сутками и обрезается по
        // {@code to}. Инвентарь на границе суток обнуляется по-прежнему — это
        // свойство конструкции, а не следствие деления.
        int days = (int) Math.max(1, Math.ceil((to - from) / (double) DAY_MS));
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), days));
        double hours = (to - from) / 3_600_000.0;
        log.warn("обход: {} суток ({} ч), {} потоков",
                days, Math.round(hours * 10) / 10.0, threads);
        if (hours < 24) {
            log.warn("⚠️ окно короче суток — результат по одному неполному дню");
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try (StandReader stand = new StandReader(standDbPath, cfg.memecoins(), limits,
                cfg.fairMaxSkewMs())) {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int d = 0; d < days; d++) {
                final long day = from + (long) d * DAY_MS;
                // Последние сутки обрезаются по концу окна: иначе на неполном
                // куске читались бы книги за его пределами.
                final long dayEnd = Math.min(day + DAY_MS - 1, to);
                final int dayNo = d + 1;
                tasks.add(pool.submit(() -> {
                    String label = java.time.Instant.ofEpochMilli(day).toString().substring(0, 10);
                    var fair = new StandFair(standDbPath, null, limits, cfg.memecoins(),
                            cfg.fairMaxSkewMs(), org.home.data.revx.exec.Clock.system(),
                            day, dayEnd);
                    if (fair.pairs() == 0) {
                        log.warn("{}: книг нет, сутки пропущены", label);
                        return;
                    }
                    log.warn("=== сутки {} ({} из {}), пар в срезе {} ===",
                            label, dayNo, days, fair.pairs());
                    for (String base : new ArrayList<>(fair.bases())) {
                        if (only != null && !only.contains(base)) {
                            continue;
                        }
                        String symbol = base + "/USDC";
                        if (fair.snapshots(base) < MIN_SNAPSHOTS) {
                            synchronized (skipped) {
                                skipped.merge(base, 1, Integer::sum);
                            }
                            continue;
                        }
                        StandReader.PairSpec ps;
                        synchronized (specs) {
                            ps = specs.computeIfAbsent(symbol, stand::spec);
                        }
                        if (ps == null) {
                            continue;             // пара не торгуется — считать нечего
                        }
                        try {
                            oneDay(standDbPath, cfg, fair, base, symbol, ps, label, day,
                                    levels, levelStepBp, innerFirst, offsetsBp, lotsUsd, thin, dynK,
                                    capUsd, grid);
                        } catch (Exception e) {
                            log.warn("{} {}: прогон не прошёл — {}", label, symbol, e.toString());
                        }
                    }
                }));
            }
            for (var t : tasks) {
                t.get();
            }
        } catch (Exception e) {
            log.error("обход вселенной не прошёл: {}", e.toString(), e);
            return;
        } finally {
            pool.shutdown();
        }

        log.info("\n{}", render(grid, levels, levelStepBp, innerFirst, skipped));
    }

    private static void oneDay(String standDbPath, RevxConfig cfg, StandFair fair,
                               String base, String symbol, StandReader.PairSpec ps,
                               String label, long dayStart, int levels, double levelStepBp,
                               boolean innerFirst, double[] offsetsBp, double[] lotsUsd, int thin,
                               double dynK, double capUsd,
                               Map<String, Map<Variant, Cell>> grid) throws Exception {
        List<ReplayFair.Tick> ticks = fair.toTicks(base);
        // ⚠️ ПРОРЕЖИВАНИЕ. Оставляем каждый N-й тик, чтобы измерить цену
        // редкого опроса: у BTC, ETH и SOL запись секундная, у остальных
        // двадцати — раз в шесть секунд, и весь обход сравнивал пары, часть
        // которых видит рынок вшестеро реже. Здесь тот же рынок и та же лента
        // сделок — реже только МОМЕНТЫ, когда бот может переставить заявку.
        // Это и есть разница между «опрашиваем раз в секунду» и «раз в шесть».
        if (thin > 1) {
            List<ReplayFair.Tick> kept = new ArrayList<>(ticks.size() / thin + 1);
            for (int i = 0; i < ticks.size(); i += thin) {
                kept.add(ticks.get(i));
            }
            ticks = kept;
        }
        // ⚠️ Молчаливых отказов здесь быть не должно: пустая строка в отчёте
        // читается как «настройка не торгует», а не как «мы это не считали».
        if (ticks.size() < MIN_SNAPSHOTS / Math.max(1, thin)) {
            log.warn("{} {}: тиков {} при пороге {} — сутки пропущены",
                    label, symbol, ticks.size(), MIN_SNAPSHOTS / Math.max(1, thin));
            return;
        }
        // Середина окна как опорная цена: тик посередине может оказаться без
        // справедливой цены (гейты), и тогда берётся первый, у которого она есть.
        double price = ticks.get(ticks.size() / 2).fair();
        if (!(price > 0)) {
            for (ReplayFair.Tick t : ticks) {
                if (Double.isFinite(t.fair()) && t.fair() > 0) {
                    price = t.fair();
                    break;
                }
            }
        }
        if (!(price > 0)) {
            log.warn("{} {}: ни одного тика со справедливой ценой — сутки пропущены",
                    label, symbol);
            return;
        }
        MarketData market0 = MarketData.load(standDbPath, symbol,
                ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs());

        for (double lotUsd : lotsUsd) {
        // Лот заданного размера, округлённый к шагу количества. У части пар шаг
        // грубый, и доллар в него не укладывается — тогда лот выходит больше,
        // и это видно в отчёте отдельной колонкой.
        double lot = ps.baseStep() > 0
                ? Math.max(ps.baseStep(),
                        Math.round(lotUsd / price / ps.baseStep()) * ps.baseStep())
                : lotUsd / price;
        // ⚠️ Потолок инвентаря по умолчанию идёт ЗА лотом (двадцать лотов), и это
        // верно, когда меряют ёмкость: с лотом растёт и вложенный капитал. Но при
        // сравнении ФОРМЫ сетки — один уровень по $3 против трёх по $1 — тот же
        // порядок дал бы одноуровневой втрое больший потолок, и сравнивалась бы не
        // форма, а размер позиции. --cap-usd держит потолок постоянным.
        double cap = capUsd > 0
                ? (ps.baseStep() > 0
                        ? Math.max(ps.baseStep(),
                                Math.round(capUsd / price / ps.baseStep()) * ps.baseStep())
                        : capUsd / price)
                : lot * 20;

        var bp = new BootParams(symbol, "a", lot, cap, offsetsBp[0] / 10_000,
                cfg.simSkewK(), 0.3, 1000, ps.minNotional(), ps.baseStep(),
                ps.quoteStep(), 0.10, -1, -1, 0, 0.02, 0.5, true,
                levels, levelStepBp / 10_000, innerFirst);

        for (double offBp : offsetsBp) {
            var spec = new Forecast.BotSpec("a", offBp / 10_000, 0.3, cap,
                    levels, levelStepBp / 10_000, lot, innerFirst, dynK);
            List<Forecast.BotSpec> one = List.of(spec);

            var queue = Forecast.run(ticks, new MarketFillModel(market0.fresh()), bp, one, cfg);
            var touch = Forecast.run(ticks, new TouchFillModel(market0.fresh()), bp, one, cfg);
            if (queue.isEmpty() || touch.isEmpty()) {
                continue;
            }
            Forecast.BotResult q = queue.get(0);
            Forecast.BotResult t = touch.get(0);

            // ⚠️ Накопитель ОБЩИЙ на все потоки суток, а TreeMap не потокобезопасен.
            // Без замка здесь обход тихо терял бы дни: конкурентная вставка в
            // TreeMap не падает, а портит дерево, и часть суток просто исчезала бы
            // из итога — ровно тот класс ошибки, который в отчёте не виден.
            synchronized (grid) {
            Cell cell = grid.computeIfAbsent(base, k -> new TreeMap<>())
                    .computeIfAbsent(new Variant(offBp, lotUsd), k -> new Cell());
            cell.marketTrades += market0.tradeCount();
            cell.lotUsd = lotUsd;
            cell.realisedMarket += q.realised();
            cell.realisedTouch += t.realised();
            cell.fills += q.fills();
            cell.buys += q.buys();
            cell.sells += q.sells();
            cell.placements += q.placements();
            cell.days += q.days();
            cell.inventoryLots += q.inventoryLots();
            cell.daysHeld++;
            cell.lot = lot;
            cell.cap = cap;
            cell.price = price;
            double move = q.days_() == null || q.days_().isEmpty() ? 0
                    : q.days_().get(0).movePct();
            cell.byDay.add(new Forecast.Day(label, move, q.realised(), q.fills()));
            }
        }
        }
    }

    /**
     * КОНЦЕНТРАЦИЯ: сколько суток из скольких делают доход, по каждой ступени.
     *
     * <h2>Зачем это отдельно от среднего</h2>
     *
     * «В сутки +0.05» одинаково описывает две разные конструкции: ту, что берёт
     * по копейке каждый день, и ту, что тринадцать дней стоит в нуле, а на
     * четырнадцатый ловит движение. Их нельзя ни сравнивать по среднему, ни
     * одинаково масштабировать: у второй ожидание держится на хвосте, а хвост —
     * это то, чего в выборке из тринадцати суток почти нет.
     *
     * <h2>Меры и почему именно они</h2>
     *
     * ⚠️ Джини и доли «топ-N от суммы» на знакопеременном ряде врут: при сумме
     * около нуля доля одного дня уходит в сотни процентов, а при отрицательной
     * меняет знак. Поэтому здесь три меры, каждая устойчива по-своему:
     *
     * <ul>
     *   <li><b>приб.</b> — доля прибыльных суток. Прямая и без знаменателя;</li>
     *   <li><b>50% за</b> — сколько ЛУЧШИХ суток набирают половину суммы всех
     *       ПОЛОЖИТЕЛЬНЫХ суток. Знаменатель положителен по построению, поэтому
     *       мера не взрывается. 1 из 13 — доход на одном дне, 6 из 13 — ровный;</li>
     *   <li><b>медиана/среднее</b> — отношение медианных суток к средним.
     *       Единица — ровный ряд, ноль и ниже — среднее держится на выбросах.</li>
     * </ul>
     *
     * ⚠️ <b>СМЕЩЕНИЕ ЗДЕСЬ РАБОТАЕТ ЗА ГИПОТЕЗУ, и это надо помнить при чтении.</b>
     * Инвентарь, унесённый за полночь, в суточный доход не попадает: сутки
     * считаются отдельным прогоном, и незакрытая партия остаётся незакрытой.
     * Чем шире отступ, тем чаще круг не замыкается внутри суток — а не
     * замыкается он в первую очередь на ТИХИХ днях. Значит тихий день у широкой
     * ступени показывает ноль там, где на непрерывном счёте была бы половинка
     * круга, и концентрация выходит завышенной. Настоящая проверка гипотезы —
     * не сама величина, а разница между концентрацией дохода и концентрацией
     * СДЕЛОК: она этим смещением почти не задета.
     */
    private static String concentration(Map<String, Map<Variant, Cell>> grid) {
        StringBuilder sb = new StringBuilder(
                "\n\n=== КОНЦЕНТРАЦИЯ: сколько суток делают доход ===\n\n");
        sb.append("пара     | ступень |  сут | приб. |    доход | лучш.сут | 50% за"
                + " | мед/сред | сделок 50% за | сдел/сут\n");
        sb.append("---------+---------+------+-------+----------+----------+-------"
                + "-+----------+---------------+---------\n");
        for (var pair : grid.entrySet()) {
            for (var e : pair.getValue().entrySet()) {
                Cell c = e.getValue();
                List<Double> days = c.byDay.stream().map(Forecast.Day::realised)
                        .sorted(Comparator.reverseOrder()).toList();
                if (days.isEmpty()) {
                    continue;
                }
                double total = days.stream().mapToDouble(Double::doubleValue).sum();
                double positive = days.stream().filter(v -> v > 0)
                        .mapToDouble(Double::doubleValue).sum();
                long profitable = days.stream().filter(v -> v > 0).count();
                double bestShare = positive > 0 ? days.get(0) / positive * 100 : 0;
                double median = days.get(days.size() / 2);
                double mean = total / days.size();
                // ⚠️ ТА ЖЕ МЕРА ПО ЧИСЛУ СДЕЛОК — без неё вывод не разложить.
                //
                // Широкий отступ ловит меньше сделок, а редкий поток лумпится САМ
                // СОБОЙ: при двух сделках в сутки пустые дни неизбежны по одной
                // арифметике счёта, безо всякой «ставки на редкий день». Сравнение
                // концентрации дохода с концентрацией СДЕЛОК отделяет одно от
                // другого: если доход собран плотнее сделок, значит и цена сделки
                // в те дни была выше.
                List<Double> fills = c.byDay.stream().map(d -> (double) d.fills())
                        .sorted(Comparator.reverseOrder()).toList();
                sb.append(String.format(Locale.ROOT,
                        "%-8s | %7s | %4d | %2d/%-2d | %+8.4f | %7.0f%% | %2d/%-3d | %8s"
                                + " | %10d/%-2d | %8.1f%n",
                        pair.getKey(), e.getKey().label(), days.size(),
                        profitable, days.size(), total, bestShare,
                        halfOf(days), days.size(),
                        Math.abs(mean) > 1e-9
                                ? String.format(Locale.ROOT, "%+.2f", median / mean) : "-",
                        halfOf(fills), fills.size(),
                        fills.stream().mapToDouble(Double::doubleValue).sum() / fills.size()));
            }
        }
        sb.append("\nприб. — прибыльных суток из всех; лучш.сут — доля лучших суток"
                + " в сумме ПОЛОЖИТЕЛЬНЫХ;\n50% за — столько лучших суток дают половину"
                + " этой суммы; мед/сред — 1.0 ровный ряд,\nоколо нуля — среднее"
                + " держится на выбросах.\n⚠️ «сделок 50% за» — та же мера по числу"
                + " сделок. Редкий поток лумпится сам собой,\nи только разница между"
                + " двумя колонками говорит о концентрации СВЕРХ арифметики счёта.\n");

        // СЫРЬЁ. Таблицы отвечают на заданный вопрос, а следующий вопрос всегда
        // другой, и пересчитывать тринадцать суток ради него — полдня. Здесь тот
        // же ряд машиночитаемо: пара, ступень, дата, ход цены, доход, сделки.
        sb.append("\n\n=== СЫРЬЁ ПО СУТКАМ (пара;ступень;дата;ход%;доход;сделок) ===\n\n");
        for (var pair : grid.entrySet()) {
            for (var e : pair.getValue().entrySet()) {
                for (Forecast.Day d : e.getValue().byDay) {
                    sb.append(String.format(Locale.ROOT, "%s;%s;%s;%.3f;%.6f;%d%n",
                            pair.getKey(), e.getKey().label(), d.label(), d.movePct(),
                            d.realised(), d.fills()));
                }
            }
        }
        return sb.toString();
    }

    /** Сколько ЛУЧШИХ суток набирают половину суммы положительных. Ряд убывающий. */
    private static int halfOf(List<Double> sortedDesc) {
        double positive = sortedDesc.stream().filter(v -> v > 0)
                .mapToDouble(Double::doubleValue).sum();
        int n = 0;
        double acc = 0;
        for (double v : sortedDesc) {
            if (acc >= positive / 2 || v <= 0) {
                break;
            }
            acc += v;
            n++;
        }
        return n;
    }

    private static String render(Map<String, Map<Variant, Cell>> grid,
                                 int levels, double levelStepBp, boolean innerFirst,
                                 Map<String, Integer> skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ОБХОД ВСЕЛЕННОЙ: ").append(levels).append(" уровня шагом ")
                .append(String.format(Locale.ROOT, "%.0f", levelStepBp)).append(" б.п., ")
                .append(innerFirst ? "от ближнего" : "от дальнего")
                .append(", потолок 20 лотов ===\n");

        List<Variant> variants = grid.values().stream().flatMap(m -> m.keySet().stream())
                .distinct().sorted().toList();

        // Ступень лестницы, где пара дала лучшие ГОДОВЫЕ на капитал.
        // ⚠️ Не доход за окно: потолок равен двадцати лотам, поэтому с лотом
        // растёт и вложенный капитал, и лот $25 обгонит лот $1 просто потому,
        // что денег в деле в двадцать пять раз больше.
        record Best(String base, Variant v, Cell cell) {
            double annual() {
                // ⚠️ Знаменатель — РЕАЛЬНЫЙ потолок, а не «двадцать лотов».
                // Пока здесь стояло lot*price*20, ключ --cap-usd ломал колонку
                // молча: при одинаковом капитале в $20 форма «3 уровня по $1»
                // получала втрое большие годовые, чем «1 уровень по $3», просто
                // потому что делилась на втрое меньшее число (09.09.2026).
                double capital = cell.cap > 0 ? cell.cap * cell.price
                        : cell.lot * cell.price * 20;
                return capital > 0 && cell.days > 0
                        ? cell.realisedMarket / cell.days * 365 / capital * 100 : 0;
            }
        }
        List<Best> best = new ArrayList<>();
        for (var e : grid.entrySet()) {
            Best b = null;
            for (var o : e.getValue().entrySet()) {
                Best cand = new Best(e.getKey(), o.getKey(), o.getValue());
                if (o.getValue().days > 0 && (b == null || cand.annual() > b.annual())) {
                    b = cand;
                }
            }
            if (b != null && b.cell().days > 0) {
                best.add(b);
            }
        }
        best.sort(Comparator.comparingDouble((Best b) -> -b.annual()));

        // ⚠️ Покупки и продажи РАЗДЕЛЬНО, и обязательно рядом с инвентарём.
        // «Реализовано» — это ЗАКРЫТЫЕ пары FIFO: непроданный остаток в него не
        // входит вовсе. Пара, которая только покупала, показывает ровно ноль и
        // внешне неотличима от той, где ничего не происходило, — хотя на деле
        // она потратила капитал и сидит с мешком. Средний остаток на конец суток
        // это и показывает.
        // ⚠️ ДОЛЯ УЧАСТИЯ — первая колонка, куда надо смотреть на тонкой паре.
        // Это наши исполнения к числу сделок на самой площадке. Маркет-мейкер,
        // который забирает 90% ленты, — не результат, а признак того, что модель
        // очереди на этой паре сломалась: у PEPE на площадке 34 сделки в сутки,
        // и «поймать» их все физически некому.
        sb.append("\nпара     | ступень | сут |покуп|прод | доля |  очередь |  касание |")
                .append("  в сутки | годовых | ост.лотов | пост/сут | на пост. | худшие сутки\n");
        sb.append("---------+---------+-----+-----+-----+------+----------+----------+")
                .append("----------+---------+-----------+----------+----------+-------------\n");
        for (Best b : best) {
            Cell c = b.cell();
            double perDay = c.realisedMarket / c.days;
            double perPlacement = c.placements > 0 ? c.realisedMarket / c.placements : 0;
            double share = c.marketTrades > 0 ? 100.0 * c.fills / c.marketTrades : 0;
            Forecast.Day worst = c.byDay.stream()
                    .min(Comparator.comparingDouble(Forecast.Day::realised)).orElse(null);
            sb.append(String.format(Locale.ROOT,
                    "%-8s | %7s |%4.0f |%4d |%4d |%4.0f%% |%+9.4f |%+9.4f |%+9.4f |%+7.1f%% "
                            + "|%10.1f |%9.0f |%+9.6f | %s%n",
                    b.base(), b.v().label(), c.days, c.buys, c.sells, share,
                    c.realisedMarket, c.realisedTouch, perDay, b.annual(),
                    c.daysHeld > 0 ? c.inventoryLots / c.daysHeld : 0,
                    c.placements / c.days, perPlacement,
                    worst == null ? "-" : String.format(Locale.ROOT, "%s %+.4f",
                            worst.label().substring(5), worst.realised())));
        }

        sb.append("\n\n=== ЛЕСТНИЦА: годовых на капитал / доля ленты ===\n\n");
        sb.append("пара     ");
        for (Variant v : variants) {
            sb.append(String.format(Locale.ROOT, "|%13s", v.label()));
        }
        sb.append("\n---------");
        for (int i = 0; i < variants.size(); i++) {
            sb.append("+-------------");
        }
        sb.append('\n');
        for (Best b : best) {
            sb.append(String.format(Locale.ROOT, "%-8s ", b.base()));
            for (Variant v : variants) {
                Cell c = grid.get(b.base()).get(v);
                if (c == null || c.days <= 0) {
                    sb.append("|            -");
                    continue;
                }
                // ⚠️ Тот же знаменатель, что и в главной таблице: РЕАЛЬНЫЙ
                // потолок. Пока здесь стояло lot*price*20, две таблицы одного
                // отчёта давали по одной ячейке разные годовые (+254% против
                // +85%), и какая верна — угадать было нельзя.
                double capital = c.cap > 0 ? c.cap * c.price : c.lot * c.price * 20;
                double annual = capital > 0
                        ? c.realisedMarket / c.days * 365 / capital * 100 : 0;
                double share = c.marketTrades > 0 ? 100.0 * c.fills / c.marketTrades : 0;
                sb.append(String.format(Locale.ROOT, "|%+8.0f%% %3.0f%%", annual, share));
            }
            sb.append('\n');
        }

        sb.append(concentration(grid));

        // Разрез по суткам у лучших пар: средний доход прячет главное — держится
        // ли конструкция на падении.
        sb.append("\n\n=== ПО СУТКАМ, шесть первых пар (рабочая модель) ===\n\n");
        List<Best> top = best.subList(0, Math.min(6, best.size()));
        Map<String, Map<String, Forecast.Day>> byDate = new TreeMap<>();
        for (Best b : top) {
            for (Forecast.Day d : b.cell().byDay) {
                byDate.computeIfAbsent(d.label(), k -> new LinkedHashMap<>()).put(b.base(), d);
            }
        }
        sb.append("сутки     ");
        for (Best b : top) {
            sb.append(String.format(Locale.ROOT, "|%16s", b.base() + " " + b.v().label()));
        }
        sb.append('\n');
        for (var e : byDate.entrySet()) {
            sb.append(String.format(Locale.ROOT, "%-9s ", e.getKey().substring(5)));
            for (Best b : top) {
                Forecast.Day d = e.getValue().get(b.base());
                sb.append(d == null ? "|               -"
                        : String.format(Locale.ROOT, "|%+8.4f %+6.2f%%", d.realised(), d.movePct()));
            }
            sb.append('\n');
        }

        if (!skipped.isEmpty()) {
            sb.append("\nсуток пропущено по нехватке снимков: ").append(skipped).append('\n');
        }
        return sb.toString();
    }
}
