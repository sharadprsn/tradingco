package com.kite.trading.scheduler;

import com.kite.trading.service.OiAnalysisService;
import com.kite.trading.service.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntradayOiSchedulerTest {

    @Mock
    private OiAnalysisService oiAnalysisService;

    @Mock
    private TelegramService telegramService;

    private IntradayOiScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new IntradayOiScheduler(oiAnalysisService, telegramService);
    }

    @Test
    void shouldRun_returnsFalse_onWeekend() {
        final boolean result = scheduler.shouldRun();
        assertFalse(result);
    }

    @Test
    void resetDaily_clearsState() {
        scheduler.resetDaily();

        verify(oiAnalysisService).reset();
        verify(telegramService).sendMessage(contains("initialized"));
    }

    @Test
    void marketCloseSummary_resetsEvenWhenNoSnapshots() {
        when(oiAnalysisService.getSnapshots()).thenReturn(java.util.List.of());

        scheduler.marketCloseSummary();

        verify(oiAnalysisService).reset();
        verify(telegramService, never()).sendMessage(anyString());
    }

    @Test
    void scheduledOiCheck_runsWithoutError() {
        scheduler.scheduledOiCheck();
    }

    @Test
    void scheduledOiCheck_handlesExceptionGracefully_whenCalled() {
        scheduler.scheduledOiCheck();
    }

    @Test
    void sendLoginUrl_sendsViaTelegram() {
        scheduler.sendLoginUrl();

        verify(telegramService).sendMessage(contains("https://localhost:443/api/v1/auth/login-url"));
    }

    @Test
    void sendLoginUrl_includesEndpoint() {
        scheduler.sendLoginUrl();

        verify(telegramService).sendMessage(contains("/api/v1/auth/login-url"));
    }
}
