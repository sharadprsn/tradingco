package com.kite.trading.service;

import java.math.BigDecimal;

public record BacktestTrade(
    BigDecimal entrySpot,
    BigDecimal exitSpot,
    BigDecimal targetSpot,
    BigDecimal stopSpot,
    String outcome,
    BigDecimal pnlPct) {}
