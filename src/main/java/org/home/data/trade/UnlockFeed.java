package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Фид разлоков (doc 54 §8 п.1): из {@link EmissionSource} извлекает предстоящие клифф-разлоки
 * ≥{@link #MIN_PCT} циркулирующего supply, сопоставляет протокол → тикер (CoinGecko) → перп Kraken,
 * фильтрует по доступности на Kraken.
 *
 * <p>Разбор расписания вынесен в {@link UnlockSchedule} и общий с бэктестом (док. 130 §6). Порог там
 * применяется ПОСЛЕ слияния траншей одного дня: раньше он резал каждый транш по отдельности, из-за чего
 * день из нескольких мелких траншей пропадал целиком.
 */
public class UnlockFeed implements EventFeed {

    public static final double MIN_PCT = 0.03;

    private final EmissionSource src;
    private final Map<String, String> geckoToTicker;  // gecko_id -> TICKER (upper)
    private final Set<String> krakenBases;            // базы с торгуемым перпом на Kraken (XBT->BTC)
    /**
     * Тикеры, на которые претендует БОЛЬШЕ ОДНОГО проекта CoinGecko.
     *
     * ⚠️ Отображение {@code gecko_id → тикер} не проверяет тождества монеты, и
     * это структурная подмена (док. 130 §III п.7): у `velodrome-finance` тикер
     * `VELO`, на Kraken есть `PF_VELOUSD` — но это Velo Labs, другая монета.
     * Любой протокол, чей тикер совпал с чужим перпом, отдал бы боту ЧУЖОЕ
     * расписание разлоков, и бот открыл бы позицию по событию, которого у этой
     * монеты нет.
     *
     * Разрешать такое угадыванием (по капитализации, по возрасту) нельзя:
     * ошибка тут стоит реальной сделки. Поэтому конфликтный тикер **исключается
     * целиком**, а его список печатается при старте — это отказ, а не тихий
     * пропуск.
     */
    private final Set<String> ambiguous;

    public UnlockFeed(EmissionSource src, Map<String, String> geckoToTicker, Set<String> krakenBases) {
        this.src = src; this.geckoToTicker = geckoToTicker; this.krakenBases = krakenBases;
        this.ambiguous = ambiguousTickers(geckoToTicker, krakenBases);
    }

    /**
     * Тикеры торгуемых перпов, на которые претендует больше одного gecko_id.
     *
     * Считается только по базам Kraken: коллизии среди монет, которыми мы не
     * торгуем, вреда не приносят, а в предупреждении были бы шумом.
     */
    static Set<String> ambiguousTickers(Map<String, String> geckoToTicker, Set<String> krakenBases) {
        Map<String, Integer> claims = new java.util.HashMap<>();
        for (String t : geckoToTicker.values()) {
            if (krakenBases.contains(t)) {
                claims.merge(t, 1, Integer::sum);
            }
        }
        Set<String> out = new java.util.TreeSet<>();
        claims.forEach((t, n) -> {
            if (n > 1) {
                out.add(t);
            }
        });
        return out;
    }

    /** Исключённые из-за неоднозначности тикеры — для печати при старте. */
    public Set<String> ambiguousTickers() {
        return ambiguous;
    }

    /** Все клифф-разлоки ≥{@link #MIN_PCT} на Kraken-инструментах с датой > todayEpochDay (будущие). */
    @Override public List<UnlockEvent> upcoming(long todayEpochDay) throws Exception {
        List<UnlockEvent> out = new ArrayList<>();
        for (String slug : src.protocols()) {
            try { collect(src.emissions(slug), todayEpochDay, out); }
            catch (Exception ignore) { /* один протокол не валит фид */ }
        }
        out.sort((a, b) -> Long.compare(a.unlockDay(), b.unlockDay()));
        return out;
    }

    /** События, для которых сегодня день входа: unlockDay == today + entryLead. */
    @Override public List<UnlockEvent> dueForEntry(long todayEpochDay, int entryLead) throws Exception {
        List<UnlockEvent> out = new ArrayList<>();
        for (UnlockEvent e : upcoming(todayEpochDay)) if (e.unlockDay() == todayEpochDay + entryLead) out.add(e);
        return out;
    }

    /** Тикер протокола: {@code metadata.token} вида {@code coingecko:<id>}, иначе поле {@code gecko_id}. */
    static String ticker(JsonNode emissions, Map<String, String> geckoToTicker) {
        String tok = emissions.path("metadata").path("token").asText("");
        String gecko = tok.startsWith("coingecko:") ? tok.substring(10) : emissions.path("gecko_id").asText("");
        return geckoToTicker.getOrDefault(gecko, "");
    }

    void collect(JsonNode e, long todayEpochDay, List<UnlockEvent> out) {
        String ticker = ticker(e, geckoToTicker);
        if (ticker.isEmpty() || !krakenBases.contains(ticker)) return;
        // Тикер, на который претендует несколько проектов, не берём ВООБЩЕ:
        // расписание могло приехать от чужой монеты (док. 130 §III п.7).
        if (ambiguous.contains(ticker)) return;
        for (UnlockSchedule.Unlock u : UnlockSchedule.parse(e, ticker, MIN_PCT)) {
            if (u.day() <= todayEpochDay) continue;                 // только будущие
            out.add(new UnlockEvent(u.base(), UnlockSchedule.krakenSymbol(u.base()), u.day(),
                    u.pct(), u.category(), u.breakdown()));
        }
    }
}
