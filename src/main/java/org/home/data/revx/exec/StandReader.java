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
import java.util.HashMap;
import java.util.Map;
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
public final class StandReader implements FairSource, AutoCloseable {

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
            LIMIT 4
            """;

    private static final String SELECT_UNIVERSE = """
            SELECT DISTINCT substr(symbol, 1, instr(symbol, '/') - 1) AS base
            FROM revx_pair
            WHERE status = 'active' AND symbol LIKE '%/USDC'
            """;

    /** Справедливая цена пары и всё, что нужно, чтобы решить — котировать ли. */
    /**
     *  bookBid лучший БИД книги торгуемой пары (USDC-нога), 0 = неизвестен
     *  bookAsk лучший АСК той же книги, 0 = неизвестен
     *
     * Верх стакана нужен не для цены, а для ПРЕДОХРАНИТЕЛЯ: наша справедливая
     * цена считается по корзине из 23 пар и на быстром движении обгоняет книгу
     * самой площадки. Тогда бид, посчитанный как `fair·(1−d)`, оказывается выше
     * текущего аска, и площадка отвергает заявку с `post_only_immediate_match`.
     * Измерено 03.09.2026: **139 из 217 отказов замены за 8 часов — именно эта
     * причина**, и каждый такой отказ стоит постановки из суточной тысячи.
     */
    public record Fair(double price, boolean quotable, String pausedReason,
                       long asOfMs, int pairsUsed, double bookBid, double bookAsk) {

        public Fair(double price, boolean quotable, String pausedReason,
                    long asOfMs, int pairsUsed) {
            this(price, quotable, pausedReason, asOfMs, pairsUsed, 0, 0);
        }
    }

    /**
     * Спецификация пары с площадки: шаги и минимальный номинал.
     *
     * ⚠️ Эти числа У КАЖДОЙ ПАРЫ СВОИ, и до 04.09.2026 они были зашиты под
     * BTC/USDC прямо в {@link Executor}. Для SOL они другие: шаг цены 0.001
     * против 0.01, шаг количества 1e-6 против 1e-8. Бот на SOL с BTC-шагами
     * ставил бы цены неверной точности.
     *
     * @param minNotional {@code min_order_size_quote} — связывает именно он, а
     *                    не {@code min_order_size}: у BTC это 0.1 USDC против
     *                    пренебрежимых 1e-8 BTC
     */
    public record PairSpec(String symbol, double baseStep, double quoteStep, double minNotional) {
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
    /**
     * Спецификация пары из каталога площадки.
     *
     * @return null, если пары в каталоге нет — и тогда бот обязан НЕ ЗАПУСКАТЬСЯ.
     *         Подставить чужие шаги здесь хуже, чем упасть: заявка уйдёт с
     *         неверной точностью цены, а узнаем мы об этом по отказам площадки
     *         уже на живых деньгах.
     */
    public PairSpec spec(String symbol) {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT base_step, quote_step, min_order_size_quote FROM revx_pair "
                        + "WHERE symbol = ? AND status = 'active'")) {
            ps.setString(1, symbol);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    log.error("пары {} нет в каталоге revx_pair", symbol);
                    return null;
                }
                PairSpec spec = new PairSpec(symbol, rs.getDouble(1), rs.getDouble(2),
                        rs.getDouble(3));
                if (!(spec.quoteStep() > 0) || !(spec.baseStep() > 0)
                        || !(spec.minNotional() > 0)) {
                    log.error("спецификация {} неполная: {}", symbol, spec);
                    return null;
                }
                return spec;
            }
        } catch (Exception e) {
            log.error("не прочитать спецификацию {}: {}", symbol, e.toString());
            return null;
        }
    }

    public Fair latest(String base, long lookbackMs) {
        long since = System.currentTimeMillis() - lookbackMs;
        List<PairQuote> quotes = new ArrayList<>();
        java.util.Map<String, Leg> ownBook = new java.util.HashMap<>();
        long asOf = 0;
        for (String pairBase : universe()) {
            // Ноги сопоставляются ПО snap_id, а не «последняя с последней».
            //
            // Наивная склейка ломалась гонкой с писателем: коллектор кладёт ноги
            // одного цикла двумя строками, и запрос между этими записями брал
            // USDC из цикла N, а USD из цикла N−1. Расхождение выходило равным
            // периоду опроса — на быстром ярусе это 1000 мс при пороге 250, пара
            // отбрасывалась, и если такой парой оказывалась торгуемая, срабатывал
            // гейт «пары нет в последнем срезе»: обе заявки снимались и ставились
            // заново. Каждая такая осечка тратит постановку, а суточный потолок
            // постановок — единственный жёсткий ресурс площадки.
            //
            // Поэтому берём несколько последних снимков каждой ноги и выбираем
            // самый свежий snap_id, который есть у обеих.
            Map<Long, Leg> usdcLegs = legs(pairBase + "/USDC", since);
            Map<Long, Leg> usdLegs = legs(pairBase + "/USD", since);
            Long snapId = usdcLegs.keySet().stream()
                    .filter(usdLegs::containsKey)
                    .max(Long::compareTo).orElse(null);
            if (snapId == null) {
                continue;                            // общего снимка нет — пара не в счёт
            }
            Leg usdc = usdcLegs.get(snapId);
            Leg usd = usdLegs.get(snapId);
            long skew = Math.max(usdc.skewMs, usd.skewMs);
            if (skew > maxSkewMs) {
                continue;
            }
            ownBook.put(pairBase, usdc);
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
        Leg own = ownBook.get(base);
        return new Fair(state.fairUsdc(), state.quotable(), state.pausedReason(),
                asOf, quotes.size(),
                own == null ? 0 : own.bid(), own == null ? 0 : own.ask());
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

    /** Несколько последних снимков ноги, разложенных по snap_id. */
    private Map<Long, Leg> legs(String symbol, long sinceMs) {
        Map<Long, Leg> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_LATEST)) {
            ps.setString(1, symbol);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double ask = rs.getDouble("ap1");
                    double bid = rs.getDouble("bp1");
                    if (!(ask > 0) || !(bid > 0)) {
                        continue;                    // пустой ответ книги, такое бывает
                    }
                    out.put(rs.getLong("snap_id"), new Leg(rs.getLong("snap_id"), ask, bid,
                            rs.getLong("skew_ms"), rs.getLong("t_recv_ms")));
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась нога {}: {}", symbol, e.getMessage());
        }
        return out;
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
