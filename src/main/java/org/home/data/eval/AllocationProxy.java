package org.home.data.eval;

import org.home.data.core.Db;
import org.home.data.detector.RegimeFsmV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Db db;

    public AllocationProxy(Db db) {
        this.db = db;
    }

    private record Axes(double d, double t, double s, String state) {}
    private record Metrics(double cagr, double maxdd, double sharpe, double turnover) {}

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
        writeHypotheses(passedAvg);
        log.info("crash-econ: A7 avg passed={}%, отчёт -> {}", Math.round(passedAvg * 100), Path.of(outPath).toAbsolutePath());
    }

    // ================= симуляция =================

    /** exposure входит в день t по состоянию t−1 (без look-ahead). target — целевая экспозиция на каждый день. */
    private static Metrics simulate(double[] target, boolean[] immediate, double[] close, int[] ci, double years) {
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
        return metrics(curve, turnover, years);
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

    /** Журнал гипотез (док. 15 §7, append-only): записи 1 (дефект S) и 2 (дневной таймфрейм, A7). */
    private void writeHypotheses(double passedAvg) {
        Path p = Path.of("reports/hypotheses.md");
        String header = "# Журнал гипотез (append-only, док. 15 §7)\n\n"
                + "| дата | гипотеза | мотивация | результат | решение |\n|---|---|---|---|---|\n";
        String e1 = "| 2026-08 | S = max(vol_z, dd_speed), порог 0.80, выход S<0.5 три дня | исходная конструкция doc 01 v2 §2.3 | "
                + "515 дней в CRASH (21.5%), dd_speed доминирует 85% и держится над 0.5 до 41 дня (храповик) | "
                + "дефект конструкции: dd_speed удалить, S разделить на S_acute + stress_level |\n";
        String e2 = String.format("| 2026-08 | дневной таймфрейм распознаёт каскад ликвидаций | проверка A7 (док. 18-v2) | "
                + "среднее passed по топ-5 эпизодам = %.0f%% | %s |%n", passedAvg * 100,
                passedAvg > 0.70 ? "дневной CRASH фиксирует убыток, а не предотвращает — кандидат на удаление" : "защищает частично/успевает");
        try {
            String cur = Files.exists(p) ? Files.readString(p) : header;
            if (!cur.contains("max(vol_z, dd_speed)")) {
                cur += e1;
            }
            if (!cur.contains("дневной таймфрейм")) {
                cur += e2;
            }
            Files.writeString(p, cur);
        } catch (IOException e) {
            log.warn("не удалось записать hypotheses.md: {}", e.toString());
        }
    }
}
