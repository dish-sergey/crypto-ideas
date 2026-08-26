package org.home.data.revx.sim;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Кто по ту сторону книги: свойство потока или трансфер от конкретного участника.
 *
 * Зачем это отдельно от симуляции. Стенд измерил СВОЙ чистый край. Но одна и та
 * же величина означает разное в зависимости от того, кому она достаётся вообще:
 *
 *  - если ВСЯ пассивная сторона площадки зарабатывает столько же, наш край —
 *    свойство потока (тейкеры платят за немедленность), и риск у него обычный
 *    конкурентный: придут мейкеры, спред сузится;
 *  - если пассивная сторона в целом около нуля или в минусе, а мы в плюсе, то
 *    зарабатываем мы не на потоке, а на ОТБОРЕ — стоим лучше среднего стоящего.
 *    Это тоже доход, но его срок жизни определяется скоростью адаптации тех,
 *    кто стоит хуже.
 *
 * Второй вопрос — кто эти «стоящие хуже». Розничные лимитки markout не мониторят
 * и не адаптируются; алгоритм адаптируется за дни. Различить их можно по следу
 * в книге: округлости размеров, времени жизни лучшего уровня и симметрии сторон.
 *
 * Важное ограничение метода: мы видим только ИСПОЛНЕННЫЕ сделки и верхушку книги.
 * Про заявки, которые стояли и не исполнились, данных нет, поэтому «пассивная
 * сторона» здесь — это те, кому досталась торговля, а не вся резервная ликвидность.
 */
@Component
@Lazy
public class FlowReport {

    private static final Logger log = LoggerFactory.getLogger(FlowReport.class);

    private static final long[] HORIZONS_MS = {60_000, 300_000};

    private final SimDataReader reader;
    private final RevxConfig cfg;
    private final RevxDb db;

    public FlowReport(SimDataReader reader, RevxConfig cfg, RevxDb db) {
        this.reader = reader;
        this.cfg = cfg;
        this.db = db;
    }

    /** Итог по пассивной стороне на одном горизонте, в б.п. оборота. */
    private record PassiveEdge(long horizonMs, int trades, double turnover,
                               double captureBp, double markoutBp, double netBp,
                               double buyNetBp, double sellNetBp,
                               double buyCaptureBp, double sellCaptureBp) {
    }

    /**
     * Где стоит книга USDC относительно опоры. Нужно, чтобы отличить настоящую
     * асимметрию сторон от систематического смещения: если середина книги
     * постоянно ниже справедливой цены, то ЛЮБАЯ покупка в ней выглядит удачной,
     * а любая продажа — неудачной, и разрыв между сторонами не значит ничего.
     */
    private record BookOffset(int samples, double midBp, double bidBp, double askBp,
                              double halfSpreadBp) {
    }

    public void run(String symbol, int hours, long toMs, String out) {
        long fromMs = toMs - hours * 3600_000L;
        SimDataReader.Dataset data = reader.read(symbol, fromMs, toMs,
                cfg.authBookPeriodSeconds() * 1000L);
        if (data.windows().isEmpty()) {
            log.warn("{}: окон нет — отчёт по потоку не строится", symbol);
            return;
        }

        NavigableMap<Long, Double> fair = new TreeMap<>();
        List<MarketTrade> trades = new ArrayList<>();
        for (SimEngine.Window window : data.windows()) {
            if (window.fair() > 0) {
                fair.put(window.tsMs(), window.fair());
            }
            trades.addAll(window.trades());
        }

        List<PassiveEdge> edges = new ArrayList<>();
        for (long horizon : HORIZONS_MS) {
            edges.add(passiveEdge(trades, fair, horizon));
        }
        Liquidity liquidity = classify(data, pairBaseStep(symbol));
        BookOffset offset = bookOffset(data);

        String markdown = render(symbol, hours, data, edges, liquidity, offset);
        write(out, markdown);
        PassiveEdge at60 = edges.get(0);
        log.info("{}: пассивная сторона — захват {} б.п., чистый край {} б.п. на 60 с "
                        + "по {} сделкам → {}", symbol, round(at60.captureBp(), 2),
                round(at60.netBp(), 2), at60.trades(), out);
    }

    /**
     * Край пассивной стороны. Знак: пассивная сторона противоположна агрессору,
     * поэтому при агрессоре SELL пассив КУПИЛ, и его край считается как у покупки.
     * Сделки без определённого агрессора выбрасываются — приписать им сторону
     * значило бы выдумать половину выборки.
     */
    private static PassiveEdge passiveEdge(List<MarketTrade> trades,
                                           NavigableMap<Long, Double> fair, long horizonMs) {
        double turnover = 0;
        double capture = 0;
        double markout = 0;
        double buyNet = 0;
        double buyCapture = 0;
        double buyTurnover = 0;
        double sellNet = 0;
        double sellCapture = 0;
        double sellTurnover = 0;
        int used = 0;
        long lastFairMs = fair.isEmpty() ? Long.MIN_VALUE : fair.lastKey();

        for (MarketTrade trade : trades) {
            if (trade.aggressor() == null) {
                continue;
            }
            if (trade.tsMs() + horizonMs > lastFairMs) {
                continue;                      // горизонт не влезает в данные
            }
            var atFill = fair.floorEntry(trade.tsMs());
            var later = fair.floorEntry(trade.tsMs() + horizonMs);
            if (atFill == null || later == null) {
                continue;
            }
            int passiveSign = trade.aggressor() == Side.SELL ? 1 : -1;   // SELL-агрессор → пассив купил
            double notional = trade.price() * trade.qty();
            double captureValue = passiveSign * (atFill.getValue() - trade.price()) * trade.qty();
            double markoutValue = passiveSign * (later.getValue() - trade.price()) * trade.qty();

            turnover += notional;
            capture += captureValue;
            markout += markoutValue;
            if (passiveSign > 0) {
                buyNet += captureValue + markoutValue;
                buyCapture += captureValue;
                buyTurnover += notional;
            } else {
                sellNet += captureValue + markoutValue;
                sellCapture += captureValue;
                sellTurnover += notional;
            }
            used++;
        }

        double bp = 10_000.0;
        return new PassiveEdge(horizonMs, used, turnover,
                turnover > 0 ? capture / turnover * bp : Double.NaN,
                turnover > 0 ? markout / turnover * bp : Double.NaN,
                turnover > 0 ? (capture + markout) / turnover * bp : Double.NaN,
                buyTurnover > 0 ? buyNet / buyTurnover * bp : Double.NaN,
                sellTurnover > 0 ? sellNet / sellTurnover * bp : Double.NaN,
                buyTurnover > 0 ? buyCapture / buyTurnover * bp : Double.NaN,
                sellTurnover > 0 ? sellCapture / sellTurnover * bp : Double.NaN);
    }

    private static BookOffset bookOffset(SimDataReader.Dataset data) {
        int samples = 0;
        double mid = 0;
        double bid = 0;
        double ask = 0;
        double halfSpread = 0;
        for (SimEngine.Window window : data.windows()) {
            BookView book = window.book();
            if (book == null || book.empty() || !(window.fair() > 0)) {
                continue;
            }
            double fair = window.fair();
            double bestBid = book.bestBid();
            double bestAsk = book.bestAsk();
            samples++;
            mid += ((bestBid + bestAsk) / 2 / fair - 1) * 10_000;
            bid += (bestBid / fair - 1) * 10_000;
            ask += (bestAsk / fair - 1) * 10_000;
            halfSpread += (bestAsk - bestBid) / 2 / fair * 10_000;
        }
        return samples == 0
                ? new BookOffset(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
                : new BookOffset(samples, mid / samples, bid / samples, ask / samples,
                        halfSpread / samples);
    }

    /** След резервной ликвидности в книге. */
    private record Liquidity(int samples, double roundShareBid, double roundShareAsk,
                             double roundShareTrades, double medianBestLifeMs, double p90BestLifeMs,
                             int bestChanges, double sizeSymmetry, double identicalSizeShare) {
    }

    /**
     * Округлость размера: сколько нулей в конце, если считать объём в шагах лота.
     * Мейкер ставит ровные объёмы (0.01, 0.05), розница — «сколько было на балансе».
     * Порог в пять нулей при {@code base_step} = 1e-8 отделяет 0.001 и круглее
     * от произвольных величин вроде 0.0134278.
     */
    private static boolean round(double qty, double baseStep) {
        if (!(qty > 0) || !(baseStep > 0)) {
            return false;
        }
        long steps = Math.round(qty / baseStep);
        if (steps <= 0) {
            return false;
        }
        int zeros = 0;
        while (steps % 10 == 0 && zeros < 18) {
            steps /= 10;
            zeros++;
        }
        return zeros >= 5;
    }

    private Liquidity classify(SimDataReader.Dataset data, double baseStep) {
        int samples = 0;
        int roundBid = 0;
        int roundAsk = 0;
        int identical = 0;
        double symmetrySum = 0;
        List<Long> lives = new ArrayList<>();
        int changes = 0;

        Double previousBest = null;
        long runStart = 0;
        long previousTs = 0;

        for (SimEngine.Window window : data.windows()) {
            BookView book = window.book();
            if (book == null || book.empty()) {
                continue;
            }
            double bidQty = book.bids().get(0).qty();
            double askQty = book.asks().get(0).qty();
            samples++;
            if (round(bidQty, baseStep)) {
                roundBid++;
            }
            if (round(askQty, baseStep)) {
                roundAsk++;
            }
            if (Math.abs(bidQty - askQty) <= baseStep) {
                identical++;
            }
            double min = Math.min(bidQty, askQty);
            double max = Math.max(bidQty, askQty);
            if (max > 0) {
                symmetrySum += min / max;
            }

            double best = book.bestBid();
            if (previousBest == null) {
                previousBest = best;
                runStart = window.tsMs();
            } else if (Math.abs(best - previousBest) > 1e-12) {
                lives.add(previousTs - runStart);
                changes++;
                previousBest = best;
                runStart = window.tsMs();
            }
            previousTs = window.tsMs();
        }

        int roundTrades = 0;
        int tradeSamples = 0;
        for (SimEngine.Window window : data.windows()) {
            for (MarketTrade trade : window.trades()) {
                tradeSamples++;
                if (round(trade.qty(), baseStep)) {
                    roundTrades++;
                }
            }
        }

        lives.sort(Long::compareTo);
        return new Liquidity(samples,
                share(roundBid, samples), share(roundAsk, samples), share(roundTrades, tradeSamples),
                percentile(lives, 0.50), percentile(lives, 0.90), changes,
                samples == 0 ? Double.NaN : symmetrySum / samples, share(identical, samples));
    }

    private static double share(int part, int total) {
        return total == 0 ? Double.NaN : (double) part / total;
    }

    private static double percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        return sorted.get(Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.round(q * (sorted.size() - 1)))));
    }

    private double pairBaseStep(String symbol) {
        List<Double> rows = db.query("SELECT base_step FROM revx_pair WHERE symbol = ?",
                rs -> rs.getDouble("base_step"), symbol);
        if (rows.isEmpty()) {
            throw new IllegalStateException("нет спецификации пары " + symbol + " — сначала --revx-pairs");
        }
        return rows.get(0);
    }

    private String render(String symbol, int hours, SimDataReader.Dataset data,
                          List<PassiveEdge> edges, Liquidity liquidity, BookOffset offset) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Кто по ту сторону: ").append(symbol).append("\n\n");
        sb.append("Проверка по док. 79 §5. Считается на собранных данных, без единого "
                + "нового запроса.\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(data.fromMs())).append(" — ")
                .append(Instant.ofEpochMilli(data.toMs())).append(" (").append(hours).append(" ч) |\n");
        sb.append("| Сделок в ленте | ").append(data.tradesTotal()).append(" |\n");
        sb.append("| Из них без стороны агрессора | ").append(data.tradesUnknownSide())
                .append(" |\n\n");

        sb.append("## 1. Край пассивной стороны площадки\n\n");
        sb.append("Для каждой сделки пассивная сторона — противоположная агрессору. "
                + "Захват считается от справедливой цены в момент сделки, markout — от неё "
                + "же через горизонт. Это ровно та метрика, которой меряется наша "
                + "стратегия, но применённая ко ВСЕЙ торговле площадки.\n\n");
        sb.append("| Горизонт | Сделок | Оборот | Захват, б.п. | markout, б.п. "
                + "| **Чистый край, б.п.** | покупки: захват / край | продажи: захват / край |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (PassiveEdge edge : edges) {
            sb.append("| ").append(edge.horizonMs() / 1000).append(" с")
                    .append(" | ").append(edge.trades())
                    .append(" | ").append(round(edge.turnover(), 0))
                    .append(" | ").append(round(edge.captureBp(), 2))
                    .append(" | ").append(round(edge.markoutBp(), 2))
                    .append(" | **").append(round(edge.netBp(), 2)).append("**")
                    .append(" | ").append(round(edge.buyCaptureBp(), 2)).append(" / ")
                    .append(round(edge.buyNetBp(), 2))
                    .append(" | ").append(round(edge.sellCaptureBp(), 2)).append(" / ")
                    .append(round(edge.sellNetBp(), 2))
                    .append(" |\n");
        }

        sb.append("\n### Где стоит книга USDC относительно справедливой цены\n\n");
        sb.append("Без этой поправки разрыв между сторонами читать нельзя: если "
                + "середина книги систематически смещена, то одна сторона выглядит "
                + "выгодной, а другая убыточной просто по построению.\n\n");
        sb.append("| Показатель | Значение, б.п. от справедливой |\n|---|---|\n");
        sb.append("| Середина книги | ").append(round(offset.midBp(), 2)).append(" |\n");
        sb.append("| Лучший бид | ").append(round(offset.bidBp(), 2)).append(" |\n");
        sb.append("| Лучший аск | ").append(round(offset.askBp(), 2)).append(" |\n");
        sb.append("| Половина спреда | ").append(round(offset.halfSpreadBp(), 2)).append(" |\n");
        sb.append("| Снимков | ").append(offset.samples()).append(" |\n\n");
        sb.append("\n**Как читать.** Положительный край пассивной стороны означает, что "
                + "тейкеры площадки в среднем платят за немедленность, а не приносят "
                + "информацию. Тогда наш край — доля общего пирога, и риск у него "
                + "конкурентный: придут мейкеры, спред сузится. Край около нуля или "
                + "отрицательный при нашем положительном означает обратное: мы стоим "
                + "лучше среднего стоящего, и живёт это ровно до того, как он "
                + "выправится.\n\n");

        sb.append("## 2. След резервной ликвидности\n\n");
        sb.append("| Показатель | Значение | Что означает |\n|---|---|---|\n");
        sb.append("| Снимков в выборке | ").append(liquidity.samples()).append(" | — |\n");
        sb.append("| Круглый объём на лучшем биде | ")
                .append(round(100 * liquidity.roundShareBid(), 1)).append("% | ")
                .append("высокая доля = алгоритм, низкая = «сколько было на балансе» |\n");
        sb.append("| Круглый объём на лучшем аске | ")
                .append(round(100 * liquidity.roundShareAsk(), 1)).append("% | то же |\n");
        sb.append("| Круглый объём в сделках | ")
                .append(round(100 * liquidity.roundShareTrades(), 1)).append("% | ")
                .append("для сравнения: так выглядит активная сторона |\n");
        sb.append("| Время жизни лучшего бида: медиана / 90-й | ")
                .append(humanMs(liquidity.medianBestLifeMs())).append(" / ")
                .append(humanMs(liquidity.p90BestLifeMs())).append(" | ")
                .append("секунды = алгоритм, часы = висящие лимитки |\n");
        sb.append("| Смен лучшего бида за окно | ").append(liquidity.bestChanges())
                .append(" | — |\n");
        sb.append("| Симметрия сторон (min/max объёма) | ")
                .append(round(liquidity.sizeSymmetry(), 3)).append(" | ")
                .append("около 1 = двусторонний мейкер, около 0 = односторонняя розница |\n");
        sb.append("| Совпадение объёмов сторон в точности | ")
                .append(round(100 * liquidity.identicalSizeShare(), 2)).append("% | ")
                .append("сильная подпись мейкера |\n\n");

        sb.append("**Оговорка к разделу.** Видна только верхушка книги и только "
                + "исполненные сделки. Про заявки, которые стояли и не исполнились, "
                + "данных нет, поэтому «пассивная сторона» из раздела 1 — это те, кому "
                + "досталась торговля, а не вся резервная ликвидность. Признаки раздела 2 "
                + "косвенные: они сужают гипотезу, но не доказывают её.\n");
        return sb.toString();
    }

    private static String humanMs(double ms) {
        if (Double.isNaN(ms)) {
            return "—";
        }
        if (ms < 60_000) {
            return round(ms / 1000, 1) + " с";
        }
        if (ms < 3_600_000) {
            return round(ms / 60_000, 1) + " мин";
        }
        return round(ms / 3_600_000, 1) + " ч";
    }

    private static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private void write(String out, String markdown) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записать отчёт " + out, e);
        }
    }
}
