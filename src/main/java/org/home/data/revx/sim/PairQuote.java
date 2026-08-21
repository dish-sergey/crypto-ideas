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
        long availableAtMs) {

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
