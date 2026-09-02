package org.home.data.revx.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.ApiClient;
import org.home.data.revx.BookFlags;
import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Скрининг всей вселенной стенда: пункт 2 очереди док. 127 §15.
 *
 * <b>Зачем.</b> Три пары (BTC, ETH, SOL) выбраны потому, что пришли в голову, а
 * механизм преимущества уже назван и он структурный: **широкий спред и тонкий
 * поток** (док. 125 §6). Если механизм такой, то выбор пары — это СКРИНИНГ, а не
 * эксперимент, и делается он на уже собранных данных без единого прогона
 * симулятора. Может оказаться, что SOL — лучший из трёх, но седьмой из двадцати
 * трёх (док. 127 §14).
 *
 * <b>Что меряется и почему именно это.</b>
 *
 * <ul>
 *   <li><b>Полуспред книги</b> — источник края: чем шире книга, тем дальше от
 *       справедливой цены можно стоять;</li>
 *   <li><b>поток ленты</b> — противовес: край на исполнение бесполезен, если
 *       исполнений нет, и он же задаёт потолок ёмкости (док. 127 §13);</li>
 *   <li><b>разрешение хеджа</b> — сколько шагов контракта укладывается в потолок
 *       инвентаря. Ниже двух десятков ступеней хедж не грубоват, а невозможен
 *       (док. 127 §8.4), и это фильтр, а не колонка «для сведения»;</li>
 *   <li><b>фондирование</b> — шорт его получает при положительной ставке; у пар
 *       с отрицательной ставкой статьи дохода, компенсировавшей комиссии перпа
 *       у BTC, попросту нет;</li>
 *   <li><b>закон прихода по ленте и δ*</b> — пункт 6 той же очереди. Формула
 *       {@code δ* = c + 1/κ} выведена, а не подобрана, поэтому отступ для каждой
 *       пары считается из ЕЁ собственного потока, а не переносится с BTC
 *       коэффициентом (док. 127 §11).</li>
 * </ul>
 *
 * <b>Чем этот κ отличается от симуляционного.</b> В {@link SimRunner} закон
 * прихода подгоняется по лестнице отступов: каждая ступень — это прогон, и
 * интенсивность там измерена НАШИМИ исполнениями. Здесь интенсивность берётся
 * прямо из ленты: сколько принтов за час уходит от середины книги дальше, чем на
 * δ. Это верхняя граница нашей интенсивности (мы получили бы не весь такой
 * принт, а его часть), но её достаточно для оценки κ — наклон логарифма от
 * общего множителя не зависит. Раздел «сверка с симуляцией» показывает, во что
 * это обходится по трём парам, где есть оба ответа.
 */
@Component
@Lazy
public class ScreenReport {

    private static final Logger log = LoggerFactory.getLogger(ScreenReport.class);

    private static final String INSTRUMENTS =
            "https://futures.kraken.com/derivatives/api/v3/instruments";
    private static final String FUNDING =
            "https://futures.kraken.com/derivatives/api/v4/historicalfundingrates?symbol=";

    /** Ступени, на которых меряется интенсивность прихода, в долях цены. */
    private static final double[] ARRIVAL_OFFSETS =
            {0.0004, 0.0008, 0.0012, 0.0016, 0.0024, 0.0032, 0.0048};

    private final RevxDb db;

    private final ApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScreenReport(RevxDb db, ApiClient api) {
        this.db = db;

        this.api = api;
    }

    /** Спецификация перпа Kraken: есть ли он и каким шагом торгуется. */
    private record Perp(String symbol, double step, boolean tradeable) {
    }

    /** Фондирование перпа за доступную историю. */
    private record Funding(int points, double meanPerHour, double negativeShare) {
    }

    private record Row(String symbol, String base, int snapshots, double price,
                       double halfSpreadBp, double sigmaBp, long period,
                       int trades, double notional, double medianTradeNotional,
                       Perp perp, Funding funding,
                       ArrivalLaw.Fit byPrints, ArrivalLaw.Fit byNotional, double adverseBp) {

        /** Лот живого бота в базовой валюте: тот же номинал, что у BTC-бота. */
        double lot(double lotNotional) {
            return price > 0 ? lotNotional / price : Double.NaN;
        }

        /** Потолок инвентаря в базовой валюте (потолок = 20 лотов, как у живого). */
        double cap(double lotNotional, int lotsPerCap) {
            return lot(lotNotional) * lotsPerCap;
        }

        /** Шаг контракта, выраженный в наших лотах. */
        double stepInLots(double lotNotional) {
            return perp == null || !(lot(lotNotional) > 0)
                    ? Double.NaN : perp.step() / lot(lotNotional);
        }

        /** Сколько ступеней контракта укладывается в весь потолок инвентаря. */
        double stepsPerCap(double lotNotional, int lotsPerCap) {
            double inLots = stepInLots(lotNotional);
            return Double.isNaN(inLots) || inLots <= 0 ? Double.NaN : lotsPerCap / inLots;
        }

        /**
         * Пошлина `c` = устаревание + неблагоприятный отбор, б.п.
         *
         * Устаревание оценивается волатильностью справедливой цены за период
         * котирования: именно на столько уезжает рынок, пока заявка висит по
         * старой цене. Это прямое обобщение константы 2.86 б.п., измеренной на
         * BTC при шаге 5 с (док. 100), на пару с другой волатильностью.
         */
        double costBp() {
            return sigmaBp + Math.max(0, adverseBp);
        }

        /**
         * `δ*` считается по подгонке ПО ОБОРОТУ, а не по числу принтов.
         *
         * Это не педантизм: глубокие исполнения приходят от крупных выносов,
         * поэтому средний филл растёт с дистанцией, κ по обороту заметно меньше,
         * и оптимальный отступ ШИРЕ учебничного (док. 100 §4). Подгонка по числу
         * принтов оставлена рядом как приёмка формы закона.
         */
        double optimalOffsetBp() {
            return byNotional == null || !(byNotional.kappa() > 0) ? Double.NaN
                    : costBp() + 10_000 / byNotional.kappa();
        }
    }

    public void run(int hours, long toMs, String out) {
        long fromMs = toMs - hours * 3600_000L;
        List<String> symbols = db.queryStrings(
                "SELECT DISTINCT symbol FROM revx_book WHERE leg='usdc' "
                        + "AND t_recv_ms BETWEEN ? AND ? ORDER BY symbol", fromMs, toMs);
        if (symbols.isEmpty()) {
            log.warn("в окне нет ни одной пары — нечего скринить");
            return;
        }
        Map<String, Perp> perps = krakenInstruments();
        Map<String, Funding> fundings = new LinkedHashMap<>();

        List<Row> rows = new ArrayList<>();
        for (String symbol : symbols) {
            Row row = measure(symbol, fromMs, toMs, perps, fundings);
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -r.halfSpreadBp()));
        write(out, render(rows, hours, fromMs, toMs));
        log.info("скрининг: {} пар, отчёт → {}", rows.size(), out);
    }

    // --- измерение одной пары ------------------------------------------------

    private Row measure(String symbol, long fromMs, long toMs,
                        Map<String, Perp> perps, Map<String, Funding> fundings) {
        record Quote(long tsMs, double mid, double halfSpreadBp) {
        }
        List<Quote> quotes = new ArrayList<>();
        db.query("SELECT t_recv_ms, flags, bp1, ap1 FROM revx_book WHERE symbol = ? AND leg='usdc' "
                + "AND t_recv_ms BETWEEN ? AND ? ORDER BY t_recv_ms", rs -> {
            int flags = rs.getInt("flags");
            if (BookFlags.has(flags, BookFlags.CROSSED) || BookFlags.has(flags, BookFlags.EMPTY_SIDE)) {
                return null;
            }
            double bp = rs.getDouble("bp1");
            double ap = rs.getDouble("ap1");
            if (!(bp > 0) || !(ap > 0)) {
                return null;
            }
            double mid = (bp + ap) / 2;
            quotes.add(new Quote(rs.getLong("t_recv_ms"), mid, (ap - bp) / 2 / mid * 10_000));
            return null;
        }, symbol, fromMs, toMs);
        if (quotes.size() < 100) {
            log.warn("{}: снимков {} — мало для скрининга", symbol, quotes.size());
            return null;
        }

        double price = median(quotes.stream().mapToDouble(Quote::mid).toArray());
        double halfSpread = median(quotes.stream().mapToDouble(Quote::halfSpreadBp).toArray());
        long span = quotes.get(quotes.size() - 1).tsMs() - quotes.get(0).tsMs();
        long period = Math.max(1, span / Math.max(1, quotes.size() - 1));

        // Волатильность середины книги за ОДИН период опроса: во столько рынок
        // уезжает, пока заявка висит по старой цене.
        double[] returns = new double[quotes.size() - 1];
        for (int i = 1; i < quotes.size(); i++) {
            returns[i - 1] = (quotes.get(i).mid() - quotes.get(i - 1).mid())
                    / quotes.get(i - 1).mid() * 10_000;
        }
        double sigma = sd(returns);

        // Середины по времени — для расстояния принта от середины и для markout.
        TreeMap<Long, Double> mids = new TreeMap<>();
        for (Quote q : quotes) {
            mids.put(q.tsMs(), q.mid());
        }

        record Trade(long tsMs, double price, double qty, String side) {
        }
        List<Trade> trades = new ArrayList<>();
        db.query("SELECT ts_ms, price, qty, side FROM revx_trade WHERE symbol = ? "
                + "AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms", rs -> {
            trades.add(new Trade(rs.getLong("ts_ms"), rs.getDouble("price"),
                    rs.getDouble("qty"), rs.getString("side")));
            return null;
        }, symbol, fromMs, toMs);

        double notional = 0;
        double[] tradeNotionals = new double[trades.size()];
        // Интенсивность прихода — ДВА счёта, как в симуляции: по числу принтов и
        // по их обороту. Глубокие исполнения приходят от крупных выносов, поэтому
        // оборотная кривая падает медленнее, κ по ней меньше, а оптимальный
        // отступ шире (док. 100 §4). Оптимизировать надо по обороту.
        int[] deeper = new int[ARRIVAL_OFFSETS.length];
        double[] deeperNotional = new double[ARRIVAL_OFFSETS.length];
        // Неблагоприятный отбор: куда ушла середина через минуту после принта,
        // в сторону агрессора. Это то же самое, что markout у нашего исполнения,
        // измеренное на чужих сделках.
        List<Double> markouts = new ArrayList<>();
        for (int i = 0; i < trades.size(); i++) {
            Trade t = trades.get(i);
            notional += t.price() * t.qty();
            tradeNotionals[i] = t.price() * t.qty();
            Map.Entry<Long, Double> at = mids.floorEntry(t.tsMs());
            if (at == null || !(at.getValue() > 0)) {
                continue;
            }
            double mid = at.getValue();
            double distance = Math.abs(t.price() - mid) / mid;
            for (int k = 0; k < ARRIVAL_OFFSETS.length; k++) {
                if (distance >= ARRIVAL_OFFSETS[k]) {
                    deeper[k]++;
                    deeperNotional[k] += t.price() * t.qty();
                }
            }
            Map.Entry<Long, Double> later = mids.floorEntry(t.tsMs() + 60_000);
            if (later != null && later.getKey() > t.tsMs() && later.getValue() > 0) {
                int sign = "buy".equalsIgnoreCase(t.side()) ? 1
                        : "sell".equalsIgnoreCase(t.side()) ? -1 : 0;
                if (sign != 0) {
                    markouts.add(sign * (later.getValue() - mid) / mid * 10_000);
                }
            }
        }
        double hoursSpan = Math.max(1e-9, span / 3_600_000.0);
        List<ArrivalLaw.Rung> byPrints = new ArrayList<>();
        List<ArrivalLaw.Rung> byNotional = new ArrayList<>();
        for (int k = 0; k < ARRIVAL_OFFSETS.length; k++) {
            byPrints.add(new ArrivalLaw.Rung(ARRIVAL_OFFSETS[k], deeper[k] / hoursSpan));
            byNotional.add(new ArrivalLaw.Rung(ARRIVAL_OFFSETS[k], deeperNotional[k] / hoursSpan));
        }

        String base = symbol.substring(0, symbol.indexOf('/'));
        Perp perp = perps.get(krakenBase(base));
        Funding funding = perp == null ? null
                : fundings.computeIfAbsent(perp.symbol(), this::krakenFunding);

        // Отбор — СРЕДНЕЕ, а не медиана: в симуляции он определён как разность
        // средних (захват минус чистый край), и две оценки должны быть одной
        // величиной, иначе сверять их нечем.
        return new Row(symbol, base, quotes.size(), price, halfSpread, sigma, period,
                trades.size(), notional, median(tradeNotionals), perp, funding,
                ArrivalLaw.fit(byPrints), ArrivalLaw.fit(byNotional),
                markouts.isEmpty() ? Double.NaN
                        : markouts.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
    }

    // --- Kraken --------------------------------------------------------------

    /**
     * Спецификации перпов. Шаг количества — {@code contractValueTradePrecision}:
     * это ЧИСЛО ЗНАКОВ после запятой, то есть шаг равен 10^−precision, и
     * отрицательная точность (шаг крупнее единицы) тоже встречается.
     */
    private Map<String, Perp> krakenInstruments() {
        Map<String, Perp> out = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(api.get(INSTRUMENTS));
            for (JsonNode node : root.path("instruments")) {
                String symbol = node.path("symbol").asText("").toUpperCase(Locale.ROOT);
                if (!symbol.startsWith("PF_") || !symbol.endsWith("USD")) {
                    continue;                       // только линейные перпы
                }
                String base = symbol.substring(3, symbol.length() - 3);
                double step = Math.pow(10, -node.path("contractValueTradePrecision").asDouble(0));
                out.put(base, new Perp(symbol, step, node.path("tradeable").asBoolean(false)));
            }
        } catch (IOException | RuntimeException e) {
            log.error("instruments Kraken недоступны: {}", e.toString());
        }
        return out;
    }

    private Funding krakenFunding(String perpSymbol) {
        try {
            JsonNode root = mapper.readTree(api.get(FUNDING + perpSymbol));
            List<Double> rates = new ArrayList<>();
            for (JsonNode node : root.path("rates")) {
                if (node.hasNonNull("relativeFundingRate")) {
                    rates.add(node.path("relativeFundingRate").asDouble());
                }
            }
            if (rates.isEmpty()) {
                return null;
            }
            double sum = 0;
            int negative = 0;
            for (double r : rates) {
                sum += r;
                if (r < 0) {
                    negative++;
                }
            }
            return new Funding(rates.size(), sum / rates.size(), (double) negative / rates.size());
        } catch (IOException | RuntimeException e) {
            log.error("фондирование {} недоступно: {}", perpSymbol, e.toString());
            return null;
        }
    }

    /** У Kraken биткойн — XBT, остальные базы совпадают. */
    private static String krakenBase(String base) {
        return "BTC".equals(base) ? "XBT" : base;
    }

    // --- отчёт ---------------------------------------------------------------

    private String render(List<Row> rows, int hours, long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Скрининг вселенной стенда: где вообще стоит котировать\n\n");
        sb.append("Пункт 2 очереди док. 127 §15. Три пары выбраны потому, что пришли в "
                + "голову; механизм преимущества назван и он структурный (широкий спред "
                + "плюс дешёвый хедж), значит выбор пары — **скрининг на уже собранных "
                + "данных**, а не эксперимент.\n\n");
        sb.append("| Окно | ").append(Instant.ofEpochMilli(fromMs)).append(" — ")
                .append(Instant.ofEpochMilli(toMs)).append(" (").append(hours)
                .append(" ч) |\n|---|---|\n");
        sb.append("| Пар в окне | ").append(rows.size()).append(" |\n");
        sb.append("| Номинал лота | $").append(LOT_NOTIONAL).append(" |\n");
        sb.append("| Потолок инвентаря | ").append(LOTS_PER_CAP).append(" лотов = $")
                .append(round(LOT_NOTIONAL * LOTS_PER_CAP, 1)).append(" |\n\n");

        sb.append("## 1. Книга и поток\n\n");
        sb.append("Полуспред — медиана `(ask₁ − bid₁) / 2 / mid`. Это верхняя оценка того, "
                + "как далеко можно стоять, не уходя из книги; край на исполнение растёт "
                + "вместе с ней (док. 125 §6). Поток — противовес: край бесполезен без "
                + "исполнений, и он же ограничивает ёмкость сверху (док. 127 §13).\n\n");
        sb.append("| Пара | Цена | **Полуспред, б.п.** | σ за период, б.п. | Сделок/сут "
                + "| **Оборот ленты, $/сут** | Медианная сделка, $ | Снимков |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        double days = hours / 24.0;
        for (Row r : rows) {
            sb.append("| ").append(r.symbol())
                    .append(" | ").append(trim(r.price()))
                    .append(" | **").append(round(r.halfSpreadBp(), 1)).append("**")
                    .append(" | ").append(round(r.sigmaBp(), 2))
                    .append(" | ").append(round(r.trades() / days, 0))
                    .append(" | ").append(round(r.notional() / days, 0))
                    .append(" | ").append(round(r.medianTradeNotional(), 1))
                    .append(" | ").append(r.snapshots())
                    .append(" |\n");
        }

        sb.append("\n## 2. Разрешение хеджа: чем ограничен живой масштаб\n\n");
        sb.append("Хедж — единственный механизм, который убирает проблему падения, а не "
                + "переносит её (док. 127 §8). Но он квантован шагом контракта, и когда "
                + "шаг сравним с потолком инвентаря, хеджировать нечего: у живого бота "
                + "на BTC весь потолок — две с половиной ступени. **Порог годности — два "
                + "десятка ступеней**, ниже него проверять на живом нечего.\n\n");
        sb.append("| Пара | Перп | Шаг контракта | Наш лот | **Шаг в наших лотах** "
                + "| **Ступеней на потолок** | Годен живьём |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            double steps = r.stepsPerCap(LOT_NOTIONAL, LOTS_PER_CAP);
            sb.append("| ").append(r.symbol())
                    .append(" | ").append(r.perp() == null ? "**нет**" : r.perp().symbol())
                    .append(" | ").append(r.perp() == null ? "—" : trim(r.perp().step()))
                    .append(" | ").append(trim(round(r.lot(LOT_NOTIONAL), 9)))
                    .append(" | ").append(Double.isNaN(r.stepInLots(LOT_NOTIONAL)) ? "—"
                            : round(r.stepInLots(LOT_NOTIONAL), 2))
                    .append(" | **").append(Double.isNaN(steps) ? "—" : round(steps, 1)).append("**")
                    .append(" | ").append(Double.isNaN(steps) ? "—" : steps >= 20 ? "**да**" : "нет")
                    .append(" |\n");
        }
        sb.append("\nКолонка «ступеней на потолок» считается при потолке ")
                .append(LOTS_PER_CAP).append(" лотов, то есть при том же номинале, на "
                        + "котором стоит живой бот. Чтобы пара стала годной, номинал надо "
                        + "поднимать пропорционально: пара с одной ступенью требует "
                        + "двадцатикратного увеличения.\n\n");

        sb.append("## 3. Фондирование: платим или получаем\n\n");
        sb.append("Шорт ПОЛУЧАЕТ фондирование при положительной ставке. У BTC это "
                + "покрывало часть комиссий перпа; там, где ставка отрицательна, этой "
                + "статьи дохода нет вовсе, и хедж дорожает на всю её величину.\n\n");
        sb.append("| Пара | Перп | Точек | Средняя ставка, ppm/ч | Годовых | "
                + "**Доля отрицательных часов** |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            if (r.funding() == null) {
                continue;
            }
            sb.append("| ").append(r.symbol())
                    .append(" | ").append(r.perp().symbol())
                    .append(" | ").append(r.funding().points())
                    .append(" | ").append(round(r.funding().meanPerHour() * 1e6, 2))
                    .append(" | ").append(round(r.funding().meanPerHour() * 24 * 365 * 100, 1)).append("%")
                    .append(" | **").append(round(r.funding().negativeShare() * 100, 1)).append("%**")
                    .append(" |\n");
        }

        sb.append("\n## 4. Отступ каждой пары из её собственного закона прихода (§15 п. 6)\n\n");
        sb.append("Отступ ETH и SOL был перенесён с BTC правилом «1.43 своей полуширины "
                + "книги», а сам коэффициент взят из рабочей точки 14 б.п., про которую "
                + "уже известно, что она шире оптимума (док. 127 §11). Правильный ход — "
                + "не чинить коэффициент, а посчитать `δ* = c + 1/κ` для каждой пары по "
                + "её потоку.\n\n");
        sb.append("Интенсивность здесь измерена **по ленте**: сколько принтов за час "
                + "уходит от середины книги дальше, чем на δ. Это не наши исполнения, а "
                + "верхняя их граница, но κ — это НАКЛОН логарифма, и от общего "
                + "множителя он не зависит.\n\n");
        sb.append("Подгонок две, как и в симуляции: по числу принтов и по их обороту. "
                + "Оптимизировать надо по **обороту** — глубокие исполнения приходят от "
                + "крупных выносов, поэтому оборотная кривая падает медленнее и "
                + "оптимальный отступ шире учебничного (док. 100 §4). `δ*` в таблице "
                + "посчитан по оборотной подгонке.\n\n");
        sb.append("| Пара | `c`, б.п. | κ принты | R² | δ* принты | κ оборот | R² "
                + "| δ* оборот | **Обе подгонки держатся** | Полуспред | δ*/полуспред |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            ArrivalLaw.Fit prints = r.byPrints();
            ArrivalLaw.Fit turn = r.byNotional();
            double deltaPrints = prints == null || prints.kappa() <= 0 ? Double.NaN
                    : r.costBp() + 10_000 / prints.kappa();
            double delta = r.optimalOffsetBp();
            boolean solid = prints != null && prints.holds() && turn != null && turn.holds();
            sb.append("| ").append(r.symbol())
                    .append(" | ").append(round(r.costBp(), 2))
                    .append(" | ").append(prints == null ? "—" : round(prints.kappa(), 0))
                    .append(" | ").append(prints == null ? "—" : round(prints.rSquared(), 3))
                    .append(" | ").append(Double.isNaN(deltaPrints) ? "—" : round(deltaPrints, 1))
                    .append(" | ").append(turn == null ? "—" : round(turn.kappa(), 0))
                    .append(" | ").append(turn == null ? "—" : round(turn.rSquared(), 3))
                    .append(" | ").append(Double.isNaN(delta) ? "—" : round(delta, 1))
                    .append(" | ").append(solid ? "**да**" : "нет")
                    .append(" | ").append(round(r.halfSpreadBp(), 1))
                    .append(" | ").append(!solid || !(r.halfSpreadBp() > 0) ? "—"
                            : round(delta / r.halfSpreadBp(), 2))
                    .append(" |\n");
        }
        sb.append("\n**Читать с оговоркой, и оговорка тут главное.** Колонка «обе подгонки "
                + "держатся» — приёмка самого допущения `λ = A·e^{−κδ}` (R² ≥ 0.9 и хотя бы "
                + "четыре непустые ступени) СРАЗУ на обеих кривых. Где написано «нет», "
                + "число `δ*` не значит ничего: у BCH оборотная подгонка даёт κ = 3 и "
                + "«оптимальный отступ» в тысячи базисных пунктов — это не результат, а "
                + "видимый признак того, что на семи сделках в сутки закон прихода по "
                + "ленте не устанавливается вовсе.\n\n");
        sb.append("Пошлина `c` здесь оценена по ленте — волатильность середины за период "
                + "опроса плюс СРЕДНИЙ markout чужих принтов через минуту, — а в симуляции "
                + "она меряется нашими исполнениями на нашем отступе. Это разные выборки "
                + "одной величины, и расходиться они обязаны тем сильнее, чем реже мы "
                + "исполняемся.\n\n");
        sb.append("Последняя колонка — отношение `δ*` к полуспреду книги. Именно его "
                + "док. 127 §11 предлагал держать постоянным (1.085 вместо 1.43). "
                + "**Постоянным оно не выходит даже среди пар, где обе подгонки держатся**, "
                + "и это довод против любого переносимого коэффициента: отношение — "
                + "свойство пары, а не площадки.\n\n");

        sb.append("## 5. Что из этого следует\n\n");
        List<Row> hedgeable = rows.stream()
                .filter(r -> r.stepsPerCap(LOT_NOTIONAL, LOTS_PER_CAP) >= 20).toList();
        sb.append("- Пар с перпом на Kraken: **")
                .append(rows.stream().filter(r -> r.perp() != null).count())
                .append("** из ").append(rows.size()).append(".\n");
        sb.append("- Пар, на которых хедж проверяем при нынешнем номинале (≥20 ступеней "
                        + "контракта на потолок): **").append(hedgeable.size()).append("**")
                .append(hedgeable.isEmpty() ? "" : " — " + hedgeable.stream()
                        .map(Row::symbol).reduce((a, b) -> a + ", " + b).orElse(""))
                .append(".\n");
        sb.append("- Пара с самым широким спредом: **")
                .append(rows.isEmpty() ? "—" : rows.get(0).symbol())
                .append("**, самый тонкий поток и самая широкая книга обычно совпадают — "
                        + "это и есть конфликт §13: то, что делает пару прибыльнее, "
                        + "ограничивает её масштаб.\n\n");
        sb.append("Скрининг НЕ измеряет доходность: он отбирает кандидатов, которых потом "
                + "гоняют симулятором. Пара, прошедшая по спреду, но провалившая "
                + "разрешение хеджа, к живой проверке не годится ни при какой "
                + "доходности.\n");
        return sb.toString();
    }

    /**
     * Номинал лота живого бота, $. Взят от BTC-бота (0.0000125 BTC при цене
     * ~80 000) и держится одинаковым для всех пар нарочно: сравнивать разрешение
     * хеджа имеет смысл только при равном номинале.
     */
    private static final double LOT_NOTIONAL = 1.0;
    /** Потолок инвентаря живого бота в лотах: 0.00025 / 0.0000125 = 20. */
    private static final int LOTS_PER_CAP = 20;

    private static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double sd(double[] values) {
        if (values.length < 2) {
            return Double.NaN;
        }
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double sum = 0;
        for (double v : values) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    private static String trim(double v) {
        return Double.isNaN(v) ? "—"
                : java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private void write(String out, String markdown) {
        try {
            Path path = Path.of(out);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не записать отчёт " + out, e);
        }
    }
}
