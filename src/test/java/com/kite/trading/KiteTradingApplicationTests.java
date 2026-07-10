package com.kite.trading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Main application context load test.
 *
 * <p>This test verifies that the Spring application context loads correctly and all beans are
 * properly configured.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("test")
class KiteTradingApplicationTests {

  /** Tests that the application context loads successfully. */
  @Test
  void contextLoads() {
    // Context load test - if no exception is thrown, the test passes
  }
}
