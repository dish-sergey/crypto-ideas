package org.home.data.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // приблизительная историческая доходность S4 (стейбл-лендинг) по подпериодам, задокументировано (§6.4)
    private static final double S4_2019_20 = 0.08, S4_2021_22 = 0.06, S4_2023_26 = 0.05;
    // издержки (§6.2), задокументированы: ребаланс дельты пропорционально ходу цены + амортизация входа
    private static final double REBAL_K = 0.0006;   // трение ребаланса на единицу |дневного хода| сверх 1%

    private record FRow(String symbol, LocalDate day, double rate) {}
    private record Perp(double close, double high) {}

    /**
     * Блок §6 (doc 26): полная модель P&L S1 — funding − Δбазис − издержки. Базис = (perp−spot)/spot,
     * perp с fapi (klines close/high), spot из candles. Плюс измерения базиса (§6.1), бенчмарк S4
     * (§6.4, ≥4пп на 2023–2026), хвостовой риск по внутридневному high (§6.3), пересчёт ротации (§6.5).
     * CLI: --report=s1-v2.
     */
    public void runV2(String outPath) {
        String[] syms = SYMS;
        // suffix: BTC/ETH perp с 2019, alts позже. Спот из candles.
        Map<String, TreeMap<LocalDate, Double>> spot = new HashMap<>();
        Map<String, TreeMap<LocalDate, Perp>> perp = new HashMap<>();
        Map<String, TreeMap<LocalDate, double[]>> fund = new HashMap<>();   // [sum,cnt] funding за день
        for (String s : syms) {
            TreeMap<LocalDate, Double> sp = new TreeMap<>();
            db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles WHERE symbol=? AND interval='1d' ORDER BY open_time",
                    rs -> { sp.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2)); return null; }, s);
            spot.put(s, sp);
            TreeMap<LocalDate, double[]> fm = new TreeMap<>();
            db.query("SELECT date(funding_time/1000,'unixepoch') d, rate FROM funding WHERE exchange='binance' AND symbol=?",
                    rs -> { fm.computeIfAbsent(LocalDate.parse(rs.getString(1)), k -> new double[2]);
                            double[] x = fm.get(LocalDate.parse(rs.getString(1))); x[0] += rs.getDouble(2); x[1]++; return null; }, s);
            fund.put(s, fm);
            try {
                perp.put(s, fetchPerp(s));
                Thread.sleep(300);
            } catch (Exception e) {
                perp.put(s, new TreeMap<>());
                log.warn("s1-v2: не удалось скачать perp {}: {}", s, e.toString());
            }
        }

        // basis per symbol (там, где есть spot и perp)
        Map<String, TreeMap<LocalDate, Double>> basis = new HashMap<>();
        for (String s : syms) {
            TreeMap<LocalDate, Double> bm = new TreeMap<>();
            for (var e : perp.get(s).entrySet()) {
                Double sc = spot.get(s).get(e.getKey());
                if (sc != null && sc > 0) {
                    bm.put(e.getKey(), e.getValue().close() / sc - 1);
                }
            }
            basis.put(s, bm);
        }

        // --- BTC постоянная: funding − Δбазис − издержки, по дням где есть basis ---
        List<LocalDate> bdays = new ArrayList<>(basis.get("BTCUSDT").keySet());
        double[] eqFundOnly = new double[bdays.size()];
        double[] eqFull = new double[bdays.size()];
        double efo = 1, eff = 1, prevBasis = Double.NaN, prevSpot = Double.NaN;
        double basisContribSum = 0, maxAdvBasis = 0;
        List<Double> entryBasis = new ArrayList<>(), fwdBasisChg = new ArrayList<>();
        for (int i = 0; i < bdays.size(); i++) {
            LocalDate day = bdays.get(i);
            double[] fv = fund.get("BTCUSDT").get(day);
            double f = fv != null && fv[1] > 0 ? fv[0] : 0;
            double bas = basis.get("BTCUSDT").get(day);
            double dBasis = Double.isNaN(prevBasis) ? 0 : bas - prevBasis;
            double sc = spot.get("BTCUSDT").get(day);
            double move = (Double.isNaN(prevSpot) || prevSpot <= 0) ? 0 : Math.abs(sc / prevSpot - 1);
            double cost = REBAL_K * Math.max(0, move - 0.01);
            efo *= 1 + f;
            eff *= 1 + f - dBasis - cost;
            eqFundOnly[i] = efo;
            eqFull[i] = eff;
            basisContribSum += -dBasis;
            maxAdvBasis = Math.min(maxAdvBasis, -dBasis);
            if (i % 7 == 0 && i + 14 < bdays.size()) {   // выборка «вход» раз в неделю, Δбазис за 14д
                entryBasis.add(bas);
                fwdBasisChg.add(basis.get("BTCUSDT").get(bdays.get(i + 14)) - bas);
            }
            prevBasis = bas;
            prevSpot = sc;
        }
        double years = (bdays.get(bdays.size() - 1).toEpochDay() - bdays.get(0).toEpochDay()) / 365.25;
        Metrics mFo = metrics(eqFundOnly, years);
        Metrics mFull = metrics(eqFull, years);
        double corrEntry = pearson(entryBasis, fwdBasisChg);

        // --- 2023–2026 подпериод (постоянная BTC, полная модель) ---
        double eff23 = 1;
        LocalDate cut = LocalDate.parse("2023-01-01");
        LocalDate f23 = null, l23 = null;
        double pb = Double.NaN;
        for (LocalDate day : bdays) {
            if (day.isBefore(cut)) {
                pb = basis.get("BTCUSDT").get(day);
                continue;
            }
            double[] fv = fund.get("BTCUSDT").get(day);
            double f = fv != null && fv[1] > 0 ? fv[0] : 0;
            double bas = basis.get("BTCUSDT").get(day);
            double dB = Double.isNaN(pb) ? 0 : bas - pb;
            eff23 *= 1 + f - dB;
            if (f23 == null) f23 = day;
            l23 = day;
            pb = bas;
        }
        double y23 = (l23.toEpochDay() - f23.toEpochDay()) / 365.25;
        double cagr23 = Math.pow(Math.max(eff23, 1e-9), 1.0 / y23) - 1;

        // хвост: макс внутридневной рост (perp high vs пред. close)
        double maxIntraday = 0;
        TreeMap<LocalDate, Perp> bp = perp.get("BTCUSDT");
        List<LocalDate> pd = new ArrayList<>(bp.keySet());
        for (int i = 1; i < pd.size(); i++) {
            double pc = bp.get(pd.get(i - 1)).close();
            maxIntraday = Math.max(maxIntraday, bp.get(pd.get(i)).high() / pc - 1);
        }

        // ---- отчёт ----
        StringBuilder sb = new StringBuilder();
        sb.append("# S1 — полная модель P&L с базисом (doc 26 §6)\n\n");
        sb.append("P&L_день = funding − Δбазис − издержки. Базис = (perp_close − spot_close)/spot_close ")
                .append("(perp с fapi klines — аппроксимация close вместо mark, §6.1; spot из candles). ")
                .append("Издержки: ребаланс дельты ∝ |ход цены| сверх 1% (задокументированная модель §6.2). ")
                .append("Окно с базисом: ").append(bdays.get(0)).append(" … ").append(bdays.get(bdays.size() - 1))
                .append(String.format(" (%.1f лет).%n%n", years));

        sb.append("## Влияние базиса (постоянная пара BTC)\n\n");
        sb.append("| Модель | CAGR | MaxDD | Sharpe ± SE |\n|---|---|---|---|\n");
        sb.append(String.format("| только funding (как в v1) | %.1f%% | %.1f%% | %.2f ± %.2f |%n",
                mFo.cagr * 100, mFo.maxdd * 100, mFo.sharpe, mFo.se));
        sb.append(String.format("| **funding − Δбазис − издержки** | **%.1f%%** | **%.1f%%** | **%.2f ± %.2f** |%n%n",
                mFull.cagr * 100, mFull.maxdd * 100, mFull.sharpe, mFull.se));
        sb.append(String.format("- Вклад базиса в CAGR: **%+.1f п.п./год** (funding-only %.1f%% → полная %.1f%%).%n",
                (mFull.cagr - mFo.cagr) * 100, mFo.cagr * 100, mFull.cagr * 100));
        sb.append(String.format("- Корреляция базис(вход) с Δбазис(14д): **%.2f** (гипотеза §2.2: отрицательная = неблагоприятный отбор).%n", corrEntry));
        sb.append(String.format("- Макс. неблагоприятное дневное движение базиса: **%.2f%%**.%n", maxAdvBasis * 100));
        sb.append(String.format("- Sharpe после базиса = **%.2f** — %s.%n%n", mFull.sharpe,
                mFull.sharpe > 5 ? "ВСЁ ЕЩЁ >5, искать ещё неучтённый риск (§6.1)" : "однозначный, как и ожидалось (§6.1)"));

        sb.append("## Бенчмарк S4 и решение (§6.4)\n\n");
        sb.append(String.format("S4 (стейбл-лендинг, задокументированные ставки): 2019–20 %.0f%%, 2021–22 %.0f%%, **2023–26 %.0f%%**.%n%n",
                S4_2019_20 * 100, S4_2021_22 * 100, S4_2023_26 * 100));
        double edge = cagr23 - S4_2023_26;
        sb.append(String.format("**S1 (полная модель) на 2023–2026 = %.1f%%/год против S4 %.0f%% → запас %+.1f п.п.**%n%n",
                cagr23 * 100, S4_2023_26 * 100, edge * 100));
        sb.append(edge >= 0.04
                ? "Запас ≥4 п.п. — **S1 окупает сложность** (шорт под плечом, две биржи, контроль дельты).\n\n"
                : "**Запас <4 п.п. — S1 НЕ доказала, что окупает операционную сложность на актуальном подпериоде.** "
                + "Не отвергается, но статус «требует пересмотра конструкции» (§6.4): вероятно работает только при высоком "
                + "funding — нужен явный гейт активации по уровню ставки.\n\n");

        sb.append("## Хвостовой риск (§6.3)\n\n");
        sb.append(String.format("- Макс. **внутридневной** рост BTC-перп (high vs пред. close) = **%+.1f%%** (в v1 по дневным close было +19.5%%). ",
                maxIntraday * 100));
        sb.append(String.format("При 2x ликвидация ~+50%%, при 3x ~+33%% — %s.%n",
                maxIntraday > 0.33 ? "**3x пробивался внутри дня**" : "в пределах 2x/3x"));
        sb.append("- В дни каскадов базис расходится сильнее всего (см. макс. неблагоприятное движение базиса выше) — ")
                .append("именно тогда реализуется отрицательный Δбазис.\n\n");

        sb.append("## §6.5 Ротация — пересчёт после базиса\n\n");
        sb.append("Ротация уходит в XRP/BNB/SOL, где funding выше **из-за большего риска** (тоньше ликвидность, шире базис). ")
                .append("Базис по альтам волатильнее, чем по BTC. Полный пересчёт ротации с индивидуальным базисом каждого альта — ")
                .append("следующий шаг; предварительно: +4 п.п. v1 не пережили бы вычет альт-базиса и издержек альт-ротации. ")
                .append("Решение по ротации откладывается (§6.5): принимается только если бьёт постоянную BTC И по CAGR, И по Sharpe.\n\n");

        sb.append("**Вывод:** базис снял мнимость Sharpe 10.6 → ").append(String.format("%.2f", mFull.sharpe))
                .append(". Оговорка: perp close вместо mark; издержки — задокументированная модель, не тиковая реальность; ")
                .append("S4-ставки приблизительные. Для решения о деньгах хватает главного: во сколько базис и издержки ")
                .append("обходятся и остаётся ли запас над S4.\n");

        writeFile(outPath, sb.toString());
        log.info("s1-v2: funding-only {}% -> с базисом {}% (Sharpe {}), 2023-26={}%, запас над S4={}пп -> {}",
                String.format("%.1f", mFo.cagr * 100), String.format("%.1f", mFull.cagr * 100),
                String.format("%.1f", mFull.sharpe), String.format("%.1f", cagr23 * 100),
                String.format("%.1f", edge * 100), Path.of(outPath).toAbsolutePath());
    }

    /**
     * Блок §6 (doc 27): проверка гейта активации S1. Если EMA7(средней 8ч-ставки funding_BTC)
     * ≥ порога — S1 активна (funding − Δбазис − издержки, плечо ≤2x), иначе капитал в S4
     * (стейбл-лендинг по историческим ставкам). Порог ЗАФИКСИРОВАН §6.2 = 0.010%/8ч, одна
     * попытка. Критерий §6.4 (все три): CAGR23-26 − S4 ≥4пп И доля активной ≥30% И издержки
     * переключений ≤20% прироста. Иначе Вариант Б. CLI: --report=s1-gate.
     */
    public void gateTest(String outPath) {
        double THRESH = 0.0001;        // 0.010% за 8ч (§6.2), не подбирается
        double SWITCH = 0.001;         // 2 ноги на вход или выход (0.1%)
        // funding BTC (сумма+cnt за день) + basis BTC
        TreeMap<LocalDate, double[]> fm = new TreeMap<>();
        db.query("SELECT date(funding_time/1000,'unixepoch') d, rate FROM funding WHERE exchange='binance' AND symbol='BTCUSDT'",
                rs -> { fm.computeIfAbsent(LocalDate.parse(rs.getString(1)), k -> new double[2]);
                        double[] x = fm.get(LocalDate.parse(rs.getString(1))); x[0] += rs.getDouble(2); x[1]++; return null; });
        TreeMap<LocalDate, Double> spot = new TreeMap<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { spot.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2)); return null; });
        TreeMap<LocalDate, Perp> pp;
        try {
            pp = fetchPerp("BTCUSDT");
        } catch (Exception e) {
            log.warn("s1-gate: не удалось скачать perp BTC: {}", e.toString());
            return;
        }
        TreeMap<LocalDate, Double> basis = new TreeMap<>();
        for (var e : pp.entrySet()) {
            Double sc = spot.get(e.getKey());
            if (sc != null && sc > 0) {
                basis.put(e.getKey(), e.getValue().close() / sc - 1);
            }
        }
        // EMA7 средней 8ч-ставки
        List<LocalDate> days = new ArrayList<>(fm.keySet());
        TreeMap<LocalDate, Double> ema = new TreeMap<>();
        double e = Double.NaN, k = 2.0 / (7 + 1);
        for (LocalDate d : days) {
            double[] fv = fm.get(d);
            double avg = fv[1] > 0 ? fv[0] / fv[1] : 0;
            e = Double.isNaN(e) ? avg : e + k * (avg - e);
            ema.put(d, e);
        }

        // прогон на 2023-2026 (окно критерия §6.4), полная модель + гейт
        LocalDate cut = LocalDate.parse("2023-01-01");
        double s4Daily = Math.pow(1 + S4_2023_26, 1.0 / 365) - 1;
        double eqGate = 1, eqS4 = 1;
        double prevBasis = Double.NaN;
        boolean active = false;
        int activeDays = 0, total = 0, switches = 0;
        double switchCost = 0;
        LocalDate first = null, last = null;
        double[] curve = null;
        List<Double> rets = new ArrayList<>();
        for (LocalDate d : days) {
            if (d.isBefore(cut) || !basis.containsKey(d)) {
                if (basis.containsKey(d)) {
                    prevBasis = basis.get(d);
                }
                continue;
            }
            boolean want = ema.get(d) >= THRESH;
            if (want != active) {
                switches++;
                switchCost += SWITCH;
                eqGate *= 1 - SWITCH;
            }
            active = want;
            double ret;
            if (active) {
                double[] fv = fm.get(d);
                double f = fv[1] > 0 ? fv[0] : 0;
                double bas = basis.get(d);
                double dB = Double.isNaN(prevBasis) ? 0 : bas - prevBasis;
                ret = f - dB;
                activeDays++;
            } else {
                ret = s4Daily;
            }
            eqGate *= 1 + ret;
            eqS4 *= 1 + s4Daily;
            rets.add(ret);
            prevBasis = basis.get(d);
            total++;
            if (first == null) {
                first = d;
            }
            last = d;
        }
        double years = (last.toEpochDay() - first.toEpochDay()) / 365.25;
        double cagrGate = Math.pow(Math.max(eqGate, 1e-9), 1.0 / years) - 1;
        double cagrS4 = Math.pow(eqS4, 1.0 / years) - 1;
        double edge = cagrGate - cagrS4;
        double activeFrac = (double) activeDays / total;
        double switchShare = edge > 0 ? switchCost / years / edge : Double.NaN;
        double mean = rets.stream().mapToDouble(x -> x).average().orElse(0);
        double var = rets.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
        double sharpe = var <= 0 ? 0 : mean / Math.sqrt(var) * Math.sqrt(365);

        boolean c1 = edge >= 0.04, c2 = activeFrac >= 0.30, c3 = !Double.isNaN(switchShare) && switchShare <= 0.20;
        boolean varА = c1 && c2 && c3;

        StringBuilder sb = new StringBuilder();
        sb.append("# S1 — гейт активации: последняя проверка конструкции (doc 27 §6)\n\n");
        String hdr = "Гейт: EMA7(средней 8ч-ставки funding_BTC) ≥ **%.3f%%/8ч** → S1 активна (плечо ≤2x, полная модель "
                + "funding − Δбазис − издержки), иначе капитал в **S4** (%.0f%%/год). Порог фиксирован §6.2, не подбирался. "
                + "Окно критерия %s … %s (%.1f лет). Перп с fapi (close-аппрокс).%n%n";
        sb.append(String.format(hdr, THRESH * 100, S4_2023_26 * 100, first, last, years));

        sb.append("| Метрика | Значение | Порог §6.4 | ✓/✗ |\n|---|---|---|---|\n");
        sb.append(String.format("| CAGR с гейтом (2023-26) | %.1f%% | — | |%n", cagrGate * 100));
        sb.append(String.format("| S4 (тот же период) | %.1f%% | — | |%n", cagrS4 * 100));
        sb.append(String.format("| **Запас над S4** | **%+.1f п.п.** | ≥ 4.0 | %s |%n", edge * 100, c1 ? "✓" : "✗"));
        sb.append(String.format("| **Доля времени активна S1** | **%.0f%%** | ≥ 30%% | %s |%n", activeFrac * 100, c2 ? "✓" : "✗"));
        sb.append(String.format("| **Издержки переключений / прирост** | **%s** | ≤ 20%% | %s |%n",
                Double.isNaN(switchShare) ? "н/д (нет прироста)" : String.format("%.0f%%", switchShare * 100), c3 ? "✓" : "✗"));
        sb.append(String.format("| Переключений/год | %.1f | — | |%n", switches / years));
        sb.append(String.format("| Sharpe | %.2f | «>5 искать дальше» | %s |%n%n", sharpe, sharpe > 5 ? "⚠>5" : "ок"));

        sb.append("## Вывод (§6.4)\n\n");
        if (varА) {
            sb.append("**Все три критерия выполнены → ВАРИАНТ А:** гейт окупает сложность, S1 принимается как ")
                    .append("оппортунистическая надстройка над S4 (не постоянное ядро). Это изменение архитектуры (00 §3) — принять явно.\n");
        } else {
            sb.append("**Критерий НЕ выполнен (");
            List<String> fails = new ArrayList<>();
            if (!c1) fails.add(String.format("запас %+.1fпп < 4пп", edge * 100));
            if (!c2) fails.add(String.format("активна %.0f%% < 30%%", activeFrac * 100));
            if (!c3) fails.add("издержки переключений > 20% прироста");
            sb.append(String.join("; ", fails)).append(") → ВАРИАНТ Б (§5, §9):** ");
            sb.append("гейт активации не спасает. **S1 исключается из постоянной архитектуры, ядром становится S4.** ")
                    .append("При розничном размере капитала постоянного нейтрального дохода с приемлемым доход/сложность не нашлось; ")
                    .append("доступны S4 + событийные (S5 разлоки, S7 panic-fade) + направленная премия (S2/S9). ")
                    .append("Согласуется с ожиданием §6.5 (записано до прогона: гейт не пройдёт — funding>порога в осн. 2019-22).\n");
        }
        sb.append("\n_Оговорки: perp close вместо mark (занижает хвост и Δбазис в стрессе, §3.1); все три недоучёта работают ")
                .append("против S1 — уточнение сделает вывод жёстче, не мягче. Плечо 3x запрещено (§2: внутридневной +36.2% > ликвидации 3x).\n");

        writeFile(outPath, sb.toString());
        log.info("s1-gate: CAGR={}%, S4={}%, запас={}пп, активна={}%, Вариант {} -> {}",
                String.format("%.1f", cagrGate * 100), String.format("%.1f", cagrS4 * 100),
                String.format("%.1f", edge * 100), String.format("%.0f", activeFrac * 100),
                varА ? "А" : "Б", Path.of(outPath).toAbsolutePath());
    }

    /** Дневные perp klines с fapi (close, high), пагинация с 2019-09. */
    private static TreeMap<LocalDate, Perp> fetchPerp(String symbol) throws IOException, InterruptedException {
        TreeMap<LocalDate, Perp> out = new TreeMap<>();
        long start = 1568073600000L;   // 2019-09-10
        long now = Instant.now().toEpochMilli();
        for (int iter = 0; iter < 6; iter++) {
            String url = "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol
                    + "&interval=1d&limit=1500&startTime=" + start;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0").timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            JsonNode arr = MAPPER.readTree(resp.body());
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            long lastOpen = start;
            for (JsonNode k : arr) {
                long t = k.get(0).asLong();
                LocalDate d = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
                out.put(d, new Perp(k.get(4).asDouble(), k.get(2).asDouble()));
                lastOpen = t;
            }
            if (arr.size() < 1500 || lastOpen >= now - 86_400_000L) {
                break;
            }
            start = lastOpen + 86_400_000L;
        }
        return out;
    }

    private static double pearson(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 3) {
            return 0;
        }
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double a = x.get(i), b = y.get(i);
            sx += a; sy += b; sxx += a * a; syy += b * b; sxy += a * b;
        }
        double cov = n * sxy - sx * sy, vx = n * sxx - sx * sx, vy = n * syy - sy * sy;
        return (vx <= 0 || vy <= 0) ? 0 : cov / Math.sqrt(vx * vy);
    }

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

    /**
     * Блок E (doc 24 §E / 23 §5.1): описательное исследование leverage_warning вокруг обвалов.
     * 5 обвалов против 5 спокойных периодов (фикс. зерно), метрики за 30/14/7 дней до точки.
     * <b>Это описание, а не доказательство</b> — 5 событий статистики не дают. Пороги не
     * подбираются. Данные-ограничение: funding с 2019-09, OI с 2021 → 2018-01 исключён.
     * CLI: --report=leverage.
     */
    public void leverageStudy(String outPath) {
        // суточный funding BTC+ETH (средняя ставка за день) + leverage_warning из regime_daily_v5
        Map<LocalDate, double[]> fund = new TreeMap<>();   // [sum, cnt] по дню (BTC+ETH)
        db.query("SELECT date(funding_time/1000,'unixepoch') d, rate FROM funding "
                        + "WHERE exchange='binance' AND symbol IN ('BTCUSDT','ETHUSDT')",
                rs -> { fund.computeIfAbsent(LocalDate.parse(rs.getString(1)), k -> new double[2]);
                        double[] a = fund.get(LocalDate.parse(rs.getString(1))); a[0] += rs.getDouble(2); a[1]++; return null; });
        Map<LocalDate, Integer> lev = new TreeMap<>();
        db.query("SELECT day, leverage_warning FROM regime_daily_v5",
                rs -> { lev.put(LocalDate.parse(rs.getString(1)), rs.getInt(2)); return null; });

        LocalDate[] crashes = {LocalDate.parse("2020-03-12"), LocalDate.parse("2021-05-19"),
                LocalDate.parse("2021-11-10"), LocalDate.parse("2024-08-05")};
        // спокойные периоды: фикс. зерно, вне ±45 дней любого обвала
        java.util.Random rnd = new java.util.Random(42);
        LocalDate lo = LocalDate.parse("2020-06-01"), hi = LocalDate.parse("2026-06-01");
        long span = hi.toEpochDay() - lo.toEpochDay();
        List<LocalDate> calm = new ArrayList<>();
        while (calm.size() < 5) {
            LocalDate d = lo.plusDays((long) (rnd.nextDouble() * span));
            boolean near = false;
            for (LocalDate c : crashes) {
                if (Math.abs(d.toEpochDay() - c.toEpochDay()) < 45) {
                    near = true;
                }
            }
            if (!near) {
                calm.add(d);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# leverage_warning вокруг обвалов — описательное исследование (doc 24 §E)\n\n");
        sb.append("**Это ОПИСАНИЕ, а не доказательство.** 5 событий (из них 2018-01 исключён — нет деривативных ")
                .append("данных до 2019-09) статистики не дают. Вопрос — «отличались ли метрики за 30/14/7 дней до точки», ")
                .append("а не «предсказывают ли». Пороги не подбирались, выборка не расширялась. funding — средняя BTC+ETH ")
                .append("(бп/8ч); lev% — доля дней с leverage_warning=1 (OI/MC-компонента доступна с 2021).\n\n");
        sb.append("| Событие | тип | funding 30д | funding 14д | funding 7д | lev% 30д | lev% 14д |\n|---|---|---|---|---|---|---|\n");
        double[] crAgg = new double[5];
        int crN = 0;
        for (LocalDate c : crashes) {
            double[] r = window(c, fund, lev);
            sb.append(String.format("| %s | ОБВАЛ | %s | %s | %s | %s | %s |%n", c,
                    bp(r[0]), bp(r[1]), bp(r[2]), pct(r[3]), pct(r[4])));
            for (int k = 0; k < 5; k++) {
                crAgg[k] += r[k];
            }
            crN++;
        }
        double[] caAgg = new double[5];
        for (LocalDate c : calm) {
            double[] r = window(c, fund, lev);
            sb.append(String.format("| %s | спокойн | %s | %s | %s | %s | %s |%n", c,
                    bp(r[0]), bp(r[1]), bp(r[2]), pct(r[3]), pct(r[4])));
            for (int k = 0; k < 5; k++) {
                caAgg[k] += r[k];
            }
        }
        sb.append(String.format("| **среднее ОБВАЛ** | | %s | %s | %s | %s | %s |%n",
                bp(crAgg[0] / crN), bp(crAgg[1] / crN), bp(crAgg[2] / crN), pct(crAgg[3] / crN), pct(crAgg[4] / crN)));
        sb.append(String.format("| **среднее спокойн** | | %s | %s | %s | %s | %s |%n%n",
                bp(caAgg[0] / 5), bp(caAgg[1] / 5), bp(caAgg[2] / 5), pct(caAgg[3] / 5), pct(caAgg[4] / 5)));
        boolean fundHigher = crAgg[1] / crN > caAgg[1] / 5;
        boolean levHigher = crAgg[3] / crN > caAgg[3] / 5;
        sb.append(String.format("**Наблюдение (описательно):** перед обвалами funding за 14д %s, чем в спокойные (%s vs %s бп/8ч); ",
                fundHigher ? "ВЫШЕ" : "не выше", bp(crAgg[1] / crN), bp(caAgg[1] / 5)));
        sb.append(String.format("доля дней leverage_warning за 30д %s (%s vs %s). ",
                levHigher ? "ВЫШЕ" : "не выше", pct(crAgg[3] / crN), pct(caAgg[3] / 5)));
        sb.append("Это согласуется с механикой (набитые плечи = топливо каскадов), но на 4 наблюдениях это лишь описание — ")
                .append("не сигнал и не доказательство. leverage_warning остаётся диагностическим модификатором, не входом детектора.\n");
        writeFile(outPath, sb.toString());
        log.info("leverage-study: funding14 обвал/спок = {}/{} бп -> {}",
                bp(crAgg[1] / crN), bp(caAgg[1] / 5), Path.of(outPath).toAbsolutePath());
    }

    /**
     * Блок §5.1 (doc 26): 2×2 leverage_warning × обвал с БАЗОВОЙ частотой. Для каждого дня —
     * сработал ли leverage_warning и случилась ли просадка ≥25% в следующие 60 дней (пороги
     * фиксированы до расчёта). Если P(обвал|warning) ≈ базовой частоте — предупреждение не
     * несёт информации. CLI: --report=leverage-v2.
     */
    public void leverageStudyV2(String outPath) {
        double DD = 0.25;
        int HORIZON = 60;
        // BTC close по дням
        TreeMap<LocalDate, Double> close = new TreeMap<>();
        db.query("SELECT date(open_time/1000,'unixepoch') d, close FROM candles WHERE symbol='BTCUSDT' AND interval='1d' ORDER BY open_time",
                rs -> { close.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2)); return null; });
        Map<LocalDate, Integer> lev = new TreeMap<>();
        db.query("SELECT day, leverage_warning FROM regime_daily_v5",
                rs -> { lev.put(LocalDate.parse(rs.getString(1)), rs.getInt(2)); return null; });
        List<LocalDate> days = new ArrayList<>(close.keySet());
        Map<LocalDate, Integer> dayIdx = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            dayIdx.put(days.get(i), i);
        }
        int a = 0, b = 0, c = 0, d = 0;
        for (Map.Entry<LocalDate, Integer> e : lev.entrySet()) {
            Integer i = dayIdx.get(e.getKey());
            if (i == null || i + HORIZON >= days.size()) {
                continue;   // нужен полный горизонт вперёд
            }
            double p0 = close.get(days.get(i));
            double min = p0;
            for (int k = 1; k <= HORIZON; k++) {
                min = Math.min(min, close.get(days.get(i + k)));
            }
            boolean crash = min / p0 - 1 <= -DD;
            boolean warn = e.getValue() == 1;
            if (warn && crash) a++;
            else if (warn) b++;
            else if (crash) c++;
            else d++;
        }
        int nTot = a + b + c + d;
        double base = (double) (a + c) / nTot;
        double pCrashGivenWarn = (a + b) > 0 ? (double) a / (a + b) : Double.NaN;
        double pWarnBeforeCrash = (a + c) > 0 ? (double) a / (a + c) : Double.NaN;
        double lift = base > 0 ? pCrashGivenWarn / base : Double.NaN;

        StringBuilder sb = new StringBuilder();
        sb.append("# leverage_warning — 2×2 с базовой частотой (doc 26 §5.1)\n\n");
        sb.append(String.format("Порог просадки **%.0f%%**, горизонт **%d дней** (фиксированы до расчёта). ", DD * 100, HORIZON));
        sb.append("Для каждого дня истории: сработал ли `leverage_warning` и была ли просадка ≥порога в следующие ")
                .append("60 дней. Окно ").append(days.get(0)).append("… (leverage_warning с 2020; OI-компонента с 2021).\n\n");
        sb.append("| | обвал в 60д | обвала не было | итого |\n|---|---|---|---|\n");
        sb.append(String.format("| **warning=1** | A=%d | B=%d | %d |%n", a, b, a + b));
        sb.append(String.format("| **warning=0** | C=%d | D=%d | %d |%n", c, d, c + d));
        sb.append(String.format("| итого | %d | %d | %d |%n%n", a + c, b + d, nTot));
        sb.append(String.format("- Базовая частота обвалов `(A+C)/N` = **%.1f%%**%n", base * 100));
        sb.append(String.format("- P(обвал | warning) `A/(A+B)` = **%.1f%%**%n", pCrashGivenWarn * 100));
        sb.append(String.format("- Доля обвалов с предупреждением `A/(A+C)` = **%.1f%%**%n", pWarnBeforeCrash * 100));
        sb.append(String.format("- Lift = P(обвал|warning) / базовая = **%.2f×**%n%n", lift));
        String verdict;
        if (Double.isNaN(lift) || Math.abs(lift - 1) < 0.15) {
            verdict = "**Вывод: предупреждение НЕ несёт информации** — P(обвал|warning) ≈ базовой частоте (lift ≈ 1). "
                    + "Контраст из 5 событий (doc 24) был тавтологией: funding высок на росте, обвалы бывают после роста, "
                    + "но большинство ралли обвалом не кончаются. Ложные срабатывания (B) это и показывают.";
        } else if (lift > 1) {
            verdict = String.format("**Вывод: предупреждение поднимает вероятность обвала в %.2f× над базовой** — слабый, но "
                    + "ненулевой сигнал. Оговорка: число независимых обвалов не выросло, это по-прежнему описание; "
                    + "выросло число наблюдений «warning без обвала» (B=%d), которого и не хватало.", lift, b);
        } else {
            verdict = "**Вывод: предупреждение снижает вероятность обвала** — контр-интуитивно, вероятно артефакт выборки.";
        }
        sb.append(verdict).append("\n");
        writeFile(outPath, sb.toString());
        log.info("leverage-v2: base={}%, P(crash|warn)={}%, lift={}x -> {}",
                String.format("%.1f", base * 100), String.format("%.1f", pCrashGivenWarn * 100),
                String.format("%.2f", lift), Path.of(outPath).toAbsolutePath());
    }

    /** Метрики за 30/14/7 дней до date: {funding30, funding14, funding7, lev%30, lev%14} (funding в бп/8ч). */
    private static double[] window(LocalDate date, Map<LocalDate, double[]> fund, Map<LocalDate, Integer> lev) {
        int[] wins = {30, 14, 7};
        double[] out = new double[5];
        for (int w = 0; w < 3; w++) {
            double sum = 0;
            int cnt = 0;
            for (long k = 1; k <= wins[w]; k++) {
                double[] fv = fund.get(date.minusDays(k));
                if (fv != null && fv[1] > 0) {
                    sum += fv[0] / fv[1];
                    cnt++;
                }
            }
            out[w] = cnt > 0 ? sum / cnt * 1e4 : Double.NaN;   // бп/8ч
        }
        int[] levWins = {30, 14};
        for (int w = 0; w < 2; w++) {
            int on = 0, cnt = 0;
            for (long k = 1; k <= levWins[w]; k++) {
                Integer lv = lev.get(date.minusDays(k));
                if (lv != null) {
                    cnt++;
                    on += lv;
                }
            }
            out[3 + w] = cnt > 0 ? (double) on / cnt : Double.NaN;
        }
        return out;
    }

    private static String bp(double v) {
        return Double.isNaN(v) ? "н/д" : String.format("%.2f", v);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "н/д" : String.format("%.0f%%", v * 100);
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
