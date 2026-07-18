package org.home.data.scheduling;

import org.home.data.cli.CliMode;
import org.home.data.collectors.CalendarCollector;
import org.home.data.collectors.Collector;
import org.home.data.collectors.FundingCollector;
import org.home.data.collectors.MacroCollector;
import org.home.data.collectors.NewsRssCollector;
import org.home.data.collectors.OhlcvCollector;
import org.home.data.collectors.OnchainCollector;
import org.home.data.collectors.OpenInterestCollector;
import org.home.data.collectors.UniverseCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание из док. 09 §5. Каждый коллектор изолирован: сбой одного не
 * останавливает остальные (док. 01 §3 «система не должна падать из-за одного API»).
 * В one-shot режиме (--collect/--backfill) расписание отключено через {@link CliMode}.
 */
@Component
public class CollectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectorScheduler.class);

    private final CliMode mode;
    private final OhlcvCollector ohlcv;
    private final FundingCollector funding;
    private final OpenInterestCollector oi;
    private final OnchainCollector onchain;
    private final UniverseCollector universe;
    private final MacroCollector macro;
    private final CalendarCollector calendar;
    private final NewsRssCollector news;

    public CollectorScheduler(CliMode mode, OhlcvCollector ohlcv, FundingCollector funding,
                              OpenInterestCollector oi, OnchainCollector onchain,
                              UniverseCollector universe, MacroCollector macro,
                              CalendarCollector calendar, NewsRssCollector news) {
        this.mode = mode;
        this.ohlcv = ohlcv;
        this.funding = funding;
        this.oi = oi;
        this.onchain = onchain;
        this.universe = universe;
        this.macro = macro;
        this.calendar = calendar;
        this.news = news;
    }

    // OI — самый частый: Binance хранит только 30 дней, пропуски невосполнимы
    @Scheduled(initialDelay = 20_000, fixedDelay = 5 * 60_000)
    public void oi() {
        run(oi);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 15 * 60_000)
    public void ohlcv() {
        run(ohlcv);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60 * 60_000)
    public void funding() {
        run(funding);
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    public void onchain() {
        run(onchain);
    }

    @Scheduled(initialDelay = 90_000, fixedDelay = 24 * 60 * 60_000)
    public void universe() {
        run(universe);
    }

    @Scheduled(cron = "0 30 7 * * MON", zone = "UTC")
    public void macro() {
        run(macro);
    }

    @Scheduled(initialDelay = 45_000, fixedDelay = 60 * 60_000)
    public void calendar() {
        run(calendar);
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 10 * 60_000)
    public void news() {
        run(news);
    }

    private void run(Collector collector) {
        if (!mode.isScheduleMode()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            collector.collect();
            log.debug("{} ok за {} мс", collector.name(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Коллектор {} упал: {}", collector.name(), e.getMessage());
        }
    }
}
