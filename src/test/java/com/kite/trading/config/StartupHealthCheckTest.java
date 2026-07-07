package com.kite.trading.config;

import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import com.kite.trading.service.NseOptionChainClient;
import com.kite.trading.service.TelegramService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupHealthCheckTest {

    @Mock
    private NseOptionChainClient nseClient;

    @Mock
    private TelegramService telegramService;

    @Test
    void onApplicationReady_checksNse() {
        when(nseClient.fetchOptionChain()).thenReturn(validOptionChain());

        final var healthCheck = new StartupHealthCheck(nseClient, telegramService);
        healthCheck.onApplicationReady();

        verify(nseClient).fetchOptionChain();
        verify(telegramService, never()).sendMessage(anyString());
    }

    @Test
    void onApplicationReady_handlesNseFailureGracefully() {
        when(nseClient.fetchOptionChain()).thenThrow(new RuntimeException("NSE unavailable"));

        final var healthCheck = new StartupHealthCheck(nseClient, telegramService);
        healthCheck.onApplicationReady();

        verify(telegramService, never()).sendMessage(anyString());
    }

    @Test
    void onApplicationReady_handlesNullDataGracefully() {
        when(nseClient.fetchOptionChain()).thenReturn(null);

        final var healthCheck = new StartupHealthCheck(nseClient, telegramService);
        healthCheck.onApplicationReady();

        verify(telegramService, never()).sendMessage(anyString());
    }

    @Test
    void onApplicationReady_handlesNseFailureGracefullyWhenTelegramUnavailable() {
        when(nseClient.fetchOptionChain()).thenThrow(new RuntimeException("NSE unavailable"));

        final var healthCheck = new StartupHealthCheck(nseClient, telegramService);
        healthCheck.onApplicationReady();

        verify(nseClient).fetchOptionChain();
        verify(telegramService, never()).sendMessage(anyString());
    }

    private static OptionChainData validOptionChain() {
        final var peContract = new OptionContract(null, null, null, null, BigDecimal.valueOf(5000),
                BigDecimal.valueOf(100), null, null, null, null, null, null, null, null, null, null, null, null, null);
        final var ceContract = new OptionContract(null, null, null, null, BigDecimal.valueOf(6000),
                BigDecimal.valueOf(200), null, null, null, null, null, null, null, null, null, null, null, null, null);
        final var option = new OptionData(BigDecimal.valueOf(24200), null, ceContract, peContract);
        return new OptionChainData(
                new Records(null, List.of(option), null, BigDecimal.valueOf(24200), null),
                null);
    }
}
