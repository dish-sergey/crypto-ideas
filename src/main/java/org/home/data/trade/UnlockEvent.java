package org.home.data.trade;

/**
 * Предстоящий клифф-разлок ≥3% циркулирующего supply (сигнал для входа S5).
 * base — базовый тикер (APT), krakenSymbol — перп на Kraken (PF_APTUSD), unlockDay — epoch-день разлока.
 * Вход — за 5 дней до unlockDay; category — получатель (investors/team/ecosystem/staking).
 */
public record UnlockEvent(String base, String krakenSymbol, long unlockDay,
                          double pctCirculating, String category) {}
