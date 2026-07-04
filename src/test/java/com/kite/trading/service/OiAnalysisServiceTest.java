package com.kite.trading.service;

import com.kite.trading.dto.OiAnalysisResult;
import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OiAnalysisServiceTest {

    @Mock
    private NseOptionChainClient nseClient;

    @Mock
    private TelegramService telegramService;

    private OiAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new OiAnalysisService(nseClient, telegramService);
    }

    @Test
    void fetchAndRecordOi_returnsNull_whenDataIsNull() {
        when(nseClient.fetchOptionChain()).thenReturn(null);

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNull(result);
        assertTrue(service.getSnapshots().isEmpty());
    }

    @Test
    void fetchAndRecordOi_returnsNull_whenRecordsIsNull() {
        when(nseClient.fetchOptionChain()).thenReturn(new OptionChainData(null, null));

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNull(result);
    }

    @Test
    void fetchAndRecordOi_returnsNull_whenUnderlyingIsNull() {
        final var options = List.of(optionData(BigDecimal.valueOf(24200), null, null));
        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(new Records(null, options, null, null, null), null));

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNull(result);
    }

    @Test
    void fetchAndRecordOi_filtersToNearStrikesOnly() {
        final var farStrike = optionData(BigDecimal.valueOf(25000),
                contract(BigDecimal.valueOf(1000), BigDecimal.valueOf(100)),
                contract(BigDecimal.valueOf(2000), BigDecimal.valueOf(200)));
        final var nearStrike = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(300)),
                contract(BigDecimal.valueOf(6000), BigDecimal.valueOf(400)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(farStrike, nearStrike), null, BigDecimal.valueOf(24200), null),
                        null));

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(5000), result.totalPeOi());
        assertEquals(BigDecimal.valueOf(6000), result.totalCeOi());
    }

    @Test
    void fetchAndRecordOi_computesPcrCorrectly() {
        final var option = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(300)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null),
                        null));

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNotNull(result);
        assertEquals(0, BigDecimal.valueOf(2.0000).compareTo(result.pcr()));
    }

    @Test
    void fetchAndRecordOi_recordsTopOiBuildUp() {
        final var option = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(1000)),
                contract(BigDecimal.valueOf(6000), BigDecimal.valueOf(800)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null),
                        null));

        final OiDataSnapshot result = service.fetchAndRecordOi();

        assertNotNull(result);
        assertNotNull(result.topOiBuildUp());
        assertEquals(2, result.topOiBuildUp().size());
    }

    @Test
    void analyzeAndPredict_returnsBullish_whenPeOiChangeDominates() {
        final var firstOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                        null));
        service.fetchAndRecordOi();

        final var secondOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10500), BigDecimal.valueOf(1000)),
                contract(BigDecimal.valueOf(5100), BigDecimal.valueOf(200)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(secondOption), null, BigDecimal.valueOf(24250), null),
                        null));
        service.fetchAndRecordOi();

        final OiAnalysisResult result = service.analyzeAndPredict();

        assertNotNull(result);
        assertEquals("BULLISH", result.direction());
        assertEquals("PUT CREDIT SPREAD", result.suggestedStrategy());
    }

    @Test
    void analyzeAndPredict_returnsBearish_whenCeOiChangeDominates() {
        final var firstOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)),
                contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(200)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                        null));
        service.fetchAndRecordOi();

        final var secondOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5100), BigDecimal.valueOf(200)),
                contract(BigDecimal.valueOf(10800), BigDecimal.valueOf(1000)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(secondOption), null, BigDecimal.valueOf(24250), null),
                        null));
        service.fetchAndRecordOi();

        final OiAnalysisResult result = service.analyzeAndPredict();

        assertNotNull(result);
        assertEquals("BEARISH", result.direction());
        assertEquals("CALL CREDIT SPREAD", result.suggestedStrategy());
    }

    @Test
    void analyzeAndPredict_returnsNeutral_whenOiChangesAreBalanced() {
        final var option = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(8000), BigDecimal.valueOf(300)),
                contract(BigDecimal.valueOf(8000), BigDecimal.valueOf(300)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null),
                        null));
        service.fetchAndRecordOi();

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(option), null, BigDecimal.valueOf(24250), null),
                        null));
        service.fetchAndRecordOi();

        final OiAnalysisResult result = service.analyzeAndPredict();

        assertNotNull(result);
        assertEquals("NEUTRAL", result.direction());
        assertEquals("IRON CONDOR", result.suggestedStrategy());
    }

    @Test
    void analyzeAndPredict_returnsNull_whenNoSnapshots() {
        assertNull(service.analyzeAndPredict());
    }

    @Test
    void notifyOiUpdate_sendsTelegram_whenSignificantChange() {
        final var opt1 = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(500)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(200)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(opt1), null, BigDecimal.valueOf(24200), null),
                        null));

        service.notifyOiUpdate();
        verify(telegramService, times(1)).sendMessage(anyString());

        final var opt2 = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(1500)),
                contract(BigDecimal.valueOf(1000), BigDecimal.valueOf(200)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(opt2), null, BigDecimal.valueOf(24250), null),
                        null));

        service.notifyOiUpdate();
        verify(telegramService, times(2)).sendMessage(anyString());
    }

    @Test
    void notifyOiUpdate_skipsTelegram_whenChangeBelowThreshold() {
        final var option = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null),
                        null));

        service.notifyOiUpdate();
        verify(telegramService, times(1)).sendMessage(anyString());

        service.notifyOiUpdate();
        verify(telegramService, times(1)).sendMessage(anyString());
    }

    @Test
    void notifyPrediction_sendsOncePerDay() {
        final var firstOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10000), BigDecimal.valueOf(500)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

        when(nseClient.fetchOptionChain()).thenReturn(
                new OptionChainData(
                        new Records(null, List.of(firstOption), null, BigDecimal.valueOf(24200), null),
                        null));
        service.fetchAndRecordOi();

        final var secondOption = optionData(BigDecimal.valueOf(24200),
                contract(BigDecimal.valueOf(10500), BigDecimal.valueOf(1000)),
                contract(BigDecimal.valueOf(5000), BigDecimal.valueOf(100)));

        when(nseClient.fetchOptionChain()).thenReturn(
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
    void checkExitSignal_returnsNone_whenNoPosition() {
        assertEquals(OiAnalysisService.ExitSignal.NONE, service.checkExitSignal());
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
        when(nseClient.fetchOptionChain()).thenReturn(null);
        service.notifyOiUpdate();
        verify(telegramService, never()).sendMessage(anyString());
    }

    private static OptionData optionData(final BigDecimal strike, final OptionContract pe, final OptionContract ce) {
        return new OptionData(strike, null, ce, pe);
    }

    private static OptionContract contract(final BigDecimal openInterest, final BigDecimal changeInOi) {
        return new OptionContract(null, null, null, null, openInterest, changeInOi, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
