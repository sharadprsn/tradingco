package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.IndexQuote.IndexData;
import com.kite.trading.dto.OhlcCandle;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SniperStrategyServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock private NseOptionChainClient nseClient;
  @Mock private OptionChainClient optionChainClient;
  @Mock private TelegramService telegramService;

  private MutableClock clock;
  private SniperStrategyService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock("10:00", false);
    service = new SniperStrategyService(nseClient, optionChainClient, telegramService, clock);
  }

  @Test
  void evaluate_shouldSkipWeekends() {
    clock.setTime("10:00", true);
    service.evaluate();
    verifyNoInteractions(nseClient, optionChainClient, telegramService);
  }

  @Test
  void evaluate_shouldSkipOutsideMarketHours() {
    clock.setTime("08:00");
    service.evaluate();
    verifyNoInteractions(optionChainClient, telegramService);
  }

  @Test
  void evaluate_shouldRefreshPremarketContextBeforeBell() {
    clock.setTime("09:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchFiiDii())
        .thenReturn(
            new NseOptionChainClient.FiiDiiResponse(
                List.of(
                    new NseOptionChainClient.FiiDiiItem(
                        "FII", BigDecimal.valueOf(1000), BigDecimal.valueOf(800)),
                    new NseOptionChainClient.FiiDiiItem(
                        "DII", BigDecimal.valueOf(600), BigDecimal.valueOf(500)))));
    service.evaluate();
    verify(nseClient).fetchIndiaVix();
    verify(nseClient).fetchFiiDii();
  }

  @Test
  void evaluate_shouldLoadPreviousDayLevels() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    mockIndexQuote(
        "NIFTY",
        BigDecimal.valueOf(24100),
        BigDecimal.valueOf(24000),
        BigDecimal.valueOf(24050),
        BigDecimal.valueOf(23950));
    service.evaluate();
    final SniperStrategyService.IndexState state = getState("NIFTY");
    assertNotNull(state);
    assertTrue(state.pdhPdlLoaded);
    assertEquals(0, BigDecimal.valueOf(24200).compareTo(state.pdh));
    assertEquals(0, BigDecimal.valueOf(23900).compareTo(state.pdl));
  }

  @Test
  void evaluate_shouldWaitDuringOpeningRange() {
    clock.setTime("09:30");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    mockIndexQuote(
        "NIFTY",
        BigDecimal.valueOf(24100),
        BigDecimal.valueOf(24000),
        BigDecimal.valueOf(24050),
        BigDecimal.valueOf(23950));
    lenient()
        .when(optionChainClient.fetchOptionChain("NIFTY"))
        .thenReturn(optionChain("NIFTY", "CE", BigDecimal.valueOf(24500), BigDecimal.valueOf(80)));
    service.evaluate();
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void evaluate_shouldGenerateLongSignalOnBreakoutWithVolumeAndSupertrend() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    // Rising ticks above PDH/OR high build a bullish 5m Supertrend + volume confirmation.
    when(optionChainClient.fetchOptionChain("NIFTY"))
        .thenReturn(optionChain("NIFTY", "CE", BigDecimal.valueOf(24500), BigDecimal.valueOf(80)));
    BigDecimal price = BigDecimal.valueOf(24400);
    for (int i = 0; i < 130; i++) {
      mockIndexQuote(
          "NIFTY",
          price,
          price.add(BigDecimal.valueOf(250)),
          BigDecimal.valueOf(24050),
          price.subtract(BigDecimal.valueOf(250)));
      service.evaluate();
      price = price.add(BigDecimal.valueOf(20));
    }
    assertFalse(service.getSignals().isEmpty());
    final var signal = service.getSignals().getLast();
    assertEquals("LONG", signal.direction());
    assertEquals("CE", signal.optionType());
    assertTrue(signal.volumeConfirmed());
    assertTrue(signal.emaAligned());
  }

  @Test
  void evaluate_shouldExitLongWhenPriceCrossesBelowSupertrend() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    when(optionChainClient.fetchOptionChain("NIFTY"))
        .thenReturn(optionChain("NIFTY", "CE", BigDecimal.valueOf(24500), BigDecimal.valueOf(80)));
    // Phase 1: rising ticks build a LONG position.
    BigDecimal price = BigDecimal.valueOf(24400);
    for (int i = 0; i < 130; i++) {
      mockIndexQuote(
          "NIFTY",
          price,
          price.add(BigDecimal.valueOf(250)),
          BigDecimal.valueOf(24050),
          price.subtract(BigDecimal.valueOf(250)));
      service.evaluate();
      price = price.add(BigDecimal.valueOf(20));
    }
    assertFalse(service.getSignals().isEmpty());
    assertEquals("LONG", service.getSignals().getLast().direction());

    // Phase 2: falling ticks push price below the 5m Supertrend line -> EXIT.
    for (int i = 0; i < 130; i++) {
      mockIndexQuote(
          "NIFTY",
          price,
          price.add(BigDecimal.valueOf(50)),
          BigDecimal.valueOf(24050),
          price.subtract(BigDecimal.valueOf(300)));
      service.evaluate();
      price = price.subtract(BigDecimal.valueOf(50));
    }
    final boolean exited = service.getSignals().stream().anyMatch(s -> "EXIT".equals(s.status()));
    assertTrue(exited);
    final var exit =
        service.getSignals().stream()
            .filter(s -> "EXIT".equals(s.status()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertEquals("SHORT", exit.direction());
    assertEquals("SUPERTREND_EXIT", exit.triggeredLevel());
  }

  @Test
  void evaluate_shouldNotGenerateSignalWithoutVolumeConfirmation() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    // Same price every tick -> zero range -> no volume -> no confirmation.
    final BigDecimal flat = BigDecimal.valueOf(24500);
    final IndexData data =
        new IndexData("NIFTY", flat, flat, flat, flat, BigDecimal.valueOf(24000));
    when(optionChainClient.fetchIndexQuote("NIFTY")).thenReturn(new IndexQuote(List.of(data)));
    lenient()
        .when(optionChainClient.fetchOptionChain("NIFTY"))
        .thenReturn(optionChain("NIFTY", "CE", BigDecimal.valueOf(24500), BigDecimal.valueOf(80)));
    service.evaluate();
    assertTrue(service.getSignals().isEmpty());
  }

  @Test
  void evaluate_shouldGenerateShortSignalOnBreakdown() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    // Falling ticks below PDL build a bearish 5m Supertrend + volume confirmation.
    when(optionChainClient.fetchOptionChain("NIFTY"))
        .thenReturn(optionChain("NIFTY", "PE", BigDecimal.valueOf(23800), BigDecimal.valueOf(80)));
    BigDecimal price = BigDecimal.valueOf(24000);
    for (int i = 0; i < 130; i++) {
      mockIndexQuote(
          "NIFTY",
          price,
          price.add(BigDecimal.valueOf(100)),
          BigDecimal.valueOf(24050),
          price.subtract(BigDecimal.valueOf(300)));
      service.evaluate();
      price = price.subtract(BigDecimal.valueOf(50));
    }
    assertFalse(service.getSignals().isEmpty());
    assertEquals("SHORT", service.getSignals().getLast().direction());
    assertEquals("PE", service.getSignals().getLast().optionType());
  }

  @Test
  void evaluate_shouldHandleSensexQuote() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(12));
    when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    when(optionChainClient.fetchOptionChain("SENSEX"))
        .thenReturn(
            optionChain("SENSEX", "CE", BigDecimal.valueOf(81000), BigDecimal.valueOf(120)));
    BigDecimal price = BigDecimal.valueOf(80500);
    for (int i = 0; i < 55; i++) {
      mockIndexQuote(
          "SENSEX",
          price,
          price.add(BigDecimal.valueOf(250)),
          BigDecimal.valueOf(80500),
          price.subtract(BigDecimal.valueOf(250)));
      service.evaluate();
      price = price.add(BigDecimal.valueOf(100));
    }
    assertFalse(service.getSignals().isEmpty());
    assertEquals("SENSEX", service.getSignals().getLast().index());
  }

  @Test
  void evaluate_shouldSkipWhenVixAboveGate() {
    clock.setTime("10:00");
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(21));
    lenient().when(nseClient.fetchIndexDailyCandle()).thenReturn(previousDayCandles());
    lenient()
        .when(optionChainClient.fetchIndexQuote(anyString()))
        .thenReturn(
            new IndexQuote(
                List.of(
                    new IndexData(
                        "NIFTY",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(24050),
                        BigDecimal.valueOf(23950),
                        BigDecimal.valueOf(24000)))));
    service.evaluate();
    verify(optionChainClient, never()).fetchOptionChain(anyString());
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void resetDaily_shouldClearAllState() {
    service.resetDaily();
    assertTrue(service.getSignals().isEmpty());
  }

  @Test
  void refreshPremarketContext_shouldComputeFiiDiiNet() {
    when(nseClient.fetchIndiaVix()).thenReturn(BigDecimal.valueOf(11));
    when(nseClient.fetchFiiDii())
        .thenReturn(
            new NseOptionChainClient.FiiDiiResponse(
                List.of(
                    new NseOptionChainClient.FiiDiiItem(
                        "FII", BigDecimal.valueOf(1000), BigDecimal.valueOf(700)),
                    new NseOptionChainClient.FiiDiiItem(
                        "DII", BigDecimal.valueOf(500), BigDecimal.valueOf(450)))));
    clock.setTime("08:30");
    service.evaluate();
    // Re-run on same day should not re-fetch.
    service.evaluate();
    verify(nseClient, times(1)).fetchIndiaVix();
    verify(nseClient, times(1)).fetchFiiDii();
  }

  private static List<OhlcCandle> previousDayCandles() {
    // Single prior-session daily (1d) candle: PDH=24200, PDL=23900.
    final OhlcCandle daily =
        new OhlcCandle(
            LocalDateTime.of(2026, 7, 16, 15, 30),
            BigDecimal.valueOf(24000),
            BigDecimal.valueOf(24200),
            BigDecimal.valueOf(23900),
            BigDecimal.valueOf(24050));
    return List.of(daily);
  }

  private void mockIndexQuote(
      final String symbol,
      final BigDecimal last,
      final BigDecimal high,
      final BigDecimal prevClose,
      final BigDecimal low) {
    final IndexData data = new IndexData(symbol, last, high, low, last, prevClose);
    when(optionChainClient.fetchIndexQuote(symbol)).thenReturn(new IndexQuote(List.of(data)));
  }

  private static OptionChainData optionChain(
      final String symbol, final String type, final BigDecimal strike, final BigDecimal premium) {
    final List<OptionData> data = new ArrayList<>();
    final List<BigDecimal> strikes = new ArrayList<>();
    // Build a wide band of strikes around the requested one so the strategy can always locate a
    // suitable contract near the live spot price (candidate strikes are derived from spot).
    for (int s = -50000; s <= 50000; s += 50) {
      final BigDecimal k = strike.add(BigDecimal.valueOf(s));
      strikes.add(k);
      final OptionContract contract =
          new OptionContract(
              k,
              "24-JUL-2026",
              symbol,
              symbol + k + type,
              BigDecimal.valueOf(100000),
              BigDecimal.valueOf(5000),
              BigDecimal.valueOf(5),
              BigDecimal.valueOf(50000),
              BigDecimal.valueOf(15),
              premium,
              BigDecimal.valueOf(10),
              BigDecimal.valueOf(2),
              BigDecimal.valueOf(1000000),
              BigDecimal.valueOf(800000),
              BigDecimal.valueOf(100),
              BigDecimal.valueOf(75),
              BigDecimal.valueOf(100),
              BigDecimal.valueOf(85),
              k);
      data.add(
          new OptionData(
              k,
              "24-JUL-2026",
              "CE".equals(type) ? contract : null,
              "PE".equals(type) ? contract : null));
    }
    final Records records =
        new Records(List.of("24-JUL-2026"), data, "2026-07-24 10:00:00", strike, strikes);
    return new OptionChainData(records, null);
  }

  @SuppressWarnings("unchecked")
  private SniperStrategyService.IndexState getState(final String index) {
    try {
      final var field = SniperStrategyService.class.getDeclaredField("states");
      field.setAccessible(true);
      final java.util.Map<String, SniperStrategyService.IndexState> map =
          (java.util.Map<String, SniperStrategyService.IndexState>) field.get(service);
      return map.get(index);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
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
