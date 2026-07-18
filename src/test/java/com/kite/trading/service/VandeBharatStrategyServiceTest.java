package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import com.kite.trading.dto.StockQuote;
import com.kite.trading.dto.StockQuote.PriceInfo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VandeBharatStrategyServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock private NseOptionChainClient nseClient;

  @Mock private TelegramService telegramService;

  private MutableClock clock;
  private VandeBharatStrategyService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock("09:35", false);
    service = new VandeBharatStrategyService(nseClient, telegramService, clock);
  }

  @Test
  void analyze_shouldSkipBeforeMarketOpen() {
    clock.setTime("09:00");
    service.analyze();
    verifyNoInteractions(nseClient);
  }

  @Test
  void analyze_shouldSkipAfterMarketClose() {
    clock.setTime("15:45");
    service.analyze();
    verifyNoInteractions(nseClient);
  }

  @Test
  void analyze_shouldSkipWeekends() {
    clock.setTime("10:00", true);
    service.analyze();
    verifyNoInteractions(nseClient);
  }

  @Test
  void analyze_shouldSkipBeforeNoTradeZone() {
    clock.setTime("09:20");
    service.analyze();
    verifyNoInteractions(nseClient);
  }

  @Test
  void analyze_shouldInitializeOnFirstCallAndNotSendSignal() {
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(103),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(50000));

    service.analyze();

    verify(nseClient, atLeastOnce()).fetchEquityQuote(anyString());
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void analyze_shouldHandleNullQuote() {
    when(nseClient.fetchEquityQuote(anyString())).thenReturn(null);

    service.analyze();

    verify(nseClient, atLeastOnce()).fetchEquityQuote(anyString());
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void analyze_shouldHandleQuoteWithNullPriceInfo() {
    when(nseClient.fetchEquityQuote(anyString())).thenReturn(new StockQuote(null));

    service.analyze();

    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void signals_shouldBeEmptyInitially() {
    assertTrue(service.getSignals().isEmpty());
  }

  @Test
  void resetDaily_shouldClearState() {
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(103),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(50000));
    service.analyze();

    service.resetDaily();

    assertTrue(service.getSignals().isEmpty());
  }

  @Test
  void enterTrade_shouldHandleUnknownSymbol() {
    service.enterTrade("UNKNOWN");
    assertTrue(service.getSignals().isEmpty());
  }

  @Test
  void shouldGenerateEntrySignalAfterBreakoutAndInsideCandle() {
    // prevClose=100 → PDH=101

    // Tick 1 (9:35): init
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(102),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(40000));
    clock.setTime("09:35");
    service.analyze();

    // Tick 2 (9:40): breakout candle, vol=10000
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(103),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(50000));
    clock.setTime("09:40");
    service.analyze();

    // Tick 3 (9:45): inside candle, vol=3000 (<= breakout vol 10000 ✓)
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(102),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(53000));
    clock.setTime("09:45");
    service.analyze();

    // Signal should NOT be generated yet — waiting for break of inside candle's high
    assertTrue(service.getSignals().isEmpty());

    // Tick 4 (9:50): entry trigger, vol=5000 (> inside vol 3000 ✓), close=104 >
    // insideCandle.high=103
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(104),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(58000));
    clock.setTime("09:50");
    service.analyze();

    final var signals = service.getSignals();
    assertEquals(1, signals.size());
    final var signal = signals.get(0);
    assertEquals("TATASTEEL", signal.symbol());
    assertEquals("LONG", signal.direction());
    assertEquals(0, BigDecimal.valueOf(103).compareTo(signal.entryPrice()));
    assertEquals(0, BigDecimal.valueOf(102).compareTo(signal.stopLoss()));
    assertEquals("SIGNAL_READY", signal.status());
    verify(telegramService).sendMessage(contains("VANDE BHARAT SIGNAL"));
  }

  @Test
  void isInsideCandle_shouldAcceptEqualBounds() {
    final var breakout =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.ZERO);
    final var candle =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(102),
            BigDecimal.ZERO);

    assertTrue(VandeBharatStrategyService.isInsideCandle(breakout, candle));
  }

  @Test
  void isInsideCandle_shouldAcceptStrictlyInside() {
    final var breakout =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.ZERO);
    final var candle =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102.5),
            BigDecimal.valueOf(102.3),
            BigDecimal.valueOf(102.4),
            BigDecimal.ZERO);

    assertTrue(VandeBharatStrategyService.isInsideCandle(breakout, candle));
  }

  @Test
  void isInsideCandle_shouldRejectHigherHigh() {
    final var breakout =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.ZERO);
    final var candle =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(104),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103.5),
            BigDecimal.ZERO);

    assertFalse(VandeBharatStrategyService.isInsideCandle(breakout, candle));
  }

  @Test
  void isInsideCandle_shouldRejectLowerLow() {
    final var breakout =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.ZERO);
    final var candle =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(101),
            BigDecimal.valueOf(102),
            BigDecimal.ZERO);

    assertFalse(VandeBharatStrategyService.isInsideCandle(breakout, candle));
  }

  @Test
  void isInsideCandle_shouldRejectBothSides() {
    final var breakout =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(102),
            BigDecimal.valueOf(103),
            BigDecimal.ZERO);
    final var candle =
        new VandeBharatStrategyService.FiveMinCandle(
            LocalDateTime.now(),
            BigDecimal.valueOf(103),
            BigDecimal.valueOf(104),
            BigDecimal.valueOf(101),
            BigDecimal.valueOf(102),
            BigDecimal.ZERO);

    assertFalse(VandeBharatStrategyService.isInsideCandle(breakout, candle));
  }

  @Test
  void shouldExitTradeWhenCloseBelowEma10() {
    // ---- Entry phase ----
    // Tick 1 (9:35): init
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(102),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(40000));
    clock.setTime("09:35");
    service.analyze();

    // Tick 2 (9:40): breakout
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(103),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(50000));
    clock.setTime("09:40");
    service.analyze();

    // Tick 3 (9:45): inside candle
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(102),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(53000));
    clock.setTime("09:45");
    service.analyze();

    assertTrue(service.getSignals().isEmpty());

    // Tick 4 (9:50): entry trigger, vol=5000 > inside vol 3000 ✓
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(104),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(58000));
    clock.setTime("09:50");
    service.analyze();

    assertEquals(1, service.getSignals().size());
    service.enterTrade("TATASTEEL");

    // ---- Accumulate 7 more candles (total 10) for SMA ----
    // Current candles: [close=103, close=102, close=104] = 3
    // Need 7 more: 9:55 through 10:25
    long vol = 63000;
    for (int count = 0; count < 7; count++) {
      final int totalMin = 9 * 60 + 55 + count * 5;
      final String time = String.format("%02d:%02d", totalMin / 60, totalMin % 60);
      mockQuoteFor(
          "TATASTEEL",
          BigDecimal.valueOf(100),
          BigDecimal.valueOf(105),
          BigDecimal.valueOf(98),
          BigDecimal.valueOf(103),
          BigDecimal.valueOf(100),
          BigDecimal.valueOf(vol));
      clock.setTime(time);
      service.analyze();
      vol += 5000;
    }

    // After tick at 10:25, 10th candle added → SMA = (103+102+104+103*7)/10 = 103.0
    // Trailing stop moved to 103 after first post-entry tick (highest=104, stopDist=1)
    // checkExit: trailing 103 < close 103? No → EMA: 103 < 103.0? No → no exit

    // ---- Tick at 10:30: close=102 triggers trailing stop (102 < 103) ----
    mockQuoteFor(
        "TATASTEEL",
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(105),
        BigDecimal.valueOf(98),
        BigDecimal.valueOf(102),
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(vol));
    clock.setTime("10:30");
    service.analyze();

    verify(telegramService, atLeast(1)).sendMessage(contains("EXIT"));
  }

  private void mockQuoteFor(
      final String symbol,
      final BigDecimal open,
      final BigDecimal high,
      final BigDecimal low,
      final BigDecimal lastPrice,
      final BigDecimal previousClose,
      final BigDecimal volume) {
    final StockQuote quote =
        new StockQuote(new PriceInfo(open, high, low, lastPrice, previousClose, volume));
    when(nseClient.fetchEquityQuote(anyString()))
        .thenAnswer(inv -> symbol.equals(inv.getArgument(0)) ? quote : null);
  }

  private static class MutableClock extends Clock {

    private Instant now;

    MutableClock(final String time, final boolean saturday) {
      setTime(time, saturday);
    }

    void setTime(final String time) {
      setTime(time, false);
    }

    void setTime(final String time, final boolean saturday) {
      final String[] parts = time.split(":");
      final int hour = Integer.parseInt(parts[0]);
      final int minute = Integer.parseInt(parts[1]);
      final int day = saturday ? 18 : 17;
      this.now = LocalDateTime.of(2026, 7, day, hour, minute).atZone(IST).toInstant();
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return IST;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }
  }
}
