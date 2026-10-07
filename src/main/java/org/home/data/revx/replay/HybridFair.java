package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/**
 * ГИБРИДНАЯ СПРАВЕДЛИВАЯ ЦЕНА (04.10.2026, идея владельца): уровень — своя книга
 * Revolut, движение — Бинанс.
 *
 * <h2>Зачем</h2>
 * Бинанс опережает Revolut на 1–4 с (задача A54), и наш отбор ~1.2 б.п. на сделке
 * идёт ровно от этого. А курс USDC/USD по корзине из 23 пар хрупок: 04.10.2026 с
 * площадки ушли маркет-мейкеры, разброс курса по тонким альтам вырос до 0.65%, и
 * боты встали на часы — при том что их собственные книги были в порядке.
 *
 * <h2>Почему не цена Бинанса как есть</h2>
 * Уровень решает частоту исполнений (κ ≈ 0.385 на б.п.), а Revolut торгует на своём
 * уровне, плавающем относительно Бинанса на несколько б.п. Переход на чужой
 * уровень уже проваливался (A47, прямая книга USDC/USD). Поэтому:
 * <pre>  fair = EMA_τ(середина своей книги) × B(t) / EMA_τ(B)</pre>
 * где B — цена Бинанса последней закрытой секунды не позже t − {@code lag}. Резкий
 * ход Бинанса сразу двигает опору, за τ сглаженная своя середина его догоняет.
 *
 * <h2>Гейт</h2>
 * Котировать можно, если своя книга не шире {@code max-spread-bp} и есть цена
 * Бинанса; иначе действует прежний вердикт тика (корзина).
 *
 * Включается {@code -Drevx.fair.hybrid-dir=<каталог с секундными свечами>} — файлы
 * {@code <SYM>USDC-1s-YYYY-MM-DD.zip} из data.binance.vision.
 */
final class HybridFair {

    private static final Logger log = LoggerFactory.getLogger(HybridFair.class);

    static final String DIR = System.getProperty("revx.fair.hybrid-dir", "");
    static final double TAU_MS = Double.parseDouble(System.getProperty("revx.fair.hybrid-tau-sec", "60")) * 1000;
    static final long LAG_MS = Long.getLong("revx.fair.hybrid-lag-ms", 300L);
    static final double MAX_SPREAD_BP = Double.parseDouble(System.getProperty("revx.fair.hybrid-max-spread-bp", "50"));
    /**
     * Откуда уровень: {@code own} — середина своей книги (сдвигает опору вверх на
     * 2–4 б.п., то есть заодно «центрирует», отвергнутое в A49); {@code tick} —
     * прежняя опора (корзина), чтобы мерить ТОЛЬКО опережение Бинанса.
     */
    static final boolean LEVEL_TICK = "tick".equalsIgnoreCase(System.getProperty("revx.fair.hybrid-level", "own"));
    /** {@code bnb} — опора ТОЛЬКО по Бинансу, без уровня Revolut (опыт владельца 04.10.2026). */
    static final boolean LEVEL_BNB = "bnb".equalsIgnoreCase(System.getProperty("revx.fair.hybrid-level", "own"));
    /**
     * {@code book} — опора ТОЛЬКО от своей книги (XRP-USDC и т.д.), без корзины курса и
     * без Бинанса: сглаженная за τ середина (τ ≤ 0 — без сглаживания). 04.10.2026 корзина
     * развалилась, а своя книга XRP-USDC стояла со спредом 2 б.п.
     */
    static final boolean LEVEL_BOOK = "book".equalsIgnoreCase(System.getProperty("revx.fair.hybrid-level", "own"));
    /**
     * СМЕСЬ УРОВНЕЙ (05.10.2026, идея владельца): уровень = w · прежняя опора +
     * (1 − w) · середина своей книги, дальше то же сглаживание и движение Бинанса.
     * w = 1 — «прежний уровень + Бинанс» (сильнее на росте), w = 0 — «своя книга +
     * Бинанс» (сильнее на падении). {@code revx.fair.hybrid-mix}; < 0 — выключено.
     */
    static final double LEVEL_MIX = Double.parseDouble(System.getProperty("revx.fair.hybrid-mix", "-1"));
    /**
     * ПОДВИЖНАЯ ДОЛЯ (05.10.2026, идея владельца): w следит за тем, насколько прежняя
     * опора ОТЪЕХАЛА от своей книги. Обычный разрыв (2–4 б.п.) — сглаженный за час;
     * отклонение от него меньше d0 б.п. → w = 1 (прежняя опора), больше d1 → w = 0
     * (своя книга), между — линейно; сама w сглаживается за 60 с.
     * {@code revx.fair.hybrid-adapt=d0,d1}; пусто — выключено.
     */
    static final double[] ADAPT = parseAdapt(System.getProperty("revx.fair.hybrid-adapt", ""));
    /**
     * УРОВЕНЬ СВОЕЙ КНИГИ ПО ГЛУБИНЕ, ПРИВЯЗАННОЙ К ЛОТУ (05.10.2026, владелец): вместо
     * середины верхушки (лучший бид + лучший аск)/2 — середина между ценами, до которых
     * на каждой стороне набирается k лотов бота. Пыль на верхушке (XRP: робот по $0.10)
     * и тонкие уровни пропускаются, а на крупном лоте порог растёт вместе с лотом.
     * {@code revx.fair.hybrid-depth-k}; 0 — середина верхушки, как прежде.
     */
    static final double DEPTH_K = Double.parseDouble(System.getProperty("revx.fair.hybrid-depth-k", "0"));

    /** Цена, на которой накопленный объём стороны достигает {@code qty}; NaN — глубины не хватило. */
    private static double depthPrice(List<BookView.Level> side, double qty) {
        double acc = 0;
        for (BookView.Level l : side) {
            acc += l.qty();
            if (acc >= qty) {
                return l.price();
            }
        }
        return Double.NaN;
    }

    private static double[] parseAdapt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] p = s.split(",");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
    }

    private HybridFair() {
    }

    static boolean enabled() {
        return !DIR.isBlank();
    }

    /** Пересчитывает ряд тиков; без данных Бинанса или книги тик остаётся прежним. */
    static List<ReplayFair.Tick> apply(List<ReplayFair.Tick> ticks, String symbol, MarketData market,
                                       double lotQty) {
        if (ticks.isEmpty() || market == null) {
            return ticks;
        }
        String base = symbol.substring(0, symbol.indexOf('/'));
        TreeMap<Long, Double> bnb = LEVEL_BOOK ? new TreeMap<>() : load(base, ticks.get(0).tsMs() - 3_600_000L,
                ticks.get(ticks.size() - 1).tsMs());
        if (bnb.isEmpty() && !LEVEL_BOOK) {
            log.error("ГИБРИД: нет секундных свечей {}USDC в {} — опора прежняя", base, DIR);
            return ticks;
        }
        if (LEVEL_MIX >= 0 && ADAPT == null) {
            // Смесь — тем же кодом, что и живой бот (HybridCore): сверка живого с
            // симуляцией иначе сравнивала бы две разные опоры.
            org.home.data.revx.exec.HybridCore core = new org.home.data.revx.exec.HybridCore(
                    LEVEL_MIX, DEPTH_K, TAU_MS / 1000, MAX_SPREAD_BP);
            List<ReplayFair.Tick> out = new ArrayList<>(ticks.size());
            long done = 0;
            long gate = 0;
            for (ReplayFair.Tick t : ticks) {
                var e = bnb.floorEntry(t.tsMs() - LAG_MS - 1000);
                var o = core.step(t.tsMs(), t.fair(), t.quotable(), market.bookAt(t.tsMs()), lotQty,
                        e == null ? Double.NaN : e.getValue());
                if (o == null) {
                    out.add(t);
                    continue;
                }
                done++;
                if (o.ownGate()) {
                    gate++;
                }
                out.add(new ReplayFair.Tick(t.tsMs(), o.fair(), t.bid(), t.ask(), t.inventory(),
                        o.quotable(), o.quotable() ? null : t.reason(), t.pressure()));
            }
            log.warn(String.format(Locale.ROOT, "ГИБРИД %s (общий расчёт): смесь %.2f, глубина %.1f лота, "
                            + "τ %.0f с — пересчитано %d из %d, котируем вопреки корзине %d, "
                            + "глубины не хватило на %d, поводок к Бинансу ±%.1f б.п. держал %.2f%% тиков",
                    symbol, LEVEL_MIX, DEPTH_K, TAU_MS / 1000, done,
                    ticks.size(), gate, core.depthShort(), core.clampBp(), 100 * core.clampedShare()));
            return out;
        }
        List<ReplayFair.Tick> out = new ArrayList<>(ticks.size());
        double emaM = Double.NaN;
        double emaB = Double.NaN;
        long prev = 0;
        long hybrid = 0;
        long ownGate = 0;
        double sumShift = 0;
        double devBase = Double.NaN;      // обычный разрыв прежней опоры и своей книги, б.п.
        double wAdapt = 1;                // подвижная доля прежней опоры
        long ownTicks = 0;                // тиков, где своя книга весила больше половины
        long depthUsed = 0;
        long depthShort = 0;
        for (ReplayFair.Tick t : ticks) {
            BookView book = market.bookAt(t.tsMs());
            var e = bnb.floorEntry(t.tsMs() - LAG_MS - 1000);   // свеча [s, s+1с) готова в s+1с
            if (book == null || book.empty() || (e == null && !LEVEL_BOOK)) {
                out.add(t);
                continue;
            }
            double bookMid = (book.bestBid() + book.bestAsk()) / 2;
            double spreadBp = (book.bestAsk() - book.bestBid()) / bookMid * 1e4;
            if (DEPTH_K > 0 && lotQty > 0) {
                double bd = depthPrice(book.bids(), DEPTH_K * lotQty);
                double ad = depthPrice(book.asks(), DEPTH_K * lotQty);
                if (Double.isFinite(bd) && Double.isFinite(ad)) {
                    bookMid = (bd + ad) / 2;
                    depthUsed++;
                } else {
                    depthShort++;            // глубины не хватило — середина верхушки
                }
            }
            double mid = bookMid;
            if (ADAPT != null) {
                if (!(Double.isFinite(t.fair()) && t.fair() > 0)) {
                    out.add(t);
                    continue;
                }
                double dev = (t.fair() / bookMid - 1) * 1e4;
                if (Double.isNaN(devBase)) {
                    devBase = dev;
                    wAdapt = 1;
                } else {
                    double dt = t.tsMs() - prev;
                    devBase += (1 - Math.exp(-dt / 3_600_000.0)) * (dev - devBase);
                    double excess = Math.abs(dev - devBase);
                    double target = excess <= ADAPT[0] ? 1 : excess >= ADAPT[1] ? 0
                            : 1 - (excess - ADAPT[0]) / (ADAPT[1] - ADAPT[0]);
                    wAdapt += (1 - Math.exp(-dt / 60_000.0)) * (target - wAdapt);
                }
                if (wAdapt < 0.5) {
                    ownTicks++;
                }
                mid = wAdapt * t.fair() + (1 - wAdapt) * bookMid;
            } else if (LEVEL_MIX >= 0) {
                if (!(Double.isFinite(t.fair()) && t.fair() > 0)) {
                    out.add(t);
                    continue;
                }
                mid = LEVEL_MIX * t.fair() + (1 - LEVEL_MIX) * bookMid;
            } else if (LEVEL_TICK) {
                if (!(Double.isFinite(t.fair()) && t.fair() > 0)) {
                    out.add(t);
                    continue;
                }
                mid = t.fair();
            }
            double b = e == null ? 1.0 : e.getValue();
            if (Double.isNaN(emaM) || TAU_MS <= 0) {
                emaM = mid;
                emaB = b;
            } else {
                double a = 1 - Math.exp(-(t.tsMs() - prev) / TAU_MS);
                emaM += a * (mid - emaM);
                emaB += a * (b - emaB);
            }
            prev = t.tsMs();
            double fair = LEVEL_BNB ? b : LEVEL_BOOK ? emaM : emaM * b / emaB;
            boolean ok = spreadBp <= MAX_SPREAD_BP;
            if (ok && !t.quotable()) {
                ownGate++;                 // корзина запрещала, своя книга разрешает
            }
            if (Double.isFinite(t.fair()) && t.fair() > 0) {
                sumShift += (fair / t.fair() - 1) * 1e4;
                hybrid++;
            }
            out.add(new ReplayFair.Tick(t.tsMs(), fair, t.bid(), t.ask(), t.inventory(),
                    ok || t.quotable(), ok ? null : t.reason(), t.pressure()));
        }
        log.warn(String.format(Locale.ROOT,
                "ГИБРИД %s: тиков %d, пересчитано %d, средний сдвиг к прежней опоре %+.2f б.п., "
                        + "котируем вопреки корзине %d тиков (τ %.0f с, задержка Бинанса %d мс)",
                symbol, ticks.size(), hybrid, hybrid > 0 ? sumShift / hybrid : 0, ownGate,
                TAU_MS / 1000, LAG_MS));
        if (DEPTH_K > 0) {
            log.warn(String.format(Locale.ROOT, "ГИБРИД %s: уровень по глубине %.1f лота (%.6g монеты) — "
                    + "посчитан на %d тиках, глубины не хватило на %d", symbol, DEPTH_K, DEPTH_K * lotQty,
                    depthUsed, depthShort));
        }
        if (ADAPT != null) {
            log.warn(String.format(Locale.ROOT, "ГИБРИД %s: подвижная доля (пороги %.1f/%.1f б.п.) — "
                    + "на своей книге %.1f%% тиков", symbol, ADAPT[0], ADAPT[1],
                    100.0 * ownTicks / Math.max(1, hybrid)));
        }
        return out;
    }

    /** Закрытия секундных свечей Бинанса по открытию секунды (мс). */
    private static TreeMap<Long, Double> load(String base, long fromMs, long toMs) {
        TreeMap<Long, Double> m = new TreeMap<>();
        LocalDate d = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(toMs).atZone(ZoneOffset.UTC).toLocalDate();
        for (; !d.isAfter(last); d = d.plusDays(1)) {
            Path f = Path.of(DIR, base + "USDC-1s-" + d + ".zip");
            if (!Files.exists(f)) {
                log.warn("ГИБРИД: нет файла {}", f);
                continue;
            }
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(f))) {
                while (z.getNextEntry() != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(z));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                            continue;
                        }
                        String[] c = line.split(",", 6);
                        long ts = Long.parseLong(c[0]);
                        if (ts > 100_000_000_000_000L) {
                            ts /= 1000;                // с 2025 года отметки в микросекундах
                        }
                        m.put(ts, Double.parseDouble(c[4]));
                    }
                }
            } catch (Exception e) {
                log.warn("ГИБРИД: не прочитать {}: {}", f, e.toString());
            }
        }
        return m;
    }
}
