package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OiAnalysisResult;
import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import com.kite.trading.repository.OiSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OiAnalysisServiceTest {

  @Mock private NseOptionChainClient nseClient;

  @Mock private TelegramService telegramService;

  @Mock private OiSnapshotRepository snapshotRepository;

  @Mock private LstmPredictionClient lstmClient;

  @Mock private SentimentClient sentimentClient;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private OiAnalysisService service;

  @BeforeEach
  void setUp() {
    final Clock morningClock =
        Clock.fixed(Instant.parse("2026-07-16T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
    lenient()
        .when(sentimentClient.getSentiment())
        .thenReturn(
            new SentimentClient.SentimentResult(BigDecimal.ZERO, "neutral", List.of(), false));
    service =
        new OiAnalysisService(
            nseClient,
            telegramService,
            snapshotRepository,
            objectMapper,
            lstmClient,
            sentimentClient,
            morningClock);
  }

  @Test
  void fetchAndRecordOi_returnsNull_whenDataIsNull() {
    when(nseClient.fetchOptionChain(anyString())).thenReturn(null);

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNull(result);
    assertTrue(service.getSnapshots().isEmpty());
  }

  @Test
  void fetchAndRecordOi_returnsNull_whenRecordsIsNull() {
    when(nseClient.fetchOptionChain(anyString())).thenReturn(new OptionChainData(null, null));

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNull(result);
  }

  @Test
  void fetchAndRecordOi_returnsNull_whenUnderlyingIsNull() {
    final var options = List.of(optionData(BigDecimal.valueOf(24200), null, null));
    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(new OptionChainData(new Records(null, options, null, null, null), null));

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNull(result);
  }

  @Test
  void fetchAndRecordOi_filtersToNearStrikesOnly() {
    final var farStrike =
        optionData(
            BigDecimal.valueOf(25000),
            contract(BigDecimal.valueOf(1000), BigDecimal.valueOf(100)),
            contract(BigDecimal.valueOf(2000), BigDecimal.valueOf(200)));
    final var nearStrike =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(300)),
            contract(BigDecimal.valueOf(6000), BigDecimal.valueOf(400)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(
                    null, List.of(farStrike, nearStrike), null, BigDecimal.valueOf(24200), null),
                null));

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNotNull(result);
    assertEquals(BigDecimal.valueOf(5000), result.totalPeOi());
    assertEquals(BigDecimal.valueOf(6000), result.totalCeOi());
  }

  @Test
  void fetchAndRecordOi_computesPcrCorrectly() {
    final var option =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(300)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null), null));

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNotNull(result);
    assertEquals(0, BigDecimal.valueOf(2.0000).compareTo(result.pcr()));
  }

  @Test
  void fetchAndRecordOi_recordsTopOiBuildUp() {
    final var option =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(1000)),
            contract(BigDecimal.valueOf(6000), BigDecimal.valueOf(800)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null), null));

    final OiDataSnapshot result = service.fetchAndRecordOi();

    assertNotNull(result);
    assertNotNull(result.topOiBuildUp());
    assertEquals(2, result.topOiBuildUp().size());
  }

  @Test
  void analyzeAndPredict_returnsBullish_whenPeOiChangeDominates() {
    final var firstOption =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                null));
    service.fetchAndRecordOi();

    final var secondOption =
        optionData(
            BigDecimal.valueOf(24200),
            contractWithPremium(
                BigDecimal.valueOf(10500), BigDecimal.valueOf(1000), BigDecimal.valueOf(35)),
            contract(BigDecimal.valueOf(5100), BigDecimal.valueOf(200)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(secondOption), null, BigDecimal.valueOf(24250), null),
                null));
    service.fetchAndRecordOi();

    final OiAnalysisResult result = service.analyzeAndPredict();

    assertNotNull(result);
    assertEquals("BULLISH", result.direction());
    assertEquals("DIRECTIONAL PUT SELLING", result.suggestedStrategy());
  }

  @Test
  void analyzeAndPredict_returnsBearish_whenCeOiChangeDominates() {
    final var firstOption =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)),
            contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(200)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                null));
    service.fetchAndRecordOi();

    final var secondOption =
        optionData(
            BigDecimal.valueOf(24300),
            contract(BigDecimal.valueOf(5100), BigDecimal.valueOf(200)),
            contractWithPremium(
                BigDecimal.valueOf(10800), BigDecimal.valueOf(1000), BigDecimal.valueOf(35)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(secondOption), null, BigDecimal.valueOf(24250), null),
                null));
    service.fetchAndRecordOi();

    final OiAnalysisResult result = service.analyzeAndPredict();

    assertNotNull(result);
    assertEquals("BEARISH", result.direction());
    assertEquals("DIRECTIONAL CALL SELLING", result.suggestedStrategy());
  }

  @Test
  void analyzeAndPredict_returnsNeutral_whenOiChangesAreBalanced() {
    final var putOption =
        optionData(
            BigDecimal.valueOf(24200),
            contractWithPremium(
                BigDecimal.valueOf(8000), BigDecimal.valueOf(300), BigDecimal.valueOf(35)),
            null);
    final var callOption =
        optionData(
            BigDecimal.valueOf(24300),
            null,
            contractWithPremium(
                BigDecimal.valueOf(8000), BigDecimal.valueOf(300), BigDecimal.valueOf(35)));
    final var options = List.of(putOption, callOption);

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, options, null, BigDecimal.valueOf(24200), null), null));
    service.fetchAndRecordOi();

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, options, null, BigDecimal.valueOf(24250), null), null));
    service.fetchAndRecordOi();

    final OiAnalysisResult result = service.analyzeAndPredict();

    assertNotNull(result);
    assertEquals("NEUTRAL", result.direction());
    assertEquals("SHORT STRANGLE", result.suggestedStrategy());
  }

  @Test
  void analyzeAndPredict_returnsNull_whenNoSnapshots() {
    assertNull(service.analyzeAndPredict());
  }

  @Test
  void notifyOiUpdate_sendsTelegram_whenSignificantChange() {
    final var opt1 =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(500)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(200)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(opt1), null, BigDecimal.valueOf(24200), null), null));

    service.notifyOiUpdate();
    verify(telegramService, times(1)).sendMessage(anyString());

    final var opt2 =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(1500)),
            contract(BigDecimal.valueOf(1000), BigDecimal.valueOf(200)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(opt2), null, BigDecimal.valueOf(24250), null), null));

    service.notifyOiUpdate();
    verify(telegramService, times(2)).sendMessage(anyString());
  }

  @Test
  void notifyOiUpdate_skipsTelegram_whenChangeBelowThreshold() {
    final var option =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null), null));

    service.notifyOiUpdate();
    verify(telegramService, times(1)).sendMessage(anyString());

    service.notifyOiUpdate();
    verify(telegramService, times(1)).sendMessage(anyString());
  }

  @Test
  void notifyPrediction_sendsOncePerDay() {
    final var firstOption =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                null));
    service.fetchAndRecordOi();

    final var secondOption =
        optionData(
            BigDecimal.valueOf(24200),
            contract(BigDecimal.valueOf(10500), BigDecimal.valueOf(1000)),
            contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(
            new OptionChainData(
                new Records(null, List.of(secondOption), null, BigDecimal.valueOf(24250), null),
                null));
    service.fetchAndRecordOi();

    service.notifyPrediction();
    verify(telegramService, times(1)).sendMessage(anyString());

    service.notifyPrediction();
    verify(telegramService, times(1)).sendMessage(anyString());
  }

  @Test
  void markPositionEntered_setsFlag() {
    assertFalse(service.isPositionEntered());
    service.markPositionEntered();
    assertTrue(service.isPositionEntered());
  }

  @Test
  void markPositionExited_clearsFlag() {
    service.markPositionEntered();
    assertTrue(service.isPositionEntered());
    service.markPositionExited();
    assertFalse(service.isPositionEntered());
  }

  @Test
  void reset_clearsState() {
    service.fetchAndRecordOi();
    service.markPositionEntered();
    service.reset();

    assertFalse(service.isPositionEntered());
    assertTrue(service.getSnapshots().isEmpty());
    assertNull(service.getLastAnalysis());
    assertFalse(service.isPredictionSentToday());
  }

  @Test
  void notifyOiUpdate_doesNotSend_whenDataIsNull() {
    when(nseClient.fetchOptionChain(anyString())).thenReturn(null);
    service.notifyOiUpdate();
    verify(telegramService, never()).sendMessage(anyString());
  }

  @Test
  void resolveIndexForDay_returnsNiftyOnMonday() {
    assertEquals("NIFTY", OiAnalysisService.resolveIndexForDay(DayOfWeek.MONDAY));
  }

  @Test
  void resolveIndexForDay_returnsNiftyOnTuesday() {
    assertEquals("NIFTY", OiAnalysisService.resolveIndexForDay(DayOfWeek.TUESDAY));
  }

  @Test
  void resolveIndexForDay_returnsSensexOnWednesday() {
    assertEquals("SENSEX", OiAnalysisService.resolveIndexForDay(DayOfWeek.WEDNESDAY));
  }

  @Test
  void resolveIndexForDay_returnsSensexOnThursday() {
    assertEquals("SENSEX", OiAnalysisService.resolveIndexForDay(DayOfWeek.THURSDAY));
  }

  @Test
  void resolveIndexForDay_returnsNiftyOnFriday() {
    assertEquals("NIFTY", OiAnalysisService.resolveIndexForDay(DayOfWeek.FRIDAY));
  }

  @Test
  void resolveIndexForDay_returnsNiftyOnWeekend() {
    assertEquals("NIFTY", OiAnalysisService.resolveIndexForDay(DayOfWeek.SATURDAY));
    assertEquals("NIFTY", OiAnalysisService.resolveIndexForDay(DayOfWeek.SUNDAY));
  }

  @Test
  void notifyExitIfNeeded_triggersHardStop_onPremiumDoubling() {
    // Entry Snapshot 1
    final var peSellContract1 =
        contractWithPremium(
            BigDecimal.valueOf(5000), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var peHedgeContract1 =
        contractWithPremium(
            BigDecimal.valueOf(3000), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var option1_1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract1);
    final var option1_2 = new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeContract1);
    final var entryChain1 =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(option1_1, option1_2),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Entry Snapshot 2 (adds OI buildup to dominate, so it is BULLISH)
    final var peSellContract2 =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var peHedgeContract2 =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var option2_1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract2);
    final var option2_2 = new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeContract2);
    final var entryChain2 =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(option2_1, option2_2),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Exit Snapshot (premium doubles to 80)
    final var peSellExitContract =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(80));
    final var peHedgeExitContract =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var exitOption1 =
        new OptionData(BigDecimal.valueOf(24200), null, null, peSellExitContract);
    final var exitOption2 =
        new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeExitContract);
    final var exitChain =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(exitOption1, exitOption2),
                null,
                BigDecimal.valueOf(24100),
                null),
            null);

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(entryChain1)
        .thenReturn(entryChain2)
        .thenReturn(exitChain);

    service.fetchAndRecordOi(); // snap 1
    service.fetchAndRecordOi(); // snap 2
    service.analyzeAndPredict(); // BULLISH, suggested strikes [24200]
    service.markPositionEntered();

    service.fetchAndRecordOi(); // exit snap
    service.notifyExitIfNeeded();

    verify(telegramService, atLeastOnce()).sendMessage(argThat(msg -> msg.contains("HARD STOP")));
  }

  @Test
  void notifyExitIfNeeded_triggersProfitTarget_onPremiumDecay() {
    // Entry Snapshot 1
    final var peSellContract1 =
        contractWithPremium(
            BigDecimal.valueOf(5000), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var peHedgeContract1 =
        contractWithPremium(
            BigDecimal.valueOf(3000), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var option1_1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract1);
    final var option1_2 = new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeContract1);
    final var entryChain1 =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(option1_1, option1_2),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Entry Snapshot 2
    final var peSellContract2 =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var peHedgeContract2 =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var option2_1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract2);
    final var option2_2 = new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeContract2);
    final var entryChain2 =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(option2_1, option2_2),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Exit Snapshot (premium decays to 5, hedge to 0)
    final var peSellExitContract =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(5));
    final var peHedgeExitContract =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(0));
    final var exitOption1 =
        new OptionData(BigDecimal.valueOf(24200), null, null, peSellExitContract);
    final var exitOption2 =
        new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeExitContract);
    final var exitChain =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(exitOption1, exitOption2),
                null,
                BigDecimal.valueOf(24350),
                null),
            null);

    when(nseClient.fetchOptionChain(anyString()))
        .thenReturn(entryChain1)
        .thenReturn(entryChain2)
        .thenReturn(exitChain);

    service.fetchAndRecordOi(); // snap 1
    service.fetchAndRecordOi(); // snap 2
    service.analyzeAndPredict();
    service.markPositionEntered();

    service.fetchAndRecordOi(); // exit snap
    service.notifyExitIfNeeded();

    verify(telegramService, atLeastOnce())
        .sendMessage(
            argThat(msg -> msg.contains("Profit target reached") || msg.contains("PROFIT_TARGET")));
  }

  @Test
  void cumulativeNormalDistribution_returnsHalfForZero() {
    assertEquals(0.5, OiAnalysisService.cumulativeNormalDistribution(0.0), 0.0001);
  }

  @Test
  void calculateDelta_returnsCorrectValuesForAtmOptions() {
    double callDelta = OiAnalysisService.calculateDelta(24000.0, 24000.0, 15.0, 5.0, true);
    assertEquals(0.5, callDelta, 0.1);

    double putDelta = OiAnalysisService.calculateDelta(24000.0, 24000.0, 15.0, 5.0, false);
    assertEquals(-0.5, putDelta, 0.1);
  }

  @Test
  void notifyExitIfNeeded_triggersTimeSquareOff_after310PM() {
    final Clock fixedClock =
        Clock.fixed(Instant.parse("2026-07-16T09:45:00Z"), ZoneId.of("Asia/Kolkata"));
    final OiAnalysisService serviceWithFixedClock =
        new OiAnalysisService(
            nseClient,
            telegramService,
            snapshotRepository,
            objectMapper,
            lstmClient,
            sentimentClient,
            fixedClock);

    final var peSellContract =
        contractWithPremium(
            BigDecimal.valueOf(5000), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var peHedgeContract =
        contractWithPremium(
            BigDecimal.valueOf(3000), BigDecimal.valueOf(50), BigDecimal.valueOf(10));
    final var option1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract);
    final var option2 = new OptionData(BigDecimal.valueOf(24050), null, null, peHedgeContract);
    final var chain =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"),
                List.of(option1, option2),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    when(nseClient.fetchOptionChain(anyString())).thenReturn(chain);

    serviceWithFixedClock.fetchAndRecordOi();
    serviceWithFixedClock.analyzeAndPredict();
    serviceWithFixedClock.markPositionEntered();

    serviceWithFixedClock.notifyExitIfNeeded();

    verify(telegramService, atLeastOnce())
        .sendMessage(argThat(msg -> msg.contains("TIME-BASED SQUARE-OFF")));
  }

  @Test
  void buildZerodhaSymbol_returnsCorrectFormat() {
    String symbol =
        service.buildZerodhaSymbol(
            "NIFTY", LocalDate.of(2026, 7, 16), BigDecimal.valueOf(24100), "PE");
    assertEquals("NIFTY2671624100PE", symbol);

    // Test October single character code
    String symbolOct =
        service.buildZerodhaSymbol(
            "NIFTY", LocalDate.of(2026, 10, 15), BigDecimal.valueOf(24100), "CE");
    assertEquals("NIFTY26O1524100CE", symbolOct);
  }

  @Test
  void pickStrikes_returnsEmptyList_onLowVix() {
    // Mock VIX client to return low VIX (e.g. 10.0)
    final var vixQuote =
        new IndexQuote(
            List.of(
                new IndexQuote.IndexData("VIX", null, null, null, BigDecimal.valueOf(10.0), null)));
    when(nseClient.fetchIndexQuote("VIX")).thenReturn(vixQuote);
    final var indexQuote =
        new IndexQuote(
            List.of(
                new IndexQuote.IndexData(
                    "SENSEX", null, null, null, null, BigDecimal.valueOf(24250))));
    when(nseClient.fetchIndexQuote("SENSEX")).thenReturn(indexQuote);

    // Setup some option data snapshots
    final var peSellContract =
        contractWithPremium(
            BigDecimal.valueOf(5000), BigDecimal.valueOf(100), BigDecimal.valueOf(35));
    final var option1 = new OptionData(BigDecimal.valueOf(24200), null, null, peSellContract);
    final var chain =
        new OptionChainData(
            new Records(
                List.of("16-Jul-2026"), List.of(option1), null, BigDecimal.valueOf(24250), null),
            null);

    when(nseClient.fetchOptionChain(anyString())).thenReturn(chain);

    service.fetchAndRecordOi();
    final OiAnalysisResult result = service.analyzeAndPredict();

    assertNotNull(result);
    assertEquals("NEUTRAL", result.direction());
    assertEquals("NO_TRADE", result.suggestedStrategy());
    assertTrue(result.suggestedStrikes().isEmpty());
    assertTrue(result.tradeRecommendation().contains("NO TRADE"));
  }

  private static OptionData optionData(
      final BigDecimal strike, final OptionContract pe, final OptionContract ce) {
    return new OptionData(strike, null, ce, pe);
  }

  private static OptionContract contract(
      final BigDecimal openInterest, final BigDecimal changeInOi) {
    return new OptionContract(
        null,
        null,
        null,
        null,
        openInterest,
        changeInOi,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static OptionContract contractWithPremium(
      final BigDecimal openInterest, final BigDecimal changeInOi, final BigDecimal lastPrice) {
    return new OptionContract(
        null,
        null,
        null,
        null,
        openInterest,
        changeInOi,
        null,
        null,
        null,
        lastPrice,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
