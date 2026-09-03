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
     * Тикер → gecko_id ГЛАВНОЙ монеты с этим тикером (по капитализации).
     *
     * ⚠️ Отображение {@code gecko_id → тикер} не проверяет тождества монеты, и
     * это структурная подмена (док. 130 §III п.7): у `velodrome-finance` тикер
     * `VELO`, на Kraken есть `PF_VELOUSD` — но это Velo Labs, другая монета.
     * Протокол, чей тикер совпал с чужим перпом, отдал бы боту ЧУЖОЕ расписание
     * разлоков, и бот открыл бы позицию по событию, которого у этой монеты нет.
     *
     * ⚠️⚠️ И вот чего делать НЕЛЬЗЯ, хотя очень хочется: отсекать по признаку
     * «на тикер претендует больше одного проекта». В списке CoinGecko 19 499
     * монет, и почти у каждого крупного тикера есть однофамильцы — проверка
     * 02.09.2026 выбросила **118 тикеров, включая BTC, ETH, SOL и ADA**, то есть
     * практически всю торгуемую вселенную. Такой «отказ» не защищает, а тихо
     * выключает стратегию.
     *
     * Правильный вопрос — не «уникален ли тикер», а **«наш ли это протокол»**.
     * Отвечает на него капитализация: перп на Kraken торгуется на ГЛАВНУЮ монету
     * с этим тикером, а не на однофамильца. Поэтому расписание принимается,
     * когда наш {@code gecko_id} и есть главный носитель тикера.
     */
    private final Map<String, String> canonicalByTicker;
    /** Тикеры, конфликт по которым разрешить не удалось — их и печатаем. */
    private final Set<String> unresolved;

    public UnlockFeed(EmissionSource src, Map<String, String> geckoToTicker, Set<String> krakenBases,
                      Map<String, String> canonicalByTicker) {
        this.src = src; this.geckoToTicker = geckoToTicker; this.krakenBases = krakenBases;
        this.canonicalByTicker = canonicalByTicker;
        this.unresolved = unresolved(geckoToTicker, krakenBases, canonicalByTicker);
    }

    /**
     * Тикеры торгуемых перпов, у которых несколько претендентов И ни одного
     * известного главного. Только они действительно теряются.
     *
     * Считается по базам Kraken: коллизии среди монет, которыми мы не торгуем,
     * вреда не приносят, а в предупреждении были бы шумом.
     */
    static Set<String> unresolved(Map<String, String> geckoToTicker, Set<String> krakenBases,
                                  Map<String, String> canonicalByTicker) {
        Set<String> out = new java.util.TreeSet<>(contested(geckoToTicker, krakenBases));
        out.removeIf(canonicalByTicker::containsKey);
        return out;
    }

    /**
     * Торгуемые тикеры, на которые претендует больше одного проекта.
     *
     * Само по себе это НЕ повод отказывать — у любого крупного тикера есть
     * однофамильцы, и отказ по этому признаку выключает вселенную целиком
     * (см. комментарий к {@link #canonicalByTicker}). Список нужен для другого:
     * это ровно те тикеры, для которых стоит выяснить главную монету, и больше
     * ни для каких запрашивать рейтинг незачем.
     */
    public static Set<String> contested(Map<String, String> geckoToTicker, Set<String> krakenBases) {
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

    /** Тикеры, потерянные из-за неразрешимого конфликта. */
    public Set<String> unresolvedTickers() {
        return unresolved;
    }

    /**
     * Наш ли это протокол: пускать ли его расписание на тикер {@code ticker}.
     *
     * Один претендент — вопроса нет. Несколько и известен главный — сверяем.
     * Несколько и главный неизвестен — отказ: угадывать тут нельзя, ошибка
     * стоит сделки.
     */
    boolean owns(String gecko, String ticker) {
        long claimants = geckoToTicker.values().stream().filter(ticker::equals).count();
        if (claimants <= 1) {
            return true;
        }
        String canonical = canonicalByTicker.get(ticker);
        return canonical != null && canonical.equals(gecko);
    }

    /**
     * Печатает потерянные тикеры при старте. Вызывать обязательно: молчаливое
     * исключение монеты выглядит как «событий нет», и через месяц никто не
     * вспомнит, что она отсеяна намеренно.
     */
    public void warnAmbiguous(org.slf4j.Logger log) {
        if (unresolved.isEmpty()) {
            log.info("конфликтов тикеров, которые нечем разрешить, нет");
            return;
        }
        log.warn("ИСКЛЮЧЕНЫ из фида: у тикеров {} несколько проектов CoinGecko и ни один "
                + "не опознан как главный по капитализации, поэтому чьё расписание — "
                + "неизвестно (док. 130 §III п.7). Угадывать нельзя: ошибка стоит сделки.",
                unresolved);
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
        return geckoToTicker.getOrDefault(geckoId(emissions), "");
    }

    /**
     * Идентификатор монеты у протокола: {@code metadata.token} вида
     * {@code coingecko:<id>}, иначе поле {@code gecko_id}. Порядок именно
     * такой — {@code gecko_id} бывает мусорным (см. CLAUDE.md).
     */
    static String geckoId(JsonNode emissions) {
        String tok = emissions.path("metadata").path("token").asText("");
        return tok.startsWith("coingecko:") ? tok.substring(10)
                : emissions.path("gecko_id").asText("");
    }

    void collect(JsonNode e, long todayEpochDay, List<UnlockEvent> out) {
        String ticker = ticker(e, geckoToTicker);
        if (ticker.isEmpty() || !krakenBases.contains(ticker)) return;
        // Наш ли это протокол: при нескольких претендентах на тикер расписание
        // берётся только у главной монеты (док. 130 §III п.7).
        if (!owns(geckoId(e), ticker)) return;
        for (UnlockSchedule.Unlock u : UnlockSchedule.parse(e, ticker, MIN_PCT)) {
            if (u.day() <= todayEpochDay) continue;                 // только будущие
            out.add(new UnlockEvent(u.base(), UnlockSchedule.krakenSymbol(u.base()), u.day(),
                    u.pct(), u.category(), u.breakdown()));
        }
    }
}
