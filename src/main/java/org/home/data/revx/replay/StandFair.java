package org.home.data.revx.replay;

import org.home.data.revx.exec.Clock;
import org.home.data.revx.exec.FairSource;
import org.home.data.revx.exec.StandReader;
import org.home.data.revx.sim.FairPrice;
import org.home.data.revx.sim.PairQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Справедливая цена ИЗ ЗАПИСАННЫХ КНИГ на заданный момент времени.
 *
 * <h2>Зачем</h2>
 *
 * Прогноз до сих пор брал ряд цены из {@code exec_quote} журнала бота, поэтому
 * считать можно было только пары, где бот уже работал: BTC и SOL. Между тем в
 * записи лежат ВСЕ 46 книг — 23 в USDC и 23 опорных в USD, — то есть данных
 * хватает на любую из двадцати трёх пар. Не хватало кода: {@link StandReader}
 * умеет только «последнюю» цену, без параметра времени.
 *
 * <h2>Почему нельзя взять локальный mid</h2>
 *
 * ⚠️ Соблазн подставить середину книги той пары, в которой торгуем, велик и
 * ведёт к другой стратегии. Опорная цена берётся из USD-книги того же актива:
 * {@code fair_usdc = mid_usd / курс_usdc_usd}, а курс — медиана по многим парам.
 * Котирование вокруг собственной книги Revolut — это не то, что делает живой
 * бот, и сравнивать такие прогоны было бы не с чем.
 *
 * <h2>Ноги сшиваются по snap_id</h2>
 *
 * ⚠️ Как и в живом чтении: USDC и USD берутся из ОДНОГО цикла опроса. Наивная
 * склейка «последняя с последней» ломается гонкой с писателем, расхождение
 * выходит равным периоду опроса, и пара отбрасывается гейтом. Здесь та же
 * ошибка была бы незаметнее — она просто испортила бы цену.
 */
public final class StandFair implements FairSource {

    private static final Logger log = LoggerFactory.getLogger(StandFair.class);

    /** Сшитый снимок одной пары: обе ноги из одного цикла. */
    private record Slice(long recvMs, double midUsdc, double midUsd,
                         double spreadUsdc, double spreadUsd, double bid, double ask) {
    }

    private final String base;
    private final FairPrice.Limits limits;
    private final java.util.Collection<String> memecoins;
    private final long maxSkewMs;
    private Clock clock;

    /** По каждой паре — её снимки по времени и курсор чтения. */
    private final Map<String, List<Slice>> byPair = new LinkedHashMap<>();
    private final Map<String, Integer> cursor = new HashMap<>();

    public StandFair(String standDbPath, String base, FairPrice.Limits limits,
                     java.util.Collection<String> memecoins, long maxSkewMs, Clock clock,
                     long fromMs, long toMs) {
        this.base = base;
        this.limits = limits;
        this.memecoins = memecoins;
        this.maxSkewMs = maxSkewMs;
        this.clock = clock;
        load(standDbPath, fromMs, toMs);
    }

    /** Сколько пар удалось собрать: столько же участвует в расчёте курса. */
    public int pairs() {
        return byPair.size();
    }

    /**
     * Какие пары есть в срезе — для обхода всей вселенной.
     *
     * Загрузка стоит дорого (46 ног за сутки), а курс всё равно считается по
     * всем парам сразу. Поэтому обход берёт ОДИН срез и переспрашивает его про
     * каждую пару, вместо того чтобы читать книги двадцать три раза подряд.
     */
    public java.util.Set<String> bases() {
        return byPair.keySet();
    }

    /** Сколько снимков у пары: мера покрытия, у разных пар оно РАЗНОЕ. */
    public int snapshots(String pair) {
        List<Slice> l = byPair.get(pair);
        return l == null ? 0 : l.size();
    }

    /** Отметки времени, по которым имеет смысл тикать: снимки торгуемой пары. */
    public long[] schedule() {
        return schedule(base);
    }

    public long[] schedule(String forBase) {
        List<Slice> own = byPair.get(forBase);
        if (own == null) {
            return new long[0];
        }
        long[] out = new long[own.size()];
        for (int i = 0; i < own.size(); i++) {
            out[i] = own.get(i).recvMs();
        }
        return out;
    }

    /**
     * Развернуть в ряд тиков — тот же вид, что даёт журнал живого бота.
     *
     * Так прогноз работает с новой парой БЕЗ единой правки: он по-прежнему
     * получает список тиков со справедливой ценой, признаком {@code quotable} и
     * причиной паузы. Разница лишь в том, что раньше их писал живой бот, а
     * теперь они считаются из записанных книг тем же {@link FairPrice}, включая
     * оба его предохранителя.
     *
     * ⚠️ Метод сдвигает собственные часы и курсоры, поэтому вызывать его надо
     * ОДИН раз и до прогона.
     */
    public List<ReplayFair.Tick> toTicks() {
        return toTicks(base);
    }

    public List<ReplayFair.Tick> toTicks(String forBase) {
        long[] when = schedule(forBase);
        List<ReplayFair.Tick> out = new ArrayList<>(when.length);
        SimClock own = new SimClock(when.length > 0 ? when[0] : 0);
        Clock saved = replaceClock(own);
        try {
            for (long ts : when) {
                own.moveTo(ts);
                StandReader.Fair f = latest(forBase, 30_000);
                out.add(new ReplayFair.Tick(ts, f.price(), null, null, 0,
                        f.quotable() && f.price() > 0, f.pausedReason()));
            }
        } finally {
            replaceClock(saved);
            cursor.clear();
        }
        return out;
    }

    // Часы подменяются на время разворачивания в тики: там нужен свой ход
    // времени, а после — прежние.

    private Clock replaceClock(Clock c) {
        Clock old = this.clock;
        this.clock = c;
        return old;
    }

    private void load(String path, long fromMs, long toMs) {
        String url = "jdbc:sqlite:file:" + path + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url)) {
            List<String> bases = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT DISTINCT substr(symbol, 1, instr(symbol, '/') - 1) AS base "
                                 + "FROM revx_book WHERE symbol LIKE '%/USDC' ORDER BY base")) {
                while (rs.next()) {
                    bases.add(rs.getString("base"));
                }
            }
            for (String b : bases) {
                List<Slice> slices = stitch(c, b, fromMs, toMs);
                if (!slices.isEmpty()) {
                    byPair.put(b, slices);
                }
            }
            log.warn("справедливая цена из записи: пар {}, снимков у {} — {}",
                    byPair.size(), base,
                    byPair.containsKey(base) ? byPair.get(base).size() : 0);
        } catch (Exception e) {
            log.error("не прочиталась книга стенда: {}", e.toString());
        }
    }

    /** Сшить USDC- и USD-ноги одной пары по {@code snap_id}. */
    private List<Slice> stitch(Connection c, String b, long fromMs, long toMs) {
        Map<Long, double[]> usdc = legs(c, b + "/USDC", fromMs, toMs);
        Map<Long, double[]> usd = legs(c, b + "/USD", fromMs, toMs);
        List<Slice> out = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : usdc.entrySet()) {
            double[] u = usd.get(e.getKey());
            if (u == null) {
                continue;                         // общего снимка нет — пара не в счёт
            }
            double[] q = e.getValue();
            long skew = (long) Math.max(q[3], u[3]);
            if (skew > maxSkewMs) {
                continue;
            }
            double midQ = (q[0] + q[1]) / 2;
            double midU = (u[0] + u[1]) / 2;
            if (!(midQ > 0) || !(midU > 0)) {
                continue;
            }
            out.add(new Slice((long) Math.max(q[4], u[4]), midQ, midU,
                    (q[0] - q[1]) / midQ, (u[0] - u[1]) / midU, q[1], q[0]));
        }
        out.sort(java.util.Comparator.comparingLong(Slice::recvMs));
        return out;
    }

    /** {@code snap_id → [ask, bid, _, skew, recv]}. */
    private static Map<Long, double[]> legs(Connection c, String symbol, long from, long to) {
        Map<Long, double[]> out = new HashMap<>();
        String sql = "SELECT snap_id, ap1, bp1, skew_ms, t_recv_ms FROM revx_book "
                + "WHERE symbol = ? AND t_recv_ms >= ? AND t_recv_ms <= ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double ask = rs.getDouble("ap1");
                    double bid = rs.getDouble("bp1");
                    if (!(ask > 0) || !(bid > 0)) {
                        continue;                 // пустой ответ книги, такое бывает
                    }
                    out.put(rs.getLong("snap_id"), new double[]{ask, bid, 0,
                            rs.getLong("skew_ms"), rs.getLong("t_recv_ms")});
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась нога {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    /** Последний снимок пары не позже {@code ts}; курсор идёт только вперёд. */
    private Slice at(String pair, long ts) {
        List<Slice> list = byPair.get(pair);
        if (list == null || list.isEmpty() || list.get(0).recvMs() > ts) {
            return null;
        }
        int i = cursor.getOrDefault(pair, 0);
        while (i + 1 < list.size() && list.get(i + 1).recvMs() <= ts) {
            i++;
        }
        cursor.put(pair, i);
        return list.get(i);
    }

    @Override
    public StandReader.Fair latest(String askedBase, long lookbackMs) {
        long now = clock.now();
        long since = now - lookbackMs;
        List<PairQuote> quotes = new ArrayList<>();
        Slice own = null;
        long asOf = 0;
        for (String pair : byPair.keySet()) {
            Slice s = at(pair, now);
            if (s == null || s.recvMs() < since) {
                continue;                         // снимок протух — пара не в счёт
            }
            if (pair.equals(askedBase)) {
                own = s;
            }
            quotes.add(new PairQuote(pair, s.midUsdc(), s.midUsd(),
                    s.spreadUsdc(), s.spreadUsd(), memecoins.contains(pair), s.recvMs()));
            asOf = Math.max(asOf, s.recvMs());
        }
        if (quotes.isEmpty()) {
            return new StandReader.Fair(0, false, "снимков нет в этом окне", 0, 0);
        }
        FairPrice.Result result = FairPrice.compute(quotes, limits);
        FairPrice.PairState state = result.pair(askedBase);
        if (state == null) {
            return new StandReader.Fair(0, false,
                    "пары " + askedBase + " нет в срезе", asOf, quotes.size());
        }
        return new StandReader.Fair(state.fairUsdc(), state.quotable(), state.pausedReason(),
                asOf, quotes.size(),
                own == null ? 0 : own.bid(), own == null ? 0 : own.ask());
    }
}
