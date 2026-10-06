package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code --revx-hedge-paper}: БУМАЖНЫЙ ХЕДЖ НА ЖИВЫХ БОТАХ. Ни одной заявки на
 * Kraken, ключ не нужен — только публичный тикер.
 *
 * <h2>Зачем</h2>
 *
 * 192 требует бумажного режима перед живым пилотом: «журнал что бы сделал» ловит
 * ровно те ошибки, которые на живом стоят денег, — перевёрнутый знак, лишнюю
 * заявку, не тот размер. Владелец (27.09.2026): «реальные запросы не делаем, пишем
 * лог, когда бы шортили и когда бы выходили».
 *
 * <h2>Как</h2>
 *
 * Раз в {@link #CYCLE_MS} для каждого бота: позиция — из его журнала
 * ({@code exec_state.position}, её двигают исполнения, с этапа 2 — и лента
 * сделок площадки), цены — публичный тикер Kraken. Параллельно ведутся несколько
 * правил (у каждого своя виртуальная позиция перпа):
 * <ul>
 *   <li><b>полоса B лотов</b> — отклонение «запас + перп» больше B лотов →
 *       выровнять к нулю («на превышение», как хотел владелец);</li>
 *   <li><b>излишек сверх K лотов</b> — страхуется только запас выше K;</li>
 *   <li><b>раз в 60 минут</b> — для сравнения с 194.</li>
 * </ul>
 * Размер — до цели, с округлением К НУЛЮ до шага контракта (164). Исполнение:
 * заявка в лучшую цену своей стороны; мейкерская, если за {@link #WAIT_MS} цена
 * прошла через неё (последняя сделка по нашей цене или лучше, либо встречная
 * лучшая цена перешла за нашу); иначе тейкером по встречной цене. Комиссии —
 * 2 и 4.9 б.п. (163), фандинг — по текущей ставке тикера (относительная =
 * {@code fundingRate / markPrice}, начисляется непрерывно).
 *
 * Всё пишется в {@code revx-shared/hedge_paper.db}: события (шортил бы / откупал
 * бы / исполнилось бы), состояние и итог по каждому боту и правилу.
 */
@Component
@Lazy
public class HedgePaper {

    private static final Logger log = LoggerFactory.getLogger(HedgePaper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final long CYCLE_MS = 5_000L;
    static final long WAIT_MS = 30_000L;
    static final double MAKER_FEE = 2e-4;
    static final double TAKER_FEE = 4.9e-4;
    /** Шаг контракта Kraken — общий справочник с сеткой хеджа (там и XRP, и прочие). */
    static final Map<String, Double> STEP = HedgeGrid.STEP;

    enum Kind { BAND, EXCESS, PERIOD, PRICE }

    /** Правило: вид и параметр (лоты для полосы и излишка, минуты для периода). */
    record Rule(String name, Kind kind, double param) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("полоса 1 лот", Kind.BAND, 1),
            new Rule("полоса 2 лота", Kind.BAND, 2),
            new Rule("излишек сверх 2 лотов", Kind.EXCESS, 2),
            new Rule("раз в 60 мин", Kind.PERIOD, 60),
            // ЦЕНОВОЕ ПРАВИЛО (06.10.2026, лучшее для XRP на стенде x10): рост ≥1.5% за
            // 1 ч, откат ≥25% роста → шорт на весь запас на 1 ч; повтор в течение 120 мин,
            // если цена ниже цены выхода на 1%, не больше 3 раз. Цена — марка перпа раз в
            // минуту (на стенде — справедливая цена бота; разница — доли б.п.).
            new Rule("цена: рост 1.5%/1ч, откат 25%, 1 ч, повтор 2ч/1%", Kind.PRICE, 0));

    static final long PRICE_WIN_MS = 3_600_000L;
    static final double PRICE_RISE = 0.015;
    static final double PRICE_BACK = 0.25;
    static final long PRICE_HOLD_MS = 3_600_000L;
    static final long PRICE_REENTRY_MS = 120 * 60_000L;
    static final double PRICE_REENTRY_DROP = 0.01;
    static final int PRICE_REENTRY_MAX = 3;

    /** Поминутный ряд марки перпа по монете — общий для всех ботов этой монеты. */
    private final Map<String, java.util.ArrayDeque<double[]>> minutes = new HashMap<>();

    /** Состояние ценового правила по боту. */
    static final class PriceState {
        boolean on;
        long since;
        double lastPeakTs = -1;
        long exitAt;
        double exitPx;
        int reentries;
    }

    private final Map<String, PriceState> priceStates = new HashMap<>();

    private void noteMinute(String base, double px, long now) {
        var d = minutes.computeIfAbsent(base, k -> new java.util.ArrayDeque<>());
        if (px > 0 && (d.isEmpty() || (long) d.peekLast()[0] / 60_000 != now / 60_000)) {
            d.addLast(new double[]{now, px});
        }
        while (!d.isEmpty() && now - (long) d.peekFirst()[0] > PRICE_WIN_MS) {
            d.pollFirst();
        }
    }

    /** Включён ли ценовой режим сейчас — та же логика, что HedgeOverlay.simulatePrice. */
    private boolean priceOn(InfoBot.Watched w, Rule r, double px, long now) throws Exception {
        PriceState s = priceStates.computeIfAbsent(w.botId() + "|" + r.name(), k -> new PriceState());
        var d = minutes.get(w.base());
        if (d == null || d.isEmpty()) {
            return s.on;
        }
        if (s.on) {
            if (now - s.since >= PRICE_HOLD_MS) {
                s.on = false;
                s.exitAt = now;
                s.exitPx = px;
                event(w, r, now, "выход из ценового режима", 0, px, 0, 0, "прошёл час");
            }
            return s.on;
        }
        if (s.exitAt > 0 && now - s.exitAt <= PRICE_REENTRY_MS
                && px <= s.exitPx * (1 - PRICE_REENTRY_DROP) && s.reentries < PRICE_REENTRY_MAX) {
            s.reentries++;
            s.on = true;
            s.since = now;
            s.exitAt = 0;
            event(w, r, now, "повторный вход", 0, px, 0, 0, "повтор " + s.reentries);
            return true;
        }
        double[] hi = null;
        for (double[] m : d) {
            if (hi == null || m[1] > hi[1]) {
                hi = m;
            }
        }
        double[] lo = hi;
        for (double[] m : d) {
            if (m[0] > hi[0]) {
                break;
            }
            if (m[1] < lo[1]) {
                lo = m;
            }
        }
        double rise = hi[1] / lo[1] - 1;
        double back = hi[1] - lo[1] > 0 ? (hi[1] - px) / (hi[1] - lo[1]) : 0;
        if (hi[0] != s.lastPeakTs && rise >= PRICE_RISE && back >= PRICE_BACK) {
            s.on = true;
            s.since = now;
            s.lastPeakTs = hi[0];
            s.reentries = 0;
            event(w, r, now, "вход в ценовой режим", 0, px, 0, 0,
                    String.format(Locale.ROOT, "рост %.2f%%, откат %.0f%%", rise * 100, back * 100));
        }
        return s.on;
    }

    /** Виртуальная нога по одному боту и правилу. */
    static final class Leg {
        double perp;
        double cash;           // −Σ qty·цена − комиссии: переоценка = cash + perp·марка
        double fees;
        double funding;
        int trades;
        int makers;
        long lastPeriodic;
        // висящая заявка
        double pendQty;
        double pendPx;
        long pendTs;
    }

    private final List<String> botSpec;
    private final String dbPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, Leg> legs = new HashMap<>();
    private Connection db;
    private volatile boolean alive = true;
    private long lastFundingMs;

    public HedgePaper(@Value("${revx.info.bots}") List<String> botSpec,
                      @Value("${revx.hedge-paper.db:/home/ubuntu/revx-shared/hedge_paper.db}") String dbPath) {
        this.botSpec = botSpec;
        this.dbPath = dbPath;
    }

    public void stop() {
        alive = false;
    }

    /** Котировка перпа из тикера. */
    record Quote(double bid, double ask, double last, long lastTime, double mark, double fundRel) {
    }

    public void run() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
        List<InfoBot.Watched> bots = InfoBot.parse(botSpec);
        restore();
        log.info("бумажный хедж: ботов {}, правил {}, база {} — заявок на Kraken НЕ ставлю",
                bots.size(), RULES.size(), dbPath);
        while (alive) {
            long t0 = System.currentTimeMillis();
            try {
                Map<String, Quote> quotes = tickers();
                long now = System.currentTimeMillis();
                double dtHours = lastFundingMs > 0 ? (now - lastFundingMs) / 3_600_000.0 : 0;
                lastFundingMs = now;
                for (InfoBot.Watched w : bots) {
                    Quote q = quotes.get(perpFor(w.base()));
                    if (q == null) {
                        continue;
                    }
                    noteMinute(w.base(), q.mark(), now);
                    double[] invLot = position(w);
                    if (invLot == null) {
                        continue;
                    }
                    for (Rule r : RULES) {
                        step(w, r, invLot[0], invLot[1], q, now, dtHours);
                    }
                }
            } catch (Exception e) {
                log.warn("бумажный хедж: цикл — {}", e.toString());
            }
            long sleep = CYCLE_MS - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }

    /** Один шаг одного правила по одному боту. */
    void step(InfoBot.Watched w, Rule r, double inv, double lot, Quote q, long now, double dtHours)
            throws Exception {
        String key = w.botId() + "|" + r.name();
        Leg leg = legs.computeIfAbsent(key, k -> {
            Leg l = new Leg();
            l.lastPeriodic = now;
            return l;
        });
        double step = STEP.getOrDefault(w.base(), 1e-4);
        // Фандинг за прошедший цикл: лонг платит шорту при положительной ставке.
        if (dtHours > 0 && leg.perp != 0) {
            double fu = -leg.perp * q.mark() * q.fundRel() * dtHours;
            leg.funding += fu;
            leg.cash += fu;
        }
        // Висящая заявка: исполнилась мейкером или пора тейкером.
        if (leg.pendQty != 0) {
            boolean sell = leg.pendQty < 0;
            boolean through = sell
                    ? (q.bid() > leg.pendPx || (q.last() >= leg.pendPx && q.lastTime() > leg.pendTs))
                    : (q.ask() < leg.pendPx || (q.last() <= leg.pendPx && q.lastTime() > leg.pendTs));
            if (through) {
                fill(w, r, leg, leg.pendQty, leg.pendPx, true, inv, now);
            } else if (now - leg.pendTs >= WAIT_MS) {
                fill(w, r, leg, leg.pendQty, sell ? q.bid() : q.ask(), false, inv, now);
            }
            save(w, r, leg, q.mark(), now);
            return;
        }
        double hedged = r.kind() == Kind.EXCESS ? Math.max(0, inv - r.param() * lot)
                : r.kind() == Kind.PRICE ? (priceOn(w, r, q.mark(), now) ? inv : 0) : inv;
        double want = -hedged;
        double dev = leg.perp - want;
        boolean due = switch (r.kind()) {
            // Ценовой режим: следовать за запасом с точностью до шага контракта; вне режима — в ноль.
            case PRICE -> Math.abs(dev) >= step;
            case BAND -> Math.abs(dev) > r.param() * lot;
            case EXCESS -> Math.abs(dev) > lot;
            case PERIOD -> {
                if (now - leg.lastPeriodic >= (long) (r.param() * 60_000)) {
                    leg.lastPeriodic = now;
                    yield Math.abs(dev) >= step;
                }
                yield false;
            }
        };
        if (due) {
            double delta = want - leg.perp;
            double qty = Math.signum(delta) * Math.floor(Math.abs(delta) / step + 1e-9) * step;
            if (Math.abs(qty) >= step) {
                leg.pendQty = qty;
                leg.pendPx = qty < 0 ? q.ask() : q.bid();     // встать в лучшую цену своей стороны
                leg.pendTs = now;
                event(w, r, now, qty < 0 ? "шортил бы" : "откупал бы", qty, leg.pendPx, inv, leg.perp,
                        String.format(Locale.ROOT, "запас %.2f лота, перп %.2f лота, цель %.2f лота",
                                inv / lot, leg.perp / lot, want / lot));
            }
        }
        save(w, r, leg, q.mark(), now);
    }

    private void fill(InfoBot.Watched w, Rule r, Leg leg, double qty, double px, boolean maker,
                      double inv, long now) throws Exception {
        double fee = Math.abs(qty) * px * (maker ? MAKER_FEE : TAKER_FEE);
        leg.perp += qty;
        leg.cash -= qty * px + fee;
        leg.fees += fee;
        leg.trades++;
        if (maker) {
            leg.makers++;
        }
        leg.pendQty = 0;
        event(w, r, now, maker ? "исполнилось бы мейкером" : "исполнилось бы тейкером", qty, px, inv,
                leg.perp, String.format(Locale.ROOT, "комиссия %.6f", fee));
    }

    // ================================================================ данные

    /** Позиция бота и лот: из журнала (exec_state.position и последний boot). */
    static double[] position(InfoBot.Watched w) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + w.journalPath() + "?mode=ro");
             Statement st = c.createStatement()) {
            double inv;
            try (ResultSet rs = st.executeQuery("SELECT value FROM exec_state WHERE key = 'position'")) {
                if (!rs.next()) {
                    return null;
                }
                inv = rs.getDouble(1);
            }
            double lot = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT detail FROM exec_event WHERE kind = 'boot' ORDER BY ts_ms DESC LIMIT 1")) {
                if (rs.next() && rs.getString(1) != null && rs.getString(1).contains("|")) {
                    String d = rs.getString(1);
                    lot = MAPPER.readTree(d.substring(d.indexOf('|') + 1)).path("size").asDouble();
                }
            }
            return lot > 0 ? new double[]{inv, lot} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Quote> tickers() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("https://futures.kraken.com/derivatives/api/v3/tickers"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Quote> out = new HashMap<>();
        for (JsonNode t : MAPPER.readTree(r.body()).path("tickers")) {
            String s = t.path("symbol").asText();
            if (!s.startsWith("PF_")) {
                continue;
            }
            double mark = t.path("markPrice").asDouble();
            long lastTime = 0;
            try {
                lastTime = Instant.parse(t.path("lastTime").asText()).toEpochMilli();
            } catch (Exception ignore) {
                // нет времени — сделка по нашей цене не засчитывается
            }
            out.put(s, new Quote(t.path("bid").asDouble(), t.path("ask").asDouble(),
                    t.path("last").asDouble(), lastTime, mark,
                    mark > 0 ? t.path("fundingRate").asDouble() / mark : 0));
        }
        return out;
    }

    static String perpFor(String base) {
        return "PF_" + ("BTC".equals(base) ? "XBT" : base) + "USD";
    }

    // ================================================================ база

    private void event(InfoBot.Watched w, Rule r, long now, String kind, double qty, double px,
                       double inv, double perp, String note) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO paper_event(ts_ms, bot, rule, kind, qty, price, inventory, perp, note) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, now);
            ps.setString(2, w.botId());
            ps.setString(3, r.name());
            ps.setString(4, kind);
            ps.setDouble(5, qty);
            ps.setDouble(6, px);
            ps.setDouble(7, inv);
            ps.setDouble(8, perp);
            ps.setString(9, note);
            ps.executeUpdate();
        }
    }

    private void save(InfoBot.Watched w, Rule r, Leg leg, double mark, long now) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO paper_state(bot, rule, perp, cash, fees, funding, trades, makers, "
                        + "mark, pnl, pend_qty, pend_px, pend_ts, last_periodic, updated_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, w.botId());
            ps.setString(2, r.name());
            ps.setDouble(3, leg.perp);
            ps.setDouble(4, leg.cash);
            ps.setDouble(5, leg.fees);
            ps.setDouble(6, leg.funding);
            ps.setInt(7, leg.trades);
            ps.setInt(8, leg.makers);
            ps.setDouble(9, mark);
            ps.setDouble(10, leg.cash + leg.perp * mark);
            ps.setDouble(11, leg.pendQty);
            ps.setDouble(12, leg.pendPx);
            ps.setLong(13, leg.pendTs);
            ps.setLong(14, leg.lastPeriodic);
            ps.setLong(15, now);
            ps.executeUpdate();
        }
    }

    /** После перезапуска виртуальные позиции продолжаются с того же места. */
    private void restore() throws Exception {
        try (ResultSet rs = db.createStatement().executeQuery(
                "SELECT bot, rule, perp, cash, fees, funding, trades, makers, pend_qty, pend_px, "
                        + "pend_ts, last_periodic FROM paper_state")) {
            while (rs.next()) {
                Leg l = new Leg();
                l.perp = rs.getDouble(3);
                l.cash = rs.getDouble(4);
                l.fees = rs.getDouble(5);
                l.funding = rs.getDouble(6);
                l.trades = rs.getInt(7);
                l.makers = rs.getInt(8);
                l.pendQty = rs.getDouble(9);
                l.pendPx = rs.getDouble(10);
                l.pendTs = rs.getLong(11);
                l.lastPeriodic = rs.getLong(12);
                legs.put(rs.getString(1) + "|" + rs.getString(2), l);
            }
        }
    }

    /**
     * {@code --revx-hedge-paper-report}: итог бумажного хеджа по ботам и правилам.
     * Результат ноги = деньги сделок + переоценка по последней марке + фандинг.
     */
    public static String report(String dbPath) throws Exception {
        StringBuilder sb = new StringBuilder("# Бумажный хедж: итог по ботам и правилам\n\n")
                .append("| бот | правило | перп сейчас | сделок | мейкером | комиссии $ | фандинг $ | итог ноги $ |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT bot, rule, perp, trades, makers, fees, funding, pnl, updated_ms "
                             + "FROM paper_state ORDER BY bot, rule")) {
            while (rs.next()) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %.6f | %d | %d | %.4f | %+.4f | %+.4f |%n",
                        rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getInt(4), rs.getInt(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8)));
            }
        }
        sb.append("\n⚠️ «Итог ноги» — только перп: он должен примерно зеркалить переноску ")
                .append("спотового запаса, то есть сам по себе ни прибыль, ни убыток. Сравнивать с ")
                .append("`--revx-hedge-grid` по тем же суткам.\n");
        return sb.toString();
    }

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS paper_event (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms     INTEGER NOT NULL,
                bot       TEXT,
                rule      TEXT,
                kind      TEXT,
                qty       REAL,
                price     REAL,
                inventory REAL,
                perp      REAL,
                note      TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_paper_event_ts ON paper_event(ts_ms);
            CREATE TABLE IF NOT EXISTS paper_state (
                bot           TEXT,
                rule          TEXT,
                perp          REAL,
                cash          REAL,
                fees          REAL,
                funding       REAL,
                trades        INTEGER,
                makers        INTEGER,
                mark          REAL,
                pnl           REAL,
                pend_qty      REAL,
                pend_px       REAL,
                pend_ts       INTEGER,
                last_periodic INTEGER,
                updated_ms    INTEGER,
                PRIMARY KEY (bot, rule)
            );
            """;
}
