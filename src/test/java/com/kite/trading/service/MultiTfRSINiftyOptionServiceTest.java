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
class MultiTfRSINiftyOptionServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock private NseOptionChainClient nseClient;
  @Mock private CandlestickPatternService patternService;
  @Mock private TelegramService telegramService;

  private MutableClock clock;
  private MultiTfRSINiftyOptionService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock("10:00", false);
    service = new MultiTfRSINiftyOptionService(nseClient, patternService, telegramService, clock);
  }

  @Test
  void evaluate_shouldSkipWeekends() {
    clock.setTime("10:00", true);
    service.evaluate();
    verifyNoInteractions(nseClient);
  }

  @Test
  void evaluate_shouldSkipBeforeMarketOpen() {
    clock.setTime("09:00");
    service.evaluate();
    verifyNoInteractions(nseClient);
  }

  @Test
  void evaluate_shouldSkipAfterMarketEnd() {
    clock.setTime("15:45");
    service.evaluate();
    verifyNoInteractions(nseClient);
  }

  @Test
  void evaluate_shouldSkipOutsideTradeWindow() {
    clock.setTime("12:00");
    service.evaluate();
    verifyNoInteractions(nseClient);
  }

  @Test
  void evaluate_shouldSkipBeforeTradeStart() {
    clock.setTime("09:20");
    service.evaluate();
    verifyNoInteractions(nseClient);
  }

  @Test
  void evaluate_shouldAccumulateTicksAndBuildCandles() {
    mockNiftyQuote(BigDecimal.valueOf(24100));
    service.evaluate();
    final MultiTfRSINiftyOptionService.TfState state = getState();
    assertEquals(1, state.oneMinTicks.size());
    assertEquals(1, state.tickCount5);
    assertEquals(1, state.tickCount15);
  }

  @Test
  void evaluate_shouldBuildFiveMinCandleAfter5Ticks() {
    for (int i = 0; i < 5; i++) {
      mockNiftyQuote(BigDecimal.valueOf(24100 + i));
      service.evaluate();
    }
    final MultiTfRSINiftyOptionService.TfState state = getState();
    assertEquals(5, state.oneMinTicks.size());
    assertEquals(0, state.tickCount5);
    assertEquals(1, state.candles5.size());
  }

  @Test
  void evaluate_shouldBuildFifteenMinCandleAfter3FiveMinCandles() {
    for (int i = 0; i < 16; i++) {
      mockNiftyQuote(BigDecimal.valueOf(24100 + (i % 5)));
      service.evaluate();
    }
    final MultiTfRSINiftyOptionService.TfState state = getState();
    assertEquals(1, state.tickCount15);
    assertEquals(5, state.candles15.size());
  }

  @Test
  void computeSingleRsi_shouldReturn100WhenNoLosses() {
    final List<OhlcCandle> candles = new ArrayList<>();
    BigDecimal price = BigDecimal.valueOf(24000);
    for (int i = 0; i <= 14; i++) {
      final OhlcCandle c = new OhlcCandle(LocalDateTime.now(), price, price, price, price);
      candles.add(c);
      price = price.add(BigDecimal.TEN);
    }
    final MultiTfRSINiftyOptionService.TfState state = new MultiTfRSINiftyOptionService.TfState();
    final BigDecimal rsi = MultiTfRSINiftyOptionService.computeSingleRsi(state, candles, 14, true);
    assertEquals(0, BigDecimal.valueOf(100).compareTo(rsi));
  }

  @Test
  void computeSingleRsi_shouldReturnZeroWhenNoGains() {
    final List<OhlcCandle> candles = new ArrayList<>();
    BigDecimal price = BigDecimal.valueOf(24100);
    for (int i = 0; i <= 14; i++) {
      final OhlcCandle c = new OhlcCandle(LocalDateTime.now(), price, price, price, price);
      candles.add(c);
      price = price.subtract(BigDecimal.TEN);
    }
    final BigDecimal rsi =
        MultiTfRSINiftyOptionService.computeSingleRsi(
            new MultiTfRSINiftyOptionService.TfState(), candles, 14, true);
    assertEquals(0, BigDecimal.ZERO.compareTo(rsi));
  }

  @Test
  void computeSingleRsi_shouldReturnFiftyWhenEqualGainsAndLosses() {
    final List<OhlcCandle> candles = new ArrayList<>();
    BigDecimal price = BigDecimal.valueOf(24000);
    for (int i = 0; i <= 14; i++) {
      final OhlcCandle c = new OhlcCandle(LocalDateTime.now(), price, price, price, price);
      candles.add(c);
      if (i % 2 == 0) {
        price = price.add(BigDecimal.TEN);
      } else {
        price = price.subtract(BigDecimal.TEN);
      }
    }
    final BigDecimal rsi =
        MultiTfRSINiftyOptionService.computeSingleRsi(
            new MultiTfRSINiftyOptionService.TfState(), candles, 14, true);
    assertTrue(rsi.compareTo(BigDecimal.valueOf(49)) > 0);
    assertTrue(rsi.compareTo(BigDecimal.valueOf(51)) < 0);
  }

  @Test
  void evaluate_shouldNotGenerateSignalWithoutEnoughCandles() {
    mockNiftyQuote(BigDecimal.valueOf(24100));
    service.evaluate();
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void evaluate_shouldGenerateSignalWhenRsiAlignedAndPatternDetected() {
    final int ticksNeeded = 5 * 35;
    for (int i = 0; i < ticksNeeded; i++) {
      final BigDecimal price = BigDecimal.valueOf(i < 70 ? 24500 - i : 24430L + (i - 70L) * 2L);
      mockNiftyQuote(price);
      service.evaluate();
    }
    when(patternService.detectPatterns(anyList())).thenReturn(List.of("BULLISH_ENGULFING"));
    mockOptionChain("CE", BigDecimal.valueOf(24500), BigDecimal.valueOf(80));
    mockNiftyQuote(BigDecimal.valueOf(24500));
    service.evaluate();
    assertFalse(service.getSignals().isEmpty());
  }

  @Test
  void evaluate_shouldNotGenerateSignalOnConflict() {
    final int ticksNeeded = 5 * 35;
    for (int i = 0; i < ticksNeeded; i++) {
      mockNiftyQuote(BigDecimal.valueOf(24100));
      service.evaluate();
    }
    when(patternService.detectPatterns(anyList())).thenReturn(List.of("BULLISH_ENGULFING"));
    mockNiftyQuote(BigDecimal.valueOf(24100));
    service.evaluate();
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void resetDaily_shouldClearAllState() {
    service.resetDaily();
    assertTrue(service.getSignals().isEmpty());
  }

  private void mockNiftyQuote(final BigDecimal price) {
    final IndexData data = new IndexData("NIFTY", price, price, price, price, price);
    final IndexQuote quote = new IndexQuote(List.of(data));
    when(nseClient.fetchIndexQuote("NIFTY")).thenReturn(quote);
  }

  private void mockOptionChain(
      final String type, final BigDecimal strike, final BigDecimal premium) {
    final OptionContract contract =
        new OptionContract(
            strike,
            "24-JUL-2026",
            "NIFTY",
            "NIFTY" + strike + type,
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
            BigDecimal.valueOf(24200));
    final OptionData optionData =
        new OptionData(
            strike,
            "24-JUL-2026",
            "CE".equals(type) ? contract : null,
            "PE".equals(type) ? contract : null);
    final Records records =
        new Records(
            List.of("24-JUL-2026"),
            List.of(optionData),
            "2026-07-24 10:00:00",
            BigDecimal.valueOf(24200),
            List.of(strike));
    final OptionChainData chain = new OptionChainData(records, null);
    when(nseClient.fetchOptionChain("NIFTY")).thenReturn(chain);
  }

  @SuppressWarnings("unchecked")
  private MultiTfRSINiftyOptionService.TfState getState() {
    try {
      final var field = MultiTfRSINiftyOptionService.class.getDeclaredField("states");
      field.setAccessible(true);
      final List<MultiTfRSINiftyOptionService.TfState> states =
          (List<MultiTfRSINiftyOptionService.TfState>) field.get(service);
      return states.isEmpty() ? null : states.getFirst();
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
