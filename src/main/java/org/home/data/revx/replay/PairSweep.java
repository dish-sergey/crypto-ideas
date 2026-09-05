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

    /** Накопитель по одной паре и одному отступу. */
    private static final class Cell {
        double realisedMarket;
        double realisedTouch;
        int fills;
        int buys;
        int sells;
        long placements;
        double days;
        double lot;
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
        long from = java.time.Instant.parse(fromIso).toEpochMilli();
        long to = java.time.Instant.parse(toIso).toEpochMilli();
        FairPrice.Limits limits = new FairPrice.Limits(cfg.fairMinPairs(),
                cfg.fairMaxDispersionPct(), cfg.fairMaxReferenceSpreadPct(),
                cfg.fairMaxResidualPct());

        // пара → отступ → накопитель
        Map<String, Map<Double, Cell>> grid = new TreeMap<>();
        Map<String, StandReader.PairSpec> specs = new LinkedHashMap<>();
        Map<String, Integer> skipped = new TreeMap<>();

        try (StandReader stand = new StandReader(standDbPath, cfg.memecoins(), limits,
                cfg.fairMaxSkewMs())) {
            int dayNo = 0;
            for (long day = from; day < to; day += DAY_MS) {
                dayNo++;
                String label = java.time.Instant.ofEpochMilli(day).toString().substring(0, 10);
                var fair = new StandFair(standDbPath, null, limits, cfg.memecoins(),
                        cfg.fairMaxSkewMs(), org.home.data.revx.exec.Clock.system(),
                        day, day + DAY_MS - 1);
                if (fair.pairs() == 0) {
                    log.warn("{}: книг нет, сутки пропущены", label);
                    continue;
                }
                log.warn("=== сутки {} ({} из {}), пар в срезе {} ===",
                        label, dayNo, (to - from) / DAY_MS, fair.pairs());

                for (String base : new ArrayList<>(fair.bases())) {
                    String symbol = base + "/USDC";
                    if (fair.snapshots(base) < MIN_SNAPSHOTS) {
                        skipped.merge(base, 1, Integer::sum);
                        continue;
                    }
                    StandReader.PairSpec ps = specs.computeIfAbsent(symbol, stand::spec);
                    if (ps == null) {
                        continue;                 // пара не торгуется — считать нечего
                    }
                    try {
                        oneDay(standDbPath, cfg, fair, base, symbol, ps, label, day,
                                levels, levelStepBp, innerFirst, offsetsBp, grid);
                    } catch (Exception e) {
                        log.warn("{} {}: прогон не прошёл — {}", label, symbol, e.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.error("обход вселенной не прошёл: {}", e.toString(), e);
            return;
        }

        log.info("\n{}", render(grid, offsetsBp, levels, levelStepBp, innerFirst, skipped));
    }

    private static void oneDay(String standDbPath, RevxConfig cfg, StandFair fair,
                               String base, String symbol, StandReader.PairSpec ps,
                               String label, long dayStart, int levels, double levelStepBp,
                               boolean innerFirst, double[] offsetsBp,
                               Map<String, Map<Double, Cell>> grid) throws Exception {
        List<ReplayFair.Tick> ticks = fair.toTicks(base);
        if (ticks.size() < MIN_SNAPSHOTS) {
            return;
        }
        double price = ticks.get(ticks.size() / 2).fair();
        if (!(price > 0)) {
            return;
        }
        // Лот в один доллар, округлённый к шагу количества. У части пар шаг
        // грубый, и доллар в него не укладывается — тогда лот выходит больше,
        // и это видно в отчёте отдельной колонкой.
        double lot = ps.baseStep() > 0
                ? Math.max(ps.baseStep(), Math.round(1.0 / price / ps.baseStep()) * ps.baseStep())
                : 1.0 / price;
        double cap = lot * 20;

        var bp = new BootParams(symbol, "a", lot, cap, offsetsBp[0] / 10_000,
                cfg.simSkewK(), 0.3, 1000, ps.minNotional(), ps.baseStep(),
                ps.quoteStep(), 0.10, -1, -1, 0, 0.02, 0.5, true,
                levels, levelStepBp / 10_000, innerFirst);

        MarketData market0 = MarketData.load(standDbPath, symbol,
                ticks.get(0).tsMs(), ticks.get(ticks.size() - 1).tsMs());

        for (double offBp : offsetsBp) {
            var spec = new Forecast.BotSpec("a", offBp / 10_000, 0.3, cap,
                    levels, levelStepBp / 10_000, lot, innerFirst);
            List<Forecast.BotSpec> one = List.of(spec);

            var queue = Forecast.run(ticks, new MarketFillModel(market0.fresh()), bp, one, cfg);
            var touch = Forecast.run(ticks, new TouchFillModel(market0.fresh()), bp, one, cfg);
            if (queue.isEmpty() || touch.isEmpty()) {
                continue;
            }
            Forecast.BotResult q = queue.get(0);
            Forecast.BotResult t = touch.get(0);

            Cell cell = grid.computeIfAbsent(base, k -> new TreeMap<>())
                    .computeIfAbsent(offBp, k -> new Cell());
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
            cell.price = price;
            double move = q.days_() == null || q.days_().isEmpty() ? 0
                    : q.days_().get(0).movePct();
            cell.byDay.add(new Forecast.Day(label, move, q.realised(), q.fills()));
        }
    }

    private static String render(Map<String, Map<Double, Cell>> grid, double[] offsetsBp,
                                 int levels, double levelStepBp, boolean innerFirst,
                                 Map<String, Integer> skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ОБХОД ВСЕЛЕННОЙ: ").append(levels).append(" уровня шагом ")
                .append(String.format(Locale.ROOT, "%.0f", levelStepBp)).append(" б.п., ")
                .append(innerFirst ? "от ближнего" : "от дальнего")
                .append(", лот $1, потолок $20 ===\n");

        // Ступень лестницы, где пара заработала больше всего по РАБОЧЕЙ модели.
        record Best(String base, double off, Cell cell) {
        }
        List<Best> best = new ArrayList<>();
        for (var e : grid.entrySet()) {
            Best b = null;
            for (var o : e.getValue().entrySet()) {
                if (b == null || o.getValue().realisedMarket > b.cell().realisedMarket) {
                    b = new Best(e.getKey(), o.getKey(), o.getValue());
                }
            }
            if (b != null && b.cell().days > 0) {
                best.add(b);
            }
        }
        best.sort(Comparator.comparingDouble((Best b) ->
                -b.cell().realisedMarket / Math.max(0.01, b.cell().days)));

        // ⚠️ Покупки и продажи РАЗДЕЛЬНО, и обязательно рядом с инвентарём.
        // «Реализовано» — это ЗАКРЫТЫЕ пары FIFO: непроданный остаток в него не
        // входит вовсе. Пара, которая только покупала, показывает ровно ноль и
        // внешне неотличима от той, где ничего не происходило, — хотя на деле
        // она потратила капитал и сидит с мешком. Средний остаток на конец суток
        // это и показывает.
        sb.append("\nпара     |отступ| сут |покуп|прод |  очередь |  касание |  в сутки |")
                .append(" годовых | ост.лотов | пост/сут | на пост. | лот $ | худшие сутки\n");
        sb.append("---------+------+-----+-----+-----+----------+----------+----------+")
                .append("---------+-----------+----------+----------+-------+-------------\n");
        for (Best b : best) {
            Cell c = b.cell();
            double perDay = c.realisedMarket / c.days;
            double capital = c.lot * c.price * 20;
            double annual = capital > 0 ? perDay * 365 / capital * 100 : 0;
            double perPlacement = c.placements > 0 ? c.realisedMarket / c.placements : 0;
            Forecast.Day worst = c.byDay.stream()
                    .min(Comparator.comparingDouble(Forecast.Day::realised)).orElse(null);
            sb.append(String.format(Locale.ROOT,
                    "%-8s | %4.0f |%4.0f |%4d |%4d |%+9.4f |%+9.4f |%+9.4f |%+7.1f%% "
                            + "|%10.1f |%9.0f |%+9.6f |%6.2f | %s%n",
                    b.base(), b.off(), c.days, c.buys, c.sells,
                    c.realisedMarket, c.realisedTouch, perDay, annual,
                    c.daysHeld > 0 ? c.inventoryLots / c.daysHeld : 0,
                    c.placements / c.days, perPlacement, c.lot * c.price,
                    worst == null ? "-" : String.format(Locale.ROOT, "%s %+.4f",
                            worst.label().substring(5), worst.realised())));
        }

        sb.append("\n\n=== ЛЕСТНИЦА ОТСТУПОВ (доход за окно по рабочей модели) ===\n\n");
        sb.append("пара     ");
        for (double o : offsetsBp) {
            sb.append(String.format(Locale.ROOT, "|%7.0f б.п.", o));
        }
        sb.append("\n---------");
        for (int i = 0; i < offsetsBp.length; i++) {
            sb.append("+-----------");
        }
        sb.append('\n');
        for (Best b : best) {
            sb.append(String.format(Locale.ROOT, "%-8s ", b.base()));
            for (double o : offsetsBp) {
                Cell c = grid.get(b.base()).get(o);
                sb.append(c == null ? "|          -"
                        : String.format(Locale.ROOT, "|%+10.4f", c.realisedMarket));
            }
            sb.append('\n');
        }

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
            sb.append(String.format(Locale.ROOT, "|%16s", b.base() + " " + (int) b.off() + "бп"));
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
