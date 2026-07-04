package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "strategy")
public class StrategyConfigProperties {

    private boolean enabled = false;
    private String niftyFuturesToken = "NSE:NIFTY 50";
    private BigDecimal deployedCapital = BigDecimal.valueOf(1000000);
    private int atrPeriod = 14;
    private BigDecimal trailingSlMultiplier = BigDecimal.valueOf(3);
    private BigDecimal initialSlPercentage = BigDecimal.valueOf(0.5);
    private int lotSize = 50;
    private int strikeInterval = 50;
    private String interval = "15minute";
    private int candleLookback = 30;

    public StrategyConfigProperties() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getNiftyFuturesToken() {
        return niftyFuturesToken;
    }

    public void setNiftyFuturesToken(final String niftyFuturesToken) {
        this.niftyFuturesToken = niftyFuturesToken;
    }

    public BigDecimal getDeployedCapital() {
        return deployedCapital;
    }

    public void setDeployedCapital(final BigDecimal deployedCapital) {
        this.deployedCapital = deployedCapital;
    }

    public int getAtrPeriod() {
        return atrPeriod;
    }

    public void setAtrPeriod(final int atrPeriod) {
        this.atrPeriod = atrPeriod;
    }

    public BigDecimal getTrailingSlMultiplier() {
        return trailingSlMultiplier;
    }

    public void setTrailingSlMultiplier(final BigDecimal trailingSlMultiplier) {
        this.trailingSlMultiplier = trailingSlMultiplier;
    }

    public BigDecimal getInitialSlPercentage() {
        return initialSlPercentage;
    }

    public void setInitialSlPercentage(final BigDecimal initialSlPercentage) {
        this.initialSlPercentage = initialSlPercentage;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(final int lotSize) {
        this.lotSize = lotSize;
    }

    public int getStrikeInterval() {
        return strikeInterval;
    }

    public void setStrikeInterval(final int strikeInterval) {
        this.strikeInterval = strikeInterval;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(final String interval) {
        this.interval = interval;
    }

    public int getCandleLookback() {
        return candleLookback;
    }

    public void setCandleLookback(final int candleLookback) {
        this.candleLookback = candleLookback;
    }
}
