package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Live-оценка funding для фильтра дорогого шорта. Kraken funding — ЧАСОВОЙ; из публичного tickers берём
 * текущую ставку и экстраполируем на 5 дней (120 ч): {@code estimate = (fundingRate/markPrice) * 120}.
 * Знак по конвенции проекта (doc 55 §3.2): для шорта funding>0 = получаем, дорогой шорт = сумма < −1.5%.
 * Текущая ставка предсказывает накопленную за 5д (corr 0.812, s5_prelaunch) — точность фильтра достаточна.
 */
public class KrakenFundingSource implements FundingSource {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int HORIZON_HOURS = 120;   // 5 суток часового funding

    private final KrakenApi api;

    public KrakenFundingSource(KrakenApi api) { this.api = api; }

    @Override
    public double estimate5dFunding(String base) throws Exception {
        String symbol = "PF_" + (base.equalsIgnoreCase("BTC") ? "XBT" : base.toUpperCase()) + "USD";
        for (JsonNode t : M.readTree(api.get("/api/v3/tickers", false)).path("tickers")) {
            if (!symbol.equalsIgnoreCase(t.path("symbol").asText())) continue;
            double mark = t.path("markPrice").asDouble(t.path("last").asDouble(0));
            double rate = t.path("fundingRate").asDouble(0);
            if (mark <= 0) return 0.0;
            return (rate / mark) * HORIZON_HOURS;      // относительная часовая × 120
        }
        return 0.0;   // нет перпа в tickers — нейтрально (фильтр не срабатывает)
    }
}
