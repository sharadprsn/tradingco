package com.kite.trading.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "stock-strategy")
public class StockStrategyConfig {

  private boolean enabled = false;
  private List<String> watchlist = List.of();
  private int candleIntervalMinutes = 5;
  private int ignoreFirstCandles = 3;
  private double minRiskReward = 2.0;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getWatchlist() {
    return watchlist;
  }

  public void setWatchlist(final List<String> watchlist) {
    this.watchlist = watchlist;
  }

  public int getCandleIntervalMinutes() {
    return candleIntervalMinutes;
  }

  public void setCandleIntervalMinutes(final int candleIntervalMinutes) {
    this.candleIntervalMinutes = candleIntervalMinutes;
  }

  public int getIgnoreFirstCandles() {
    return ignoreFirstCandles;
  }

  public void setIgnoreFirstCandles(final int ignoreFirstCandles) {
    this.ignoreFirstCandles = ignoreFirstCandles;
  }

  public double getMinRiskReward() {
    return minRiskReward;
  }

  public void setMinRiskReward(final double minRiskReward) {
    this.minRiskReward = minRiskReward;
  }
}
