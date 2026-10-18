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

    /**
     * Сшитые снимки одной пары по времени: обе ноги из одного цикла. Колонками (18.10.2026):
     * объект на снимок стоил ~100 байт, а снимков в корзине курса за месяц ~12 млн — 1.2 ГБ кучи
     * на прогон, и вдвое больше на двух месяцах. Колонки — 80 байт и без объектов.
     */
    private static final class Series {
        final int n;
        final long[] recvMs;
        final double[] midUsdc, midUsd, spreadUsdc, spreadUsd, bid, ask, bq, aq, smooth;
        int cur;

        Series(int n) {
            this.n = n;
            recvMs = new long[n];
            midUsdc = new double[n];
            midUsd = new double[n];
            spreadUsdc = new double[n];
            spreadUsd = new double[n];
            bid = new double[n];
            ask = new double[n];
            bq = new double[n];
            aq = new double[n];
            smooth = new double[n];
        }
    }

    private final String base;
    private final FairPrice.Limits limits;
    private final java.util.Collection<String> memecoins;
    private final long maxSkewMs;
    private Clock clock;

    /** По каждой паре — её снимки по времени (курсор чтения — в самой серии). */
    private final Map<String, Series> byPair = new LinkedHashMap<>();

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
        Series l = byPair.get(pair);
        return l == null ? 0 : l.n;
    }

    /** Отметки времени, по которым имеет смысл тикать: снимки торгуемой пары. */
    public long[] schedule() {
        return schedule(base);
    }

    public long[] schedule(String forBase) {
        Series own = byPair.get(forBase);
        return own == null ? new long[0] : own.recvMs.clone();
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
            for (Series s : byPair.values()) {
                s.cur = 0;
            }
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
                Series slices = stitch(c, b, fromMs, toMs);
                if (slices.n > 0) {
                    byPair.put(b, slices);
                }
            }
            if (rateSource != RateSource.MEDIAN) {
                loadDirectRate(c, fromMs, toMs);
            }
            log.warn("справедливая цена из записи: пар {}, снимков у {} — {}; курс {}",
                    byPair.size(), base,
                    byPair.containsKey(base) ? byPair.get(base).n : 0,
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

    /**
     * Сшить USDC- и USD-ноги одной пары по {@code snap_id}.
     *
     * Без карт (18.10.2026): USD-нога ложится в колонки и сортируется по {@code snap_id}, USDC-нога
     * идёт потоком и ищет пару двоичным поиском. Карта {@code snap_id → double[]} стоила ~150 байт
     * на строку, на ноге BTC за месяц — 0.4 ГБ. При равном времени снимки идут в порядке чтения
     * USDC-ноги (по времени её получения), а не в порядке обхода хеш-карты, как раньше.
     */
    private Series stitch(Connection c, String b, long fromMs, long toMs) {
        Leg usd = Leg.load(c, b + "/USD", fromMs, toMs);
        int[] byId = order(usd.n, (x, y) -> Long.compare(usd.id[x], usd.id[y]));
        long[] ids = new long[usd.n];
        for (int k = 0; k < usd.n; k++) {
            ids[k] = usd.id[byId[k]];
        }
        Rows rows = new Rows();
        String sql = "SELECT snap_id, ap1, bp1, bq1, aq1, skew_ms, t_recv_ms FROM revx_book "
                + "WHERE symbol = ? AND t_recv_ms >= ? AND t_recv_ms <= ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, b + "/USDC");
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double qa = rs.getDouble(2);
                    double qb = rs.getDouble(3);
                    if (!(qa > 0) || !(qb > 0)) {
                        continue;                 // пустой ответ книги, такое бывает
                    }
                    int j = java.util.Arrays.binarySearch(ids, rs.getLong(1));
                    if (j < 0) {
                        continue;                 // общего снимка нет — пара не в счёт
                    }
                    int u = byId[j];
                    long skew = Math.max(rs.getLong(6), usd.skew[u]);
                    if (skew > maxSkewMs) {
                        continue;
                    }
                    double midQ = (qa + qb) / 2;
                    double midU = (usd.ask[u] + usd.bid[u]) / 2;
                    if (!(midQ > 0) || !(midU > 0)) {
                        continue;
                    }
                    rows.add(Math.max(rs.getLong(7), usd.recv[u]), midQ, midU, (qa - qb) / midQ,
                            (usd.ask[u] - usd.bid[u]) / midU, qb, qa, rs.getDouble(4), rs.getDouble(5));
                }
            }
        } catch (Exception e) {
            log.error("не прочиталась нога {}: {}", b + "/USDC", e.getMessage());
        }
        int[] byTime = order(rows.n, (x, y) -> Long.compare(rows.recv[x], rows.recv[y]));
        Series s = new Series(rows.n);
        for (int k = 0; k < rows.n; k++) {
            int r = byTime[k];
            s.recvMs[k] = rows.recv[r];
            s.midUsdc[k] = rows.v[0][r];
            s.midUsd[k] = rows.v[1][r];
            s.spreadUsdc[k] = rows.v[2][r];
            s.spreadUsd[k] = rows.v[3][r];
            s.bid[k] = rows.v[4][r];
            s.ask[k] = rows.v[5][r];
            s.bq[k] = rows.v[6][r];
            s.aq[k] = rows.v[7][r];
        }
        double freezeSec = Double.parseDouble(
                System.getProperty("revx.fair.freeze-sec", "0"));
        double smoothSec = Double.parseDouble(
                System.getProperty("revx.fair.smooth-sec", "0"));
        if (freezeSec > 0 && smoothSec > 0) {
            throw new IllegalStateException(
                    "revx.fair.freeze-sec и revx.fair.smooth-sec пишут в ОДНО поле "
                            + "альтернативной опоры: включать можно только одно");
        }
        if (freezeSec > 0) {
            freeze(s, tradeTimes(c, b + "/USDC", fromMs, toMs), (long) (freezeSec * 1000));
        } else {
            smooth(s);
        }
        return s;
    }

    /** USD-нога колонками: {@code snap_id, ask, bid, skew, recv}. */
    private static final class Leg {
        int n;
        long[] id = new long[1024], skew = new long[1024], recv = new long[1024];
        double[] ask = new double[1024], bid = new double[1024];

        static Leg load(Connection c, String symbol, long from, long to) {
            Leg l = new Leg();
            String sql = "SELECT snap_id, ap1, bp1, skew_ms, t_recv_ms FROM revx_book "
                    + "WHERE symbol = ? AND t_recv_ms >= ? AND t_recv_ms <= ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, symbol);
                ps.setLong(2, from);
                ps.setLong(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double a = rs.getDouble(2);
                        double b = rs.getDouble(3);
                        if (!(a > 0) || !(b > 0)) {
                            continue;
                        }
                        if (l.n == l.id.length) {
                            int cap = l.n * 2;
                            l.id = java.util.Arrays.copyOf(l.id, cap);
                            l.skew = java.util.Arrays.copyOf(l.skew, cap);
                            l.recv = java.util.Arrays.copyOf(l.recv, cap);
                            l.ask = java.util.Arrays.copyOf(l.ask, cap);
                            l.bid = java.util.Arrays.copyOf(l.bid, cap);
                        }
                        l.id[l.n] = rs.getLong(1);
                        l.ask[l.n] = a;
                        l.bid[l.n] = b;
                        l.skew[l.n] = rs.getLong(4);
                        l.recv[l.n] = rs.getLong(5);
                        l.n++;
                    }
                }
            } catch (Exception e) {
                log.error("не прочиталась нога {}: {}", symbol, e.getMessage());
            }
            return l;
        }
    }

    /** Сшитые строки до сортировки по времени. */
    private static final class Rows {
        int n;
        long[] recv = new long[1024];
        double[][] v = new double[8][1024];

        void add(long r, double... x) {
            if (n == recv.length) {
                recv = java.util.Arrays.copyOf(recv, n * 2);
                for (int i = 0; i < v.length; i++) {
                    v[i] = java.util.Arrays.copyOf(v[i], n * 2);
                }
            }
            recv[n] = r;
            for (int i = 0; i < v.length; i++) {
                v[i][n] = x[i];
            }
            n++;
        }
    }

    private interface IntCmp {
        int compare(int a, int b);
    }

    /** Устойчивая сортировка индексов {@code 0..n-1} слиянием — без упаковки в объекты. */
    private static int[] order(int n, IntCmp cmp) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        boolean sorted = true;
        for (int i = 1; i < n && sorted; i++) {
            sorted = cmp.compare(a[i - 1], a[i]) <= 0;
        }
        if (sorted) {
            return a;                             // ноги читаются по времени — обычно уже по порядку
        }
        int[] b = new int[n];
        for (int w = 1; w < n; w *= 2) {
            for (int lo = 0; lo < n; lo += 2 * w) {
                int mid = Math.min(lo + w, n), hi = Math.min(lo + 2 * w, n), i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    b[k++] = cmp.compare(a[j], a[i]) < 0 ? a[j++] : a[i++];
                }
                while (i < mid) {
                    b[k++] = a[i++];
                }
                while (j < hi) {
                    b[k++] = a[j++];
                }
            }
            int[] t = a;
            a = b;
            b = t;
        }
        return a;
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
    private static void freeze(Series s, List<Long> trades, long holdMs) {
        if (s.n == 0) {
            return;
        }
        int t = 0;
        double held = s.midUsdc[0];
        long heldUntil = Long.MIN_VALUE;
        double prevMid = s.midUsdc[0];
        for (int k = 0; k < s.n; k++) {
            while (t < trades.size() && trades.get(t) <= s.recvMs[k]) {
                long ts = trades.get(t);
                if (ts > heldUntil) {
                    held = prevMid;               // значение ДО принта
                }
                heldUntil = ts + holdMs;
                t++;
            }
            s.smooth[k] = s.recvMs[k] < heldUntil ? held : s.midUsdc[k];
            prevMid = s.midUsdc[k];
        }
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
    private static void smooth(Series s) {
        double tau = Double.parseDouble(System.getProperty("revx.fair.smooth-sec", "0"));
        if (tau <= 0 || s.n == 0) {
            return;                               // поле сглаживания остаётся нулём, как раньше
        }
        double ema = s.midUsdc[0];
        long prev = s.recvMs[0];
        for (int k = 0; k < s.n; k++) {
            double dt = Math.max(0, s.recvMs[k] - prev) / 1000.0;
            double a = 1 - Math.exp(-dt / tau);
            ema += a * (s.midUsdc[k] - ema);
            prev = s.recvMs[k];
            s.smooth[k] = ema;
        }
    }

    /** Последний снимок пары не позже {@code ts} (индекс) или −1; курсор идёт только вперёд. */
    private static int at(Series s, long ts) {
        if (s.n == 0 || s.recvMs[0] > ts) {
            return -1;
        }
        int i = s.cur;
        while (i + 1 < s.n && s.recvMs[i + 1] <= ts) {
            i++;
        }
        s.cur = i;
        return i;
    }

    @Override
    public StandReader.Fair latest(String askedBase, long lookbackMs) {
        long now = clock.now();
        long since = now - lookbackMs;
        List<PairQuote> quotes = new ArrayList<>();
        double ownBid = 0, ownAsk = 0;
        long asOf = 0;
        for (Map.Entry<String, Series> e : byPair.entrySet()) {
            String pair = e.getKey();
            Series s = e.getValue();
            int i = at(s, now);
            if (i < 0 || s.recvMs[i] < since) {
                continue;                         // снимок протух — пара не в счёт
            }
            if (pair.equals(askedBase)) {
                ownBid = s.bid[i];
                ownAsk = s.ask[i];
            }
            quotes.add(new PairQuote(pair, s.midUsdc[i], s.midUsd[i],
                    s.spreadUsdc[i], s.spreadUsd[i], memecoins.contains(pair), s.recvMs[i],
                    s.bq[i], s.aq[i], s.smooth[i]));
            asOf = Math.max(asOf, s.recvMs[i]);
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
                ownBid, ownAsk,
                state.referenceSpreadPct());
    }
}
