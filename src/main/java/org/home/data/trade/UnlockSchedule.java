package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Единственный разбор расписания разлоков DefiLlama — общий для боевого фида и для бэктеста (док. 130 §6).
 *
 * <p>Порядок операций важен и был причиной док. 130: сначала транши одного дня СЛИВАЮТСЯ в одно событие,
 * и только потом применяется порог {@code minPct}. Обратный порядок отбрасывал день целиком, если каждый
 * транш по отдельности до порога не дотягивал (ZRO 19.09.2026: 1.68% + 2.06% = 3.74%, оба {@code insiders}).
 *
 * <p>Циркулирующее предложение берётся как сумма {@code unlocked} по всем траншам на момент события. Доля
 * дня — сумма долей его траншей; знаменатель у траншей одного дня может слегка отличаться, если метки
 * времени внутри дня разные, но это доли процента и на отбор не влияет.
 */
public final class UnlockSchedule {

    private UnlockSchedule() {
    }

    /**
     * Разлок за один день: слитые транши одного тикера.
     *
     * @param base      базовый тикер (APT)
     * @param day       epoch-день разлока
     * @param pct       доля циркулирующего предложения (0..1)
     * @param tokens    сумма токенов всех траншей дня
     * @param category  категория крупнейшего транша (investors/team/ecosystem/staking)
     * @param breakdown состав дня для оператора («team 32.5 + inv 32.5 + eco 3.2»), null для одиночного транша
     * @param tranches  сколько траншей слито в это событие
     */
    public record Unlock(String base, long day, double pct, double tokens,
                         String category, String breakdown, int tranches) {
    }

    /** Один транш до слияния — промежуточное представление. */
    private record Tranche(long day, double tokens, double pct, String category) {
    }

    /**
     * Все cliff-разлоки из расписания, слитые по дню и отфильтрованные по доле.
     *
     * @param emissions тело {@code defillama-datasets.llama.fi/emissions/{slug}}
     * @param base      базовый тикер, который приписывается событиям
     * @param minPct    минимальная доля циркуляции ПОСЛЕ слияния (0.03 = 3%)
     * @return события по возрастанию дня; пустой список, если расписание пустое или непригодное
     */
    public static List<Unlock> parse(JsonNode emissions, String base, double minPct) {
        TreeMap<Long, Double> circ = circulating(emissions);
        if (circ.isEmpty()) {
            return List.of();
        }
        List<Tranche> tranches = tranches(emissions, circ);
        return merge(tranches, base).stream().filter(u -> u.pct() >= minPct).toList();
    }

    /** Циркулирующее предложение по времени: сумма {@code unlocked} всех траншей на каждую метку. */
    public static TreeMap<Long, Double> circulating(JsonNode emissions) {
        TreeMap<Long, Double> total = new TreeMap<>();
        for (JsonNode section : emissions.path("documentedData").path("data")) {
            for (JsonNode p : section.path("data")) {
                total.merge(asLong(p.path("timestamp")), p.path("unlocked").asDouble(0), Double::sum);
            }
        }
        return total;
    }

    /** Отдельные cliff-транши без порога. Линейные события пропускаются: там noOfTokens — смена скорости. */
    private static List<Tranche> tranches(JsonNode emissions, TreeMap<Long, Double> circ) {
        JsonNode evs = emissions.path("metadata").path("events");
        if (evs.isMissingNode() || !evs.isArray()) {
            evs = emissions.path("events");
        }
        List<Tranche> out = new ArrayList<>();
        for (JsonNode ev : evs) {
            if (!"cliff".equals(ev.path("unlockType").asText())) {
                continue;
            }
            JsonNode tokens = ev.path("noOfTokens");
            if (!tokens.isArray() || tokens.isEmpty()) {
                continue;
            }
            double amount = tokens.get(0).asDouble(0);
            long ts = asLong(ev.path("timestamp"));
            if (amount <= 0 || ts <= 0) {
                continue;
            }
            Long floor = circ.floorKey(ts);
            if (floor == null || circ.get(floor) <= 0) {
                continue;
            }
            out.add(new Tranche(ts / 86400L, amount, amount / circ.get(floor), classify(ev)));
        }
        return out;
    }

    /** Слить транши одного дня: токены суммируются, доля — сумма токенов к общему знаменателю дня. */
    private static List<Unlock> merge(List<Tranche> tranches, String base) {
        Map<Long, List<Tranche>> byDay = new LinkedHashMap<>();
        for (Tranche t : tranches) {
            byDay.computeIfAbsent(t.day(), k -> new ArrayList<>()).add(t);
        }
        List<Unlock> out = new ArrayList<>();
        for (Map.Entry<Long, List<Tranche>> e : byDay.entrySet()) {
            List<Tranche> g = new ArrayList<>(e.getValue());
            g.sort((a, b) -> Double.compare(b.pct(), a.pct()));          // крупнейший транш первым
            double tokens = 0;
            double pct = 0;
            StringBuilder bd = new StringBuilder();
            for (Tranche t : g) {
                tokens += t.tokens();
                pct += t.pct();
                if (!bd.isEmpty()) {
                    bd.append(" + ");
                }
                bd.append(shortCat(t.category())).append(' ')
                        .append(String.format(Locale.ROOT, "%.1f", t.pct() * 100));
            }
            out.add(new Unlock(base, e.getKey(), Math.min(pct, 1.0), tokens,
                    g.get(0).category(), g.size() == 1 ? null : bd.toString(), g.size()));
        }
        out.sort((a, b) -> Long.compare(a.day(), b.day()));
        return out;
    }

    /** {@code timestamp} в корпусе иногда приходит строкой (Curve), иногда числом — приводим оба случая. */
    private static long asLong(JsonNode n) {
        return n.isTextual() ? parseLong(n.asText()) : n.asLong(0);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static String shortCat(String category) {
        return switch (category) {
            case "investors" -> "inv";
            case "ecosystem" -> "eco";
            case "staking" -> "stk";
            default -> category;
        };
    }

    /** Категория получателя: из описания («… from Investors on …»), иначе из поля category. */
    static String classify(JsonNode ev) {
        String desc = ev.path("description").asText("");
        String label = ev.path("category").asText("");
        int fi = desc.indexOf("from ");
        if (fi >= 0) {
            int oi = desc.indexOf(" on ", fi);
            if (oi > fi) {
                label = desc.substring(fi + 5, oi);
            }
        }
        String s = label.toLowerCase(Locale.ROOT);
        if (s.contains("investor") || s.contains("private") || s.contains("seed")
                || s.contains("vc") || s.contains("backer")) {
            return "investors";
        }
        if (s.contains("team") || s.contains("core") || s.contains("contributor")
                || s.contains("founder") || s.contains("advisor") || s.contains("insider")) {
            return "team";
        }
        if (s.contains("stak") || s.contains("mining") || s.contains("reward")
                || s.contains("airdrop") || s.contains("incentive") || s.contains("liquidity")) {
            return "staking";
        }
        return "ecosystem";
    }

    /** Тикер базы в символе перпа Kraken: BTC торгуется как XBT. */
    public static String krakenSymbol(String base) {
        return "PF_" + (base.equalsIgnoreCase("BTC") ? "XBT" : base.toUpperCase(Locale.ROOT)) + "USD";
    }
}
