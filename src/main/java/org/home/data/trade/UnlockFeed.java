package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Фид разлоков (doc 54 §8 п.1): из {@link EmissionSource} извлекает предстоящие клифф-разлоки
 * ≥3% циркулирующего supply, сопоставляет протокол → тикер (CoinGecko) → перп Kraken, фильтрует по
 * доступности на Kraken. Логика парсинга — как в бэктесте (`S5Unlocks`): circulating = сумма
 * documentedData на дату; событие — metadata.events с unlockType=cliff и noOfTokens[0].
 */
public class UnlockFeed implements EventFeed {

    public static final double MIN_PCT = 0.03;

    private final EmissionSource src;
    private final Map<String, String> geckoToTicker;  // gecko_id -> TICKER (upper)
    private final Set<String> krakenBases;            // базы с торгуемым перпом на Kraken (XBT->BTC)

    public UnlockFeed(EmissionSource src, Map<String, String> geckoToTicker, Set<String> krakenBases) {
        this.src = src; this.geckoToTicker = geckoToTicker; this.krakenBases = krakenBases;
    }

    /** Все клифф-разлоки ≥3% на Kraken-инструментах с датой > todayEpochDay (будущие). */
    @Override public List<UnlockEvent> upcoming(long todayEpochDay) throws Exception {
        List<UnlockEvent> raw = new ArrayList<>();
        for (String slug : src.protocols()) {
            try { collect(src.emissions(slug), todayEpochDay, raw); }
            catch (Exception ignore) { /* один протокол не валит фид */ }
        }
        List<UnlockEvent> out = merge(raw);
        out.sort((a, b) -> Long.compare(a.unlockDay(), b.unlockDay()));
        return out;
    }

    /**
     * Слить транши одного разлока (совпадают символ и день) в одно событие: проценты СУММИРУЕМ (истинный
     * размер клиффа за день), категория — у крупнейшего транша. Иначе схлопывание по ключу символ@день
     * оставило бы % лишь одного транша и занизило бы масштаб (напр. XPL 3.2%+32.5%+32.5% ≈ 68%, а не 3.2%).
     */
    static List<UnlockEvent> merge(List<UnlockEvent> raw) {
        java.util.LinkedHashMap<String, UnlockEvent> byKey = new java.util.LinkedHashMap<>();
        Map<String, Double> topTranche = new java.util.HashMap<>();
        for (UnlockEvent e : raw) {
            String key = e.krakenSymbol() + "@" + e.unlockDay();
            UnlockEvent cur = byKey.get(key);
            if (cur == null) {
                byKey.put(key, e);
                topTranche.put(key, e.pctCirculating());
            } else {
                double sum = Math.min(1.0, cur.pctCirculating() + e.pctCirculating());
                String cat = e.pctCirculating() > topTranche.get(key) ? e.category() : cur.category();
                topTranche.put(key, Math.max(topTranche.get(key), e.pctCirculating()));
                byKey.put(key, new UnlockEvent(cur.base(), cur.krakenSymbol(), cur.unlockDay(), sum, cat));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** События, для которых сегодня день входа: unlockDay == today + entryLead. */
    @Override public List<UnlockEvent> dueForEntry(long todayEpochDay, int entryLead) throws Exception {
        List<UnlockEvent> out = new ArrayList<>();
        for (UnlockEvent e : upcoming(todayEpochDay)) if (e.unlockDay() == todayEpochDay + entryLead) out.add(e);
        return out;
    }

    void collect(JsonNode e, long todayEpochDay, List<UnlockEvent> out) {
        JsonNode meta = e.path("metadata");
        String tok = meta.path("token").asText("");
        String gecko = tok.startsWith("coingecko:") ? tok.substring(10) : e.path("gecko_id").asText("");
        String ticker = geckoToTicker.getOrDefault(gecko, "");
        if (ticker.isEmpty() || !krakenBases.contains(ticker)) return;

        // circulating = сумма unlocked по категориям на момент t
        TreeMap<Long, Double> total = new TreeMap<>();
        for (JsonNode cat : e.path("documentedData").path("data"))
            for (JsonNode p : cat.path("data"))
                total.merge(p.path("timestamp").asLong(), p.path("unlocked").asDouble(0), Double::sum);
        if (total.isEmpty()) return;

        JsonNode evs = meta.path("events");
        if (evs.isMissingNode() || !evs.isArray()) evs = e.path("events");
        for (JsonNode ev : evs) {
            if (!"cliff".equals(ev.path("unlockType").asText())) continue;
            JsonNode t = ev.path("noOfTokens");
            if (!t.isArray() || t.size() == 0) continue;
            double amt = t.get(0).asDouble(0);
            if (amt <= 0) continue;
            long ts = ev.path("timestamp").asLong();
            long day = ts / 86400;
            if (day <= todayEpochDay) continue;                 // только будущие
            Long fk = total.floorKey(ts);
            if (fk == null) continue;
            double circ = total.get(fk);
            if (circ <= 0) continue;
            double pct = amt / circ;
            if (pct < MIN_PCT) continue;
            out.add(new UnlockEvent(ticker, "PF_" + krakenSuffix(ticker) + "USD", day,
                    Math.min(pct, 1.0), classify(ev)));
        }
    }

    private static String krakenSuffix(String ticker) { return ticker.equals("BTC") ? "XBT" : ticker; }

    static String classify(JsonNode ev) {
        String desc = ev.path("description").asText("");
        String label = ev.path("category").asText("");
        int fi = desc.indexOf("from ");
        if (fi >= 0) { int oi = desc.indexOf(" on ", fi); if (oi > fi) label = desc.substring(fi + 5, oi); }
        String s = label.toLowerCase();
        if (s.contains("investor") || s.contains("private") || s.contains("seed") || s.contains("vc") || s.contains("backer")) return "investors";
        if (s.contains("team") || s.contains("core") || s.contains("contributor") || s.contains("founder") || s.contains("advisor") || s.contains("insider")) return "team";
        if (s.contains("stak") || s.contains("mining") || s.contains("reward") || s.contains("airdrop") || s.contains("incentive") || s.contains("liquidity")) return "staking";
        return "ecosystem";
    }
}
