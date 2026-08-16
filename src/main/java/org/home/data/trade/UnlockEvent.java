package org.home.data.trade;

/**
 * Предстоящий клифф-разлок ≥3% циркулирующего supply (сигнал для входа S5).
 * base — базовый тикер (APT), krakenSymbol — перп на Kraken (PF_APTUSD), unlockDay — epoch-день разлока.
 * Вход — за 5 дней до unlockDay; category — получатель крупнейшего транша (investors/team/ecosystem/staking).
 * breakdown — состав траншей одного дня для показа оператору («team 32.5 + inv 32.5 + eco 3.2»), null для
 * одиночного транша ({@link UnlockFeed#merge}).
 */
public record UnlockEvent(String base, String krakenSymbol, long unlockDay,
                          double pctCirculating, String category, String breakdown) {

    /** Одиночный транш — без разбивки. */
    public UnlockEvent(String base, String krakenSymbol, long unlockDay, double pctCirculating, String category) {
        this(base, krakenSymbol, unlockDay, pctCirculating, category, null);
    }

    /** Метка для сообщений: «6.0% investors» либо «68.2% (team 32.5 + inv 32.5 + eco 3.2)». */
    public String pctLabel() {
        String p = String.format(java.util.Locale.ROOT, "%.1f%%", pctCirculating * 100);
        return breakdown == null ? p + " " + category : p + " (" + breakdown + ")";
    }
}
