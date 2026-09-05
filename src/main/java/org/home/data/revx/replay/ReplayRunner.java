package org.home.data.revx.replay;

import org.home.data.revx.exec.AllocRegistry;
import org.home.data.revx.exec.BotTag;
import org.home.data.revx.exec.ExecJournal;
import org.home.data.revx.exec.QuoteLoop;
import org.home.data.revx.sim.QuotePolicy;
import org.home.data.revx.sim.Quoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Прогон живого журнала через ТОТ ЖЕ котировщик и сверка котировок.
 *
 * <h2>Что здесь проверяется</h2>
 *
 * Живой бот на каждом тике записал в {@code exec_quote} справедливую цену,
 * инвентарь и выставленные бид с аском. Повтор берёт цену и исполнения из этой
 * же записи, пропускает их через {@link QuoteLoop} — тот самый класс, что
 * торгует на деньги, — и сверяет полученные котировки с записанными.
 *
 * Сверка ЗАМКНУТАЯ: входы одинаковы, код один, значит совпадение обязано быть
 * стопроцентным. Расхождение означает поломку повтора (часы, разбор ответов,
 * порядок событий), а не спор моделей — и это ровно то, что нужно закрыть,
 * прежде чем на стенде что-то прогнозировать.
 *
 * ⚠️ Это ПЕРВАЯ ступень. Она не проверяет, угадывает ли стенд исполнения:
 * исполнения здесь взяты из записи. Модель исполнения меряется второй ступенью
 * против этих же записанных исполнений, и там стопроцентного совпадения не
 * будет никогда.
 */
public final class ReplayRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayRunner.class);

    /** Итог сверки по одному прогону. */
    public record Result(int ticks, int compared, int bidMatch, int askMatch,
                         int bidMiss, int askMiss, long placements, long replaces,
                         long cancels, long replaceRejects, long appliedFills, long unattributedFills,
                         double worstBidBp, double worstAskBp) {

        public double bidShare() {
            return compared == 0 ? Double.NaN : (double) bidMatch / compared;
        }

        public double askShare() {
            return compared == 0 ? Double.NaN : (double) askMatch / compared;
        }
    }

    private ReplayRunner() {
    }

    /** Тики из журнала живого бота, начиная с последнего запуска процесса. */
    public static List<ReplayFair.Tick> readTicks(String journalPath, long fromMs) {
        List<ReplayFair.Tick> out = new ArrayList<>();
        String sql = "SELECT ts_ms, fair, bid, ask, inventory, quotable, reason FROM exec_quote"
                + " WHERE ts_ms >= " + fromMs + " ORDER BY ts_ms";
        try (Connection c = open(journalPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double bid = rs.getDouble(3);
                boolean bidNull = rs.wasNull();
                double ask = rs.getDouble(4);
                boolean askNull = rs.wasNull();
                out.add(new ReplayFair.Tick(rs.getLong(1), rs.getDouble(2),
                        bidNull ? null : bid, askNull ? null : ask,
                        rs.getDouble(5), rs.getInt(6) != 0, rs.getString(7)));
            }
        } catch (Exception e) {
            log.error("не прочитались тики: {}", e.getMessage());
        }
        return out;
    }

    /** Исполнения из журнала. Передачи между ботами исключены: рынок их не делал. */
    public static List<RecordedFillModel.RecordedFill> readFills(String journalPath, long fromMs) {
        List<RecordedFillModel.RecordedFill> out = new ArrayList<>();
        String sql = "SELECT ts_ms, side, qty, price FROM exec_fill WHERE ts_ms >= " + fromMs
                + " AND (status IS NULL OR status = 'filled') ORDER BY ts_ms";
        try (Connection c = open(journalPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new RecordedFillModel.RecordedFill(rs.getLong(1),
                        "BUY".equalsIgnoreCase(rs.getString(2)),
                        rs.getDouble(3), rs.getDouble(4)));
            }
        } catch (Exception e) {
            log.error("не прочитались исполнения: {}", e.getMessage());
        }
        return out;
    }

    /** Момент последнего запуска процесса — начало чистого окна. */
    public static long lastBoot(String journalPath) {
        try (Connection c = open(journalPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts_ms FROM exec_event WHERE kind = 'boot' ORDER BY ts_ms DESC LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }


    /** Текст события boot: в нём едут настройки, с которыми бот работал. */
    public static String lastBootDetail(String journalPath) {
        try (Connection c = open(journalPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT detail FROM exec_event WHERE kind = 'boot' ORDER BY ts_ms DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Connection open(String path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
    }

    /**
     * Прогнать запись и сверить котировки.
     *
     * @param tolerance допуск сравнения цен в долях (0 — совпадение до знака)
     */
    public static Result run(List<ReplayFair.Tick> ticks, List<RecordedFillModel.RecordedFill> fills,
                             Quoter.Params params, QuotePolicy policy, String symbol,
                             long periodMs, double minNotional, String botId,
                             double baseStep, double parkDistance, double quoteStart,
                             FillModel model,

                             double tolerance) throws Exception {
        if (ticks.isEmpty()) {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long start = ticks.get(0).tsMs();
        long end = ticks.get(ticks.size() - 1).tsMs();

        SimClock clock = new SimClock(start);
        clock.followSchedule(ticks.stream().mapToLong(ReplayFair.Tick::tsMs).toArray());
        ReplayFair fair = new ReplayFair(ticks, clock);
        SimVenue venue = new SimVenue(clock, model, symbol,
                ticks.get(0).inventory(), quoteStart);

        // Журнал и реестр — во временных файлах: повтор не имеет права трогать
        // ни живой журнал, ни общий реестр владения.
        Path dir = Files.createTempDirectory("revx-replay");
        try (ExecJournal journal = new ExecJournal(dir.resolve("replay.db").toString())) {
            journal.clock(clock);
            AllocRegistry alloc = new AllocRegistry(dir.resolve("alloc.db").toString());
            alloc.claim(botId, symbol.substring(0, symbol.indexOf('/')),
                    ticks.get(0).inventory(), ticks.get(0).inventory(),
                    ticks.get(0).fair(), start);
            alloc.claim(botId, symbol.substring(symbol.indexOf('/') + 1),
                    quoteStart, quoteStart, ticks.get(0).fair(), start);

            QuoteLoop loop = new QuoteLoop(venue, clock, fair, journal, params, symbol,
                    periodMs, minNotional, new BotTag(botId), policy, true,
                    ticks.get(0).inventory(), baseStep, parkDistance, alloc);
            clock.stopAt(end, loop::shutdown);
            loop.startQuoting();
            loop.run();

            return compare(ticks, journal, dir, venue, tolerance, fills);
        } finally {
            delete(dir);
        }
    }

    /**
     * Сверка: котировки повтора против записанных живым ботом.
     *
     * Сравниваются тики, попавшие в ОБА журнала по отметке времени. Тик, на
     * котором живой не котировал (бид или аск пуст), сверяется на то же самое:
     * «не котировал» — тоже решение, и разойтись здесь так же плохо.
     */
    private static Result compare(List<ReplayFair.Tick> live, ExecJournal journal, Path dir,
                                  SimVenue venue, double tolerance,
                                  List<RecordedFillModel.RecordedFill> liveFills) throws Exception {
        List<ReplayFair.Tick> mine = readTicks(dir.resolve("replay.db").toString(), 0);
        java.util.Map<Long, ReplayFair.Tick> byTs = new java.util.HashMap<>();
        for (ReplayFair.Tick t : mine) {
            byTs.put(t.tsMs(), t);
        }
        int compared = 0;
        int bidMatch = 0;
        int askMatch = 0;
        double worstBid = 0;
        int invMatch = 0;
        int shown = 0;
        long firstInvMismatch = 0;
        double worstAsk = 0;
        for (ReplayFair.Tick t : live) {
            ReplayFair.Tick m = byTs.get(t.tsMs());
            if (m == null) {
                continue;
            }
            compared++;
            if (Math.abs(t.inventory() - m.inventory()) < 1e-12) {
                invMatch++;
            } else if (firstInvMismatch == 0) {
                firstInvMismatch = t.tsMs();
            }
            double db = diffBp(t.bid(), m.bid(), t.fair());
            double da = diffBp(t.ask(), m.ask(), t.fair());
            if (shown < 12 && (!same(t.bid(), m.bid(), t.fair(), tolerance)
                    || !same(t.ask(), m.ask(), t.fair(), tolerance))) {
                shown++;
                log.warn("расхождение {} курс {} | живой бид {} аск {} инв {} | повтор бид {} аск {} инв {}",
                        java.time.Instant.ofEpochMilli(t.tsMs()), t.fair(),
                        t.bid(), t.ask(), t.inventory(), m.bid(), m.ask(), m.inventory());
            }
            if (same(t.bid(), m.bid(), t.fair(), tolerance)) {
                bidMatch++;
            } else {
                worstBid = Math.max(worstBid, db);
            }
            if (same(t.ask(), m.ask(), t.fair(), tolerance)) {
                askMatch++;
            } else {
                worstAsk = Math.max(worstAsk, da);
            }
        }
        // Прямая диагностика запаздывания: когда живой и повтор ЗАРЕГИСТРИРОВАЛИ
        // каждое исполнение. Всё остальное — следствие этих моментов.
        List<RecordedFillModel.RecordedFill> mineFills =
                readFills(dir.resolve("replay.db").toString(), 0);
        log.warn("исполнений: у живого {}, у стенда {}", liveFills.size(), mineFills.size());
        matchFills(liveFills, mineFills);
        for (int i = 0; i < Math.min(10, Math.min(liveFills.size(), mineFills.size())); i++) {
            long a = liveFills.get(i).tsMs();
            long b = mineFills.get(i).tsMs();
            log.warn("  #{} живой {} | повтор {} | сдвиг {} с | {} {} против {} {}",
                    i + 1, java.time.Instant.ofEpochMilli(a), java.time.Instant.ofEpochMilli(b),
                    (b - a) / 1000.0,
                    liveFills.get(i).buy() ? "BUY" : "SELL", liveFills.get(i).qty(),
                    mineFills.get(i).buy() ? "BUY" : "SELL", mineFills.get(i).qty());
        }
        log.warn("инвентарь совпал на {} тиках из {} ({}%), первое расхождение {}",
                invMatch, compared, compared > 0 ? 100 * invMatch / compared : 0,
                firstInvMismatch == 0 ? "нет" : java.time.Instant.ofEpochMilli(firstInvMismatch));
        return new Result(live.size(), compared, bidMatch, askMatch,
                compared - bidMatch, compared - askMatch,
                venue.placements(), venue.replaces(), venue.cancels(), venue.replaceRejects(),
                venue.appliedFills(),
                venue.model() instanceof RecordedFillModel r ? r.unattributed() : 0,
                worstBid, worstAsk);
    }

    private static boolean same(Double a, Double b, double fair, double tolerance) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return fair > 0 ? Math.abs(a - b) / fair <= tolerance : a.equals(b);
    }

    private static double diffBp(Double a, Double b, double fair) {
        if (a == null || b == null || !(fair > 0)) {
            return a == null && b == null ? 0 : Double.POSITIVE_INFINITY;
        }
        return Math.abs(a - b) / fair * 10_000;
    }

    /**
     * Сверка ИСПОЛНЕНИЙ: угадала ли модель то, что случилось на самом деле.
     *
     * Для контрольной модели это тавтология (исполнения взяты из записи), а вот
     * для прогнозных — единственная честная метрика. Стопроцентного совпадения
     * тут не будет никогда, и требовать его нельзя: модель отвечает на вопрос,
     * дошёл ли поток до нашей цены, а он по построению вероятностный.
     *
     * Совпадением считается исполнение той же стороны в пределах минуты:
     * обнаружение у живого привязано к минутной сверке с книгой, и требовать
     * секундной точности значило бы мерить не модель, а расписание опросов.
     */
    private static void matchFills(List<RecordedFillModel.RecordedFill> live,
                                   List<RecordedFillModel.RecordedFill> mine) {
        boolean[] used = new boolean[mine.size()];
        int matched = 0;
        for (RecordedFillModel.RecordedFill l : live) {
            for (int i = 0; i < mine.size(); i++) {
                RecordedFillModel.RecordedFill m = mine.get(i);
                if (!used[i] && m.buy() == l.buy()
                        && Math.abs(m.tsMs() - l.tsMs()) <= 60_000) {
                    used[i] = true;
                    matched++;
                    break;
                }
            }
        }
        int spurious = mine.size() - matched;
        double recall = live.isEmpty() ? Double.NaN : 100.0 * matched / live.size();
        double precision = mine.isEmpty() ? Double.NaN : 100.0 * matched / mine.size();
        log.warn("сверка исполнений: угадано {} из {} ({}%), выдумано {} ({}% предсказанных)",
                matched, live.size(), String.format(Locale.ROOT, "%.1f", recall),
                spurious, String.format(Locale.ROOT, "%.1f", 100 - precision));
        double lq = live.stream().mapToDouble(RecordedFillModel.RecordedFill::qty).sum();
        double mq = mine.stream().mapToDouble(RecordedFillModel.RecordedFill::qty).sum();
        log.warn("оборот: у живого {}, у стенда {} ({}x)", lq, mq,
                lq > 0 ? String.format(Locale.ROOT, "%.2f", mq / lq) : "—");
    }

    public static String render(Result r) {
        return String.format(Locale.ROOT, """
                === Повтор живого журнала через тот же котировщик ===

                тиков в записи:        %d
                сверено по времени:    %d
                бид совпал:            %d (%.2f%%), разошёлся %d
                аск совпал:            %d (%.2f%%), разошёлся %d
                худшее расхождение:    бид %.2f б.п., аск %.2f б.п.

                действия повтора:      постановок %d, замен %d, отмен %d, отказов замены %d
                исполнений разнесено:  %d, не на что положить %d
                """,
                r.ticks(), r.compared(),
                r.bidMatch(), 100 * r.bidShare(), r.bidMiss(),
                r.askMatch(), 100 * r.askShare(), r.askMiss(),
                r.worstBidBp(), r.worstAskBp(),
                r.placements(), r.replaces(), r.cancels(), r.replaceRejects(),
                r.appliedFills(), r.unattributedFills());
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // временный каталог; остаток уберёт система
                }
            });
        } catch (Exception ignored) {
            // то же самое
        }
    }
}
