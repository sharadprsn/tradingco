package com.kite.trading.dto;

import java.time.LocalDateTime;

public record MarketBreadth(int advances, int declines, int unchanged, LocalDateTime timestamp) {

  public boolean isBullish() {
    return advances > declines;
  }

  public boolean isBearish() {
    return declines > advances;
  }

  public boolean isNeutral() {
    return advances == declines;
  }
}
