package org.home.data.revx.exec;

import org.home.data.revx.sim.BookView;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * БЫСТРАЯ ОПОРА ТОЛЬКО ОТ БИНАНСА (пилот ADA, 15.10.2026): середина {@code <BASE>USDT},
 * делённая на середину {@code USDCUSDT}, — по websocket ({@link BnbStream}), на каждое
 * изменение лучших цен.
 *
 * <h2>Почему USDT, а не USDC</h2>
 *
 * Посекундные свечи 07–13.10: ADAUSDT — 159 тыс. сделок в сутки против 27 тыс. у
 * ADAUSDC, и USDT ведёт (корреляция хода USDT в t с ходом USDC в t+1 равна 0.088, в t−1 —
 * 0.048). Перед тейкером-продавцом Revolut ADAUSDT успевает упасть на 2.92 б.п., ADAUSDC —
 * на 2.37. Курс USDC/USDT при этом не единица (медиана 1.0008, 1–99% 1.00003–1.00118),
 * поэтому делить на него обязательно: иначе опора съедет на 8 б.п.
 *
 * <h2>Сбой</h2>
 *
 * Сессия websocket молчит дольше {@link #MAX_AGE_MS} (нет сообщений НИ ПО ОДНОЙ паре)
 * или какая-то из цен не менялась дольше {@link #LEG_MAX_AGE_MS} — опора НЕ котируема.
 * ⚠️ Возраст отдельной пары с порогом в 3 с не годится: {@code bookTicker} шлёт
 * сообщение только на изменение верха книги, и при живом соединении курс USDCUSDT (а
 * ночью и ADAUSDT) стоит секундами. Сухой прогон 14.10 с таким правилом уводил заявки
 * 156 раз за час (12% тиков) при нуле обрывов. Неизменная цена при живой сессии —
 * верная цена. Котировщик
 * отводит заявки заменой на {@code park-distance} от последней доверенной цены
 * (решение владельца: при обрыве связи с Бинансом — на 10%), а не торгует по старой.
 *
 * <h2>Своя книга</h2>
 *
 * Нужна только для двух вещей: зажима {@code post_only} ({@code noCross}) и правила
 * «встать за лучшим чужим аском». Берётся последний снимок сборщика не старше
 * {@link #BOOK_MAX_AGE_MS}: у ADA хвост собирается раз в 15 с, и на старой книге
 * нижняя граница «опора +20» всё равно держит цену — теряются исполнения, а не деньги.
 */
public final class FastBnbFair implements FairSource {

    public static final boolean ENABLED = Boolean.getBoolean("revx.fair.fast-bnb");
    static final long MAX_AGE_MS = Long.getLong("revx.fair.fast-max-age-ms", 3_000L);
    /** Пара не менялась дольше этого при живой сессии — тоже сбой (подписка отвалилась). */
    static final long LEG_MAX_AGE_MS = Long.getLong("revx.fair.fast-leg-max-age-ms", 60_000L);
    static final long BOOK_MAX_AGE_MS = Long.getLong("revx.fair.fast-book-max-age-ms", 60_000L);
    /** История опоры для правила рывка: столько миллисекунд назад. */
    static final long HISTORY_MS = 10_000L;

    private final BnbStream stream;
    private final String pair;
    private final String rate;
    private final String symbol;
    private final String standDbPath;
    private final ArrayDeque<double[]> history = new ArrayDeque<>();
    private volatile DoubleConsumer onPrice = p -> { };
    private Connection book;
    private BookView cachedBook;
    private long cachedBookAtMs;

    /**
     * @param symbol      пара площадки, {@code ADA/USDC}
     * @param pair        пара Бинанса с ценой монеты, {@code ADAUSDT}
     * @param rate        пара Бинанса с курсом валюты котировки, {@code USDCUSDT}; пусто — не делить
     */
    public FastBnbFair(BnbStream stream, String symbol, String pair, String rate, String standDbPath) {
        this.stream = stream;
        this.symbol = symbol;
        this.pair = pair.toUpperCase(Locale.ROOT);
        this.rate = rate == null ? "" : rate.toUpperCase(Locale.ROOT);
        this.standDbPath = standDbPath;
        stream.onUpdate(this::updated);
    }

    /** Пары Бинанса по умолчанию для монеты: {@code <BASE>USDT} и {@code USDCUSDT}. */
    public static FastBnbFair fromProperties(String symbol, String standDbPath) {
        String base = symbol.substring(0, symbol.indexOf('/'));
        String pair = System.getProperty("revx.fair.fast-bnb-pair", base + "USDT");
        String rate = System.getProperty("revx.fair.fast-bnb-rate", "USDCUSDT");
        BnbStream s = rate.isBlank() ? new BnbStream(pair) : new BnbStream(pair, rate);
        FastBnbFair f = new FastBnbFair(s, symbol, pair, rate, standDbPath);
        s.start();
        return f;
    }

    public String describe() {
        return rate.isEmpty() ? pair : pair + " ÷ " + rate;
    }

    public BnbStream stream() {
        return stream;
    }

    /** Котировщик подписывается на каждую новую цену — будить себя. */
    public void onPrice(DoubleConsumer c) {
        this.onPrice = c == null ? p -> { } : c;
    }

    /** Текущая опора, NaN — какой-то из цен нет вовсе. */
    public double price() {
        BnbStream.Tick t = stream.tick(pair);
        if (t == null) {
            return Double.NaN;
        }
        if (rate.isEmpty()) {
            return t.mid();
        }
        BnbStream.Tick r = stream.tick(rate);
        return r == null ? Double.NaN : t.mid() / r.mid();
    }

    /** Возраст самой старой из нужных цен, мс; {@link Long#MAX_VALUE} — цены нет. */
    public long ageMs(long now) {
        BnbStream.Tick t = stream.tick(pair);
        BnbStream.Tick r = rate.isEmpty() ? t : stream.tick(rate);
        if (t == null || r == null) {
            return Long.MAX_VALUE;
        }
        return now - Math.min(t.recvMs(), r.recvMs());
    }

    private void updated() {
        double p = price();
        if (!(p > 0)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (history) {
            history.addLast(new double[]{now, p});
            while (!history.isEmpty() && now - history.peekFirst()[0] > HISTORY_MS) {
                history.pollFirst();
            }
        }
        onPrice.accept(p);
    }

    /**
     * Ход опоры за последние {@code windowMs}: {падение от максимума окна, рост от
     * минимума окна} в б.п., оба ≥ 0. Падение опасно для бида, рост — для аска.
     */
    public double[] moves(long now, long windowMs) {
        double p = price();
        if (!(p > 0)) {
            return new double[]{0, 0};
        }
        double hi = p;
        double lo = p;
        synchronized (history) {
            for (var it = history.descendingIterator(); it.hasNext(); ) {
                double[] h = it.next();
                if (now - h[0] > windowMs) {
                    break;
                }
                hi = Math.max(hi, h[1]);
                lo = Math.min(lo, h[1]);
            }
        }
        return new double[]{(hi - p) / hi * 1e4, (p - lo) / lo * 1e4};
    }

    @Override
    public StandReader.Fair latest(String base, long lookbackMs) {
        long now = System.currentTimeMillis();
        double p = price();
        long age = ageMs(now);
        BookView b = bookNow(now);
        double bb = b == null || b.bids().isEmpty() ? 0 : b.bids().get(0).price();
        double ba = b == null || b.asks().isEmpty() ? 0 : b.asks().get(0).price();
        // Цена подтверждена последним сообщением сессии: верх обеих пар с тех пор не менялся.
        long asOf = age == Long.MAX_VALUE ? 0 : stream.lastMsgMs();
        String why = staleWhy(now);
        return new StandReader.Fair(p > 0 ? p : Double.NaN, why == null, why, asOf, 0, bb, ba, 0);
    }

    /** Почему цене нельзя верить (правила свежести выше); null — можно. */
    public String staleWhy(long now) {
        double p = price();
        long age = ageMs(now);
        long silent = now - stream.lastMsgMs();
        if (!(p > 0) || age == Long.MAX_VALUE) {
            return "Бинанс " + describe() + ": цены ещё нет";
        } else if (silent > MAX_AGE_MS) {
            return String.format(Locale.ROOT, "Бинанс %s молчит %.1f с", describe(), silent / 1000.0);
        } else if (age > LEG_MAX_AGE_MS) {
            return String.format(Locale.ROOT, "Бинанс %s: цена не менялась %.0f с", describe(), age / 1000.0);
        }
        return null;
    }

    /** Своя книга площадки (в ней стоим и мы сами); null — нет свежей. Кеш на 300 мс. */
    public synchronized BookView bookNow(long now) {
        if (cachedBook != null && now - cachedBookAtMs < 300) {
            return cachedBook;
        }
        try {
            if (book == null) {
                book = DriverManager.getConnection("jdbc:sqlite:file:" + standDbPath + "?mode=ro");
            }
            cachedBook = LiveHybridFair.readBook(book, symbol, now, BOOK_MAX_AGE_MS, new long[1]);
        } catch (Exception e) {
            cachedBook = null;
            try {
                if (book != null) {
                    book.close();
                }
            } catch (Exception ignore) {
                // переоткроется на следующем вызове
            }
            book = null;
        }
        cachedBookAtMs = now;
        return cachedBook;
    }

    public void close() {
        stream.close();
    }
}
