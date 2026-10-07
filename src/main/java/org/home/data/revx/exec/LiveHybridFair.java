package org.home.data.revx.exec;

import org.home.data.revx.replay.MarketData;
import org.home.data.revx.sim.BookView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * ЖИВАЯ ОПОРА «СМЕСЬ + ГЛУБИНА + БИНАНС» (06.10.2026) — обёртка над прежней опорой
 * ({@link StandReader}), считает тем же {@link HybridCore}, что и стенд.
 *
 * Источники:
 * <ul>
 *   <li>прежняя опора — {@code StandReader.latest} (корзина пар площадки), как прежде;</li>
 *   <li>своя книга — последний снимок {@code <монета>/USDC} из базы сборщика, верхние
 *       пять уровней и глубина {@code deep_bids/deep_asks} (до 50 уровней);</li>
 *   <li>Бинанс — последняя середина {@code <монета>USDC} из {@code bnb.db}, которую
 *       пишет служба {@code --revx-bnb-feed} раз в секунду.</li>
 * </ul>
 *
 * ⚠️ ОТКАТ: книга старше {@link #MAX_AGE_MS} или Бинанс молчит дольше того же срока —
 * возвращается прежняя опора без изменений, и в журнал пишется событие
 * {@code hybrid_fallback} (раз в минуту, чтобы не засорять). Бот при этом продолжает
 * торговать как раньше, а не останавливается.
 *
 * Включается {@code -Drevx.fair.live-hybrid=true}; параметры — {@code revx.fair.hybrid-mix},
 * {@code -depth-k}, {@code -tau-sec}, {@code -max-spread-bp}; база Бинанса —
 * {@code revx.bnb.db}.
 */
public final class LiveHybridFair implements FairSource {

    private static final Logger log = LoggerFactory.getLogger(LiveHybridFair.class);

    public static final boolean ENABLED = Boolean.getBoolean("revx.fair.live-hybrid");
    static final long MAX_AGE_MS = Long.getLong("revx.fair.hybrid-max-age-ms", 15_000L);

    private final FairSource inner;
    private final String standDbPath;
    private final String bnbDbPath;
    private final String symbol;
    private final String bnbSymbol;
    private final double lotQty;
    private final ExecJournal journal;
    private final HybridCore core;
    private Connection book;
    private Connection bnb;
    private long lastFallbackEventMs;
    private long lastNoBasketEventMs;
    /** Когда последний раз видели свою книгу и цену Бинанса (любой давности). */
    private long lastBookMs;
    private long lastBnbMs;
    /**
     * Состояние бота для сообщения о сбое: {запас, опора, котирует ли (1/0)}. Без него
     * сводка писала «распродаёт запас», даже когда запаса не было (просьба владельца
     * 07.10.2026: «что именно нарушено и какие действия делает бот»).
     */
    private java.util.function.Supplier<double[]> botState;

    /** Подключить состояние бота ({@link QuoteLoop} делает это сам). */
    public void attach(java.util.function.Supplier<double[]> state) {
        this.botState = state;
    }
    private long hybridTicks;
    private long fallbackTicks;

    public LiveHybridFair(FairSource inner, String standDbPath, String symbol, double lotQty,
                          ExecJournal journal) {
        this.inner = inner;
        this.standDbPath = standDbPath;
        this.bnbDbPath = System.getProperty("revx.bnb.db", "/home/ubuntu/revx-shared/bnb.db");
        this.symbol = symbol;
        this.bnbSymbol = symbol.substring(0, symbol.indexOf('/')) + "USDC";
        this.lotQty = lotQty;
        this.journal = journal;
        this.core = new HybridCore(
                Double.parseDouble(System.getProperty("revx.fair.hybrid-mix", "0.25")),
                Double.parseDouble(System.getProperty("revx.fair.hybrid-depth-k", "10")),
                Double.parseDouble(System.getProperty("revx.fair.hybrid-tau-sec", "180")),
                Double.parseDouble(System.getProperty("revx.fair.hybrid-max-spread-bp", "50")));
        log.warn("опора СМЕСЬ+ГЛУБИНА+БИНАНС для {}: смесь {}, глубина {} лота ({} монеты), τ {} с, "
                        + "Бинанс {} из {}", symbol, System.getProperty("revx.fair.hybrid-mix", "0.25"),
                System.getProperty("revx.fair.hybrid-depth-k", "10"),
                Double.parseDouble(System.getProperty("revx.fair.hybrid-depth-k", "10")) * lotQty,
                System.getProperty("revx.fair.hybrid-tau-sec", "180"), bnbSymbol, bnbDbPath);
    }

    /**
     * АВАРИЙНЫЙ РЕЖИМ (06.10.2026, решение владельца): что-то из источников сломалось —
     * бот не продолжает торговать как ни в чём не бывало, а распродаётся и встаёт в
     * паузу ({@link QuoteLoop} смотрит {@link #degraded()}), пока всё не будет в норме
     * {@link #RECOVER_MS} подряд. Вход и выход пишутся событиями
     * {@code hybrid_degraded}/{@code hybrid_recovered}; по входу будит сводный бот.
     *
     * Опора на время сбоя — та, что лучше всех держалась на падении, насколько её
     * можно посчитать: молчит Бинанс → смесь прежней опоры и своей книги по глубине
     * без множителя Бинанса; нет своей книги → прежняя опора; нет ничего → котировать
     * нельзя (прежний гейт отведёт заявки).
     */
    private final long RECOVER_MS = Long.getLong("revx.fair.hybrid-recover-ms", 120_000L);
    private boolean degraded;
    private long healthySince;
    private String degradedWhy = "";

    public boolean degraded() {
        return degraded;
    }

    public String degradedWhy() {
        return degradedWhy;
    }

    @Override
    public StandReader.Fair latest(String base, long lookbackMs) {
        StandReader.Fair f = inner.latest(base, lookbackMs);
        long now = System.currentTimeMillis();
        String why;
        BookView b = null;
        try {
            b = ownBook(now);
            double mid = bnbMid(now);
            if (b == null) {
                why = "своей книги " + symbol + " нет или она старше " + MAX_AGE_MS / 1000 + " с";
            } else if (!(mid > 0)) {
                why = "Бинанс " + bnbSymbol + " молчит дольше " + MAX_AGE_MS / 1000 + " с";
            } else {
                HybridCore.Out o = core.step(now, f.price(), f.quotable(), b, lotQty, mid);
                if (o == null) {
                    why = "нет прежней опоры";
                } else {
                    hybridTicks++;
                    if (!(f.price() > 0) && now - lastNoBasketEventMs >= 600_000) {
                        // Не сбой (07.10.2026): уровень на это время — своя книга.
                        lastNoBasketEventMs = now;
                        journal.event("hybrid_no_basket", symbol + ": прежней опоры (корзины) нет — "
                                + "уровень по своей книге, движение по Бинансу, торгую как обычно");
                    }
                    healthy(now);
                    return new StandReader.Fair(o.fair(), o.quotable(), o.quotable() ? null : f.pausedReason(),
                            f.asOfMs(), f.pairsUsed(), b.bestBid(), b.bestAsk(), f.referenceSpreadPct());
                }
            }
        } catch (Exception e) {
            why = "ошибка чтения: " + e.getMessage();
            closeQuietly();
        }
        fallbackTicks++;
        // Аварийная опора: уровень по своей книге без Бинанса, если книга есть.
        Double level = b == null ? null : core.levelOnly(now, f.price(), b, lotQty);
        broken(now, why, f, level);
        if (level != null) {
            boolean ok = (b.bestAsk() - b.bestBid()) / ((b.bestAsk() + b.bestBid()) / 2) * 1e4
                    <= Double.parseDouble(System.getProperty("revx.fair.hybrid-max-spread-bp", "50"));
            return new StandReader.Fair(level, ok || f.quotable(), ok || f.quotable() ? null : f.pausedReason(),
                    f.asOfMs(), f.pairsUsed(), b.bestBid(), b.bestAsk(), f.referenceSpreadPct());
        }
        return f;
    }

    private void healthy(long now) {
        if (!degraded) {
            return;
        }
        if (healthySince == 0) {
            healthySince = now;
        }
        if (now - healthySince >= RECOVER_MS) {
            degraded = false;
            healthySince = 0;
            journal.event("hybrid_recovered", symbol + ": опора снова в норме " + RECOVER_MS / 1000
                    + " с подряд — обычная торговля (сбой был: " + degradedWhy + ")");
            log.warn("опора {} снова в норме — обычная торговля", symbol);
        }
    }

    /** Сколько назад: «3 с», «нет данных». */
    private static String age(long now, long ts) {
        return ts <= 0 ? "нет данных" : (now - ts) / 1000 + " с назад";
    }

    /**
     * Полный текст сбоя для журнала и сводного бота: что нарушено, в каком состоянии
     * каждый источник, от какой опоры котируем и что делаем.
     */
    private String describe(long now, String why, StandReader.Fair f, Double level) {
        String base = symbol.substring(0, symbol.indexOf('/'));
        boolean bookOk = lastBookMs > 0 && now - lastBookMs <= MAX_AGE_MS;
        boolean bnbOk = lastBnbMs > 0 && now - lastBnbMs <= MAX_AGE_MS;
        boolean oldOk = f != null && f.price() > 0;
        StringBuilder sb = new StringBuilder(symbol).append(": ").append(why).append('\n');
        sb.append("Источники: своя книга — ").append(bookOk ? "в норме" : "НЕТ")
                .append(" (").append(age(now, lastBookMs)).append("); Бинанс ").append(bnbSymbol)
                .append(" — ").append(bnbOk ? "в норме" : "МОЛЧИТ").append(" (").append(age(now, lastBnbMs))
                .append("); прежняя опора (корзина) — ").append(oldOk ? "есть" : "нет").append('\n');
        sb.append("Опора на время сбоя: ").append(level != null
                ? "уровень по своей книге и прежней опоре, без движения Бинанса"
                : oldOk ? "прежняя опора (корзина пар)" : "нет — котировать не от чего").append('\n');
        double[] st = botState == null ? null : botState.get();
        String act;
        if (st == null) {
            act = "покупки снимаю; запас, если есть, продаю аском с целью 0; без запаса — пауза";
        } else if (st[2] < 0.5) {
            act = "котирование и так выключено — только жду";
        } else if (level == null && !oldOk) {
            act = "все заявки снимаю — опоры нет; пауза";
        } else if (st[0] * st[1] >= 1) {
            act = String.format(java.util.Locale.ROOT,
                    "покупки снял; запас %s %s (≈$%.2f) продаю аском по этой опоре с целью 0, "
                            + "распродав — пауза", fmt(st[0]), base, st[0] * st[1]);
        } else {
            act = "запаса нет — заявки снял, пауза";
        }
        sb.append("Делаю: ").append(act).append('\n');
        sb.append("Вернусь сам, когда все источники будут в норме ").append(RECOVER_MS / 1000).append(" с подряд.");
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.8f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void broken(long now, String why, StandReader.Fair f, Double level) {
        healthySince = 0;
        if (!degraded) {
            degraded = true;
            degradedWhy = why;
            journal.event("hybrid_degraded", describe(now, why, f, level));
            log.error("опора {} нарушена: {} — распродажа и пауза", symbol, why);
        } else if (now - lastFallbackEventMs >= 600_000) {
            lastFallbackEventMs = now;
            journal.event("hybrid_fallback", symbol + ": по-прежнему " + why
                    + String.format(java.util.Locale.ROOT, " (гибрид %d тиков, сбой %d)", hybridTicks, fallbackTicks));
        }
    }

    private BookView ownBook(long now) throws Exception {
        if (book == null) {
            book = DriverManager.getConnection("jdbc:sqlite:file:" + standDbPath + "?mode=ro");
        }
        try (PreparedStatement ps = book.prepareStatement(
                "SELECT t_recv_ms, bp1,bq1,bp2,bq2,bp3,bq3,bp4,bq4,bp5,bq5,"
                        + "ap1,aq1,ap2,aq2,ap3,aq3,ap4,aq4,ap5,aq5, deep_bids, deep_asks"
                        + " FROM revx_book WHERE symbol = ? ORDER BY t_recv_ms DESC LIMIT 1")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                lastBookMs = rs.getLong(1);
                if (now - lastBookMs > MAX_AGE_MS) {
                    return null;
                }
                List<BookView.Level> bids = new ArrayList<>();
                List<BookView.Level> asks = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    double p = rs.getDouble(2 + i * 2);
                    double q = rs.getDouble(3 + i * 2);
                    if (p > 0 && q > 0) {
                        bids.add(new BookView.Level(p, q));
                    }
                    p = rs.getDouble(12 + i * 2);
                    q = rs.getDouble(13 + i * 2);
                    if (p > 0 && q > 0) {
                        asks.add(new BookView.Level(p, q));
                    }
                }
                MarketData.appendDeep(bids, rs.getString(22));
                MarketData.appendDeep(asks, rs.getString(23));
                return new BookView(bids, asks);
            }
        }
    }

    private double bnbMid(long now) throws Exception {
        if (bnb == null) {
            bnb = DriverManager.getConnection("jdbc:sqlite:file:" + bnbDbPath + "?mode=ro");
        }
        try (PreparedStatement ps = bnb.prepareStatement(
                "SELECT ts_ms, bid, ask FROM bnb_tick WHERE symbol = ? ORDER BY ts_ms DESC LIMIT 1")) {
            ps.setString(1, bnbSymbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Double.NaN;
                }
                lastBnbMs = rs.getLong(1);
                if (now - lastBnbMs > MAX_AGE_MS) {
                    return Double.NaN;
                }
                return (rs.getDouble(2) + rs.getDouble(3)) / 2;
            }
        }
    }

    /** Закрыть соединения с базами (тест, прибор проверки). */
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (book != null) {
                book.close();
            }
        } catch (Exception ignore) {
            // переоткроется на следующем тике
        }
        try {
            if (bnb != null) {
                bnb.close();
            }
        } catch (Exception ignore) {
            // переоткроется на следующем тике
        }
        book = null;
        bnb = null;
    }
}
