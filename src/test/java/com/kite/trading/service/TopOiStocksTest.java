package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
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
class TopOiStocksTest {

  private static final Logger log = LoggerFactory.getLogger(TopOiStocksTest.class);

  @Autowired private NseOptionChainClient nseClient;

  @Test
  void fetchTop10ByPctChange() {
    final NseOptionChainClient.PreOpenResponse resp = nseClient.fetchPreOpenData("FO");

    assertNotNull(resp, "Pre-open response should not be null");
    assertNotNull(resp.data(), "Pre-open data should not be null");
    assertFalse(resp.data().isEmpty(), "Pre-open data should contain entries");

    final List<NseOptionChainClient.PreOpenItem> sorted =
        resp.data().stream()
            .sorted(
                Comparator.comparing(
                    (final NseOptionChainClient.PreOpenItem item) -> item.metadata().pChange(),
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());

    log.info("========== TOP 10 F&O STOCKS BY %CHNG ==========");
    log.info("{}, {}, {}", "#", "Symbol", "%CHNG");
    log.info("---------------------------------------------");
    int rank = 1;
    for (final NseOptionChainClient.PreOpenItem item : sorted) {
      if (rank > 10) break;
      final NseOptionChainClient.PreOpenMetadata m = item.metadata();
      log.info(
          "{}. {} {}%",
          rank,
          m.symbol(),
          m.pChange() != null ? m.pChange().setScale(1, java.math.RoundingMode.HALF_UP) : "0");
      rank++;
    }
    log.info("==============================================");

    log.info("Advances: {}", resp.advances());
    log.info("Declines: {}", resp.declines());
    log.info("Unchanged: {}", resp.unchanged());
    log.info("Total F&O stocks: {}", resp.data().size());

    assertNotNull(sorted.get(0).metadata().pChange(), "Top stock should have pChange");
  }
}
