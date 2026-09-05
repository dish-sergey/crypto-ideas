package org.home.data.revx.replay;

import org.home.data.revx.exec.AllocRegistry;
import org.home.data.revx.exec.BotTag;
import org.home.data.revx.exec.ExecJournal;
import org.home.data.revx.exec.Executor;
import org.home.data.revx.exec.FifoLedger;
import org.home.data.revx.exec.QuoteLoop;
import org.home.data.revx.sim.Quoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Прогон НЕСКОЛЬКИХ котировщиков по одной книге.
 *
 * <h2>Зачем не один</h2>
 *
 * На счёте их и так несколько: A и C котируют одну BTC/USDC с отступами 10 и
 * 14 б.п. Поток на площадке конечен — за 17 часов на паре прошло ВСЕГО
 * 314 сделок, — и заявки ботов стоят в книге одновременно, деля его между
 * собой. Стенд с одним котировщиком отдаёт ему весь поток и завышает
 * исполнения каждому. Отсюда правило: сравнивать варианты настройки можно
 * только прогоном ВСЕХ ботов разом.
 *
 * <h2>Чем это отличается от сверки</h2>
 *
 * Сверка ({@link ReplayRunner}) доказывает, что стенд воспроизводит живого:
 * тот же код, те же входы, 99.92% совпавших котировок. Прогноз отвечает на
 * другой вопрос — «что будет, если поменять настройки», — и проверить его
 * записью нельзя по определению: такого бота не было.
 *
 * ⚠️ Поэтому результат отдаётся ВИЛКОЙ по двум моделям исполнения: рабочей и
 * заведомо завышенной. Одно число здесь обманывает.
 */
public final class Forecast {

    private static final Logger log = LoggerFactory.getLogger(Forecast.class);

    /** Один котировщик в прогоне: чем отличается от базового. */
    /**
     * @param inventoryCap потолок инвентаря ЭТОГО уровня. Отдельным полем не для
     *                     красоты: сетка из N уровней с полным потолком у каждого
     *                     занимает в N раз больше капитала, чем одиночная
     *                     котировка, и сравнивать их «в лоб» нельзя. Нормировка
     *                     на капитал — деление общего потолка между уровнями.
     */
    public record BotSpec(String botId, double offset, double skewTarget,
                          double inventoryCap, int levels, double levelStep,
                          double size, boolean sellInnerFirst) {
    }

    /** Что получилось у одного котировщика. */
    public record BotResult(String botId, double offsetBp, int fills, double realised,
                            double inventoryLots, long placements, long replaces,
                            long placementCap, double days, String state, long lossStops,
                            double atCapShare, double lotSize) {
    }

    private Forecast() {
    }

    public static List<BotResult> run(List<ReplayFair.Tick> ticks, FillModel model,
                                      BootParams base, List<BotSpec> bots,
                                      org.home.data.revx.RevxConfig cfg) throws Exception {
        long start = ticks.get(0).tsMs();
        long end = ticks.get(ticks.size() - 1).tsMs();

        SimClock clock = new SimClock(start);
        clock.followSchedule(ticks.stream().mapToLong(ReplayFair.Tick::tsMs).toArray());
        ReplayFair fair = new ReplayFair(ticks, clock);

        // Счёт ОБЩИЙ: боты делят и книгу, и деньги. Денег даём столько, чтобы
        // каждому хватило на полный потолок, иначе меряли бы не настройку, а
        // нехватку средств.
        double quoteStart = bots.stream().mapToDouble(BotSpec::inventoryCap).sum()
                * ticks.get(0).fair() * 1.2;
        SimVenue venue = new SimVenue(clock, model, base.symbol(),
                ticks.get(0).inventory(), quoteStart);

        Path dir = Files.createTempDirectory("revx-forecast");
        List<ExecJournal> journals = new ArrayList<>();
        List<QuoteLoop> loops = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        AllocRegistry alloc = new AllocRegistry(dir.resolve("alloc.db").toString());
        try {
            for (BotSpec spec : bots) {
                ExecJournal journal = new ExecJournal(
                        dir.resolve("bot-" + spec.botId() + ".db").toString());
                journal.clock(clock);
                journals.add(journal);

                alloc.claim(spec.botId(), base.symbol().substring(0, base.symbol().indexOf('/')),
                        0, 0, ticks.get(0).fair(), start);
                alloc.claim(spec.botId(), base.symbol().substring(base.symbol().indexOf('/') + 1),
                        quoteStart / bots.size(), quoteStart, ticks.get(0).fair(), start);

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
                        true, 0, base.baseStep(), base.parkDistance(), alloc,
                        spec.levels(), spec.levelStep(), spec.sellInnerFirst());
                loops.add(loop);
            }
            for (int i = 1; i < loops.size(); i++) {
                clock.join();
            }
            // Конец записи останавливает ВСЕХ: иначе оставшиеся ждали бы у барьера.
            clock.stopAt(end, () -> loops.forEach(QuoteLoop::shutdown));

            for (QuoteLoop loop : loops) {
                loop.startQuoting();
            }
            for (QuoteLoop loop : loops) {
                final int slotIndex = threads.size();
                Thread t = new Thread(() -> {
                    clock.assignSlot(slotIndex);
                    try {
                        loop.run();
                    } finally {
                        clock.leave();
                    }
                }, "forecast-" + loop.botId());
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join(600_000);
            }

            List<BotResult> out = new ArrayList<>();
            for (int i = 0; i < bots.size(); i++) {
                out.add(measure(bots.get(i), journals.get(i), loops.get(i), base,
                        Math.max(1e-9, (end - start) / 86_400_000.0)));
            }
            return out;
        } finally {
            journals.forEach(ExecJournal::close);
            delete(dir);
        }
    }

    private static BotResult measure(BotSpec spec, ExecJournal journal,
                                     QuoteLoop loop, BootParams base, double days) {
        FifoLedger ledger = new FifoLedger();
        int fills = 0;
        for (ExecJournal.FillRow f : journal.fills()) {
            if (f.handover()) {
                continue;
            }
            fills++;
            ledger.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee());
        }
        // Доля времени с ПОЛНЫМ инвентарём. Пока бот упёрт в потолок, он только
        // продаёт: покупать нечем, и половина конструкции простаивает. Без этого
        // числа «доход за окно» скрывает, какой ценой он получен.
        double atCap = 0;
        int ticks = 0;
        for (ReplayFair.Tick t : ReplayRunner.readTicks(
                journal.path(), 0, Long.MAX_VALUE)) {
            ticks++;
            if (spec.inventoryCap() > 0 && t.inventory() >= 0.9 * spec.inventoryCap()) {
                atCap++;
            }
        }
        QuoteLoop.Stats st = loop.stats();
        return new BotResult(spec.botId(), spec.offset() * 10_000, fills,
                ledger.tradingRealisedSince(0),
                spec.size() > 0 ? st.inventory() / spec.size() : 0,
                st.placements(), st.replaces(),
                org.home.data.revx.exec.ExecLimits.maxPlacementsPerDay(spec.botId()), days,
                st.state(), journal.countEvents("loss_stop"),
                ticks > 0 ? atCap / ticks : 0, spec.size());
    }

    public static String render(String modelName, List<BotResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("модель: ").append(modelName).append('\n');
        // ⚠️ Постановки и замены приводятся К СУТКАМ, а лимиты у площадки тоже
        // суточные. Прежде печаталось «634/300» — общее число за 5.4 суток
        // против СУТОЧНОГО потолка, и это читалось как «пробил лимит», хотя на
        // деле было 117 в сутки.
        sb.append("бот | отступ |  лот | исполнений | реализовано | инвентарь"
                + " | в потолке | постановок/сут | замен/с\n");
        for (BotResult r : results) {
            // ⚠️ Состояние на конец прогона печатается не для полноты. Бот
            // встаёт сам, когда торговый убыток против buy & hold превышает
            // MAX_TRADING_LOSS_USDC = 1.0, а на многодневном окне с $20
            // инвентаря и движением в 5% это обычное дело. Прогон, где бот
            // простоял три четверти окна, внешне неотличим от честного, и
            // сравнивать их между собой нельзя.
            String state = r.lossStops() > 0 ? "  СТОП по убытку ×" + r.lossStops() : "";
            sb.append(String.format(Locale.ROOT,
                    "%-3s | %5.1f  | %4.2f | %10d | %+11.4f | %8.1f  | %8.1f%% | %6.0f/%-5d "
                            + "| %6.2f%s%n",
                    r.botId(), r.offsetBp(), r.lotSize() * 80_000, r.fills(), r.realised(),
                    r.inventoryLots(), 100 * r.atCapShare(),
                    r.placements() / r.days(), r.placementCap(),
                    r.replaces() / (r.days() * 86_400), state));
        }
        return sb.toString();
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // временный каталог; остаток уберёт система
                }
            });
        } catch (Exception ignored) {
            // то же самое
        }
    }
}
