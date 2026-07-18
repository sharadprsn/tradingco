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

  private final ObjectMapper objectMapper = new ObjectMapper();

  private OiAnalysisService service;

  @BeforeEach
  void setUp() {
    final Clock morningClock =
        Clock.fixed(Instant.parse("2026-07-16T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
    service =
        new OiAnalysisService(
            nseClient, telegramService, snapshotRepository, objectMapper, morningClock);
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
  }

  @Test
  void analyzeAndPredict_returnsNull_whenNoSnapshots() {
    assertNull(service.analyzeAndPredict());
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
    // Entry Snapshot 1 - add a far OTM strike (23800) near delta -0.15 for accurate strike
    // selection
    final var peSellContract1 =
        contractWithPremium(
            BigDecimal.valueOf(5000), BigDecimal.valueOf(100), BigDecimal.valueOf(18));
    final var peHedgeContract1 =
        contractWithPremium(
            BigDecimal.valueOf(3000), BigDecimal.valueOf(50), BigDecimal.valueOf(8));
    final var peOiBuildup1 =
        contractWithPremium(
            BigDecimal.valueOf(8000), BigDecimal.valueOf(500), BigDecimal.valueOf(35));
    final var option1_1 = new OptionData(BigDecimal.valueOf(23800), null, null, peSellContract1);
    final var option1_2 = new OptionData(BigDecimal.valueOf(23600), null, null, peHedgeContract1);
    final var option1_3 = new OptionData(BigDecimal.valueOf(24200), null, null, peOiBuildup1);
    final var entryChain1 =
        new OptionChainData(
            new Records(
                List.of("23-Jul-2026"),
                List.of(option1_1, option1_2, option1_3),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Entry Snapshot 2 (adds OI buildup to dominate, so it is BULLISH)
    final var peSellContract2 =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(18));
    final var peHedgeContract2 =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(8));
    final var peOiBuildup2 =
        contractWithPremium(
            BigDecimal.valueOf(8500), BigDecimal.valueOf(500), BigDecimal.valueOf(35));
    final var option2_1 = new OptionData(BigDecimal.valueOf(23800), null, null, peSellContract2);
    final var option2_2 = new OptionData(BigDecimal.valueOf(23600), null, null, peHedgeContract2);
    final var option2_3 = new OptionData(BigDecimal.valueOf(24200), null, null, peOiBuildup2);
    final var entryChain2 =
        new OptionChainData(
            new Records(
                List.of("23-Jul-2026"),
                List.of(option2_1, option2_2, option2_3),
                null,
                BigDecimal.valueOf(24250),
                null),
            null);

    // Exit Snapshot (premium doubles to 40, underlying drops to test strike breach + hard stop)
    final var peSellExitContract =
        contractWithPremium(
            BigDecimal.valueOf(5500), BigDecimal.valueOf(100), BigDecimal.valueOf(40));
    final var peHedgeExitContract =
        contractWithPremium(
            BigDecimal.valueOf(3100), BigDecimal.valueOf(50), BigDecimal.valueOf(14));
    final var peOiBuildupExit =
        contractWithPremium(
            BigDecimal.valueOf(8500), BigDecimal.valueOf(500), BigDecimal.valueOf(35));
    final var exitOption1 =
        new OptionData(BigDecimal.valueOf(23800), null, null, peSellExitContract);
    final var exitOption2 =
        new OptionData(BigDecimal.valueOf(23600), null, null, peHedgeExitContract);
    final var exitOption3 = new OptionData(BigDecimal.valueOf(24200), null, null, peOiBuildupExit);
    final var exitChain =
        new OptionChainData(
            new Records(
                List.of("23-Jul-2026"),
                List.of(exitOption1, exitOption2, exitOption3),
                null,
                BigDecimal.valueOf(23700),
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
                List.of("23-Jul-2026"),
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
                List.of("23-Jul-2026"),
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
                List.of("23-Jul-2026"),
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
            nseClient, telegramService, snapshotRepository, objectMapper, fixedClock);

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

  // ── Helper: build a minimal OiDataSnapshot for stability tests ───────────────

  private static OiDataSnapshot stableSnap(
      final double pcr, final double peOiChange, final double ceOiChange) {
    return new OiDataSnapshot(
        java.time.LocalDateTime.now(), // timestamp
        BigDecimal.valueOf(24200), // underlyingValue
        BigDecimal.valueOf(1_200_000), // totalPeOi
        BigDecimal.valueOf(1_000_000), // totalCeOi
        BigDecimal.valueOf(peOiChange), // totalPeOiChange
        BigDecimal.valueOf(ceOiChange), // totalCeOiChange
        BigDecimal.valueOf(pcr), // pcr
        List.of(), // topOiBuildUp — empty, no velocity concern
        BigDecimal.valueOf(24000), // largestPeOiStrike
        BigDecimal.valueOf(24500), // largestCeOiStrike
        List.of(), // strikePremiums
        BigDecimal.ZERO); // marketSentiment
  }

  private static OiDataSnapshot snapWithVelocity(final double pchangeInOi) {
    final OiDataSnapshot.OiStrikeInfo hotStrike =
        new OiDataSnapshot.OiStrikeInfo(
            BigDecimal.valueOf(24000), // strikePrice
            "PE", // optionType
            BigDecimal.valueOf(500_000), // openInterest
            BigDecimal.valueOf(50_000), // changeInOi
            BigDecimal.valueOf(pchangeInOi)); // pchangeInOi
    return new OiDataSnapshot(
        java.time.LocalDateTime.now(),
        BigDecimal.valueOf(24200),
        BigDecimal.valueOf(1_200_000),
        BigDecimal.valueOf(1_000_000),
        BigDecimal.valueOf(80_000),
        BigDecimal.valueOf(60_000),
        BigDecimal.valueOf(1.15),
        List.of(hotStrike),
        BigDecimal.valueOf(24000),
        BigDecimal.valueOf(24500),
        List.of(),
        BigDecimal.ZERO);
  }

  // ── isOiStable() tests ───────────────────────────────────────────────────────

  @Test
  void isOiStable_false_whenFewerThanThreeSnapshots() {
    service.addSnapshotForTesting(stableSnap(1.15, 100_000, 80_000));
    service.addSnapshotForTesting(stableSnap(1.16, 90_000, 70_000));

    assertFalse(service.isOiStable(), "Should not be stable with only 2 snapshots");
  }

  @Test
  void isOiStable_false_whenPcrVarianceTooHigh() {
    // PCR swings 0.12 across the window (> 0.05 threshold)
    service.addSnapshotForTesting(stableSnap(1.05, 100_000, 90_000));
    service.addSnapshotForTesting(stableSnap(1.12, 90_000, 60_000));
    service.addSnapshotForTesting(stableSnap(1.17, 80_000, 40_000));

    assertFalse(service.isOiStable(), "Should not be stable when PCR variance > 0.05");
  }

  @Test
  void isOiStable_false_whenOiChangeRateNotDecelerating() {
    // Latest OI delta = 160K, first OI delta = 200K → ratio = 0.80 > 0.50 threshold
    service.addSnapshotForTesting(stableSnap(1.14, 120_000, 80_000)); // Δ = 200K
    service.addSnapshotForTesting(stableSnap(1.15, 100_000, 80_000)); // Δ = 180K
    service.addSnapshotForTesting(stableSnap(1.15, 95_000, 65_000)); // Δ = 160K

    assertFalse(service.isOiStable(), "Should not be stable when OI change rate is still high");
  }

  @Test
  void isOiStable_false_whenHighOiVelocityAtStrike() {
    // pchangeInOi = 55% at one strike (> 30% threshold)
    service.addSnapshotForTesting(stableSnap(1.15, 100_000, 80_000));
    service.addSnapshotForTesting(stableSnap(1.15, 90_000, 70_000));
    service.addSnapshotForTesting(snapWithVelocity(55.0));

    assertFalse(
        service.isOiStable(), "Should not be stable with aggressive OI velocity at a strike");
  }

  @Test
  void isOiStable_true_whenAllConditionsMet() {
    // PCR varies by only 0.02 (< 0.05), OI rate decelerates well below 50%
    service.addSnapshotForTesting(stableSnap(1.15, 500_000, 400_000)); // Δ = 900K (window start)
    service.addSnapshotForTesting(stableSnap(1.16, 200_000, 150_000)); // Δ = 350K
    service.addSnapshotForTesting(stableSnap(1.16, 80_000, 50_000)); // Δ = 130K  (< 50% of 900K ✅)

    assertTrue(
        service.isOiStable(),
        "Should be stable: PCR stable, OI rate decelerated, no velocity spike");
  }
}
