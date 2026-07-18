package com.kite.trading.scheduler;

import static org.mockito.Mockito.*;

import com.kite.trading.service.MultiTfRSINiftyOptionService;
import com.kite.trading.service.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MultiTfRSINiftySchedulerTest {

  @Mock private MultiTfRSINiftyOptionService strategyService;
  @Mock private TelegramService telegramService;

  private MultiTfRSINiftyScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new MultiTfRSINiftyScheduler(strategyService, telegramService);
  }

  @Test
  void scheduledMultiTfCheck_shouldDelegateToService() {
    scheduler.scheduledMultiTfCheck();
    verify(strategyService).evaluate();
    verifyNoInteractions(telegramService);
  }

  @Test
  void scheduledMultiTfCheck_shouldHandleServiceException() {
    doThrow(new RuntimeException("Test error")).when(strategyService).evaluate();
    scheduler.scheduledMultiTfCheck();
    verify(strategyService).evaluate();
    verify(telegramService).sendMessage(contains("Error"));
  }

  @Test
  void resetDaily_shouldResetServiceAndNotify() {
    scheduler.resetDaily();
    verify(strategyService).resetDaily();
    verify(telegramService).sendMessage(contains("Multi-TF"));
  }
}
