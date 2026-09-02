package org.home.data.revx.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.revx.BookFlags;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Базис перпа против спота: во что обходится нейтральность.
 *
 * <b>Зачем.</b> Хедж — единственный механизм, который убирает проблему падения,
 * а не переносит её (док. 127 §8). Но шорт стоит на ДРУГОЙ площадке, и разница
 * цен между ней и нашим спотом — не наша позиция и не наш край, а отдельный риск.
 * Для BTC он измерен разово (док. 122 §П4) и оказался крупным: на падающем окне
 * шум базиса в одну сигму равнялся 66% всей прибыли хеджа. Для ETH и SOL он не
 * мерился вовсе, и это самый крупный недостающий вычет из чисел проекта
 * (док. 127 §12, пункт 3 очередей 132 и 134).
 *
 * <b>Что именно стоит денег.</b> Не уровень базиса, а его ИЗМЕНЕНИЕ за время
 * удержания хеджа. Если перп стабильно на 2 б.п. выше спота, это ничего не
 * стоит: мы открываем и закрываем шорт при одном и том же смещении. Стоит
 * ошибка — то, на сколько смещение уехало между открытием и закрытием. Поэтому
 * главная таблица здесь не «уровень», а «разброс приращений по горизонтам».
 *
 * <b>Опора берётся из USD-ноги, а не из USDC.</b> Перп Kraken котируется в USD,
 * и сравнение с USDC-книгой примешивало бы к базису отклонение стейблкойна.
 * USD-нога той же пары собирается стендом рядом с USDC-ногой, поэтому вычесть
 * стейблкойн ничего не стоит. USDC-нога считается тоже — торгуем-то мы в ней, —
 * и разница между двумя колонками и есть вклад USDC/USD.
 */
@Component
@Lazy
public class PerpBasisReport {

    private static final Logger log = LoggerFactory.getLogger(PerpBasisReport.class);

    private static final String CHARTS = "https://futures.kraken.com/api/charts/v1/mark/";
    /** Горизонты удержания, на которых меряется ошибка хеджа. */
    private static final int[] HORIZON_MIN = {5, 30, 60, 24 * 60};
    /** Свечи тянутся кусками: длинное окно одним запросом отдаётся не целиком. */
    private static final long CHUNK_MS = 12 * 3600_000L;

    private final RevxDb db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public PerpBasisReport(RevxDb db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    /**
     * Порог «книга сломана»: относительный спред опоры, выше которого середина
     * перестаёт быть ценой.
     *
     * ⚠️ Без этого фильтра отчёт меряет не базис, а ширину нашего же стакана.
     * 22.08.2026 спред SOL/USD раздувался до **870 б.п. при норме 4**, и
     * «базис» на этих минутах доходил до −1185 б.п. Середина книги шириной в
     * восемь процентов — не цена, и разность с ней не риск хеджа. Первый прогон
     * этого отчёта дал по SOL σ = 148 б.п. за сутки ровно из-за таких минут.
     */
    private static final double BROKEN_SPREAD_BP = 50;

    /** Базис одной пары: ряд в б.п. по минутам плюс подписи. */
    private record Series(String symbol, String perp, TreeMap<Long, Double> usd,
                          TreeMap<Long, Double> usdcAll, TreeMap<Long, Double> usdAll,
                          int spotMinutes, int perpMinutes, int brokenMinutes) {
    }

    public void run(List<String> symbols, int hours, long toMs, String out) {
        long fromMs = toMs - hours * 3600_000L;
        List<Series> all = new ArrayList<>();
        for (String symbol : symbols) {
            Series s = measure(symbol.trim(), fromMs, toMs);
            if (s != null) {
                all.add(s);
            }
        }
        if (all.isEmpty()) {
            log.warn("ни по одной паре базис не посчитан");
            return;
        }
        write(out, render(all, hours, fromMs, toMs));
        log.info("базис перпа: {} пар → {}", all.size(), out);
    }

    // --- измерение ------------------------------------------------------------

    private Series measure(String symbol, long fromMs, long toMs) {
        String base = symbol.substring(0, symbol.indexOf('/'));
        String perp = "PF_" + ("BTC".equals(base) ? "XBT" : base) + "USD";

        // ⚠️ У опорной ноги СВОЙ символ, а не тот же с другим значением leg:
        // в revx_book лежат строки `BTC/USD`+`usd` и `BTC/USDC`+`usdc`. Запрос
        // `symbol='BTC/USDC' AND leg='usd'` не находит ничего и молча даёт
        // пустой ряд — на этом первый прогон и выдал NaN по всем парам.
        TreeMap<Long, Double> spotUsdAll = spotByMinute(base + "/USD", "usd", fromMs, toMs, false);
        TreeMap<Long, Double> spotUsd = spotByMinute(base + "/USD", "usd", fromMs, toMs, true);
        TreeMap<Long, Double> spotUsdc = spotByMinute(symbol, "usdc", fromMs, toMs, true);
        if (spotUsdAll.isEmpty() && spotUsdc.isEmpty()) {
            log.warn("{}: спота в окне нет", symbol);
            return null;
        }
        TreeMap<Long, Double> mark = markByMinute(perp, fromMs, toMs);
        if (mark.isEmpty()) {
            log.warn("{}: марк-цены {} нет", symbol, perp);
            return null;
        }
        return new Series(symbol, perp, basis(mark, spotUsd), basis(mark, spotUsdc),
                basis(mark, spotUsdAll), spotUsdAll.size(), mark.size(),
                spotUsdAll.size() - spotUsd.size());
    }

    /** Базис в б.п.: перп минус спот, на общих минутах. */
    private static TreeMap<Long, Double> basis(TreeMap<Long, Double> mark,
                                               TreeMap<Long, Double> spot) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map.Entry<Long, Double> e : spot.entrySet()) {
            Double m = mark.get(e.getKey());
            if (m != null && e.getValue() > 0) {
                out.put(e.getKey(), (m - e.getValue()) / e.getValue() * 10_000);
            }
        }
        return out;
    }

    /**
     * Спот по минутам: последняя середина книги внутри минуты.
     *
     * Последняя, а не первая: марк-свеча Kraken закрывается концом минуты, и
     * сравнивать надо сопоставимые моменты. Смещение на полминуты добавило бы к
     * базису чистый шум цены.
     */
    private TreeMap<Long, Double> spotByMinute(String symbol, String leg, long fromMs, long toMs,
                                               boolean dropBroken) {
        TreeMap<Long, Double> out = new TreeMap<>();
        db.query("SELECT t_recv_ms, flags, bp1, ap1 FROM revx_book WHERE symbol = ? AND leg = ? "
                + "AND t_recv_ms BETWEEN ? AND ? ORDER BY t_recv_ms", rs -> {
            int flags = rs.getInt("flags");
            if (BookFlags.has(flags, BookFlags.CROSSED) || BookFlags.has(flags, BookFlags.EMPTY_SIDE)) {
                return null;
            }
            double bp = rs.getDouble("bp1");
            double ap = rs.getDouble("ap1");
            if (!(bp > 0) || !(ap > 0)) {
                return null;
            }
            double mid = (bp + ap) / 2;
            if (dropBroken && (ap - bp) / mid * 10_000 > BROKEN_SPREAD_BP) {
                return null;                  // книга шире порога — середина не цена
            }
            out.put(rs.getLong("t_recv_ms") / 60_000L * 60_000L, mid);
            return null;
        }, symbol, leg, fromMs, toMs);
        return out;
    }

    private TreeMap<Long, Double> markByMinute(String perp, long fromMs, long toMs) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (long start = fromMs; start < toMs; start += CHUNK_MS) {
            long end = Math.min(toMs, start + CHUNK_MS);
            String url = CHARTS + perp + "/1m?from=" + start / 1000 + "&to=" + end / 1000;
            try {
                JsonNode root = mapper.readTree(api.get(url));
                for (JsonNode c : root.path("candles")) {
                    double close = c.path("close").asDouble(0);
                    if (close > 0) {
                        out.put(c.path("time").asLong(), close);
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.error("марк-цена {} за кусок {}: {}", perp, Instant.ofEpochMilli(start), e.toString());
            }
        }
        return out;
    }

    // --- отчёт ----------------------------------------------------------------

    private String render(List<Series> all, int hours, long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Базис перпа против спота: цена нейтральности по парам\n\n");
        sb.append("Пункт 3 очередей док. 132 и 134, открыт с док. 127 §12. Для BTC базис "
                + "измерялся разово (док. 122 §П4), для ETH и SOL — не измерялся вовсе, и это "
                + "самый крупный недостающий вычет из чисел проекта: **на падающем окне шум "
                + "базиса в одну сигму равнялся 66% всей прибыли хеджа**.\n\n");
        sb.append("**Стоит денег не уровень базиса, а его ИЗМЕНЕНИЕ за время удержания.** "
                + "Постоянное смещение перпа относительно спота ничего не стоит: шорт "
                + "открывается и закрывается при одном и том же смещении. Стоит то, на сколько "
                + "смещение уехало между открытием и закрытием — поэтому главная таблица здесь "
                + "не «уровень», а «разброс приращений».\n\n");
        sb.append("Опора — **USD-нога** той же пары: перп Kraken котируется в USD, и сравнение "
                + "с USDC-книгой примешало бы к базису отклонение стейблкойна. USDC-нога "
                + "посчитана рядом, потому что торгуем мы в ней; разница колонок и есть вклад "
                + "USDC/USD.\n\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(fromMs)).append(" — ")
                .append(Instant.ofEpochMilli(toMs)).append(" (").append(hours).append(" ч) |\n|---|---|\n");
        sb.append("| Пар | ").append(all.size()).append(" |\n\n");
        sb.append("| Пара | Минут спота | Минут со сломанной книгой (спред > ")
                .append(round(BROKEN_SPREAD_BP, 0)).append(" б.п.) |\n|---|---|---|\n");
        for (Series s : all) {
            sb.append("| ").append(s.symbol()).append(" | ").append(s.spotMinutes())
                    .append(" | ").append(s.brokenMinutes()).append(" (")
                    .append(round(100.0 * s.brokenMinutes() / Math.max(1, s.spotMinutes()), 1))
                    .append("%) |\n");
        }
        sb.append("\n");

        sb.append("## 1. Уровень базиса\n\n");
        sb.append("| Пара | Перп | Минут | Среднее, б.п. | Медиана | σ | 5-й … 95-й "
                + "| То же против USDC-ноги |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Series s : all) {
            double[] v = values(s.usd());
            double[] u = values(s.usdcAll());
            sb.append("| ").append(s.symbol()).append(" | ").append(s.perp())
                    .append(" | ").append(v.length)
                    .append(" | ").append(round(mean(v), 2))
                    .append(" | ").append(round(median(v), 2))
                    .append(" | ").append(round(sd(v), 2))
                    .append(" | ").append(round(pct(v, 0.05), 1)).append(" … ")
                    .append(round(pct(v, 0.95), 1))
                    .append(" | ").append(u.length == 0 ? "—"
                            : round(mean(u), 2) + " ± " + round(sd(u), 2))
                    .append(" |\n");
        }
        sb.append("\nБлизкий к нулю уровень означает «перп идёт за спотом» и сам по себе "
                + "хорош, но за него не платят. Разница между двумя последними колонками — "
                + "это отклонение USDC от доллара, и оно входит в наш результат отдельно от "
                + "базиса.\n\n");

        sb.append("## 2. Ошибка хеджа: разброс приращений по горизонтам\n\n");
        sb.append("Вот это и есть цена нейтральности. Читается так: держим шорт `t` минут — "
                + "получаем ошибку такого размера в базисных пунктах номинала.\n\n");
        sb.append("⚠️ **Минуты со сломанной книгой выброшены.** Если спред опоры шире ")
                .append(round(BROKEN_SPREAD_BP, 0)).append(" б.п., её середина — не цена, и "
                        + "разность с ней не риск хеджа, а ширина нашего же стакана. Колонка "
                        + "«без фильтра» показывает, во что обходится этот недосмотр.\n\n");
        sb.append("| Пара | Горизонт | **σ приращения, б.п.** | Средний модуль | 95-й | Максимум "
                + "| σ БЕЗ фильтра |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Series s : all) {
            for (int h : HORIZON_MIN) {
                double[] d = deltas(s.usd(), h);
                if (d.length < 10) {
                    continue;
                }
                double[] abs = Arrays.stream(d).map(Math::abs).sorted().toArray();
                double[] raw = deltas(s.usdAll(), h);
                sb.append("| ").append(s.symbol())
                        .append(" | ").append(h < 60 ? h + " мин" : (h / 60) + " ч")
                        .append(" | **").append(round(sd(d), 1)).append("**")
                        .append(" | ").append(round(mean(abs), 1))
                        .append(" | ").append(round(pct(abs, 0.95), 1))
                        .append(" | ").append(round(abs[abs.length - 1], 1))
                        .append(" | ").append(raw.length < 10 ? "—" : round(sd(raw), 1))
                        .append(" |\n");
            }
        }
        sb.append("\n**Ключевой вопрос — растёт ли разброс с горизонтом.** Если растёт как "
                + "корень из времени, базис блуждает, и ошибка накапливается без предела: "
                + "держать хедж долго нельзя. Если почти не растёт, базис возвратный, ошибка "
                + "ограничена, и период ребалансировки можно выбирать по стоимости комиссий, "
                + "а не по риску.\n\n");

        sb.append("## 3. Кластеризация по суткам\n\n");
        sb.append("Средняя сигма не описывает риск, если разброс собран в несколько дней. "
                + "И это не гипотеза: у BTC (док. 122 §П4) два дня из четырнадцати имели "
                + "разброс в шесть-восемь раз выше обычного, причём это были дни рыночного "
                + "стресса — то есть базис расходится тогда же, когда мы несём максимальный "
                + "инвентарь. **Риски складываются, а не независимы.**\n\n");
        sb.append("| Пара | Сутки | Точек | σ уровня | Мин | Макс |\n|---|---|---|---|---|---|\n");
        for (Series s : all) {
            Map<String, List<Double>> byDay = new LinkedHashMap<>();
            for (Map.Entry<Long, Double> e : s.usd().entrySet()) {
                String day = Instant.ofEpochMilli(e.getKey()).atZone(ZoneOffset.UTC)
                        .toLocalDate().toString();
                byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(e.getValue());
            }
            for (Map.Entry<String, List<Double>> e : byDay.entrySet()) {
                double[] v = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
                if (v.length < 60) {
                    continue;
                }
                sb.append("| ").append(s.symbol()).append(" | ").append(e.getKey())
                        .append(" | ").append(v.length)
                        .append(" | **").append(round(sd(v), 1)).append("**")
                        .append(" | ").append(round(Arrays.stream(v).min().orElse(Double.NaN), 1))
                        .append(" | ").append(round(Arrays.stream(v).max().orElse(Double.NaN), 1))
                        .append(" |\n");
            }
        }

        sb.append("\n## 4. Во что это обходится\n\n");
        sb.append("Ошибка в деньгах = σ приращения × номинал хеджа. Номинал хеджа равен "
                + "несомому инвентарю, поэтому таблица считается на **$1 000 инвентаря** — "
                + "умножайте на свой масштаб.\n\n");
        sb.append("| Пара | σ за 1 мин | σ за 1 ч | Ошибка 1σ на $1 000 при часовом хедже "
                + "| При минутном |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Series s : all) {
            double[] d1 = deltas(s.usd(), 1);
            double[] d60 = deltas(s.usd(), 60);
            if (d60.length < 10) {
                continue;
            }
            sb.append("| ").append(s.symbol())
                    .append(" | ").append(d1.length < 10 ? "—" : round(sd(d1), 1))
                    .append(" | ").append(round(sd(d60), 1))
                    .append(" | **$").append(round(sd(d60) / 10_000 * 1000, 2)).append("**")
                    .append(" | ").append(d1.length < 10 ? "—"
                            : "$" + round(sd(d1) / 10_000 * 1000, 2))
                    .append(" |\n");
        }
        sb.append("\nСравнивать это надо не с оборотом, а с **прибылью хеджированной "
                + "конструкции за то же время**: если ошибка одной сигмы сопоставима с ней, "
                + "нейтральность куплена ценой другого риска того же размера, и выигрыша "
                + "нет — есть замена одного риска другим.\n");
        return sb.toString();
    }

    // --- арифметика -----------------------------------------------------------

    /** Приращения базиса за горизонт: пропуски минут не сшиваются. */
    private static double[] deltas(TreeMap<Long, Double> series, int horizonMin) {
        List<Double> out = new ArrayList<>();
        long step = horizonMin * 60_000L;
        for (Map.Entry<Long, Double> e : series.entrySet()) {
            Double later = series.get(e.getKey() + step);
            if (later != null) {
                out.add(later - e.getValue());
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] values(TreeMap<Long, Double> s) {
        return s.values().stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double mean(double[] v) {
        return v.length == 0 ? Double.NaN : Arrays.stream(v).average().orElse(Double.NaN);
    }

    private static double sd(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    private static double median(double[] v) {
        return pct(v, 0.5);
    }

    private static double pct(double[] v, double q) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        return c[Math.min(c.length - 1, Math.max(0, (int) Math.round(q * (c.length - 1))))];
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private void write(String out, String md) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записать отчёт " + out, e);
        }
    }
}
