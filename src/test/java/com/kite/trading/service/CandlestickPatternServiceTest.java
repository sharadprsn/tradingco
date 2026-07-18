package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kite.trading.dto.OhlcCandle;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CandlestickPatternServiceTest {

  private CandlestickPatternService service;

  @BeforeEach
  void setUp() {
    service = new CandlestickPatternService();
  }

  @Test
  void isHammer_shouldDetectHammerPattern() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(80),
            new BigDecimal("101.6"));
    assertTrue(service.isHammer(candle));
  }

  @Test
  void isHammer_shouldRejectNonHammer() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(110),
            BigDecimal.valueOf(90), BigDecimal.valueOf(105));
    assertFalse(service.isHammer(candle));
  }

  @Test
  void isInvertedHammer_shouldDetectInvertedHammer() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(115),
            new BigDecimal("98.8"),
            BigDecimal.valueOf(99));
    assertTrue(service.isInvertedHammer(candle));
  }

  @Test
  void isInvertedHammer_shouldRejectNonInvertedHammer() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(105),
            BigDecimal.valueOf(95), BigDecimal.valueOf(103));
    assertFalse(service.isInvertedHammer(candle));
  }

  @Test
  void isDoji_shouldDetectDoji() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(102),
            BigDecimal.valueOf(98), BigDecimal.valueOf(100));
    assertTrue(service.isDoji(candle));
  }

  @Test
  void isDoji_shouldRejectNonDoji() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(110),
            BigDecimal.valueOf(90), BigDecimal.valueOf(105));
    assertFalse(service.isDoji(candle));
  }

  @Test
  void isBullishEngulfing_shouldDetectPattern() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(105), BigDecimal.valueOf(105),
            BigDecimal.valueOf(98), BigDecimal.valueOf(98));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(97), BigDecimal.valueOf(108),
            BigDecimal.valueOf(97), BigDecimal.valueOf(107));
    assertTrue(service.isBullishEngulfing(prev, curr));
  }

  @Test
  void isBullishEngulfing_shouldRejectNonEngulfing() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(105), BigDecimal.valueOf(105),
            BigDecimal.valueOf(100), BigDecimal.valueOf(102));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(104), BigDecimal.valueOf(106),
            BigDecimal.valueOf(103), BigDecimal.valueOf(105));
    assertFalse(service.isBullishEngulfing(prev, curr));
  }

  @Test
  void isBearishEngulfing_shouldDetectPattern() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(105),
            BigDecimal.valueOf(100), BigDecimal.valueOf(105));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(106), BigDecimal.valueOf(106),
            BigDecimal.valueOf(97), BigDecimal.valueOf(97));
    assertTrue(service.isBearishEngulfing(prev, curr));
  }

  @Test
  void isBearishEngulfing_shouldRejectNonEngulfing() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(104),
            BigDecimal.valueOf(100), BigDecimal.valueOf(103));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(104), BigDecimal.valueOf(104),
            BigDecimal.valueOf(98), BigDecimal.valueOf(101));
    assertFalse(service.isBearishEngulfing(prev, curr));
  }

  @Test
  void isOpenHigh_shouldDetectPattern() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(105), BigDecimal.valueOf(105),
            BigDecimal.valueOf(98), BigDecimal.valueOf(103));
    assertTrue(service.isOpenHigh(candle));
  }

  @Test
  void isOpenLow_shouldDetectPattern() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(98), BigDecimal.valueOf(105),
            BigDecimal.valueOf(98), BigDecimal.valueOf(103));
    assertTrue(service.isOpenLow(candle));
  }

  @Test
  void isBullishMarubozu_shouldDetectPattern() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(105),
            BigDecimal.valueOf(100), BigDecimal.valueOf(105));
    assertTrue(service.isBullishMarubozu(candle));
  }

  @Test
  void isBearishMarubozu_shouldDetectPattern() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(105), BigDecimal.valueOf(105),
            BigDecimal.valueOf(100), BigDecimal.valueOf(100));
    assertTrue(service.isBearishMarubozu(candle));
  }

  @Test
  void detectPatterns_shouldReturnMultiplePatterns() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(105), BigDecimal.valueOf(105),
            BigDecimal.valueOf(98), BigDecimal.valueOf(98));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(97), BigDecimal.valueOf(108),
            BigDecimal.valueOf(97), BigDecimal.valueOf(107));
    final List<String> patterns = service.detectPatterns(List.of(prev, curr));
    assertTrue(patterns.contains("BULLISH_ENGULFING"));
  }

  @Test
  void detectPatterns_shouldReturnEmptyForNoMatch() {
    final OhlcCandle prev =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(102),
            BigDecimal.valueOf(98), BigDecimal.valueOf(101));
    final OhlcCandle curr =
        candle(
            BigDecimal.valueOf(101), BigDecimal.valueOf(103),
            BigDecimal.valueOf(100), BigDecimal.valueOf(102));
    final List<String> patterns = service.detectPatterns(List.of(prev, curr));
    assertTrue(patterns.isEmpty());
  }

  @Test
  void detectPatterns_shouldHandleNull() {
    final List<String> patterns = service.detectPatterns(null);
    assertTrue(patterns.isEmpty());
  }

  @Test
  void detectPatterns_shouldHandleSingleCandle() {
    final OhlcCandle candle =
        candle(
            BigDecimal.valueOf(100), BigDecimal.valueOf(100),
            BigDecimal.valueOf(100), BigDecimal.valueOf(100));
    final List<String> patterns = service.detectPatterns(List.of(candle));
    assertTrue(patterns.isEmpty());
  }

  private static OhlcCandle candle(
      final BigDecimal open, final BigDecimal high, final BigDecimal low, final BigDecimal close) {
    return new OhlcCandle(LocalDateTime.now(), open, high, low, close);
  }
}
