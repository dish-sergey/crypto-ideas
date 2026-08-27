package org.home.data.revx.exec;

import org.home.data.revx.sim.FairPrice;
import org.home.data.revx.sim.PairQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Справедливая цена — ИЗ БАЗЫ СТЕНДА, только на чтение.
 *
 * Почему не опрашивать рынок самому. Дело не в экономии запросов, а в
 * интерпретируемости: если исполнитель возьмёт свою цену в свой момент, любое
 * расхождение с моделью можно будет списать на разные данные, и вся затея с
 * микро-live потеряет смысл. Читая ту же строку той же базы, что и симулятор,
 * исполнитель делает расхождение однозначным — оно может означать только
 * маршрутизацию.
 *
 * Соединение открывается с {@code mode=ro}: разделение стенда и исполнителя
 * (ТЗ §0) держится на флаге драйвера, а не на дисциплине. Данные книги
 * невосполнимы, и права на запись у торгующего процесса быть не должно.
 *
 * Запрос повторяет {@code SnapshotReader}: пара собирается из ДВУХ ног одного
 * снимка, курс USDC/USD считается по всей вселенной, и уже он даёт справедливую
 * цену конкретной пары.
 */
public final class StandReader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StandReader.class);

    /**
     * Последний снимок ОДНОГО символа. Запрос точечный намеренно.
     *
     * Первая версия читала весь срез одним запросом с условием
     * {@code WHERE t_recv_ms BETWEEN ? AND ?} — и это оказалось полным сканом
     * таблицы на пять миллионов строк каждую секунду: индекса по одному
     * {@code t_recv_ms} нет, а составной {@code (symbol, t_recv_ms)} по нему
     * одному не работает. Цикл съедал 84% ядра на машине, которая в это же время
     * собирает данные, — то есть мешал ровно тому, ради чего всё делается.
     *
     * Здесь используется первичный ключ {@code (symbol, t_sent_ms)}: сорок шесть
     * точечных выборок вместо одного скана.
     */
    private static final String SELECT_LATEST = """
            SELECT snap_id, ap1, bp1, skew_ms, t_recv_ms
            FROM revx_book
            WHERE symbol = ? AND t_sent_ms >= ?
            ORDER BY t_sent_ms DESC
            LIMIT 1
            """;

    private static final String SELECT_UNIVERSE = """
            SELECT DISTINCT substr(symbol, 1, instr(symbol, '/') - 1) AS base
            FROM revx_pair
            WHERE status = 'active' AND symbol LIKE '%/USDC'
            """;

    /** Справедливая цена пары и всё, что нужно, чтобы решить — котировать ли. */
    public record Fair(double price, boolean quotable, String pausedReason,
                       long asOfMs, int pairsUsed) {
    }

    private final Connection connection;
    private final List<String> memecoins;
    private final FairPrice.Limits limits;
    private final long maxSkewMs;
    private volatile List<String> universe;

    public StandReader(String dbPath, List<String> memecoins, FairPrice.Limits limits,
                       long maxSkewMs) {
        this.memecoins = memecoins;
        this.limits = limits;
        this.maxSkewMs = maxSkewMs;
        try {
            // mode=ro — драйвер физически не даст записать в базу стенда.
            connection = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
            log.info("база стенда открыта на чтение: {}", dbPath);
        } catch (Exception e) {
            throw new IllegalStateException("не открыть базу стенда " + dbPath + " на чтение", e);
        }
    }

    /**
     * @param base       базовая валюта пары, например {@code BTC}
     * @param lookbackMs сколько последних миллисекунд смотреть; берётся ПОСЛЕДНИЙ
     *                   снимок, а окно нужно лишь чтобы не читать всю базу
     */
    public Fair latest(String base, long lookbackMs) {
        long since = System.currentTimeMillis() - lookbackMs;
        List<PairQuote> quotes = new ArrayList<>();
        long asOf = 0;
        for (String pairBase : universe()) {
            Leg usdc = leg(pairBase + "/USDC", since);
            Leg usd = leg(pairBase + "/USD", since);
            if (usdc == null || usd == null) {
                continue;                            // одной ноги нет — пара не в счёт
            }
            // Ноги берутся из разных запросов, поэтому расхождение считается по
            // времени получения, а не по полю snap_id: снимки могут оказаться
            // из соседних циклов, и молча склеивать их нельзя.
            long skew = usdc.snapId == usd.snapId
                    ? Math.max(usdc.skewMs, usd.skewMs)
                    : Math.abs(usdc.recvMs - usd.recvMs);
            if (skew > maxSkewMs) {
                continue;
            }
            quotes.add(new PairQuote(pairBase, usdc.mid(), usd.mid(),
                    usdc.spread(), usd.spread(), memecoins.contains(pairBase),
                    Math.max(usdc.recvMs, usd.recvMs)));
            asOf = Math.max(asOf, Math.max(usdc.recvMs, usd.recvMs));
        }

        if (quotes.isEmpty()) {
            return new Fair(0, false, "снимков нет — сбор стоит?", 0, 0);
        }
        FairPrice.Result result = FairPrice.compute(quotes, limits);
        FairPrice.PairState state = result.pair(base);
        if (state == null) {
            return new Fair(0, false, "пары " + base + " нет в последнем срезе", asOf, quotes.size());
        }
        return new Fair(state.fairUsdc(), state.quotable(), state.pausedReason(),
                asOf, quotes.size());
    }

    /** Одна нога последнего снимка символа. */
    private record Leg(long snapId, double ask, double bid, long skewMs, long recvMs) {

        double mid() {
            return (ask + bid) / 2;
        }

        double spread() {
            double mid = mid();
            return mid > 0 ? (ask - bid) / mid : Double.NaN;
        }
    }

    private Leg leg(String symbol, long sinceMs) {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_LATEST)) {
            ps.setString(1, symbol);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                double ask = rs.getDouble("ap1");
                double bid = rs.getDouble("bp1");
                if (!(ask > 0) || !(bid > 0)) {
                    return null;                     // пустой ответ книги, такое бывает
                }
                return new Leg(rs.getLong("snap_id"), ask, bid,
                        rs.getLong("skew_ms"), rs.getLong("t_recv_ms"));
            }
        } catch (Exception e) {
            log.error("не прочиталась нога {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** Вселенная читается один раз: пары не меняются в течение прогона. */
    private synchronized List<String> universe() {
        if (universe != null) {
            return universe;
        }
        List<String> bases = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_UNIVERSE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                bases.add(rs.getString("base"));
            }
        } catch (Exception e) {
            log.error("не прочиталась вселенная пар: {}", e.getMessage());
        }
        log.info("вселенная для расчёта курса: {} пар", bases.size());
        universe = List.copyOf(bases);
        return universe;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("база стенда закрылась с ошибкой: {}", e.getMessage());
        }
    }
}
