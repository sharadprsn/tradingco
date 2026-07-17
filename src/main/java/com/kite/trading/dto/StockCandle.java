package com.kite.trading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockCandle(
    String symbol,
    LocalDateTime timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume) {}
