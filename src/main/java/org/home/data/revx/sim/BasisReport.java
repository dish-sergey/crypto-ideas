package org.home.data.revx.sim;

import org.home.data.revx.RevxConfig;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Отчёт по базису: курс USDC/USD, его устойчивость и пригодность каждой пары
 * к котированию. Это шаг 4 плана и заготовка разделов §5.1–5.2 отчёта ТЗ.
 *
 * Считается на собранных данных, по правилу available_at: курс на момент t
 * строится только из снимков, полученных к моменту t.
 */
@Component
@Lazy
public class BasisReport {

    private static final Logger log = LoggerFactory.getLogger(BasisReport.class);

    private final SnapshotReader reader;
    private final RevxConfig cfg;

    public BasisReport(SnapshotReader reader, RevxConfig cfg) {
        this.reader = reader;
        this.cfg = cfg;
    }

    public void run(int hours, long bucketMs, String out) {
        run(hours, bucketMs, System.currentTimeMillis(), out);
    }

    /** Конец окна задаётся явно по той же причине, что и в {@code SimRunner}. */
    public void run(int hours, long bucketMs, long toMs, String out) {
        long fromMs = toMs - hours * 3600_000L;

        SnapshotReader.Window window = reader.read(fromMs, toMs, cfg.fairMaxSkewMs());
        var buckets = SnapshotReader.bucket(window.quotes(), bucketMs);
        if (buckets.isEmpty()) {
            log.warn("за последние {} ч нет парных снимков — сбор ещё не накопил данных", hours);
            return;
        }

        FairPrice.Limits limits = new FairPrice.Limits(cfg.fairMinPairs(), cfg.fairMaxDispersionPct(),
                cfg.fairMaxReferenceSpreadPct(), cfg.fairMaxResidualPct());

        List<Double> rates = new ArrayList<>(buckets.size());
        List<Double> dispersions = new ArrayList<>(buckets.size());
        int unreliableBuckets = 0;
        Map<String, PairStats> perPair = new TreeMap<>();

        for (var bucket : buckets) {
            FairPrice.Result result = FairPrice.compute(bucket.getValue(), limits);
            rates.add(result.rate());
            dispersions.add(result.dispersionPct());
            if (!result.reliable()) {
                unreliableBuckets++;
            }
            for (PairQuote q : bucket.getValue()) {
                PairStats stats = perPair.computeIfAbsent(q.base(), PairStats::new);
                FairPrice.PairState state = result.pair(q.base());
                stats.add(q, state);
            }
        }

        Map<String, Integer> emptyBooks = reader.anomalyCountsByBase(fromMs, toMs, "empty_side");
        int crossedBooks = reader.anomalyTotal(fromMs, toMs, "crossed_book");
        String markdown = render(hours, fromMs, toMs, bucketMs, window, buckets.size(),
                rates, dispersions, unreliableBuckets, perPair, limits, emptyBooks, crossedBooks);
        write(out, markdown);
        log.info("отчёт по базису: {} корзин, курс {} (медиана), ненадёжен в {}% времени → {}",
                buckets.size(), round(median(rates), 5),
                round(100.0 * unreliableBuckets / buckets.size(), 1), out);
    }

    /** Накопитель по паре: спреды, остаток и доля времени, когда котировать нельзя. */
    private static final class PairStats {
        final String base;
        final List<Double> spreadUsdc = new ArrayList<>();
        final List<Double> spreadUsd = new ArrayList<>();
        final List<Double> residualPct = new ArrayList<>();
        final Map<String, Integer> pauseReasons = new LinkedHashMap<>();
        int observations;
        int paused;

        PairStats(String base) {
            this.base = base;
        }

        void add(PairQuote quote, FairPrice.PairState state) {
            observations++;
            spreadUsdc.add(100.0 * quote.spreadUsdc());
            spreadUsd.add(100.0 * quote.spreadUsd());
            if (state != null) {
                residualPct.add(state.residualPct());
                if (!state.quotable()) {
                    paused++;
                    // причина укорачивается до вида, а не до конкретных чисел
                    String kind = state.pausedReason().split(":")[0];
                    pauseReasons.merge(kind, 1, Integer::sum);
                }
            }
        }

        double pausedShare() {
            return observations == 0 ? 0 : 100.0 * paused / observations;
        }
    }

    private String render(int hours, long fromMs, long toMs, long bucketMs,
                          SnapshotReader.Window window, int buckets,
                          List<Double> rates, List<Double> dispersions, int unreliableBuckets,
                          Map<String, PairStats> perPair, FairPrice.Limits limits,
                          Map<String, Integer> emptyBooks, int crossedBooks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Базис USDC/USD и пригодность пар — стенд Revolut X\n\n");
        sb.append("Автоотчёт, шаг 4 плана `plans/2026-08-19-revx-mm-stand.md`. ")
                .append("Курс на момент t строится только из снимков, доступных к моменту t.\n\n");

        sb.append("## Данные\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(fromMs)).append(" — ")
                .append(Instant.ofEpochMilli(toMs)).append(" (").append(hours).append(" ч) |\n");
        sb.append("| Парных снимков | ").append(window.stats().pairsTotal()).append(" |\n");
        sb.append("| Отброшено по skew (> ").append(cfg.fairMaxSkewMs()).append(" мс) | ")
                .append(window.stats().droppedBySkew()).append(" (")
                .append(round(share(window.stats().droppedBySkew(), window.stats().pairsTotal()), 2))
                .append("%) |\n");
        sb.append("| Отброшено по флагам книги | ").append(window.stats().droppedByFlags()).append(" |\n");
        sb.append("| Пустых ответов книги (`asks:[], bids:[]`) | ")
                .append(emptyBooks.values().stream().mapToInt(Integer::intValue).sum()).append(" |\n");
        sb.append("| Перекрещенных книг (best_ask ≤ best_bid) | ").append(crossedBooks).append(" |\n");
        sb.append("| Корзин по ").append(bucketMs / 1000).append(" с | ").append(buckets).append(" |\n\n");
        sb.append("Пустые и перекрещенные ответы в базу не пишутся — они существуют только ")
                .append("в журнале аномалий, поэтому и вынесены сюда (ТЗ §8: аномалия обязана дойти до отчёта).\n\n");

        sb.append("## Курс USDC/USD\n\n");
        sb.append("| Показатель | Значение |\n|---|---|\n");
        sb.append("| Медиана курса | ").append(round(median(rates), 5)).append(" |\n");
        sb.append("| 5-й / 95-й процентиль | ").append(round(percentile(rates, 0.05), 5))
                .append(" / ").append(round(percentile(rates, 0.95), 5)).append(" |\n");
        sb.append("| Отклонение медианы от 1.0 | ")
                .append(round(100.0 * (median(rates) - 1), 3)).append("% |\n");
        sb.append("| Разброс implied (медиана MAD) | ")
                .append(round(median(dispersions), 4)).append("% |\n");
        sb.append("| Доля времени с ненадёжным курсом | ")
                .append(round(100.0 * unreliableBuckets / buckets, 2)).append("% |\n\n");
        sb.append("Пороги: минимум пар ").append(limits.minPairs())
                .append(", разброс ≤ ").append(limits.maxDispersionPct())
                .append("%, спред опоры ≤ ").append(limits.maxReferenceSpreadPct())
                .append("%, остаток ≤ ±").append(limits.maxResidualPct()).append("%.\n\n");

        sb.append("## По парам\n\n");
        sb.append("| Пара | Спред USDC, % | Спред USD, % | Отношение | Остаток, % | Пустых книг | Нельзя котировать | Причина |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        List<PairStats> sorted = new ArrayList<>(perPair.values());
        sorted.sort(Comparator.comparingDouble(PairStats::pausedShare)
                .thenComparing(s -> -median(s.spreadUsdc)));
        for (PairStats s : sorted) {
            double usdc = median(s.spreadUsdc);
            double usd = median(s.spreadUsd);
            sb.append("| ").append(s.base)
                    .append(" | ").append(round(usdc, 3))
                    .append(" | ").append(round(usd, 3))
                    .append(" | ").append(usd > 0 ? round(usdc / usd, 1) : "-")
                    .append(" | ").append(round(median(s.residualPct), 3))
                    .append(" | ").append(emptyBooks.getOrDefault(s.base, 0))
                    .append(" | ").append(round(s.pausedShare(), 1)).append("%")
                    .append(" | ").append(s.pauseReasons.isEmpty() ? "—" : top(s.pauseReasons))
                    .append(" |\n");
        }

        sb.append("\n## Как это читать\n\n");
        sb.append("- **Отношение спредов** — во сколько раз книга USDC шире опорной. ")
                .append("Ради этого разрыва стенд и строился (док. 62 §2): чем больше, тем больше ниша.\n");
        sb.append("- **Остаток** — отклонение implied по паре от общего курса. ")
                .append("Это либо локальная возможность, либо поломка опоры; отличает их спред опоры.\n");
        sb.append("- **Нельзя котировать** — доля времени, когда сработал предохранитель. ")
                .append("Пара с широкой опорой непригодна, даже если её собственный спред заманчив.\n");
        return sb.toString();
    }

    private static String top(Map<String, Integer> reasons) {
        return reasons.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .orElse("—");
    }

    private void write(String out, String markdown) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записался отчёт " + out, e);
        }
    }

    private static double share(int part, int total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }

    private static double median(List<Double> values) {
        return FairPrice.median(values);
    }

    private static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }
}
