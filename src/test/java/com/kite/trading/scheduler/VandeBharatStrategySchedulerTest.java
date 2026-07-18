package com.kite.trading.scheduler;

import static org.mockito.Mockito.*;

import com.kite.trading.service.TelegramService;
import com.kite.trading.service.VandeBharatStrategyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VandeBharatStrategySchedulerTest {

  @Mock private VandeBharatStrategyService strategyService;

  @Mock private TelegramService telegramService;

  @Test
  void scheduledVandeBharatCheck_shouldCallAnalyze() {
    final var scheduler = new VandeBharatStrategyScheduler(strategyService, telegramService);

    scheduler.scheduledVandeBharatCheck();

    verify(strategyService).analyze();
    verifyNoInteractions(telegramService);
  }

  @Test
  void scheduledVandeBharatCheck_shouldHandleErrors() {
    doThrow(new RuntimeException("test error")).when(strategyService).analyze();

    final var scheduler = new VandeBharatStrategyScheduler(strategyService, telegramService);

    scheduler.scheduledVandeBharatCheck();

    verify(strategyService).analyze();
    verify(telegramService).sendMessage(contains("test error"));
  }

  @Test
  void resetDaily_shouldDelegate() {
    final var scheduler = new VandeBharatStrategyScheduler(strategyService, telegramService);

    scheduler.resetDaily();

    verify(strategyService).resetDaily();
    verify(telegramService).sendMessage(contains("Vande Bharat"));
  }
}
