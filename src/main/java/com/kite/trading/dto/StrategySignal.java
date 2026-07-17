package com.kite.trading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StrategySignal(
    String symbol,
    String action,
    String type,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal target,
    BigDecimal riskReward,
    LocalDateTime timestamp) {

  public String toTelegramMessage() {
    return "<b>"
        + action
        + " "
        + symbol
        + "</b>\n"
        + "Type: "
        + type
        + "\n"
        + "Entry: "
        + entry
        + "\n"
        + "SL: "
        + stopLoss
        + "\n"
        + "Target: "
        + target
        + "\n"
        + "R:R: 1:"
        + riskReward
        + "\n"
        + "Time: "
        + timestamp;
  }
}
