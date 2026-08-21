package org.home.data.revx;

/**
 * Спецификация пары из /api/1.0/public/configuration/pairs.
 *
 * Ключ в ответе — 'ETH/USDC' (через слэш), а в пути книги и ленты сделок тот же
 * символ пишется через дефис ('ETH-USDC'). Два формата в одном API — источник
 * тихих 404, поэтому преобразование живёт здесь и только здесь.
 *
 * base_step / quote_step обязательны для округления размеров в симуляторе
 * (ТЗ §4.6 п.6: игнорировать их — дефект).
 */
public record PairSpec(
        String symbol,
        String base,
        String quote,
        double baseStep,
        double quoteStep,
        Double minOrderSize,
        Double maxOrderSize,
        Double minOrderSizeQuote,
        Double maxOrderSizeQuote,
        String status) {

    public String pathSymbol() {
        return base + "-" + quote;
    }

    public boolean active() {
        return "active".equalsIgnoreCase(status);
    }
}
