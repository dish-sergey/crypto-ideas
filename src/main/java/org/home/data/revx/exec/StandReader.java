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

    private static final String SELECT_PAIRS = """
            SELECT snap_id,
                   MAX(CASE WHEN leg='usdc' THEN symbol END)                            AS usdc_symbol,
                   MAX(CASE WHEN leg='usdc' THEN (ap1 + bp1) / 2.0 END)                 AS mid_usdc,
                   MAX(CASE WHEN leg='usd'  THEN (ap1 + bp1) / 2.0 END)                 AS mid_usd,
                   MAX(CASE WHEN leg='usdc' THEN (ap1 - bp1) / ((ap1 + bp1) / 2.0) END) AS spread_usdc,
                   MAX(CASE WHEN leg='usd'  THEN (ap1 - bp1) / ((ap1 + bp1) / 2.0) END) AS spread_usd,
                   MAX(skew_ms)                                                         AS skew_ms,
                   MAX(t_recv_ms)                                                       AS available_at,
                   COUNT(*)                                                             AS legs
            FROM revx_book
            WHERE t_recv_ms BETWEEN ? AND ?
            GROUP BY snap_id
            HAVING legs = 2 AND mid_usdc IS NOT NULL AND mid_usd IS NOT NULL
            ORDER BY available_at
            """;

    /** Справедливая цена пары и всё, что нужно, чтобы решить — котировать ли. */
    public record Fair(double price, boolean quotable, String pausedReason,
                       long asOfMs, int pairsUsed) {
    }

    private final Connection connection;
    private final List<String> memecoins;
    private final FairPrice.Limits limits;
    private final long maxSkewMs;

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
        long now = System.currentTimeMillis();
        List<PairQuote> quotes = new ArrayList<>();
        long asOf = 0;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_PAIRS)) {
            ps.setLong(1, now - lookbackMs);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long skew = rs.getLong("skew_ms");
                    if (skew > maxSkewMs) {
                        continue;                    // ноги разъехались — пара не в счёт
                    }
                    String symbol = rs.getString("usdc_symbol");
                    if (symbol == null) {
                        continue;
                    }
                    String pairBase = symbol.substring(0, symbol.indexOf('/'));
                    quotes.add(new PairQuote(pairBase, rs.getDouble("mid_usdc"),
                            rs.getDouble("mid_usd"), rs.getDouble("spread_usdc"),
                            rs.getDouble("spread_usd"), memecoins.contains(pairBase),
                            rs.getLong("available_at")));
                    asOf = Math.max(asOf, rs.getLong("available_at"));
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась справедливая цена: {}", e.getMessage());
            return new Fair(0, false, "база стенда не читается", 0, 0);
        }

        if (quotes.isEmpty()) {
            return new Fair(0, false, "снимков нет — сбор стоит?", 0, 0);
        }
        // Берётся ПОСЛЕДНИЙ срез рынка: у каждой пары оставляем самый свежий снимок.
        List<PairQuote> latest = new ArrayList<>();
        for (PairQuote quote : quotes) {
            latest.removeIf(existing -> existing.base().equals(quote.base()));
            latest.add(quote);
        }

        FairPrice.Result result = FairPrice.compute(latest, limits);
        FairPrice.PairState state = result.pair(base);
        if (state == null) {
            return new Fair(0, false, "пары " + base + " нет в последнем срезе", asOf, latest.size());
        }
        return new Fair(state.fairUsdc(), state.quotable(), state.pausedReason(),
                asOf, latest.size());
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
