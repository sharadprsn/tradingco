package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kite.trading.dto.OhlcCandle;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SupertrendTest {

  private static OhlcCandle candle(
      final BigDecimal o, final BigDecimal h, final BigDecimal l, final BigDecimal c) {
    return new OhlcCandle(LocalDateTime.now(), o, h, l, c);
  }

  @Test
  void addCandle_returnsNullUntilAtrPeriodCandles() {
    final Supertrend st = new Supertrend(10, BigDecimal.valueOf(3));
    for (int i = 0; i < 9; i++) {
      assertNull(st.addCandle(candle(bd(100), bd(110), bd(90), bd(105))));
    }
    assertNotNull(st.addCandle(candle(bd(100), bd(110), bd(90), bd(105))));
  }

  @Test
  void addCandle_turnsBullishWhenPriceStaysAboveLowerBand() {
    final Supertrend st = new Supertrend(3, BigDecimal.valueOf(3));
    // Seed enough candles with a clear uptrend so the trend flips bullish.
    BigDecimal price = bd(100);
    for (int i = 0; i < 10; i++) {
      st.addCandle(candle(price, price.add(bd(5)), price.subtract(bd(2)), price.add(bd(4))));
      price = price.add(bd(4));
    }
    final Supertrend.State latest = st.latest();
    assertNotNull(latest);
    assertTrue(latest.trendUp(), "expected bullish Supertrend after sustained uptrend");
  }

  @Test
  void addCandle_turnsBearishWhenPriceStaysBelowUpperBand() {
    final Supertrend st = new Supertrend(3, BigDecimal.valueOf(3));
    BigDecimal price = bd(200);
    for (int i = 0; i < 10; i++) {
      st.addCandle(candle(price, price.add(bd(2)), price.subtract(bd(5)), price.subtract(bd(4))));
      price = price.subtract(bd(4));
    }
    final Supertrend.State latest = st.latest();
    assertNotNull(latest);
    assertFalse(latest.trendUp(), "expected bearish Supertrend after sustained downtrend");
  }

  @Test
  void addCandle_supertrendLineStaysNearPriceInTrend() {
    final Supertrend st = new Supertrend(5, BigDecimal.valueOf(2));
    BigDecimal price = bd(150);
    for (int i = 0; i < 12; i++) {
      st.addCandle(candle(price, price.add(bd(3)), price.subtract(bd(3)), price));
      price = price.add(bd(1));
    }
    final Supertrend.State latest = st.latest();
    assertNotNull(latest);
    // Supertrend line should be within a few % of price, not wildly detached.
    final BigDecimal diff = latest.supertrend().subtract(price.subtract(bd(12))).abs();
    assertTrue(diff.compareTo(bd(50)) < 0);
  }

  private static BigDecimal bd(final long v) {
    return BigDecimal.valueOf(v);
  }
}
