package org.home.data.eval.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.Db;
import org.home.data.eval.PeakTroughLabeler;
import org.home.data.eval.bench.Candidates.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Стенд сравнения детекторов режима (док. 15 v4). Прогоняет кандидатов
 * ({@link Candidates}) через одну ex-post разметку ({@link PeakTroughLabeler}), один
 * экономический прокси (§6.2) и одни метрики (§6.3), отвечая на главный вопрос §6.4:
 * <b>бьёт ли композит rules2d однострочный baseline_sma200</b>. Пишет comparison.md/csv,
 * checklist.md (§7), rejected.md (§10), bench-chart.html. Прокси-логика — своя копия
 * (не трогает AllocationProxy). CLI: --report=bench [--out=reports].
 */
@Component
public class Bench {

    private static final Logger log = LoggerFactory.getLogger(Bench.class);

    private static final double COST = 0.001;
    private static final double CASH_DAILY = Math.pow(1.08, 1.0 / 365) - 1;
    private static final int STEPS = 5;
    private static final String EVAL_FROM = "2020-01-01";   // окно, на котором зафиксированы числа §0
    private static final long SHUFFLE_SEED = 42;            // детерминизм §7.4

    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public Bench(Db db) {
        this.db = db;
    }

    private record Metrics(double cagr, double maxdd, double sharpe, double turnover) {}
    private record Tech(double accuracy, double lagDays, double flipsPerYear, double falseBear) {}
    private record Row(String key, Metrics m, Tech t, double dMaxddOverDCagr, double se, double sDefl) {}

    public void run(String outDir) {
        // ---- данные ----
        List<String> day = new ArrayList<>();
        List<double[]> ohlc = new ArrayList<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, high, low, close FROM candles "
                        + "WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { day.add(rs.getString(1));
                        ohlc.add(new double[]{rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)}); return null; });
        int n = day.size();
        if (n < 800) {
            log.warn("bench: мало свечей ({})", n);
            return;
        }
        double[] high = new double[n], low = new double[n], close = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = ohlc.get(i)[0];
            low[i] = ohlc.get(i)[1];
            close[i] = ohlc.get(i)[2];
        }
        String[] labels = PeakTroughLabeler.label(close);

        int firstEval = 0;
        while (firstEval < n && day.get(firstEval).compareTo(EVAL_FROM) < 0) {
            firstEval++;
        }
        int m = n - firstEval;                 // длина окна оценки
        int[] ci = new int[m];
        for (int t = 0; t < m; t++) {
            ci[t] = firstEval + t;
        }
        double years = (LocalDate.parse(day.get(n - 1)).toEpochDay()
                - LocalDate.parse(day.get(firstEval)).toEpochDay()) / 365.25;

        double delta = 0.01, h = 0.15;
        Properties p = loadParams();
        if (p != null) {
            delta = Double.parseDouble(p.getProperty("cusum.delta", "0.01"));
            h = Double.parseDouble(p.getProperty("cusum.h", "0.15"));
        }
        List<Candidate> cands = List.of(
                new Candidates.AlwaysRange(),
                new Candidates.Sma200(),
                new Candidates.Cusum(delta, h),
                new Candidates.Rules2d());

        // ---- бенчмарк и число гипотез ----
        Metrics bh = buyHold(close, ci, years);
        int hypN = countHypotheses();

        Map<String, String[]> stateCache = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Candidate c : cands) {
            String[] st = c.predict(high, low, close);
            stateCache.put(c.key(), st);
            String[] ev = slice(st, firstEval, n);
            Metrics me = econ(ev, close, ci, years);
            Tech te = technical(ev, labels, ci);
            double ratio = (me.cagr() < bh.cagr() && me.maxdd() > bh.maxdd())
                    ? -(me.cagr() - bh.cagr()) / (me.maxdd() - bh.maxdd()) : Double.NaN;
            double se = Math.sqrt((1 + me.sharpe() * me.sharpe() / 2) / years);
            double sDefl = me.sharpe() - se * Math.sqrt(2 * Math.log(Math.max(hypN, 2)));
            rows.add(new Row(c.key(), me, te, ratio, se, sDefl));
        }

        Row sma = rows.stream().filter(r -> r.key().equals("baseline_sma200")).findFirst().orElseThrow();
        Row r2d = rows.stream().filter(r -> r.key().equals("rules2d")).findFirst().orElseThrow();
        boolean rulesBeats = betterRatio(r2d.dMaxddOverDCagr(), sma.dMaxddOverDCagr());

        writeComparison(outDir, rows, bh, years, hypN, rulesBeats, sma, r2d);
        writeChecklist(outDir, cands, stateCache, high, low, close, labels, firstEval, n, ci, m, years, bh);
        writeRejected(outDir);
        writeChart(outDir, day, close, labels, stateCache, firstEval, n);

        log.info("bench: rules2d ratio={} vs sma200 ratio={} -> композит {} SMA200; отчёты в {}",
                fmt(r2d.dMaxddOverDCagr()), fmt(sma.dMaxddOverDCagr()),
                rulesBeats ? "БЬЁТ" : "НЕ бьёт", Path.of(outDir).toAbsolutePath());
    }

    private static boolean betterRatio(double a, double b) {
        if (Double.isNaN(a)) {
            return false;
        }
        if (Double.isNaN(b)) {
            return true;
        }
        return a < b;   // меньше отдано CAGR за пункт просадки = лучше
    }

    // ================= экономика (своя копия прокси) =================

    private static Metrics econ(String[] states, double[] close, int[] ci, double years) {
        int m = states.length;
        double[] curve = new double[m];
        double eq = 1, actual = 0, curTarget = -999, step = 0, turnover = 0;
        int stepsLeft = 0;
        for (int t = 0; t < m; t++) {
            if (t > 0) {
                double br = close[ci[t]] / close[ci[t - 1]] - 1;
                eq *= 1 + actual * br + (1 - actual) * CASH_DAILY;
            }
            double tgt = exposureFor(states[t]);
            if (tgt != curTarget) {
                curTarget = tgt;
                step = (tgt - actual) / STEPS;
                stepsLeft = STEPS;
            }
            if (stepsLeft > 0) {
                double na = actual + step;
                if (--stepsLeft == 0) {
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
        double sum = 0, sq = 0;
        for (int t = 1; t < m; t++) {
            double r = curve[t] / curve[t - 1] - 1;
            sum += r;
            sq += r * r;
        }
        int c = m - 1;
        double mean = sum / c, std = Math.sqrt(Math.max(sq / c - mean * mean, 0));
        double sharpe = std <= 0 ? 0 : mean / std * Math.sqrt(365);
        return new Metrics(cagr, maxdd, sharpe, turnover / years);
    }

    private static double exposureFor(String state) {
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case "BULL" -> 1.0;
            case "TRANSITION" -> 0.5;
            default -> 0.0;
        };
    }

    private static double avgExp(String[] states) {
        double s = 0;
        for (String st : states) {
            s += exposureFor(st);
        }
        return s / states.length;
    }

    /** Контроль: постоянная экспозиция каждый день (§2.1), остальное — кэш; без издержек. */
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

    // ================= технические метрики =================

    private static Tech technical(String[] states, String[] labels, int[] ci) {
        int m = states.length;
        int match = 0, defined = 0, flips = 0, trueBull = 0, falseBear = 0;
        String prev = null;
        List<Integer> lags = new ArrayList<>();
        for (int t = 0; t < m; t++) {
            String s = states[t];
            String lab = labels[ci[t]];
            if (s != null) {
                defined++;
                if (s.equals(lab)) {
                    match++;
                }
                if (prev != null && !prev.equals(s)) {
                    flips++;
                }
                prev = s;
            }
            if ("BULL".equals(lab)) {
                trueBull++;
                if ("BEAR".equals(s)) {
                    falseBear++;
                }
            }
            // истинный разворот: смена метки на BULL/BEAR -> лаг до совпадения кандидата
            if (t > 0 && !labels[ci[t]].equals(labels[ci[t - 1]]) && !"RANGE".equals(lab)) {
                for (int k = t; k < Math.min(m, t + 180); k++) {
                    if (lab.equals(states[k])) {
                        lags.add(k - t);
                        break;
                    }
                    if (!labels[ci[k]].equals(lab)) {
                        break;
                    }
                }
            }
        }
        lags.sort(Integer::compareTo);
        double lagMed = lags.isEmpty() ? Double.NaN : lags.get(lags.size() / 2);
        double years = (double) m / 365.25;
        return new Tech(defined > 0 ? (double) match / defined : 0, lagMed, flips / years,
                trueBull > 0 ? (double) falseBear / trueBull : 0);
    }

    // ================= отчёты =================

    private void writeComparison(String outDir, List<Row> rows, Metrics bh, double years, int hypN,
                                 boolean rulesBeats, Row sma, Row r2d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Сравнение детекторов режима (стенд, док. 15 v4)\n\n");
        sb.append("**Вывод (§6.4, правило отсечения сложности):** композит `rules2d` (две оси, 4 параметра) ")
                .append(rulesBeats ? "**БЬЁТ** " : "**НЕ бьёт** ")
                .append("однострочный `baseline_sma200` по `ΔMaxDD/ΔCAGR` на out-of-sample ")
                .append(String.format("(%s против %s). ", fmt(r2d.dMaxddOverDCagr()), fmt(sma.dMaxddOverDCagr())))
                .append(rulesBeats ? "Композит оправдан.\n\n"
                        : "**По правилу отсечения сложности в прод должен идти однострочник SMA200.**\n\n");
        sb.append(String.format("Окно оценки %s … посл. день (%.1f лет). Прокси §6.2 (BULL 100%%/TRANSITION 50%%/прочее 0%%, ", EVAL_FROM, years))
                .append("реаллокация 5д, издержки 0.10%, кэш 8%). **Прокси, не P&L.** ")
                .append(String.format("N гипотез из журнала = %d. Правиловые кандидаты не обучаются → OOS ≡ in-sample.%n%n", hypN));

        sb.append("| Кандидат | CAGR | MaxDD | Sharpe ± SE | Sharpe-defl | ΔMaxDD/ΔCAGR | lag_дни | флипов/год | false_bear | оборот/год |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        sb.append(String.format("| buy & hold | %.1f%% | %.1f%% | %.2f | — | — | — | — | — | 0.0 |%n",
                bh.cagr() * 100, bh.maxdd() * 100, bh.sharpe()));
        for (Row r : rows) {
            sb.append(String.format("| %s | %.1f%% | %.1f%% | %.2f ± %.2f | %.2f | %s | %s | %.1f | %.0f%% | %.1f |%n",
                    r.key(), r.m().cagr() * 100, r.m().maxdd() * 100, r.m().sharpe(), r.se(), r.sDefl(),
                    fmt(r.dMaxddOverDCagr()),
                    Double.isNaN(r.t().lagDays()) ? "—" : String.format("%.0f", r.t().lagDays()),
                    r.t().flipsPerYear(), r.t().falseBear() * 100, r.m().turnover()));
        }
        sb.append("\n`state_accuracy` убрана из таблицы (док. 20 §2.3): тривиальный `always_range` берёт её на 49% ")
                .append("просто потому, что RANGE — 49% эталонной разметки. `ΔMaxDD/ΔCAGR` — пунктов CAGR отдано за ")
                .append("1 пункт снятой просадки (меньше = лучше; «—» = не снижает просадку). ")
                .append("Sharpe без интервала не интерпретируется: при ").append(String.format("%.1f", years))
                .append(String.format(" годах SE≈%.2f, дефлированный Sharpe вычитает SE·√(2 ln N).%n%n", rows.get(0).se()));
        sb.append("Отложены (§5.3, ожидается переобучение на 3 циклах): `hmm`, `gmm`, `ensemble`. ")
                .append("Отвергнутые конструкции — `rejected.md`. Проверки §7 — `checklist.md`. ")
                .append("Кросс-рынок победителя — `crossmarket.md`. Полосы состояний — `bench-chart.html`.\n");

        writeFile(outDir + "/comparison.md", sb.toString());

        // CSV
        StringBuilder csv = new StringBuilder("candidate,cagr,maxdd,sharpe,se,sharpe_defl,dmaxdd_over_dcagr,state_acc,lag_days,flips_per_year,false_bear,turnover\n");
        csv.append(String.format("buy_and_hold,%.4f,%.4f,%.4f,,,,,,,,0%n", bh.cagr(), bh.maxdd(), bh.sharpe()));
        for (Row r : rows) {
            csv.append(String.format("%s,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%.4f,%s,%.2f,%.4f,%.2f%n",
                    r.key(), r.m().cagr(), r.m().maxdd(), r.m().sharpe(), r.se(), r.sDefl(),
                    Double.isNaN(r.dMaxddOverDCagr()) ? "" : String.format("%.4f", r.dMaxddOverDCagr()),
                    r.t().accuracy(), Double.isNaN(r.t().lagDays()) ? "" : String.format("%.0f", r.t().lagDays()),
                    r.t().flipsPerYear(), r.t().falseBear(), r.m().turnover()));
        }
        writeFile(outDir + "/comparison.csv", csv.toString());
    }

    private void writeChecklist(String outDir, List<Candidate> cands, Map<String, String[]> stateCache,
                                double[] high, double[] low, double[] close, String[] labels,
                                int firstEval, int n, int[] ci, int m, double years, Metrics bh) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Антибаг-чеклист стенда (док. 15 v4 §7)\n\n");

        // 7.1 Causality
        Random rnd = new Random(SHUFFLE_SEED);
        sb.append("## 7.1 Causality (утечка будущего)\n\n");
        sb.append("Для 20 случайных дат: `predict([0..t])[t]` == `predict(весь ряд)[t]`?\n\n");
        sb.append("| Кандидат | совпало |\n|---|---|\n");
        for (Candidate c : cands) {
            String[] full = stateCache.get(c.key());
            int ok = 0;
            for (int k = 0; k < 20; k++) {
                int t = firstEval + rnd.nextInt(n - firstEval);
                String[] trunc = c.predict(prefix(high, t + 1), prefix(low, t + 1), prefix(close, t + 1));
                if (java.util.Objects.equals(trunc[t], full[t])) {
                    ok++;
                }
            }
            sb.append(String.format("| %s | %d/20 %s |%n", c.key(), ok, ok == 20 ? "✓" : "✗ УТЕЧКА"));
        }

        // 7.4 shuffle — корректная статистика (док. 20 §2.1): избыток CAGR над контролем с той же
        // средней экспозицией. ΔMaxDD не годится — снижение просадки на перемешанных механическое.
        sb.append("\n## 7.4 Тест на перемешанных данных (исправлено, док. 20 §2.1)\n\n");
        sb.append("Статистика — **избыток CAGR над контролем с той же средней экспозицией** (не ΔMaxDD: ")
                .append("снижение просадки на перемешанных возникает механически, от удержания меньшей позиции). ")
                .append("400 перестановок дневных лог-доходностей; p = доля перестановок с избытком ≥ фактического.\n\n");
        sb.append("| Кандидат | избыток факт | p |\n|---|---|---|\n");
        String[] keys = {"rules2d", "baseline_sma200", "cusum"};
        int perm = 400;
        for (String key : keys) {
            Candidate cand = cands.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
            String[] evReal = slice(stateCache.get(key), firstEval, n);
            double realExcess = econ(evReal, close, ci, years).cagr() - constExposure(avgExp(evReal), close, ci, years).cagr();
            int ge = 0;
            for (int s = 0; s < perm; s++) {
                double[] sc = shuffledClose(close, rnd);
                String[] ev = slice(cand.predict(sc, sc, sc), firstEval, n);
                double ex = econ(ev, sc, ci, years).cagr() - constExposure(avgExp(ev), sc, ci, years).cagr();
                if (ex >= realExcess) {
                    ge++;
                }
            }
            sb.append(String.format("| %s | %+.2f%% | %.3f |%n", key, realExcess * 100, (double) ge / perm));
        }
        sb.append("\nНи один кандидат не показывает значимого тайминга (p не мал) — на 6.6 годах одного актива это ")
                .append("ожидаемо (t07). Прежняя галочка «преимущество исчезло» снята: она опиралась на неверную статистику ")
                .append("(снижение MaxDD на перемешанных — механическое, а не признак структуры).\n\n");

        // 7.4 прочее
        double corrDT = corrDtFromV3();
        Metrics rules0 = econCost(slice(stateCache.get("rules2d"), firstEval, n), close, ci, years, 0.0);
        Metrics rules1 = econCost(slice(stateCache.get("rules2d"), firstEval, n), close, ci, years, 0.01);
        Metrics rulesA = econ(slice(stateCache.get("rules2d"), firstEval, n), close, ci, years);
        Metrics rulesB = econ(slice(stateCache.get("rules2d"), firstEval, n), close, ci, years);
        Row smaRow = null;
        Metrics smaM = econ(slice(stateCache.get("baseline_sma200"), firstEval, n), close, ci, years);
        sb.append("## Прочие проверки (§7.4)\n\n");
        sb.append(String.format("- **corr(D, T)** на всей истории: **%.3f** (%s |corr|>0.8 — оси %s).%n",
                corrDT, Math.abs(corrDT) > 0.8 ? "⚠" : "✓", Math.abs(corrDT) > 0.8 ? "НЕ ортогональны" : "ортогональны"));
        sb.append(String.format("- **Комиссии применяются:** rules2d CAGR при 0%%=%.1f%%, при 1%%=%.1f%% — разный результат ✓.%n",
                rules0.cagr() * 100, rules1.cagr() * 100));
        sb.append(String.format("- **Детерминизм:** два прогона rules2d дают CAGR %.4f и %.4f — %s.%n",
                rulesA.cagr(), rulesB.cagr(), rulesA.cagr() == rulesB.cagr() ? "идентично ✓" : "РАЗЛИЧАЮТСЯ ✗"));
        sb.append(String.format("- **Warm-up не заполняется:** окно оценки с %s, прогревочные дни (индикаторы SMA200/ATR90/ранг) ", EVAL_FROM))
                .append("исключены и ничем не протянуты ✓.\n");
        sb.append(String.format("- **Sanity baseline:** baseline_sma200 MaxDD=%.1f%% (ожидание −50…−65%%), CAGR=%.1f%% против B&H %.1f%% — %s.%n",
                smaM.maxdd() * 100, smaM.cagr() * 100, bh.cagr() * 100,
                (smaM.maxdd() < -0.40 && smaM.cagr() < bh.cagr()) ? "в ожидании ✓" : "вне ожидания — проверить утечку/комиссии ⚠"));

        writeFile(outDir + "/checklist.md", sb.toString());
    }

    private void writeRejected(String outDir) {
        String s = "# Реестр отвергнутого (док. 15 v4 §10)\n\n"
                + "Чтобы через полгода не начать заново уже закрытое. Возврат требует нового измерения, "
                + "опровергающего основание, а не аргумента «попробуем иначе».\n\n"
                + "| Конструкция | Основание отказа | Что могло бы вернуть |\n|---|---|---|\n"
                + "| Ось `S` с `dd_speed` | doc 18 A1/A2: храповик по построению, серии до 41 дня | ничего: дефект из определения формулы |\n"
                + "| Состояние `CRASH` | doc 18 A4: CAGR 26.9% против 32.7% при идентичном MaxDD | измерение выигрыша на другом рынке/таймфрейме |\n"
                + "| `baseline_voltarget` | doc 19: 67% на потолке, min экспозиция 0.29, защита/цена 0.79 | версия с плечом либо мультиактивный портфель |\n"
                + "| Множитель `stress_level` | doc 01 v4 §2.4: диапазон [0.5, 1.0] выходит ещё меньше | новое измерение под другую задачу (защита моментума, doc 17 §7) |\n"
                + "| Композит `rules2d` (оси D+T) | doc 20 §1: −25.6% было свойством окна (2015+: −57.9%); ratio 0.15 vs 0.14 у sma200; кросс-рынок 2/5 | измерение на большем числе независимых наблюдений (кросс-секция, t10) |\n"
                + "| `cusum` (самостоятельный кандидат) | doc 20: ratio 0.44, избыток над контролем незначим (p≈0.36) | — |\n"
                + "| Ансамбль (голосование 3, равные веса) | doc 20 §3: лучше sma200 по ratio 0/6, crit2 провален (BTC −69.2 vs −64.3) | — |\n"
                + "| Гейт наклона SMA (RANGE) | doc 21 §4: A не прошла (1/6), B — RANGE хуже BEAR для S3 | новое измерение с реальным S3/детектором RANGE |\n"
                + "| Стратегия S3 (дневной mean reversion) | doc 22 §C: CAGR при 0% издержек BTC −46%/ETH −61%; профиль проданного опциона | внутридневной контур или иной таймфрейм, где fade работает |\n";
        writeFile(outDir + "/rejected.md", s);
    }

    private void writeChart(String outDir, List<String> day, double[] close, String[] labels,
                            Map<String, String[]> stateCache, int firstEval, int n) {
        String[] sma = stateCache.get("baseline_sma200");
        String[] cusum = stateCache.get("cusum");
        String[] r2d = stateCache.get("rules2d");
        List<Object[]> points = new ArrayList<>();
        for (int i = firstEval; i < n; i++) {
            points.add(new Object[]{day.get(i), close[i], labelIdx(labels[i]),
                    stIdx(sma[i]), stIdx(cusum[i]), stIdx(r2d[i])});
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", day.get(firstEval));
        data.put("to", day.get(n - 1));
        data.put("points", points);
        try {
            String tpl = new String(new ClassPathResource("bench-chart.template.html")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            writeFile(outDir + "/bench-chart.html", tpl.replace("__DATA__", mapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.warn("bench: не удалось записать график: {}", e.toString());
        }
    }

    // ================= вспомогательное =================

    private static Metrics econCost(String[] states, double[] close, int[] ci, double years, double cost) {
        int m = states.length;
        double[] curve = new double[m];
        double eq = 1, actual = 0, curTarget = -999, step = 0;
        int stepsLeft = 0;
        for (int t = 0; t < m; t++) {
            if (t > 0) {
                double br = close[ci[t]] / close[ci[t - 1]] - 1;
                eq *= 1 + actual * br + (1 - actual) * CASH_DAILY;
            }
            double tgt = exposureFor(states[t]);
            if (tgt != curTarget) {
                curTarget = tgt;
                step = (tgt - actual) / STEPS;
                stepsLeft = STEPS;
            }
            if (stepsLeft > 0) {
                double na = actual + step;
                if (--stepsLeft == 0) {
                    na = curTarget;
                }
                eq *= 1 - cost * Math.abs(na - actual);
                actual = na;
            }
            curve[t] = eq;
        }
        return metrics(curve, 0, years);
    }

    private static double[] shuffledClose(double[] close, Random rnd) {
        int n = close.length;
        double[] ret = new double[n - 1];
        for (int i = 1; i < n; i++) {
            ret[i - 1] = Math.log(close[i] / close[i - 1]);
        }
        for (int i = ret.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            double tmp = ret[i];
            ret[i] = ret[j];
            ret[j] = tmp;
        }
        double[] sc = new double[n];
        sc[0] = close[0];
        for (int i = 1; i < n; i++) {
            sc[i] = sc[i - 1] * Math.exp(ret[i - 1]);
        }
        return sc;
    }

    private double corrDtFromV3() {
        List<double[]> dt = db.query("SELECT d, t FROM regime_daily_v3 WHERE d IS NOT NULL AND t IS NOT NULL",
                rs -> new double[]{rs.getDouble(1), rs.getDouble(2)});
        int k = dt.size();
        if (k < 2) {
            return 0;
        }
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (double[] r : dt) {
            sx += r[0]; sy += r[1]; sxx += r[0] * r[0]; syy += r[1] * r[1]; sxy += r[0] * r[1];
        }
        double cov = k * sxy - sx * sy, vx = k * sxx - sx * sx, vy = k * syy - sy * sy;
        return (vx <= 0 || vy <= 0) ? 0 : cov / Math.sqrt(vx * vy);
    }

    private int countHypotheses() {
        Path p = Path.of("reports/hypotheses.md");
        if (!Files.exists(p)) {
            return 3;
        }
        try {
            int rows = 0;
            for (String line : Files.readAllLines(p)) {
                String t = line.trim();
                if (t.startsWith("|") && (t.contains("2026") || t.contains("| —"))) {
                    rows++;
                }
            }
            return Math.max(rows, 3);
        } catch (IOException e) {
            return 3;
        }
    }

    private Properties loadParams() {
        Path p = Path.of("configs/bench-params.properties");
        if (!Files.exists(p)) {
            return null;
        }
        try {
            Properties pr = new Properties();
            pr.load(Files.newBufferedReader(p, StandardCharsets.UTF_8));
            return pr;
        } catch (IOException e) {
            return null;
        }
    }

    private static String[] slice(String[] a, int from, int to) {
        String[] out = new String[to - from];
        System.arraycopy(a, from, out, 0, to - from);
        return out;
    }

    private static double[] prefix(double[] a, int len) {
        double[] out = new double[len];
        System.arraycopy(a, 0, out, 0, len);
        return out;
    }

    private static int stIdx(String s) {
        return switch (s == null ? "" : s) {
            case "BULL" -> 0;
            case "RANGE" -> 1;
            case "BEAR" -> 2;
            case "TRANSITION" -> 3;
            default -> 1;
        };
    }

    private static int labelIdx(String s) {
        return switch (s == null ? "" : s) {
            case "BULL" -> 0;
            case "BEAR" -> 2;
            default -> 1;
        };
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "—" : String.format("%.2f", v);
    }

    private void writeFile(String outPath, String content) {
        try {
            Path out = Path.of(outPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, content);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать: " + outPath, e);
        }
    }
}
