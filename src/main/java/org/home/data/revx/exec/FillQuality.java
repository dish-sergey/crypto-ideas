package org.home.data.revx.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * КАЧЕСТВО СДЕЛКИ ({@code --revx-fill-quality}, 15.10.2026, идея владельца): каждая сделка
 * раскладывается на слагаемые в б.п. от НАСТОЯЩЕЙ середины Бинанса, а не по доходу.
 *
 * <h2>Зачем</h2>
 *
 * Доход за неделю почти целиком — ход рынка, пока бот носит запас, и разница настроек в
 * 1 б.п. тонет в нём на тысячах сделок. Ценность сделки к свежей опоре через секунды
 * шумит на порядок меньше: сотни сделок дают ответ за день-два.
 *
 * <h2>Слагаемые (знак «+» — в нашу пользу)</h2>
 *
 * <ul>
 *   <li><b>отступ</b> — цена сделки против опоры БОТА на тике, когда цена была поставлена
 *       (момент постановки или замены — {@code order_info.created_ms} читателя площадки:
 *       каждая замена — новая заявка со своим временем);</li>
 *   <li><b>проспали</b> — ход середины Бинанса от постановки цены до исполнения. Опора
 *       бота идёт за Бинансом 1:1, поэтому всё, что Бинанс ушёл против нас, пока заявка
 *       стояла, — это цена того, что мы не переставили (не знали или не успели);</li>
 *   <li><b>отбор</b> — ход середины Бинанса за H секунд ПОСЛЕ исполнения: информированность
 *       контрагента, скоростью не лечится;</li>
 *   <li><b>ценность@H</b> = отступ + проспали + отбор@H — сделка против свежей опоры через H.</li>
 * </ul>
 *
 * Дополнительно для первого уровня — <b>отстали от цели</b>: насколько цена сделки ближе к
 * рынку, чем цель котировщика на последнем тике перед исполнением. Это часть «проспали»,
 * которую бот уже знал, но не успел переставить (одна замена за тик, затык, порог).
 *
 * ⚠️ Опора бота лежит на базисе к Бинансу (своя книга + корзина), поэтому «отступ» меряется
 * от неё, а ходы — от Бинанса: базис за секунды не меняется, за минуты — уже может.
 * ⚠️ Время сделки — площадки ({@code trade.tdt}), время Бинанса — приёма на micro
 * ({@code bnb-book}); часы обоих по NTP, расхождение — миллисекунды.
 *
 * Источники: облегчённые журналы ботов ({@code exec_quote}, {@code exec_fill}), {@code venue.db}
 * читателя, каталог {@code book-YYYY-MM-DD.jsonl[.gz]} службы {@code bnb-book}. Только чтение.
 */
public final class FillQuality {

    static final int[] H = {1, 5, 60, 300, 900};
    static final double STALE_BP = 3.0;

    private FillQuality() {
    }

    /** Сделка и всё, что о ней узнали. */
    static final class Fill {
        String bot;
        String sym;      // BTCUSDC
        int sg;          // +1 покупка, −1 продажа
        long t;          // время площадки
        long tSet;       // постановка цены
        double p;
        double usd;
        int level = -1;
        double fairSet = Double.NaN;
        double fairT = Double.NaN;
        double tgtT = Double.NaN;
        // середина Бинанса: tSet, t−3с, t, t+H...
        // размах середины Бинанса, б.п.: за 60 и 300 с ДО сделки, за 60 с ПОСЛЕ
        double vol60 = Double.NaN, vol300 = Double.NaN, volAfter60 = Double.NaN;
        final double[] b = new double[3 + H.length];
        final long[] age = new long[3 + H.length];

        long[] queries() {
            long[] q = new long[3 + H.length];
            q[0] = tSet;
            q[1] = t - 3000;
            q[2] = t;
            for (int i = 0; i < H.length; i++) {
                q[3 + i] = t + H[i] * 1000L;
            }
            return q;
        }
    }

    /**
     * @param journals {@code a=<db>[;<db>],b=...}
     * @param venueDb  {@code venue.db} читателя площадки
     * @param bookDir  каталог {@code bnb-book}
     */
    public static void run(String journals, String venueDb, String bookDir, String from, String to, String out)
            throws Exception {
        long fromMs = from.isBlank() ? 0 : Instant.parse(from).toEpochMilli();
        long toMs = to.isBlank() ? Long.MAX_VALUE : Instant.parse(to).toEpochMilli();
        Map<String, List<String>> jr = new LinkedHashMap<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            jr.put(kv[0].trim(), Arrays.asList(kv[1].split(";")));
        }
        List<Fill> fills = loadVenue(venueDb, fromMs, toMs, jr.keySet());
        for (var e : jr.entrySet()) {
            attachJournal(e.getKey(), e.getValue(), fills);
        }
        attachBinance(Path.of(bookDir), fills);
        try (PrintStream ps = out.isBlank() ? null : new PrintStream(Files.newOutputStream(Path.of(out)), true,
                StandardCharsets.UTF_8)) {
            report(fills, jr.keySet(), ps == null ? System.out : ps);
        }
    }

    static List<Fill> loadVenue(String db, long from, long to, java.util.Set<String> bots) throws SQLException {
        List<Fill> res = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             Statement st = c.createStatement()) {
            ResultSet r = st.executeQuery("SELECT o.bot, t.symbol, o.side, t.tdt, o.created_ms, t.price, t.qty "
                    + "FROM trade t JOIN order_info o USING(oid) WHERE o.bot IS NOT NULL AND t.tdt >= " + from
                    + " AND t.tdt < " + to + " ORDER BY t.tdt");
            while (r.next()) {
                if (!bots.contains(r.getString(1))) {
                    continue;
                }
                Fill f = new Fill();
                f.bot = r.getString(1);
                f.sym = r.getString(2).replace("-", "").replace("/", "");
                f.sg = r.getString(3).toLowerCase(Locale.ROOT).startsWith("b") ? 1 : -1;
                f.t = r.getLong(4);
                f.tSet = r.getLong(5);
                f.p = r.getDouble(6);
                f.usd = f.p * r.getDouble(7);
                res.add(f);
            }
            // уровень — из журналов по id заявки (ниже)
        }
        return res;
    }

    /** Опора бота на тике постановки и на последнем тике до сделки, уровень сделки. */
    static void attachJournal(String bot, List<String> paths, List<Fill> fills) throws SQLException {
        List<Fill> mine = fills.stream().filter(f -> f.bot.equals(bot)).toList();
        if (mine.isEmpty()) {
            return;
        }
        // тики всех журналов бота, по времени
        TreeMap<Long, double[]> ticks = new TreeMap<>();
        Map<Long, Integer> levelByT = new HashMap<>();
        for (String p : paths) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + p.trim() + "?mode=ro");
                 Statement st = c.createStatement()) {
                ResultSet r = st.executeQuery("SELECT ts_ms, fair, bid, ask FROM exec_quote WHERE fair > 0");
                while (r.next()) {
                    ticks.put(r.getLong(1), new double[]{r.getDouble(2), nz(r, 3), nz(r, 4)});
                }
                r = st.executeQuery("SELECT price, side, level, ts_ms FROM exec_fill WHERE level IS NOT NULL");
                while (r.next()) {
                    // ключ — цена и сторона; уровень у сделки по той же цене один
                    levelByT.put(key(r.getDouble(1), r.getString(2)), r.getInt(3));
                }
            }
        }
        for (Fill f : mine) {
            var s = ticks.floorEntry(f.tSet);
            var n = ticks.floorEntry(f.t);
            if (s != null && f.tSet - s.getKey() < 30_000) {
                f.fairSet = s.getValue()[0];
            }
            if (n != null && f.t - n.getKey() < 30_000) {
                f.fairT = n.getValue()[0];
                f.tgtT = f.sg > 0 ? n.getValue()[1] : n.getValue()[2];
            }
            Integer lv = levelByT.get(key(f.p, f.sg > 0 ? "BUY" : "SELL"));
            f.level = lv == null ? -1 : lv;
        }
    }

    private static long key(double price, String side) {
        long k = Math.round(price * 1e6);
        return side.toUpperCase(Locale.ROOT).startsWith("B") ? k : -k;
    }

    private static double nz(ResultSet r, int i) throws SQLException {
        double v = r.getDouble(i);
        return r.wasNull() ? Double.NaN : v;
    }

    /**
     * Середина Бинанса на все нужные моменты одним проходом по архиву: запросы каждой пары
     * сортируются, ответ — последняя середина не позже момента (и её возраст).
     */
    static void attachBinance(Path dir, List<Fill> fills) throws IOException {
        Map<String, List<long[]>> qs = new HashMap<>(); // sym → [время, номер сделки, номер запроса]
        for (int i = 0; i < fills.size(); i++) {
            long[] q = fills.get(i).queries();
            for (int k = 0; k < q.length; k++) {
                qs.computeIfAbsent(fills.get(i).sym, x -> new ArrayList<>()).add(new long[]{q[k], i, k});
            }
            Arrays.fill(fills.get(i).b, Double.NaN);
        }
        Map<String, long[][]> sorted = new HashMap<>();
        Map<String, int[]> ptr = new HashMap<>();
        Map<String, double[]> last = new HashMap<>(); // mid, ts
        long secFrom = Long.MAX_VALUE, secTo = 0;
        for (Fill f : fills) {
            secFrom = Math.min(secFrom, f.t / 1000 - 400);
            secTo = Math.max(secTo, f.t / 1000 + 100);
        }
        final long s0 = secFrom;
        final int nSec = (int) Math.max(0, secTo - secFrom);
        Map<String, double[][]> secHL = new HashMap<>(); // sym → {max, min} по секундам
        for (var e : qs.entrySet()) {
            long[][] a = e.getValue().toArray(new long[0][]);
            Arrays.sort(a, (x, y) -> Long.compare(x[0], y[0]));
            sorted.put(e.getKey(), a);
            ptr.put(e.getKey(), new int[1]);
            last.put(e.getKey(), new double[]{Double.NaN, 0});
        }
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith("book-")).sorted().toList();
        }
        for (Path p : files) {
            try (InputStream in0 = Files.newInputStream(p);
                 InputStream in = p.toString().endsWith(".gz") ? new GZIPInputStream(in0, 1 << 16) : in0;
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII), 1 << 16)) {
                for (String l; (l = br.readLine()) != null; ) {
                    int sp = l.indexOf(' ');
                    int si = l.indexOf("\"s\":\"", sp);
                    if (sp < 0 || si < 0) {
                        continue;
                    }
                    String sym = l.substring(si + 5, l.indexOf('"', si + 5));
                    long[][] a = sorted.get(sym);
                    if (a == null) {
                        continue;
                    }
                    long ts = Long.parseLong(l, 0, sp, 10);
                    double mid = (field(l, "\"b\":\"") + field(l, "\"a\":\"")) / 2;
                    int[] pt = ptr.get(sym);
                    double[] ls = last.get(sym);
                    while (pt[0] < a.length && a[pt[0]][0] < ts) {
                        Fill f = fills.get((int) a[pt[0]][1]);
                        int k = (int) a[pt[0]][2];
                        f.b[k] = ls[0];
                        f.age[k] = a[pt[0]][0] - (long) ls[1];
                        pt[0]++;
                    }
                    ls[0] = mid;
                    ls[1] = ts;
                    long sec = ts / 1000 - s0;
                    if (sec >= 0 && sec < nSec) {
                        double[][] hl = secHL.computeIfAbsent(sym, x -> {
                            double[][] z = new double[2][nSec];
                            Arrays.fill(z[0], Double.NaN);
                            Arrays.fill(z[1], Double.NaN);
                            return z;
                        });
                        int k = (int) sec;
                        hl[0][k] = Double.isNaN(hl[0][k]) ? mid : Math.max(hl[0][k], mid);
                        hl[1][k] = Double.isNaN(hl[1][k]) ? mid : Math.min(hl[1][k], mid);
                    }
                }
            }
        }
        for (Fill f : fills) {
            double[][] hl = secHL.get(f.sym);
            if (hl == null) {
                continue;
            }
            int si = (int) (f.t / 1000 - s0);
            f.vol60 = range(hl, si - 60, si);
            f.vol300 = range(hl, si - 300, si);
            f.volAfter60 = range(hl, si, si + 60);
        }
    }

    /** Размах середины за секунды [from, to), б.п. от середины окна; NaN — обновлений нет. */
    static double range(double[][] hl, int from, int to) {
        double hi = Double.NaN, lo = Double.NaN;
        for (int k = Math.max(0, from); k < Math.min(to, hl[0].length); k++) {
            if (!Double.isNaN(hl[0][k])) {
                hi = Double.isNaN(hi) ? hl[0][k] : Math.max(hi, hl[0][k]);
                lo = Double.isNaN(lo) ? hl[1][k] : Math.min(lo, hl[1][k]);
            }
        }
        return Double.isNaN(hi) ? Double.NaN : (hi - lo) / ((hi + lo) / 2) * 1e4;
    }

    private static double field(String l, String name) {
        int i = l.indexOf(name) + name.length();
        return Double.parseDouble(l.substring(i, l.indexOf('"', i)));
    }

    /** Накопитель: сумма, сумма квадратов, число — и то же в долларах. */
    static final class Acc {
        double n, s, ss, usd, sUsd;

        void add(double v, double usd) {
            if (Double.isNaN(v)) {
                return;
            }
            n++;
            s += v;
            ss += v * v;
            this.usd += usd;
            sUsd += v * usd / 1e4;
        }

        double mean() {
            return n > 0 ? s / n : Double.NaN;
        }

        double se() {
            return n > 1 ? Math.sqrt((ss - s * s / n) / (n - 1) / n) : Double.NaN;
        }

        String fmt() {
            return n == 0 ? "      —      " : String.format(Locale.ROOT, "%+6.2f ±%4.2f", mean(), se());
        }
    }

    static final long MAX_AGE_MS = 5_000;

    /** Слагаемые сделки; null — данных не хватило. [отступ, проспали, отбор@H..., ценность@H..., до сделки 3с, отстали] */
    static double[] parts(Fill f) {
        for (int k = 0; k < f.b.length; k++) {
            if (!(f.b[k] > 0) || f.age[k] > MAX_AGE_MS) {
                return null;
            }
        }
        if (!(f.fairSet > 0)) {
            return null;
        }
        double off = f.sg * (f.fairSet - f.p) / f.fairSet * 1e4;
        double stale = f.sg * (f.b[2] / f.b[0] - 1) * 1e4;
        double[] r = new double[2 + 2 * H.length + 2];
        r[0] = off;
        r[1] = stale;
        for (int i = 0; i < H.length; i++) {
            double adv = f.sg * (f.b[3 + i] / f.b[2] - 1) * 1e4;
            r[2 + i] = adv;
            r[2 + H.length + i] = off + stale + adv;
        }
        r[2 + 2 * H.length] = f.sg * (f.b[2] / f.b[1] - 1) * 1e4;
        r[3 + 2 * H.length] = f.level == 0 && f.tgtT > 0 && f.fairT > 0
                ? f.sg * (f.tgtT - f.p) / f.fairT * 1e4 : Double.NaN;
        return r;
    }

    /**
     * ЦЕННОСТЬ ПО ВОЛАТИЛЬНОСТИ (вопрос владельца 15.10.2026: «в волатильность бот торгует в
     * минус»). Прежние проверки (A72, A74, A88) шли по ЧАСАМ; здесь — размах середины Бинанса
     * в минутах вокруг каждой сделки. Ценность@300/900 включает ход после сделки, то есть
     * цену того, что запас остался в руках на рынке, который едет.
     */
    static void volTable(List<Fill> fills, java.util.Set<String> bots, PrintStream o, String title, int which,
                         double[] edges) {
        int iV60 = 2 + H.length + 2, iV300 = 2 + H.length + 3, iV900 = 2 + H.length + 4;
        String[] coins = {"BTC", "ETH", "SOL", "XRP"};
        o.println();
        o.println(title);
        o.println("размах, б.п. | сделок | доля | ценность@60с | ценность@300с | ценность@900с | $@300с | хуже нуля @300 | @300 по монетам: BTC / ETH / SOL / XRP");
        double total = 0;
        for (Fill f : fills) {
            if (parts(f) != null) {
                total++;
            }
        }
        for (int b = 0; b + 1 < edges.length; b++) {
            Acc v60 = new Acc(), v300 = new Acc(), v900 = new Acc();
            Acc[] byCoin = {new Acc(), new Acc(), new Acc(), new Acc()};
            double neg = 0;
            for (Fill f : fills) {
                double[] r = parts(f);
                double v = which == 0 ? f.vol60 : which == 1 ? f.vol300 : f.volAfter60;
                if (r == null || Double.isNaN(v) || v < edges[b] || v >= edges[b + 1]) {
                    continue;
                }
                v60.add(r[iV60], f.usd);
                v300.add(r[iV300], f.usd);
                v900.add(r[iV900], f.usd);
                if (r[iV300] < 0) {
                    neg++;
                }
                for (int c = 0; c < coins.length; c++) {
                    if (f.sym.startsWith(coins[c])) {
                        byCoin[c].add(r[iV300], f.usd);
                    }
                }
            }
            String nm = Double.isInfinite(edges[b + 1]) ? String.format(Locale.ROOT, "≥%.0f", edges[b])
                    : String.format(Locale.ROOT, "%.0f–%.0f", edges[b], edges[b + 1]);
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%-8s | %5.0f | %4.1f%% | %s | %s | %s | %+6.2f | %4.1f%%",
                    nm, v60.n, total > 0 ? 100 * v60.n / total : 0, v60.fmt(), v300.fmt(), v900.fmt(), v300.sUsd,
                    v300.n > 0 ? 100 * neg / v300.n : 0));
            for (Acc c : byCoin) {
                s.append(c.n < 20 ? " |    —  " : String.format(Locale.ROOT, " | %+6.1f(%.0f)", c.mean(), c.n));
            }
            o.println(s);
        }
    }

    static void report(List<Fill> fills, java.util.Set<String> bots, PrintStream o) {
        int iV60 = 2 + H.length + 2, iV300 = 2 + H.length + 3;
        o.println("КАЧЕСТВО СДЕЛКИ: слагаемые в б.п. (среднее ± ошибка среднего), «+» в нашу пользу. Опора — середина Бинанса (bnb-book).");
        o.println("отступ — к опоре бота при постановке цены; проспали — ход Бинанса от постановки до сделки; отбор@H — ход после; ценность@H = сумма.");
        o.println();
        o.println("бот | сделок | без данных (нет тика опоры/нет Бинанса) | объём $ | отступ | проспали | отбор@1с | отбор@5с | отбор@60с | ЦЕННОСТЬ@60с | $@60с | ценность@300с | проспали ≤−3: доля, $ | возраст цены, медиана с | отстали от цели (ур.0)");
        Acc[] all = new Acc[2 + 2 * H.length + 2];
        for (int i = 0; i < all.length; i++) {
            all[i] = new Acc();
        }
        for (String bot : bots) {
            Acc[] a = new Acc[all.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = new Acc();
            }
            int n = 0, miss = 0;
            int noFair = 0, noBnb = 0;
            double staleN = 0, staleUsd = 0;
            List<Long> ages = new ArrayList<>();
            for (Fill f : fills) {
                if (!f.bot.equals(bot)) {
                    continue;
                }
                n++;
                double[] r = parts(f);
                if (r == null) {
                    miss++;
                    if (!(f.fairSet > 0)) {
                        noFair++;
                    } else {
                        noBnb++;
                    }
                    continue;
                }
                for (int i = 0; i < r.length; i++) {
                    a[i].add(r[i], f.usd);
                    all[i].add(r[i], f.usd);
                }
                if (r[1] <= -STALE_BP) {
                    staleN++;
                    staleUsd += r[1] * f.usd / 1e4;
                }
                ages.add(f.t - f.tSet);
            }
            if (n == 0) {
                continue;
            }
            ages.sort(null);
            double used = a[0].n;
            o.printf(Locale.ROOT, "%-3s | %5d | %4d (%d/%d) | %7.0f | %s | %s | %s | %s | %s | %s | %+6.2f | %s | %4.1f%% %+6.2f | %5.1f | %s%n",
                    bot, n, miss, noFair, noBnb, a[0].usd, a[0].fmt(), a[1].fmt(), a[2].fmt(), a[3].fmt(), a[4].fmt(), a[iV60].fmt(),
                    a[iV60].sUsd, a[iV300].fmt(), used > 0 ? 100 * staleN / used : 0, staleUsd,
                    ages.isEmpty() ? 0 : ages.get(ages.size() / 2) / 1000.0, a[a.length - 1].fmt());
        }
        o.printf(Locale.ROOT, "ВСЕ | %5.0f |      | %7.0f | %s | %s | %s | %s | %s | %s | %+6.2f | %s%n", all[0].n, all[0].usd,
                all[0].fmt(), all[1].fmt(), all[2].fmt(), all[3].fmt(), all[4].fmt(), all[iV60].fmt(), all[iV60].sUsd, all[iV300].fmt());

        o.println();
        o.println("ГДЕ ТЕРЯЕМ: по ходу Бинанса от постановки до сделки (проспали), все боты");
        o.println("проспали, б.п. | сделок | доля | отступ | проспали | отбор@60с | ценность@60с | $@60с");
        double[] edges = {Double.NEGATIVE_INFINITY, -10, -5, -3, -1, 1, Double.POSITIVE_INFINITY};
        String[] names = {"< −10", "−10…−5", "−5…−3", "−3…−1", "−1…+1", "> +1"};
        for (int b = 0; b < names.length; b++) {
            Acc off = new Acc(), st = new Acc(), adv = new Acc(), v = new Acc();
            for (Fill f : fills) {
                double[] r = parts(f);
                if (r == null || r[1] < edges[b] || r[1] >= edges[b + 1]) {
                    continue;
                }
                off.add(r[0], f.usd);
                st.add(r[1], f.usd);
                adv.add(r[4], f.usd);
                v.add(r[iV60], f.usd);
            }
            o.printf(Locale.ROOT, "%-8s | %5.0f | %4.1f%% | %s | %s | %s | %s | %+6.2f%n", names[b], off.n,
                    all[0].n > 0 ? 100 * off.n / all[0].n : 0, off.fmt(), st.fmt(), adv.fmt(), v.fmt(), v.sUsd);
        }

        volTable(fills, bots, o, "РАЗМАХ БИНАНСА ЗА 60 с ДО СДЕЛКИ (известен заранее — годится для правила)", 0,
                new double[]{0, 5, 10, 20, 40, 80, Double.POSITIVE_INFINITY});
        volTable(fills, bots, o, "РАЗМАХ БИНАНСА ЗА 300 с ДО СДЕЛКИ", 1,
                new double[]{0, 10, 20, 40, 80, 160, Double.POSITIVE_INFINITY});
        volTable(fills, bots, o, "РАЗМАХ БИНАНСА ЗА 60 с ПОСЛЕ СДЕЛКИ (заранее НЕ известен — только для понимания)", 2,
                new double[]{0, 5, 10, 20, 40, 80, Double.POSITIVE_INFINITY});

        o.println();
        o.println("ПО СУТКАМ: ценность@60с (среднее ± ошибка), сделок; в скобках — проспали");
        TreeMap<String, Map<String, Acc[]>> days = new TreeMap<>();
        for (Fill f : fills) {
            double[] r = parts(f);
            if (r == null) {
                continue;
            }
            String d = Instant.ofEpochMilli(f.t).atZone(ZoneOffset.UTC).toLocalDate().toString().substring(5);
            Acc[] x = days.computeIfAbsent(d, k -> new LinkedHashMap<>())
                    .computeIfAbsent(f.bot, k -> new Acc[]{new Acc(), new Acc()});
            x[0].add(r[iV60], f.usd);
            x[1].add(r[1], f.usd);
        }
        StringBuilder hdr = new StringBuilder("сутки");
        for (String bot : bots) {
            hdr.append(" | ").append(String.format("%-24s", bot));
        }
        o.println(hdr);
        for (var d : days.entrySet()) {
            StringBuilder s = new StringBuilder(d.getKey());
            for (String bot : bots) {
                Acc[] x = d.getValue().get(bot);
                s.append(" | ").append(x == null ? String.format("%-24s", "—")
                        : String.format(Locale.ROOT, "%s %3.0f (%+5.1f)", x[0].fmt(), x[0].n, x[1].mean()));
            }
            o.println(s);
        }
    }
}
