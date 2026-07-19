package com.kite.trading.service;

import com.kite.trading.dto.OhlcCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Historical backtest for the Sniper strategy over a date range using REAL market data from Yahoo
 * Finance (NIFTY = ^NSEI, SENSEX = ^BSESN). No synthetic data is generated and the 7-step rule set
 * is applied exactly as in {@link SniperStrategyService} (no rule amendments).
 *
 * <p>Per historical trading day the backtest:
 *
 * <ol>
 *   <li>Fetches the prior-session 1-day candle for PDH/PDL (Step 3).
 *   <li>Fetches that day's 1-minute candles to build the 9:15-9:45 Opening Range (Step 4) and a
 *       5-minute Supertrend (Step 7), using the per-bar price range as the volume proxy (Step 6).
 *   <li>Applies the India VIX gate (Step 1) when ^INDIAVIX history is available; otherwise the gate
 *       is skipped and logged. FII/DII context (Step 2) is logged as unavailable (no historical
 *       series) but does not block signals.
 * </ol>
 *
 * <p>Outcome measurement only (the live strategy never exits): on a valid signal a fixed 1R stop /
 * 2R target is projected on the underlying spot path for the rest of the day. R = 0.5%.
 */
@Service
public class SniperBacktest {

  private static final Logger logger = LoggerFactory.getLogger(SniperBacktest.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime OR_END = LocalTime.of(9, 45);
  private static final LocalTime SIGNAL_START = LocalTime.of(9, 50);
  private static final LocalTime MARKET_END = LocalTime.of(15, 30);

  private static final BigDecimal VIX_GATE = BigDecimal.valueOf(19);
  private static final int VOLUME_AVG_PERIOD = 20;
  private static final BigDecimal VOLUME_MIN_MULTIPLE = BigDecimal.valueOf(1.2);
  private static final int SUPERTREND_ATR_PERIOD = 10;
  private static final BigDecimal SUPERTREND_MULTIPLIER = BigDecimal.valueOf(3);
  private static final int FIVE_MIN_TICKS = 5;
  private static final BigDecimal RISK_R = BigDecimal.valueOf(0.005); // 0.5%

  private final NseOptionChainClient nseClient;

  public SniperBacktest(final NseOptionChainClient nseClient) {
    this.nseClient = nseClient;
  }

  public BacktestReport runBacktest(final LocalDate from, final LocalDate to) {
    final BacktestReport report = new BacktestReport(from, to);
    final List<String> indices = List.of("NIFTY", "SENSEX");
    for (final String index : indices) {
      try {
        backtestIndex(index, from, to, report);
      } catch (final Exception e) {
        logger.warn("Backtest failed for {}: {}", index, e.getMessage());
        report.addNote(index + ": backtest aborted - " + e.getMessage());
      }
    }
    report.finalizeReport();
    return report;
  }

  private void backtestIndex(
      final String index, final LocalDate from, final LocalDate to, final BacktestReport report) {
    // 1d candles for PDH/PDL + VIX, spanning [from-1day, to].
    final long dayFrom = from.minusDays(3).atStartOfDay(IST).toEpochSecond();
    final long dayTo = to.plusDays(1).atTime(MARKET_END).atZone(IST).toEpochSecond();
    final List<OhlcCandle> daily = nseClient.fetchIndexCandlesHistory(index, "1d", dayFrom, dayTo);
    final List<OhlcCandle> vixDaily =
        nseClient.fetchIndexCandlesHistory("INDIAVIX", "1d", dayFrom, dayTo);

    LocalDate day = from;
    while (!day.isAfter(to)) {
      if (day.getDayOfWeek().getValue() >= 6) {
        day = day.plusDays(1);
        continue;
      }
      backtestDay(index, day, daily, vixDaily, report);
      day = day.plusDays(1);
    }
  }

  private void backtestDay(
      final String index,
      final LocalDate day,
      final List<OhlcCandle> daily,
      final List<OhlcCandle> vixDaily,
      final BacktestReport report) {
    final OhlcCandle prevDay = findDailyCandle(daily, day.minusDays(1));
    if (prevDay == null) {
      report.addNote(index + " " + day + ": no prior-day 1d candle (PDH/PDL unavailable), skipped");
      return;
    }
    final BigDecimal pdh = prevDay.high();
    final BigDecimal pdl = prevDay.low();

    // Step 1: VIX gate (skip if unavailable).
    final OhlcCandle vix = findDailyCandle(vixDaily, day);
    boolean vixGateActive = false;
    if (vix != null && vix.close() != null) {
      if (vix.close().compareTo(VIX_GATE) > 0) {
        vixGateActive = true;
        report.recordVixSkip(index, day, vix.close());
      }
    } else {
      report.addNote(index + " " + day + ": India VIX history unavailable, gate skipped");
    }
    if (vixGateActive) {
      return;
    }

    // Step 6 baseline seeded from prior-day range (mirrors live).
    final BigDecimal baseRange = prevDay.high().subtract(prevDay.low()).max(BigDecimal.ZERO);

    // 1m candles for the day (matches live 1-min granularity: Opening Range + 5-min Supertrend +
    // volume).
    final long mFrom = day.atTime(MARKET_START).atZone(IST).toEpochSecond();
    final long mTo = day.atTime(MARKET_END).atZone(IST).toEpochSecond();
    final List<OhlcCandle> minutes = nseClient.fetchIndexCandlesHistory(index, "1m", mFrom, mTo);
    if (minutes.isEmpty()) {
      report.addNote(index + " " + day + ": no 1m candle history available, skipped");
      return;
    }

    // Step 4: Opening Range 9:15-9:45.
    BigDecimal orHigh = null;
    BigDecimal orLow = null;
    final List<BigDecimal> volumes = new ArrayList<>();
    for (int i = 0; i < VOLUME_AVG_PERIOD; i++) {
      volumes.add(baseRange);
    }
    for (final OhlcCandle c : minutes) {
      final LocalTime t = c.timestamp().toLocalTime();
      if (!t.isBefore(MARKET_START) && t.isBefore(OR_END) || t.equals(MARKET_START)) {
        orHigh = orHigh == null ? c.high() : max(orHigh, c.high());
        orLow = orLow == null ? c.low() : min(orLow, c.low());
      }
    }
    if (orHigh == null || orLow == null) {
      report.addNote(index + " " + day + ": insufficient 1m bars for Opening Range, skipped");
      return;
    }

    // Step 5-7: iterate 1m bars from 9:50, building 5-min candles for the Supertrend (Step 7).
    final Supertrend supertrend = new Supertrend(SUPERTREND_ATR_PERIOD, SUPERTREND_MULTIPLIER);
    OhlcCandle running5 = null;
    int tickCount5 = 0;
    int lastDirection = 0;
    for (final OhlcCandle c : minutes) {
      final LocalTime t = c.timestamp().toLocalTime();
      if (t.isBefore(SIGNAL_START) || t.isAfter(MARKET_END)) {
        continue;
      }
      final BigDecimal price = c.close();
      // Aggregate 1m bars into 5-min candles and advance the Supertrend on each completed 5m bar.
      if (running5 == null) {
        running5 = new OhlcCandle(c.timestamp(), price, c.high(), c.low(), price);
      } else {
        running5 =
            new OhlcCandle(
                running5.timestamp(),
                running5.open(),
                max(running5.high(), c.high()),
                min(running5.low(), c.low()),
                price);
      }
      tickCount5++;
      if (tickCount5 >= FIVE_MIN_TICKS) {
        tickCount5 = 0;
        supertrend.addCandle(running5);
        running5 = null;
      }

      // Volume proxy = bar range.
      final BigDecimal range = c.high().subtract(c.low()).max(BigDecimal.ZERO);
      volumes.add(range);
      final BigDecimal avgVol = average(volumes);
      final boolean volumeOk =
          avgVol.compareTo(BigDecimal.ZERO) > 0
              && range.compareTo(avgVol.multiply(VOLUME_MIN_MULTIPLE)) >= 0;

      // Breakout of the 4 levels.
      final boolean longBo = price.compareTo(pdh) > 0 || price.compareTo(orHigh) > 0;
      final boolean shortBo = price.compareTo(pdl) < 0 || price.compareTo(orLow) < 0;
      final String direction;
      if (longBo) {
        direction = lastDirection == -1 ? "LONG_REVERSAL" : "LONG_BREAKOUT";
      } else if (shortBo) {
        direction = lastDirection == 1 ? "SHORT_REVERSAL" : "SHORT_BREAKOUT";
      } else {
        final Supertrend.State st = supertrend.latest();
        lastDirection = st == null ? 0 : (st.trendUp() ? 1 : -1);
        continue;
      }
      final boolean isLong = direction.startsWith("LONG");
      final Supertrend.State st = supertrend.latest();
      final boolean stAligned = st != null && (isLong ? st.trendUp() : !st.trendUp());
      if (!volumeOk || !stAligned) {
        lastDirection = isLong ? 1 : -1;
        continue;
      }

      // All 7 steps passed -> record signal and measure outcome.
      final BacktestTrade trade = measureTrade(isLong, price, c.timestamp(), minutes, report);
      report.recordSignal(index, day, direction, price, pdh, pdl, orHigh, orLow, trade);
      // One signal per day per index (mirrors live single-signal behaviour).
      return;
    }
  }

  private BacktestTrade measureTrade(
      final boolean isLong,
      final BigDecimal entry,
      final LocalDateTime entryTime,
      final List<OhlcCandle> minutes,
      final BacktestReport report) {
    final BigDecimal target =
        isLong
            ? entry.add(entry.multiply(RISK_R).multiply(BigDecimal.valueOf(2)))
            : entry.subtract(entry.multiply(RISK_R).multiply(BigDecimal.valueOf(2)));
    final BigDecimal stop =
        isLong ? entry.subtract(entry.multiply(RISK_R)) : entry.add(entry.multiply(RISK_R));
    String outcome = "EOD";
    BigDecimal exitPrice = entry;
    for (final OhlcCandle c : minutes) {
      if (c.timestamp().isBefore(entryTime) || c.timestamp().equals(entryTime)) {
        continue;
      }
      final BigDecimal p = c.close();
      if (isLong) {
        if (p.compareTo(target) >= 0) {
          outcome = "TARGET";
          exitPrice = p;
          break;
        }
        if (p.compareTo(stop) <= 0) {
          outcome = "STOP";
          exitPrice = p;
          break;
        }
      } else {
        if (p.compareTo(target) <= 0) {
          outcome = "TARGET";
          exitPrice = p;
          break;
        }
        if (p.compareTo(stop) >= 0) {
          outcome = "STOP";
          exitPrice = p;
          break;
        }
      }
    }
    if ("EOD".equals(outcome)) {
      final boolean win = isLong ? exitPrice.compareTo(entry) > 0 : exitPrice.compareTo(entry) < 0;
      outcome = win ? "EOD_WIN" : "EOD_LOSS";
    }
    final BigDecimal pnlPct =
        exitPrice
            .subtract(entry)
            .divide(entry, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .multiply(isLong ? BigDecimal.ONE : BigDecimal.valueOf(-1));
    return new BacktestTrade(entry, exitPrice, target, stop, outcome, pnlPct);
  }

  private BigDecimal average(final List<BigDecimal> vols) {
    final int n = Math.min(VOLUME_AVG_PERIOD, vols.size());
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = vols.size() - n; i < vols.size(); i++) {
      sum = sum.add(vols.get(i));
    }
    return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
  }

  private OhlcCandle findDailyCandle(final List<OhlcCandle> daily, final LocalDate date) {
    OhlcCandle fallback = null;
    for (final OhlcCandle c : daily) {
      final LocalDate d = c.timestamp().toLocalDate();
      if (d.isEqual(date)) {
        return c;
      }
      if (fallback == null || d.isBefore(date)) {
        fallback = c;
      }
    }
    // If exact date missing (holiday/partial), use the most recent prior candle.
    return fallback;
  }

  private static BigDecimal max(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  private static BigDecimal min(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
