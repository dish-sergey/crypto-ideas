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
                         double spreadUsdc, double spreadUsd, double bid, double ask,
                         double bq, double aq, double smooth) {
    }

    private final String base;
    private final FairPrice.Limits limits;
    private final java.util.Collection<String> memecoins;
    private final long maxSkewMs;
    private Clock clock;

    /** По каждой паре — её снимки по времени и курсор чтения. */
    private final Map<String, List<Slice>> byPair = new LinkedHashMap<>();
    private final Map<String, Integer> cursor = new HashMap<>();

    /**
     * ОТКУДА БРАТЬ КУРС USDC/USD — прибор под задачу A45, а не боевая настройка.
     *
     * Курс считается медианой подразумеваемых оценок по парам, а у площадки есть
     * ПРЯМАЯ книга {@code USDC/USD} со спредом в один базисный пункт. Замер за
     * четверо суток (34 382 точки): наша медиана лежит на <b>5.31 б.п. НИЖЕ</b>
     * прямой книги, и это устойчиво по суткам; а шаг за минуту у медианы
     * 1.77 б.п. против <b>нуля</b> у прямой книги.
     *
     * Отсюда два РАЗНЫХ вопроса, и разделять их обязательно:
     * <ul>
     *   <li><b>шум</b> — чинится сглаживанием самого курса, уровень не меняется,
     *       риска первого порядка нет ({@code SMOOTH});</li>
     *   <li><b>уровень</b> — смена основания котирования на прямую книгу
     *       ({@code DIRECT}). Сдвиг всех {@code fair} вниз на 5–6 б.п.: бид
     *       дальше от рынка, аск ближе. Цена ошибки известна — 10.09.2026 сдвиг
     *       курса на 1.1 б.п. изменил число сделок BTC на 40%.</li>
     * </ul>
     *
     * ⚠️ Подменяется ТОЛЬКО делитель цены. Медиана, разброс, остатки и все три
     * гейта считаются по-прежнему — иначе опыт менял бы две вещи разом и
     * «сдвинулась цена» было бы не отличить от «иначе сработали гейты».
     */
    private enum RateSource { MEDIAN, DIRECT, SMOOTH }

    private final RateSource rateSource = RateSource.valueOf(
            System.getProperty("revx.fair.rate-source", "MEDIAN").toUpperCase(java.util.Locale.ROOT));

    /** Полупериод сглаживания курса, секунды (для {@code SMOOTH}). */
    private final double rateSmoothSec =
            Double.parseDouble(System.getProperty("revx.fair.rate-smooth-sec", "60"));

    /** Прямая книга USDC/USD: отметка → середина. */
    private final List<long[]> directRateTs = new ArrayList<>();
    private final List<Double> directRateMid = new ArrayList<>();
    private int directCursor;

    /** Сглаживание курса: общий код с живым чтением. */
    private final org.home.data.revx.sim.RateSmoother smoother =
            new org.home.data.revx.sim.RateSmoother(rateSmoothSec);

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
                        f.quotable() && f.price() > 0, f.pausedReason(), 0));
            }
        } finally {
            replaceClock(saved);
            cursor.clear();
            // ⚠️ Вместе с курсорами пар сбрасываются курсор прямой книги и
            // состояние сглаживания: следующий вызов начнётся с начала окна, и
            // курс, «запомненный» с прошлой пары, испортил бы первые тики.
            directCursor = 0;
            smoother.reset();
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
        try (Connection c = StandDb.open(path)) {
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
            if (rateSource != RateSource.MEDIAN) {
                loadDirectRate(c, fromMs, toMs);
            }
            log.warn("справедливая цена из записи: пар {}, снимков у {} — {}; курс {}",
                    byPair.size(), base,
                    byPair.containsKey(base) ? byPair.get(base).size() : 0,
                    rateSource == RateSource.MEDIAN ? "медианой по парам"
                            : rateSource == RateSource.DIRECT
                                    ? "ПРЯМОЙ книгой USDC/USD (" + directRateMid.size() + " снимков)"
                                    : "СГЛАЖЕННОЙ медианой, полупериод "
                                            + rateSmoothSec + " с");
        } catch (Exception e) {
            log.error("не прочиталась книга стенда: {}", e.toString());
        }
    }

    /**
     * ПРЯМАЯ КНИГА КУРСА. Собирается с 10.09.2026 раз в 10 с (задача A45).
     *
     * ⚠️ На окнах до 10.09 её просто нет, и прогон с {@code DIRECT} тогда
     * молча выродился бы в медианный — поэтому пустая книга это громкая
     * ошибка, а не тихий откат.
     */
    private void loadDirectRate(Connection c, long fromMs, long toMs) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT t_recv_ms, bp1, ap1 FROM revx_book WHERE symbol = 'USDC/USD'"
                        + " AND t_recv_ms >= ? AND t_recv_ms <= ? AND bp1 > 0 AND ap1 > 0"
                        + " ORDER BY t_recv_ms")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    directRateTs.add(new long[]{rs.getLong(1)});
                    directRateMid.add((rs.getDouble(2) + rs.getDouble(3)) / 2);
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась прямая книга курса: {}", e.toString());
        }
        if (rateSource == RateSource.DIRECT && directRateMid.isEmpty()) {
            throw new IllegalStateException(
                    "revx.fair.rate-source=DIRECT, а книги USDC/USD в этом окне нет "
                            + "(собирается с 10.09.2026) — прогон был бы медианным и молча");
        }
    }

    /** Курс прямой книги на момент {@code ts}; 0 — снимок протух или его нет. */
    private double directRate(long ts) {
        if (directRateMid.isEmpty() || directRateTs.get(0)[0] > ts) {
            return 0;
        }
        while (directCursor + 1 < directRateTs.size()
                && directRateTs.get(directCursor + 1)[0] <= ts) {
            directCursor++;
        }
        // ⚠️ Свежесть та же, что у корзины курса: снимок старше 30 с в расчёт
        // не идёт. Иначе на простое сбора курс замирал бы, а цены пар — нет.
        return ts - directRateTs.get(directCursor)[0] > 30_000 ? 0
                : directRateMid.get(directCursor);
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
                    (q[0] - q[1]) / midQ, (u[0] - u[1]) / midU, q[1], q[0], q[5], q[6], 0));
        }
        out.sort(java.util.Comparator.comparingLong(Slice::recvMs));
        double freezeSec = Double.parseDouble(
                System.getProperty("revx.fair.freeze-sec", "0"));
        double smoothSec = Double.parseDouble(
                System.getProperty("revx.fair.smooth-sec", "0"));
        if (freezeSec > 0 && smoothSec > 0) {
            throw new IllegalStateException(
                    "revx.fair.freeze-sec и revx.fair.smooth-sec пишут в ОДНО поле "
                            + "альтернативной опоры: включать можно только одно");
        }
        return freezeSec > 0
                ? freeze(out, tradeTimes(c, b + "/USDC", fromMs, toMs), (long) (freezeSec * 1000))
                : smooth(out);
    }

    /** Отметки сделок пары: вход для замороженной опоры. */
    private static List<Long> tradeTimes(Connection c, String symbol, long from, long to) {
        List<Long> out = new ArrayList<>();
        String sql = "SELECT ts_ms FROM revx_trade WHERE symbol = ? AND ts_ms >= ? "
                + "AND ts_ms <= ? ORDER BY ts_ms";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.error("не прочитались сделки {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    /**
     * ЗАМОРОЖЕННАЯ СЕРЕДИНА: опора не двигается {@code N} секунд после принта.
     *
     * <h2>Какой вопрос она решает</h2>
     *
     * К 12.09.2026 три объяснения преимущества межплощадочной опоры проверены и
     * отвергнуты (ширина, асимметрия, вес микроцены), и оставалось одно —
     * «в медиане по 23 активам есть межрыночная ИНФОРМАЦИЯ». Но список был
     * неполон, и четвёртый механизм не требует никакой информации:
     * **невосприимчивость к собственному потоку пары**.
     *
     * Середина книги механически двигается тем самым потоком, который нас
     * исполняет: свип сносит биды → середина падает → котировка, привязанная к
     * середине, уезжает вниз НАВСТРЕЧУ свипу. Медиана по 23 парам от свипа на
     * ЭТОЙ паре не двигается, поэтому наш бид остаётся на месте.
     *
     * Замороженная середина даёт ровно это и НЕ даёт межрыночной информации.
     * Значит опыт разделяющий: если она воспроизводит преимущество —
     * дело в обратной связи; если нет — в медиане действительно есть сигнал.
     *
     * <h2>Как считается</h2>
     *
     * На приход принта опора фиксируется на значении, которое было ДО него, и
     * держится {@code N} секунд. Новый принт внутри окна продлевает заморозку,
     * но НЕ меняет удерживаемое значение: иначе пачка принтов одного свипа
     * протащила бы опору за собой по шагу, то есть вернула бы ровно ту обратную
     * связь, которую опыт убирает.
     */
    private static List<Slice> freeze(List<Slice> in, List<Long> trades, long holdMs) {
        if (in.isEmpty()) {
            return in;
        }
        List<Slice> out = new ArrayList<>(in.size());
        int t = 0;
        double held = in.get(0).midUsdc();
        long heldUntil = Long.MIN_VALUE;
        double prevMid = in.get(0).midUsdc();
        for (Slice s : in) {
            while (t < trades.size() && trades.get(t) <= s.recvMs()) {
                long ts = trades.get(t);
                if (ts > heldUntil) {
                    held = prevMid;               // значение ДО принта
                }
                heldUntil = ts + holdMs;
                t++;
            }
            double value = s.recvMs() < heldUntil ? held : s.midUsdc();
            out.add(new Slice(s.recvMs(), s.midUsdc(), s.midUsd(), s.spreadUsdc(),
                    s.spreadUsd(), s.bid(), s.ask(), s.bq(), s.aq(), value));
            prevMid = s.midUsdc();
        }
        return out;
    }

    /** {@code snap_id → [ask, bid, _, skew, recv]}. */
    private static Map<Long, double[]> legs(Connection c, String symbol, long from, long to) {
        Map<Long, double[]> out = new HashMap<>();
        String sql = "SELECT snap_id, ap1, bp1, bq1, aq1, skew_ms, t_recv_ms FROM revx_book "
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
                            rs.getLong("skew_ms"), rs.getLong("t_recv_ms"),
                            rs.getDouble("bq1"), rs.getDouble("aq1")});
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась нога {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    /**
     * Экспоненциальное сглаживание середины книги с постоянной времени
     * {@code revx.fair.smooth-sec}.
     *
     * ⚠️ Сглаживается ТОЛЬКО цена, от которой котируем. Курс USDC/USD считается
     * по несглаженным серединам обеих ног — иначе опыт менял бы две вещи разом,
     * и это то же правило, что у подмены курса и у выбора опоры.
     *
     * Сглаживание идёт ПО ВРЕМЕНИ, а не по числу снимков: плотность сбора
     * менялась (07.09.2026 с секунды на полторы), и оконное среднее по
     * фиксированному числу точек означало бы на разных окнах разное время.
     */
    private static List<Slice> smooth(List<Slice> in) {
        double tau = Double.parseDouble(System.getProperty("revx.fair.smooth-sec", "0"));
        if (tau <= 0 || in.isEmpty()) {
            return in;
        }
        List<Slice> out = new ArrayList<>(in.size());
        double ema = in.get(0).midUsdc();
        long prev = in.get(0).recvMs();
        for (Slice s : in) {
            double dt = Math.max(0, s.recvMs() - prev) / 1000.0;
            double a = 1 - Math.exp(-dt / tau);
            ema += a * (s.midUsdc() - ema);
            prev = s.recvMs();
            out.add(new Slice(s.recvMs(), s.midUsdc(), s.midUsd(), s.spreadUsdc(),
                    s.spreadUsd(), s.bid(), s.ask(), s.bq(), s.aq(), ema));
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
                    s.spreadUsdc(), s.spreadUsd(), memecoins.contains(pair), s.recvMs(),
                    s.bq(), s.aq(), s.smooth()));
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
        // ⚠️ ПОДМЕНА КУРСА — ТОЛЬКО ДЕЛИТЕЛЬ ЦЕНЫ, и потому она делается здесь,
        // а не внутри FairPrice: гейты по разбросу и остатку обязаны остаться на
        // медиане. `fair = mid_usd / курс`, значит замена курса — это множитель
        // `курс_медианы / курс_новый`, применённый к готовой цене.
        double price = state.fairUsdc();
        if (rateSource != RateSource.MEDIAN && result.rate() > 0 && price > 0) {
            double swapped = rateSource == RateSource.DIRECT
                    ? directRate(now) : smoother.next(now, result.rate());
            if (swapped > 0) {
                price *= result.rate() / swapped;
            }
        }
        return new StandReader.Fair(price, state.quotable(), state.pausedReason(),
                asOf, quotes.size(),
                own == null ? 0 : own.bid(), own == null ? 0 : own.ask(),
                state.referenceSpreadPct());
    }
}
