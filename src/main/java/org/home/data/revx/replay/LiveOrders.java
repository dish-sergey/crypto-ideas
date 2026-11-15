package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * ЗАЯВКИ ЖИВЫХ БОТОВ В ЗАПИСАННОЙ КНИГЕ (15.11.2026, док. 248). Опора стенда считается заранее по записанной
 * книге, а в ней стоят заявки живых ботов, а не стендовых: стенд видел в уровне опоры живую траекторию. Живой бот
 * с {@code revx.fair.hybrid-exclude-own=true} вычитает свои заявки; стенд, чтобы считать ту же опору, вычитает из
 * записанной книги заявки живых ботов той же монеты.
 *
 * <p>{@code revx.sim.hybrid-live-orders=<каталог>}: {@code q-<бот>.csv.gz} (время, бид, аск — моменты смены цен
 * {@code exec_quote} и строка раз в минуту; пусто — стороны нет) и {@code f-<бот>.csv} (время, сторона, объём
 * исполнения — для лота). Боты монет: a, e — BTC; c, d — ETH; b, f — SOL ({@code revx.sim.hybrid-live-bots}).
 * Заявка считается стоящей, пока у бота есть строка не старше 90 с; объём — лот бота (наибольшее исполнение за
 * 48 ч до момента), не больше объёма уровня. Цена ищется в книге с допуском 0.05 б.п.
 */
final class LiveOrders {

    static final String DIR = System.getProperty("revx.sim.hybrid-live-orders", "");
    static final String BOTS = System.getProperty("revx.sim.hybrid-live-bots", "a:BTC,e:BTC,c:ETH,d:ETH,b:SOL,f:SOL");
    static final long MAX_AGE_MS = 90_000L, LOT_WINDOW_MS = 48 * 3_600_000L;

    private final List<TreeMap<Long, double[]>> quotes = new ArrayList<>();
    private final List<TreeMap<Long, Double>> fills = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private long looked, found, books;

    /** null — каталог не задан или ботов монеты в нём нет. */
    static LiveOrders load(String base) {
        if (DIR.isBlank()) {
            return null;
        }
        LiveOrders lo = new LiveOrders();
        for (String bc : BOTS.split(",")) {
            String[] p = bc.split(":");
            if (!p[1].equals(base)) {
                continue;
            }
            Path q = Path.of(DIR, "q-" + p[0] + ".csv.gz"), f = Path.of(DIR, "f-" + p[0] + ".csv");
            if (!Files.exists(q) || !Files.exists(f)) {
                throw new IllegalStateException("ЗАЯВКИ ЖИВЫХ: нет " + q + " или " + f);
            }
            TreeMap<Long, double[]> qm = new TreeMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(q))))) {
                for (String l; (l = r.readLine()) != null; ) {
                    String[] s = l.split(",", -1);
                    qm.put(Long.parseLong(s[0]), new double[]{num(s[1]), num(s[2])});
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("ЗАЯВКИ ЖИВЫХ: не прочитать " + q, e);
            }
            TreeMap<Long, Double> fm = new TreeMap<>();
            try {
                for (String l : Files.readAllLines(f)) {
                    String[] s = l.split(",");
                    fm.merge(Long.parseLong(s[0]), Double.parseDouble(s[2]), Math::max);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("ЗАЯВКИ ЖИВЫХ: не прочитать " + f, e);
            }
            lo.quotes.add(qm);
            lo.fills.add(fm);
            lo.names.add(p[0]);
        }
        return lo.names.isEmpty() ? null : lo;
    }

    private static double num(String s) {
        return s == null || s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }

    /** Лот бота к моменту: наибольшее исполнение за 48 ч до него (иначе — наибольшее вообще). */
    private double lot(int i, long t) {
        double m = 0;
        for (double v : fills.get(i).subMap(t - LOT_WINDOW_MS, true, t, true).values()) {
            m = Math.max(m, v);
        }
        if (m == 0) {
            for (double v : fills.get(i).values()) {
                m = Math.max(m, v);
            }
        }
        return m;
    }

    /** Книга без заявок живых ботов монеты в момент {@code t}. */
    BookView without(BookView book, long t) {
        if (book == null || book.empty()) {
            return book;
        }
        books++;
        List<double[]> bid = new ArrayList<>(), ask = new ArrayList<>();
        for (int i = 0; i < quotes.size(); i++) {
            Map.Entry<Long, double[]> e = quotes.get(i).floorEntry(t);
            if (e == null || t - e.getKey() > MAX_AGE_MS) {
                continue;
            }
            double lot = lot(i, t);
            if (!(lot > 0)) {
                continue;
            }
            if (e.getValue()[0] > 0) {
                bid.add(new double[]{e.getValue()[0], lot});
            }
            if (e.getValue()[1] > 0) {
                ask.add(new double[]{e.getValue()[1], lot});
            }
        }
        if (bid.isEmpty() && ask.isEmpty()) {
            return book;
        }
        return new BookView(minus(book.bids(), bid), minus(book.asks(), ask));
    }

    private List<BookView.Level> minus(List<BookView.Level> levels, List<double[]> mine) {
        if (mine.isEmpty()) {
            return levels;
        }
        double[] left = new double[mine.size()];
        for (int k = 0; k < mine.size(); k++) {
            left[k] = mine.get(k)[1];
        }
        looked += mine.size();
        boolean[] hit = new boolean[mine.size()];
        List<BookView.Level> out = new ArrayList<>(levels.size());
        for (BookView.Level l : levels) {
            double q = l.qty();
            for (int k = 0; k < mine.size(); k++) {
                if (left[k] > 0 && Math.abs(mine.get(k)[0] - l.price()) <= 5e-6 * l.price()) {
                    double take = Math.min(q, left[k]);
                    q -= take;
                    left[k] -= take;
                    hit[k] = true;
                }
            }
            if (q > 1e-12) {
                out.add(q == l.qty() ? l : new BookView.Level(l.price(), q));
            }
        }
        for (boolean h : hit) {
            if (h) {
                found++;
            }
        }
        return out;
    }

    String report(String symbol) {
        return String.format(Locale.ROOT, "ГИБРИД %s: заявки живых ботов %s вычтены из книги — найдено в книге %d из %d "
                + "(%.1f%%) на %d снимках", symbol, String.join("/", names), found, looked,
                looked == 0 ? 0 : 100.0 * found / looked, books);
    }
}
