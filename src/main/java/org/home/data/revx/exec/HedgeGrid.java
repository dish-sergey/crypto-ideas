package org.home.data.revx.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.sim.PerpMarkSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * {@code --revx-hedge-grid}: ХЕДЖ НА ЖИВЫХ ТРАЕКТОРИЯХ — СЕТКА ПРАВИЛ С ИСПОЛНЕНИЕМ
 * КАК В ЖИЗНИ (191 часть II, 192 пункты 1–6).
 *
 * <h2>Чем отличается от {@code --revx-hedge}</h2>
 *
 * {@link HedgeOverlay} исполнял всё мгновенно по марке с одной пошлиной. Здесь:
 * <ul>
 *   <li><b>исполнение на Kraken</b>: заявка в касание, ждём {@code T} секунд; не
 *       исполнилась — тейкером по марке в момент {@code T} плюс полспреда плюс 4.9
 *       б.п. Исполнится ли и когда — розыгрыш по вероятностям из 163 с
 *       фиксированными зёрнами. Рядом две границы: «всё мейкером» и «всё
 *       тейкером»; розыгрыш обязан лежать между ними — это проверка прибора;</li>
 *   <li><b>фандинг</b> — часовые {@code relativeFundingRate} Kraken, начисляется
 *       непрерывно (так по документации Kraken: считается непрерывно, фиксируется
 *       раз в час). Лонг платит шорту при положительной ставке;</li>
 *   <li><b>темнота</b> бота (Л2): тиков нет, запас заморожен. Вариант
 *       «довыровнять перед темнотой» — одна заявка на Kraken в момент остановки;</li>
 *   <li><b>мерка — на бот-сутки</b>: итог без беты с ногой хеджа минус «без хеджа»,
 *       ошибка кластерная по суткам.</li>
 * </ul>
 *
 * <h2>Нога перпа — в USD, без поминутного пересчёта</h2>
 *
 * Поминутный пересчёт марки в USDC подмешивает шум нашего курса (A73: 3.3–5.8 б.п.
 * в минуту, до 65% итога на шаге $1). Уровень отклонения USDC от доллара — ~6 б.п.
 * — на суммах хеджа пренебрежим, поэтому нога считается в USD как в USDC.
 */
@Component
@Lazy
public class HedgeGrid {

    private static final Logger log = LoggerFactory.getLogger(HedgeGrid.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY = 86_400_000L;
    private static final long HOUR = 3_600_000L;

    /** Шаг контракта Kraken ({@code contractValueTradePrecision}, A69). */
    static final Map<String, Double> STEP = Map.of("BTC", 1e-4, "ETH", 1e-3, "SOL", 1e-2);
    /** Средний спред перпа, б.п. (163, захват 18–19.09). */
    static final Map<String, Double> SPREAD_BP = Map.of("BTC", 0.16, "ETH", 0.45, "SOL", 1.65);
    /**
     * Доля мейкерских исполнений за 10/30/60 с (163, нижняя оценка по тикеру).
     * ⚠️ Для 120 с замера нет — берётся значение 60 с, то есть снова оценка снизу.
     */
    static final Map<String, double[]> FILL = Map.of(
            "SOL", new double[]{0.439, 0.659, 0.760},
            "ETH", new double[]{0.633, 0.792, 0.852},
            "BTC", new double[]{0.569, 0.743, 0.817});
    static final double MAKER_FEE = 2e-4;
    static final double TAKER_FEE = 4.9e-4;

    enum Rule { BAND, PERIOD, PERIOD_BAND }

    enum Exec { SIM, MAKER, TAKER, FORCED }

    /** Настройка хеджа. {@code forcedMaker} — только для {@link Exec#FORCED}. */
    record Config(Rule rule, double bandLots, int periodMin, int waitSec, boolean flatten,
                  Exec exec, double forcedMaker, double underwaterBp,
                  int flowMin, double flowPct, double minLots, boolean entryOnly,
                  double excessLots) {

        /** Без хеджа излишка. */
        Config(Rule rule, double bandLots, int periodMin, int waitSec, boolean flatten,
               Exec exec, double forcedMaker, double underwaterBp,
               int flowMin, double flowPct, double minLots, boolean entryOnly) {
            this(rule, bandLots, periodMin, waitSec, flatten, exec, forcedMaker, underwaterBp,
                    flowMin, flowPct, minLots, entryOnly, 0);
        }

        /** Условие и на вход, и на выход (прежний смысл). */
        Config(Rule rule, double bandLots, int periodMin, int waitSec, boolean flatten,
               Exec exec, double forcedMaker, double underwaterBp,
               int flowMin, double flowPct, double minLots) {
            this(rule, bandLots, periodMin, waitSec, flatten, exec, forcedMaker, underwaterBp,
                    flowMin, flowPct, minLots, false);
        }

        /** С условием «под водой», без перевеса тейкеров. */
        Config(Rule rule, double bandLots, int periodMin, int waitSec, boolean flatten,
               Exec exec, double forcedMaker, double underwaterBp) {
            this(rule, bandLots, periodMin, waitSec, flatten, exec, forcedMaker, underwaterBp, 0, 0, 0);
        }

        /** Без условия «под водой». */
        Config(Rule rule, double bandLots, int periodMin, int waitSec, boolean flatten,
               Exec exec, double forcedMaker) {
            this(rule, bandLots, periodMin, waitSec, flatten, exec, forcedMaker, 0);
        }

        /** Та же настройка с другим исполнением (для порога мейкерской доли). */
        Config withExec(Exec e, double maker) {
            return new Config(rule, bandLots, periodMin, waitSec, flatten, e, maker, underwaterBp,
                    flowMin, flowPct, minLots, entryOnly, excessLots);
        }

        String name() {
            String r = switch (rule) {
                case BAND -> "полоса " + fmt(bandLots);
                case PERIOD -> "раз в " + periodMin + " мин";
                case PERIOD_BAND -> "раз в " + periodMin + " мин, если > " + fmt(bandLots);
            };
            return r + ", ждать " + waitSec + " с" + (flatten ? ", до нуля перед темнотой" : "")
                    + (underwaterBp > 0 ? ", только под водой ≥ " + fmt(underwaterBp) + " б.п." : "")
                    + (flowMin > 0 ? ", перевес продаж Бинанса ≥ " + fmt(flowPct) + "% за " + flowMin + " мин" : "")
                    + (minLots > 0 ? ", запас ≥ " + fmt(minLots) + " лот." : "")
                    + (entryOnly ? " — ТОЛЬКО НА ВХОД" : "")
                    + (excessLots > 0 ? ", хедж излишка сверх " + fmt(excessLots) + " лот." : "");
        }

        String key() {
            return rule + "|" + bandLots + "|" + periodMin + "|" + waitSec + "|" + flatten
                    + "|" + underwaterBp + "|" + flowMin + "|" + flowPct + "|" + minLots
                    + "|" + entryOnly + "|" + excessLots;
        }
    }

    /** Данные одного бота. */
    static final class Bot {
        String id;
        String base;
        long[] ts;
        double[] fair;
        double[] inv;
        long[] fillTs;
        double[] fillDq;
        double[] fillPx;
        double lot;
        /** Начала темноты по бюджету (остановка сразу после limit_blocked). */
        List<Long> darkStarts = new ArrayList<>();
        /** Тейкеры Бинанса по минутам: момент доступности свечи и накопленные объёмы. */
        long[] flowT = new long[0];
        double[] cumVol = new double[0];
        double[] cumBuy = new double[0];

        /** Задержка доступности минутной свечи после её закрытия (A89). */
        static final long FLOW_DELAY_MS = 30_000L;

        /** Минутные свечи Бинанса с тейкерами из {@code candles}; нет базы — пусто. */
        void loadFlow(String dbPath, String symbol, long from, long to) {
            List<double[]> rows = new ArrayList<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + dbPath + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT close_time, volume, taker_buy_volume FROM candles WHERE symbol = ? "
                                 + "AND interval = '1m' AND open_time BETWEEN ? AND ? "
                                 + "AND volume > 0 AND taker_buy_volume IS NOT NULL ORDER BY open_time")) {
                ps.setString(1, symbol);
                ps.setLong(2, from - 3_600_000L);
                ps.setLong(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new double[]{rs.getLong(1), rs.getDouble(2), rs.getDouble(3)});
                    }
                }
            } catch (Exception e) {
                log.warn("тейкеры Бинанса {} не прочитаны из {}: {}", symbol, dbPath, e.toString());
            }
            flowT = new long[rows.size()];
            cumVol = new double[rows.size() + 1];
            cumBuy = new double[rows.size() + 1];
            for (int i = 0; i < rows.size(); i++) {
                flowT[i] = (long) rows.get(i)[0] + 1 + FLOW_DELAY_MS;
                cumVol[i + 1] = cumVol[i] + rows.get(i)[1];
                cumBuy[i + 1] = cumBuy[i] + rows.get(i)[2];
            }
        }

        /**
         * Перевес продаж тейкеров за последние {@code n} известных к моменту {@code t}
         * минут: {@code (продажи − покупки) / объём}, от −1 до 1. Данных нет — 0.
         */
        double sellImbalance(long t, int n) {
            int k = lowerBound(flowT, t + 1);        // свечей, известных к t
            if (k < n) {
                return 0;
            }
            double vol = cumVol[k] - cumVol[k - n];
            double buy = cumBuy[k] - cumBuy[k - n];
            return vol > 0 ? (vol - 2 * buy) / vol : 0;
        }
    }

    /** Итог по бот-суткам: ключ — сутки, значение — {без хеджа, с хеджем, сделок, пошлина, фандинг, тейкером}. */
    record Days(TreeMap<LocalDate, double[]> days) {
    }

    private final PerpMarkSource marks;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public HedgeGrid(PerpMarkSource marks) {
        this.marks = marks;
    }

    public void run(String journals, String windowsSpec, int seeds, String out) throws Exception {
        // --- окна: имя=from..to
        Map<String, long[]> windows = new LinkedHashMap<>();
        for (String w : windowsSpec.split(",")) {
            String[] kv = w.split("=", 2);
            String[] ft = kv[1].split("\\.\\.");
            windows.put(kv[0], new long[]{LocalDate.parse(ft[0]).atStartOfDay(ZoneOffset.UTC)
                    .toInstant().toEpochMilli(), LocalDate.parse(ft[1]).atStartOfDay(ZoneOffset.UTC)
                    .toInstant().toEpochMilli()});
        }
        long from = windows.values().stream().mapToLong(v -> v[0]).min().orElseThrow();
        long to = windows.values().stream().mapToLong(v -> v[1]).max().orElseThrow();

        // --- боты
        List<Bot> bots = new ArrayList<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            String[] idBase = kv[0].split(":");
            Bot b = read(kv[1], from, to);
            b.id = idBase[0];
            b.base = idBase[1];
            b.loadFlow(System.getProperty("revx.hedge.crypto-db", "data/crypto.db"),
                    ("BTC".equals(b.base) ? "BTC" : b.base) + "USDT", from, to);
            if (b.ts.length > 100) {
                bots.add(b);
            }
        }
        // --- марки и фандинг по перпам
        Map<String, NavigableMap<Long, Double>> markBy = new LinkedHashMap<>();
        Map<String, NavigableMap<Long, Double>> fundBy = new LinkedHashMap<>();
        StringBuilder fundNote = new StringBuilder();
        for (Bot b : bots) {
            if (markBy.containsKey(b.base)) {
                continue;
            }
            String perp = PerpMarkSource.perpFor(b.base);
            markBy.put(b.base, marks.marks(perp, from - 3_600_000L, to + 3_600_000L));
            NavigableMap<Long, Double> f = funding(perp);
            fundBy.put(b.base, f);
            double sum = 0;
            int n = 0;
            for (Map.Entry<Long, Double> e : f.subMap(from, true, to, false).entrySet()) {
                sum += e.getValue();
                n++;
            }
            fundNote.append(String.format(Locale.ROOT,
                    "| %s | %d | %+.3f | %+.2f |%n", perp, n, n > 0 ? sum / n * 1e4 : 0,
                    sum * 1e4));
        }

        // --- сетка
        List<Config> grid = new ArrayList<>();
        for (int wait : new int[]{30, 120}) {
            for (double b : new double[]{0.5, 1, 2}) {
                grid.add(new Config(Rule.BAND, b, 0, wait, false, Exec.SIM, 0));
            }
            for (int p : new int[]{15, 30, 60}) {
                grid.add(new Config(Rule.PERIOD, 0, p, wait, false, Exec.SIM, 0));
            }
            for (double b : new double[]{0.5, 1, 2}) {
                grid.add(new Config(Rule.PERIOD_BAND, b, 30, wait, false, Exec.SIM, 0));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Хедж на живых траекториях: сетка правил с исполнением как в жизни\n\n")
                .append("191 часть II, 192 пункты 1–6. Окна: ").append(windowsSpec)
                .append("; зёрен розыгрыша: ").append(seeds).append(".\n\n");
        sb.append(RULE_TEXT);
        sb.append("\n## Фандинг Kraken за окно\n\n| перп | часов | средняя ставка, б.п./ч | сумма, б.п. |\n|---|---:|---:|---:|\n")
                .append(fundNote)
                .append("\nЗнак: при положительной ставке лонг платит шорту; хедж спотового запаса — шорт.\n");

        // --- прогон: для каждой пары и окна — все настройки.
        Map<String, List<Bot>> byBase = new LinkedHashMap<>();
        for (Bot b : bots) {
            byBase.computeIfAbsent(b.base, k -> new ArrayList<>()).add(b);
        }
        Map<String, Map<String, Row>> chosenInput = new LinkedHashMap<>();
        for (Map.Entry<String, List<Bot>> pair : byBase.entrySet()) {
            String base = pair.getKey();
            sb.append("\n## ").append(base).append(" (боты ");
            pair.getValue().forEach(b -> sb.append(b.id).append(String.format(Locale.ROOT,
                    ": шаг %.2f лота; ", STEP.get(base) / b.lot)));
            sb.append(")\n\n");
            for (Map.Entry<String, long[]> w : windows.entrySet()) {
                sb.append("бюджетных остановок в окне ").append(w.getKey()).append(": ");
                for (Bot b : pair.getValue()) {
                    long n = b.darkStarts.stream()
                            .filter(x -> x >= w.getValue()[0] && x < w.getValue()[1]).count();
                    sb.append(b.id).append(" ").append(n).append("; ");
                }
                sb.append("\n");
            }
            Map<String, Row> rowsAll = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> w : windows.entrySet()) {
                sb.append("\n### ").append(w.getKey()).append(" ")
                        .append(LocalDate.ofInstant(Instant.ofEpochMilli(w.getValue()[0]), ZoneOffset.UTC))
                        .append(" … ")
                        .append(LocalDate.ofInstant(Instant.ofEpochMilli(w.getValue()[1] - 1), ZoneOffset.UTC))
                        .append("\n\n| настройка | Δ на бот-сутки, $ | ошибка | t | разброс зёрен | всё мейкером | всё тейкером | сделок/сут | пошлина/сут | фандинг/сут | тейкером | без хеджа, итог без беты |\n")
                        .append("|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|\n");
                for (Config cfg : grid) {
                    Row r = evaluate(pair.getValue(), cfg, w.getValue(), markBy.get(base),
                            fundBy.get(base), seeds);
                    rowsAll.put(w.getKey() + "|" + cfg.key(), r);
                    sb.append(r.line(cfg.name()));
                }
                // Добавочные варианты: «до нуля перед темнотой» и «только под водой».
                for (Config cfg : extras()) {
                    Row r = evaluate(pair.getValue(), cfg, w.getValue(), markBy.get(base),
                            fundBy.get(base), seeds);
                    rowsAll.put(w.getKey() + "|" + cfg.key(), r);
                    sb.append(r.line(cfg.name()));
                }
            }
            chosenInput.put(base, rowsAll);
            sb.append(choose(base, grid, windows.keySet(), rowsAll));
        }

        // --- порог мейкерской доли для выбранной настройки SOL
        if (byBase.containsKey("SOL")) {
            Config pick = picked.get("SOL");
            // Правило настройку не назвало — порог считается на ОПОРНОЙ (полоса 1 лот,
            // 30 с: так мерили 163 и 170) и прямо помечается как не выбранная.
            boolean reference = pick == null;
            if (reference) {
                pick = new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0);
            }
            {
                sb.append("\n## Порог мейкерской доли, SOL (").append(pick.name())
                        .append(reference ? " — ОПОРНАЯ, правилом не выбрана" : "")
                        .append(")\n\n")
                        .append("| доля мейкера | ");
                windows.keySet().forEach(w -> sb.append(w).append(": Δ / t | "));
                sb.append("\n|---:|");
                windows.keySet().forEach(w -> sb.append("---:|"));
                sb.append("\n");
                Map<String, TreeMap<Double, Double>> curve = new LinkedHashMap<>();
                for (double q : new double[]{0.4, 0.5, 0.66, 0.8, 1.0}) {
                    sb.append(String.format(Locale.ROOT, "| %.0f%% | ", q * 100));
                    for (Map.Entry<String, long[]> w : windows.entrySet()) {
                        Config forced = pick.withExec(Exec.FORCED, q);
                        Row r = evaluate(byBase.get("SOL"), forced, w.getValue(), markBy.get("SOL"),
                                fundBy.get("SOL"), seeds);
                        curve.computeIfAbsent(w.getKey(), k -> new TreeMap<>()).put(q, r.mean);
                        sb.append(String.format(Locale.ROOT, "%+.4f / %.2f | ", r.mean, r.t()));
                    }
                    sb.append("\n");
                }
                sb.append("\nДоля мейкера, при которой выигрыш хеджа против «без хеджа» обнуляется:\n");
                curve.forEach((w, c) -> sb.append("- ").append(w).append(": ")
                        .append(zeroCross(c)).append("\n"));
            }
        }

        Path p = Path.of(out);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        log.info("отчёт: {}", p.toAbsolutePath());
    }

    /**
     * Добавочные варианты к основной сетке.
     *
     * «Только под водой» — вопрос владельца 27.09.2026: хедж включается, когда
     * опора ниже средней цены покупки запаса на X б.п., и снимается, когда она
     * вернулась выше «средняя − X/2». Смысл — не отдавать хеджу плюс, который запас
     * приносит на росте (у SOL на растущем окне это +0.043 $ на бот-сутки, 194).
     * ⚠️ По сути это стоп-лосс с возвратом: выигрывает на продолжающемся падении,
     * проигрывает на пиле.
     */
    static List<Config> extras() {
        List<Config> out = new ArrayList<>();
        for (double b : new double[]{0.5, 1, 2}) {
            out.add(new Config(Rule.BAND, b, 0, 30, true, Exec.SIM, 0));
        }
        for (double x : new double[]{10, 20}) {
            out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, x));
            out.add(new Config(Rule.PERIOD, 0, 60, 120, false, Exec.SIM, 0, x));
        }
        // Перевес продаж тейкеров Бинанса (вопрос владельца 27.09.2026): хедж включён,
        // пока за последние N минут продажи перевешивали покупки на M% и больше
        // (выключение — ниже M/2), по желанию — только при запасе от K лотов.
        for (int n : new int[]{5, 15}) {
            for (double m : new double[]{10, 20}) {
                for (double k : new double[]{0, 2}) {
                    out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, 0, n, m, k));
                }
            }
        }
        // «Только на вход» (владелец, 27.09.2026): условие лишь разрешает НАРАЩИВАТЬ
        // шорт; откуп при убыли запаса и уже открытый хедж — как в обычном хедже.
        for (double x : new double[]{10, 20}) {
            out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, x, 0, 0, 0, true));
            out.add(new Config(Rule.PERIOD, 0, 60, 120, false, Exec.SIM, 0, x, 0, 0, 0, true));
        }
        for (int n : new int[]{5, 15}) {
            for (double m : new double[]{10, 20}) {
                for (double k : new double[]{0, 2}) {
                    out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, 0, n, m, k, true));
                }
            }
        }
        // Хедж по загрузке (владелец, 27.09.2026): «излишек сверх K лотов» — шорт на
        // запас выше K, плавно; «всё с K лотов» — шорт на весь запас, пока он ≥ K.
        for (double k : new double[]{2, 3}) {
            out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, 0, 0, 0, 0, false, k));
            out.add(new Config(Rule.PERIOD, 0, 60, 120, false, Exec.SIM, 0, 0, 0, 0, 0, false, k));
            out.add(new Config(Rule.BAND, 1, 0, 30, false, Exec.SIM, 0, 0, 0, 0, k));
            out.add(new Config(Rule.PERIOD, 0, 60, 120, false, Exec.SIM, 0, 0, 0, 0, k));
        }
        return out;
    }

    /** Правило выбора — печатается ДО результатов (192 п. 6). */
    static final String RULE_TEXT = """
            ## Правило выбора (записано до прогона, 27.09.2026)

            1. Мерка — Δ = «итог без беты с ногой хеджа, за вычетом пошлин и фандинга»
               минус «итог без беты без хеджа», на бот-сутки; ошибка кластерная по суткам
               (боты одной пары внутри суток — один кластер).
            2. Для каждого окна находится лучшая настройка по Δ розыгрыша (среднее по
               зёрнам). **Плато окна** — настройки с Δ ≥ Δ_лучшей − ошибка_лучшей.
            3. Кандидаты — настройки, лежащие на плато **обоих** окон (падающего и
               растущего).
            4. Из кандидатов берётся **середина**: кандидат с МЕДИАННЫМ числом
               перевешиваний в сутки (среднее по двум окнам); при равенстве — меньше пошлины.
               ⚠️ Правка 27.09.2026 ПОСЛЕ первого прогона: там стояло «средняя по величине
               полоса», что в смешанной сетке (полосы и периоды) не определено. Первый
               прогон был к тому же ошибочен (бета перпа вычиталась по споту), его числа
               видены — поэтому правка помечена, а не выдана за исходную.
            5. Кандидатов нет — выбора нет: данные настройку не называют, пишется «плато
               на обоих окнах пусто».
            6. Проверка — новыми сутками после 22.09 (пилот), не этими.

            ⚠️ Данные умеют отсечь плохие настройки, а не назвать лучшую: Δ между
            соседними клетками обычно меньше ошибки.
            """;

    /** Выбор по правилу; результат пишется и в {@link #picked}. */
    private final Map<String, Config> picked = new ConcurrentHashMap<>();

    private String choose(String base, List<Config> grid, java.util.Set<String> windowNames,
                          Map<String, Row> rows) {
        List<Config> all = new ArrayList<>(grid);
        all.addAll(extras());
        List<Config> cand = new ArrayList<>(all);
        StringBuilder sb = new StringBuilder("\n**Выбор по правилу (").append(base).append("):** ");
        for (String w : windowNames) {
            Row best = null;
            for (Config c : all) {
                Row r = rows.get(w + "|" + c.key());
                if (r != null && (best == null || r.mean > best.mean)) {
                    best = r;
                }
            }
            if (best == null) {
                continue;
            }
            double floor = best.mean - best.se;
            cand.removeIf(c -> rows.get(w + "|" + c.key()) == null
                    || rows.get(w + "|" + c.key()).mean < floor);
        }
        if (cand.isEmpty()) {
            return sb.append("плато на обоих окнах пусто — данные настройку не называют.\n").toString();
        }
        // Середина: кандидат с медианным числом перевешиваний; при равенстве — меньше пошлины.
        List<Config> sorted = new ArrayList<>(cand);
        sorted.sort((x, y) -> {
            double tx = 0;
            double ty = 0;
            double fx = 0;
            double fy = 0;
            for (String w : windowNames) {
                tx += rows.get(w + "|" + x.key()).trades;
                ty += rows.get(w + "|" + y.key()).trades;
                fx += rows.get(w + "|" + x.key()).fees;
                fy += rows.get(w + "|" + y.key()).fees;
            }
            int byTrades = Double.compare(tx, ty);
            return byTrades != 0 ? byTrades : Double.compare(fx, fy);
        });
        Config pick = sorted.get((sorted.size() - 1) / 2);
        picked.put(base, pick);
        sb.append("кандидатов ").append(cand.size()).append(": ");
        cand.forEach(c -> sb.append(c.name()).append("; "));
        sb.append("\n→ **").append(pick.name()).append("**\n");
        return sb.toString();
    }

    private static String zeroCross(TreeMap<Double, Double> c) {
        Double prevQ = null;
        Double prevV = null;
        for (Map.Entry<Double, Double> e : c.entrySet()) {
            if (prevV != null && Math.signum(prevV) != Math.signum(e.getValue())) {
                double q = prevQ + (e.getKey() - prevQ) * (0 - prevV) / (e.getValue() - prevV);
                return String.format(Locale.ROOT, "**%.0f%%** (линейно между соседними точками)", q * 100);
            }
            prevQ = e.getKey();
            prevV = e.getValue();
        }
        double first = c.firstEntry().getValue();
        return first > 0 ? "ниже 40% — выигрыш положителен на всей сетке"
                : "выше 100% — выигрыша нет даже при всех мейкерских";
    }

    // ================================================================ оценка

    /** Строка таблицы: среднее по зёрнам и ошибка по суткам. */
    static final class Row {
        double mean;
        double se;
        double seedMin = Double.MAX_VALUE;
        double seedMax = -Double.MAX_VALUE;
        double makerAll;
        double takerAll;
        double trades;
        double fees;
        double funding;
        double takerShare;
        double plain;

        double t() {
            return se > 0 ? mean / se : 0;
        }

        String line(String name) {
            return String.format(Locale.ROOT,
                    "| %s | %+.4f | %.4f | %.2f | %+.4f…%+.4f | %+.4f | %+.4f | %.1f | %.4f | %+.4f | %.0f%% | %+.4f |%n",
                    name, mean, se, t(), seedMin, seedMax, makerAll, takerAll, trades, fees,
                    funding, takerShare * 100, plain);
        }
    }

    private Row evaluate(List<Bot> bots, Config cfg, long[] window,
                         NavigableMap<Long, Double> mark, NavigableMap<Long, Double> fund, int seeds) {
        Row row = new Row();
        List<Integer> seedList = new ArrayList<>();
        int nSeeds = cfg.exec() == Exec.SIM || cfg.exec() == Exec.FORCED ? seeds : 1;
        for (int s = 0; s < nSeeds; s++) {
            seedList.add(s);
        }
        // Зёрна параллельно: каждое — независимый прогон по всем ботам пары.
        List<double[]> perSeed = Collections.synchronizedList(new ArrayList<>());
        Map<LocalDate, double[]> firstSeedDays = new ConcurrentHashMap<>();
        IntStream.range(0, nSeeds).parallel().forEach(s -> {
            TreeMap<LocalDate, double[]> days = new TreeMap<>();
            double[] agg = new double[6];   // Δ сумма, бот-суток, сделок, пошлина, фандинг; +тейкер ниже
            double[] taker = new double[2];
            for (Bot b : bots) {
                sim(b, cfg, cfg.exec(), window, mark, fund, 1000L * (s + 1) + b.id.hashCode(),
                        days, agg, taker);
            }
            perSeed.add(new double[]{agg[0] / Math.max(1, agg[1]), agg[1], agg[2], agg[3], agg[4],
                    agg[5] / Math.max(1, agg[1]),
                    taker[1] > 0 ? taker[0] / taker[1] : 0});
            if (s == 0) {
                firstSeedDays.putAll(days);
            }
        });
        double sum = 0;
        for (double[] v : perSeed) {
            sum += v[0];
            row.seedMin = Math.min(row.seedMin, v[0]);
            row.seedMax = Math.max(row.seedMax, v[0]);
            row.trades += v[2] / Math.max(1, v[1]) / perSeed.size();
            row.fees += v[3] / Math.max(1, v[1]) / perSeed.size();
            row.funding += v[4] / Math.max(1, v[1]) / perSeed.size();
            row.plain += v[5] / perSeed.size();
            row.takerShare += v[6] / perSeed.size();
        }
        row.mean = sum / perSeed.size();
        // Ошибка — кластерная по суткам на первом зерне (разброс зёрен печатается рядом).
        row.se = clusterSe(firstSeedDays);
        // Границы исполнения.
        row.makerAll = boundary(bots, cfg, Exec.MAKER, window, mark, fund);
        row.takerAll = boundary(bots, cfg, Exec.TAKER, window, mark, fund);
        return row;
    }

    private double boundary(List<Bot> bots, Config cfg, Exec exec, long[] window,
                            NavigableMap<Long, Double> mark, NavigableMap<Long, Double> fund) {
        TreeMap<LocalDate, double[]> days = new TreeMap<>();
        double[] agg = new double[6];
        double[] taker = new double[2];
        for (Bot b : bots) {
            sim(b, cfg, exec, window, mark, fund, 1, days, agg, taker);
        }
        return agg[0] / Math.max(1, agg[1]);
    }

    /**
     * Ошибка среднего на бот-сутки, кластер — сутки: {@code days} хранит по суткам
     * {Σ Δ, число бот-суток}.
     */
    static double clusterSe(Map<LocalDate, double[]> days) {
        double n = 0;
        double s = 0;
        for (double[] v : days.values()) {
            s += v[0];
            n += v[1];
        }
        if (n < 2 || days.size() < 2) {
            return 0;
        }
        double mean = s / n;
        double var = 0;
        for (double[] v : days.values()) {
            double r = v[0] - mean * v[1];
            var += r * r;
        }
        int c = days.size();
        return Math.sqrt(var * c / (c - 1)) / n;
    }

    /**
     * Прогон одного бота на одном окне. Копит в {@code days} по суткам {Σ Δ,
     * бот-суток}, в {@code agg} — {Σ Δ, бот-суток, сделок, пошлина, фандинг}, в
     * {@code taker} — {тейкерских, всего исполнений}.
     */
    void sim(Bot b, Config cfg, Exec exec, long[] window, NavigableMap<Long, Double> mark,
             NavigableMap<Long, Double> fund, long seed, Map<LocalDate, double[]> days,
             double[] agg, double[] taker) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double step = STEP.get(b.base);
        double halfSpread = SPREAD_BP.get(b.base) / 2e4;
        double[] probs = FILL.get(b.base);
        double band = cfg.bandLots() * b.lot;
        long period = cfg.periodMin() * 60_000L;
        long wait = cfg.waitSec() * 1000L;

        int i0 = lowerBound(b.ts, window[0]);
        int i1 = lowerBound(b.ts, window[1]);
        if (i1 - i0 < 100) {
            return;
        }
        int fi = lowerBound(b.fillTs, b.ts[i0]);
        int di = 0;
        while (di < b.darkStarts.size() && b.darkStarts.get(di) < b.ts[i0]) {
            di++;
        }

        // Состояние
        double cash = 0;
        double perp = 0;
        double hedgePnl = 0;      // переоценка перпа, пошлина и фандинг — всё сюда
        double fees = 0;
        double funding = 0;
        int trades = 0;
        long lastPeriodic = b.ts[i0];
        // Средняя цена покупки запаса и условие «под водой».
        double avgCost = 0;
        double costQty = -1;              // −1 — ещё не заведено (первый тик окна)
        boolean gate = false;
        boolean flowOn = false;
        boolean activeWas = true;
        // Висящая заявка на Kraken: размер, момент постановки, цена касания, исполнится ли и когда.
        double pendQty = 0;
        long pendT0 = 0;
        long pendFillAt = Long.MAX_VALUE;
        double pendPx = 0;
        boolean pendMaker = false;

        LocalDate curDay = null;
        double dayEq0Plain = 0;
        double dayEq0Hedged = 0;
        double dayP0 = 0;
        double areaPlain = 0;
        double areaPerp = 0;
        double dayM0 = 0;
        double lastM = 0;
        double areaT = 0;
        double prevF = 0;
        double prevInv = 0;
        long prevT = 0;
        double prevM = 0;
        double prevEqPlain = 0;
        double prevLastM = 0;
        double prevEqHedged = 0;
        double prevPerp = 0;

        for (int i = i0; i < i1; i++) {
            long t = b.ts[i];
            double f = b.fair[i];
            double inv = b.inv[i];
            while (fi < b.fillTs.length && b.fillTs[fi] <= t) {
                double dq = b.fillDq[fi];
                double px = b.fillPx[fi];
                cash -= dq * px;
                // Средняя цена покупки запаса (средневзвешенная): покупка сдвигает её,
                // продажа — нет; запас обнулился — сброс.
                if (dq > 0) {
                    avgCost = (costQty * avgCost + dq * px) / (costQty + dq);
                    costQty += dq;
                } else {
                    costQty = Math.max(0, costQty + dq);
                    if (costQty < 1e-12) {
                        avgCost = 0;
                    }
                }
                fi++;
            }
            if (costQty < 0) {                // первый тик окна: цена входа — опора
                costQty = inv;
                avgCost = inv > 0 ? f : 0;
            }
            // Запас по тикам — источник правды: прирост без сделки (передача, затравка)
            // считается покупкой по опоре, убыль — продажей. Без этого средняя цена
            // не заводилась бы, и хедж «под водой» не включался бы никогда.
            if (inv > costQty + 1e-12) {
                avgCost = (costQty * avgCost + (inv - costQty) * f) / inv;
                costQty = inv;
            } else if (inv < costQty - 1e-12) {
                costQty = Math.max(0, inv);
                if (costQty < 1e-12) {
                    avgCost = 0;
                }
            }
            // «Под водой»: включаемся, когда опора ниже средней покупки на X б.п.;
            // выключаемся, когда вернулась выше «средняя − X/2» (зазор от дёрганья).
            boolean gateWas = gate;
            if (cfg.underwaterBp() > 0) {
                double x = cfg.underwaterBp() / 1e4;
                if (!gate && avgCost > 0 && inv > 0 && f < avgCost * (1 - x)) {
                    gate = true;
                } else if (gate && (avgCost <= 0 || inv <= 0 || f >= avgCost * (1 - x / 2))) {
                    gate = false;
                }
            }
            // Перевес продаж тейкеров Бинанса за последние N ЗАКРЫТЫХ минут; свеча
            // известна через 30 с после закрытия (как в A89), иначе прибор знал бы
            // перевес минуты в её середине.
            if (cfg.flowMin() > 0) {
                double imb = b.sellImbalance(t, cfg.flowMin());
                double mth = cfg.flowPct() / 100;
                if (!flowOn && imb >= mth) {
                    flowOn = true;
                } else if (flowOn && imb < mth / 2) {
                    flowOn = false;
                }
            }
            boolean lotsOk = cfg.minLots() <= 0 || inv >= cfg.minLots() * b.lot - 1e-12;
            boolean active = (cfg.underwaterBp() <= 0 || gate)
                    && (cfg.flowMin() <= 0 || flowOn) && lotsOk;
            double m = markAt(mark, t);
            // Переоценка перпа и фандинг за прошедший интервал (в т.ч. через темноту).
            if (prevT > 0 && m > 0 && prevM > 0) {
                hedgePnl += perp * (m - prevM);
                double fu = -perp * prevM * fundingBetween(fund, prevT, t);
                hedgePnl += fu;
                funding += fu;
            }
            // Висящая заявка: исполнилась мейкером или пора добивать тейкером.
            if (pendQty != 0) {
                if (pendMaker && t >= pendFillAt) {
                    perp += pendQty;
                    // Исполнение по цене касания: разница с маркой — в пользу мейкера.
                    hedgePnl += pendQty * (m - pendPx);
                    double fee = Math.abs(pendQty) * pendPx * MAKER_FEE;
                    hedgePnl -= fee;
                    fees += fee;
                    trades++;
                    taker[1]++;
                    pendQty = 0;
                } else if (!pendMaker && t - pendT0 >= wait) {
                    double px = m * (1 + Math.signum(pendQty) * halfSpread);
                    perp += pendQty;
                    hedgePnl += pendQty * (m - px);
                    double fee = Math.abs(pendQty) * px * TAKER_FEE;
                    hedgePnl -= fee;
                    fees += fee;
                    trades++;
                    taker[0]++;
                    taker[1]++;
                    pendQty = 0;
                }
            }
            // Решение (если заявки нет).
            boolean darkNext = di < b.darkStarts.size() && i + 1 < b.ts.length
                    && b.darkStarts.get(di) <= b.ts[i + 1] && b.darkStarts.get(di) >= t;
            if (pendQty == 0 && m > 0) {
                // Цель перпа: −запас, а при условии «под водой» — только пока оно
                // выполнено, иначе ноль. «net» — отклонение от цели.
                // «Только на вход»: цель всегда −запас, но НАРАЩИВАТЬ шорт можно лишь
                // при выполненном условии; откуп при убыли запаса — как обычно.
                // Хедж излишка: страхуем только запас сверх K лотов — мелкий быстрый
                // запас бот сбрасывает сам, «загрузку» страхуем (владелец, 27.09.2026).
                double hedged = cfg.excessLots() > 0
                        ? Math.max(0, inv - cfg.excessLots() * b.lot) : inv;
                double want = active || cfg.entryOnly() ? -hedged : 0;
                double net = perp - want;
                boolean due = false;
                switch (cfg.rule()) {
                    case BAND -> due = Math.abs(net) > band;
                    case PERIOD -> {
                        if (t - lastPeriodic >= period) {
                            lastPeriodic = t;
                            due = Math.abs(net) >= step;
                        }
                    }
                    case PERIOD_BAND -> {
                        if (t - lastPeriodic >= period) {
                            lastPeriodic = t;
                            due = Math.abs(net) > band;
                        }
                    }
                }
                if (cfg.flatten() && darkNext) {
                    due = Math.abs(net) >= step;
                }
                // Условие переключилось — действуем сразу, не дожидаясь полосы или часа.
                if (active != activeWas && (!cfg.entryOnly() || active)) {
                    due = Math.abs(net) >= step;
                }
                // Переключение отмечается только тогда, когда было кому на него
                // ответить: при висящей заявке оно подождёт до её исполнения.
                activeWas = active;
                if (due) {
                    double delta = want - perp;
                    if (cfg.entryOnly() && !active && delta < 0) {
                        delta = 0;             // условие не выполнено — шорт не наращиваем
                    }
                    double q = Math.signum(delta) * Math.floor(Math.abs(delta) / step + 1e-9) * step;
                    if (Math.abs(q) >= step) {
                        // Перед темнотой — сразу тейкером: бот гаснет сейчас.
                        boolean now = cfg.flatten() && darkNext;
                        Order o = startOrder(q, t, m, halfSpread, exec, cfg, probs, rnd, now);
                        pendQty = q;
                        pendT0 = t;
                        pendPx = o.px;
                        pendMaker = o.maker;
                        pendFillAt = o.fillAt;
                        if (!pendMaker && (exec == Exec.TAKER || now)) {
                            // тейкер немедленно
                            double px = m * (1 + Math.signum(q) * halfSpread);
                            perp += q;
                            hedgePnl += q * (m - px);
                            double fee = Math.abs(q) * px * TAKER_FEE;
                            hedgePnl -= fee;
                            fees += fee;
                            trades++;
                            taker[0]++;
                            taker[1]++;
                            pendQty = 0;
                        } else if (pendMaker && pendFillAt <= t) {
                            perp += q;
                            hedgePnl += q * (m - pendPx);
                            double fee = Math.abs(q) * pendPx * MAKER_FEE;
                            hedgePnl -= fee;
                            fees += fee;
                            trades++;
                            taker[1]++;
                            pendQty = 0;
                        }
                    }
                }
            }
            if (darkNext) {
                di++;
            }
            // Сутки: итог без беты считается по суткам UTC.
            LocalDate d = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
            double eqPlain = cash + inv * f;
            double eqHedged = eqPlain + hedgePnl;
            if (m > 0) {
                lastM = m;
            }
            if (curDay == null) {
                curDay = d;
                dayEq0Plain = eqPlain;
                dayEq0Hedged = eqHedged;
                dayP0 = f;
                dayM0 = lastM;
            } else if (!d.equals(curDay)) {
                closeDay(curDay, prevF, dayP0, prevLastM, dayM0, prevEqPlain, dayEq0Plain,
                        prevEqHedged, dayEq0Hedged, areaPlain, areaPerp, areaT, days, agg);
                curDay = d;
                dayEq0Plain = prevEqPlain;
                dayEq0Hedged = prevEqHedged;
                dayP0 = prevF;
                dayM0 = prevLastM;
                areaPlain = 0;
                areaPerp = 0;
                areaT = 0;
            }
            if (prevT > 0) {
                double dt = t - prevT;
                areaPlain += prevInv * dt;
                areaPerp += prevPerp * dt;
                areaT += dt;
            }
            prevEqPlain = eqPlain;
            prevEqHedged = eqHedged;
            prevF = f;
            prevInv = inv;
            prevPerp = perp;
            prevT = t;
            prevM = m;
            prevLastM = lastM;
        }
        if (curDay != null) {
            closeDay(curDay, prevF, dayP0, prevLastM, dayM0, prevEqPlain, dayEq0Plain,
                    prevEqHedged, dayEq0Hedged, areaPlain, areaPerp, areaT, days, agg);
        }
        agg[2] += trades;
        agg[3] += fees;
        agg[4] += funding;
    }

    /** Что решила постановка: цена касания, исполнится ли мейкером и когда. */
    static final class Order {
        double px;
        boolean maker;
        long fillAt;
    }

    /**
     * Постановка заявки на Kraken. Решает, исполнится ли она мейкером за время
     * ожидания и когда. Цена касания — марка минус полспреда для покупки (плюс для
     * продажи): мейкер стоит на лучшей цене своей стороны.
     */
    private static Order startOrder(double q, long t, double m, double halfSpread, Exec exec,
                                    Config cfg, double[] probs, SplittableRandom rnd, boolean now) {
        Order o = new Order();
        o.px = m * (1 - Math.signum(q) * halfSpread);
        if (now || exec == Exec.TAKER) {
            o.maker = false;
            o.fillAt = Long.MAX_VALUE;
            return o;
        }
        if (exec == Exec.MAKER) {
            o.maker = true;
            o.fillAt = t;
            return o;
        }
        double pT = exec == Exec.FORCED ? cfg.forcedMaker() : fillProb(probs, cfg.waitSec());
        double u = rnd.nextDouble();
        if (u < pT) {
            o.maker = true;
            // Момент исполнения — обратная кусочно-линейная функция распределения.
            double sec = exec == Exec.FORCED ? u / pT * cfg.waitSec() : inverseFill(probs, u);
            o.fillAt = t + (long) (sec * 1000);
        } else {
            o.maker = false;
            o.fillAt = Long.MAX_VALUE;
        }
        return o;
    }

    /** Вероятность мейкерского исполнения за {@code sec} секунд (163). */
    static double fillProb(double[] p, double sec) {
        double[] xs = {0, 10, 30, 60};
        double[] ys = {0, p[0], p[1], p[2]};
        if (sec >= 60) {
            return p[2];      // ⚠️ дальше 60 с замера нет — оценка снизу
        }
        for (int k = 1; k < xs.length; k++) {
            if (sec <= xs[k]) {
                return ys[k - 1] + (ys[k] - ys[k - 1]) * (sec - xs[k - 1]) / (xs[k] - xs[k - 1]);
            }
        }
        return p[2];
    }

    static double inverseFill(double[] p, double u) {
        double[] xs = {0, 10, 30, 60};
        double[] ys = {0, p[0], p[1], p[2]};
        for (int k = 1; k < xs.length; k++) {
            if (u <= ys[k]) {
                return xs[k - 1] + (xs[k] - xs[k - 1]) * (u - ys[k - 1]) / (ys[k] - ys[k - 1]);
            }
        }
        return 60;
    }

    /**
     * ⚠️ БЕТА КАЖДОЙ НОГИ — ПО ЕЁ СОБСТВЕННОЙ ЦЕНЕ: спот по споту (USDC), перп по
     * марке перпа (USD). Первая версия вычитала бету чистой экспозиции по споту, и
     * дрейф USDC/USD и базиса за сутки целиком садился в «выигрыш хеджа»: на SOL
     * растущего окна это дало −0.03 $ на бот-сутки при t = −3 (27.09.2026).
     */
    private static void closeDay(LocalDate d, double p1, double p0, double m1, double m0,
                                 double eq1Plain, double eq0Plain,
                                 double eq1Hedged, double eq0Hedged, double areaPlain,
                                 double areaPerp, double areaT, Map<LocalDate, double[]> days,
                                 double[] agg) {
        if (areaT < 6 * HOUR) {
            return;       // сутки короче шести часов данных — край окна, не считаем
        }
        double betaPlain = areaPlain / areaT * (p1 - p0);
        double betaNet = areaPlain / areaT * (p1 - p0)
                + (m0 > 0 && m1 > 0 ? areaPerp / areaT * (m1 - m0) : 0);
        double exPlain = (eq1Plain - eq0Plain) - betaPlain;
        double exHedged = (eq1Hedged - eq0Hedged) - betaNet;
        double diff = exHedged - exPlain;
        synchronized (days) {
            double[] v = days.computeIfAbsent(d, k -> new double[2]);
            v[0] += diff;
            v[1] += 1;
        }
        agg[0] += diff;
        agg[5] += exPlain;
        agg[1] += 1;
    }

    // ================================================================ данные

    private static int lowerBound(long[] a, long x) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double markAt(NavigableMap<Long, Double> mark, long t) {
        Map.Entry<Long, Double> e = mark.floorEntry(t);
        return e == null || t - e.getKey() > 10 * 60_000L ? 0 : e.getValue();
    }

    /**
     * Σ ставка × доля часа на отрезке [t0, t1): темнота длится часами, и ставка на её
     * начало была бы неверна для всего отрезка.
     */
    static double fundingBetween(NavigableMap<Long, Double> fund, long t0, long t1) {
        double s = 0;
        long t = t0;
        while (t < t1) {
            long hourEnd = (t / HOUR + 1) * HOUR;
            long end = Math.min(hourEnd, t1);
            s += fundAt(fund, t) * (end - t) / (double) HOUR;
            t = end;
        }
        return s;
    }

    private static double fundAt(NavigableMap<Long, Double> fund, long t) {
        Map.Entry<Long, Double> e = fund.floorEntry(t);
        return e == null ? 0 : e.getValue();
    }

    /** Часовые относительные ставки фандинга Kraken (API v4, без ключа). */
    private NavigableMap<Long, Double> funding(String perp) throws Exception {
        TreeMap<Long, Double> out = new TreeMap<>();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(
                        "https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol=" + perp))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        for (JsonNode n : MAPPER.readTree(r.body()).path("rates")) {
            out.put(Instant.parse(n.path("timestamp").asText()).toEpochMilli(),
                    n.path("relativeFundingRate").asDouble());
        }
        log.info("фандинг {}: {} часовых ставок", perp, out.size());
        return out;
    }

    /** Тики, сделки и начала бюджетной темноты из журнала. */
    static Bot read(String path, long from, long to) throws Exception {
        Bot b = new Bot();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro")) {
            List<long[]> tsList = new ArrayList<>();
            List<double[]> fi = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, fair, inventory FROM exec_quote WHERE ts_ms BETWEEN ? AND ? "
                            + "AND fair > 0 ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tsList.add(new long[]{rs.getLong(1)});
                        fi.add(new double[]{rs.getDouble(2), rs.getDouble(3)});
                    }
                }
            }
            b.ts = new long[tsList.size()];
            b.fair = new double[tsList.size()];
            b.inv = new double[tsList.size()];
            for (int i = 0; i < b.ts.length; i++) {
                b.ts[i] = tsList.get(i)[0];
                b.fair[i] = fi.get(i)[0];
                b.inv[i] = fi.get(i)[1];
            }
            List<double[]> fills = new ArrayList<>();
            List<Double> qty = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, side, qty, price FROM exec_fill WHERE ts_ms BETWEEN ? AND ? "
                            + "ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double q = rs.getDouble(3);
                        double sign = "SELL".equalsIgnoreCase(rs.getString(2)) ? -1 : 1;
                        fills.add(new double[]{rs.getLong(1), sign * q, rs.getDouble(4)});
                        qty.add(q);
                    }
                }
            }
            b.fillTs = new long[fills.size()];
            b.fillDq = new double[fills.size()];
            b.fillPx = new double[fills.size()];
            for (int i = 0; i < fills.size(); i++) {
                b.fillTs[i] = (long) fills.get(i)[0];
                b.fillDq[i] = fills.get(i)[1];
                b.fillPx[i] = fills.get(i)[2];
            }
            Collections.sort(qty);
            b.lot = qty.isEmpty() ? 0 : qty.get(qty.size() / 2);
            // Бюджетная темнота: stop в пределах 5 с после limit_blocked ПО ПОСТАНОВКАМ.
            // ⚠️ limit_blocked пишется и по денежным пределам («нотионал»,
            // «экспозиция»); без фильтра первая версия насчитала b девять бюджетных
            // остановок там, где по Л2 (193) их нет ни одной.
            // ⚠️ НЕ Long.MIN_VALUE: t − MIN_VALUE переполняется в отрицательное, и каждая
            // остановка сходила за бюджетную (27.09.2026, та же грабля, что в HedgeOverlay).
            long lastBlocked = -1_000_000_000_000L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts_ms, kind, detail FROM exec_event WHERE ts_ms BETWEEN ? AND ? "
                            + "AND kind IN ('limit_blocked','stop') ORDER BY ts_ms")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("limit_blocked".equals(rs.getString(2))) {
                            String detail = rs.getString(3);
                            if (detail == null || !detail.contains("постановк")) {
                                continue;
                            }
                            lastBlocked = rs.getLong(1);
                        } else if (rs.getLong(1) - lastBlocked <= 5_000L) {
                            b.darkStarts.add(rs.getLong(1));
                        }
                    }
                }
            }
        }
        return b;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
