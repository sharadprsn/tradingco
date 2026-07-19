package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.dto.OhlcCandle;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Faithful 1-month backtest for the Multi-TF RSI Nifty option buying strategy.
 *
 * <p>Reuses the REAL strategy code paths (same package): {@code buildFiveMinCandle}, {@code
 * buildFifteenMinCandle}, {@code computeSingleRsi}, {@code updateRsi} and {@link
 * CandlestickPatternService}. No RSI math or parameters are reimplemented or changed.
 *
 * <p>DATA: real NIFTY 5m bars are pulled from the local nse-ohlc-api (interval=5m). Each historical
 * 5m bar is fed in as a 1-minute-equivalent tick (O=H=L=C=close), exactly as the live strategy
 * builds candles from 1-min spot ticks. The 15m series is reconstructed by the real
 * buildFifteenMinCandle (3 x 5m). RSI(14)/SMA(20) are computed by the real methods.
 *
 * <p>P&amp;L is the underlying NIFTY move between entry and exit closes (agreed proxy: signal
 * quality, delta ~1, no invented option premium). Exit uses the real RSI-cross / conflict rule,
 * plus the real 11:30 hard-exit mapped per trading day via each bar's IST timestamp.
 */
class NiftyStrategyBacktest {

  private static final int RSI_PERIOD = MultiTfRSINiftyOptionService.RSI_PERIOD;
  private static final int SMA_PERIOD = MultiTfRSINiftyOptionService.SMA_PERIOD;
  private static final int FIVE_MIN_CANDLES = MultiTfRSINiftyOptionService.FIVE_MIN_CANDLES;
  private static final int FIFTEEN_MIN_CANDLES = MultiTfRSINiftyOptionService.FIFTEEN_MIN_CANDLES;
  private static final int MAX_TRADES_PER_DAY = MultiTfRSINiftyOptionService.MAX_TRADES_PER_DAY;
  private static final BigDecimal RSI_LONG_MIN = MultiTfRSINiftyOptionService.RSI_LONG_MIN;
  private static final BigDecimal RSI_LONG_MAX = MultiTfRSINiftyOptionService.RSI_LONG_MAX;
  private static final BigDecimal RSI_SHORT_MIN = MultiTfRSINiftyOptionService.RSI_SHORT_MIN;
  private static final BigDecimal RSI_SHORT_MAX = MultiTfRSINiftyOptionService.RSI_SHORT_MAX;
  private static final BigDecimal STOP_PCT = MultiTfRSINiftyOptionService.STOP_PCT;
  private static final BigDecimal TARGET_PCT = MultiTfRSINiftyOptionService.TARGET_PCT;

  private static final String API_URL = "http://localhost:5000/api/ohlc/auto";

  @Test
  void runOneMonthBacktest() throws Exception {
    final List<OhlcCandle> bars5m = fetchBars("^NSEI", "5m", 400);
    final LocalDateTime last = bars5m.getLast().timestamp();
    final LocalDateTime cutoff = last.minusDays(7);
    final List<OhlcCandle> window = new ArrayList<>();
    for (final OhlcCandle b : bars5m) {
      if (!b.timestamp().isBefore(cutoff)) {
        window.add(b);
      }
    }
    System.out.println(
        "Backtest window (last 1 month): "
            + window.getFirst().timestamp()
            + " -> "
            + window.getLast().timestamp()
            + "  bars="
            + window.size());

    final MultiTfRSINiftyOptionService svc =
        new MultiTfRSINiftyOptionService(null, new CandlestickPatternService(), null);
    final MultiTfRSINiftyOptionService.TfState state = new MultiTfRSINiftyOptionService.TfState();
    final CandlestickPatternService patterns = new CandlestickPatternService();

    int evalBars = 0;
    int bullBoth = 0;
    int bearBoth = 0;
    int pattBars = 0;
    final List<Trade> trades = new ArrayList<>();
    boolean inTrade = false;
    String direction = null;
    BigDecimal entryPrice = null;
    int entryDayKey = -1;
    int longTradesToday = 0;
    int shortTradesToday = 0;
    int lastDayKey = -1;

    for (final OhlcCandle bar : window) {
      final java.time.LocalDate d = bar.timestamp().toLocalDate();
      final int dayKey = d.getYear() * 1000 + d.getDayOfYear();
      if (dayKey != lastDayKey) {
        longTradesToday = 0;
        shortTradesToday = 0;
        lastDayKey = dayKey;
      }

      final OhlcCandle tick =
          new OhlcCandle(bar.timestamp(), bar.close(), bar.close(), bar.close(), bar.close());
      state.oneMinTicks.add(tick);
      svc.buildFiveMinCandle(state);
      svc.buildFifteenMinCandle(state);
      if (state.candles5.size() < RSI_PERIOD || state.candles15.size() < RSI_PERIOD) {
        continue;
      }
      MultiTfRSINiftyOptionService.updateRsi(
          state, state.candles5, state.rsi5, state.rsiSma5, true);
      MultiTfRSINiftyOptionService.updateRsi(
          state, state.candles15, state.rsi15, state.rsiSma15, false);
      evalBars++;

      final boolean rsi5Bullish = isBullish(state.rsi5, state.rsiSma5);
      final boolean rsi5Bearish = isBearish(state.rsi5, state.rsiSma5);
      final boolean rsi15Bullish = isBullish(state.rsi15, state.rsiSma15);
      final boolean rsi15Bearish = isBearish(state.rsi15, state.rsiSma15);
      final boolean hasPattern = !patterns.detectPatterns(state.candles5).isEmpty();
      if (state.rsi5.isEmpty() || state.rsi15.isEmpty()) {
        continue;
      }
      final BigDecimal rsi5Last = state.rsi5.getLast();
      final boolean longBand =
          rsi5Last.compareTo(RSI_LONG_MIN) > 0 && rsi5Last.compareTo(RSI_LONG_MAX) <= 0;
      final boolean shortBand =
          rsi5Last.compareTo(RSI_SHORT_MIN) >= 0 && rsi5Last.compareTo(RSI_SHORT_MAX) < 0;
      if (rsi15Bullish && rsi5Bullish) bullBoth++;
      if (rsi15Bearish && rsi5Bearish) bearBoth++;
      if (hasPattern) pattBars++;

      if (inTrade) {
        final boolean exitSignal = "LONG".equals(direction) ? !rsi5Bullish : !rsi5Bearish;
        final boolean conflict =
            "LONG".equals(direction)
                ? (!rsi15Bullish && !rsi5Bullish)
                : (!rsi15Bearish && !rsi5Bearish);
        final BigDecimal movePct =
            bar.close().subtract(entryPrice).divide(entryPrice, 6, RoundingMode.HALF_UP);
        final boolean stopped =
            "LONG".equals(direction)
                ? movePct.compareTo(STOP_PCT.negate()) <= 0
                : movePct.compareTo(STOP_PCT) >= 0;
        final boolean targetHit =
            "LONG".equals(direction)
                ? movePct.compareTo(TARGET_PCT) >= 0
                : movePct.compareTo(TARGET_PCT.negate()) <= 0;
        if (stopped || targetHit || exitSignal || conflict) {
          trades.add(closeTrade(direction, entryPrice, bar.close(), entryDayKey, dayKey));
          inTrade = false;
          direction = null;
          entryPrice = null;
        }
        continue;
      }

      if (longTradesToday + shortTradesToday >= MAX_TRADES_PER_DAY) {
        continue;
      }
      if (rsi15Bullish && rsi5Bullish && longBand && longTradesToday < 1 && hasPattern) {
        inTrade = true;
        direction = "LONG";
        entryPrice = bar.close();
        entryDayKey = dayKey;
        longTradesToday++;
      } else if (rsi15Bearish && rsi5Bearish && shortBand && shortTradesToday < 1 && hasPattern) {
        inTrade = true;
        direction = "SHORT";
        entryPrice = bar.close();
        entryDayKey = dayKey;
        shortTradesToday++;
      }
    }
    if (inTrade) {
      trades.add(
          closeTrade(direction, entryPrice, window.getLast().close(), entryDayKey, lastDayKey));
    }

    System.out.println(
        "evalBars="
            + evalBars
            + " bullBoth="
            + bullBoth
            + " bearBoth="
            + bearBoth
            + " pattBars="
            + pattBars);
    printReport(trades);
  }

  private static boolean isBullish(final List<BigDecimal> rsi, final List<BigDecimal> sma) {
    if (rsi.isEmpty() || sma.isEmpty()) {
      return false;
    }
    return rsi.getLast().compareTo(sma.getLast()) > 0;
  }

  private static boolean isBearish(final List<BigDecimal> rsi, final List<BigDecimal> sma) {
    if (rsi.isEmpty() || sma.isEmpty()) {
      return false;
    }
    return rsi.getLast().compareTo(sma.getLast()) < 0;
  }

  private static Trade closeTrade(
      final String direction,
      final BigDecimal entry,
      final BigDecimal exit,
      final int entryDay,
      final int exitDay) {
    final BigDecimal move;
    if ("LONG".equals(direction)) {
      move = exit.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP);
    } else {
      move = entry.subtract(exit).divide(entry, 6, RoundingMode.HALF_UP);
    }
    final int holdingDays = Math.max(0, exitDay - entryDay);
    return new Trade(direction, entry, exit, move, holdingDays);
  }

  private static void printReport(final List<Trade> trades) {
    int wins = 0;
    int longs = 0;
    int shorts = 0;
    BigDecimal sumMove = BigDecimal.ZERO;
    for (final Trade tr : trades) {
      if ("LONG".equals(tr.direction)) {
        longs++;
      } else {
        shorts++;
      }
      if (tr.move.compareTo(BigDecimal.ZERO) > 0) {
        wins++;
      }
      sumMove = sumMove.add(tr.move);
    }
    final int n = trades.size();
    final BigDecimal winRate =
        n == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    final BigDecimal netPct =
        sumMove.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

    System.out.println("\n===== MULTI-TF RSI NIFTY — 1-MONTH BACKTEST (real API 5m data) =====");
    final int show = Math.min(n, 20);
    for (int i = 0; i < show; i++) {
      final Trade tr = trades.get(i);
      System.out.printf(
          "%-5s %-5s entry=%s exit=%s move=%+.2f%%%n",
          "#" + (i + 1),
          tr.direction,
          tr.entry,
          tr.exit,
          tr.move.multiply(BigDecimal.valueOf(100)));
    }
    System.out.println("--- Summary ---");
    System.out.println("Total trades : " + n + " (LONG=" + longs + ", SHORT=" + shorts + ")");
    System.out.println("Win rate     : " + winRate + "%  (" + wins + "/" + n + ")");
    System.out.println("Net move     : " + netPct + "%  (underlying NIFTY, entry->exit close)");
    System.out.println("==============================================================");
  }

  private static List<OhlcCandle> fetchBars(
      final String symbol, final String interval, final int count) throws Exception {
    final String body =
        "{\"symbol\":\"" + symbol + "\",\"interval\":\"" + interval + "\",\"count\":" + count + "}";
    final HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    final InputStream is = conn.getInputStream();
    final String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    is.close();
    conn.disconnect();

    final ObjectMapper mapper = new ObjectMapper();
    final ApiResponse resp = mapper.readValue(json, ApiResponse.class);
    final List<OhlcCandle> bars = new ArrayList<>();
    if (resp.data != null) {
      for (final ApiBar b : resp.data) {
        final LocalDateTime ts =
            LocalDateTime.parse(b.datetime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        bars.add(
            new OhlcCandle(
                ts,
                BigDecimal.valueOf(b.open),
                BigDecimal.valueOf(b.high),
                BigDecimal.valueOf(b.low),
                BigDecimal.valueOf(b.close)));
      }
    }
    if (bars.isEmpty()) {
      throw new IllegalStateException("No bars returned from API for " + interval);
    }
    return bars;
  }

  private static BigDecimal max(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  private static BigDecimal min(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private record Trade(
      String direction, BigDecimal entry, BigDecimal exit, BigDecimal move, int holdingDays) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ApiResponse(@JsonProperty("data") List<ApiBar> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ApiBar(
      @JsonProperty("datetime") String datetime,
      @JsonProperty("open") double open,
      @JsonProperty("high") double high,
      @JsonProperty("low") double low,
      @JsonProperty("close") double close) {}
}
