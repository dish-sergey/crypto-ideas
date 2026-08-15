package org.home.data.trade;

/** Открытая позиция на бирже. qty > 0; для шорта прибыль = (entryPx − markPx)/entryPx. */
public record Position(String symbol, Side side, double qty, double entryPx) {}
