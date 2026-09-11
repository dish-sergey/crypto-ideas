package org.home.data.revx.sim;

/**
 * Состояние одной пары на момент времени: обе ноги (USDC и опорная USD) из
 * одного снимка. Единица входа для расчёта справедливой цены (ТЗ §4.1).
 *
 * {@code availableAtMs} — момент, когда пара стала известна НАМ, то есть время
 * получения ПОЗДНЕЙ из двух ног. Симулятор читает только по нему: взять раннюю
 * ногу значило бы объявить пару известной до того, как пришла вторая половина.
 */
public record PairQuote(
        String base,
        double midUsdc,
        double midUsd,
        double spreadUsdc,
        double spreadUsd,
        boolean memecoin,
        long availableAtMs,
        double bqUsdc,
        double aqUsdc,
        double smoothUsdc) {

    /**
     * Без сглаженной середины: опора SMOOTH вырождается в обычную.
     */
    public PairQuote(String base, double midUsdc, double midUsd, double spreadUsdc,
                     double spreadUsd, boolean memecoin, long availableAtMs,
                     double bqUsdc, double aqUsdc) {
        this(base, midUsdc, midUsd, spreadUsdc, spreadUsd, memecoin, availableAtMs,
                bqUsdc, aqUsdc, 0);
    }

    /**
     * Без объёмов на лучшем уровне. Микроцена по такой паре не считается и
     * молча вырождается в середину — это верно: объёмов нет, перекоса нет.
     */
    public PairQuote(String base, double midUsdc, double midUsd, double spreadUsdc,
                     double spreadUsd, boolean memecoin, long availableAtMs) {
        this(base, midUsdc, midUsd, spreadUsdc, spreadUsd, memecoin, availableAtMs, 0, 0, 0);
    }

    /**
     * Микроцена Stoikov по ноге USDC: середина, взвешенная ОБРАТНО объёмам.
     * Если на биде втрое больше, чем на аске, аск сметут раньше, и цена ближе к
     * аску. Перепутанный вес превращает лучший предиктор в худший.
     */
    public double microUsdc() {
        double s = bqUsdc + aqUsdc;
        if (s <= 0 || spreadUsdc <= 0) {
            return midUsdc;
        }
        double half = midUsdc * spreadUsdc / 200.0;
        return midUsdc + half * (bqUsdc - aqUsdc) / s;
    }

    /** Подразумеваемый курс USDC/USD по этой паре. */
    public double implied() {
        return midUsd / midUsdc;
    }

    public boolean valid() {
        return midUsdc > 0 && midUsd > 0
                && !Double.isNaN(midUsdc) && !Double.isNaN(midUsd)
                && !Double.isInfinite(midUsdc) && !Double.isInfinite(midUsd);
    }
}
