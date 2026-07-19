package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration backtest of the Sniper strategy over the last ~1 month using REAL Yahoo Finance
 * historical data (NIFTY = ^NSEI, SENSEX = ^BSESN, VIX = ^INDIAVIX). No synthetic data. Requires
 * network access; skipped automatically when OFFLINE=true.
 */
@SpringBootTest
@ActiveProfiles("test")
class SniperBacktestIntegrationTest {

  @Autowired private SniperBacktest backtest;

  @Test
  @EnabledIfEnvironmentVariable(named = "OFFLINE", matches = "^(?i)false$|^$")
  void runOneMonthBacktest() {
    final LocalDate to = LocalDate.now();
    final LocalDate from = to.minusDays(30);
    final BacktestReport report = backtest.runBacktest(from, to);
    assertNotNull(report);
    System.out.println(report.summary());
    for (final BacktestReport.BacktestSignalRow row : report.signals()) {
      System.out.printf(
          "%s %s %s spot=%.2f PDH=%.2f PDL=%.2f ORH=%.2f ORL=%.2f -> %s pnl=%s%%%n",
          row.index(),
          row.day(),
          row.direction(),
          row.spot(),
          row.pdh(),
          row.pdl(),
          row.orHigh(),
          row.orLow(),
          row.trade().outcome(),
          row.trade().pnlPct());
    }
  }
}
