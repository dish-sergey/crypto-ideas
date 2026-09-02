package org.home.data.theory.s5;

import org.home.data.theory.Jlog;
import org.home.data.theory.kelly.KellyConfig;
import org.home.data.theory.kelly.S5Outcomes;
import org.home.data.theory.TheoryDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Премия S5 на СЛИТЫХ событиях (док. 130, очередь п. 2, 3, 5).
 *
 * <p>Считает то же, что считал разовый скрипт {@code reports/analysis/S5Unlocks}: шорт за {@code lead}
 * дней до разлока, выход в день разлока, премия = доходность шорта минус безусловная доходность шорта той
 * же длины по той же монете. Отличие одно и оно принципиальное: событием считается ДЕНЬ разлока, а не
 * отдельный транш. В старом счёте день из трёх траншей давал три сделки с одинаковым исходом, что раздувало
 * выборку и сужало доверительный интервал.
 *
 * <p>Разрезы: лестница порога, годы, наличие перпа на Kraken (бот торгует только его), кластеризация.
 * Доверительный интервал — обычный t-интервал; он остаётся оптимистичным, потому что события
 * кластеризуются по времени, а точечная оценка не поправлена на общий рыночный фактор.
 *
 * <p>CLI: {@code --theory=s5-premium [--out=reports/theory]}.
 */
@Component
@Lazy
public class S5PremiumBench {

    private static final Logger log = LoggerFactory.getLogger(S5PremiumBench.class);

    private final TheoryDb db;
    private final int lead;
    private final S5Outcomes outcomes;
    private final KellyConfig cfg;

    public S5PremiumBench(TheoryDb db, @Value("${theory.kelly.lead}") int lead,
                          S5Outcomes outcomes, KellyConfig cfg) {
        this.db = db;
        this.lead = lead;
        this.outcomes = outcomes;
        this.cfg = cfg;
    }

    /** Событие с посчитанным исходом. */
    private record Trade(String base, String day, double pct, double shortRet, double premium,
                         boolean onKraken) {
    }

    public void run(String outDir) {
        Map<String, TreeMap<String, Double>> px = prices();
        Map<String, Double> uncond = unconditional(px);
        Set<String> kraken = krakenBases();

        List<Object[]> raw = db.query(
                "SELECT base, unlock_day, pct_supply FROM s5_event ORDER BY unlock_day",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)});

        List<Trade> all = new ArrayList<>();
        int noPrice = 0;
        for (Object[] r : raw) {
            String base = (String) r[0];
            String day = (String) r[1];
            double pct = (double) r[2];
            TreeMap<String, Double> p = px.get(base);
            if (p == null) {
                noPrice++;
                continue;
            }
            Double entry = p.get(LocalDate.parse(day).minusDays(lead).toString());
            Double exit = p.get(day);
            if (entry == null || exit == null || entry <= 0) {
                noPrice++;
                continue;
            }
            double shortRet = (entry - exit) / entry;
            all.add(new Trade(base, day, pct, shortRet, shortRet - uncond.getOrDefault(base, 0.0),
                    kraken.contains(base)));
        }
        all.sort(Comparator.comparing(Trade::day));
        Jlog.info(log, "s5premium.built", Map.of("raw", raw.size(), "trades", all.size(),
                "no_price", noPrice, "kraken_known", kraken.size()));

        StringBuilder md = new StringBuilder();
        md.append("# S5: премия на слитых событиях\n\n");
        md.append("Событие = ДЕНЬ разлока (транши одного дня слиты, док. 130). ")
                .append("Шорт за ").append(lead).append(" дней до разлока, выход в день разлока; ")
                .append("премия = доходность шорта минус безусловная доходность шорта той же длины ")
                .append("по той же монете. Без издержек и стопа — это ответ на вопрос «есть ли эффект», ")
                .append("а не «сколько заработает бот».\n\n");
        md.append("Событий в датасете: **").append(raw.size()).append("**, из них с ценами: **")
                .append(all.size()).append("** (без цен ").append(noPrice).append(").\n\n");

        md.append("## Лестница порога\n\n");
        md.append("| Порог | Событий | Премия | Медиана | %>0 | t | 95% интервал |\n|---|---|---|---|---|---|---|\n");
        for (double t : new double[]{0.02, 0.03, 0.04, 0.05, 0.07, 0.10}) {
            md.append(row(String.format(Locale.ROOT, "≥%.0f%%", t * 100),
                    all.stream().filter(x -> x.pct() >= t).toList()));
        }

        List<Trade> base3 = all.stream().filter(x -> x.pct() >= 0.03).toList();

        md.append("\n## По годам (порог 3%)\n\n");
        md.append("| Годы | Событий | Премия | Медиана | %>0 | t | 95% интервал |\n|---|---|---|---|---|---|---|\n");
        md.append(row("2020-22", base3.stream().filter(x -> x.day().compareTo("2023-01-01") < 0).toList()));
        md.append(row("2023-24", base3.stream()
                .filter(x -> x.day().compareTo("2023-01-01") >= 0 && x.day().compareTo("2025-01-01") < 0).toList()));
        md.append(row("2025-26", base3.stream().filter(x -> x.day().compareTo("2025-01-01") >= 0).toList()));

        md.append("\n## Kraken против остального (порог 3%)\n\n");
        md.append("Бот торгует только перпы Kraken. Если премия держится на монетах, которых там нет, ")
                .append("измеренное среднее к боту не относится.\n\n");
        md.append("| Вселенная | Событий | Премия | Медиана | %>0 | t | 95% интервал |\n|---|---|---|---|---|---|---|\n");
        md.append(row("есть на Kraken", base3.stream().filter(Trade::onKraken).toList()));
        md.append(row("нет на Kraken", base3.stream().filter(x -> !x.onKraken()).toList()));

        md.append("\n## Kraken × период (порог 3%)\n\n");
        md.append("Эффект живёт в свежих годах, а бот торгует только Kraken — пересечение и есть то, ")
                .append("на что можно рассчитывать.\n\n");
        md.append("| Разрез | Событий | Премия | Медиана | %>0 | t | 95% интервал |\n|---|---|---|---|---|---|---|\n");
        md.append(row("Kraken, 2025-26", base3.stream()
                .filter(x -> x.onKraken() && x.day().compareTo("2025-01-01") >= 0).toList()));
        md.append(row("Kraken, до 2025", base3.stream()
                .filter(x -> x.onKraken() && x.day().compareTo("2025-01-01") < 0).toList()));
        md.append(row("не Kraken, 2025-26", base3.stream()
                .filter(x -> !x.onKraken() && x.day().compareTo("2025-01-01") >= 0).toList()));
        md.append(row("не Kraken, до 2025", base3.stream()
                .filter(x -> !x.onKraken() && x.day().compareTo("2025-01-01") < 0).toList()));
        md.append("\nКолонка «Премия» — до издержек. Круговая сделка стоит около 0.19% плюс проскальзывание, ")
                .append("так что из каждой строки надо вычесть примерно 0.2 процентного пункта.\n");

        md.append("\n## Устойчивость среднего (порог 3%)\n\n");
        md.append(blockBootstrap(base3, 2000)).append('\n');
        md.append("Только Kraken: ").append(blockBootstrap(
                base3.stream().filter(Trade::onKraken).toList(), 2000));

        Controls ctl = controls(px);

        md.append("\n## Честный бенчмарк: шорт тех же монет без разлока (порог 3%)\n\n");
        md.append(benchmark(base3, px, ctl));

        md.append("\n## Листинг или тонкий стакан? (порог 3%)\n\n");
        md.append(liquidity(base3, px, ctl));

        md.append("\n## Разложение: сколько из дохода — эффект разлока, а сколько снос\n\n");
        md.append("Доходность шорта = безусловный снос монеты за те же 5 дней + премия события. ")
                .append("Первое слагаемое — бета (шортим падающие альты), второе — то, за что S5 существует.\n\n");
        md.append("| Разрез | Событий | Шорт всего | Безусловный снос | Премия события |\n|---|---|---|---|---|\n");
        md.append(decomp("всё", base3));
        md.append(decomp("есть на Kraken", base3.stream().filter(Trade::onKraken).toList()));
        md.append(decomp("Kraken, 2025-26", base3.stream()
                .filter(x -> x.onKraken() && x.day().compareTo("2025-01-01") >= 0).toList()));
        md.append(decomp("нет на Kraken", base3.stream().filter(x -> !x.onKraken()).toList()));

        md.append("\n## Полная модель сделки (стоп, фондирование, издержки)\n\n");
        md.append(pnl(kraken));

        md.append("\n## Кластеризация (порог 3%)\n\n");
        md.append(clustering(base3));

        md.append("\n## Оговорки\n\n")
                .append("- Расписание читается на сегодня, а не на дату входа: даты и объёмы разлоков ")
                .append("правятся задним числом, поэтому это **верхняя граница**, а не PIT-оценка.\n")
                .append("- t-интервал предполагает независимость событий; события кластеризуются ")
                .append("по времени, так что настоящий интервал шире.\n")
                .append("- Премия снята за вычетом безусловной доходности той же монеты, но не за ")
                .append("вычетом рынка: общий фактор не выделен.\n")
                .append("- Цены — перп Binance; бот торгует перп Kraken.\n")
                .append("- Разрезов много (порог × год × площадка), поэтому отдельная значимая ячейка ")
                .append("сама по себе ничего не доказывает: при таком числе срезов часть из них проходит ")
                .append("порог случайно. Смотреть надо на согласованность разрезов, а не на лучший.\n");

        write(outDir, md.toString());
    }

    /**
     * Честный бенчмарк (док. 131 §7 п.2): шорт тех же монет БЕЗ привязки к разлоку.
     *
     * <p>Два контроля, потому что «безусловный снос по всей истории монеты» снимает только один из двух
     * конфаундеров. Второй — время: события кучкуются, и если они пришлись на падающий рынок, шорт был бы
     * прибылен и без разлока.
     * <ul>
     *   <li><b>Рыночный контроль</b>: те же календарные дни, но другие монеты — среднее по всем базам, у
     *       которых в это окно СВОЕГО разлока нет. Разность «событие − рынок» и есть доход, очищенный от
     *       режима.</li>
     *   <li><b>Плацебо по времени</b>: та же монета, случайные окна без разлока рядом. Перестановочный тест
     *       даёт распределение того же показателя при случайных датах — с ним и сравнивается факт.</li>
     * </ul>
     */
    private record Controls(Map<String, double[]> peer, Map<String, List<String>> placebo) {
    }

    /** Контроли считаются один раз: рыночный по дням и список «чистых» окон по каждой монете. */
    private Controls controls(Map<String, TreeMap<String, Double>> px) {
        Map<String, java.util.Set<Long>> unlocks = new HashMap<>();
        db.query("SELECT base, unlock_day FROM s5_event", rs -> {
            unlocks.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                    .add(LocalDate.parse(rs.getString(2)).toEpochDay());
            return null;
        });

        Map<String, double[]> peer = new HashMap<>();                 // день -> {сумма, счёт}
        Map<String, List<String>> placeboDays = new HashMap<>();      // база -> дни без разлока рядом
        for (Map.Entry<String, TreeMap<String, Double>> e : px.entrySet()) {
            java.util.Set<Long> un = unlocks.getOrDefault(e.getKey(), java.util.Set.of());
            for (Map.Entry<String, Double> p : e.getValue().entrySet()) {
                String d = p.getKey();
                Double exit = e.getValue().get(LocalDate.parse(d).plusDays(lead).toString());
                if (exit == null || p.getValue() <= 0) {
                    continue;
                }
                long d0 = LocalDate.parse(d).toEpochDay();
                if (!hasUnlock(un, d0, d0 + lead)) {                  // в контроль — только чистые окна
                    double ret = (p.getValue() - exit) / p.getValue();
                    peer.computeIfAbsent(d, k -> new double[2])[0] += ret;
                    peer.get(d)[1]++;
                    if (!hasUnlock(un, d0 - lead, d0 + 2L * lead)) {  // плацебо — с запасом вокруг окна
                        placeboDays.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(d);
                    }
                }
            }
        }

        return new Controls(peer, placeboDays);
    }

    /** Таблица бенчмарка: событие против рынка в те же дни и против случайных дат. */
    private String benchmark(List<Trade> t, Map<String, TreeMap<String, Double>> px, Controls c) {
        Map<String, double[]> peer = c.peer();
        Map<String, List<String>> placeboDays = c.placebo();
        List<Trade> usable = t.stream()
                .filter(x -> peerCount(peer, entryDay(x)) >= 20 && placeboDays.containsKey(x.base()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Рыночный контроль: среднее по монетам без своего разлока в те же дни ")
                .append("(минимум 20 монет в контроле). Плацебо: та же монета, случайные окна, ")
                .append("вокруг которых разлоков нет.\n\n");
        sb.append("| Разрез | Событий | Шорт события | Шорт рынка в те же дни | Разность | Плацебо | t | p |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        sb.append(benchRow("всё", usable, peer, px, placeboDays));
        sb.append(benchRow("есть на Kraken", usable.stream().filter(Trade::onKraken).toList(),
                peer, px, placeboDays));
        sb.append(benchRow("Kraken, 2025-26", usable.stream()
                .filter(x -> x.onKraken() && x.day().compareTo("2025-01-01") >= 0).toList(),
                peer, px, placeboDays));
        sb.append(benchRow("нет на Kraken", usable.stream().filter(x -> !x.onKraken()).toList(),
                peer, px, placeboDays));
        sb.append("\n`Разность` = шорт события минус рынок в те же дни: доход, очищенный от режима.\n")
                .append("`Плацебо` — та же величина на случайных окнах без разлока, среднее по 2000 прогонам: ")
                .append("это уровень, который даёт шорт этих же монет БЕЗ разлока.\n")
                .append("`t` проверяет разность против нуля, `p` — против плацебо (доля прогонов, где ")
                .append("случайные даты дали не хуже фактических). Правильный вопрос — второй: ")
                .append("«отличается ли дата разлока от любой другой даты».\n");
        return sb.toString();
    }

    private String benchRow(String label, List<Trade> t, Map<String, double[]> peer,
                            Map<String, TreeMap<String, Double>> px, Map<String, List<String>> placebo) {
        if (t.size() < 20) {
            return "| " + label + " | " + t.size() + " | — | — | — | — | — | — |\n";
        }
        double[] adj = new double[t.size()];
        double sEv = 0;
        double sMk = 0;
        for (int i = 0; i < t.size(); i++) {
            Trade x = t.get(i);
            double mk = peerMean(peer, entryDay(x));
            adj[i] = x.shortRet() - mk;
            sEv += x.shortRet();
            sMk += mk;
        }
        double mean = Arrays.stream(adj).average().orElse(0);
        double se = sd(adj, mean) / Math.sqrt(adj.length);

        java.util.Random rnd = new java.util.Random(20260902L);
        int iters = 2000;
        int notWorse = 0;
        double placeboSum = 0;
        for (int it = 0; it < iters; it++) {
            double sum = 0;
            int n = 0;
            for (Trade x : t) {
                List<String> days = placebo.get(x.base());
                if (days == null || days.isEmpty()) {
                    continue;
                }
                String d = days.get(rnd.nextInt(days.size()));
                TreeMap<String, Double> p = px.get(x.base());
                Double entry = p.get(d);
                Double exit = p.get(LocalDate.parse(d).plusDays(lead).toString());
                if (entry == null || exit == null || entry <= 0) {
                    continue;
                }
                sum += (entry - exit) / entry - peerMean(peer, d);
                n++;
            }
            if (n > 0) {
                placeboSum += sum / n;
                if (sum / n >= mean) {
                    notWorse++;
                }
            }
        }
        return String.format(Locale.ROOT,
                "| %s | %d | %+.2f%% | %+.2f%% | %+.2f%% | %+.2f%% | %.2f | %.3f |%n",
                label, t.size(), sEv / t.size() * 100, sMk / t.size() * 100, mean * 100,
                placeboSum / iters * 100, se > 0 ? mean / se : 0, (double) notWorse / iters);
    }

    /**
     * Разведение двух объяснений (док. 131 §7 п.4). Разрез «есть перп на Kraken» — про листинг, а гипотеза
     * S5 — про тонкий стакан. Здесь событиям приписывается ликвидность на момент входа (медианный дневной
     * оборот перпа за 30 дней до), они бьются на квинтили, и внутри КАЖДОГО квинтиля сравниваются
     * Kraken и не-Kraken. Если разница исчезает внутри квинтилей — дело в ликвидности, а листинг был
     * просто её меткой.
     */
    private String liquidity(List<Trade> t, Map<String, TreeMap<String, Double>> px, Controls c) {
        Map<String, TreeMap<String, Double>> vol = new HashMap<>();
        db.query("SELECT base, day, quote_volume FROM s5_liquidity ORDER BY base, day", rs -> {
            vol.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getString(2), rs.getDouble(3));
            return null;
        });
        if (vol.isEmpty()) {
            return "Таблица `s5_liquidity` пуста — нужен повторный `--theory=s5-import`.\n";
        }

        record WithVol(Trade t, double vol) {
        }
        List<WithVol> wv = new ArrayList<>();
        for (Trade x : t) {
            double v = medianVolume(vol.get(x.base()), entryDay(x));
            if (v > 0 && peerCount(c.peer(), entryDay(x)) >= 20 && c.placebo().containsKey(x.base())) {
                wv.add(new WithVol(x, v));
            }
        }
        if (wv.size() < 100) {
            return "Событий с известным оборотом мало (" + wv.size() + ") — разрез не считается.\n";
        }
        wv.sort(java.util.Comparator.comparingDouble(WithVol::vol));

        StringBuilder sb = new StringBuilder();
        sb.append("Ликвидность события — медианный дневной оборот перпа Binance за 30 дней до входа ")
                .append("(величина на момент входа, не задним числом). Квинтили по ней:\n\n");
        sb.append("| Квинтиль оборота | Событий | Медианный оборот | Доля на Kraken | Разность к рынку | Плацебо | p |\n")
                .append("|---|---|---|---|---|---|---|\n");
        int q = wv.size() / 5;
        for (int i = 0; i < 5; i++) {
            List<WithVol> part = wv.subList(i * q, i == 4 ? wv.size() : (i + 1) * q);
            List<Trade> tr = part.stream().map(WithVol::t).toList();
            double medVol = part.get(part.size() / 2).vol();
            long onK = tr.stream().filter(Trade::onKraken).count();
            // benchRow: | label | n | шорт | рынок | разность | плацебо | t | p |
            String[] f = benchRow("q", tr, c.peer(), px, c.placebo()).split("\\|");
            sb.append(String.format(Locale.ROOT, "| Q%d %s | %d | $%.1f млн | %.0f%% | %s | %s | %s |%n",
                    i + 1, i == 0 ? "(тонкие)" : i == 4 ? "(толстые)" : "", tr.size(), medVol / 1e6,
                    100.0 * onK / tr.size(), f[5].trim(), f[6].trim(), f[8].trim()));
        }

        sb.append("\n### Кто несёт эффект вне Kraken\n\n");
        sb.append("Вся вселенная датасета — перпы Binance USDT, поэтому «нет на Kraken» означает ")
                .append("«есть на Binance, нет на Kraken». Монеты с наибольшим числом таких событий:\n\n");
        sb.append("| Монета | Событий | Разность к рынку | Медианный оборот |\n|---|---|---|---|\n");
        Map<String, List<WithVol>> byBase = new LinkedHashMap<>();
        for (WithVol x : wv) {
            if (!x.t().onKraken()) {
                byBase.computeIfAbsent(x.t().base(), k -> new ArrayList<>()).add(x);
            }
        }
        byBase.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(10)
                .forEach(e -> {
                    List<Trade> tr = e.getValue().stream().map(WithVol::t).toList();
                    double mv = e.getValue().stream().mapToDouble(WithVol::vol).sorted()
                            .skip(e.getValue().size() / 2).findFirst().orElse(0);
                    double[] v = tr.stream()
                            .mapToDouble(x -> x.shortRet() - peerMean(c.peer(), entryDay(x))).toArray();
                    sb.append(String.format(Locale.ROOT, "| %s | %d | %+.2f%% | $%.1f млн |%n",
                            e.getKey(), tr.size(), Arrays.stream(v).average().orElse(0) * 100, mv / 1e6));
                });

        sb.append("\n### Kraken против не-Kraken ВНУТРИ квинтилей\n\n");
        sb.append("| Квинтиль | Kraken n | Kraken разность | не-Kraken n | не-Kraken разность |\n")
                .append("|---|---|---|---|---|\n");
        for (int i = 0; i < 5; i++) {
            List<WithVol> part = wv.subList(i * q, i == 4 ? wv.size() : (i + 1) * q);
            List<Trade> k = part.stream().map(WithVol::t).filter(Trade::onKraken).toList();
            List<Trade> nk = part.stream().map(WithVol::t).filter(x -> !x.onKraken()).toList();
            sb.append(String.format(Locale.ROOT, "| Q%d | %d | %s | %d | %s |%n", i + 1,
                    k.size(), diffOnly(k, c.peer()), nk.size(), diffOnly(nk, c.peer())));
        }
        return sb.toString();
    }

    /** Только «разность к рынку» без перестановки — для компактных клеток кросс-таблицы. */
    private String diffOnly(List<Trade> t, Map<String, double[]> peer) {
        if (t.size() < 15) {
            return "—";
        }
        double[] v = t.stream().mapToDouble(x -> x.shortRet() - peerMean(peer, entryDay(x))).toArray();
        double mean = Arrays.stream(v).average().orElse(0);
        double se = sd(v, mean) / Math.sqrt(v.length);
        return String.format(Locale.ROOT, "%+.2f%% (t=%.2f)", mean * 100, se > 0 ? mean / se : 0);
    }

    /** Медианный дневной оборот за 30 дней до дня входа; 0, если данных нет. */
    private static double medianVolume(TreeMap<String, Double> v, String entryDay) {
        if (v == null) {
            return 0;
        }
        LocalDate d = LocalDate.parse(entryDay);
        List<Double> vals = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            Double x = v.get(d.minusDays(i).toString());
            if (x != null && x > 0) {
                vals.add(x);
            }
        }
        if (vals.size() < 10) {
            return 0;
        }
        java.util.Collections.sort(vals);
        return vals.get(vals.size() / 2);
    }

    private String entryDay(Trade x) {
        return LocalDate.parse(x.day()).minusDays(lead).toString();
    }

    private static boolean hasUnlock(java.util.Set<Long> days, long from, long to) {
        for (long d = from; d <= to; d++) {
            if (days.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private static double peerMean(Map<String, double[]> peer, String day) {
        double[] a = peer.get(day);
        return a == null || a[1] == 0 ? 0 : a[0] / a[1];
    }

    private static double peerCount(Map<String, double[]> peer, String day) {
        double[] a = peer.get(day);
        return a == null ? 0 : a[1];
    }

    /** Разложение доходности шорта на безусловный снос монеты и премию события. */
    private static String decomp(String label, List<Trade> t) {
        if (t.isEmpty()) {
            return "| " + label + " | 0 | — | — | — |\n";
        }
        double shortRet = t.stream().mapToDouble(Trade::shortRet).average().orElse(0);
        double prem = t.stream().mapToDouble(Trade::premium).average().orElse(0);
        return String.format(Locale.ROOT, "| %s | %d | %+.2f%% | %+.2f%% | %+.2f%% |%n",
                label, t.size(), shortRet * 100, (shortRet - prem) * 100, prem * 100);
    }

    /** Строка сводки: n, среднее, медиана, доля положительных, t-статистика и 95%-интервал. */
    private static String row(String label, List<Trade> t) {
        if (t.isEmpty()) {
            return "| " + label + " | 0 | — | — | — | — | — |\n";
        }
        double[] v = t.stream().mapToDouble(Trade::premium).toArray();
        double mean = Arrays.stream(v).average().orElse(0);
        double sd = sd(v, mean);
        double se = sd / Math.sqrt(v.length);
        double tstat = se > 0 ? mean / se : 0;
        double lo = mean - 1.96 * se;
        double hi = mean + 1.96 * se;
        long pos = Arrays.stream(v).filter(x -> x > 0).count();
        return String.format(Locale.ROOT,
                "| %s | %d | %+.2f%% | %+.2f%% | %.0f%% | %.2f | %+.2f%%…%+.2f%% |%n",
                label, v.length, mean * 100, median(v) * 100, 100.0 * pos / v.length, tstat,
                lo * 100, hi * 100);
    }

    /**
     * То же разбиение, но на фактическом P&amp;L позиции: {@link S5Outcomes} применяет отмену по дорогому
     * фондированию, стоп и издержки. Премия отвечает «есть ли эффект», а эта таблица — «что осталось бы
     * на счету».
     */
    private String pnl(Set<String> kraken) {
        S5Outcomes.Dataset ds = outcomes.build(cfg, cfg.stop());
        List<S5Outcomes.Outcome> all = ds.outcomes();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Событий в модели: **%d** из %d (отменено по фондированию %d, без цен %d), стоп −%.0f%%, "
                        + "издержки %.2f%% на круг.%n%n",
                all.size(), ds.rawEvents(), ds.skippedFunding(), ds.skippedNoPrice(),
                cfg.stop() * 100, cfg.tradeCost() * 100));
        sb.append("| Разрез | Сделок | Ср. P&L | Медиана | %>0 | t | 95% интервал |\n|---|---|---|---|---|---|---|\n");
        sb.append(pnlRow("всё", all));
        sb.append(pnlRow("есть на Kraken", all.stream().filter(o -> kraken.contains(o.base())).toList()));
        sb.append(pnlRow("Kraken, 2025-26", all.stream()
                .filter(o -> kraken.contains(o.base()) && o.unlockDay().compareTo("2025-01-01") >= 0).toList()));
        sb.append(pnlRow("нет на Kraken", all.stream().filter(o -> !kraken.contains(o.base())).toList()));
        return sb.toString();
    }

    private static String pnlRow(String label, List<S5Outcomes.Outcome> t) {
        if (t.isEmpty()) {
            return "| " + label + " | 0 | — | — | — | — | — |\n";
        }
        double[] v = t.stream().mapToDouble(S5Outcomes.Outcome::outcome).toArray();
        double mean = Arrays.stream(v).average().orElse(0);
        double se = sd(v, mean) / Math.sqrt(v.length);
        long pos = Arrays.stream(v).filter(x -> x > 0).count();
        return String.format(Locale.ROOT,
                "| %s | %d | %+.2f%% | %+.2f%% | %.0f%% | %.2f | %+.2f%%…%+.2f%% |%n",
                label, v.length, mean * 100, median(v) * 100, 100.0 * pos / v.length,
                se > 0 ? mean / se : 0, (mean - 1.96 * se) * 100, (mean + 1.96 * se) * 100);
    }

    /**
     * Блочный бутстрап по календарным месяцам: месяц — блок, месяцы переразыгрываются с возвращением.
     * t-интервал считает события независимыми, а они кластеризуются во времени (несколько разлоков в одном
     * рыночном движении), поэтому блочный интервал шире и честнее.
     */
    private static String blockBootstrap(List<Trade> t, int iters) {
        if (t.size() < 20) {
            return "Событий мало — бутстрап не считается.\n";
        }
        Map<String, List<Double>> byMonth = new LinkedHashMap<>();
        for (Trade x : t) {
            byMonth.computeIfAbsent(x.day().substring(0, 7), k -> new ArrayList<>()).add(x.premium());
        }
        List<List<Double>> blocks = new ArrayList<>(byMonth.values());
        java.util.Random rnd = new java.util.Random(20260902L);
        double[] means = new double[iters];
        for (int i = 0; i < iters; i++) {
            double sum = 0;
            int n = 0;
            for (int b = 0; b < blocks.size(); b++) {
                for (double v : blocks.get(rnd.nextInt(blocks.size()))) {
                    sum += v;
                    n++;
                }
            }
            means[i] = n > 0 ? sum / n : 0;
        }
        Arrays.sort(means);
        double lo = means[(int) (iters * 0.025)];
        double hi = means[(int) (iters * 0.975)];
        long neg = Arrays.stream(means).filter(x -> x <= 0).count();
        return String.format(Locale.ROOT,
                "Блочный бутстрап по месяцам (%d блоков, %d прогонов): 95%%-интервал среднего "
                        + "**%+.2f%%…%+.2f%%**, доля прогонов с неположительной премией **%.1f%%**.%n",
                blocks.size(), iters, lo * 100, hi * 100, 100.0 * neg / iters);
    }

    /** Максимум одновременно открытых позиций: окно удержания [день−lead, день]. */
    private String clustering(List<Trade> t) {
        Map<Long, Integer> byDay = new HashMap<>();
        for (Trade x : t) {
            long d = LocalDate.parse(x.day()).toEpochDay();
            for (long i = d - lead; i <= d; i++) {
                byDay.merge(i, 1, Integer::sum);
            }
        }
        int max = byDay.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double avg = byDay.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        return String.format(Locale.ROOT,
                "Максимум одновременно открытых позиций: **%d**; в среднем по дням с позициями %.1f.%n"
                        + "Лимит на позицию выводился из сценария «18 одновременных» — сверить с этим числом.%n",
                max, avg);
    }

    private Map<String, TreeMap<String, Double>> prices() {
        Map<String, TreeMap<String, Double>> px = new HashMap<>();
        db.query("SELECT base, day, close FROM s5_price ORDER BY base, day", rs -> {
            px.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getString(2), rs.getDouble(3));
            return null;
        });
        return px;
    }

    /** Безусловная доходность шорта длиной lead по каждой монете — база для премии. */
    private Map<String, Double> unconditional(Map<String, TreeMap<String, Double>> px) {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, TreeMap<String, Double>> e : px.entrySet()) {
            TreeMap<String, Double> m = e.getValue();
            double sum = 0;
            int n = 0;
            for (Map.Entry<String, Double> p : m.entrySet()) {
                Double later = m.get(LocalDate.parse(p.getKey()).plusDays(lead).toString());
                if (later == null || p.getValue() <= 0) {
                    continue;
                }
                sum += (p.getValue() - later) / p.getValue();
                n++;
            }
            out.put(e.getKey(), n > 0 ? sum / n : 0);
        }
        return out;
    }

    /** Базы с торгуемым перпом Kraken. Сеть недоступна → пустое множество, разрез просто не наполнится. */
    private Set<String> krakenBases() {
        Set<String> out = new HashSet<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://futures.kraken.com/derivatives/api/v3/tickers"))
                    .header("User-Agent", "curl/8.0").timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(20)).build()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            for (var t : new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body()).path("tickers")) {
                String s = t.path("symbol").asText("").toUpperCase(Locale.ROOT);
                if (!s.startsWith("PF_") || !s.endsWith("USD")) {
                    continue;
                }
                String core = s.substring(3, s.length() - 3);
                out.add(core.equals("XBT") ? "BTC" : core);
            }
        } catch (Exception ex) {
            Jlog.warn(log, "s5premium.kraken.fail", Map.of("err", ex.toString()));
        }
        return out;
    }

    private static double sd(double[] v, double mean) {
        if (v.length < 2) {
            return 0;
        }
        double s = 0;
        for (double x : v) {
            s += (x - mean) * (x - mean);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
    }

    private static void write(String outDir, String md) {
        try {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Path f = dir.resolve("s5_premium.md");
            Files.writeString(f, md, StandardCharsets.UTF_8);
            Jlog.info(log, "s5premium.report", Map.of("file", f.toString()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
