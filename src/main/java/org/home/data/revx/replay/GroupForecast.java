package org.home.data.revx.replay;

import org.home.data.revx.exec.AllocRegistry;
import org.home.data.revx.exec.BotTag;
import org.home.data.revx.exec.ExecJournal;
import org.home.data.revx.exec.Executor;
import org.home.data.revx.exec.PlacementBudget;
import org.home.data.revx.exec.QuoteLoop;
import org.home.data.revx.sim.Quoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ГРУППОВОЙ ПРОГОН: несколько ботов на РАЗНЫХ парах по одним часам и с одним
 * общим ведром постановок (29.09.2026, задача владельца).
 *
 * <h2>Зачем</h2>
 *
 * У площадки 1000 постановок в сутки на весь счёт, и поднять предел одному боту
 * можно только за счёт соседей. Значит подбирать надо не настройку бота, а состав
 * группы. Одиночный {@link Forecast} этого не видит: у него ведра нет вовсе,
 * давление всегда ноль, и каждый бот берёт сколько хочет до своего предела.
 *
 * <h2>Как устроено</h2>
 *
 * <ul>
 *   <li>боты ОДНОЙ пары делят один {@link SimVenue} — одну книгу, один поток
 *       сделок и один счёт, как d и c на XRP живьём;</li>
 *   <li>у каждой пары свой {@link ReplayFair} и свой реестр владения, а часы
 *       ОДНИ: расписание — слияние отметок всех пар, прорежённое до одной в
 *       {@link #MIN_STEP_MS} (живой бот тикает раз в секунду, а три пары по
 *       секунде дали бы каждому боту втрое больше тиков, чем в одиночном
 *       прогоне);</li>
 *   <li>ведро постановок — один {@link PlacementBudget} на всех, с полами по
 *       боту ({@code revx.budget.floors}); свой суточный предел у каждого бота
 *       остаётся, как живьём.</li>
 * </ul>
 *
 * ⚠️ Касса НЕ общая: у каждой пары свой счёт с деньгами на полный потолок. Живьём
 * USDC один на всех (~110), и в бурный день боты делят ещё и деньги — это
 * следующий шаг, если окажется, что упираемся в него.
 *
 * ⚠️ Группа из одного бота обязана совпасть с одиночным прогоном до знака: так
 * проверяется, что общие часы ничего не сдвинули. Для одной пары прорежение не
 * применяется.
 */
public final class GroupForecast {

    private static final Logger log = LoggerFactory.getLogger(GroupForecast.class);

    /** Не чаще одной отметки часов на это расстояние, когда пар несколько. */
    static final long MIN_STEP_MS = 900;

    /** Одна пара группы: запись, модель исполнения, настройки и её боты. */
    public record Pair(String symbol, List<ReplayFair.Tick> ticks, FillModel model,
                       BootParams base, List<Forecast.BotSpec> bots, List<Integer> caps) {
    }

    /** Итог бота плюс то, что есть только у группы. */
    public record Result(String symbol, int levels, int cap, Forecast.BotResult bot, long budgetDenied,
                         long sellOnly, long limitStops, double pressuredShare,
                         double pressuredHalfShare) {
    }

    private GroupForecast() {
    }

    public static List<Result> run(List<Pair> pairs, org.home.data.revx.RevxConfig cfg,
                                   boolean shareBudget, String journalOut) throws Exception {
        long start = Long.MIN_VALUE;
        long end = Long.MAX_VALUE;
        for (Pair p : pairs) {
            start = Math.max(start, p.ticks().get(0).tsMs());
            end = Math.min(end, p.ticks().get(p.ticks().size() - 1).tsMs());
        }
        if (end <= start) {
            throw new IllegalStateException("у пар нет общего окна");
        }
        final long endMs = end;
        SimClock clock = new SimClock(start);
        clock.followSchedule(schedule(pairs, start, end));

        Path dir = Files.createTempDirectory("revx-group");
        PlacementBudget budget = shareBudget
                ? new PlacementBudget(dir.resolve("budget.db").toString(),
                pairs.stream().mapToInt(p -> p.bots().size()).sum())
                : null;
        List<AllocRegistry> allocs = new ArrayList<>();
        List<ExecJournal> journals = new ArrayList<>();
        List<QuoteLoop> loops = new ArrayList<>();
        List<Forecast.BotSpec> specs = new ArrayList<>();
        List<Pair> owners = new ArrayList<>();
        List<Double> seeds = new ArrayList<>();
        try {
            for (Pair p : pairs) {
                BootParams base = p.base();
                List<ReplayFair.Tick> ticks = p.ticks();
                ReplayFair fair = new ReplayFair(ticks, clock);
                double refPrice = Double.NaN;
                for (ReplayFair.Tick t : ticks) {
                    if (Double.isFinite(t.fair()) && t.fair() > 0) {
                        refPrice = t.fair();
                        break;
                    }
                }
                if (!(refPrice > 0)) {
                    throw new IllegalStateException("нет справедливой цены у " + p.symbol());
                }
                double quoteStart = p.bots().stream().mapToDouble(Forecast.BotSpec::inventoryCap)
                        .sum() * refPrice * 1.2;
                double startBase = Forecast.startInventory(ticks, p.bots());
                SimVenue venue = new SimVenue(clock, p.model(), base.symbol(), startBase,
                        quoteStart, base.minNotional());
                String baseCur = base.symbol().substring(0, base.symbol().indexOf('/'));
                String quoteCur = base.symbol().substring(base.symbol().indexOf('/') + 1);
                AllocRegistry alloc = new AllocRegistry(
                        dir.resolve("alloc-" + baseCur + ".db").toString());
                alloc.heartbeatOff();
                allocs.add(alloc);
                for (int i = 0; i < p.bots().size(); i++) {
                    Forecast.BotSpec spec = p.bots().get(i);
                    ExecJournal journal = new ExecJournal(
                            dir.resolve("bot-" + spec.botId() + ".db").toString());
                    journal.clock(clock);
                    if (journalOut == null || journalOut.isBlank()) {
                        journal.quotesOff();
                    }
                    journals.add(journal);
                    double baseSeed = startBase / p.bots().size();
                    alloc.claim(spec.botId(), baseCur, baseSeed, startBase, refPrice, start);
                    alloc.claim(spec.botId(), quoteCur, quoteStart / p.bots().size(), quoteStart,
                            refPrice, start);
                    Quoter.Params params = new Quoter.Params(spec.offset(), spec.size(),
                            spec.inventoryCap(), base.skewK(), spec.skewTarget(), cfg.simDriftBeta(),
                            cfg.simBuySizeRatio(), cfg.simDriftWindowMs(), cfg.simSizeShapeEta(),
                            cfg.simDriftGateEr(), cfg.simErWindowMs(), cfg.simErSampleMs(),
                            cfg.simStopDrawdownPct(), Quoter.Sticky.OFF, Quoter.Frozen.OFF,
                            Quoter.Hedge.OFF, cfg.simStopCoolOffMs(), cfg.simRequoteThreshold(),
                            base.quoteStep());
                    QuoteLoop loop = new QuoteLoop(venue, clock, fair, journal, params,
                            base.symbol(), base.periodMs(), base.minNotional(),
                            new BotTag(spec.botId()),
                            Executor.buildPolicy(params, base.costFloorMargin(), base.anchorLeash(),
                                    base.anchorWidening(), base.widening(), base.wideningMaxStep(),
                                    spec.size(), spec.inventoryCap(), base.quoteStep()),
                            true, baseSeed, base.baseStep(), base.parkDistance(), alloc,
                            spec.levels(), spec.levelStep(), spec.innerFirst());
                    if (budget != null) {
                        loop.placementBudget(budget);
                    }
                    // Свой суточный предел — всегда, как живьём (100/250).
                    loop.placementCap(p.caps().get(i));
                    loop.scaleLimitsForLot(spec.size() * refPrice);
                    if (spec.dynOffsetK() > 0) {
                        loop.dynamicOffset(spec.dynOffsetK(), 1.0);
                    }
                    loop.statsInventoryCap(spec.inventoryCap());
                    loops.add(loop);
                    specs.add(spec);
                    owners.add(p);
                    seeds.add(baseSeed);
                }
            }
            for (int i = 1; i < loops.size(); i++) {
                clock.join();
            }
            clock.stopAt(endMs, () -> loops.forEach(QuoteLoop::shutdown));
            for (QuoteLoop loop : loops) {
                loop.startQuoting();
            }
            List<Throwable> crashes = Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (QuoteLoop loop : loops) {
                Thread t = new Thread(() -> {
                    try {
                        loop.run();
                    } finally {
                        clock.leave();
                    }
                }, "group-" + loop.botId());
                t.setUncaughtExceptionHandler((thread, e) -> {
                    log.error("котировщик {} упал: {}", thread.getName(), e.toString(), e);
                    crashes.add(e);
                });
                threads.add(t);
                t.start();
            }
            // Шесть ботов по одним часам идут медленнее одного: предохранитель
            // шире одиночного, но молчать о недосчитанном прогоне он не вправе.
            long timeout = Forecast.JOIN_TIMEOUT_MS * 4;
            for (Thread t : threads) {
                t.join(timeout);
            }
            List<String> stuck = threads.stream().filter(Thread::isAlive)
                    .map(Thread::getName).toList();
            if (!stuck.isEmpty()) {
                throw new IllegalStateException("групповой прогон НЕ ДОСЧИТАН: ещё торгуют " + stuck);
            }
            if (!crashes.isEmpty()) {
                throw new IllegalStateException("групповой прогон не состоялся: упало "
                        + crashes.size() + ", первый — " + crashes.get(0), crashes.get(0));
            }
            double days = Math.max(1e-9, (endMs - start) / 86_400_000.0);
            List<Result> out = new ArrayList<>();
            for (int i = 0; i < loops.size(); i++) {
                QuoteLoop l = loops.get(i);
                ExecJournal j = journals.get(i);
                Forecast.BotResult r = Forecast.measure(specs.get(i), j, l, owners.get(i).base(),
                        days, window(owners.get(i).ticks(), start, endMs), seeds.get(i));
                long[] pt = l.pressureTicks();
                out.add(new Result(owners.get(i).symbol(), specs.get(i).levels(),
                        owners.get(i).caps().get(owners.get(i).bots().indexOf(specs.get(i))), r,
                        j.countEvents("budget_denied"),
                        j.countEvents("sell_only"), j.countEvents("limit_blocked"),
                        pt[0] > 0 ? (double) pt[1] / pt[0] : 0,
                        pt[0] > 0 ? (double) pt[2] / pt[0] : 0));
            }
            return out;
        } finally {
            journals.forEach(ExecJournal::close);
            if (budget != null) {
                budget.close();
            }
            allocs.forEach(AllocRegistry::close);
            if (journalOut != null && !journalOut.isBlank()) {
                Path outDir = Path.of(journalOut);
                Files.createDirectories(outDir);
                for (Forecast.BotSpec s : specs) {
                    Path src = dir.resolve("bot-" + s.botId() + ".db");
                    if (Files.exists(src)) {
                        Files.copy(src, outDir.resolve("bot-" + s.botId() + ".db"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Forecast.delete(dir);
        }
    }

    /** Отметки часов: у одной пары — её собственные, у нескольких — слияние с прорежением. */
    static long[] schedule(List<Pair> pairs, long start, long end) {
        if (pairs.size() == 1) {
            return pairs.get(0).ticks().stream().mapToLong(ReplayFair.Tick::tsMs)
                    .filter(t -> t >= start && t <= end).toArray();
        }
        long[] all = pairs.stream().flatMap(p -> p.ticks().stream())
                .mapToLong(ReplayFair.Tick::tsMs).filter(t -> t >= start && t <= end)
                .sorted().toArray();
        long[] thin = new long[all.length];
        int n = 0;
        long last = Long.MIN_VALUE;
        for (long t : all) {
            if (n == 0 || t - last >= MIN_STEP_MS) {
                thin[n++] = t;
                last = t;
            }
        }
        return java.util.Arrays.copyOf(thin, n);
    }

    private static List<ReplayFair.Tick> window(List<ReplayFair.Tick> ticks, long from, long to) {
        return ticks.stream().filter(t -> t.tsMs() >= from && t.tsMs() <= to).toList();
    }

    /** Сводная таблица группы: каждый бот, итог и то, что даёт соседство. */
    public static String render(List<Result> results) {
        StringBuilder sb = new StringBuilder("\n=== ГРУППОВОЙ ПРОГОН ===\n");
        sb.append("бот | пара     | ур | отступ | покуп | прод | итог,$  | пост/сут | отказов ведра"
                + " | под давл. | давл.≥0.5 | распрод. | стоп пред.\n");
        double total = 0;
        Map<String, Double> byDay = new LinkedHashMap<>();
        for (Result r : results) {
            Forecast.BotResult b = r.bot();
            total += b.realised();
            sb.append(String.format(Locale.ROOT,
                    "%-3s | %-8s | %2s | %6.1f | %5d | %4d | %+7.4f | %4.0f/%-3d | %13d | %8.1f%% | %8.1f%% | %8d | %10d%n",
                    b.botId(), r.symbol(), String.valueOf(r.levels()), b.offsetBp(), b.buys(), b.sells(), b.realised(),
                    b.placements() / b.days(), r.cap(), r.budgetDenied(),
                    100 * r.pressuredShare(), 100 * r.pressuredHalfShare(), r.sellOnly(),
                    r.limitStops()));
            for (Forecast.Day d : b.days_()) {
                byDay.merge(d.label(), d.realised(), Double::sum);
            }
        }
        sb.append(String.format(Locale.ROOT, "ИТОГО группы: %+.4f $%n", total));
        sb.append("по суткам, вся группа (реализовано):\n");
        byDay.forEach((d, v) -> sb.append(String.format(Locale.ROOT, "  %s  %+.4f%n", d, v)));
        return sb.toString();
    }
}
