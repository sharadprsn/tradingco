package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BseConnectivityTest {

  private static final Logger logger = LoggerFactory.getLogger(BseConnectivityTest.class);

  @Autowired private OptionChainClient optionChainClient;

  @Test
  void fetchSensexAtmPlusMinusFive() {
    // 1. Fetch live SENSEX option chain data
    final OptionChainData data = optionChainClient.fetchOptionChain("SENSEX");

    assertNotNull(data, "Option chain data should not be null");
    assertNotNull(data.records(), "Records should not be null");
    assertNotNull(data.records().data(), "Options data list should not be null");
    assertFalse(data.records().data().isEmpty(), "Should have at least one option contract");
    assertNotNull(data.records().underlyingValue(), "Underlying value should be present");

    final BigDecimal spot = data.records().underlyingValue();
    assertTrue(spot.compareTo(BigDecimal.ZERO) > 0, "Sensex spot should be positive");

    // 2. SENSEX strike interval is 100
    final int interval = 100;

    // Round underlying to nearest strike
    final BigDecimal roundedAtm =
        spot.divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(interval));

    // ATM +- 5 strikes range
    final BigDecimal minStrike = roundedAtm.subtract(BigDecimal.valueOf(5 * interval));
    final BigDecimal maxStrike = roundedAtm.add(BigDecimal.valueOf(5 * interval));

    logger.info("Sensex Spot: {}", spot);
    logger.info("Sensex ATM: {}", roundedAtm);
    logger.info("Sensex Strike Range (ATM +- 5): {} to {}", minStrike, maxStrike);

    // Filter option chain to ATM +- 5 strikes
    final List<OptionData> nearStrikes =
        data.records().data().stream()
            .filter(d -> d.strikePrice() != null)
            .filter(
                d ->
                    d.strikePrice().compareTo(minStrike) >= 0
                        && d.strikePrice().compareTo(maxStrike) <= 0)
            .collect(Collectors.toList());

    logger.info("Found {} strikes in the range of ATM +- 5", nearStrikes.size());

    assertFalse(nearStrikes.isEmpty(), "Near strikes list should not be empty");

    // Output the results for verification
    for (final OptionData option : nearStrikes) {
      final String ceOi = option.ce() != null ? String.valueOf(option.ce().openInterest()) : "N/A";
      final String peOi = option.pe() != null ? String.valueOf(option.pe().openInterest()) : "N/A";
      final String ceLtp = option.ce() != null ? String.valueOf(option.ce().lastPrice()) : "N/A";
      final String peLtp = option.pe() != null ? String.valueOf(option.pe().lastPrice()) : "N/A";
      logger.info(
          String.format(
              "Strike: %s | CE OI: %s (LTP: %s) | PE OI: %s (LTP: %s)",
              option.strikePrice(), ceOi, ceLtp, peOi, peLtp));
    }
  }
}
