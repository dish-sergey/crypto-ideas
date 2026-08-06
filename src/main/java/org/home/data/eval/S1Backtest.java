package org.home.data.eval;

import org.home.data.core.Db;
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
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Бэктест S1 funding-арбитраж (doc 24 §F). Дельта-нейтраль: лонг спот + шорт перп равного
 * номинала; доход — funding-платежи шортам (шорт получает rate&gt;0, платит rate&lt;0). Экспозиция
 * к цене ≈ 0, поэтому доходность = кумулятив funding-ставок (на 1x номинала; плечо — множитель
 * доходности и риска, §F5). <b>Тривиальный бенчмарк первым (§F3):</b> постоянная пара BTC без
 * ротации/отбора/выходов. Полная S1 (отбор по EMA7(funding), ротация, выход в кэш) сравнивается
 * с ней — если не превосходит после издержек, в прод идёт постоянная пара. CLI: --report=s1.
 */
@Component
public class S1Backtest {

    private static final Logger log = LoggerFactory.getLogger(S1Backtest.class);

    private static final String[] SYMS = {"BTCUSDT", "ETHUSDT", "XRPUSDT", "BNBUSDT", "SOLUSDT"};
    private static final double SWITCH_COST = 0.002;   // 4 ноги комиссий + спред ≈ 0.2% за переключение
    private static final int AMORT = 14;               // амортизация издержки переключения в скоре
    private static final double ROT_THRESH = 1.30;     // ротация при превышении текущего скора на ≥30%
    private static final int EXIT_NEG_DAYS = 3;        // выход в кэш при EMA7(funding)<0 три дня подряд
    private static final int EMA_N = 7;

    private final Db db;

    public S1Backtest(Db db) {
        this.db = db;
    }

    private record FRow(String symbol, LocalDate day, double rate) {}

    public void run(String outPath) {
        // суточный funding по символам (сумма 8ч-ставок за день)
        Map<String, Map<LocalDate, Double>> daily = new HashMap<>();
        for (String s : SYMS) {
            daily.put(s, new TreeMap<>());
        }
        List<FRow> rows = db.query(
                "SELECT symbol, date(funding_time/1000,'unixepoch') d, rate FROM funding "
                        + "WHERE exchange='binance' AND symbol IN ('BTCUSDT','ETHUSDT','XRPUSDT','BNBUSDT','SOLUSDT') "
                        + "ORDER BY funding_time",
                rs -> new FRow(rs.getString(1), LocalDate.parse(rs.getString(2)), rs.getDouble(3)));
        TreeSet<LocalDate> allDays = new TreeSet<>();
        for (FRow r : rows) {
            daily.get(r.symbol()).merge(r.day(), r.rate(), Double::sum);
            allDays.add(r.day());
        }
        List<LocalDate> dates = new ArrayList<>(allDays);
        int n = dates.size();

        // выровненные ряды суточного funding + EMA7 по каждому символу
        Map<String, double[]> f = new HashMap<>(), ema = new HashMap<>();
        for (String s : SYMS) {
            double[] fs = new double[n];
            double[] es = new double[n];
            java.util.Arrays.fill(fs, Double.NaN);
            java.util.Arrays.fill(es, Double.NaN);
            double e = Double.NaN;
            double k = 2.0 / (EMA_N + 1);
            for (int i = 0; i < n; i++) {
                Double v = daily.get(s).get(dates.get(i));
                if (v != null) {
                    fs[i] = v;
                    e = Double.isNaN(e) ? v : e + k * (v - e);
                    es[i] = e;
                }
            }
            f.put(s, fs);
            ema.put(s, es);
        }

        // BTC для цены (риск ликвидации шорта) — макс. дневной рост
        Map<LocalDate, Double> btcClose = new TreeMap<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { btcClose.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2)); return null; });

        double years = (dates.get(n - 1).toEpochDay() - dates.get(0).toEpochDay()) / 365.25;

        // --- F3: постоянная пара BTC (бенчмарк) ---
        double[] btcF = f.get("BTCUSDT");
        double[] constEq = new double[n];
        double eq = 1;
        int posDays = 0, defDays = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(btcF[i])) {
                eq *= 1 + btcF[i];
                defDays++;
                if (btcF[i] > 0) {
                    posDays++;
                }
            }
            constEq[i] = eq;
        }

        // --- полная S1: отбор по EMA7 − амортизированная издержка, ротация, выход в кэш ---
        double[] rotEq = new double[n];
        double re=1; String cur = null; int negStreak = 0, switches = 0; double costPaid = 0, gross = 0;
        for (int i = 0; i < n; i++) {
            // доход за день по текущему держанию (funding вчерашней пары)
            if (cur != null && !Double.isNaN(f.get(cur)[i])) {
                double d = f.get(cur)[i];
                re *= 1 + d;
                gross += d;
            }
            // решение на конец дня i (по данным ≤ i)
            String best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (String s : SYMS) {
                double e = ema.get(s)[i];
                if (Double.isNaN(e)) {
                    continue;
                }
                double score = e - SWITCH_COST / AMORT;   // амортизированная издержка в скоре
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            double curEma = cur == null ? Double.NEGATIVE_INFINITY : ema.get(cur)[i];
            negStreak = (cur != null && curEma < 0) ? negStreak + 1 : 0;
            boolean exitCash = cur != null && negStreak >= EXIT_NEG_DAYS;
            if (exitCash) {
                cur = null;
                negStreak = 0;
            } else if (cur == null && best != null && bestScore > 0) {
                cur = best;
                re *= 1 - SWITCH_COST;
                switches++;
                costPaid += SWITCH_COST;
            } else if (cur != null && best != null && !best.equals(cur)
                    && bestScore > curEma * ROT_THRESH && bestScore > 0) {
                cur = best;
                re *= 1 - SWITCH_COST;
                switches++;
                costPaid += SWITCH_COST;
            }
            rotEq[i] = re;
        }

        // максимальный дневной рост BTC (адверсно шорту)
        double maxUp = 0;
        List<LocalDate> bd = new ArrayList<>(btcClose.keySet());
        Double[] bc = btcClose.values().toArray(new Double[0]);
        for (int i = 1; i < bc.length; i++) {
            maxUp = Math.max(maxUp, bc[i] / bc[i - 1] - 1);
        }

        // ---- отчёт ----
        StringBuilder sb = new StringBuilder();
        sb.append("# S1 funding-арбитраж — бэктест (doc 24 §F)\n\n");
        sb.append("Дельта-нейтраль: лонг спот + шорт перп равного номинала, доход = funding шортам. ")
                .append("Доходность на **1x номинала** (плечо 2x/3x — множитель дохода и риска, §F5). Данные: Binance ")
                .append("funding 8ч, 5 перпов (BTC/ETH/XRP/BNB/SOL). Окно ").append(dates.get(0)).append(" … ")
                .append(dates.get(n - 1)).append(String.format(" (%.1f лет).%n%n", years));

        sb.append("## F3. Тривиальный бенчмарк первым — постоянная пара BTC\n\n");
        Metrics cm = metrics(constEq, years);
        sb.append(String.format("| CAGR | MaxDD | Sharpe ± SE | доля дней funding>0 |%n|---|---|---|---|%n"));
        sb.append(String.format("| **%.1f%%** | %.1f%% | %.2f ± %.2f | %.0f%% |%n%n",
                cm.cagr * 100, cm.maxdd * 100, cm.sharpe, cm.se, 100.0 * posDays / Math.max(defDays, 1)));

        sb.append("## Полная S1 (отбор EMA7 + ротация + выход в кэш) против бенчмарка\n\n");
        Metrics rm = metrics(rotEq, years);
        sb.append("| Конфигурация | CAGR | MaxDD | Sharpe ± SE | переключений/год | издержки/брутто |\n|---|---|---|---|---|---|\n");
        sb.append(String.format("| постоянная BTC | %.1f%% | %.1f%% | %.2f ± %.2f | 0 | 0%% |%n",
                cm.cagr * 100, cm.maxdd * 100, cm.sharpe, cm.se));
        sb.append(String.format("| ротация 5 перпов | %.1f%% | %.1f%% | %.2f ± %.2f | %.1f | %.0f%% |%n%n",
                rm.cagr * 100, rm.maxdd * 100, rm.sharpe, rm.se, switches / years,
                gross > 0 ? 100 * costPaid / gross : 0));
        boolean rotWins = rm.cagr > cm.cagr;
        sb.append(String.format("**§F3-вывод: ротация %s постоянную пару BTC по CAGR (%.1f%% vs %.1f%%). %s**%n%n",
                rotWins ? "БЬЁТ" : "НЕ бьёт", rm.cagr * 100, cm.cagr * 100,
                rotWins ? "Отбор оправдан — но проверить устойчивость."
                        : "Отбор+ротация не окупают сложность → в прод ПОСТОЯННАЯ пара BTC (§F3)."));

        // подпериоды
        sb.append("## F4. По подпериодам (постоянная BTC, funding-доход)\n\n");
        sb.append("| Период | CAGR | доля дней funding>0 |\n|---|---|---|\n");
        subperiod(sb, "2019–2020", dates, btcF, "2021-01-01");
        subperiod(sb, "2021–2022", dates, btcF, "2023-01-01", "2021-01-01");
        subperiod(sb, "2023–2026", dates, btcF, "2027-01-01", "2023-01-01");

        // худший месяц
        double worstMonth = worstMonth(dates, btcF);
        sb.append(String.format("%nХудший месяц (постоянная BTC): **%.2f%%**.%n%n", worstMonth * 100));

        sb.append("## F5. Специфические риски\n\n");
        sb.append(String.format("- **Отрицательный funding:** доля дней EMA7(BTC funding)<0 — по годам см. F4; в медвежьи периоды S1 простаивает (выход в кэш).%n"));
        sb.append(String.format("- **Ликвидация шорта на росте:** макс. дневной рост BTC за окно = **%+.1f%%**. При 2x ликвидация ~+50%%, при 3x ~+33%% — %s.%n",
                maxUp * 100, maxUp > 0.33 ? "3x РИСКОВАН (был день, близкий к порогу)" : "в пределах 2x/3x на дневном масштабе"));
        sb.append(String.format("- **Стоимость ротации:** %.0f%% брутто-funding ушло в издержки переключений.%n", gross > 0 ? 100 * costPaid / gross : 0));
        sb.append(String.format("- **Базис спот-перп:** не моделировался (нет ряда spot-mark) — оговорка; на держании funding его учитывает косвенно.%n%n"));

        sb.append("**Первой строкой:** funding задним числом не пересматривается (реальные платежи), поэтому look-ahead тут нет; ")
                .append("но выборка — те же ~6.6 лет BTC, и доля времени с положительным funding определяет, сколько S1 вообще работает. ")
                .append(String.format("Ожидание §F (10–30%%/год на капитал 1x): факт постоянной BTC = %.1f%%/год — %s.%n",
                        cm.cagr * 100, (cm.cagr >= 0.10 && cm.cagr <= 0.35) ? "в коридоре ожидания"
                                : cm.cagr > 0.35 ? "ВЫШЕ коридора — проверить" : "ниже коридора"));

        writeFile(outPath, sb.toString());
        log.info("s1: постоянная BTC CAGR={}%, ротация={}% ({}), -> {}",
                String.format("%.1f", cm.cagr * 100), String.format("%.1f", rm.cagr * 100),
                rotWins ? "ротация бьёт" : "постоянная лучше", Path.of(outPath).toAbsolutePath());
    }

    private record Metrics(double cagr, double maxdd, double sharpe, double se) {}

    private static Metrics metrics(double[] eq, double years) {
        int n = eq.length;
        double cagr = Math.pow(Math.max(eq[n - 1], 1e-9), 1.0 / years) - 1;
        double peak = eq[0], mdd = 0;
        for (double v : eq) {
            peak = Math.max(peak, v);
            mdd = Math.min(mdd, v / peak - 1);
        }
        double sum = 0, sq = 0;
        int c = 0;
        for (int i = 1; i < n; i++) {
            double r = eq[i] / eq[i - 1] - 1;
            sum += r;
            sq += r * r;
            c++;
        }
        double m = sum / c, std = Math.sqrt(Math.max(sq / c - m * m, 0));
        double sharpe = std <= 0 ? 0 : m / std * Math.sqrt(365);
        double se = Math.sqrt((1 + sharpe * sharpe / 2) / years);
        return new Metrics(cagr, mdd, sharpe, se);
    }

    private static void subperiod(StringBuilder sb, String label, List<LocalDate> dates, double[] btcF, String to) {
        subperiod(sb, label, dates, btcF, to, "2000-01-01");
    }

    private static void subperiod(StringBuilder sb, String label, List<LocalDate> dates, double[] btcF, String to, String from) {
        LocalDate a = LocalDate.parse(from), b = LocalDate.parse(to);
        double eq = 1;
        int pos = 0, def = 0;
        LocalDate first = null, last = null;
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            if (d.isBefore(a) || !d.isBefore(b) || Double.isNaN(btcF[i])) {
                continue;
            }
            eq *= 1 + btcF[i];
            def++;
            if (btcF[i] > 0) {
                pos++;
            }
            if (first == null) {
                first = d;
            }
            last = d;
        }
        if (def == 0) {
            sb.append(String.format("| %s | нет данных | — |%n", label));
            return;
        }
        double yrs = (last.toEpochDay() - first.toEpochDay()) / 365.25;
        double cagr = yrs > 0.1 ? Math.pow(eq, 1.0 / yrs) - 1 : eq - 1;
        sb.append(String.format("| %s | %.1f%% | %.0f%% |%n", label, cagr * 100, 100.0 * pos / def));
    }

    private static double worstMonth(List<LocalDate> dates, double[] btcF) {
        Map<String, Double> m = new TreeMap<>();
        for (int i = 0; i < dates.size(); i++) {
            if (Double.isNaN(btcF[i])) {
                continue;
            }
            String key = dates.get(i).toString().substring(0, 7);
            m.merge(key, Math.log(1 + btcF[i]), Double::sum);
        }
        double worst = 0;
        for (double v : m.values()) {
            worst = Math.min(worst, Math.exp(v) - 1);
        }
        return worst;
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
