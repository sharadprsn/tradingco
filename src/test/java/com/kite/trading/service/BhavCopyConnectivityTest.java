package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kite.trading.config.NseConfig;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BhavCopyConnectivityTest {

  @Autowired private NseOptionChainClient nseClient;

  @Autowired private NseConfig nseConfig;

  @Test
  void fetchBhavCopyForPreviousTradingDay() {
    LocalDate prev = LocalDate.now().minusDays(1);
    while (prev.getDayOfWeek() == DayOfWeek.SATURDAY || prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
      prev = prev.minusDays(1);
    }

    final Map<String, NseOptionChainClient.BhavCopyEntry> data = nseClient.fetchBhavCopyData(prev);

    assertNotNull(data, "Bhavcopy data should not be null");
    assertFalse(data.isEmpty(), "Bhavcopy should contain entries");

    System.out.println("Bhavcopy fetched for " + prev + ": " + data.size() + " entries");

    // Verify known F&O stocks are present with valid data
    final String[] testSymbols = {"RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK"};
    for (final String symbol : testSymbols) {
      final NseOptionChainClient.BhavCopyEntry entry = data.get(symbol);
      assertNotNull(entry, "Bhavcopy should contain " + symbol);
      assertNotNull(entry.high(), symbol + " high should not be null");
      assertNotNull(entry.low(), symbol + " low should not be null");
      assertTrue(entry.high().compareTo(BigDecimal.ZERO) > 0, symbol + " high should be positive");
      assertTrue(entry.low().compareTo(BigDecimal.ZERO) > 0, symbol + " low should be positive");
      assertTrue(entry.high().compareTo(entry.low()) >= 0, symbol + " high should be >= low");
      System.out.println(
          "  "
              + symbol
              + ": HIGH="
              + entry.high()
              + ", LOW="
              + entry.low()
              + ", previousClose estimate="
              + entry.high().add(entry.low()).divide(BigDecimal.valueOf(2)));
    }

    // Verify PDH/PDL values are reasonable (between 0.5x and 2x of each other)
    for (final String symbol : testSymbols) {
      final NseOptionChainClient.BhavCopyEntry entry = data.get(symbol);
      final BigDecimal ratio = entry.high().divide(entry.low(), 4, java.math.RoundingMode.HALF_UP);
      assertTrue(
          ratio.compareTo(BigDecimal.valueOf(0.5)) >= 0
              && ratio.compareTo(BigDecimal.valueOf(2.0)) <= 0,
          symbol + " high/low ratio " + ratio + " seems unreasonable");
    }

    System.out.println(
        "\nBhavcopy integration test PASSED - " + testSymbols.length + " major stocks verified");
  }

  @Test
  void bhavcopyUrlConfigurationIsValid() {
    final String url = nseConfig.getBhavCopyUrl("17072026");
    assertNotNull(url);
    assertTrue(url.contains("17072026"), "URL should contain the date");
    assertTrue(url.endsWith(".csv"), "URL should end with .csv");
    System.out.println("Bhavcopy URL: " + url);
  }
}
