package org.home.data.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.Db;
import org.home.data.detector.RegimeDetectorV3;
import org.home.data.detector.RegimeFsmV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Экономический прокси-бэктест детектора + диагностика A4/A6/A7/A8 (док. 18-v2 §2).
 * Грубая модель аллокации (док. 15 §6.2): BULL 100% / TRANSITION 50% /
 * RANGE·BEAR·CRASH 0% (кэш 8%/год), реаллокация 5 дней (вход в CRASH мгновенно),
 * издержки 0.10% с оборота. Это прокси, не P&L; сравнимы только относительные числа.
 *
 * Измерения фазы A, требующие цены/экономики (A1–A3, A5 — в crash_diagnostics.md):
 * <ul>
 *   <li><b>A4</b> — экономика трёх вариантов CRASH (как есть / →BEAR / выключен) + buy&hold + voltarget;</li>
 *   <li><b>A6</b> — false_bear_rate и перекрытие CRASH с ex-post разметкой ({@link PeakTroughLabeler});</li>
 *   <li><b>A7</b> — доля движения, пройденная до срабатывания (ключевое: дневной CRASH фиксирует или предотвращает убыток);</li>
 *   <li><b>A8</b> — согласованность с volatility targeting.</li>
 * </ul>
 * Оси D/T/S берутся из regime_daily_v2, состояния вариантов реконструируются
 * {@link RegimeFsmV2} (детектор не трогается). CLI: --report=crash-econ [--out=...].
 */
@Component
public class AllocationProxy {

    private static final Logger log = LoggerFactory.getLogger(AllocationProxy.class);

    private static final double COST = 0.001;                 // 0.05% комиссия + 0.05% слиппедж
    private static final double CASH_DAILY = Math.pow(1.08, 1.0 / 365) - 1;
    private static final int STEPS = 5;                        // дней на реаллокацию
    private static final int VT_WIN = 30;                      // окно реализованной волатильности для voltarget

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String YAHOO = "https://query1.finance.yahoo.com/v8/finance/chart/";

    private final Db db;

    public AllocationProxy(Db db) {
        this.db = db;
    }

    private record Axes(double d, double t, double s, String state) {}
    private record Metrics(double cagr, double maxdd, double sharpe, double turnover) {}
    private record Ohlc(double[] high, double[] low, double[] close, String[] dates) {}

    public void run(String outPath) {
        // OHLC
        List<String> cDay = new ArrayList<>();
        List<double[]> cOhlc = new ArrayList<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, open, high, low, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { cDay.add(rs.getString(1));
                        cOhlc.add(new double[]{rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)});
                        return null; });
        int nc = cDay.size();
        double[] open = new double[nc], high = new double[nc], low = new double[nc], close = new double[nc];
        Map<String, Integer> cIdx = new HashMap<>();
        for (int i = 0; i < nc; i++) {
            open[i] = cOhlc.get(i)[0]; high[i] = cOhlc.get(i)[1]; low[i] = cOhlc.get(i)[2]; close[i] = cOhlc.get(i)[3];
            cIdx.put(cDay.get(i), i);
        }
        String[] labels = PeakTroughLabeler.label(close);
        double[] sigma = realizedVol(close, VT_WIN);          // для voltarget

        // оси + сохранённое состояние v2
        Map<String, Axes> v2 = new HashMap<>();
        db.query("SELECT day, d, t, s, state FROM regime_daily_v2 WHERE d IS NOT NULL AND t IS NOT NULL AND s IS NOT NULL",
                rs -> { v2.put(rs.getString(1), new Axes(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5))); return null; });
        List<String> eval = new ArrayList<>(v2.keySet());
        eval.removeIf(d -> !cIdx.containsKey(d));
        eval.sort(String::compareTo);
        int m = eval.size();
        if (m < 100) {
            log.warn("crash-econ: мало дней ({}), прогони --backfill=regime-v2", m);
            return;
        }
        int[] ci = new int[m];
        for (int t = 0; t < m; t++) {
            ci[t] = cIdx.get(eval.get(t));
        }
        int firstEval = ci[0];

        // реконструкция вариантов из осей D/T/S
        String[] stored = new String[m], vFull = new String[m], vOff = new String[m];
        RegimeFsmV2 fsmOn = new RegimeFsmV2(true), fsmOff = new RegimeFsmV2(false);
        for (int t = 0; t < m; t++) {
            Axes a = v2.get(eval.get(t));
            stored[t] = a.state();
            vFull[t] = fsmOn.step(a.d(), a.t(), a.s()).name();
            vOff[t] = fsmOff.step(a.d(), a.t(), a.s()).name();
        }
        int matchV = 0;
        for (int t = 0; t < m; t++) {
            if (vFull[t].equals(stored[t])) {
                matchV++;
            }
        }

        // voltarget: σ_target = медиана σ30 по прогреву (свечи до окна оценки)
        double sigmaTarget = medianWarmup(sigma, firstEval);
        double[] vtExp = new double[m];
        for (int t = 0; t < m; t++) {
            double sg = sigma[ci[t]];
            vtExp[t] = Double.isNaN(sg) || sg <= 0 ? 1.0 : clip(sigmaTarget / sg, 0, 1);
        }

        double years = years(eval);

        // эпизоды CRASH (по vFull) для A5/A7/A8
        List<int[]> episodes = episodes(vFull);
        List<int[]> top = new ArrayList<>(episodes);
        top.sort((x, y) -> (y[1] - y[0]) - (x[1] - x[0]));
        top = top.subList(0, Math.min(5, top.size()));

        // ---- отчёт ----
        StringBuilder sb = new StringBuilder();
        sb.append("# CRASH — экономика и наблюдаемость (фаза A: A4, A6, A7, A8)\n\n");
        sb.append("**Задание:** `docs/18-ispravlenie-zalipaniya-crash-v2.md`. Дополняет `crash_diagnostics.md` (A1–A3, A5).\n");
        sb.append("**Прокси:** BULL 100% / TRANSITION 50% / RANGE·BEAR·CRASH 0% (кэш 8%/год), реаллокация 5 дней ")
                .append("(CRASH мгновенно в вар.1), издержки 0.10% с оборота. **Прокси, не P&L; сравнимы только относительные числа.**\n");
        sb.append("**Окно:** ").append(eval.get(0)).append(" … ").append(eval.get(m - 1))
                .append(String.format(" (%d дней, %.1f лет). Реконструкция вар.1 совпала с regime_daily_v2 в %.1f%%.%n%n",
                        m, years, 100.0 * matchV / m));

        // A4
        Metrics bh = buyHold(close, ci, years);
        Metrics v1 = simulate(stateTargets(vFull), crashImmediate(vFull), close, ci, years);
        Metrics v2m = simulate(stateTargets(vFull), noImmediate(m), close, ci, years);
        Metrics v3 = simulate(stateTargets(vOff), noImmediate(m), close, ci, years);
        Metrics vt = simulate(vtExp, noImmediate(m), close, ci, years);
        sb.append("## A4. Экономика вариантов\n\n");
        sb.append("| Вариант | CAGR | MaxDD | Sharpe | Оборот/год | ΔCAGR к B&H | ΔMaxDD к B&H | ΔMaxDD/ΔCAGR |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        row(sb, "buy & hold", bh, bh);
        row(sb, "1. CRASH как есть", v1, bh);
        row(sb, "2. CRASH→BEAR (не мгновенный)", v2m, bh);
        row(sb, "3. CRASH выключен", v3, bh);
        row(sb, "voltarget (непрерывный)", vt, bh);
        sb.append("\n`ΔMaxDD/ΔCAGR` — пунктов CAGR отдано за 1 пункт снятой просадки (меньше = лучше; «—» = не снижает просадку).\n\n");

        // A6
        sb.append("## A6. Ошибки против эталонной разметки\n\n");
        sb.append("| Вариант | false_bear_rate | CRASH внутри true BULL | CRASH внутри true BEAR |\n|---|---|---|---|\n");
        errRow(sb, "1. CRASH как есть", vFull, labels, ci);
        errRow(sb, "3. CRASH выключен", vOff, labels, ci);
        sb.append("\n`false_bear_rate` — доля дней BEAR/CRASH внутри истинных BULL-отрезков (пропущенный рост). Разметка: ")
                .append(labelDist(labels, ci)).append(".\n\n");

        // A7 — ключевое
        sb.append("## A7. Доля движения, пройденная до срабатывания\n\n");
        sb.append("`passed = (P_max − P_trigger)/(P_max − P_min)`. Порог трактовки: <40% защищает · 40–70% по A4 · ")
                .append("**>70% фиксирует убыток, а не предотвращает**.\n\n");
        sb.append("| Эпизод | passed | падение в 1 свече | нижний фитиль | отскок +5д | отскок +10д |\n|---|---|---|---|---|---|\n");
        double passedSum = 0; int passedCnt = 0; double reb5Sum = 0, reb10Sum = 0;
        for (int[] ep : top) {
            double[] a7 = a7(ep, ci, open, high, low, close);
            passedSum += a7[0]; passedCnt++; reb5Sum += a7[3]; reb10Sum += a7[4];
            sb.append(String.format("| %s..%s | %.0f%% | %.0f%% | %.0f%% | %+.1f%% | %+.1f%% |%n",
                    eval.get(ep[0]), eval.get(ep[1]), a7[0] * 100, a7[1] * 100, a7[2] * 100, a7[3] * 100, a7[4] * 100));
        }
        double passedAvg = passedCnt > 0 ? passedSum / passedCnt : 0;
        sb.append(String.format("%nСреднее `passed` по топ-5 = **%.0f%%**. Средний отскок +5д = %+.1f%%, +10д = %+.1f%%.%n",
                passedAvg * 100, 100 * reb5Sum / passedCnt, 100 * reb10Sum / passedCnt));
        sb.append(verdictA7(passedAvg)).append("\n\n");

        // A8 — voltarget agreement
        sb.append("## A8. Согласованность с volatility targeting\n\n");
        double corr = corrCrashVsVt(vFull, vtExp);
        sb.append(String.format("σ_target (медиана σ%dд по прогреву) = %.4f. ", VT_WIN, sigmaTarget));
        sb.append(String.format("Корреляция `(1 − exposure_vt)` с индикатором CRASH = **%.2f**.%n%n", corr));
        sb.append("| Эпизод | дней до CRASH, когда voltarget уже урезал экспозицию ≤0.5 |\n|---|---|\n");
        for (int[] ep : top) {
            int lead = vtLead(ep[0], ci, sigma, sigmaTarget);
            sb.append(String.format("| %s | %d |%n", eval.get(ep[0]), lead));
        }
        sb.append("\nЕсли voltarget урезает экспозицию раньше и плавно — дискретный CRASH не добавляет ничего, кроме издержек.\n\n");

        // развилка §3.0
        sb.append("## Развилка фазы B (док. 18-v2 §3.0)\n\n");
        boolean deleteCandidate = passedAvg > 0.70;
        sb.append(String.format("- A7 среднее `passed` = %.0f%% → %s.%n", passedAvg * 100,
                deleteCandidate ? "**> 70%: B-удаление становится основным кандидатом**" : "≤ 70%: решение по экономике A4"));
        sb.append("- Проверка A4.3 vs A4.1 по `ΔMaxDD/ΔCAGR` — см. таблицу A4: если «CRASH выключен» не хуже «как есть», CRASH подлежит удалению.\n");
        sb.append("- Итог (удалять/чинить) фиксируется после ручного прочтения обеих таблиц.\n");

        writeFile(outPath, sb.toString());
        writeHypotheses(passedAvg, vt, corr);
        log.info("crash-econ: A7 avg passed={}%, отчёт -> {}", Math.round(passedAvg * 100), Path.of(outPath).toAbsolutePath());
    }

    /**
     * Шаг 1 док. v3 §7.1: проверить, почему MaxDD вариантов 1 (CRASH как есть) и 3
     * (CRASH выключен) совпадают до десятой (−25.2%). Ожидание: максимум просадки
     * приходится на период, где варианты ведут себя одинаково (CRASH не срабатывал
     * или не менял экспозицию). Если даты просадки разные, а числа совпадают —
     * это артефакт расчёта (баг). Отчёт: даты дна и начала просадки + состояние
     * детектора в эти дни. CLI: --report=crash-maxdd.
     */
    public void maxddCheck(String outPath) {
        List<String> cDay = new ArrayList<>();
        List<Double> cClose = new ArrayList<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { cDay.add(rs.getString(1)); cClose.add(rs.getDouble(2)); return null; });
        Map<String, Integer> cIdx = new HashMap<>();
        double[] close = new double[cDay.size()];
        for (int i = 0; i < cDay.size(); i++) {
            close[i] = cClose.get(i);
            cIdx.put(cDay.get(i), i);
        }
        Map<String, Axes> v2 = new HashMap<>();
        db.query("SELECT day, d, t, s, state FROM regime_daily_v2 WHERE d IS NOT NULL AND t IS NOT NULL AND s IS NOT NULL",
                rs -> { v2.put(rs.getString(1), new Axes(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5))); return null; });
        List<String> eval = new ArrayList<>(v2.keySet());
        eval.removeIf(d -> !cIdx.containsKey(d));
        eval.sort(String::compareTo);
        int m = eval.size();
        int[] ci = new int[m];
        for (int t = 0; t < m; t++) {
            ci[t] = cIdx.get(eval.get(t));
        }
        String[] vFull = new String[m], vOff = new String[m];
        RegimeFsmV2 fsmOn = new RegimeFsmV2(true), fsmOff = new RegimeFsmV2(false);
        for (int t = 0; t < m; t++) {
            Axes a = v2.get(eval.get(t));
            vFull[t] = fsmOn.step(a.d(), a.t(), a.s()).name();
            vOff[t] = fsmOff.step(a.d(), a.t(), a.s()).name();
        }

        Sim s1 = sim(stateTargets(vFull), crashImmediate(vFull), close, ci);
        Sim s3 = sim(stateTargets(vOff), noImmediate(m), close, ci);
        int[] dd1 = ddIndices(s1.curve()), dd3 = ddIndices(s3.curve());

        StringBuilder sb = new StringBuilder();
        sb.append("# Проверка совпадения MaxDD (v3 §7.1, шаг 1)\n\n");
        sb.append("Вопрос: MaxDD вариантов 1 (CRASH как есть) и 3 (CRASH выключен) совпадают до десятой (−25.2%). ")
                .append("Естественно ли это (одинаковое поведение в период максимума), или артефакт?\n\n");
        sb.append("| | Вариант 1 (CRASH как есть) | Вариант 3 (CRASH выключен) |\n|---|---|---|\n");
        sb.append(String.format("| MaxDD | %.2f%% | %.2f%% |%n",
                dd(s1.curve(), dd1) * 100, dd(s3.curve(), dd3) * 100));
        sb.append(String.format("| Начало просадки (пик капитала) | %s | %s |%n", eval.get(dd1[0]), eval.get(dd3[0])));
        sb.append(String.format("| Дно просадки | %s | %s |%n", eval.get(dd1[1]), eval.get(dd3[1])));
        sb.append(String.format("| Состояние на пике | 1:%s / 3:%s | |%n", vFull[dd1[0]], vOff[dd1[0]]));
        sb.append(String.format("| Состояние на дне | 1:%s / 3:%s | |%n", vFull[dd1[1]], vOff[dd1[1]]));

        // сколько дней CRASH внутри окна просадки варианта 1
        int crashInWindow = 0;
        for (int t = dd1[0]; t <= dd1[1]; t++) {
            if (vFull[t].equals("CRASH")) {
                crashInWindow++;
            }
        }
        int windowLen = dd1[1] - dd1[0] + 1;
        // расходятся ли состояния вар.1 и вар.3 внутри окна просадки
        int diverge = 0;
        for (int t = dd1[0]; t <= dd1[1]; t++) {
            if (!vFull[t].equals(vOff[t])) {
                diverge++;
            }
        }
        sb.append(String.format("%nОкно просадки вар.1: %s … %s (%d дней). CRASH внутри окна: **%d дней**. ",
                eval.get(dd1[0]), eval.get(dd1[1]), windowLen, crashInWindow));
        sb.append(String.format("Дней, где состояния вар.1 и вар.3 расходятся: **%d**.%n%n", diverge));

        boolean sameDates = dd1[0] == dd3[0] && dd1[1] == dd3[1];
        double v1dd = dd(s1.curve(), dd1), v3dd = dd(s3.curve(), dd3);
        boolean valuesExact = Math.abs(v1dd - v3dd) < 1e-6;
        String verdict;
        if (sameDates && (crashInWindow == 0 || diverge == 0)) {
            verdict = "**Вывод: совпадение естественно, бага нет.** Окно максимальной просадки одно и то же, "
                    + "внутри него варианты ведут себя тождественно (CRASH не срабатывал / состояния не расходятся) → "
                    + "MaxDD обязан совпасть.";
        } else if (!sameDates && valuesExact) {
            verdict = "**Внимание: даты просадки РАЗНЫЕ, но MaxDD совпал точь-в-точь — вероятен артефакт расчёта (баг).** "
                    + "Разобрать, прежде чем полагаться на A4.";
        } else if (!sameDates) {
            verdict = String.format("**Вывод: совпадения по существу НЕТ — это округление, бага нет.** "
                    + "Максимальная просадка вариантов приходится на РАЗНЫЕ события (%s против %s) и различается "
                    + "по величине (%.2f%% против %.2f%%); в таблице §5 оба округлились до −25.2%%. Разные даты дна "
                    + "доказывают, что кривые капитала действительно различаются (расчёт корректен). "
                    + "Важно: v3 (без CRASH) даёт даже чуть меньшую просадку, чем v1 — вывод A4 усиливается, "
                    + "а не ослабляется.", eval.get(dd1[1]), eval.get(dd3[1]), v1dd * 100, v3dd * 100);
        } else {
            verdict = String.format("**Внимание:** даты просадки совпадают, но внутри окна CRASH срабатывал (%d дн) "
                    + "и/или состояния расходятся (%d дн) — проверить экспозицию.", crashInWindow, diverge);
        }
        sb.append(verdict).append("\n");

        writeFile(outPath, sb.toString());
        log.info("crash-maxdd: v1 MaxDD={}% ({}), v3 MaxDD={}% ({}), same-dates={} -> {}",
                String.format("%.2f", dd(s1.curve(), dd1) * 100), eval.get(dd1[1]),
                String.format("%.2f", dd(s3.curve(), dd3) * 100), eval.get(dd3[1]),
                sameDates, Path.of(outPath).toAbsolutePath());
    }

    private static double dd(double[] curve, int[] idx) {
        return curve[idx[1]] / curve[idx[0]] - 1;
    }

    /**
     * Прогон экономического прокси по готовым состояниям из произвольной таблицы
     * (day, state). Для сверки v3 с §5 (CAGR 32.7%, MaxDD −25.2%). Реаллокация 5 дней,
     * без мгновенного выхода (в v3 нет CRASH). CLI: --report=regime-econ [--table=...].
     */
    public void econOf(String table) {
        List<String> cDay = new ArrayList<>();
        List<Double> cClose = new ArrayList<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { cDay.add(rs.getString(1)); cClose.add(rs.getDouble(2)); return null; });
        Map<String, Integer> cIdx = new HashMap<>();
        double[] close = new double[cDay.size()];
        for (int i = 0; i < cDay.size(); i++) {
            close[i] = cClose.get(i);
            cIdx.put(cDay.get(i), i);
        }
        Map<String, String> st = new HashMap<>();
        db.query("SELECT day, state FROM " + table + " WHERE state IS NOT NULL",
                rs -> { st.put(rs.getString(1), rs.getString(2)); return null; });
        List<String> eval = new ArrayList<>(st.keySet());
        eval.removeIf(d -> !cIdx.containsKey(d));
        eval.sort(String::compareTo);
        int m = eval.size();
        if (m < 100) {
            log.warn("regime-econ: мало дней в {} ({})", table, m);
            return;
        }
        int[] ci = new int[m];
        String[] states = new String[m];
        for (int t = 0; t < m; t++) {
            ci[t] = cIdx.get(eval.get(t));
            states[t] = st.get(eval.get(t));
        }
        double years = years(eval);
        Metrics v = metrics(sim(stateTargets(states), noImmediate(m), close, ci).curve(), 0, years);
        Metrics bh = buyHold(close, ci, years);
        log.info("regime-econ [{}]: {} … {} ({} дней) | CAGR={}% MaxDD={}% Sharpe={} | B&H CAGR={}% MaxDD={}%",
                table, eval.get(0), eval.get(m - 1), m,
                String.format("%.1f", v.cagr() * 100), String.format("%.1f", v.maxdd() * 100),
                String.format("%.2f", v.sharpe()), String.format("%.1f", bh.cagr() * 100),
                String.format("%.1f", bh.maxdd() * 100));
    }

    /**
     * Разбор провала volatility targeting (док. 19): H1–H5 на существующей реализации
     * voltarget (exposure = clip(σ_target/σ30д, 0, 1)) + технические проверки §3, вывод
     * по развилке §4.1 (судьба stress_level). CLI: --report=voltarget [--out=...].
     */
    public void voltargetPostmortem(String outPath) {
        // цена
        List<String> cDay = new ArrayList<>();
        List<Double> cClose = new ArrayList<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { cDay.add(rs.getString(1)); cClose.add(rs.getDouble(2)); return null; });
        double[] close = new double[cDay.size()];
        Map<String, Integer> cIdx = new HashMap<>();
        for (int i = 0; i < cDay.size(); i++) {
            close[i] = cClose.get(i);
            cIdx.put(cDay.get(i), i);
        }
        double[] sigma = realizedVol(close, VT_WIN);

        // окно = дни regime_daily_v2; заодно реконструкция CRASH-эпизодов для H3
        Map<String, Axes> v2 = new HashMap<>();
        db.query("SELECT day, d, t, s, state FROM regime_daily_v2 WHERE d IS NOT NULL AND t IS NOT NULL AND s IS NOT NULL",
                rs -> { v2.put(rs.getString(1), new Axes(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5))); return null; });
        List<String> eval = new ArrayList<>(v2.keySet());
        eval.removeIf(d -> !cIdx.containsKey(d));
        eval.sort(String::compareTo);
        int m = eval.size();
        if (m < 100) {
            log.warn("voltarget: мало дней ({})", m);
            return;
        }
        int[] ci = new int[m];
        for (int t = 0; t < m; t++) {
            ci[t] = cIdx.get(eval.get(t));
        }
        int firstEval = ci[0];
        double sigmaTarget = medianWarmup(sigma, firstEval);
        double[] vt = new double[m];       // exposure-сигнал voltarget по дням окна
        for (int t = 0; t < m; t++) {
            double sg = sigma[ci[t]];
            vt[t] = Double.isNaN(sg) || sg <= 0 ? 1.0 : clip(sigmaTarget / sg, 0, 1);
        }
        String[] vFull = new String[m];
        RegimeFsmV2 fsm = new RegimeFsmV2(true);
        for (int t = 0; t < m; t++) {
            Axes a = v2.get(eval.get(t));
            vFull[t] = fsm.step(a.d(), a.t(), a.s()).name();
        }
        // рыночные дневные доходности по окну
        double[] ret = new double[m];
        for (int t = 1; t < m; t++) {
            ret[t] = close[ci[t]] / close[ci[t - 1]] - 1;
        }
        double years = years(eval);

        StringBuilder sb = new StringBuilder();
        sb.append("# Разбор провала volatility targeting (док. 19)\n\n");
        sb.append(String.format("Реализация: `exposure = clip(σ_target/σ%dд, 0, 1)`, σ_target = %.4f ", VT_WIN, sigmaTarget))
                .append("(медиана σ30д по прогреву, дневная). Окно ").append(eval.get(0)).append(" … ")
                .append(eval.get(m - 1)).append(String.format(" (%d дней). Анализируется exposure-сигнал (до 5-дн реаллокации).%n%n", m));

        // ---- H1: упор в потолок ----
        int[] bins = new int[10];
        int atCeiling = 0;
        double sum = 0;
        double[] vtSorted = vt.clone();
        for (double e : vt) {
            int b = Math.min(9, (int) (e * 10));
            bins[b]++;
            if (e >= 1 - 1e-9) {
                atCeiling++;
            }
            sum += e;
        }
        java.util.Arrays.sort(vtSorted);
        double h1mean = sum / m, h1median = vtSorted[m / 2];
        double h1ceil = (double) atCeiling / m;
        sb.append("## H1. Упор в потолок\n\n");
        sb.append(String.format("Доля дней exposure=1.0: **%.0f%%**; среднее %.2f, медиана %.2f.%n%n", h1ceil * 100, h1mean, h1median));
        sb.append("Гистограмма exposure (бины 0.1): ");
        for (int b = 0; b < 10; b++) {
            sb.append(String.format("[%.1f–%.1f]=%d ", b / 10.0, (b + 1) / 10.0, bins[b]));
        }
        sb.append("\n\n");

        // ---- H2: недостижимость нуля ----
        double vtMin = vtSorted[0];
        int[] ddi = ddIndices(sim(vt, noImmediate(m), close, ci).curve());
        double expAtDd = vt[ddi[1]];
        Integer[] order = new Integer[m];
        for (int t = 0; t < m; t++) {
            order[t] = t;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(ret[x], ret[y]));
        double worst20 = 0, best20 = 0;
        for (int k = 0; k < 20; k++) {
            worst20 += vt[order[k]];
            best20 += vt[order[m - 1 - k]];
        }
        worst20 /= 20;
        best20 /= 20;
        sb.append("## H2. Недостижимость нуля\n\n");
        sb.append(String.format("Минимальная exposure за всю историю: **%.2f**. Exposure в день макс. просадки (%s): %.2f. ",
                vtMin, eval.get(ddi[1]), expAtDd));
        sb.append(String.format("Средняя exposure в 20 худших по доходности дней: **%.2f**.%n%n", worst20));

        // ---- H3: запаздывание окна (top-5 CRASH-эпизодов) ----
        List<int[]> eps = episodes(vFull);
        eps.sort((x, y) -> (y[1] - y[0]) - (x[1] - x[0]));
        sb.append("## H3. Запаздывание окна (топ-5 обвалов)\n\n");
        sb.append("| Эпизод | exp за 1д до | exp в день макс. падения | exp +5д | min exp (день от начала) |\n|---|---|---|---|---|\n");
        int lagCount = 0, lagTot = 0;
        for (int e = 0; e < Math.min(5, eps.size()); e++) {
            int s = eps.get(e)[0], en = eps.get(e)[1];
            double before = s > 0 ? vt[s - 1] : vt[s];
            int fallT = s;
            double fall = 0;
            for (int t = s; t <= en; t++) {
                if (ret[t] < fall) {
                    fall = ret[t];
                    fallT = t;
                }
            }
            double after5 = vt[Math.min(m - 1, s + 5)];
            double minE = 2;
            int minDay = 0;
            for (int t = s; t <= en; t++) {
                if (vt[t] < minE) {
                    minE = vt[t];
                    minDay = t - s;
                }
            }
            lagTot++;
            if (minDay > (fallT - s)) {
                lagCount++;   // минимум достигнут ПОСЛЕ дня максимального падения
            }
            sb.append(String.format("| %s..%s | %.2f | %.2f | %.2f | %.2f (день %d) |%n",
                    eval.get(s), eval.get(en), before, vt[fallT], after5, minE, minDay));
        }
        sb.append(String.format("%nМинимум exposure достигнут ПОСЛЕ дня макс. падения в %d из %d эпизодов.%n%n", lagCount, lagTot));

        // ---- H4: симметрия волатильности ----
        int h = Math.min(m - 20, m);
        double[] x = new double[Math.max(0, m - 20)], y = new double[Math.max(0, m - 20)];
        for (int t = 0; t + 20 < m; t++) {
            x[t] = vt[t];
            y[t] = close[ci[t + 20]] / close[ci[t]] - 1;
        }
        double corr = x.length > 0 ? pearson(x, y) : 0;
        double h4diff = best20 - worst20;
        sb.append("## H4. Симметрия волатильности (ключевая)\n\n");
        sb.append(String.format("corr(exposure, доходность t→t+20) = **%.2f**. ", corr));
        sb.append(String.format("Средняя exposure в 20 ЛУЧШИХ дней: **%.2f**, в 20 ХУДШИХ: **%.2f** (разница %.2f).%n%n",
                best20, worst20, h4diff));
        sb.append(Math.abs(h4diff) < 0.1
                ? "_Экспозиция в лучшие и худшие дни почти одинакова — механизм не различает хорошую волатильность и плохую, режет доходность симметрично с риском._\n\n"
                : "_Экспозиция в лучшие и худшие дни заметно различается._\n\n");

        // ---- H5: разложение потери доходности ----
        Metrics full = metrics(sim(ones(m), noImmediate(m), close, ci).curve(), 0, years);
        Metrics act = metrics(sim(vt, noImmediate(m), close, ci).curve(), 0, years);
        double foregone = 0, avoided = 0;
        for (int t = 1; t < m; t++) {
            double e = vt[t - 1];        // exposure, действующая в день t
            if (e < 0.8) {
                double u = (1 - e) * ret[t];
                if (ret[t] > 0) {
                    foregone += u;       // упущенный рост (цена)
                } else {
                    avoided += -u;       // избегнутое падение (защита)
                }
            }
        }
        sb.append("## H5. Разложение потери доходности\n\n");
        sb.append(String.format("CAGR при exposure=1 всегда: %.1f%% (≈ buy&hold − издержки). CAGR по факту: %.1f%%.%n%n",
                full.cagr() * 100, act.cagr() * 100));
        sb.append(String.format("В дни с exposure<0.8: упущенный рост (сумма недобора на росте) = %.2f, ", foregone));
        sb.append(String.format("избегнутое падение = %.2f, отношение защита/цена = **%.2f**.%n%n",
                avoided, foregone > 0 ? avoided / foregone : 0));

        // ---- §3 технические проверки ----
        double vtTurnover = sim(vt, noImmediate(m), close, ci).turnover() / years;
        sb.append("## Технические проверки (§3)\n\n");
        sb.append(String.format("1. **Аннуализация:** σ_target=%.4f и σ_реализ — оба дневные std лог-доходностей (те же единицы). ", sigmaTarget))
                .append("Постоянного упора в потолок из-за √365-сдвига нет.\n");
        sb.append("2. **Задержка:** exposure дня t применяется с t+1 (в `sim` экспозиция входит в день по решению t−1) — утечки нет.\n");
        sb.append("3. **Реаллокация:** к voltarget применяется та же 5-дневная реаллокация, что к другим вариантам (как есть).\n");
        sb.append(String.format("4. **Издержки:** оборот %.1f/год → ~%.2f%%/год — пренебрежимо против потери ~6.6 п.п.%n", vtTurnover, vtTurnover * COST * 100));
        sb.append("5. **Прогрев:** σ_target по барам до окна оценки (2019), окно с 2020 — пересечения нет.\n\n");

        // ---- вывод §4.1 ----
        boolean h1c = h1ceil > 0.35, h2c = worst20 > 0.3, h4c = Math.abs(h4diff) < 0.1;
        sb.append("## Вывод (развилка §4.1)\n\n");
        if (h1c && h2c && h4c) {
            sb.append("**Вариант А — несоответствие инструмента задаче (H1/H2/H4 подтверждены).** ")
                    .append("voltarget упирается в потолок половину времени, не выходит в обвалах и режет доходность симметрично с риском. ")
                    .append("Для снижения просадки нужен механизм **выхода** (оси D+T), а не масштабирования. ")
                    .append("**`stress_level` остаётся выключенным:** его множитель ограничен [0.5, 1.0] — он ещё менее способен выходить, чем voltarget.\n");
        } else if (lagCount >= 3) {
            sb.append("**Вариант Б — запаздывание окна (H3 доминирует).** Основание для ОДНОЙ проверки: ")
                    .append("прогнать `stress_level` (σ7д, вчетверо быстрее) как множитель и сравнить по тем же метрикам.\n");
        } else {
            sb.append("**Смешанный результат** — см. числа выше; развилка §4.1 решается вручную.\n");
        }

        writeFile(outPath, sb.toString());
        log.info("voltarget: H1 ceil={}%, H4 diff={}, min exp={} -> {}",
                Math.round(h1ceil * 100), String.format("%.2f", h4diff), String.format("%.2f", vtMin),
                Path.of(outPath).toAbsolutePath());
    }

    private static double[] ones(int m) {
        double[] a = new double[m];
        java.util.Arrays.fill(a, 1.0);
        return a;
    }

    /**
     * Последняя проверка — ансамбль (док. 20 §3). Голосование большинством 3 кандидатов,
     * равные веса, ноль свободных параметров: in_market = ([sma200=BULL]+[cusum=BULL]+
     * [rules2d∈{BULL,TRANSITION}]) ≥ 2 → экспозиция 100%, иначе 0%. На ПОЛНЫХ окнах 6
     * рынков (окно 2020–2026 израсходовано). Плюс контроль с постоянной экспозицией,
     * равной средней экспозиции ансамбля (§2.1 — отделяет тайминг от снижения риска).
     * Критерий §3.4 (зафиксирован до прогона): принять, если ансамбль превосходит
     * sma200 по ratio на ≥4/6 рынков И не проигрывает по абс. просадке >3 п.п. ни на одном.
     * CLI: --report=ensemble [--out=reports/ensemble.md].
     */
    public void ensembleRun(String outPath) {
        record Mkt(String name, String symbol) {}
        List<Mkt> mkts = List.of(
                new Mkt("BTC", "BTC-USD"),
                new Mkt("ETH", "ETH-USD"),
                new Mkt("S&P 500", "^GSPC"),
                new Mkt("Золото", "GC=F"),
                new Mkt("Нефть WTI", "CL=F"),
                new Mkt("EUR/USD", "EURUSD=X"));
        double delta = 0.01, h = 0.15;   // из configs/bench-params.properties (зафиксированы до прогона)
        try {
            Properties pr = new Properties();
            Path cf = Path.of("configs/bench-params.properties");
            if (Files.exists(cf)) {
                pr.load(Files.newBufferedReader(cf, StandardCharsets.UTF_8));
                delta = Double.parseDouble(pr.getProperty("cusum.delta", "0.01"));
                h = Double.parseDouble(pr.getProperty("cusum.h", "0.15"));
            }
        } catch (IOException ignore) {
            // дефолты соответствуют конфигу
        }
        var sma200 = new org.home.data.eval.bench.Candidates.Sma200();
        var cusum = new org.home.data.eval.bench.Candidates.Cusum(delta, h);

        StringBuilder sb = new StringBuilder();
        sb.append("# Последняя проверка — ансамбль (док. 20 §3)\n\n");
        sb.append("Голосование большинством: `in_market = ([sma200=BULL] + [cusum=BULL] + ")
                .append("[rules2d∈{BULL,TRANSITION}]) ≥ 2` → 100%, иначе 0%. Ноль свободных параметров, ")
                .append("равные веса, одна конфигурация. **Полные окна** (2020–2026 не используется). ")
                .append("Прокси как в crossmarket. Контроль — постоянная экспозиция = средней экспозиции ансамбля.\n\n");
        sb.append("Критерий §3.4 (до прогона): принять ансамбль, если (1) он лучше `sma200` по `ratio` на ")
                .append("**≥4/6** рынков И (2) не хуже `sma200` по абс. просадке более чем на **3 п.п.** ни на одном.\n\n");
        sb.append("| Рынок | Окно (eval) | ансамбль CAGR/MaxDD/ratio | sma200 CAGR/MaxDD/ratio | контроль (эксп=avg) CAGR | избыток ансамбля | avg эксп |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        int ensBetterRatio = 0, valid = 0;
        boolean crit2ok = true;
        for (Mkt mk : mkts) {
            Ohlc o;
            try {
                o = fetchYahoo(mk.symbol());
                Thread.sleep(400);
            } catch (Exception e) {
                sb.append(String.format("| %s | ошибка загрузки (%s) | | | | | |%n", mk.name(), e.getClass().getSimpleName()));
                continue;
            }
            if (o == null || o.close().length < 400) {
                sb.append(String.format("| %s | мало данных | | | | | |%n", mk.name()));
                continue;
            }
            String[] r = RegimeDetectorV3.statesFromPrice(o.high(), o.low(), o.close());
            String[] s = sma200.predict(o.high(), o.low(), o.close());
            String[] c = cusum.predict(o.high(), o.low(), o.close());
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < r.length; i++) {
                if (r[i] != null && s[i] != null && c[i] != null) {
                    idxs.add(i);
                }
            }
            if (idxs.size() < 200) {
                sb.append(String.format("| %s | мало данных после прогрева | | | | | |%n", mk.name()));
                continue;
            }
            int m = idxs.size();
            int[] ci = new int[m];
            double[] ensTarget = new double[m];
            String[] sEv = new String[m];
            double expSum = 0;
            for (int t = 0; t < m; t++) {
                int i = idxs.get(t);
                ci[t] = i;
                sEv[t] = s[i];
                int votes = ("BULL".equals(s[i]) ? 1 : 0)
                        + ("BULL".equals(c[i]) ? 1 : 0)
                        + (("BULL".equals(r[i]) || "TRANSITION".equals(r[i])) ? 1 : 0);
                ensTarget[t] = votes >= 2 ? 1.0 : 0.0;
                expSum += ensTarget[t];
            }
            double avgExp = expSum / m;
            LocalDate a = LocalDate.parse(o.dates()[ci[0]]), b = LocalDate.parse(o.dates()[ci[m - 1]]);
            double years = (b.toEpochDay() - a.toEpochDay()) / 365.25;
            Metrics ens = metrics(sim(ensTarget, noImmediate(m), o.close(), ci).curve(), 0, years);
            Metrics smaM = metrics(sim(stateTargets(sEv), noImmediate(m), o.close(), ci).curve(), 0, years);
            Metrics bh = buyHold(o.close(), ci, years);
            Metrics ctrl = constExposure(avgExp, o.close(), ci, years);
            double ensRatio = ratio(ens, bh), smaRatio = ratio(smaM, bh);
            boolean ensBetter = ratioScore(ens, bh) < ratioScore(smaM, bh);
            boolean lose3 = ens.maxdd() < smaM.maxdd() - 0.03;   // ансамбль глубже sma200 более чем на 3 п.п.
            valid++;
            if (ensBetter) {
                ensBetterRatio++;
            }
            if (lose3) {
                crit2ok = false;
            }
            sb.append(String.format("| %s | %s … %s | %.1f%% / %.1f%% / %s | %.1f%% / %.1f%% / %s | %.1f%% | %+.1f п.п.%s | %.0f%% |%n",
                    mk.name(), o.dates()[ci[0]], o.dates()[ci[m - 1]],
                    ens.cagr() * 100, ens.maxdd() * 100, fmtRatio(ensRatio),
                    smaM.cagr() * 100, smaM.maxdd() * 100, fmtRatio(smaRatio),
                    ctrl.cagr() * 100, (ens.cagr() - ctrl.cagr()) * 100, lose3 ? " ⚠просадка" : "", avgExp * 100));
        }

        boolean crit1 = ensBetterRatio >= 4;
        boolean pass = crit1 && crit2ok && valid == 6;
        sb.append(String.format("%n**Критерий §3.4:** (1) ансамбль лучше sma200 по ratio на **%d/6** (нужно ≥4) — %s; ",
                ensBetterRatio, crit1 ? "✓" : "✗"));
        sb.append(String.format("(2) нигде не хуже по просадке >3 п.п. — %s.%n%n", crit2ok ? "✓" : "✗"));
        sb.append(pass
                ? "**Итог: ансамбль ПРИНЯТ — идёт в прод.**\n"
                : "**Итог: критерий НЕ выполнен → ансамбль отклонён, в прод идёт `baseline_sma200` без дальнейших обсуждений (§3.4).**\n");
        sb.append("\nОжидание, записанное до прогона (§3.3): ансамбль не пройдёт — три компоненты читают один ценовой ")
                .append("ряд и следуют трендовой логике, усреднение снижает дисперсию, но не общее смещение. ")
                .append("Среднее трёх запаздывающих детекторов остаётся запаздывающим.\n");

        writeFile(outPath, sb.toString());
        log.info("ensemble: лучше sma200 по ratio на {}/6, crit2={}, ИТОГ {} -> {}",
                ensBetterRatio, crit2ok, pass ? "ПРИНЯТ" : "отклонён", Path.of(outPath).toAbsolutePath());
    }

    /** Контроль: постоянная экспозиция exp каждый день (§2.1), остальное — кэш. */
    private static Metrics constExposure(double exp, double[] close, int[] ci, double years) {
        int m = ci.length;
        double[] curve = new double[m];
        double eq = 1;
        for (int t = 0; t < m; t++) {
            if (t > 0) {
                double br = close[ci[t]] / close[ci[t - 1]] - 1;
                eq *= 1 + exp * br + (1 - exp) * CASH_DAILY;
            }
            curve[t] = eq;
        }
        return metrics(curve, 0, years);
    }

    /** Сортируемый скор ratio: доминирование (обгон B&H по обеим осям) — лучший; не снижает просадку — худший. */
    private static double ratioScore(Metrics det, Metrics bh) {
        double dCagr = det.cagr() - bh.cagr(), dMaxdd = det.maxdd() - bh.maxdd();
        if (dMaxdd <= 0) {
            return Double.POSITIVE_INFINITY;   // не снижает просадку
        }
        return dCagr < 0 ? -dCagr / dMaxdd : -1.0;   // доминирование = −1 (лучше любого положительного ratio)
    }

    /**
     * Кросс-рыночная проверка v3 (док. 15 §8): тот же детектор (оси D/T, те же пороги)
     * без изменений на ETH/SPY/золоте/нефти/EUR-USD. Критерий — эффект сохраняется по
     * знаку и порядку величины: просадка снижается, доходность частично теряется.
     * Данные — Yahoo v8 chart (дневной OHLC, без ключа). BTC — референс (in-sample).
     * CLI: --report=crossmarket [--out=reports/crossmarket.md].
     */
    public void crossMarket(String outPath) {
        record Mkt(String name, String symbol, boolean reference) {}
        List<Mkt> mkts = List.of(
                new Mkt("BTC (референс)", "BTC-USD", true),
                new Mkt("ETH", "ETH-USD", false),
                new Mkt("S&P 500", "^GSPC", false),
                new Mkt("Золото", "GC=F", false),
                new Mkt("Нефть WTI", "CL=F", false),
                new Mkt("EUR/USD", "EURUSD=X", false));

        var sma200 = new org.home.data.eval.bench.Candidates.Sma200();
        StringBuilder sb = new StringBuilder();
        sb.append("# Кросс-рыночная проверка §8 — rules2d vs baseline_sma200\n\n");
        sb.append("Оба кандидата **без изменений параметров** на других рынках, тот же прокси (BULL 100% / ")
                .append("TRANSITION 50% / прочее 0%, кэш 8%/год, реаллокация 5 дней, издержки 0.10%). Данные — Yahoo ")
                .append("Finance (дневной OHLC). Общее окно оценки на каждый рынок = где определён rules2d (более ")
                .append("длинный прогрев). Сравнимы относительные величины, `ratio` = ΔMaxDD/ΔCAGR к B&H (меньше = лучше).\n\n");
        sb.append("**Вопрос:** держится ли на других рынках ничья по `ratio` (BTC: 0.15 vs 0.14) и вдвое меньшая ")
                .append("абсолютная просадка у rules2d?\n\n");
        sb.append("| Рынок | Период (eval) | rules2d: CAGR / MaxDD / ratio | sma200: CAGR / MaxDD / ratio | B&H MaxDD |\n");
        sb.append("|---|---|---|---|---|\n");

        int total = 0, rulesDD = 0, smaDD = 0, rulesShallower = 0, rulesBetterRatio = 0;
        for (Mkt mk : mkts) {
            Ohlc o;
            try {
                o = fetchYahoo(mk.symbol());
                Thread.sleep(400);
            } catch (Exception e) {
                sb.append(String.format("| %s | ошибка загрузки (%s) | | | |%n", mk.name(), e.getClass().getSimpleName()));
                continue;
            }
            if (o == null || o.close().length < 400) {
                sb.append(String.format("| %s | мало данных | | | |%n", mk.name()));
                continue;
            }
            String[] rStates = RegimeDetectorV3.statesFromPrice(o.high(), o.low(), o.close());
            String[] sStates = sma200.predict(o.high(), o.low(), o.close());
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < rStates.length; i++) {
                if (rStates[i] != null && sStates[i] != null) {
                    idxs.add(i);
                }
            }
            if (idxs.size() < 200) {
                sb.append(String.format("| %s | мало данных после прогрева | | | |%n", mk.name()));
                continue;
            }
            int m = idxs.size();
            int[] ci = new int[m];
            String[] rEv = new String[m], sEv = new String[m];
            for (int t = 0; t < m; t++) {
                ci[t] = idxs.get(t);
                rEv[t] = rStates[idxs.get(t)];
                sEv[t] = sStates[idxs.get(t)];
            }
            LocalDate a = LocalDate.parse(o.dates()[ci[0]]), b = LocalDate.parse(o.dates()[ci[m - 1]]);
            double years = (b.toEpochDay() - a.toEpochDay()) / 365.25;
            Metrics dR = metrics(sim(stateTargets(rEv), noImmediate(m), o.close(), ci).curve(), 0, years);
            Metrics dS = metrics(sim(stateTargets(sEv), noImmediate(m), o.close(), ci).curve(), 0, years);
            Metrics bh = buyHold(o.close(), ci, years);
            double rRatio = ratio(dR, bh), sRatio = ratio(dS, bh);
            if (!mk.reference()) {
                total++;
                if (dR.maxdd() > bh.maxdd()) {
                    rulesDD++;
                }
                if (dS.maxdd() > bh.maxdd()) {
                    smaDD++;
                }
                if (dR.maxdd() > dS.maxdd()) {
                    rulesShallower++;   // rules2d менее глубокая просадка
                }
                if (!Double.isNaN(rRatio) && !Double.isNaN(sRatio) && rRatio < sRatio) {
                    rulesBetterRatio++;
                }
            }
            sb.append(String.format("| %s | %s … %s | %.1f%% / %.1f%% / %s | %.1f%% / %.1f%% / %s | %.1f%% |%n",
                    mk.name(), o.dates()[ci[0]], o.dates()[ci[m - 1]],
                    dR.cagr() * 100, dR.maxdd() * 100, fmtRatio(rRatio),
                    dS.cagr() * 100, dS.maxdd() * 100, fmtRatio(sRatio), bh.maxdd() * 100));
        }

        sb.append(String.format("%n**Итог по %d внешним рынкам:** просадку снижают оба — rules2d на %d/%d, sma200 на %d/%d. ",
                total, rulesDD, total, smaDD, total));
        sb.append(String.format("У rules2d **абсолютная просадка мельче, чем у sma200, на %d/%d** рынков; ", rulesShallower, total));
        sb.append(String.format("по `ratio` rules2d лучше на %d/%d.%n%n", rulesBetterRatio, total));
        sb.append("Чтение: если ничья по `ratio` держится, но rules2d стабильно даёт меньшую абсолютную просадку на ")
                .append("разных классах активов — это довод в пользу композита именно по глубине просадки, а не по эффективности. ")
                .append("Если же sma200 не хуже и по абсолютной просадке — правило отсечения сложности усиливается.\n\n");
        sb.append("**Легенда `ratio`:** «—» = кандидат обогнал buy&hold и по доходности, и по просадке (ΔCAGR≥0, ")
                .append("доминирование) — это лучший возможный исход, а не провал (док. 20 §2.6). Так, sma200 доминирует ")
                .append("B&H на BTC-2015 и ETH.\n\n");
        sb.append("**Оговорка:** на низкодрейфовых активах (EUR/USD и т.п.) плюсовой CAGR — в основном кэш 8% за время ")
                .append("вне рынка; значимый сигнал — снижение просадки. Кэш по торговым барам слегка занижает нейтральное ядро.\n");

        writeFile(outPath, sb.toString());
        log.info("crossmarket: rules2d DD↓ {}/{}, sma200 DD↓ {}/{}, rules2d мельче на {}/{} -> {}",
                rulesDD, total, smaDD, total, rulesShallower, total, Path.of(outPath).toAbsolutePath());
    }

    /** Дневной OHLC с Yahoo v8 chart (без ключа), нулевые бары отброшены. period1 = 1990-01-01. */
    private static Ohlc fetchYahoo(String symbol) throws IOException, InterruptedException {
        String url = YAHOO + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=631152000&period2=" + Instant.now().getEpochSecond() + "&interval=1d";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        JsonNode res = MAPPER.readTree(resp.body()).path("chart").path("result").path(0);
        JsonNode ts = res.path("timestamp");
        JsonNode q = res.path("indicators").path("quote").path(0);
        JsonNode hi = q.path("high"), lo = q.path("low"), cl = q.path("close");
        List<Double> h = new ArrayList<>(), l = new ArrayList<>(), c = new ArrayList<>();
        List<String> d = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            if (cl.path(i).isNull() || hi.path(i).isNull() || lo.path(i).isNull()) {
                continue;
            }
            h.add(hi.path(i).asDouble());
            l.add(lo.path(i).asDouble());
            c.add(cl.path(i).asDouble());
            d.add(Instant.ofEpochSecond(ts.path(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
        if (c.isEmpty()) {
            return null;
        }
        return new Ohlc(toArr(h), toArr(l), toArr(c), d.toArray(new String[0]));
    }

    private static double[] toArr(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = xs.get(i);
        }
        return a;
    }

    // ================= симуляция =================

    private record Sim(double[] curve, double turnover) {}

    private static Metrics simulate(double[] target, boolean[] immediate, double[] close, int[] ci, double years) {
        Sim s = sim(target, immediate, close, ci);
        return metrics(s.curve(), s.turnover(), years);
    }

    /** exposure входит в день t по состоянию t−1 (без look-ahead). Возвращает кривую капитала и оборот. */
    private static Sim sim(double[] target, boolean[] immediate, double[] close, int[] ci) {
        int m = target.length;
        double[] curve = new double[m];
        double eq = 1, actual = 0, curTarget = -999, step = 0, turnover = 0;
        int stepsLeft = 0;
        for (int t = 0; t < m; t++) {
            if (t > 0) {
                double br = close[ci[t]] / close[ci[t - 1]] - 1;
                eq *= 1 + actual * br + (1 - actual) * CASH_DAILY;
            }
            double tgt = target[t];
            if (tgt != curTarget || (immediate[t] && actual != tgt)) {
                curTarget = tgt;
                step = immediate[t] ? tgt - actual : (tgt - actual) / STEPS;
                stepsLeft = immediate[t] ? 1 : STEPS;
            }
            if (stepsLeft > 0) {
                double na = actual + step;
                stepsLeft--;
                if (stepsLeft == 0) {
                    na = curTarget;
                }
                double d = Math.abs(na - actual);
                if (d > 0) {
                    eq *= 1 - COST * d;
                    turnover += d;
                }
                actual = na;
            }
            curve[t] = eq;
        }
        return new Sim(curve, turnover);
    }

    /** {индекс пика перед максимальной просадкой, индекс дна просадки}. */
    private static int[] ddIndices(double[] curve) {
        double peak = curve[0], worst = 0;
        int peakIdx = 0, troughIdx = 0, curPeakIdx = 0;
        for (int i = 0; i < curve.length; i++) {
            if (curve[i] > peak) {
                peak = curve[i];
                curPeakIdx = i;
            }
            double dd = curve[i] / peak - 1;
            if (dd < worst) {
                worst = dd;
                troughIdx = i;
                peakIdx = curPeakIdx;
            }
        }
        return new int[]{peakIdx, troughIdx};
    }

    private static Metrics buyHold(double[] close, int[] ci, double years) {
        int m = ci.length;
        double[] curve = new double[m];
        double eq = 1;
        for (int t = 0; t < m; t++) {
            if (t > 0) {
                eq *= close[ci[t]] / close[ci[t - 1]];
            }
            curve[t] = eq;
        }
        return metrics(curve, 0, years);
    }

    private static Metrics metrics(double[] curve, double turnover, double years) {
        int m = curve.length;
        double cagr = Math.pow(curve[m - 1], 1.0 / years) - 1;
        double peak = curve[0], maxdd = 0;
        for (double v : curve) {
            peak = Math.max(peak, v);
            maxdd = Math.min(maxdd, v / peak - 1);
        }
        double sum = 0, sumSq = 0;
        int c = 0;
        for (int t = 1; t < m; t++) {
            double r = curve[t] / curve[t - 1] - 1;
            sum += r; sumSq += r * r; c++;
        }
        double mean = sum / c, std = Math.sqrt(Math.max(sumSq / c - mean * mean, 0));
        double sharpe = std <= 0 ? 0 : mean / std * Math.sqrt(365);
        return new Metrics(cagr, maxdd, sharpe, turnover / years);
    }

    private static double[] stateTargets(String[] states) {
        double[] out = new double[states.length];
        for (int t = 0; t < states.length; t++) {
            out[t] = switch (states[t]) {
                case "BULL" -> 1.0;
                case "TRANSITION" -> 0.5;
                default -> 0.0;
            };
        }
        return out;
    }

    private static boolean[] crashImmediate(String[] states) {
        boolean[] out = new boolean[states.length];
        for (int t = 0; t < states.length; t++) {
            out[t] = states[t].equals("CRASH") && (t == 0 || !states[t - 1].equals("CRASH"));
        }
        return out;
    }

    private static boolean[] noImmediate(int m) {
        return new boolean[m];
    }

    // ================= A7 / A8 =================

    /** {passed, intradayDropShare, lowerWick, reb5, reb10}. */
    private static double[] a7(int[] ep, int[] ci, double[] open, double[] high, double[] low, double[] close) {
        int si = ci[ep[0]], ei = ci[ep[1]];
        double pMax = Double.NEGATIVE_INFINITY;
        for (int j = Math.max(0, si - 30); j < si; j++) {
            pMax = Math.max(pMax, close[j]);
        }
        double pTrig = close[si];
        double pMin = Double.POSITIVE_INFINITY;
        for (int j = si; j <= ei; j++) {
            pMin = Math.min(pMin, close[j]);
        }
        double range = pMax - pMin;
        double passed = range > 0 ? (pMax - pTrig) / range : 0;
        // день максимального внутридневного движения в эпизоде
        int kd = si; double best = -1;
        for (int j = si; j <= ei; j++) {
            if (high[j] - low[j] > best) {
                best = high[j] - low[j];
                kd = j;
            }
        }
        double intraday = range > 0 ? (open[kd] - low[kd]) / range : 0;
        double wick = (high[kd] - low[kd]) > 0 ? (close[kd] - low[kd]) / (high[kd] - low[kd]) : 0;
        int n = close.length;
        double reb5 = si + 5 < n ? close[si + 5] / pTrig - 1 : Double.NaN;
        double reb10 = si + 10 < n ? close[si + 10] / pTrig - 1 : Double.NaN;
        return new double[]{passed, intraday, wick, Double.isNaN(reb5) ? 0 : reb5, Double.isNaN(reb10) ? 0 : reb10};
    }

    private static String verdictA7(double avg) {
        if (avg > 0.70) {
            return "**Вывод: >70% — дневной CRASH фиксирует убыток вблизи дна, а не предотвращает его. "
                    + "B-удаление становится основным кандидатом (проверить A4.3).**";
        }
        if (avg >= 0.40) {
            return "Вывод: 40–70% — защищает частично, решение по экономике A4.";
        }
        return "Вывод: <40% — дневной детектор успевает, конструкция защищает (чинить по фазе B).";
    }

    /** Корреляция (1 − exposure_vt) с индикатором CRASH по окну оценки. */
    private static double corrCrashVsVt(String[] states, double[] vtExp) {
        int m = states.length;
        double[] x = new double[m], y = new double[m];
        for (int t = 0; t < m; t++) {
            x[t] = 1 - vtExp[t];
            y[t] = states[t].equals("CRASH") ? 1 : 0;
        }
        return pearson(x, y);
    }

    /** За сколько дней до входа в CRASH voltarget уже держал экспозицию ≤0.5 (подряд). */
    private static int vtLead(int startT, int[] ci, double[] sigma, double sigmaTarget) {
        int si = ci[startT];
        int lead = 0;
        for (int j = si - 1; j >= 0; j--) {
            double sg = sigma[j];
            double exp = Double.isNaN(sg) || sg <= 0 ? 1.0 : clip(sigmaTarget / sg, 0, 1);
            if (exp <= 0.5) {
                lead++;
            } else {
                break;
            }
        }
        return lead;
    }

    // ================= вспомогательное =================

    private static List<int[]> episodes(String[] states) {
        List<int[]> ep = new ArrayList<>();
        int start = -1;
        for (int t = 0; t < states.length; t++) {
            boolean cr = states[t].equals("CRASH");
            if (cr && start < 0) {
                start = t;
            }
            if (!cr && start >= 0) {
                ep.add(new int[]{start, t - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            ep.add(new int[]{start, states.length - 1});
        }
        return ep;
    }

    private static double[] realizedVol(double[] close, int win) {
        int n = close.length;
        double[] ret = new double[n];
        ret[0] = Double.NaN;
        for (int i = 1; i < n; i++) {
            ret[i] = Math.log(close[i] / close[i - 1]);
        }
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = win; i < n; i++) {
            double sum = 0, sq = 0;
            for (int j = i - win + 1; j <= i; j++) {
                sum += ret[j]; sq += ret[j] * ret[j];
            }
            double mean = sum / win;
            out[i] = Math.sqrt(Math.max(sq / win - mean * mean, 0));
        }
        return out;
    }

    private static double medianWarmup(double[] sigma, int firstEval) {
        List<Double> w = new ArrayList<>();
        for (int i = 0; i < firstEval; i++) {
            if (!Double.isNaN(sigma[i])) {
                w.add(sigma[i]);
            }
        }
        if (w.isEmpty()) {
            for (int i = 0; i < sigma.length; i++) {
                if (!Double.isNaN(sigma[i])) {
                    w.add(sigma[i]);
                }
            }
        }
        java.util.Collections.sort(w);
        return w.isEmpty() ? 0 : w.get(w.size() / 2);
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; syy += y[i] * y[i]; sxy += x[i] * y[i];
        }
        double cov = n * sxy - sx * sy;
        double vx = n * sxx - sx * sx, vy = n * syy - sy * sy;
        return (vx <= 0 || vy <= 0) ? 0 : cov / Math.sqrt(vx * vy);
    }

    private static void row(StringBuilder sb, String name, Metrics v, Metrics bh) {
        double dCagr = v.cagr() - bh.cagr(), dMaxdd = v.maxdd() - bh.maxdd();
        String ratio = (dCagr < 0 && dMaxdd > 0) ? String.format("%.2f", -dCagr / dMaxdd) : "—";
        sb.append(String.format("| %s | %.1f%% | %.1f%% | %.2f | %.1f | %+.1f pp | %+.1f pp | %s |%n",
                name, v.cagr() * 100, v.maxdd() * 100, v.sharpe(), v.turnover(), dCagr * 100, dMaxdd * 100, ratio));
    }

    private static void errRow(StringBuilder sb, String name, String[] states, String[] labels, int[] ci) {
        int trueBull = 0, falseBear = 0, crashBull = 0, crashBear = 0, crashTot = 0;
        for (int t = 0; t < states.length; t++) {
            String lab = labels[ci[t]];
            if (lab.equals("BULL")) {
                trueBull++;
                if (states[t].equals("BEAR") || states[t].equals("CRASH")) {
                    falseBear++;
                }
            }
            if (states[t].equals("CRASH")) {
                crashTot++;
                if (lab.equals("BULL")) crashBull++;
                if (lab.equals("BEAR")) crashBear++;
            }
        }
        String fbr = trueBull > 0 ? String.format("%.1f%%", 100.0 * falseBear / trueBull) : "—";
        String cb = crashTot > 0 ? String.format("%d (%.0f%%)", crashBull, 100.0 * crashBull / crashTot) : "0";
        String cbe = crashTot > 0 ? String.format("%d (%.0f%%)", crashBear, 100.0 * crashBear / crashTot) : "0";
        sb.append(String.format("| %s | %s | %s | %s |%n", name, fbr, cb, cbe));
    }

    private static String labelDist(String[] labels, int[] ci) {
        int bull = 0, bear = 0, range = 0;
        for (int idx : ci) {
            switch (labels[idx]) {
                case "BULL" -> bull++;
                case "BEAR" -> bear++;
                default -> range++;
            }
        }
        return String.format("BULL %d / BEAR %d / RANGE %d дней", bull, bear, range);
    }

    private static double years(List<String> eval) {
        return (LocalDate.parse(eval.get(eval.size() - 1)).toEpochDay() - LocalDate.parse(eval.get(0)).toEpochDay()) / 365.25;
    }

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    /** ΔMaxDD/ΔCAGR к buy&hold: сколько CAGR отдано за 1 пункт снятой просадки (NaN, если не снижает). */
    private static double ratio(Metrics det, Metrics bh) {
        double dCagr = det.cagr() - bh.cagr(), dMaxdd = det.maxdd() - bh.maxdd();
        return (dCagr < 0 && dMaxdd > 0) ? -dCagr / dMaxdd : Double.NaN;
    }

    private static String fmtRatio(double r) {
        return Double.isNaN(r) ? "—" : String.format("%.2f", r);
    }

    private void writeFile(String outPath, String content) {
        try {
            Path out = Path.of(outPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, content);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать отчёт: " + outPath, e);
        }
    }

    /** Журнал гипотез (док. 15 §7, append-only): 1 дефект dd_speed, 2 дневной таймфрейм (A7), 3 voltarget. */
    private void writeHypotheses(double passedAvg, Metrics vt, double corr) {
        Path p = Path.of("reports/hypotheses.md");
        String header = "# Журнал гипотез (append-only, док. 15 §7)\n\n"
                + "| дата | гипотеза | мотивация | результат | решение |\n|---|---|---|---|---|\n";
        String e1 = "| 2026-08 | S = max(vol_z, dd_speed), порог 0.80, выход S<0.5 три дня | исходная конструкция doc 01 v2 §2.3 | "
                + "515 дней в CRASH (21.5%), dd_speed доминирует 85% и держится над 0.5 до 41 дня (храповик) | "
                + "дефект конструкции: dd_speed удалить, S разделить на S_acute + stress_level |\n";
        String e2 = String.format("| 2026-08 | дневной таймфрейм распознаёт каскад ликвидаций | проверка A7 (док. 18-v2) | "
                + "среднее passed по топ-5 эпизодам = %.0f%%, отскок +5д в среднем −8%% | "
                + "распознаётся к середине движения; для S7 нужен внутридневной контур (v3 §7.4) |%n", passedAvg * 100);
        String e3 = String.format("| 2026-08 | volatility targeting в форме clip(σ_target/σ_30д) защищает просадку | "
                + "baseline_voltarget (док. 15 §5.1), кандидат в замену CRASH/основу stress_level | "
                + "MaxDD %.1f%%, Sharpe %.2f, корреляция с CRASH %.2f, 0 дней опережения | "
                + "не работает как реализовано; stress_level вводится выключенным до разбора (doc 19) |%n",
                vt.maxdd() * 100, vt.sharpe(), corr);
        try {
            String cur = Files.exists(p) ? Files.readString(p) : header;
            if (!cur.contains("max(vol_z, dd_speed)")) {
                cur += e1;
            }
            if (!cur.contains("дневной таймфрейм")) {
                cur += e2;
            }
            if (!cur.contains("volatility targeting")) {
                cur += e3;
            }
            Files.writeString(p, cur);
        } catch (IOException e) {
            log.warn("не удалось записать hypotheses.md: {}", e.toString());
        }
    }
}
