package com.kite.trading.service;

import com.kite.trading.dto.OhlcCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CandlestickPatternService {

  private static final BigDecimal DOJI_THRESHOLD = BigDecimal.valueOf(0.05);
  private static final BigDecimal WICK_BODY_RATIO = BigDecimal.valueOf(2);

  public List<String> detectPatterns(final List<OhlcCandle> candles) {
    final List<String> patterns = new ArrayList<>();
    if (candles == null || candles.size() < 2) {
      return patterns;
    }
    final OhlcCandle latest = candles.getLast();
    final OhlcCandle prev = candles.get(candles.size() - 2);

    if (isHammer(latest)) {
      patterns.add("HAMMER");
    }
    if (isInvertedHammer(latest)) {
      patterns.add("INVERTED_HAMMER");
    }
    if (isDoji(latest)) {
      patterns.add("DOJI");
    }
    if (isBullishEngulfing(prev, latest)) {
      patterns.add("BULLISH_ENGULFING");
    }
    if (isBearishEngulfing(prev, latest)) {
      patterns.add("BEARISH_ENGULFING");
    }
    if (isOpenHigh(latest)) {
      patterns.add("OPEN_HIGH");
    }
    if (isOpenLow(latest)) {
      patterns.add("OPEN_LOW");
    }
    if (isBullishMarubozu(latest)) {
      patterns.add("BULLISH_MARUBOZU");
    }
    if (isBearishMarubozu(latest)) {
      patterns.add("BEARISH_MARUBOZU");
    }
    return patterns;
  }

  public boolean isHammer(final OhlcCandle candle) {
    final BigDecimal body = candle.close().subtract(candle.open()).abs();
    final BigDecimal lowerWick =
        candle.open().compareTo(candle.close()) < 0
            ? candle.open().subtract(candle.low())
            : candle.close().subtract(candle.low());
    final BigDecimal upperWick =
        candle.open().compareTo(candle.close()) < 0
            ? candle.high().subtract(candle.close())
            : candle.high().subtract(candle.open());
    return body.compareTo(BigDecimal.ZERO) > 0
        && lowerWick.compareTo(body.multiply(WICK_BODY_RATIO)) >= 0
        && upperWick.compareTo(body.multiply(BigDecimal.valueOf(0.3))) <= 0;
  }

  public boolean isInvertedHammer(final OhlcCandle candle) {
    final BigDecimal body = candle.close().subtract(candle.open()).abs();
    final BigDecimal upperWick =
        candle.open().compareTo(candle.close()) < 0
            ? candle.high().subtract(candle.close())
            : candle.high().subtract(candle.open());
    final BigDecimal lowerWick =
        candle.open().compareTo(candle.close()) < 0
            ? candle.open().subtract(candle.low())
            : candle.close().subtract(candle.low());
    return body.compareTo(BigDecimal.ZERO) > 0
        && upperWick.compareTo(body.multiply(WICK_BODY_RATIO)) >= 0
        && lowerWick.compareTo(body.multiply(BigDecimal.valueOf(0.3))) <= 0;
  }

  public boolean isDoji(final OhlcCandle candle) {
    final BigDecimal range = candle.high().subtract(candle.low());
    if (range.compareTo(BigDecimal.ZERO) == 0) {
      return false;
    }
    final BigDecimal body = candle.close().subtract(candle.open()).abs();
    return body.divide(range, 4, RoundingMode.HALF_UP).compareTo(DOJI_THRESHOLD) <= 0;
  }

  public boolean isBullishEngulfing(final OhlcCandle prev, final OhlcCandle curr) {
    final BigDecimal prevBody = prev.close().subtract(prev.open());
    final BigDecimal currBody = curr.close().subtract(curr.open());
    return prevBody.compareTo(BigDecimal.ZERO) < 0
        && currBody.compareTo(BigDecimal.ZERO) > 0
        && curr.open().compareTo(prev.close()) <= 0
        && curr.close().compareTo(prev.open()) >= 0;
  }

  public boolean isBearishEngulfing(final OhlcCandle prev, final OhlcCandle curr) {
    final BigDecimal prevBody = prev.close().subtract(prev.open());
    final BigDecimal currBody = curr.close().subtract(curr.open());
    return prevBody.compareTo(BigDecimal.ZERO) > 0
        && currBody.compareTo(BigDecimal.ZERO) < 0
        && curr.open().compareTo(prev.close()) >= 0
        && curr.close().compareTo(prev.open()) <= 0;
  }

  public boolean isOpenHigh(final OhlcCandle candle) {
    return candle.open().compareTo(candle.high()) == 0
        && candle.close().compareTo(candle.low()) > 0;
  }

  public boolean isOpenLow(final OhlcCandle candle) {
    return candle.open().compareTo(candle.low()) == 0
        && candle.close().compareTo(candle.high()) < 0;
  }

  public boolean isBullishMarubozu(final OhlcCandle candle) {
    final BigDecimal body = candle.close().subtract(candle.open());
    final BigDecimal upperWick = candle.high().subtract(candle.close());
    final BigDecimal lowerWick = candle.open().subtract(candle.low());
    final BigDecimal range = candle.high().subtract(candle.low());
    if (range.compareTo(BigDecimal.ZERO) == 0) {
      return false;
    }
    return body.compareTo(BigDecimal.ZERO) > 0
        && upperWick.divide(range, 4, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.05)) <= 0
        && lowerWick.divide(range, 4, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.05))
            <= 0;
  }

  public boolean isBearishMarubozu(final OhlcCandle candle) {
    final BigDecimal body = candle.close().subtract(candle.open());
    final BigDecimal upperWick = candle.high().subtract(candle.open());
    final BigDecimal lowerWick = candle.close().subtract(candle.low());
    final BigDecimal range = candle.high().subtract(candle.low());
    if (range.compareTo(BigDecimal.ZERO) == 0) {
      return false;
    }
    return body.compareTo(BigDecimal.ZERO) < 0
        && upperWick.divide(range, 4, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.05)) <= 0
        && lowerWick.divide(range, 4, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.05))
            <= 0;
  }
}
