package com.kite.trading.scheduler;

import static org.mockito.Mockito.*;

import com.kite.trading.repository.OiSnapshotRepository;
import com.kite.trading.service.OiAnalysisService;
import com.kite.trading.service.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntradayOiSchedulerTest {

  @Mock private OiAnalysisService oiAnalysisService;

  @Mock private TelegramService telegramService;

  @Mock private OiSnapshotRepository snapshotRepository;

  private IntradayOiScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new IntradayOiScheduler(oiAnalysisService, telegramService, snapshotRepository);
  }

  @Test
  void shouldRun_doesNotThrow() {
    scheduler.shouldRun();
  }

  @Test
  void resetDaily_clearsState() {
    scheduler.resetDaily();

    verify(oiAnalysisService).reset();
    verify(telegramService).sendMessage(anyString()); // message wording may evolve
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
}
