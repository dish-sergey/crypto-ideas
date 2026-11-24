package org.home.data.revx.replay;

import org.home.data.revx.sim.FairPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ДНЕВНЫЕ ФАЙЛЫ СТЕНДА (17.11.2026, решение владельца: «прошёл день — подготовили данные на
 * день, тесты гоняют любой отрезок без пересоздания окна»).
 *
 * <h2>Зачем</h2>
 *
 * Прогон брал данные из базы ОКНА ({@code --revx-assemble}), а кэши рынка и опоры ключевались
 * путём, размером и временем этой базы. Каждое новое окно — новая сборка на десятки ГБ и новые
 * кэши, хотя сутки внутри одни и те же: к 17.11 стенд занимал ~266 ГБ на трёх дисках, большей
 * частью повторов.
 *
 * <h2>Что лежит в дне</h2>
 *
 * Каталог {@code <days>/<YYYYMMDD>/}:
 * <ul>
 *   <li>{@code market-<BASE>.bin} + {@code .bin.lv} — книга и лента пары за сутки
 *       {@code [00:00, 24:00)} UTC, формат кэша {@link MarketData};</li>
 *   <li>{@code fair-<BASE>-<ключ>.bin} — ряд опоры по корзине ({@link StandFair}) за те же сутки,
 *       формат {@link FairCache}. ⚠️ Считается с РАЗГОНОМ {@link #WARMUP_MS} из предыдущих суток:
 *       курс — медиана по снимкам не старше 30 с, сглаживание курса 60 с, и без разгона первые
 *       секунды каждых суток были бы холодными. Ключ — настройки опоры (не база);</li>
 *   <li>{@code stalls.txt} — сырые затыки площадки за сутки, «момент_мс задержка_мс»; расписание
 *       окна строится при прогоне по всем дням разом, тем же правилом, что
 *       {@code StandAssembler.writeSchedule} (иначе окно затыка, начатое до полуночи, рвалось бы);</li>
 *   <li>{@code pairs.txt} — каталог пар (шаги и минимум заявки);</li>
 *   <li>{@code meta.txt} — откуда и когда день построен.</li>
 * </ul>
 *
 * Гибрид с Бинансом ({@link HybridFair}) сюда не входит: он накладывается при прогоне на весь
 * склеенный ряд, и его память (EMA) переходит через полночь сама.
 */
public final class DayStore {

    private static final Logger log = LoggerFactory.getLogger(DayStore.class);

    static final long DAY_MS = 86_400_000L;
    /** Разгон опоры из предыдущих суток. */
    static final long WARMUP_MS = 30 * 60_000L;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DayStore() {
    }

    public static String dir() {
        return System.getProperty("revx.days-dir", "");
    }

    public static boolean enabled() {
        return !dir().isBlank();
    }

    static Path dayDir(String root, LocalDate day) {
        return Path.of(root, D.format(day));
    }

    static long startMs(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Сутки, которые задевает окно {@code [fromMs, toMs)}. 23.11.2026: конец ИСКЛЮЧИТЕЛЬНЫЙ — окно до 00:00
     *  не требует следующего дня (его может ещё не быть: ежедневное задание собирает вчерашний). */
    static List<LocalDate> days(long fromMs, long toMs) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(toMs > fromMs ? toMs - 1 : toMs).atZone(ZoneOffset.UTC).toLocalDate();
        for (; !d.isAfter(last); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------ подготовка

    /**
     * Подготовить сутки {@code [first, last]} из базы стенда {@code standDb}. База обязана покрывать
     * каждый день целиком плюс {@link #WARMUP_MS} до него — это проверяется по книге торгуемой пары,
     * неполный день не пишется (его прогон тихо дал бы дыру).
     */
    public static void prep(String standDb, String root, LocalDate first, LocalDate last, List<String> bases,
                            FairPrice.Limits limits, java.util.Collection<String> memecoins, long maxSkewMs)
            throws Exception {
        String keyExtra = limits + "|" + memecoins + "|" + maxSkewMs;
        String fk = FairCache.settingsKey(keyExtra);
        long[] cover = coverage(standDb, bases.get(0) + "/USDC");
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            long d0 = startMs(day);
            long d1 = d0 + DAY_MS;
            if (cover[0] > d0 - WARMUP_MS + 60_000 || cover[1] < d1 - 60_000) {
                log.error("день {}: база покрывает {} … {}, нужно {} … {} — не пишу", day,
                        Instant.ofEpochMilli(cover[0]), Instant.ofEpochMilli(cover[1]),
                        Instant.ofEpochMilli(d0 - WARMUP_MS), Instant.ofEpochMilli(d1));
                continue;
            }
            Path dir = dayDir(root, day);
            Files.createDirectories(dir);
            long t = System.currentTimeMillis();
            for (String b : bases) {
                MarketData.writeDay(standDb, b + "/USDC", d0, d1 - 1, dir.resolve("market-" + b + ".bin"));
            }
            StandFair f = new StandFair(standDb, bases.get(0), limits, memecoins, maxSkewMs,
                    org.home.data.revx.exec.Clock.system(), d0 - WARMUP_MS, d1 - 1);
            for (String b : bases) {
                List<ReplayFair.Tick> all = f.toTicks(b);
                List<ReplayFair.Tick> day0 = new TickColumns(all.size());
                for (ReplayFair.Tick x : all) {
                    if (x.tsMs() >= d0 && x.tsMs() < d1) {
                        day0.add(x);
                    }
                }
                Path file = dir.resolve("fair-" + b + "-" + fk + ".bin");
                Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
                FairCache.write(tmp, new FairCache.Result(day0, f.pairs()));
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            int stalls = writeStalls(standDb, dir.resolve("stalls.txt"), d0, d1);
            writePairs(standDb, dir.resolve("pairs.txt"));
            Files.writeString(dir.resolve("meta.txt"), "день " + day + "\nисточник " + standDb
                    + "\nпостроен " + Instant.now() + "\nопора " + fk + " = " + keyExtra
                    + "\nпар в корзине " + f.pairs() + "\nзатыков " + stalls + "\n");
            log.warn("день {}: готов за {} с (пар в корзине {}, затыков {})", day,
                    (System.currentTimeMillis() - t) / 1000, f.pairs(), stalls);
        }
    }

    private static long[] coverage(String db, String symbol) throws Exception {
        try (Connection c = StandDb.open(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(t_recv_ms), MAX(t_recv_ms) FROM revx_book WHERE symbol = '"
                     + symbol + "'")) {
            return new long[]{rs.getLong(1), rs.getLong(2)};
        }
    }

    private static int writeStalls(String db, Path file, long d0, long d1) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             Statement st = c.createStatement()) {
            boolean has;
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'revx_stall'")) {
                has = rs.next();
            }
            if (has) {
                try (ResultSet rs = st.executeQuery("SELECT ts_ms, latency_ms FROM revx_stall WHERE ts_ms >= "
                        + d0 + " AND ts_ms < " + d1 + " ORDER BY ts_ms")) {
                    while (rs.next()) {
                        sb.append(rs.getLong(1)).append(' ').append(rs.getLong(2)).append('\n');
                        n++;
                    }
                }
            } else {
                log.warn("в базе нет revx_stall — затыков у дня не будет");
            }
        }
        Files.writeString(file, sb.toString());
        return n;
    }

    private static void writePairs(String db, Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = StandDb.open(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT symbol, base_step, quote_step, min_order_size_quote FROM revx_pair ORDER BY symbol")) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append(' ').append(rs.getDouble(2)).append(' ')
                        .append(rs.getDouble(3)).append(' ').append(rs.getDouble(4)).append('\n');
            }
        }
        Files.writeString(file, sb.toString());
    }

    // ------------------------------------------------------------------ чтение

    private static List<Path> need(String root, long fromMs, long toMs, String name) {
        List<Path> out = new ArrayList<>();
        for (LocalDate d : days(fromMs, toMs)) {
            Path p = dayDir(root, d).resolve(name);
            if (!Files.exists(p)) {
                throw new IllegalStateException("нет дня " + d + ": " + p
                        + " — подготовить: --revx-day-prep --from=" + d + " --to=" + d);
            }
            out.add(p);
        }
        return out;
    }

    /** Рынок пары за окно — из дней, без базы. */
    public static MarketData market(String symbol, long fromMs, long toMs) throws Exception {
        String b = symbol.substring(0, symbol.indexOf('/'));
        MarketData m = MarketData.concat(need(dir(), fromMs, toMs, "market-" + b + ".bin"), fromMs, toMs, true);
        log.warn("дни: рынок {} — сделок {}, снимков книги {}", symbol, m.tradeCount(), m.bookCount());
        return m;
    }

    /** Вся лента пары за окно, без порога мелких принтов (как {@code TakerFlow} из базы). */
    public static List<org.home.data.revx.sim.MarketTrade> rawTrades(String symbol, long fromMs, long toMs) throws Exception {
        String b = symbol.substring(0, symbol.indexOf('/'));
        return MarketData.concat(need(dir(), fromMs, toMs, "market-" + b + ".bin"), fromMs, toMs, false).trades();
    }

    /** Ряд опоры пары за окно {@code [fromMs, toMs]} — из дней. */
    public static FairCache.Result fair(String base, long fromMs, long toMs, String keyExtra) throws Exception {
        String fk = FairCache.settingsKey(keyExtra);
        List<ReplayFair.Tick> out = new TickColumns(1 << 16);
        int pairs = 0;
        for (Path p : need(dir(), fromMs, toMs, "fair-" + base + "-" + fk + ".bin")) {
            FairCache.Result r = FairCache.read(p);
            pairs = Math.max(pairs, r.pairs());
            for (ReplayFair.Tick t : r.ticks()) {
                if (t.tsMs() >= fromMs && t.tsMs() <= toMs) {
                    out.add(t);
                }
            }
        }
        log.warn("дни: опора {} — тиков {} (ключ {})", base, out.size(), fk);
        return new FairCache.Result(out, pairs);
    }

    /**
     * Расписание затыков окна во временный файл — то же правило, что {@code StandAssembler}:
     * медленный ответ плюс 5 с, соседние ближе 10 с сливаются. Берутся и сутки до окна: затык,
     * начатый до {@code fromMs}, может ещё длиться.
     */
    public static String stallSchedule(long fromMs, long toMs) throws Exception {
        List<long[]> rows = new ArrayList<>();
        for (LocalDate d : days(fromMs - DAY_MS, toMs)) {
            Path p = dayDir(dir(), d).resolve("stalls.txt");
            if (!Files.exists(p)) {
                continue;
            }
            for (String line : Files.readAllLines(p)) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2) {
                    rows.add(new long[]{Long.parseLong(f[0]), Long.parseLong(f[1])});
                }
            }
        }
        rows.sort(java.util.Comparator.comparingLong(x -> x[0]));
        List<long[]> w = new ArrayList<>();
        for (long[] r : rows) {
            long s = r[0];
            long e = s + r[1] + 5_000;
            if (!w.isEmpty() && s <= w.get(w.size() - 1)[1] + 10_000) {
                long[] last = w.get(w.size() - 1);
                last[1] = Math.max(last[1], e);
                last[2] = Math.max(last[2], r[1]);
            } else {
                w.add(new long[]{s, e, r[1]});
            }
        }
        // третья колонка (23.11.2026) — наибольшая задержка в окне: SimVenue отличает лёгкий затык от тяжёлого
        StringBuilder sb = new StringBuilder();
        for (long[] x : w) {
            sb.append(x[0]).append(' ').append(x[1]).append(' ').append(x[2]).append('\n');
        }
        Path f = Files.createTempFile("revx-days-stalls", ".txt");
        f.toFile().deleteOnExit();
        Files.writeString(f, sb.toString());
        log.warn("дни: затыков {} окон", w.size());
        return f.toString();
    }

    /** Шаги пары из каталога последнего дня окна: {@code [base_step, quote_step, min_notional]}. */
    public static double[] spec(String symbol, long toMs) throws Exception {
        for (Path p : need(dir(), toMs - 1, toMs - 1, "pairs.txt")) {
            for (String line : Files.readAllLines(p)) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && f[0].equals(symbol)) {
                    return new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])};
                }
            }
        }
        return null;
    }

    /** Для отчёта: что лежит в каталоге дней. */
    public static Map<LocalDate, Long> sizes(String root) throws Exception {
        Map<LocalDate, Long> out = new java.util.TreeMap<>();
        try (var s = Files.list(Path.of(root))) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                try {
                    LocalDate day = LocalDate.parse(d.getFileName().toString(), D);
                    long sum = 0;
                    try (var f = Files.list(d)) {
                        for (Path x : f.toList()) {
                            sum += Files.size(x);
                        }
                    }
                    out.put(day, sum);
                } catch (Exception ignore) {
                    // не день
                }
            }
        }
        return out;
    }
}
