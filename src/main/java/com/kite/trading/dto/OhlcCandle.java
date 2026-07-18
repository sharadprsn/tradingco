package com.kite.trading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OhlcCandle(
    LocalDateTime timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}
