package org.home.data.revx.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Минутные марк-цены перпа Kraken с кэшем в базе стенда (док. 142 §8).
 *
 * <b>Зачем отдельный класс.</b> До сих пор хедж в симуляторе переоценивался по
 * СПОТУ, то есть базис молчаливо считался тождественно нулевым. Вся проблема
 * дислокации ровно в том, что он не ноль: 22.08.2026 марка двадцать минут
 * стояла на 2.35% от спота (док. 138 §5). Считать хедж по споту — значит
 * вычесть из результата ноль там, где стоит проценты.
 *
 * <b>Почему с кэшем.</b> Ряд приходит из сети, а прогон симулятора обязан быть
 * воспроизводимым (ТЗ §7). Первый прогон наполняет таблицу, последующие читают
 * из неё и в сеть не ходят вовсе. Ключ — «перп + минута», поэтому повторная
 * загрузка того же окна ничего не дублирует.
 *
 * <b>Дырки не достраиваются.</b> Пропущенная минута остаётся пропущенной, и
 * потребитель берёт последнюю известную марку (floorEntry). Интерполяция
 * нарисовала бы базис там, где данных нет, — а именно в разрывах данных базис и
 * ведёт себя хуже всего.
 */
@Component
@Lazy
public class PerpMarkSource {

    private static final Logger log = LoggerFactory.getLogger(PerpMarkSource.class);

    private static final String CHARTS = "https://futures.kraken.com/api/charts/v1/mark/";

    /** Kraken отдаёт ограниченное число свечей за запрос — окно режется на куски. */
    private static final long CHUNK_MS = 12 * 3600_000L;

    private final RevxDb db;
    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public PerpMarkSource(RevxDb db, ApiClient api) {
        this.db = db;
        this.api = api;
    }

    /**
     * Ряд марок за окно. Сначала читает кэш; если покрытие ниже
     * {@code minCoverage} долей ожидаемых минут — дозагружает и перечитывает.
     *
     * @param perp символ Kraken, например {@code PF_XBTUSD}
     */
    public NavigableMap<Long, Double> marks(String perp, long fromMs, long toMs) {
        NavigableMap<Long, Double> cached = fromCache(perp, fromMs, toMs);
        long expected = Math.max(1, (toMs - fromMs) / 60_000L);
        double coverage = (double) cached.size() / expected;
        if (coverage >= 0.9) {
            log.info("марки {}: {} минут из кэша ({}% окна)", perp, cached.size(),
                    Math.round(coverage * 100));
            return cached;
        }
        log.info("марки {}: в кэше {} из ~{} минут — догружаю с Kraken",
                perp, cached.size(), expected);
        fetchInto(perp, fromMs, toMs);
        NavigableMap<Long, Double> after = fromCache(perp, fromMs, toMs);
        log.info("марки {}: после загрузки {} минут ({}% окна)", perp, after.size(),
                Math.round(100.0 * after.size() / expected));
        return after;
    }

    private NavigableMap<Long, Double> fromCache(String perp, long fromMs, long toMs) {
        TreeMap<Long, Double> out = new TreeMap<>();
        db.query("SELECT ts_ms, mark FROM revx_perp_mark WHERE perp = ? "
                + "AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms", rs -> {
            out.put(rs.getLong("ts_ms"), rs.getDouble("mark"));
            return null;
        }, perp, fromMs, toMs);
        return out;
    }

    private void fetchInto(String perp, long fromMs, long toMs) {
        List<Object[]> rows = new ArrayList<>();
        for (long start = fromMs; start < toMs; start += CHUNK_MS) {
            long end = Math.min(toMs, start + CHUNK_MS);
            String url = CHARTS + perp + "/1m?from=" + start / 1000 + "&to=" + end / 1000;
            try {
                JsonNode root = mapper.readTree(api.get(url));
                for (JsonNode c : root.path("candles")) {
                    double close = c.path("close").asDouble(0);
                    if (close > 0) {
                        rows.add(new Object[]{perp, c.path("time").asLong(), close});
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Изоляция сбоев (принцип 3): кусок не приехал — остальные грузим.
                log.error("марк-цена {} за кусок {}: {}", perp, Instant.ofEpochMilli(start),
                        e.toString());
            }
        }
        if (!rows.isEmpty()) {
            db.batch("INSERT INTO revx_perp_mark(perp, ts_ms, mark) VALUES (?, ?, ?) "
                    + "ON CONFLICT(perp, ts_ms) DO UPDATE SET mark = excluded.mark", rows);
        }
    }

    /** Символ перпа Kraken по базовой валюте пары стенда. У биткойна он XBT. */
    public static String perpFor(String base) {
        return "PF_" + ("BTC".equals(base) ? "XBT" : base) + "USD";
    }

    /**
     * Марки перпа, ПЕРЕСЧИТАННЫЕ В USDC.
     *
     * <b>Зачем это отдельный метод.</b> Перп Kraken котируется в USD, а
     * справедливая цена симулятора — {@code fairUsdc}, в USDC. Сравнение их
     * напрямую даёт не базис перпа, а базис ПЛЮС отклонение стейблкойна, и
     * второе слагаемое крупнее первого на порядок: на окне 28.08–01.09 базис
     * BTC к USD-ноге был 0.61 ± 1.0 б.п., а к USDC-ноге — −5.34 ± 5.59.
     *
     * Из-за этого предохранитель по дислокации в док. 146 §4 срабатывал 188 раз
     * там, где базис перпа не превышал порога НИ РАЗУ: он ловил USDC/USD, а не
     * разъезд перпа. Величина реальная, но это другой риск и другое обоснование:
     * возвратность из док. 138 §3 доказана для базиса перпа, а не для
     * стейблкойна.
     *
     * Курс берётся поминутно из книги стенда как {@code mid(BASE/USDC) /
     * mid(BASE/USD)} — то есть из тех же данных, на которых считается
     * справедливая цена. Минуты, где какой-то из ног нет, выпадают: подставлять
     * туда единицу значит молча объявить USDC равным доллару ровно тогда, когда
     * это меньше всего известно.
     */
    public NavigableMap<Long, Double> marksInQuote(String perp, String base,
                                                   long fromMs, long toMs) {
        NavigableMap<Long, Double> usd = marks(perp, fromMs, toMs);
        TreeMap<Long, Double> rate = usdcPerUsd(base, fromMs, toMs);
        if (rate.isEmpty()) {
            log.warn("{}: курса USDC/USD нет — марки остаются в USD, базис включит стейблкойн",
                    base);
            return usd;
        }
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map.Entry<Long, Double> e : usd.entrySet()) {
            Map.Entry<Long, Double> r = rate.floorEntry(e.getKey());
            if (r != null && r.getValue() > 0) {
                out.put(e.getKey(), e.getValue() * r.getValue());
            }
        }
        log.info("марки {} пересчитаны в USDC: {} минут из {} (курс по {} минутам)",
                perp, out.size(), usd.size(), rate.size());
        return out;
    }

    /** Поминутный курс USDC за доллар: отношение середин USDC- и USD-ноги пары. */
    private TreeMap<Long, Double> usdcPerUsd(String base, long fromMs, long toMs) {
        TreeMap<Long, Double> usdc = midByMinute(base + "/USDC", "usdc", fromMs, toMs);
        TreeMap<Long, Double> usd = midByMinute(base + "/USD", "usd", fromMs, toMs);
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map.Entry<Long, Double> e : usdc.entrySet()) {
            Double u = usd.get(e.getKey());
            if (u != null && u > 0) {
                out.put(e.getKey(), e.getValue() / u);
            }
        }
        return out;
    }

    /** Последняя середина книги внутри минуты; сломанные снимки отбрасываются. */
    private TreeMap<Long, Double> midByMinute(String symbol, String leg, long fromMs, long toMs) {
        TreeMap<Long, Double> out = new TreeMap<>();
        db.query("SELECT t_recv_ms, flags, bp1, ap1 FROM revx_book WHERE symbol = ? AND leg = ? "
                + "AND t_recv_ms BETWEEN ? AND ? ORDER BY t_recv_ms", rs -> {
            int flags = rs.getInt("flags");
            if (org.home.data.revx.BookFlags.has(flags, org.home.data.revx.BookFlags.CROSSED)
                    || org.home.data.revx.BookFlags.has(flags,
                            org.home.data.revx.BookFlags.EMPTY_SIDE)) {
                return null;
            }
            double bp = rs.getDouble("bp1");
            double ap = rs.getDouble("ap1");
            if (!(bp > 0) || !(ap > 0)) {
                return null;
            }
            out.put(rs.getLong("t_recv_ms") / 60_000L * 60_000L, (bp + ap) / 2);
            return null;
        }, symbol, leg, fromMs, toMs);
        return out;
    }

    /**
     * Базис в б.п. по общим минутам — для отчёта, не для прогона.
     *
     * Считается только там, где есть ОБЕ цены: подставлять последнюю известную
     * марку в статистику базиса значит мерить не разъезд, а возраст данных.
     */
    public static TreeMap<Long, Double> basisBp(NavigableMap<Long, Double> mark,
                                                NavigableMap<Long, Double> spot) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map.Entry<Long, Double> e : spot.entrySet()) {
            Double m = mark.get(e.getKey());
            if (m != null && e.getValue() > 0) {
                out.put(e.getKey(), (m - e.getValue()) / e.getValue() * 10_000);
            }
        }
        return out;
    }
}
