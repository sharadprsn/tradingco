package com.kite.trading.service;

import com.kite.trading.dto.OhlcCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Supertrend indicator computed on OHLC candles (typically 5-minute for the Sniper strategy).
 *
 * <p>Standard formulation: ATR(period) with Wilder's smoothing, upper/lower bands at multiplier x
 * ATR around a basic HL2 average, and a single trailing Supertrend line that flips when price
 * crosses it. The trend is bullish when price is above the Supertrend line and bearish when below.
 */
public final class Supertrend {

  private static final int DEFAULT_ATR_PERIOD = 10;
  private static final BigDecimal DEFAULT_MULTIPLIER = BigDecimal.valueOf(3);

  private final int atrPeriod;
  private final BigDecimal multiplier;

  private final List<BigDecimal> trList = new ArrayList<>();
  private final List<BigDecimal> atrList = new ArrayList<>();
  private final List<BigDecimal> supertrendList = new ArrayList<>();
  private final List<Boolean> trendUpList = new ArrayList<>();

  private BigDecimal prevClose;

  public Supertrend() {
    this(DEFAULT_ATR_PERIOD, DEFAULT_MULTIPLIER);
  }

  public Supertrend(final int atrPeriod, final BigDecimal multiplier) {
    this.atrPeriod = atrPeriod;
    this.multiplier = multiplier;
  }

  /**
   * Feeds one candle (in chronological order) and returns the current Supertrend state, or {@code
   * null} until enough candles have been processed to compute the first ATR.
   */
  public State addCandle(final OhlcCandle candle) {
    final BigDecimal tr;
    if (prevClose == null) {
      tr = candle.high().subtract(candle.low()).abs();
    } else {
      final BigDecimal hl = candle.high().subtract(candle.low()).abs();
      final BigDecimal hc = candle.high().subtract(prevClose).abs();
      final BigDecimal lc = candle.low().subtract(prevClose).abs();
      tr = max(hl, max(hc, lc));
    }
    prevClose = candle.close();

    if (atrList.isEmpty()) {
      trList.add(tr);
      if (trList.size() < atrPeriod) {
        return null;
      }
      BigDecimal sum = BigDecimal.ZERO;
      for (final BigDecimal t : trList) {
        sum = sum.add(t);
      }
      atrList.add(sum.divide(BigDecimal.valueOf(atrPeriod), 4, RoundingMode.HALF_UP));
    } else {
      final BigDecimal prevAtr = atrList.get(atrList.size() - 1);
      final BigDecimal atr =
          prevAtr
              .multiply(BigDecimal.valueOf(atrPeriod - 1))
              .add(tr)
              .divide(BigDecimal.valueOf(atrPeriod), 4, RoundingMode.HALF_UP);
      atrList.add(atr);
    }

    final int idx = atrList.size() - 1;
    final BigDecimal atr = atrList.get(idx);
    final BigDecimal hl2 =
        candle.high().add(candle.low()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    final BigDecimal basicUpper = hl2.add(atr.multiply(multiplier));
    final BigDecimal basicLower = hl2.subtract(atr.multiply(multiplier));

    BigDecimal supertrend;
    boolean trendUp;
    if (supertrendList.isEmpty()) {
      trendUp = candle.close().compareTo(basicLower) >= 0;
      supertrend = trendUp ? basicLower : basicUpper;
    } else {
      final BigDecimal prevSt = supertrendList.get(supertrendList.size() - 1);
      final boolean prevTrendUp = trendUpList.get(trendUpList.size() - 1);
      if (prevTrendUp) {
        final BigDecimal finalLower = basicLower.max(prevSt);
        if (candle.close().compareTo(finalLower) > 0) {
          trendUp = true;
          supertrend = finalLower;
        } else {
          trendUp = false;
          supertrend = basicUpper;
        }
      } else {
        final BigDecimal finalUpper = basicUpper.min(prevSt);
        if (candle.close().compareTo(finalUpper) < 0) {
          trendUp = false;
          supertrend = finalUpper;
        } else {
          trendUp = true;
          supertrend = basicLower;
        }
      }
    }
    supertrendList.add(supertrend);
    trendUpList.add(trendUp);
    return new State(supertrend, trendUp);
  }

  public State latest() {
    if (supertrendList.isEmpty()) {
      return null;
    }
    return new State(
        supertrendList.get(supertrendList.size() - 1), trendUpList.get(trendUpList.size() - 1));
  }

  private static BigDecimal max(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  public record State(BigDecimal supertrend, boolean trendUp) {}
}
