package org.home.data.trade;

/**
 * Параметры S5, зафиксированные протоколом 54 (не подбирать). Значения по умолчанию — из протокола.
 * entryLead=5д, позиция 4.5% капитала, суммарная экспозиция ≤81%, стоп −30%, фильтр дорогого шорта −1.5%/5д.
 */
public record S5Config(int entryLead, double positionFraction, double maxExposure,
                       double stopFrac, double expensiveFundingThreshold) {

    public static S5Config protocol() {
        return new S5Config(5, 0.045, 0.81, 0.30, -0.015);
    }
}
