package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * ОТБОР ПО ОТСТАВШЕЙ ЦЕНЕ, ПОСЧИТАННЫЙ ПО ЧУЖОЙ СЕРЕДИНЕ. Команда {@code --revx-lead}.
 *
 * <h2>Что это меряет (протокол 160, П1.1)</h2>
 *
 * Док. 156 (задача A54) установил: внешняя площадка идёт впереди Revolut X на
 * 1–4 секунды, и контрагент снимает с нас на этом отставании. Величину тогда
 * посчитали по цене ПОСЛЕДНЕЙ СДЕЛКИ Бинанса, и она вышла 1.2 б.п. — но цена
 * сделки сама шумит на полспреда, то есть связь ЗАНИЖЕНА. Настоящая середина
 * чужой книги пишется с 17.09.2026, и здесь считается по ней.
 *
 * Для каждого нашего исполнения берётся ход чужой середины в окне СТРОГО ДО
 * сделки — {@code [t−4с, t−1с]}, как в 156 §4. Секунда отступа обязательна:
 * окно {@code [t−3, t]} захватывает и время ПОСЛЕ исполнения, и это давало треть
 * эффекта (там же).
 *
 * Знак приводится к «против нас»: наша ПОКУПКА после падения чужой середины и
 * наша ПРОДАЖА после роста — это и есть отбор, обе дают положительную величину.
 *
 * <h2>Безусловный контроль</h2>
 *
 * ⚠️ Без него число бессмысленно. Тот же ход середины считается от КАЖДОЙ
 * секунды окна, а не только от моментов сделок: если рынок в эти сутки шёл вниз,
 * «ход перед покупкой» будет отрицательным и без всякого отбора. Разность
 * «на сделках минус контроль» и есть ответ, и `t` считается по ней.
 *
 * <h2>Две площадки одной командой</h2>
 *
 * Протокол просит повторить счёт по Kraken: если он опережает нас так же, опору
 * можно взять оттуда же, где стоит хедж, и внешняя площадка нужна одна вместо
 * двух. Разбор захвата у площадок разный (у Бинанса {@code "s"/"b"/"a"}, у
 * Kraken {@code product_id/bid/ask}), поэтому формат указывается явно.
 */
@Component
@Lazy
public class LeadCheck {

    private static final Logger log = LoggerFactory.getLogger(LeadCheck.class);

    /** Окно ДО сделки: конец и начало, мс. Секунда отступа — см. javadoc. */
    private static final long LAG_END_MS = 1_000;
    private static final long LAG_START_MS = 4_000;

    /** Ряд середин одной пары: отметки времени и цены, по возрастанию. */
    private record Series(long[] ts, double[] mid) {
        /** Ближайшая середина не позже {@code at}; NaN, если такой нет. */
        double at(long at) {
            int lo = 0;
            int hi = ts.length - 1;
            if (ts.length == 0 || at < ts[0]) {
                return Double.NaN;
            }
            while (lo < hi) {
                int m = (lo + hi + 1) >>> 1;
                if (ts[m] <= at) {
                    lo = m;
                } else {
                    hi = m - 1;
                }
            }
            // ⚠️ Слишком старая точка — не ответ: если захват молчал минуту,
            // «ближайшая слева» будет из другой рыночной обстановки.
            return at - ts[lo] > 30_000 ? Double.NaN : mid[lo];
        }
    }

    /** Наше исполнение: когда, какая пара, в какую сторону. */
    private record Fill(long tsMs, String pair, boolean buy) {
    }

    public void run(String captures, String journals, String fromIso, String toIso, String out) {
        long from = fromIso == null || fromIso.isBlank() ? 0 : Instant.parse(fromIso).toEpochMilli();
        long to = toIso == null || toIso.isBlank() ? Long.MAX_VALUE : Instant.parse(toIso).toEpochMilli();

        List<Fill> fills = readFills(journals, from, to);
        if (fills.isEmpty()) {
            log.warn("исполнений в окне нет — считать нечего");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Отбор по отставшей цене: ход чужой середины ДО нашей сделки\n\n");
        sb.append("окно ").append(Instant.ofEpochMilli(fills.get(0).tsMs()))
                .append(" .. ").append(Instant.ofEpochMilli(fills.get(fills.size() - 1).tsMs()))
                .append(", исполнений ").append(fills.size()).append("\n\n");
        sb.append("Ход считается в окне [t−4с, t−1с] и приводится к знаку «против нас»:\n")
                .append("покупка после падения и продажа после роста дают ПЛЮС.\n\n");

        for (String part : captures.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                log.warn("не разобрал источник «{}»: нужно площадка=путь", part);
                continue;
            }
            String venue = kv[0].trim().toLowerCase(Locale.ROOT);
            Map<String, Series> series = readCapture(venue, kv[1].trim(), from, to);
            if (series.isEmpty()) {
                log.warn("{}: захват пуст", venue);
                continue;
            }
            sb.append(report(venue, series, fills));
        }

        String text = sb.toString();
        if (out == null || out.isBlank()) {
            log.info("\n{}", text);
        } else {
            try {
                Path p = Path.of(out);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, text, StandardCharsets.UTF_8);
                log.info("отчёт записан: {}", p.toAbsolutePath());
            } catch (IOException e) {
                log.warn("не записать {}: {}", out, e.toString());
                log.info("\n{}", text);
            }
        }
    }

    /**
     * Счёт по одной площадке: отбор на сделках против безусловного контроля.
     */
    private String report(String venue, Map<String, Series> series, List<Fill> fills) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(venue.toUpperCase(Locale.ROOT)).append("\n\n");
        sb.append("| пара | сделок | отбор на сделках | контроль | **разность** | СКО | **t** |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");

        double allSum = 0;
        double allSumSq = 0;
        int allN = 0;
        double allControl = 0;
        int allControlN = 0;

        for (Map.Entry<String, Series> e : new TreeMap<>(series).entrySet()) {
            String pair = e.getKey();
            Series s = e.getValue();
            List<Double> onFills = new ArrayList<>();
            for (Fill f : fills) {
                if (!f.pair().equals(pair)) {
                    continue;
                }
                double move = moveBp(s, f.tsMs());
                if (Double.isNaN(move)) {
                    continue;
                }
                onFills.add(f.buy() ? -move : move);
            }
            // Безусловный контроль: тот же ход от КАЖДОЙ секунды ряда. Знак
            // здесь не приводится — сторон у контроля нет, и его среднее
            // показывает, сколько «отбора» даёт сам дрейф окна.
            double cSum = 0;
            int cN = 0;
            for (int i = 0; i < s.ts().length; i += 200) {          // каждая ~200-я точка
                double move = moveBp(s, s.ts()[i]);
                if (!Double.isNaN(move)) {
                    cSum += move;
                    cN++;
                }
            }
            if (onFills.isEmpty()) {
                continue;
            }
            double mean = onFills.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = 0;
            for (double v : onFills) {
                var += (v - mean) * (v - mean);
            }
            double sd = onFills.size() > 1 ? Math.sqrt(var / (onFills.size() - 1)) : 0;
            double control = cN > 0 ? cSum / cN : 0;
            double diff = mean - control;
            double t = sd > 0 ? diff / (sd / Math.sqrt(onFills.size())) : 0;
            sb.append("| ").append(pair)
                    .append(" | ").append(onFills.size())
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f", mean))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f", control))
                    .append(" | **").append(String.format(Locale.ROOT, "%+.2f", diff))
                    .append("** | ").append(String.format(Locale.ROOT, "%.2f", sd))
                    .append(" | **").append(String.format(Locale.ROOT, "%+.2f", t))
                    .append("** |\n");
            for (double v : onFills) {
                allSum += v;
                allSumSq += v * v;
                allN++;
            }
            allControl += control;
            allControlN++;
        }

        if (allN > 1) {
            double mean = allSum / allN;
            double sd = Math.sqrt((allSumSq - allN * mean * mean) / (allN - 1));
            double control = allControlN > 0 ? allControl / allControlN : 0;
            double diff = mean - control;
            double t = sd > 0 ? diff / (sd / Math.sqrt(allN)) : 0;
            sb.append("| **все** | **").append(allN)
                    .append("** | ").append(String.format(Locale.ROOT, "%+.2f", mean))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.2f", control))
                    .append(" | **").append(String.format(Locale.ROOT, "%+.2f", diff))
                    .append("** | ").append(String.format(Locale.ROOT, "%.2f", sd))
                    .append(" | **").append(String.format(Locale.ROOT, "%+.2f", t))
                    .append("** |\n");
        }
        sb.append("\nПорог протокола: ≥ 2.5 б.п. подтверждает (закрытие покрывает дефицит),\n")
                .append("≤ 1.5 б.п. опровергает (скорость не ответ).\n\n");
        return sb.toString();
    }

    /** Ход середины в окне [t−4с, t−1с], б.п. NaN, если данных нет. */
    private double moveBp(Series s, long tsMs) {
        double before = s.at(tsMs - LAG_START_MS);
        double after = s.at(tsMs - LAG_END_MS);
        if (Double.isNaN(before) || Double.isNaN(after) || !(before > 0)) {
            return Double.NaN;
        }
        return 1e4 * (after - before) / before;
    }

    /**
     * Захват площадки: путь к файлу или каталогу (.jsonl и .jsonl.gz).
     *
     * ⚠️ Отметка времени берётся НАША (первое поле строки), а не биржевая, даже
     * когда биржа свою присылает: мерим момент, когда МЫ БЫ узнали. Иначе
     * измеряется опережение чужих часов, а не наше отставание.
     */
    private Map<String, Series> readCapture(String venue, String path, long from, long to) {
        Map<String, List<long[]>> raw = new TreeMap<>();     // пара -> (ts, цена*1e8)
        List<Path> files = new ArrayList<>();
        try {
            Path p = Path.of(path);
            if (Files.isDirectory(p)) {
                try (var stream = Files.list(p)) {
                    stream.filter(f -> f.getFileName().toString().contains(".jsonl"))
                            .sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        } catch (IOException e) {
            log.warn("не прочитать {}: {}", path, e.toString());
            return Map.of();
        }
        for (Path f : files) {
            readFile(venue, f, from, to, raw);
        }
        Map<String, Series> out = new TreeMap<>();
        for (Map.Entry<String, List<long[]>> e : raw.entrySet()) {
            List<long[]> rows = e.getValue();
            rows.sort((x, y) -> Long.compare(x[0], y[0]));
            long[] ts = new long[rows.size()];
            double[] mid = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                ts[i] = rows.get(i)[0];
                mid[i] = rows.get(i)[1] / 1e8;
            }
            out.put(e.getKey(), new Series(ts, mid));
        }
        out.forEach((k, v) -> log.info("{}: {} — точек {}", venue, k, v.ts().length));
        return out;
    }

    private void readFile(String venue, Path f, long from, long to,
                          Map<String, List<long[]>> raw) {
        boolean gz = f.getFileName().toString().endsWith(".gz");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                gz ? new GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f),
                StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) {
                    continue;
                }
                long ts;
                try {
                    ts = Long.parseLong(line.substring(0, sp));
                } catch (NumberFormatException ignore) {
                    continue;
                }
                if (ts < from || ts > to) {
                    continue;
                }
                String sym = field(line, venue.startsWith("kr") ? "\"product_id\":\"" : "\"s\":\"");
                if (sym == null) {
                    continue;
                }
                double bid = number(line, venue.startsWith("kr") ? "\"bid\":" : "\"b\":\"");
                double ask = number(line, venue.startsWith("kr") ? "\"ask\":" : "\"a\":\"");
                if (!(bid > 0) || !(ask > 0)) {
                    continue;
                }
                raw.computeIfAbsent(pairOf(sym), k -> new ArrayList<>())
                        .add(new long[]{ts, Math.round((bid + ask) / 2 * 1e8)});
            }
        } catch (IOException e) {
            log.warn("не прочитать {}: {}", f, e.toString());
        }
    }

    /** Символ площадки → наша пара: BTCUSDC и PF_XBTUSD оба дают BTC. */
    private static String pairOf(String symbol) {
        String s = symbol.toUpperCase(Locale.ROOT);
        if (s.contains("XBT") || s.contains("BTC")) {
            return "BTC";
        }
        if (s.contains("ETH")) {
            return "ETH";
        }
        if (s.contains("SOL")) {
            return "SOL";
        }
        return s;
    }

    private static String field(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    private static double number(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return Double.NaN;
        }
        int start = i + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) {
            end++;
        }
        try {
            return Double.parseDouble(line.substring(start, end));
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /**
     * Наши исполнения из журналов. Передачи между ботами ({@code handover})
     * пропускаются: это не сделка с рынком, и отбора в ней нет по построению.
     */
    private List<Fill> readFills(String journals, long from, long to) {
        List<Fill> out = new ArrayList<>();
        for (String part : journals.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String pair = pairOf(kv[0].trim());
            for (String path : kv[1].split("\\+")) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:"
                        + Path.of(path.trim()).toAbsolutePath() + "?mode=ro");
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT ts_ms, side FROM exec_fill WHERE ts_ms >= ? AND ts_ms < ?"
                                     + " AND (status IS NULL OR status <> 'handover') ORDER BY ts_ms")) {
                    ps.setLong(1, from);
                    ps.setLong(2, to == Long.MAX_VALUE ? Long.MAX_VALUE : to);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Fill(rs.getLong(1), pair,
                                    "BUY".equalsIgnoreCase(rs.getString(2))));
                        }
                    }
                } catch (Exception e) {
                    log.warn("журнал {} не прочитан: {}", path, e.toString());
                }
            }
        }
        out.sort((x, y) -> Long.compare(x.tsMs(), y.tsMs()));
        return out;
    }
}
